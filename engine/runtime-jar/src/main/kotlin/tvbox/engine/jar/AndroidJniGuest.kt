package tvbox.engine.jar

import com.github.unidbg.AndroidEmulator
import com.github.unidbg.Emulator
import com.github.unidbg.arm.ARMEmulator
import com.github.unidbg.arm.Arm64Svc
import com.github.unidbg.arm.ArmSvc
import com.github.unidbg.arm.backend.Backend
import com.github.unidbg.arm.backend.BlockHook
import com.github.unidbg.arm.backend.UnHook
import com.github.unidbg.arm.backend.Unicorn2Factory
import com.github.unidbg.arm.context.RegisterContext
import com.github.unidbg.file.FileResult
import com.github.unidbg.file.FileSystem
import com.github.unidbg.file.linux.AndroidFileIO
import com.github.unidbg.linux.ARM32SyscallHandler
import com.github.unidbg.linux.ARM64SyscallHandler
import com.github.unidbg.linux.LinuxModule
import com.github.unidbg.linux.android.AndroidARM64Emulator
import com.github.unidbg.linux.android.AndroidARMEmulator
import com.github.unidbg.linux.android.AndroidResolver
import com.github.unidbg.linux.android.dvm.DvmObject
import com.github.unidbg.linux.android.dvm.VM
import com.github.unidbg.linux.android.dvm.api.Asset
import com.github.unidbg.linux.android.dvm.jni.ProxyClassFactory
import com.github.unidbg.memory.SvcMemory
import com.github.unidbg.pointer.UnidbgPointer
import com.github.unidbg.unix.UnixEmulator
import com.github.unidbg.virtualmodule.android.AndroidModule
import org.objectweb.asm.Type
import tvbox.engine.core.spider.SpiderNativeOperation
import unicorn.Arm64Const
import unicorn.ArmConst
import java.io.File
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.concurrent.TimeUnit

