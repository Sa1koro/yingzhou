package tvbox.engine.jar

import android.content.Context
import com.github.catvod.crawler.Spider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.TvboxConfig
import tvbox.engine.core.spider.JarRepository
import tvbox.engine.core.spider.SpiderLoadException
import tvbox.engine.core.spider.spiderArchiveId
import tvbox.engine.jarhost.JarSpiderRuntime
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JarDiagnosticsTest {
    object BrokenInit {
        @JvmStatic fun init(context: Context) {
            throw UnsatisfiedLinkError("Android JNI: JNI_OnLoad failed").initCause(IllegalStateException("${context.javaClass.name}: /private/path?token=private-value"))
        }
    }

    class WorkingSpider : Spider()
    class BrokenSpider : Spider() {
        init { throw NoSuchFieldError("Class com.github.catvod.spider.Init does not have member field 'int number'") }
    }
    class OpaqueSpider : Spider() {
        init { throw ExceptionInInitializerError() }
    }

    @Test
    fun `a shared jar keeps working sites and attaches its first init failure to dependent sites`(@TempDir directory: File) {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(80, 75, 3, 4))))
            val address = upstream.url("/spider?token=private-value").toString()
            var initLookups = 0
            val loader = object : ClassLoader(javaClass.classLoader) {
                override fun loadClass(name: String): Class<*> = when (name) {
                    "com.github.catvod.spider.Init" -> BrokenInit::class.java.also { initLookups++ }
                    "com.github.catvod.spider.Working" -> WorkingSpider::class.java
                    "com.github.catvod.spider.Broken" -> BrokenSpider::class.java
                    "com.github.catvod.spider.Opaque" -> OpaqueSpider::class.java
                    "com.github.catvod.spider.Dependent" -> throw ClassNotFoundException("android.webkit.WebView")
                    "com.github.catvod.spider.Wrapped" -> throw ClassNotFoundException(name, ClassNotFoundException("android.webkit.WebView"))
                    else -> super.loadClass(name)
                }
            }
            val jars = JarRepository(OkHttpClient(), ConfigLocator("http://127.0.0.1:9978/file/"), directory)
            val retired = mutableListOf<ClassLoader>()
            val runtime = object : JarSpiderRuntime(Context(), jars, { 9978 }) {
                override val retireUnusedLoaders = true
                override fun classLoader(jar: File): ClassLoader = loader
                override fun releaseLoader(loader: ClassLoader) { retired.add(loader) }
            }
            runtime.onConfig(TvboxConfig(spider = address))
            runBlocking { runtime.create(SiteBean("working", "working", 3, "csp_Working"), "") }
            val failure = assertThrows(SpiderLoadException::class.java) {
                runBlocking { runtime.create(SiteBean("broken", "broken", 3, "csp_Broken"), "") }
            }
            assertEquals(1, initLookups)
            assertEquals(1, upstream.requestCount)
            val detail = requireNotNull(failure.diagnostic)
            assertEquals("spider.create", detail.stage)
            assertEquals("broken", detail.site)
            assertEquals("missing_member", detail.kind)
            assertEquals("NoSuchFieldError: com.github.catvod.spider.Init.number", detail.cause)
            assertEquals(spiderArchiveId(address), detail.archive)
            assertEquals("UnsatisfiedLinkError: Android JNI: JNI_OnLoad failed", detail.initCause)
            assertFalse(failure.message.orEmpty().contains("private"))
            val opaque = assertThrows(SpiderLoadException::class.java) {
                runBlocking { runtime.create(SiteBean("opaque", "opaque", 3, "csp_Opaque"), "") }
            }.diagnostic
            assertEquals("native", opaque?.kind)
            assertEquals("ExceptionInInitializerError", opaque?.cause)
            assertEquals(detail.initCause, opaque?.initCause)
            for ((api, expectedKind) in mapOf("Absent" to "missing_spider", "Dependent" to "missing_class", "Wrapped" to "missing_class")) {
                val missing = assertThrows(SpiderLoadException::class.java) {
                    runBlocking { runtime.create(SiteBean(api, api, 3, "csp_$api"), "") }
                }
                assertEquals(expectedKind, missing.diagnostic?.kind)
            }
            runtime.onConfig(TvboxConfig(livesJson = """[{"type":3,"name":"live","api":"csp_Working","jar":"$address"}]"""))
            assertEquals(emptyList<ClassLoader>(), retired)
            runtime.onConfigRemoved()
            assertEquals(listOf(loader), retired)
        }
    }

    @Test
    fun `switching subscriptions during a load retires the stale loader instead of caching it`(@TempDir directory: File) = runBlocking {
        MockWebServer().use { upstream ->
            upstream.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(80, 75, 3, 4))))
            val address = upstream.url("/jar").toString()
            val entered = CompletableDeferred<Unit>()
            val resume = CountDownLatch(1)
            val created = mutableListOf<ClassLoader>()
            val released = mutableListOf<ClassLoader>()
            val jars = JarRepository(OkHttpClient(), ConfigLocator("http://127.0.0.1:9978/file/"), directory)
            val runtime = object : JarSpiderRuntime(Context(), jars, { 9978 }) {
                override val retireUnusedLoaders = true
                override fun classLoader(jar: File): ClassLoader {
                    val loader = object : ClassLoader(javaClass.classLoader) {
                        override fun loadClass(name: String): Class<*> = if (name == "com.github.catvod.spider.Working") WorkingSpider::class.java else super.loadClass(name)
                    }
                    created.add(loader)
                    entered.complete(Unit)
                    check(resume.await(10, TimeUnit.SECONDS))
                    return loader
                }
                override fun releaseLoader(loader: ClassLoader) { released.add(loader) }
            }
            val config = TvboxConfig(spider = address)
            val site = SiteBean("working", "working", 3, "csp_Working")
            runtime.onConfig(config)
            val obsolete = async { runCatching { runtime.create(site, "") }.exceptionOrNull() }
            try {
                withTimeout(10_000) { entered.await() }
                runtime.onConfigRemoved()
            } finally {
                resume.countDown()
            }
            assertTrue(obsolete.await() is CancellationException)
            assertEquals(created, released)
            runtime.onConfig(config)
            runtime.create(site, "")
            assertEquals(2, created.size)
            assertEquals(listOf(created.first()), released)
        }
    }
}
