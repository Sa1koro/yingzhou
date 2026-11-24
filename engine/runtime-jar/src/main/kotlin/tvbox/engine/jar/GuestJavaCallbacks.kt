package tvbox.engine.jar

import com.github.unidbg.linux.android.dvm.AbstractJni
import com.github.unidbg.linux.android.dvm.BaseVM
import com.github.unidbg.linux.android.dvm.DvmClass
import com.github.unidbg.linux.android.dvm.DvmField
import com.github.unidbg.linux.android.dvm.DvmMethod
import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.Jni
import com.github.unidbg.linux.android.dvm.StringObject
import com.github.unidbg.linux.android.dvm.VaList
import com.github.unidbg.linux.android.dvm.VarArg
import com.github.unidbg.linux.android.dvm.api.AssetManager
import com.github.unidbg.linux.android.dvm.array.ByteArray as GuestBytes
import dalvik.system.DexClassLoader
import org.objectweb.asm.Type
import tvbox.engine.core.spider.SpiderNativeOperation
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.DosFileAttributeView
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermission

internal class GuestJavaCallbacks(
    private val ownsClass: (String) -> Boolean,
    private val storage: GuestStorage,
    private val archiveResource: (String) -> ByteArray?,
    private val archiveClassLoader: ClassLoader?,
) : AbstractJni() {
    private val loaderToken = Any()
    private val context = GuestContext()
    private val outputs = mutableSetOf<GuestOutput>()
    private val dexLoaders = mutableSetOf<DexClassLoader>()
    private val dexClasses = mutableMapOf<String, Class<*>>()
    private var guestLoader: DvmObject<*>? = null
    var failure: NativeLoadFailure? = null
        private set
    var lastCallback: String? = null
        private set
    private var resourceReads = 0L
    private var resourceBytes = 0L
    private var resourceEof = 0L

    fun resetFailure() {
        failure = null
        lastCallback = null
        resourceReads = 0
        resourceBytes = 0
        resourceEof = 0
    }

    fun readProgress(): String = "reads=$resourceReads bytes=$resourceBytes eof=$resourceEof"

    fun abortOpenOutputs() {
        outputs.forEach { runCatching { it.abort() } }
        outputs.clear()
    }

    fun releaseDexLoaders() {
        dexLoaders.forEach { runCatching { it.close() } }
        dexLoaders.clear()
        dexClasses.clear()
    }

    fun ownsReturnedReference(value: Any): Boolean = value is DexClassLoader && value in dexLoaders

    fun dexClass(name: String): Class<*>? = dexClasses[name]

    val bridge: Jni = Proxy.newProxyInstance(Jni::class.java.classLoader, arrayOf(Jni::class.java)) { _, method, input ->
        val arguments = input.orEmpty()
        val signature = when (val member = arguments.getOrNull(2)) {
            is String -> member.takeUnless { method.name.startsWith("accept") }
            is DvmMethod -> member.signature
            is DvmField -> member.toString()
            else -> null
        }
        if (signature != null) lastCallback = signature
        try {
            method.invoke(this, *arguments)
        } catch (wrapped: InvocationTargetException) {
            val error = wrapped.targetException
            if (signature == null || error is VirtualMachineError || error is ThreadDeath) throw error
            val cause = SpiderNativeOperation("JNI callback $signature", error)
            val reason = if (error is UnsupportedOperationException) "unsupported Java callback" else "Java callback failed"
            val callbackFailure = NativeLoadFailure(reason, "Android JNI Java callback failed", cause)
            if (failure == null) failure = callbackFailure
            throw callbackFailure
        }
    } as Jni

    override fun callObjectMethod(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VarArg): DvmObject<*>? =
        call(vm, receiver, signature, args) { super.callObjectMethod(vm, receiver, signature, args) }

    override fun callObjectMethodV(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VaList): DvmObject<*>? =
        call(vm, receiver, signature, args) { super.callObjectMethodV(vm, receiver, signature, args) }

    override fun callBooleanMethod(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VarArg): Boolean =
        fileOperation(receiver, signature, args) ?: super.callBooleanMethod(vm, receiver, signature, args)

    override fun callBooleanMethodV(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VaList): Boolean =
        fileOperation(receiver, signature, args) ?: super.callBooleanMethodV(vm, receiver, signature, args)

    private fun fileOperation(receiver: DvmObject<*>, signature: String, args: VarArg): Boolean? {
        val path = receiver.value as? GuestPath ?: return null
        val absolute = path.getAbsolutePath()
        return when (signature) {
            "java/io/File->exists()Z" -> storage.attributes(absolute) != null
            "java/io/File->isDirectory()Z" -> storage.attributes(absolute)?.isDirectory == true
            "java/io/File->isFile()Z" -> storage.attributes(absolute)?.isRegularFile == true
            "java/io/File->setWritable(Z)Z" -> storage.setWritable(absolute, args.getIntArg(0) != 0, ownerOnly = true)
            "java/io/File->setWritable(ZZ)Z" -> storage.setWritable(absolute, args.getIntArg(0) != 0, args.getIntArg(1) != 0)
            "java/io/File->delete()Z" -> storage.delete(absolute)
            else -> null
        }
    }

    override fun callIntMethod(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VarArg): Int =
        streamRead(receiver, signature, args) ?: super.callIntMethod(vm, receiver, signature, args)

    override fun callIntMethodV(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VaList): Int =
        streamRead(receiver, signature, args) ?: super.callIntMethodV(vm, receiver, signature, args)

    override fun callVoidMethod(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VarArg) {
        if (!streamOperation(receiver, signature, args)) super.callVoidMethod(vm, receiver, signature, args)
    }

    override fun callVoidMethodV(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VaList) {
        if (!streamOperation(receiver, signature, args)) super.callVoidMethodV(vm, receiver, signature, args)
    }

    private fun streamRead(receiver: DvmObject<*>, signature: String, args: VarArg): Int? {
        val resource = receiver.value as? GuestResource ?: return null
        val read = when (signature) {
            "java/io/InputStream->read()I" -> resource.input().read()
            "java/io/InputStream->read([B)I" -> resource.input().read(args.getObjectArg<GuestBytes>(0).value)
            "java/io/InputStream->read([BII)I" -> resource.input().read(args.getObjectArg<GuestBytes>(0).value, args.getIntArg(1), args.getIntArg(2))
            "java/io/InputStream->available()I" -> return resource.input().available()
            else -> return null
        }
        resourceReads++
        if (read < 0) resourceEof++ else resourceBytes += if (signature.endsWith("read()I")) 1 else read
        return read
    }

    private fun streamOperation(receiver: DvmObject<*>, signature: String, args: VarArg): Boolean {
        val resource = receiver.value as? GuestResource
        val owner = signature.substringBefore("->")
        val member = signature.substringAfter("->")
        if (resource != null && member == "close()V" && (owner == "java/io/InputStream" || owner in CLOSEABLE_TYPES)) {
            resource.close()
            return true
        }
        val output = receiver.value as? GuestOutput ?: return false
        if (owner !in OUTPUT_TYPES && !(owner in CLOSEABLE_TYPES && member == "close()V") &&
            !(owner == "java/io/Flushable" && member == "flush()V")) return false
        when (member) {
            "write(I)V" -> output.write(byteArrayOf(args.getIntArg(0).toByte()), 0, 1)
            "write([B)V" -> args.getObjectArg<GuestBytes>(0).value.let { output.write(it, 0, it.size) }
            "write([BII)V" -> output.write(args.getObjectArg<GuestBytes>(0).value, args.getIntArg(1), args.getIntArg(2))
            "flush()V" -> output.flush()
            "close()V" -> try { output.close() } finally { outputs.remove(output) }
            else -> return false
        }
        return true
    }

    private class GuestResource(bytes: ByteArray) {
        private var stream: ByteArrayInputStream? = ByteArrayInputStream(bytes)

        fun input(): ByteArrayInputStream = stream ?: throw IOException("Archive resource stream is closed")
        fun close() { stream = null }
    }

    override fun newObject(vm: BaseVM, type: DvmClass, signature: String, args: VarArg): DvmObject<*>? =
        construct(vm, signature, args) ?: super.newObject(vm, type, signature, args)

    override fun newObjectV(vm: BaseVM, type: DvmClass, signature: String, args: VaList): DvmObject<*>? =
        construct(vm, signature, args) ?: super.newObjectV(vm, type, signature, args)

    private fun construct(vm: BaseVM, signature: String, args: VarArg): DvmObject<*>? {
        if (signature == DEX_CONSTRUCTOR) {
            val parent = args.getObjectArg<DvmObject<*>>(3)
            val actualParent = archiveClassLoader?.takeIf { parent != null && isArchiveLoader(parent) }
                ?: throw IOException("Desktop DEX loader requires the spider archive parent")
            if (dexLoaders.size >= 4) throw IOException("Too many guest DEX loaders")
            val requested = args.getObjectArg<StringObject>(0)?.value ?: throw IOException("Missing guest DEX path")
            val path = storage.dexFile(GuestPath(requested).getAbsolutePath())
            val child = DexClassLoader(path.toString(), null as java.io.File?, null, actualParent)
            if (!child.isFunctional) {
                child.close()
                throw IOException("Desktop DEX conversion did not produce loadable classes")
            }
            dexLoaders.add(child)
            return vm.resolveClass("dalvik/system/DexClassLoader").newObject(child)
        }
        val path = when (signature) {
            "java/io/FileOutputStream-><init>(Ljava/lang/String;)V" -> GuestPath(requireNotNull(args.getObjectArg<StringObject>(0).value))
            "java/io/FileOutputStream-><init>(Ljava/io/File;)V" -> args.getObjectArg<DvmObject<*>>(0).value as? GuestPath
                ?: throw IOException("Invalid guest file reference")
            else -> null
        }
        if (path != null) {
            if (outputs.size >= 4) throw IOException("Too many open guest output streams")
            val output = storage.openOutput(path.getAbsolutePath()).also(outputs::add)
            return vm.resolveClass("java/io/FileOutputStream").newObject(output)
        }
        val adapter = VALUE_TYPES[signature.substringBefore("->")] ?: return null
        val constructor = adapter.constructors[signature.substringAfter("->")] ?: return null
        return wrap(vm, constructor.newInstance(*adapterArgs(constructor, args)))
    }

    private fun adapterArgs(member: Executable, args: VarArg): Array<Any?> =
        member.parameterTypes.mapIndexed { index, type ->
            args.getObjectArg<DvmObject<*>>(index)?.let { type.cast(it.value) }
        }.toTypedArray()

    private fun wrap(vm: BaseVM, value: Any?): DvmObject<*>? = when (value) {
        null -> null
        is String -> StringObject(vm, value)
        is GuestPath -> vm.resolveClass("java/io/File").newObject(value)
        else -> error("Unexpected guest adapter result")
    }

    private inline fun call(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VarArg, fallback: () -> DvmObject<*>?): DvmObject<*>? {
        return when (signature) {
            "android/app/Application->getClassLoader()Ljava/lang/ClassLoader;",
            "android/content/ContextWrapper->getClassLoader()Ljava/lang/ClassLoader;",
            "android/content/Context->getClassLoader()Ljava/lang/ClassLoader;" -> loader(vm)
            "java/lang/Class->getClassLoader()Ljava/lang/ClassLoader;" -> {
                if (receiver is DvmClass && ownsClass(receiver.className)) loader(vm) else fallback()
            }
            "android/app/Application->getAssets()Landroid/content/res/AssetManager;",
            "android/content/ContextWrapper->getAssets()Landroid/content/res/AssetManager;",
            "android/content/Context->getAssets()Landroid/content/res/AssetManager;" -> AssetManager(vm, "assets")
            "java/lang/ClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;",
            "dalvik/system/DexClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;",
            "dalvik/system/BaseDexClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;" -> {
                val name = args.getObjectArg<StringObject>(0)?.value
                if (name != null && CLASS_NAME.matches(name)) {
                    val internalName = name.replace('.', '/')
                    if (isArchiveLoader(receiver) && ownsClass(internalName)) return vm.resolveClass(internalName)
                    val child = (receiver.value as? DexClassLoader)?.takeIf { it in dexLoaders }
                    if (child != null) {
                        val loaded = try { child.loadClass(name) } catch (_: ClassNotFoundException) { null }
                        if (loaded != null && loaded.classLoader === child) {
                            val previous = dexClasses[internalName]
                            if (ownsClass(internalName) || (previous != null && previous !== loaded)) {
                                throw UnsupportedOperationException("Ambiguous desktop JNI class identity")
                            }
                            dexClasses[internalName] = loaded
                            return vm.resolveClass(internalName)
                        }
                        if (loaded?.classLoader === archiveClassLoader && ownsClass(internalName)) return vm.resolveClass(internalName)
                    }
                }
                vm.throwException(vm.resolveClass("java/lang/ClassNotFoundException").newObject(null))
                null
            }
            "java/lang/ClassLoader->getResourceAsStream(Ljava/lang/String;)Ljava/io/InputStream;" -> {
                if (!isArchiveLoader(receiver)) return fallback()
                val name = args.getObjectArg<StringObject>(0)?.value ?: return null
                archiveResource(name)?.let { vm.resolveClass("java/io/InputStream").newObject(GuestResource(it)) }
            }
            "java/lang/Class->getName()Ljava/lang/String;" -> StringObject(vm, (receiver as DvmClass).name)
            else -> {
                val owner = signature.substringBefore("->")
                val member = signature.substringAfter("->")
                val adapter = if (owner in CONTEXT_TYPES) CONTEXT_ADAPTER else VALUE_TYPES[owner]
                val target = if (owner in CONTEXT_TYPES) context else receiver.value
                val method = adapter?.methods?.get(member)
                if (method == null || !adapter.type.isInstance(target)) return fallback()
                wrap(vm, method.invoke(target, *adapterArgs(method, args)))
            }
        }
    }

    private fun loader(vm: BaseVM): DvmObject<*> =
        guestLoader ?: vm.resolveClass("java/lang/ClassLoader").newObject(loaderToken).also { guestLoader = it }

    private fun isArchiveLoader(receiver: DvmObject<*>): Boolean =
        receiver.value === loaderToken || (archiveClassLoader != null && receiver.value === archiveClassLoader)

    private inner class GuestContext {
        fun getFilesDir() = directory("files")
        fun getCacheDir() = directory("cache")
        fun getCodeCacheDir() = directory("code_cache")
        fun getNoBackupFilesDir() = directory("no_backup")
        private fun directory(name: String) = GuestPath(storage.appDirectory(name))
    }

    private class GuestPath(path: String) {
        private val path = path.replace(REPEATED_SEPARATORS, "/").let { if (it.length > 1) it.trimEnd('/') else it }
        constructor(parent: GuestPath?, child: String) : this(join(parent?.path, child))
        constructor(parent: String?, child: String) : this(join(parent, child))

        fun getPath() = path
        fun getAbsolutePath() = if (path.startsWith('/')) path else "/$path"
        fun getAbsoluteFile() = GuestPath(getAbsolutePath())
        fun getCanonicalPath(): String {
            val parts = ArrayDeque<String>()
            for (part in getAbsolutePath().split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> parts.removeLastOrNull()
                    else -> parts.addLast(part)
                }
            }
            return parts.joinToString("/", prefix = "/")
        }
        fun getCanonicalFile() = GuestPath(getCanonicalPath())
        fun getName() = path.substringAfterLast('/')
        fun getParent(): String? = when (val separator = path.lastIndexOf('/')) {
            -1 -> null
            0 -> if (path == "/") null else "/"
            else -> path.substring(0, separator)
        }
        fun getParentFile() = getParent()?.let(::GuestPath)
        override fun toString() = path

        companion object {
            private val REPEATED_SEPARATORS = Regex("/{2,}")
            private fun join(parent: String?, child: String): String = when {
                parent == null -> child
                child.isEmpty() -> parent
                else -> "${parent.trimEnd('/')}/$child"
            }
        }
    }

    private class ValueAdapter(val type: Class<*>) {
        val constructors = type.constructors.associateBy { "<init>" + guestDescriptor(Type.getConstructorDescriptor(it)) }
        val methods = type.declaredMethods.filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) }
            .associateBy { it.name + guestDescriptor(Type.getMethodDescriptor(it)) }

        private fun guestDescriptor(descriptor: String) = descriptor.replace(Type.getDescriptor(GuestPath::class.java), "Ljava/io/File;")
    }

    private companion object {
        val CLASS_NAME = Regex("[A-Za-z_$][A-Za-z0-9_$.]{0,199}")
        const val DEX_CONSTRUCTOR = "dalvik/system/DexClassLoader-><init>(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/ClassLoader;)V"
        val CONTEXT_TYPES = setOf("android/app/Application", "android/content/ContextWrapper", "android/content/Context")
        val OUTPUT_TYPES = setOf("java/io/OutputStream", "java/io/FileOutputStream")
        val CLOSEABLE_TYPES = setOf("java/io/Closeable", "java/lang/AutoCloseable")
        val CONTEXT_ADAPTER = ValueAdapter(GuestContext::class.java)
        val VALUE_TYPES = mapOf("java/io/File" to ValueAdapter(GuestPath::class.java))
    }
}

