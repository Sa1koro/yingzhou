package tvbox.engine.jar

import org.objectweb.asm.ClassReader
import tvbox.engine.core.spider.SpiderNativeOperation
import java.io.File
import java.io.IOException
import java.net.URLClassLoader
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.CodeSource
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.jar.JarFile
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class AndroidJniLoader private constructor(
    private val archive: File,
    private val dataDir: File,
    private val libraries: List<Library>,
) : URLClassLoader(arrayOf(archive.toURI().toURL()), AndroidJniLoader::class.java.classLoader) {
    private data class Library(val entry: String, val size: Long, val arm64: Boolean) {
        val name = entry.substringAfterLast('/')
    }

    private class GuestSlot(val runtime: AndroidJniGuest, var synchronizedLibraries: Int = 0)

    private val guests = mutableListOf<GuestSlot>()
    private val registeredLibraries = linkedMapOf<String, Library>()
    private var depth = 0
    private var guestRoot: Path? = null
    private var guestAbi: Boolean? = null
    private var loadFailure: UnsatisfiedLinkError? = null
    private var closed = false
    private val extracted = mutableMapOf<Library, ByteArray>()
    private val entries = JarFile(archive, true, ZipFile.OPEN_READ, JarFile.runtimeVersion())
    private val sourceUrl = archive.toURI().toURL()

    @Synchronized private fun arm64() = guestAbi ?: libraries.any { it.arm64 }

    @Synchronized override fun findClass(name: String): Class<*> {
        if (closed) throw ClassNotFoundException(name)
        val entry = entries.getJarEntry(name.replace('.', '/') + ".class") ?: return super.findClass(name)
        val original = entries.getInputStream(entry).use { it.readBytes() }
        val manifest = entries.manifest
        val packageName = name.substringBeforeLast('.', "")
        val sealed = manifest?.getAttributes(packageName.replace('.', '/') + "/")?.getValue("Sealed")
            ?: manifest?.mainAttributes?.getValue("Sealed")
        if (entry.codeSigners != null || sealed.equals("true", true)) return super.findClass(name)
        if (packageName.isNotEmpty() && getDefinedPackage(packageName) == null) {
            if (manifest != null) definePackage(packageName, manifest, sourceUrl)
            else definePackage(packageName, null, null, null, null, null, null, null)
        }
        val bytes = AndroidJniBytecode.adapt(original)
        return defineClass(name, bytes, 0, bytes.size, CodeSource(sourceUrl, entry.codeSigners))
    }

    private fun bytes(library: Library): ByteArray = ZipFile(archive).use { zip ->
        zip.getInputStream(zip.getEntry(library.entry)).use { input ->
            input.readNBytes(library.size.toInt() + 1).also { if (it.size.toLong() != library.size) fail("incomplete packaged library") }
        }
    }

    private fun asset(name: String): ByteArray? = readArchiveEntry(name, "assets/")

    internal fun resource(name: String): ByteArray? = readArchiveEntry(name, "")

    private fun readArchiveEntry(name: String, prefix: String): ByteArray? {
        if (name.length !in 1..512 || '\\' in name || '\u0000' in name || name.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        val entry = entries.getJarEntry(prefix + name) ?: return null
        if (entry.isDirectory || entry.size !in 0..maxLibrary) return null
        return entries.getInputStream(entry).use { it.readNBytes(entry.size.toInt() + 1) }.takeIf { it.size.toLong() == entry.size }
    }

    private fun classHierarchy(name: String): GuestClassHierarchy? {
        val entry = entries.getJarEntry("$name.class") ?: return null
        if (entry.size !in 1..maxLibrary) return null
        val metadata = entries.getInputStream(entry).use { ClassReader(it.readNBytes(entry.size.toInt() + 1)) }
        return GuestClassHierarchy(metadata.superName, metadata.interfaces.asList())
    }

    private fun archiveClass(name: String): Class<*>? {
        if (entries.getJarEntry("$name.class") == null) return null
        val resolved = loadClass(name.replace('/', '.'))
        return resolved.takeIf { it.classLoader === this }
    }

    private fun selectPath(path: String): Library {
        val candidate = File(path).toPath()
        if (!candidate.isAbsolute || !Files.isRegularFile(candidate)) fail("library is outside spider storage")
        val root = dataDir.toPath().toAbsolutePath().normalize()
        val realPath = try {
            candidate.toRealPath().also { if (!it.startsWith(root.toRealPath())) fail("library is outside spider storage") }
        } catch (denied: AccessDeniedException) {
            unlinkedStoragePath(root, candidate) ?: throw denied
        }
        val candidates = libraries.filter { it.size == Files.size(realPath) }
        if (candidates.isEmpty()) fail("library is not packaged in this spider")
        val digest = MessageDigest.getInstance("SHA-256")
        val fingerprint = Files.newInputStream(realPath, NOFOLLOW_LINKS).use { digest.digest(it.readNBytes(candidates.first().size.toInt() + 1)) }
        return candidates.firstOrNull { fingerprint.contentEquals(extracted.getOrPut(it) { digest.digest(bytes(it)) }) }
            ?: fail("library differs from the packaged copy")
    }

    @Synchronized private fun loadGuest(value: String, byName: Boolean) {
        val method = if (byName) "loadLibrary" else "load"
        try {
            withGuestError("guest initialization failed", "JNI method java/lang/System->$method(Ljava/lang/String;)V") {
                registerLibrary(value, byName)
            }
            if (depth == 0) loadFailure = null
        } catch (error: UnsatisfiedLinkError) {
            if (loadFailure == null) loadFailure = error
            throw error
        }
    }

    private fun registerLibrary(value: String, byName: Boolean) {
        if (closed) fail("archive is closed")
        val library = if (byName) {
            val candidates = libraries.filter { it.name == "lib$value.so" }
            candidates.firstOrNull { it.arm64 == arm64() } ?: candidates.firstOrNull() ?: fail("library is not packaged in this spider")
        } else try {
            selectPath(value)
        } catch (error: IOException) {
            throw NativeLoadFailure("library path inaccessible", "Cannot access the Android JNI library path", error)
        }
        if (guestAbi != null && library.arm64 != guestAbi) fail("cannot mix armeabi-v7a and arm64-v8a in one guest")
        val previous = registeredLibraries[library.name]
        if (previous != null && previous != library && !bytes(previous).contentEquals(bytes(library))) fail("conflicting packaged libraries")
        if (guestAbi == null) guestAbi = library.arm64
        registeredLibraries.putIfAbsent(library.name, library)
        if (depth == 0) withRuntime {}
    }

    private fun <T> withRuntime(action: (AndroidJniGuest) -> T): T {
        if (depth >= MAX_GUESTS) throw NativeLoadFailure("guest nesting limit", "Nested Android JNI call limit reached")
        val abi = checkNotNull(guestAbi)
        val slot = guests.getOrNull(depth) ?: run {
            val root = guestRoot ?: Files.createTempDirectory("yingzhou-jni-").also { guestRoot = it }
            val runtime = AndroidJniGuest(
                abi, root.toFile(), ::asset, { entries.getJarEntry("$it.class") != null }, ::classHierarchy, ::archiveClass,
                GuestStorage(dataDir.toPath()), ::resource, this,
            )
            GuestSlot(runtime).also(guests::add)
        }
        depth++
        try {
            syncLibraries(slot)
            val result = action(slot.runtime)
            if (depth == 1) syncLibraries(slot)
            return result
        } finally {
            depth--
        }
    }

    private fun syncLibraries(slot: GuestSlot) {
        while (slot.synchronizedLibraries < registeredLibraries.size) {
            val library = registeredLibraries.values.elementAt(slot.synchronizedLibraries)
            slot.runtime.load(library.name, bytes(library))
            slot.synchronizedLibraries++
        }
    }

    @Synchronized private fun call(owner: Class<*>, signature: String, receiver: Any?, args: Array<Any?>): Any? {
        if (closed || guestAbi == null) {
            throw UnsatisfiedLinkError("Android JNI: guest unavailable").also { it.initCause(loadFailure) }
        }
        val name = owner.name.replace('.', '/')
        return withGuestError("native call failed", "JNI method $name->$signature") {
            withRuntime { it.invoke(name, signature, receiver, args) }
        }
    }

    @Synchronized override fun close() {
        if (closed) return
        closed = true
        try {
            var failure: Exception? = null
            for (slot in guests.asReversed()) {
                try { slot.runtime.close() } catch (error: Exception) {
                    failure?.addSuppressed(error) ?: run { failure = error }
                }
            }
            failure?.let { throw it }
        } finally {
            guests.clear()
            try { guestRoot?.let { Files.deleteIfExists(it) } } finally {
                try { entries.close() } finally { super.close() }
            }
        }
    }

    companion object {
        private const val maxLibrary = 32L * 1024 * 1024
        private const val MAX_GUESTS = 4
        private val caller = StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

        internal fun unlinkedStoragePath(root: Path, candidate: Path): Path? {
            if (!root.isAbsolute || root != root.normalize() || candidate != candidate.normalize() || candidate == root || !candidate.startsWith(root)) return null
            val rootAttributes = Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!rootAttributes.isDirectory || rootAttributes.isOther || rootAttributes.isSymbolicLink) return null
            var current = root
            for (part in root.relativize(candidate)) {
                current = current.resolve(part)
                val attributes = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (attributes.isOther || attributes.isSymbolicLink) return null
                if (current == candidate) return candidate.takeIf { attributes.isRegularFile }
                if (!attributes.isDirectory) return null
            }
            return null
        }

        private fun inspect(zip: ZipFile, entry: ZipEntry): Library? {
            if (!entry.name.endsWith(".so") || entry.size !in 20L..maxLibrary) return null
            val header = zip.getInputStream(entry).use { it.readNBytes(20) }
            if (header.size != 20 || !header.copyOfRange(0, 4).contentEquals(byteArrayOf(127, 69, 76, 70)) ||
                header[5].toInt() != 1 || header[16].toInt() != 3 || header[17].toInt() != 0 || header[19].toInt() != 0) return null
            val arm64 = when (header[4].toInt() to (header[18].toInt() and 255)) {
                1 to 40 -> false
                2 to 183 -> true
                else -> return null
            }
            return Library(entry.name, entry.size, arm64)
        }

        internal fun open(archive: File, dataDir: File, enabled: Boolean = System.getenv("YINGZHOU_ANDROID_JNI") == "1"): URLClassLoader {
            if (enabled) {
                val libraries = ZipFile(archive).use { zip -> zip.entries().asSequence().mapNotNull { inspect(zip, it) }.toList() }
                if (libraries.isNotEmpty()) return AndroidJniLoader(archive, dataDir, libraries)
            }
            return URLClassLoader(arrayOf(archive.toURI().toURL()), AndroidJniLoader::class.java.classLoader)
        }

        private fun fail(message: String): Nothing = throw UnsatisfiedLinkError("Android JNI: $message")
        private inline fun <T> withGuestError(operation: String, symbol: String? = null, run: () -> T): T = try {
            run()
        } catch (error: Throwable) {
            if (error is VirtualMachineError || error is ThreadDeath || error is CancellationException || error is InterruptedException) throw error
            val reason = when {
                error is NativeLoadFailure -> error.reason
                error is UnsatisfiedLinkError && error.message.orEmpty().startsWith("Android JNI: ") -> error.message!!.removePrefix("Android JNI: ")
                error.message == "Android JNI guest timed out" -> "guest timed out"
                error.message == "Android JNI guest unavailable" -> "guest unavailable"
                error.message.orEmpty().startsWith("Unsupported Android JNI ") -> "unsupported signature"
                else -> operation
            }
            val cause = if (symbol == null || reason == "guest unavailable") error else SpiderNativeOperation(symbol, error)
            throw UnsatisfiedLinkError("Android JNI: $reason").also { it.initCause(cause) }
        }
        private fun loader(owner: Class<*>) = owner.classLoader as? AndroidJniLoader ?: fail("unknown spider archive")
        @JvmStatic fun callerIs64Bit(): Boolean = caller.walk { frames ->
            frames.map { it.declaringClass.classLoader }.filter { it is AndroidJniLoader }.findFirst().map { (it as AndroidJniLoader).arm64() }.orElse(false)
        }
        @JvmStatic fun abiField(owner: Class<*>, field: String): Any {
            val loader = loader(owner)
            val abi = if (loader.arm64()) "arm64-v8a" else "armeabi-v7a"
            val abis = (listOf(abi) + loader.libraries.map { if (it.arm64) "arm64-v8a" else "armeabi-v7a" }).distinct()
            return when (field) {
                "CPU_ABI" -> abi
                "CPU_ABI2" -> abis.getOrNull(1).orEmpty()
                "SUPPORTED_64_BIT_ABIS" -> abis.filter { it == "arm64-v8a" }.toTypedArray()
                "SUPPORTED_32_BIT_ABIS" -> abis.filter { it == "armeabi-v7a" }.toTypedArray()
                else -> abis.toTypedArray()
            }
        }
        @JvmStatic fun loadLibrary(name: String, owner: Class<*>) = loader(owner).loadGuest(name, true)
        @JvmStatic fun load(path: String, owner: Class<*>) = loader(owner).loadGuest(path, false)
        @JvmStatic fun invokeNative(owner: Class<*>, signature: String, receiver: Any?, args: Array<Any?>): Any? = loader(owner).call(owner, signature, receiver, args)
    }
}
