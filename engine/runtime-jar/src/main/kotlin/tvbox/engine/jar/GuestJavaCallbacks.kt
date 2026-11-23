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
import org.objectweb.asm.Type
import tvbox.engine.core.spider.SpiderNativeOperation
import java.io.IOException
import java.lang.reflect.Executable
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

internal class GuestJavaCallbacks(private val ownsClass: (String) -> Boolean, private val storage: GuestStorage) : AbstractJni() {
    private val loaderToken = Any()
    private val context = GuestContext()
    private var guestLoader: DvmObject<*>? = null
    var failure: NativeLoadFailure? = null
        private set

    fun resetFailure() { failure = null }

    val bridge: Jni = Proxy.newProxyInstance(Jni::class.java.classLoader, arrayOf(Jni::class.java)) { _, method, input ->
        val arguments = input.orEmpty()
        val signature = when (val member = arguments.getOrNull(2)) {
            is String -> member.takeUnless { method.name.startsWith("accept") }
            is DvmMethod -> member.signature
            is DvmField -> member.toString()
            else -> null
        }
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
        filePredicate(receiver, signature) ?: super.callBooleanMethod(vm, receiver, signature, args)

    override fun callBooleanMethodV(vm: BaseVM, receiver: DvmObject<*>, signature: String, args: VaList): Boolean =
        filePredicate(receiver, signature) ?: super.callBooleanMethodV(vm, receiver, signature, args)

    private fun filePredicate(receiver: DvmObject<*>, signature: String): Boolean? {
        val path = receiver.value as? GuestPath ?: return null
        return when (signature) {
            "java/io/File->exists()Z" -> storage.attributes(path.getCanonicalPath()) != null
            "java/io/File->isDirectory()Z" -> storage.attributes(path.getCanonicalPath())?.isDirectory == true
            "java/io/File->isFile()Z" -> storage.attributes(path.getCanonicalPath())?.isRegularFile == true
            else -> null
        }
    }

    override fun newObject(vm: BaseVM, type: DvmClass, signature: String, args: VarArg): DvmObject<*>? =
        construct(vm, signature, args) ?: super.newObject(vm, type, signature, args)

    override fun newObjectV(vm: BaseVM, type: DvmClass, signature: String, args: VaList): DvmObject<*>? =
        construct(vm, signature, args) ?: super.newObjectV(vm, type, signature, args)

    private fun construct(vm: BaseVM, signature: String, args: VarArg): DvmObject<*>? {
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
            "android/content/Context->getClassLoader()Ljava/lang/ClassLoader;" ->
                guestLoader ?: vm.resolveClass("java/lang/ClassLoader").newObject(loaderToken).also { guestLoader = it }
            "android/app/Application->getAssets()Landroid/content/res/AssetManager;",
            "android/content/ContextWrapper->getAssets()Landroid/content/res/AssetManager;",
            "android/content/Context->getAssets()Landroid/content/res/AssetManager;" -> AssetManager(vm, "assets")
            "java/lang/ClassLoader->loadClass(Ljava/lang/String;)Ljava/lang/Class;" -> {
                val name = args.getObjectArg<StringObject>(0)?.value
                if (receiver.value === loaderToken && name != null && CLASS_NAME.matches(name) && ownsClass(name.replace('.', '/'))) {
                    vm.resolveClass(name)
                } else {
                    vm.throwException(vm.resolveClass("java/lang/ClassNotFoundException").newObject(null))
                    null
                }
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

    private class GuestContext {
        fun getFilesDir() = GuestPath("/data/user/0/yingzhou.spider/files")
        fun getCacheDir() = GuestPath("/data/user/0/yingzhou.spider/cache")
        fun getCodeCacheDir() = GuestPath("/data/user/0/yingzhou.spider/code_cache")
        fun getNoBackupFilesDir() = GuestPath("/data/user/0/yingzhou.spider/no_backup")
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
        val CONTEXT_TYPES = setOf("android/app/Application", "android/content/ContextWrapper", "android/content/Context")
        val CONTEXT_ADAPTER = ValueAdapter(GuestContext::class.java)
        val VALUE_TYPES = mapOf("java/io/File" to ValueAdapter(GuestPath::class.java))
    }
}

internal class GuestStorage(private val root: Path?) {
    fun attributes(path: String): BasicFileAttributes? {
        val base = root ?: return null
        if (!path.startsWith("$guestRoot/")) return null
        val parts = path.removePrefix("$guestRoot/").split('/')
        if (parts.first() !in appDirectories || parts.any { !component.matches(it) || it.endsWith('.') || it.endsWith(' ') }) return null
        return try {
            var current = base
            for ((index, part) in parts.withIndex()) {
                current = current.resolve(part)
                val info = Files.readAttributes(current, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (info.isSymbolicLink || info.isOther) return null
                if (index == parts.lastIndex) return info
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
    }
}
