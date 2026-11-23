package tvbox.engine.core.spider

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tvbox.engine.core.Engine
import tvbox.engine.core.config.ConfigLoader
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.SiteRuntime
import tvbox.engine.core.model.Capabilities
import tvbox.engine.core.model.DriveSignInPoll
import tvbox.engine.core.model.EngineInfo
import tvbox.engine.core.model.PlayRequest
import tvbox.engine.core.net.SiteHttp
import java.util.Collections

/**
 * TVBox hands a spider its `ext` as written (`JarLoader.getSpider` → `spider.init(context, ext)`); only CMS and
 * `type: 4` sites have an url `ext` replaced by the text it points at (`getFixUrl`). App spiders whose `ext` is their
 * domain, or `address|key`, broke when they were given the page at that address instead.
 */
class SpiderExtTest {
    private val server = MockWebServer()
    private val paths: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val extendParams: MutableList<String> = Collections.synchronizedList(ArrayList())
    private fun url(path: String) = server.url(path).toString()

    /** What each spider was created with, by site key. */
    private val created = java.util.concurrent.ConcurrentHashMap<String, String>()
    private var recommended: () -> String? = { null }
    private var readyForSignIn = false
    private var signedIn = false
    private val played = mutableListOf<Pair<String, String>>()

    private val runtime = object : SpiderRuntime {
        override val kind = "jar"
        override fun supports(site: SiteBean) = site.runtime == SiteRuntime.JAR
        override suspend fun create(site: SiteBean, ext: String): Spider {
            created[site.key] = ext
            return object : Spider {
                override suspend fun init(ext: String) {}
                override suspend fun home(filter: Boolean) = """{"class":[{"type_id":"1","type_name":"电影"}],"list":[]}"""
                override suspend fun homeVod(): String? = recommended()
                override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>): String? = null
                override suspend fun detail(id: String): String? = null
                override suspend fun search(key: String, quick: Boolean, pg: String): String? = null
                override suspend fun play(flag: String, id: String, vipFlags: List<String>): String? {
                    if (!readyForSignIn) return null
                    played += flag to id
                    return if (signedIn) """{"url":"https://cdn.test/movie.mp4","parse":0}"""
                    else """{"msg":"[quark](resolvePlay) 未登录"}"""
                }
                override val supportsDriveSignIn get() = readyForSignIn && site.key == "app"
                override suspend fun driveSignIn(params: Map<String, String>): ProxyResponse {
                    assertEquals("pan", params["do"])
                    assertEquals("quark", params["provider"])
                    val payload = when (params["action"]) {
                        "loginStart" -> """{"code":0,"qrUrl":"https://login.test/link","taskId":"plugin-private-task"}"""
                        "loginPoll" -> {
                            assertEquals("plugin-private-task", params["taskId"])
                            signedIn = true
                            """{"status":"success"}"""
                        }
                        else -> error("Unexpected sign-in action")
                    }
                    return ProxyResponse(200, "application/json", payload.byteInputStream())
                }
                override suspend fun live(url: String) = "组,#genre#\n台,http://s.test/1.m3u8"
                override fun destroy() {}
            }
        }
    }

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                paths += path
                return when (path) {
                    "/tv.json" -> MockResponse().setBody(
                        """{
                          "spider": "${url("/spider.jar")}",
                          "sites": [
                            {"key":"app","name":"APP","type":3,"api":"csp_AppRJ","ext":"${url("/app-home")}"},
                            {"key":"keyed","name":"带密钥","type":3,"api":"csp_AppQi","ext":"${url("/lyjszx.txt")}|oW5UxZ5kWVr1DI69"},
                            {"key":"cms","name":"CMS","type":1,"api":"${url("/cms")}","ext":"${url("/cms-ext.txt")}"}
                          ],
                          "lives": [{"name":"蜘蛛直播","type":3,"api":"csp_Live","url":"yqk","ext":"${url("/live-ext")}"}]
                        }""",
                    )
                    "/app-home", "/lyjszx.txt", "/live-ext" -> MockResponse().setBody("<!doctype html><html>not for the spider</html>")
                    "/cms-ext.txt" -> MockResponse().setBody("CMS-EXTEND")
                    "/cms" -> {
                        request.requestUrl!!.queryParameter("extend")?.let { extendParams += it }
                        MockResponse().setBody("""{"list":[{"vod_id":"9","vod_name":"片","vod_play_from":"线","vod_play_url":"第1集${'$'}http://s.test/1.m3u8"}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun engine() = Engine(
        EngineInfo("tvbox-engine", "test", platform = "test", capabilities = Capabilities(false, false, false, false)),
        ConfigLoader(OkHttpClient(), ConfigLocator("http://127.0.0.1:19978/file/")),
        SiteHttp(OkHttpClient()),
        proxyBase = "http://127.0.0.1:19978/proxy?",
        runtimes = listOf(runtime),
    )

    @Test
    fun `recommendation failures preserve home categories and distinguish errors from a legitimate empty result`() = runBlocking {
        val site = SiteBean("app", "APP", 3, "csp_App")
        val source = SpiderSource(site, runtime) { emptyList() }
        assertNull(source.home().warning)
        recommended = { """{"list":[]}""" }
        assertNull(source.home().warning)

        recommended = { "not JSON" }
        val partial = source.home()
        assertEquals("电影", partial.classes.single().name)
        assertEquals("spider.recommend", partial.warning?.stage)
        assertEquals("invalid_result", partial.warning?.kind)
        recommended = { throw NoClassDefFoundError("android/content/ContextWrapper") }
        assertEquals("NoClassDefFoundError: android.content.ContextWrapper", source.home().warning?.cause)
    }

    @Test
    fun `spiders get their ext as written, CMS sites the text it points at`() = runBlocking {
        val engine = engine()
        engine.activate(engine.addSubscription(url("/tv.json"), null).id)

        assertEquals(listOf("电影"), engine.home("app").classes.map { it.name })
        engine.home("keyed")
        assertEquals(url("/app-home"), created["app"], "a domain stays a domain")
        assertEquals(url("/lyjszx.txt") + "|oW5UxZ5kWVr1DI69", created["keyed"], "address|key stays as written")

        assertEquals("台", engine.liveList(0).groups.single().channels.single().name)
        assertEquals(url("/live-ext"), created.values.single { it.endsWith("/live-ext") }, "a live spider too")
        assertTrue(paths.none { it == "/app-home" || it == "/lyjszx.txt" || it == "/live-ext" }, "nothing a spider's ext points at was fetched: $paths")

        engine.detail("cms", "9")
        assertEquals(listOf("CMS-EXTEND"), extendParams, "TVBox getFixUrl: a CMS site's url ext is sent as its text")
    }

    @Test
    fun `spider QR authorizes the same source and lets the original episode resolve`() = runBlocking {
        readyForSignIn = true
        val engine = engine()
        engine.activate(engine.addSubscription(url("/tv.json"), null).id)
        val request = PlayRequest("app", "line", "episode", offerDriveSignIn = true)
        val waiting = engine.resolvePlay(request)
        assertEquals("drive_sign_in", waiting.kind)
        val prompt = requireNotNull(waiting.driveSignIn)
        assertEquals("quark", prompt.provider)
        assertEquals("https://login.test/link", prompt.qrUrl)
        assertTrue(prompt.handle != "plugin-private-task")
        assertEquals("expired", engine.pollDriveSignIn(DriveSignInPoll("keyed", prompt.handle)).status)
        assertEquals("success", engine.pollDriveSignIn(DriveSignInPoll("app", prompt.handle)).status)
        assertEquals("expired", engine.pollDriveSignIn(DriveSignInPoll("app", prompt.handle)).status)
        assertEquals("https://cdn.test/movie.mp4", engine.resolvePlay(request.copy(offerDriveSignIn = false)).url)
        assertEquals(listOf("line" to "episode", "line" to "episode"), played)
    }
}
