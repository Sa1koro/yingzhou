package tvbox.engine.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.SiteRuntime
import tvbox.engine.core.config.TvboxConfig
import tvbox.engine.core.spider.Spider
import tvbox.engine.core.spider.SpiderLoadException
import tvbox.engine.core.spider.SpiderRuntime
import tvbox.engine.core.spider.spiderDiagnostic
import tvbox.engine.server.sniff.ChildProcesses
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

internal class YingxiaScriptRuntime(
    override val kind: String,
    private val bundle: File,
    private val work: File,
    private val http: OkHttpClient,
    private val locator: ConfigLocator,
    private val log: (String) -> Unit,
) : SpiderRuntime {
    private val python = kind == "py"
    private val executable = File(bundle, if (python) "python/python.exe" else "node.exe").absoluteFile
    private val entry = File(bundle, if (python) "host.py" else "host.mjs").absoluteFile
    private val gson = Gson()
    @Volatile private var origin = ""

    override val available: Boolean get() = executable.isFile && entry.isFile
    override fun supports(site: SiteBean) = site.runtime == if (python) SiteRuntime.PY else SiteRuntime.JS
    override fun onConfig(config: TvboxConfig, origin: String) { this.origin = origin }
    override fun onConfigRemoved() { origin = "" }

    override suspend fun create(site: SiteBean, ext: String): Spider = withContext(Dispatchers.IO) {
        if (!available) throw SpiderLoadException("Yingxia $kind host is missing", code = "YINGXIA_HOST_FAILED")
        val subscription = origin
        val address = locator.locate(site.api).fetchUrl.toHttpUrlOrNull()
            ?: throw SpiderLoadException("Invalid script URL", code = "YINGXIA_SCRIPT_FAILED")
        val contents = http.newCall(Request.Builder().url(address).build()).execute().use { reply ->
            if (!reply.isSuccessful) throw IOException("Script download returned HTTP ${reply.code}")
            val body = reply.body ?: throw IOException("Empty script body")
            val source = body.source()
            if (source.request(8L * 1024 * 1024 + 1)) throw IOException("Script exceeds size limit")
            source.readByteArray()
        }
        if (contents.isEmpty() || contents.toString(Charsets.UTF_8).trimStart('\uFEFF').trimStart().startsWith('<')) {
            throw SpiderLoadException("Script response is not a script", code = "YINGXIA_SCRIPT_FAILED")
        }
        val identity = listOf(address.toString(), subscription, site.key, ext, site.catPawOpen).joinToString("\n")
        val profile = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()))
        val sessions = Files.createDirectories(File(work, "sessions").toPath())
        val cache = Files.createTempDirectory(sessions, "$kind-").toFile().absoluteFile
        val script = File(cache, if (python) "plugin.py" else "plugin.js").apply { writeBytes(contents) }
        val extension = when {
            python && site.key.startsWith("py_") && ext.substringBefore('?').endsWith(".py", ignoreCase = true) -> ""
            !python && ext.startsWith("./") -> subscription.toHttpUrlOrNull()?.resolve(ext)?.toString() ?: ext
            else -> ext
        }
        val request = File(cache, "request.json").apply {
            writeText(JsonObject().apply {
                addProperty("script", script.path)
                addProperty("cache", cache.path)
                addProperty("profile", File(work, "profiles/$profile").absolutePath)
                addProperty("api", address.toString())
                addProperty("key", site.key)
                addProperty("ext", extension)
                addProperty("catPawOpen", site.catPawOpen != null)
                addProperty("subscription", subscription)
                addProperty("cloudAccounts", File(work, "cloud-accounts.json").absolutePath)
            }.toString())
        }
        val flags = if (python) listOf("-I", "-B", "-u", "-X", "utf8") else listOf("--no-warnings", "--experimental-vm-modules")
        val process = try {
            ProcessBuilder(listOf(executable.path) + flags + listOf(entry.path, "--serve", request.path))
                .directory(cache).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).apply {
                    for (name in listOf("TEMP", "TMP", "TMPDIR")) environment()[name] = cache.path
                }.start()
        } catch (error: IOException) {
            cache.deleteRecursively()
            throw error
        }
        ChildProcesses.register(process.toHandle())
        process.onExit().thenAccept { cache.deleteRecursively() }
        log("script.host runtime=yingxia-$kind entry=${entry.name} protocol=serve")
        HostedScript(site.key, site.catPawOpen, cache, process)
    }

    private inner class HostedScript(
        private val site: String, private val catPawOpen: String?, private val cache: File, private val process: Process,
    ) : Spider {
        private val gate = Mutex()
        private val stdin = process.outputWriter(Charsets.UTF_8)

        override suspend fun init(ext: String) = Unit
        override suspend fun home(filter: Boolean) = request("home")
        override suspend fun homeVod(): String? = null
        override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>) =
            request("category", buildMap {
                put("t", tid)
                put("pg", pg)
                if (extend.isNotEmpty()) put("filters", gson.toJson(extend))
            })
        override suspend fun detail(id: String) = request("detail", mapOf("ids" to id))
        override suspend fun search(key: String, quick: Boolean, pg: String) = request("search", mapOf("wd" to key, "pg" to pg, "quick" to quick.toString()))
        override suspend fun play(flag: String, id: String, vipFlags: List<String>) = request("play", mapOf("flag" to flag, "play" to id))
        override fun destroy() { ChildProcesses.kill(process.toHandle()) }

        private suspend fun request(stage: String, params: Map<String, String> = emptyMap()): String? = gate.withLock {
            withContext(Dispatchers.IO) {
                val id = UUID.randomUUID().toString()
                val response = File(cache, "responses/$id.json")
                val answer = try {
                    withTimeoutOrNull(55_000) {
                        val ready = waitFor(File(cache, "ready.json"))
                        if (ready.has("error")) return@withTimeoutOrNull ready
                        val command = JsonObject().apply {
                            addProperty("id", id)
                            add("params", gson.toJsonTree(if (catPawOpen != null) params + ("catPawOpen" to catPawOpen) else params))
                        }
                        stdin.write(command.toString())
                        stdin.newLine()
                        stdin.flush()
                        waitFor(response)
                    } ?: throw IOException("Script host timed out")
                } finally {
                    response.delete()
                }
                if (answer.has("error")) {
                    val code = answer.get("errorCode")?.takeIf { it.isJsonPrimitive }?.asString
                        ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,48}")) } ?: "host-error"
                    log("script.request runtime=yingxia-$kind entry=${entry.name} stage=$stage failed=$code")
                    val error = SpiderLoadException("Yingxia $kind host $stage failed ($code)", code = "YINGXIA_SCRIPT_FAILED")
                    throw SpiderLoadException(error.message.orEmpty(), error, error.code,
                        spiderDiagnostic(site, "spider.$stage", error).copy(cause = code,
                            kind = if (code == "source_http" || code == "source_network") "network" else "other"))
                }
                log("script.request runtime=yingxia-$kind entry=${entry.name} stage=$stage completed")
                answer.get("result")?.takeIf { it.isJsonPrimitive }?.asString
            }
        }

        private suspend fun waitFor(file: File): JsonObject {
            while (!file.isFile) {
                if (!process.isAlive) throw IOException("Script host exited")
                delay(30)
            }
            if (file.length() > 16L * 1024 * 1024) throw IOException("Script host response too large")
            return JsonParser.parseString(file.readText()).asJsonObject
        }
    }
}
