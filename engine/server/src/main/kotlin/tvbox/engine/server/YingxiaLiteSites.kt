package tvbox.engine.server

import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.model.DriveSignInPrompt
import tvbox.engine.core.spider.Spider
import tvbox.engine.js.JsEnvironment
import tvbox.engine.js.JsSpider
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

internal class YingxiaLiteSites(
    assets: File,
    private val environment: JsEnvironment,
    private val log: (String) -> Unit,
) {
    private val classes = runCatching {
        JsonParser.parseString(File(assets, "sites/index.json").readText()).asJsonObject.getAsJsonObject("classes")
    }.getOrNull()
    private val fallback = ConcurrentHashMap<String, Long>()

    fun wrap(site: SiteBean, ext: String, jar: suspend () -> Spider): Spider? {
        val path = ext.trim().substringBefore('?').lowercase()
        if (path.endsWith(".js") || path.endsWith(".py")) return null
        val entry = classes?.getAsJsonObject(site.api.removePrefix("csp_"))?.get("script")?.asString ?: return null
        val script = when {
            entry.startsWith("https://") -> entry
            entry.matches(Regex("[A-Za-z0-9_.-]+\\.js")) -> "assets://sites/$entry"
            else -> return null
        }
        if ((fallback[site.key] ?: 0) > System.nanoTime()) return null
        return Routed(site, ext, script, jar)
    }

    private inner class Routed(
        private val site: SiteBean, private val ext: String, private val script: String, private val createJar: suspend () -> Spider,
    ) : Spider {
        private val gate = Mutex()
        private var javascript: Spider? = null
        @Volatile private var jar: Spider? = null
        private var useJar = false

        override suspend fun init(ext: String) = Unit
        override suspend fun home(filter: Boolean) = call("home", { it.hasItems("class") || it.hasItems("list") }, port = {
            val home = home(filter)
            val value = JsonParser.parseString(home ?: "{}").asJsonObject
            if (!home.hasItems("list")) {
                val recommended = homeVod()
                if (recommended.hasItems("list")) value.add("list", JsonParser.parseString(recommended).asJsonObject.get("list"))
            }
            value.toString()
        }) { home(filter) }

        override suspend fun homeVod(): String? = null
        override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>) =
            call("category", { extend.isNotEmpty() || pg != "1" || it.hasItems("list") }) { category(tid, pg, filter, extend) }
        override suspend fun detail(id: String) = call("detail", { it.hasItems("list") }) { detail(id) }
        override suspend fun search(key: String, quick: Boolean, pg: String) = call("search") { search(key, quick, pg) }
        override suspend fun play(flag: String, id: String, vipFlags: List<String>) =
            call("play", ::playable) { play(flag, id, vipFlags) }
        override suspend fun playWithSignIn(flag: String, id: String, vipFlags: List<String>, onPrompt: (DriveSignInPrompt) -> Unit) =
            call("play", ::playable) { playWithSignIn(flag, id, vipFlags, onPrompt) }
        override suspend fun pollHostedSignIn(handle: String) = jar?.pollHostedSignIn(handle)

        private suspend fun call(
            stage: String,
            usable: (String?) -> Boolean = { true },
            port: (suspend Spider.() -> String?)? = null,
            action: suspend Spider.() -> String?,
        ): String? = gate.withLock {
            if (!useJar) {
                try {
                    val current = javascript ?: JsSpider.load(site.copy(api = script), ext, environment).also { javascript = it }
                    val result = (port ?: action).invoke(current)
                    if (usable(result)) {
                        log("lite.request runtime=yingxia-quickjs stage=$stage completed")
                        return@withLock result
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    log("lite.request runtime=yingxia-quickjs stage=$stage failed=${error.javaClass.simpleName}")
                }
                useJar = true
                fallback[site.key] = System.nanoTime() + TimeUnit.MINUTES.toNanos(30)
                log("lite.fallback runtime=yingxia-quickjs stage=$stage next=yingxia-java")
            }
            (jar ?: createJar().also { jar = it }).action()
        }

        override fun destroy() {
            javascript?.destroy()
            jar?.destroy()
        }
    }

    private fun String?.hasItems(key: String): Boolean = runCatching {
        this != null && !JsonParser.parseString(this).asJsonObject.getAsJsonArray(key).isEmpty
    }.getOrDefault(false)

    private fun playable(text: String?): Boolean = runCatching {
        val url = JsonParser.parseString(text ?: "{}").asJsonObject.get("url") ?: return false
        when {
            url.isJsonArray -> !url.asJsonArray.isEmpty
            url.isJsonPrimitive -> url.asString.isNotBlank()
            else -> false
        }
    }.getOrDefault(false)
}