internal class GuestStorage(private val root: Path?) {
    fun attributes(path: String): BasicFileAttributes? = access(path) { _, attributes -> attributes }

    fun setWritable(path: String, writable: Boolean, ownerOnly: Boolean): Boolean = access(path) { target, _ ->
        val posix = Files.getFileAttributeView(target, PosixFileAttributeView::class.java, NOFOLLOW_LINKS)
        if (posix != null) {
            val permissions = posix.readAttributes().permissions().toMutableSet()
            val affected = if (ownerOnly) setOf(PosixFilePermission.OWNER_WRITE) else writePermissions
            if (writable) permissions.addAll(affected) else permissions.removeAll(affected)
            posix.setPermissions(permissions)
            true
        } else {
            val dos = Files.getFileAttributeView(target, DosFileAttributeView::class.java, NOFOLLOW_LINKS) ?: return@access false
            dos.setReadOnly(!writable)
            true
        }
    } ?: false

    fun delete(path: String): Boolean = access(path) { target, _ ->
        target.parent != root && Files.deleteIfExists(target)
    } ?: false

    fun appDirectory(name: String): String {
        require(name in appDirectories)
        val path = "$guestRoot/$name"
        access(path, allowMissingLeaf = true) { target, attributes ->
            if (attributes == null) Files.createDirectory(target)
        }
        return path
    }