internal class AndroidJniGuest @JvmOverloads constructor(
    is64Bit: Boolean,
    root: File,
    asset: (String) -> ByteArray? = { null },
    ownsClass: (String) -> Boolean = { false },
    classHierarchy: (String) -> GuestClassHierarchy? = { null },
    private val archiveClass: (String) -> Class<*>? = { null },
    storage: GuestStorage = GuestStorage(null),
    resource: (String) -> ByteArray? = { null },
    archiveClassLoader: ClassLoader? = null,
) : AutoCloseable {
    private val emulator: AndroidEmulator = emulator(is64Bit, root)
    private val vm: VM
    private val callbacks = GuestJavaCallbacks(ownsClass, storage, resource, archiveClassLoader)
    private val loaded = mutableMapOf<String, ByteArray>()
    private val receivers = IdentityHashMap<Any, DvmObject<*>>()
    private var closed = false
    private var failed = false
    private var deadline = 0L

    init {
        try {
            emulator.memory.setLibraryResolver(object : AndroidResolver(23, "libc.so", "libm.so", "libdl.so", "liblog.so", "libstdc++.so", "libz.so") {
                override fun onSetToLoader(emulator: Emulator<*>) = Unit
            })
            vm = emulator.createDalvikVM()
            vm.setJni(callbacks.bridge)
            val archiveLoader = object : ClassLoader(null) {
                override fun loadClass(name: String): Class<*> =
                    guestClass(name.replace('.', '/')) ?: throw ClassNotFoundException(name)
            }
            val proxyFactory = ProxyClassFactory(archiveLoader).apply {
                setFallbackJni(callbacks.bridge)
                configObjectVisitor { member, _, _ ->
                    val owner = member.declaringClass
                    if (guestClass(owner.name.replace('.', '/')) !== owner) {
                        throw UnsupportedOperationException("JNI proxy member is outside the spider archive")
                    }
                }
            }
            val resolving = mutableSetOf<String>()
            vm.setDvmClassFactory { base, name, parent, interfaces ->
                if (resolving.size >= 64 || !resolving.add(name)) {
                    throw NativeLoadFailure("unsupported Java callback", "Invalid guest class hierarchy")
                }
                try {
                    val hierarchy = when (name) {
                        "android/app/Application" -> GuestClassHierarchy("android/content/ContextWrapper", emptyList())
                        "android/content/ContextWrapper" -> GuestClassHierarchy("android/content/Context", emptyList())
                        "java/io/FileOutputStream" -> GuestClassHierarchy("java/io/OutputStream", emptyList())
                        "java/io/OutputStream" -> GuestClassHierarchy("java/lang/Object", listOf("java/io/Closeable", "java/io/Flushable"))
                        "java/io/InputStream" -> GuestClassHierarchy("java/lang/Object", listOf("java/io/Closeable"))
                        "java/io/Closeable" -> GuestClassHierarchy(null, listOf("java/lang/AutoCloseable"))
                        "dalvik/system/DexClassLoader" -> GuestClassHierarchy("dalvik/system/BaseDexClassLoader", emptyList())
                        "dalvik/system/BaseDexClassLoader" -> GuestClassHierarchy("java/lang/ClassLoader", emptyList())
                        else -> classHierarchy(name) ?: callbacks.dexClass(name)?.let { type ->
                            GuestClassHierarchy(type.superclass?.name?.replace('.', '/'), type.interfaces.map { it.name.replace('.', '/') })
                        }
                    }
                    val superClass = parent ?: hierarchy?.parent?.let { vm.resolveClass(it) }
                    val resolvedInterfaces = if (interfaces.isNotEmpty()) interfaces
                    else hierarchy?.interfaces?.map { vm.resolveClass(it) }?.toTypedArray() ?: emptyArray()
                    if (ownsClass(name) || callbacks.dexClass(name) != null) proxyFactory.createClass(base, name, superClass, resolvedInterfaces)
                    else base.createClass(base, name, superClass, resolvedInterfaces)
                } finally {
                    resolving.remove(name)
                }
            }
            vm.setAssetResolver(asset)
            object : AndroidModule(emulator, vm) {
                override fun onInitialize(emulator: Emulator<*>, vm: VM, symbols: MutableMap<String, UnidbgPointer>) {
                    super.onInitialize(emulator, vm, symbols)
                    val read = if (emulator.is64Bit) object : Arm64Svc() {
                        override fun handle(emulator: Emulator<*>) = readAsset(emulator, vm)
                    } else object : ArmSvc() {
                        override fun handle(emulator: Emulator<*>) = readAsset(emulator, vm)
                    }
                    symbols["AAsset_read"] = emulator.svcMemory.registerSvc(read)
                }
            }.register(emulator.memory)
            emulator.backend.hook_add_new(object : BlockHook {
                private var hook: UnHook? = null
                private var blocks = 0
                override fun onAttach(unHook: UnHook) { hook = unHook }
                override fun detach() { hook?.unhook() }
                override fun hookBlock(backend: Backend, address: Long, size: Int, user: Any?) {
                    if (++blocks % 256 == 0 && expired()) backend.emu_stop()
                }
            }, 1, 0, null)
        } catch (problem: Throwable) {
            runCatching { emulator.close() }.exceptionOrNull()?.let(problem::addSuppressed)
            throw problem
        }
    }

    @Synchronized
    fun load(name: String, bytes: ByteArray) {
        check(!closed && !failed) { "Android JNI guest unavailable" }
        require(name.matches(LIBRARY_NAME)) { "Invalid Android JNI library name" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        loaded[name]?.let { previous ->
            require(digest.contentEquals(previous)) { "Conflicting Android JNI library: $name" }
            return
        }
        runGuest {
            val module = vm.loadLibrary(name, bytes, true)
            for (elf in emulator.memory.loadedModules.filterIsInstance<LinuxModule>()) {
                val resolved = elf.neededLibraries.map { it.name }.toSet()
                val missing = elf.dynamicStructure?.neededLibraries?.firstOrNull { it !in resolved }
                if (missing != null) throw NativeLoadFailure("native dependency missing", "${elf.name}: $missing")
                if (!elf.unresolvedSymbol.isNullOrEmpty()) throw NativeLoadFailure("native symbols unresolved", "${elf.name}: ${elf.unresolvedSymbol.size}")
            }
            try {
                module.callJNI_OnLoad(emulator)
            } catch (failure: RuntimeException) {
                throw NativeLoadFailure("JNI_OnLoad failed", name, failure)
            }
            loaded[name] = digest
        }
    }

    @Synchronized
    fun invoke(owner: String, signature: String, receiver: Any?, arguments: Array<Any?>): Any? {
        check(!closed && !failed) { "Android JNI guest unavailable" }
        val descriptor = signature.substring(signature.indexOf('('))
        val parameterTypes = Type.getArgumentTypes(descriptor)
        val returnType = Type.getReturnType(descriptor)
        val returns = returnType.descriptor
        require(returns in SUPPORTED || returnType.sort == Type.OBJECT || returns == "V") { "Unsupported Android JNI return type $returns" }
        require(parameterTypes.size == arguments.size) { "Android JNI argument count mismatch" }
        val objects = GuestObjects(vm, callbacks::ownsReturnedReference, ::guestClass)
        val args = Array<Any>(parameterTypes.size) { index ->
            val value = arguments[index]
            when (val type = parameterTypes[index].descriptor) {
                "Z" -> if (value as Boolean) 1 else 0
                "C" -> (value as Char).code
                "B", "S", "I" -> (value as Number).toInt()
                "J" -> value as Long
                "Ljava/lang/Object;", "[Ljava/lang/Object;", "[Ljava/lang/String;", "Ljava/lang/Class;",
                "Ljava/lang/String;", "Landroid/content/res/AssetManager;", "[B" -> objects.wrap(value) ?: 0
                else -> if (parameterTypes[index].sort == Type.OBJECT) objects.wrap(value) ?: 0
                else throw UnsupportedOperationException("Unsupported Android JNI parameter type $type")
            }
        }
        val type = vm.resolveClass(owner.replace('.', '/'))
        try {
            type.findNativeFunction(emulator, signature)
        } catch (error: IllegalArgumentException) {
            if (error.message != "find method failed: $signature") throw error
            throw NativeLoadFailure("native method not bound", "$owner->$signature", error)
        }
        val instance = receiver?.let {
            receivers.getOrPut(it) {
                check(receivers.size < 256) { "Android JNI receiver limit reached" }
                type.newObject(it)
            }
        }
        return runGuest {
            val result: Any? = when {
                returns == "V" -> {
                    if (instance == null) type.callStaticJniMethod(emulator, signature, *args) else instance.callJniMethod(emulator, signature, *args)
                    null
                }
                returns == "J" -> if (instance == null) type.callStaticJniMethodLong(emulator, signature, *args) else instance.callJniMethodLong(emulator, signature, *args)
                returns in SUPPORTED_ARRAYS || returnType.sort == Type.OBJECT -> {
                    val returned: DvmObject<*>? = if (instance == null) type.callStaticJniMethodObject(emulator, signature, *args) else instance.callJniMethodObject(emulator, signature, *args)
                    objects.unwrap(returned, returns)
                }
                else -> {
                    val value = if (instance == null) type.callStaticJniMethodInt(emulator, signature, *args) else instance.callJniMethodInt(emulator, signature, *args)
                    when (returns) {
                        "B" -> value.toByte()
                        "S" -> value.toShort()
                        "C" -> value.toChar()
                        "Z" -> value != 0
                        else -> value
                    }
                }
            }
            objects.copyBack()
            result
        }
    }

    private fun expired() = deadline != 0L && System.nanoTime() - deadline >= 0

    private fun guestClass(name: String): Class<*>? = callbacks.dexClass(name) ?: archiveClass(name)

    private fun <T> runGuest(call: () -> T): T {
        callbacks.resetFailure()
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        try {
            return call().also {
                check(!expired()) { "Android JNI guest timed out" }
                callbacks.failure?.let { throw it }
            }
        } catch (problem: Throwable) {
            failed = true
            callbacks.abortOpenOutputs()
            if (expired()) {
                val cause = callbacks.lastCallback?.let { SpiderNativeOperation("JNI callback $it", problem) } ?: problem
                throw NativeLoadFailure("guest timed out", "Android JNI guest timed out (${callbacks.readProgress()})", cause)
            }
            callbacks.failure?.let { failure ->
                if (problem is Exception || problem is UnsatisfiedLinkError) throw failure
            }
            throw problem
        } finally {
            deadline = 0L
            callbacks.resetFailure()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            callbacks.abortOpenOutputs()
            callbacks.releaseDexLoaders()
            receivers.clear()
            emulator.close()
        }
    }

    private companion object {
        const val JNI_EXCEPTION_CHECK_SLOT = 228L
        val LIBRARY_NAME = Regex("[a-zA-Z0-9_+.-]+\\.so")
        val SUPPORTED_ARRAYS = setOf("[B", "[Ljava/lang/Object;", "[Ljava/lang/String;")
        val SUPPORTED = setOf("Z", "B", "S", "C", "I", "J") + SUPPORTED_ARRAYS
        val ARM32_MEMORY_TIME_IDS = setOf(20, 24, 45, 47, 49, 50, 78, 91, 125, 192, 199, 200, 201, 202, 224, 263)
        val ARM64_MEMORY_TIME_IDS = setOf(113, 169, 172, 174, 175, 176, 177, 178, 214, 215, 222, 226)

        fun readAsset(emulator: Emulator<*>, vm: VM): Long {
            val context = emulator.getContext<RegisterContext>()
            val count = if (emulator.is64Bit) context.getLongArg(2).toULong() else context.getIntArg(2).toUInt().toULong()
            if (count == 0uL) return 0
            val asset = vm.getObject<DvmObject<*>>(context.getIntArg(0)) as? Asset ?: return -1
            val data = asset.read(minOf(count, 64uL * 1024uL).toInt())
            if (data.isNotEmpty()) {
                val output = context.getPointerArg(1) ?: return -1
                output.write(0, data, 0, data.size)
            }
            return data.size.toLong()
        }

        fun emulator(is64Bit: Boolean, root: File): AndroidEmulator {
            var allocated: Backend? = null
            val factory = object : Unicorn2Factory(false) {
                override fun newBackendInternal(emulator: Emulator<*>, is64Bit: Boolean): Backend =
                    super.newBackendInternal(emulator, is64Bit).also { allocated = it }
            }
            try {
                return createEmulator(is64Bit, root, listOf(factory))
            } catch (problem: Throwable) {
                runCatching { allocated?.destroy() }.exceptionOrNull()?.let(problem::addSuppressed)
                throw problem
            }
        }

        fun createEmulator(is64Bit: Boolean, root: File, engines: List<Unicorn2Factory>): AndroidEmulator {
            return if (is64Bit) object : AndroidARM64Emulator("yingzhou.spider", root, engines) {
                override fun createFileSystem(rootDir: File) = GuestFiles(rootDir)
                override fun createConsoleDebugger(): Nothing = error("Android JNI guest fault")
                override fun eFunc(begin: Long, vararg arguments: Number) = checkedCall(this, begin, arguments) { address, values -> super.eFunc(address, *values) }
                override fun createSyscallHandler(svcMemory: SvcMemory) = object : ARM64SyscallHandler(svcMemory) {
                    override fun handleSyscall(emulator: Emulator<*>, number: Int): Boolean = denyUnless(emulator, number, ARM64_MEMORY_TIME_IDS, Arm64Const.UC_ARM64_REG_X0)
                    override fun hook(backend: Backend, intno: Int, swi: Int, user: Any?) {
                        if (intno == ARMEmulator.EXCP_SWI) super.hook(backend, intno, swi, user) else backend.emu_stop()
                    }
                }
            } else object : AndroidARMEmulator("yingzhou.spider", root, engines) {
                override fun createFileSystem(rootDir: File) = GuestFiles(rootDir)
                override fun createConsoleDebugger(): Nothing = error("Android JNI guest fault")
                override fun eFunc(begin: Long, vararg arguments: Number) = checkedCall(this, begin, arguments) { address, values -> super.eFunc(address, *values) }
                override fun createSyscallHandler(svcMemory: SvcMemory) = object : ARM32SyscallHandler(svcMemory) {
                    override fun handleSyscall(emulator: Emulator<*>, number: Int): Boolean = denyUnless(emulator, number, ARM32_MEMORY_TIME_IDS, ArmConst.UC_ARM_REG_R0)
                    override fun hook(backend: Backend, intno: Int, swi: Int, user: Any?) {
                        if (intno == ARMEmulator.EXCP_SWI) super.hook(backend, intno, swi, user) else backend.emu_stop()
                    }
                }
            }
        }

        inline fun checkedCall(emulator: AndroidEmulator, begin: Long, arguments: Array<out Number>, invoke: (Long, Array<out Number>) -> Number): Number {
            val environment = emulator.dalvikVM?.jniEnv as UnidbgPointer?
            val exceptionCheck = environment?.let {
                checkNotNull(it.getPointer(0)?.getPointer(JNI_EXCEPTION_CHECK_SLOT * emulator.pointerSize)) { "Android JNI exception check unavailable" }
            }
            val result = invoke(begin, arguments)
            verifyReturn(emulator)
            if (exceptionCheck != null) {
                val address: Number = if (emulator.is64Bit) environment.peer else environment.peer.toInt()
                val pending = invoke(exceptionCheck.peer, arrayOf(address)).toInt()
                verifyReturn(emulator)
                check(pending == 0) { "Android JNI guest left a Java exception pending" }
            }
            return result
        }

        fun verifyReturn(emulator: AndroidEmulator) {
            val pc = if (emulator.is64Bit) emulator.backend.reg_read(Arm64Const.UC_ARM64_REG_PC).toLong()
            else emulator.backend.reg_read(ArmConst.UC_ARM_REG_PC).toLong() and 0xffffffffL
            check(pc == emulator.returnAddress) { "Android JNI guest stopped before returning" }
        }

        fun denyUnless(emulator: Emulator<*>, number: Int, allowed: Set<Int>, resultRegister: Int): Boolean {
            if (number in allowed) return false
            emulator.backend.reg_write(resultRegister, -UnixEmulator.EACCES)
            return true
        }
    }

    private class GuestFiles(private val root: File) : FileSystem<AndroidFileIO> {
        override fun getRootDir() = root
        override fun createWorkDir() = root
        override fun open(pathname: String, oflags: Int): FileResult<AndroidFileIO> = FileResult.failed(UnixEmulator.EACCES)
        override fun mkdir(path: String, mode: Int) = false
        override fun rename(oldPath: String, newPath: String) = -1
        override fun unlink(path: String): Unit = throw UnsupportedOperationException("Guest files are read-only")
        override fun rmdir(path: String): Unit = throw UnsupportedOperationException("Guest files are read-only")
        override fun createSimpleFileIO(file: File, oflags: Int, path: String): AndroidFileIO = throw UnsupportedOperationException("Host files are unavailable")
        override fun createDirectoryFileIO(file: File, oflags: Int, path: String): AndroidFileIO = throw UnsupportedOperationException("Host files are unavailable")
    }
}

internal class NativeLoadFailure(val reason: String, detail: String, cause: Throwable? = null) : IllegalStateException(detail.take(180), cause)

internal data class GuestClassHierarchy(val parent: String?, val interfaces: List<String>)
