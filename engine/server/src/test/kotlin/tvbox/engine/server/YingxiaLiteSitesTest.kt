package tvbox.engine.server

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.spider.Spider
import tvbox.engine.js.JsEnvironment
import tvbox.engine.js.JsHttp
import tvbox.engine.js.JsModules
import tvbox.engine.js.MemoryJsLocalStore
import java.nio.file.Path

class YingxiaLiteSitesTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `uses the bundled port before falling back once to the original jar`() = runBlocking {
        val assets = directory.toFile()
        val sites = assets.resolve("sites").apply { mkdir() }
        sites.resolve("index.json").writeText("""{"classes":{"Fixture":{"script":"Fixture.js"}}}""")
        sites.resolve("Fixture.js").writeText("""
            import { title } from './shared.js';
            export default {
              home: () => ({ class: [], list: [{ vod_id: 'fixture', vod_name: title }] }),
              detail: () => ({ list: [] }),
            };
        """.trimIndent())
        sites.resolve("shared.js").writeText("export const title = 'QuickJS fixture';")
        val http = OkHttpClient()
        val environment = JsEnvironment(JsModules(http, ConfigLocator("http://localhost/file/"), assets = assets),
            JsHttp(http), MemoryJsLocalStore(), "http://localhost/proxy")
        val runtime = YingxiaLiteSites(assets, environment) {}
        val source = SiteBean("fixture", "Fixture", 3, "csp_Fixture")
        var created = 0
        val spider = runtime.wrap(source, "") { created++; OriginalSpider }!!
        try {
            assertTrue(spider.home(true)!!.contains("QuickJS fixture"))
            assertEquals(0, created)
            assertEquals("original detail", spider.detail("fixture"))
            assertEquals("original home", spider.home(true))
            assertEquals(1, created)
            assertNull(runtime.wrap(source, "") { OriginalSpider })
        } finally {
            spider.destroy()
        }
    }

    private object OriginalSpider : Spider {
        override suspend fun init(ext: String) = Unit
        override suspend fun home(filter: Boolean) = "original home"
        override suspend fun homeVod(): String? = null
        override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>): String? = null
        override suspend fun detail(id: String) = "original detail"
        override suspend fun search(key: String, quick: Boolean, pg: String): String? = null
        override suspend fun play(flag: String, id: String, vipFlags: List<String>): String? = null
        override fun destroy() = Unit
    }
}