    fun dexFile(path: String): Path = access(path) { target, attributes ->
        if (!attributes.isRegularFile || attributes.size() !in 8..(32L * 1024 * 1024)) return@access null
        val prefix = Files.newInputStream(target, NOFOLLOW_LINKS).use { it.readNBytes(8) }
        val dex = prefix.size == 8 && prefix.copyOf(4).contentEquals(byteArrayOf(100, 101, 120, 10)) && prefix[7] == 0.toByte()
        val zip = prefix.size == 8 && prefix.copyOf(4).contentEquals(byteArrayOf(80, 75, 3, 4))
        target.takeIf { dex || zip }
    } ?: throw IOException("Guest DEX path unavailable")

    fun openOutput(path: String): GuestOutput = access(path, allowMissingLeaf = true) { target, attributes ->
        if (target.parent == root || (attributes != null && !attributes.isRegularFile)) return@access null
        val temporary = Files.createTempFile(target.parent, ".jni-", ".tmp")
        try {
            GuestOutput(Files.newOutputStream(temporary, WRITE, NOFOLLOW_LINKS), temporary) {
                access(path, allowMissingLeaf = true) { current, info ->
                    if (current != target || (info != null && !info.isRegularFile)) return@access null
                    Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } ?: throw IOException("Guest output destination unavailable")
            }
        } catch (error: IOException) {
            runCatching { Files.deleteIfExists(temporary) }
            throw error
        }
    } ?: throw IOException("Guest output path unavailable")

