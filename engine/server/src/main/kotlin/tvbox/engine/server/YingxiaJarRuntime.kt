package tvbox.engine.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.SiteRuntime
import tvbox.engine.core.config.TvboxConfig
import tvbox.engine.core.model.DriveSignInPrompt
import tvbox.engine.core.model.DriveSignInStatus
import tvbox.engine.core.play.JarParsers
import tvbox.engine.core.spider.JarRepository
import tvbox.engine.core.spider.Spider
import tvbox.engine.core.spider.SpiderLoadException
import tvbox.engine.core.spider.SpiderRuntime
import tvbox.engine.core.spider.spiderArchiveId
import tvbox.engine.core.spider.spiderDiagnostic
import tvbox.engine.jar.DesktopJarRuntime
import java.io.File
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

internal class YingxiaJarRuntime(
    private val host: YingxiaProcessHost,
    private val jars: JarRepository,
    private val root: File,
    private val parsers: DesktopJarRuntime,
    private val lite: YingxiaLiteSites?,
    private val log: (String) -> Unit,
) : SpiderRuntime, JarParsers by parsers {
    private data class Subscription(val spider: String = "", val origin: String = "")

    private data class HostedPlay(
        val flag: String, val id: String, val handle: String, val started: Long, val result: Deferred<Result<String?>>,
    )

    @Volatile private var subscription = Subscription()
    private val gson = Gson()
    private val accountsFile = File(root, "cloud-accounts.json")
    private val accounts = YingxiaAccounts(accountsFile)

    override val kind = "jar"
    override val available: Boolean get() = host.available()
    override fun supports(site: SiteBean) = site.runtime == SiteRuntime.JAR
    override fun onConfig(config: TvboxConfig, origin: String) {
        parsers.onConfig(config)
        subscription = Subscription(config.spider, origin)
    }

    override fun onConfigRemoved() {
        parsers.onConfigRemoved()
        subscription = Subscription()
    }

    override suspend fun create(site: SiteBean, ext: String): Spider =
        lite?.wrap(site, ext) { original(site, ext) } ?: original(site, ext)

    private suspend fun original(site: SiteBean, ext: String): Spider = withContext(Dispatchers.IO) {
        val active = subscription
        val spec = site.jar.ifBlank { active.spider }
        val archiveId = spiderArchiveId(spec)
        val jar = try {
            if (spec.isBlank()) throw SpiderLoadException("No JAR declared for this site")
            jars.fetch(spec)
        } catch (problem: Exception) {
            if (problem is kotlinx.coroutines.CancellationException) throw problem
            throw SpiderLoadException("Could not fetch site JAR", problem, diagnostic = spiderDiagnostic(site.key, "jar.fetch", problem, archiveId))
        }
        val digest = MessageDigest.getInstance("SHA-256")
        jar.inputStream().use { input ->
            val bytes = ByteArray(8192)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val jarKey = HexFormat.of().formatHex(digest.digest())
        val profile = HexFormat.of().formatHex(digest.digest(gson.toJson(listOf(active.origin, spec, site, ext)).toByteArray()))
        val session = "$profile-$jarKey"
        val cache = File(root, "sessions/$session").apply { mkdirs() }
        val input = JsonObject().apply {
            addProperty("session", session)
            addProperty("runtime", jarKey)
            addProperty("runtimeCache", File(root, "runtimes/$jarKey").absolutePath)
            addProperty("jar", jar.absolutePath)
            addProperty("cache", cache.absolutePath)
            addProperty("conversionCache", File(root, "converted").absolutePath)
            addProperty("profile", File(root, "profiles/$profile").absolutePath)
            addProperty("cloudAccounts", accountsFile.absolutePath)
            addProperty("api", site.api)
            addProperty("key", site.key)
            addProperty("ext", ext)
            addProperty("subscription", active.origin)
        }
        HostedSpider(site.key, jarKey.take(12), input, cache)
    }

    private inner class HostedSpider(
        private val site: String, private val archive: String, private val input: JsonObject, private val cache: File,
    ) : Spider {
        private val promptFile = File(cache, "login-prompt.json")
        @Volatile private var detailId: String? = null
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val signInGate = Mutex()
        private var pending: HostedPlay? = null

        override suspend fun init(ext: String) = Unit
        override suspend fun home(filter: Boolean): String? = request("home")
        override suspend fun homeVod(): String? = null
        override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>) =
            request("category", mapOf("t" to tid, "pg" to pg, "filters" to gson.toJson(extend)))

        override suspend fun detail(id: String): String? = request("detail", mapOf("ids" to id)).also {
            if (!it.isNullOrBlank()) detailId = id
        }

        override suspend fun search(key: String, quick: Boolean, pg: String) = request("search", mapOf("wd" to key, "pg" to pg))

        override suspend fun play(flag: String, id: String, vipFlags: List<String>): String? {
            val saved = signInGate.withLock {
                val old = pending?.takeIf { it.flag == flag && it.id == id && it.result.isCompleted }
                pending?.result?.cancel()
                pending = null
                old
            }
            return saved?.result?.await()?.getOrThrow() ?: doPlay(flag, id)
        }

        override suspend fun playWithSignIn(flag: String, id: String, vipFlags: List<String>, onPrompt: (DriveSignInPrompt) -> Unit): String? {
            val playback = signInGate.withLock {
                pending?.result?.cancel()
                val started = System.currentTimeMillis()
                val result = scope.async {
                    try { Result.success(doPlay(flag, id)) }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (error: Exception) { Result.failure(error) }
                    finally { adoptSignedIn(started) }
                }
                HostedPlay(flag, id, UUID.randomUUID().toString(), started, result).also { pending = it }
            }
            var prompted = false
            try {
                while (!playback.result.isCompleted) {
                    val prompt = withContext(Dispatchers.IO) { readPrompt(playback) }
                    if (prompt != null) {
                        prompted = true
                        onPrompt(prompt)
                        break
                    }
                    delay(250)
                }
                return playback.result.await().getOrThrow()
            } finally {
                if (!prompted) signInGate.withLock {
                    if (pending === playback) pending = null
                    playback.result.cancel()
                }
            }
        }

        override suspend fun pollHostedSignIn(handle: String): DriveSignInStatus = signInGate.withLock {
            val playback = pending?.takeIf { it.handle == handle } ?: return@withLock DriveSignInStatus("expired")
            withContext(Dispatchers.IO) { adoptSignedIn(playback.started) }
            if (!playback.result.isCompleted) return@withLock DriveSignInStatus("waiting")
            val result = runCatching { playback.result.await().getOrThrow() }.getOrNull()
            if (result.isNullOrBlank()) {
                pending = null
                DriveSignInStatus("error")
            } else DriveSignInStatus("success")
        }

        private fun readPrompt(playback: HostedPlay): DriveSignInPrompt? {
            if (promptFile.length() !in 1..8192) return null
            return runCatching {
                val prompt = JsonParser.parseString(promptFile.readText()).asJsonObject
                val issued = prompt.get("time").asLong
                if (issued < playback.started || issued > System.currentTimeMillis()) return null
                val age = System.currentTimeMillis() - issued
                if (age > (prompt.get("seconds")?.asLong ?: 180L).coerceIn(1, 180) * 1000) return null
                if (prompt.get("signedIn")?.asBoolean == true) return null
                val provider = prompt.get("drive").asString.takeIf { it.matches(Regex("[a-zA-Z0-9_-]{1,32}")) } ?: return null
                val content = prompt.get("content")?.asString?.takeIf { it.length in 1..2048 && it.none(Char::isISOControl) }
                val image = prompt.get("image")?.asString?.takeIf { it.length <= 2048 }?.toHttpUrlOrNull()
                    ?.takeIf { it.username.isEmpty() && it.password.isEmpty() }?.toString()
                if (content == null && image == null) null else DriveSignInPrompt(playback.handle, provider, content.orEmpty(), image)
            }.getOrNull()
        }

        private fun adoptSignedIn(started: Long) {
            val notice = YingxiaAccounts.read(promptFile) ?: return
            val signedIn = notice.get("signedIn")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
            val issued = notice.get("time")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong ?: return
            if (signedIn && issued in started..System.currentTimeMillis()) adopt(notice)
        }

        private fun adopt(notice: JsonObject) {
            runCatching { accounts.adopt(notice) }.onFailure {
                log("jar.accounts runtime=yingxia jar=$archive failed=${it.javaClass.simpleName}")
            }
        }

        private suspend fun doPlay(flag: String, id: String): String? = request("play", buildMap {
            put("flag", flag)
            put("play", id)
            detailId?.let { put("detailID", it) }
        })

        override fun destroy() { scope.cancel() }

        private suspend fun request(stage: String, params: Map<String, String> = emptyMap()): String? {
            val deadline = if (stage == "play") 95L else 30L
            val request = input.deepCopy().apply {
                addProperty("deadline", deadline)
                add("params", gson.toJsonTree(params))
            }
            log("jar.request runtime=yingxia entry=${YingxiaProcessHost.ENTRY_POINT} stage=$stage jar=$archive")
            val answer = host.request(request, deadline + 60)
            withContext(Dispatchers.IO) {
                YingxiaAccounts.read(File(cache, "plugin-accounts.json"))?.let(::adopt)
            }
            if (answer.has("error")) {
                val code = answer.get("errorCode")?.takeIf { it.isJsonPrimitive }?.asString
                    ?.takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,48}")) } ?: "host-error"
                val trace = answer.get("trace")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                val types = failureType.findAll(trace.take(16_384)).map { it.groupValues[1] }.distinct().take(5).joinToString("/")
                val frames = failureFrame.findAll(trace.take(16_384)).map { it.groupValues[1] }.distinct().take(4).joinToString("/")
                log("jar.request runtime=yingxia stage=$stage jar=$archive failed=$code causes=$types frames=$frames")
                val error = SpiderLoadException("Yingxia JavaHost $stage failed ($code)", code = "YINGXIA_SPIDER_FAILED")
                val detail = listOf(code, types, frames.substringBefore('/')).filter(String::isNotBlank).joinToString(" · ")
                val diagnostic = spiderDiagnostic(site, "spider.$stage", error, archive).copy(
                    kind = when (code) {
                        "source_network", "source_http" -> "network"
                        "unsupported_android" -> "native"
                        "class_conversion" -> "missing_class"
                        else -> "other"
                    },
                    cause = detail,
                )
                throw SpiderLoadException(error.message.orEmpty(), error, error.code, diagnostic)
            }
            log("jar.request runtime=yingxia stage=$stage jar=$archive completed")
            return answer.get("result")?.takeUnless { it.isJsonNull }?.let { if (it.isJsonPrimitive) it.asString else it.toString() }
        }
    }

    private companion object {
        val failureType = Regex("(?m)^(?:Caused by: )?((?:[A-Za-z_$][A-Za-z0-9_$]*\\.){1,14}[A-Za-z_$][A-Za-z0-9_$]{0,79})(?=[:\\r\\n]|$)")
        val failureFrame = Regex("(?m)^\\s*at ((?:tvbox\\.runtime|com\\.github\\.catvod)(?:\\.[A-Za-z_$][A-Za-z0-9_$]*){1,14})\\(")
    }
}
