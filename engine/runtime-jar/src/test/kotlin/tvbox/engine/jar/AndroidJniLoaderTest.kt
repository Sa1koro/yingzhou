package tvbox.engine.jar

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvbox.engine.core.spider.spiderDiagnostic
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AndroidJniLoaderTest {
    @TempDir lateinit var dir: File

    private fun fixture(name: String): ByteArray = javaClass.getResourceAsStream("/jni/$name.base64")!!.use {
        Base64.getMimeDecoder().decode(it.readBytes())
    }

    private fun archive(abi: String): File {
        val source = File(dir, "spider-$abi.jar").also { jar ->
            ZipOutputStream(jar.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("classes.dex"))
                zip.write(fixture("math.dex"))
                zip.putNextEntry(ZipEntry("assets/libs/$abi/libmath.so"))
                zip.write(fixture("libmath-$abi.so"))
                zip.putNextEntry(ZipEntry("assets/libs/$abi/libndk.so"))
                zip.write(fixture("libndk-$abi.so"))
                zip.putNextEntry(ZipEntry("assets/demo/value"))
                zip.write("42".toByteArray())
                zip.putNextEntry(ZipEntry("assets/demo/empty"))
                zip.putNextEntry(ZipEntry("assets/${"x".repeat(513)}"))
                zip.write("21".toByteArray())
                zip.putNextEntry(ZipEntry("private-value"))
                zip.write("21".toByteArray())
            }
        }
        return DexConverter(File(dir, "converted")).convert(source)
    }

    @Test fun `NDK reads packaged assets on both ABIs through SDK libraries`() {
        for (abi in listOf("arm", "arm64")) {
            AndroidJniLoader.open(archive(abi), dir, enabled = true).use { loader ->
                val spider = loader.loadClass("tvbox.fixture.NativeMath")
                spider.getMethod("loadLibrary", String::class.java).invoke(null, "ndk")
                val add = spider.getMethod("add", Int::class.java, Int::class.java)
                val missing = assertThrows(InvocationTargetException::class.java) { add.invoke(null, 19, 23) }
                val diagnostic = spiderDiagnostic("site", "spider.create", missing)
                assertEquals("UnsatisfiedLinkError: Android JNI: native method not bound", diagnostic.cause)
                assertEquals("JNI method tvbox/fixture/NativeMath->add(II)I", diagnostic.nativeOperation)
                val manager = android.content.res.AssetManager()
                val read = spider.getMethod("readAsset", manager.javaClass, String::class.java)
                assertEquals(42, read.invoke(null, manager, "demo/value"))
                assertEquals(-2, read.invoke(null, manager, "demo/empty"))
                for (path in listOf("absent", "../private-value", "/private-value", "..\\private-value", "x".repeat(513))) {
                    assertEquals(-1, read.invoke(null, manager, path))
                }
            }
        }
    }

    @Test fun `packaged 32 and 64 bit JNI runs through the spider class loader`() {
        for ((abi, expected) in listOf("arm" to "armeabi-v7a", "arm64" to "arm64-v8a")) {
            AndroidJniLoader.open(archive(abi), dir, enabled = true).use { loader ->
                val spider = loader.loadClass("tvbox.fixture.NativeMath")
                assertEquals(expected, spider.getMethod("abi").invoke(null))
                val load = spider.getMethod("loadLibrary", String::class.java)
                load.invoke(null, "math")
                load.invoke(null, "math")
                assertEquals(42, spider.getMethod("add", Int::class.java, Int::class.java).invoke(null, 19, 23))
                val nested = spider.getMethod("nested", Int::class.java)
                assertEquals(42, nested.invoke(null, 35))
                assertEquals(17, nested.invoke(null, 10))
                val proxied = spider.getMethod("proxyRoundTrip", Int::class.java).invoke(null, 21)
                assertEquals(42, spider.getMethod("value").invoke(proxied))
                val error = assertThrows(InvocationTargetException::class.java) { spider.getMethod("unsupported").invoke(null) }.cause
                assertEquals("Android JNI: unsupported signature", error?.message)
            }
        }
    }

    @Test fun `System load accepts the packaged copy only from spider storage`() {
        val storage = File(dir, "spider").also { it.mkdir() }
        val asset = fixture("libmath-arm64.so")
        AndroidJniLoader.open(archive("arm64"), storage, enabled = true).use { loader ->
            val spider = loader.loadClass("tvbox.fixture.NativeMath")
            val load = spider.getMethod("load", String::class.java)
            for ((file, data) in listOf(File(dir, "elsewhere") to asset, File(storage, "modified") to asset.reversedArray())) {
                file.writeBytes(data)
                assertInstanceOf(UnsatisfiedLinkError::class.java, assertThrows(InvocationTargetException::class.java) { load.invoke(null, file.path) }.cause)
            }
            val add = spider.getMethod("add", Int::class.java, Int::class.java)
            val early = assertThrows(InvocationTargetException::class.java) { add.invoke(null, 4, 5) }
            assertEquals("JNI method java/lang/System->load(Ljava/lang/String;)V", spiderDiagnostic("site", "jar.init", early).nativeOperation)
            val copy = File(storage, "generated-filename").also { it.writeBytes(asset) }
            load.invoke(null, copy.path)
            assertEquals(9, add.invoke(null, 4, 5))
        }
        AndroidJniLoader.open(archive("arm"), storage, enabled = false).use { assertFalse(it is AndroidJniLoader) }
    }

    @Test fun `storage path check starts from the configured directory and rejects path escapes`() {
        val root = Files.createDirectory(dir.toPath().resolve("storage"))
        val subdir = Files.createDirectory(root.resolve("cache"))
        val asset = Files.createFile(subdir.resolve("library.so"))
        val outside = Files.createFile(dir.toPath().resolve("outside.so"))
        assertEquals(asset, AndroidJniLoader.unlinkedStoragePath(root, asset))
        assertNull(AndroidJniLoader.unlinkedStoragePath(root, subdir))
        assertNull(AndroidJniLoader.unlinkedStoragePath(root, outside))
        assertNull(AndroidJniLoader.unlinkedStoragePath(root, subdir.resolve("../cache/library.so")))
        val link = runCatching { Files.createSymbolicLink(subdir.resolve("link.so"), outside) }.getOrNull()
        if (link != null) assertNull(AndroidJniLoader.unlinkedStoragePath(root, link))
    }

    @Test fun `ordinary JNI object signatures keep references and array types on both ABIs`() {
        for (abi in listOf("arm", "arm64")) {
            AndroidJniLoader.open(archive(abi), dir, enabled = true).use { loader ->
                val spider = loader.loadClass("tvbox.fixture.NativeMath")
                spider.getMethod("loadLibrary", String::class.java).invoke(null, "math")
                val original = Any()
                val echo = spider.getMethod("echo", Any::class.java)
                assertSame(original, echo.invoke(null, original))
                assertNull(echo.invoke(null, null))
                assertSame(spider, echo.invoke(null, spider))
                val tag = spider.getMethod("tag", Any::class.java, String::class.java)
                assertEquals("value", tag.invoke(null, original, "value"))
                val pair = spider.getMethod("pair", Any::class.java, Any::class.java)
                assertArrayEquals(arrayOf(original, null), pair.invoke(null, original, null) as Array<*>)
                val cycle = arrayOfNulls<Any>(1).also { it[0] = it }
                assertSame(cycle, echo.invoke(null, cycle))
                val input = arrayOf("first", null)
                val output = spider.getMethod("strings", input.javaClass).invoke(null, input) as Array<*>
                assertEquals(input.javaClass, output.javaClass)
                assertArrayEquals(arrayOf("first", null), output)
                assertArrayEquals(arrayOf("first", "first"), input)
            }
        }
    }

    @Test fun `native callbacks get the Android application loader and resolve only its archive classes`() {
        for (abi in listOf("arm", "arm64")) {
            AndroidJniLoader.open(archive(abi), dir, enabled = true).use { loader ->
                val spider = loader.loadClass("tvbox.fixture.NativeMath")
                spider.getMethod("loadLibrary", String::class.java).invoke(null, "math")
                val application = android.app.Application()
                val load = spider.getMethod("loadType", Any::class.java, String::class.java)
                assertEquals(spider.name, load.invoke(null, application, spider.name))
                assertNull(load.invoke(null, application, "tvbox.engine.jar.AndroidJniLoader"))
                val directory = spider.getMethod("storageDirectory", Any::class.java, String::class.java)
                for ((method, expected) in mapOf("getCodeCacheDir" to "code_cache", "getCacheDir" to "cache", "getFilesDir" to "files")) {
                    assertEquals(expected, directory.invoke(null, application, method))
                }
                val unsupported = spider.getMethod("unsupportedContextCall", Any::class.java)
                val failure = assertThrows(InvocationTargetException::class.java) { unsupported.invoke(null, application) }
                assertEquals("Android JNI: unsupported Java callback", failure.cause?.message)
                val shared = spiderDiagnostic("first", "jar.init", failure, "240b28a7e4c2")
                assertEquals("JNI callback android/content/Context->getPackageCodePath()Ljava/lang/String;", shared.nativeOperation)
                val retry = assertThrows(InvocationTargetException::class.java) { unsupported.invoke(null, application) }
                val site = spiderDiagnostic("site", "spider.create", retry, shared.archive, shared)
                assertEquals(shared.nativeOperation, site.initNativeOperation)
            }
        }
    }
}