    private fun <T> access(path: String, operation: (Path, BasicFileAttributes) -> T): T? =
        access(path, allowMissingLeaf = false) { target, attributes -> operation(target, checkNotNull(attributes)) }

    private fun <T> access(path: String, allowMissingLeaf: Boolean, operation: (Path, BasicFileAttributes?) -> T): T? {
        val base = root ?: return null
        if (!path.startsWith("$guestRoot/")) return null
        val parts = path.removePrefix("$guestRoot/").split('/')
        if (parts.first() !in appDirectories || parts.any { !component.matches(it) || it.endsWith('.') || it.endsWith(' ') ||
                '~' in it || device.matches(it.substringBefore('.').trimEnd(' ')) }) return null
        return try {
            val baseInfo = Files.readAttributes(base, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!baseInfo.isDirectory || baseInfo.isSymbolicLink || baseInfo.isOther) return null
            var current = base
            for ((index, part) in parts.withIndex()) {
                current = current.resolve(part)
                val info = try {
                    Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                } catch (missing: NoSuchFileException) {
                    if (allowMissingLeaf && index == parts.lastIndex) return operation(current, null)
                    throw missing
                }
                if (info.isSymbolicLink || info.isOther) return null
                if (index == parts.lastIndex) return operation(current, info)
                if (!info.isDirectory) return null
            }
            null
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private companion object {
        const val guestRoot = "/data/user/0/yingzhou.spider"
        val appDirectories = setOf("files", "cache", "code_cache", "no_backup")
        val component = Regex("[^/\\\\:<>\"|?*\\x00-\\x1f]+")
        val device = Regex("CON|PRN|AUX|NUL|CONIN\\$|CONOUT\\$|(?:COM|LPT)[1-9¹²³]", RegexOption.IGNORE_CASE)
        val writePermissions = setOf(PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_WRITE, PosixFilePermission.OTHERS_WRITE)
    }
}

internal class GuestOutput(private val output: OutputStream, private val temporary: Path, private val publish: () -> Unit) {
    private var bytes = 0
    private var closed = false

    fun write(data: ByteArray, offset: Int, size: Int) {
        java.util.Objects.checkFromIndexSize(offset, size, data.size)
        if (closed || size > MAX_OUTPUT_BYTES - bytes) throw IOException("Guest output stream closed or size limit exceeded")
        output.write(data, offset, size)
        bytes += size
    }

    fun flush() {
        if (closed) throw IOException("Guest output stream is closed")
        output.flush()
    }

    fun close() = finish(publish)

    fun abort() = finish {}

    private fun finish(action: () -> Unit) {
        if (closed) return
        closed = true
        try {
            output.close()
            action()
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private companion object {
        const val MAX_OUTPUT_BYTES = 32 * 1024 * 1024
    }
}
