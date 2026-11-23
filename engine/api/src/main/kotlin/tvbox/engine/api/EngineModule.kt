package tvbox.engine.api

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.cacheControl
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.response.respondTextWriter
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.http.CacheControl
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import tvbox.engine.core.Engine
import tvbox.engine.core.EngineException
import tvbox.engine.core.model.ActivateRequest
import tvbox.engine.core.model.AddSubscriptionRequest
import tvbox.engine.core.model.RenameSubscriptionRequest
import tvbox.engine.core.model.ApiError
import tvbox.engine.core.model.ErrorBody
import tvbox.engine.core.model.FavoriteEntry
import tvbox.engine.core.model.HistoryEntry
import tvbox.engine.core.model.LiveList
import tvbox.engine.core.model.PlayInfo
import tvbox.engine.core.model.PlayRequest
import tvbox.engine.core.util.Base64Compat
import tvbox.engine.core.search.SearchEvent
import tvbox.engine.api.access.AccessControl
import tvbox.engine.api.access.installAccess
import tvbox.engine.api.remote.installRemote

val EngineJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

data class EngineModuleOptions(
    /**
     * Development only: allow browser pages on localhost (e.g. the Flutter web build) to call the engine.
     * Off by default so that arbitrary web sites cannot drive a local engine.
     */
    val allowLocalhostCors: Boolean = false,
    /** Pairing, tokens and signed media addresses (see [installAccess]); by default only this machine gets in. */
    val access: tvbox.engine.api.access.AccessControl? = null,
    /** The user's cloud drives and folders (see [driveRoutes]); null: this engine has none. */
    val drives: tvbox.engine.core.drive.DriveService? = null,
    /**
     * The LAN remote control (see [installRemote]); null: this engine has none. One hub for every listener of the
     * engine: the module is installed once per address, and screens and remotes come in over different ones.
     */
    val remote: tvbox.engine.api.remote.RemoteHub? = null,
)

/** Installs protocol v1 (docs/protocol.md) on a Ktor application. Shared by the server and the Android app. */
fun Application.engineModule(engine: Engine, options: EngineModuleOptions = EngineModuleOptions()) {
    install(ContentNegotiation) { json(EngineJson) }
    if (options.allowLocalhostCors) {
        install(CORS) {
            allowOrigins { origin ->
                origin.startsWith("http://localhost:") || origin.startsWith("http://127.0.0.1:")
            }
            allowMethod(HttpMethod.Delete)
            allowHeader(HttpHeaders.ContentType)
            allowHeader(HttpHeaders.Authorization)
        }
    }
    install(StatusPages) {
        exception<EngineException> { call, e ->
            val status = when (e) {
                is EngineException.NotFound -> HttpStatusCode.NotFound
                is EngineException.BadRequest -> HttpStatusCode.BadRequest
                is EngineException.NoConfig -> HttpStatusCode.Conflict
                is EngineException.ConfigLoadFailed -> HttpStatusCode.BadGateway
                is EngineException.Unsupported -> HttpStatusCode.NotImplemented
                is EngineException.SiteFailed -> HttpStatusCode.BadGateway
            }
            call.respond(status, ErrorBody(ApiError(e.code, e.message ?: "", e.diagnostic)))
        }
        exception<BadRequestException> { call, e ->
            call.respond(HttpStatusCode.BadRequest, ErrorBody(ApiError("BAD_REQUEST", e.message ?: "bad request")))
        }
        exception<Throwable> { call, e ->
            call.respond(HttpStatusCode.InternalServerError, ErrorBody(ApiError("INTERNAL", e.message ?: e.javaClass.simpleName)))
        }
    }
    val access = options.access ?: AccessControl()
    installAccess(access)
    options.remote?.let { installRemote(it, access) }

    routing {
        // TVBox 9978 compatibility. Jar spiders probe 9978..9999 with /proxy?do=ck to find the host, and serve media,
        // playlists and keys through /proxy?do=<spider>&... Like TVBox, request headers are passed along with the query.
        get("/proxy") {
            if (call.parameters["do"] == "ck") {
                call.respondText("ok")
                return@get
            }
            val params = LinkedHashMap<String, String>()
            call.request.headers.forEach { name, values -> values.firstOrNull()?.let { params[name.lowercase()] = it } }
            call.request.queryParameters.forEach { name, values -> values.firstOrNull()?.let { params[name] = it } }
            val response = engine.proxy(params)
            if (response == null) {
                call.respond(HttpStatusCode.NotFound, ErrorBody(ApiError("NOT_FOUND", "no spider handled this proxy request")))
                return@get
            }
            response.headers.forEach { (k, v) ->
                if (!k.equals(HttpHeaders.ContentType, true) && !k.equals(HttpHeaders.ContentLength, true) && !k.equals(HttpHeaders.TransferEncoding, true)) {
                    call.response.headers.append(k, v, safeOnly = false)
                }
            }
            val contentType = runCatching { ContentType.parse(response.contentType) }.getOrDefault(ContentType.Application.OctetStream)
            call.respondOutputStream(contentType, HttpStatusCode.fromValue(response.status)) {
                response.body?.use { it.copyTo(this, 64 * 1024) }
            }
        }

        // What the playlists below point at when the engine caches the stream, and the cache's own API.
        streamCacheRoutes(engine)
        // Episodes downloaded for offline viewing: the queue's API, and what a finished download plays from.
        downloadRoutes(engine)

        // Subtitles (players open them) and danmaku, see MediaExtrasRoutes.
        mediaExtrasRoutes(engine)
        // Cloud drives and folders: their API and the route that streams their files to players.
        options.drives?.let { driveRoutes(it) }

        // HLS playlists the engine cleans of ads (see M3u8Cleaner). Any trouble sends the player to the original url.
        get("/m3u8") {
            val url = call.parameters["url"].orEmpty()
            if (!url.startsWith("http")) throw BadRequestException("url is required")
            val headers = call.parameters["h"]?.let { h ->
                runCatching {
                    Json.parseToJsonElement(String(Base64Compat.decode(h))).jsonObject.mapValues { (it.value as JsonPrimitive).content }
                }.getOrNull()
            } ?: emptyMap()
            val text = runCatching {
                engine.playlist(url, headers, call.selfBase() + "/m3u8?", key = call.parameters["k"], userAgent = call.request.headers[HttpHeaders.UserAgent], direct = call.parameters["d"] == "1")
            }.getOrNull()
            if (text == null) {
                call.respondRedirect(url, permanent = false)
            } else {
                call.respondText(text, ContentType("application", "vnd.apple.mpegurl"))
            }
        }

        route("/api/v1") {
            get("/info") { call.respond(engine.info) }

            get("/subscriptions") { call.respond(engine.subscriptions()) }
            post("/subscriptions") {
                val req = call.receive<AddSubscriptionRequest>()
                call.respond(HttpStatusCode.Created, engine.addSubscription(req.url, req.name))
            }
            put("/subscriptions/{id}") {
                val req = call.receive<RenameSubscriptionRequest>()
                call.respond(engine.renameSubscription(call.parameters["id"]!!, req.name))
            }
            delete("/subscriptions/{id}") {
                engine.removeSubscription(call.parameters["id"]!!)
                call.respond(HttpStatusCode.NoContent)
            }
            post("/subscriptions/{id}/activate") {
                // The body is optional; an empty body means "first repo".
                val req = runCatching { call.receive<ActivateRequest>() }.getOrNull()
                call.respond(engine.activate(call.parameters["id"]!!, req?.repo))
            }

            get("/config") { call.respond(engine.config()) }
            // Downloads the active config again; the one in use stays when that fails.
            post("/config/reload") { call.respond(engine.reloadConfig()) }
            // The data cache: copies of configs, site answers, ext files, live lists.
            get("/cache") { call.respond(engine.cacheInfo()) }
            delete("/cache") { call.respond(engine.clearCache()) }

            get("/settings") { call.respond(engine.settings()) }
            // What the active config's hosts / headers / doh / ads and the DNS setting do to the engine's requests.
            get("/network") { call.respond(engine.network()) }
            // A client may send only the settings it knows; the others stay as they are.
            put("/settings") { call.respond(engine.updateSettings(mergedSettings(engine.settings(), call.receive<JsonObject>()))) }
            // The GitHub proxies offered, and measuring them: one small GitHub file through each, past the setting.
            get("/github-proxy") { call.respond(engine.githubProxies()) }
            post("/github-proxy/probe") {
                val req = runCatching { call.receive<tvbox.engine.core.model.GithubProxyProbeRequest>() }.getOrNull()
                call.respond(engine.probeGithubProxies(req?.prefixes.orEmpty()))
            }
            get("/sites") { call.respond(engine.sites()) }

            route("/sites/{site}") {
                // `refresh=1` asks the site now instead of answering from the data cache.
                get("/home") { call.respond(engine.home(call.site(), refresh = call.parameters["refresh"] == "1")) }
                get("/category") {
                    val tid = call.required("tid")
                    val page = call.parameters["pg"]?.toIntOrNull() ?: 1
                    call.respond(engine.category(call.site(), tid, page, call.filters(), refresh = call.parameters["refresh"] == "1"))
                }
                get("/detail") { call.respond(call.driveDetail(options.drives) ?: engine.detail(call.site(), call.required("id"), refresh = call.parameters["refresh"] == "1")) }
                get("/search") {
                    val page = call.parameters["pg"]?.toIntOrNull() ?: 1
                    call.respond(engine.search(call.site(), call.required("wd"), page))
                }
            }

            get("/search/stream") {
                val keyword = call.required("wd")
                val sites = call.parameters["sites"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
                val events = engine.searchAll(keyword, sites)
                call.response.cacheControl(CacheControl.NoCache(null))
                call.respondTextWriter(ContentType.Text.EventStream) {
                    events.collect { event ->
                        val (name, data) = when (event) {
                            is SearchEvent.Site -> "site" to EngineJson.encodeToString(event.event)
                            is SearchEvent.Results -> "results" to EngineJson.encodeToString(event.event)
                            is SearchEvent.End -> "end" to EngineJson.encodeToString(event.event)
                        }
                        write("event: $name\ndata: $data\n\n")
                        flush()
                    }
                }
            }

            // Live TV: the config's `lives` as sources, each a list of channel groups; EPG per channel and day.
            route("/live") {
                get { call.respond(engine.liveSources()) }
                get("/{index}") {
                    val index = call.parameters["index"]?.toIntOrNull() ?: throw BadRequestException("index must be a number")
                    call.respond(forClient(engine.liveList(index, refresh = call.parameters["refresh"] == "1"), call))
                }
                get("/{index}/epg") {
                    val index = call.parameters["index"]?.toIntOrNull() ?: throw BadRequestException("index must be a number")
                    val channel = call.parameters["channel"]?.takeIf { it.isNotBlank() } ?: throw BadRequestException("channel is required")
                    call.respond(engine.epg(index, channel, call.parameters["date"].orEmpty()))
                }
                // Catch-up: the address of a past time range of a channel (start / end in epoch milliseconds).
                get("/{index}/catchup") {
                    val index = call.parameters["index"]?.toIntOrNull() ?: throw BadRequestException("index must be a number")
                    val channel = call.parameters["channel"]?.takeIf { it.isNotBlank() } ?: throw BadRequestException("channel is required")
                    val start = call.parameters["start"]?.toLongOrNull() ?: throw BadRequestException("start must be epoch milliseconds")
                    val end = call.parameters["end"]?.toLongOrNull() ?: throw BadRequestException("end must be epoch milliseconds")
                    val line = call.parameters["line"]?.toIntOrNull() ?: 0
                    call.respond(forClient(engine.liveCatchup(index, call.parameters["group"], channel, line, start, end), call))
                }
            }

            post("/play/resolve") {
                val request = call.receive<PlayRequest>()
                val info = call.drivePlay(options.drives, request) ?: engine.resolvePlay(request)
                if (info.driveSignIn != null) call.response.headers.append("Cache-Control", "no-store")
                call.respond(forClient(info, call))
            }

            post("/play/drive-sign-in/poll") {
                call.response.headers.append("Cache-Control", "no-store")
                call.respond(engine.pollDriveSignIn(call.receive<tvbox.engine.core.model.DriveSignInPoll>()))
            }

            route("/history") {
                get { call.respond(engine.library.history(call.parameters["limit"]?.toIntOrNull() ?: 100)) }
                delete {
                    engine.library.clearHistory()
                    call.respond(HttpStatusCode.NoContent)
                }
                route("/{site}/{vodId}") {
                    get {
                        val entry = engine.library.historyFor(call.site(), call.vodId()) ?: throw EngineException.NotFound("no history for this video")
                        call.respond(entry)
                    }
                    put {
                        val body = call.receive<HistoryEntry>()
                        call.respond(engine.library.putHistory(body.copy(site = call.site(), vodId = call.vodId())))
                    }
                    delete {
                        engine.library.removeHistory(call.site(), call.vodId())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }

            route("/favorites") {
                get { call.respond(engine.library.favorites()) }
                route("/{site}/{vodId}") {
                    get {
                        val entry = engine.library.favoriteFor(call.site(), call.vodId()) ?: throw EngineException.NotFound("not a favorite")
                        call.respond(entry)
                    }
                    put {
                        val body = call.receive<FavoriteEntry>()
                        call.respond(engine.library.putFavorite(body.copy(site = call.site(), vodId = call.vodId())))
                    }
                    delete {
                        engine.library.removeFavorite(call.site(), call.vodId())
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}

private fun RoutingCall.site(): String = parameters["site"]!!

private fun RoutingCall.vodId(): String = parameters["vodId"]!!

private fun RoutingCall.required(name: String): String =
    parameters[name]?.takeIf { it.isNotBlank() } ?: throw EngineException.BadRequest("missing parameter: $name")

/** `f` is a URL-encoded JSON object of filter key -> value. */
private fun RoutingCall.filters(): Map<String, String> {
    val raw = parameters["f"]?.takeIf { it.isNotBlank() } ?: return emptyMap()
    val obj: JsonObject = try {
        EngineJson.parseToJsonElement(raw).jsonObject
    } catch (e: Exception) {
        throw EngineException.BadRequest("f must be a JSON object")
    }
    return obj.mapValues { (_, v) -> (v as? JsonPrimitive)?.content ?: v.toString() }
}

/** The engine as this caller reaches it, e.g. `http://192.168.1.5:9978` for a TV on the LAN. */
private fun RoutingCall.selfBase(): String = "http://" + (request.headers[HttpHeaders.Host] ?: "127.0.0.1:${request.local.localPort}")

/**
 * Engine urls in a play answer (playlist endpoint, spider proxies) are built for 127.0.0.1; a client on another
 * device gets them on the address it used to reach the engine.
 */
private fun forClient(list: LiveList, call: RoutingCall): LiveList {
    val loopback = "http://127.0.0.1:${call.request.local.localPort}/"
    val self = call.selfBase() + "/"
    if (self == loopback) return list
    fun fix(url: String) = if (url.startsWith(loopback)) self + url.removePrefix(loopback) else url
    return list.copy(groups = list.groups.map { g -> g.copy(channels = g.channels.map { c -> c.copy(urls = c.urls.map { it.copy(url = fix(it.url)) }) }) })
}

private fun forClient(catchup: tvbox.engine.core.model.LiveCatchup, call: RoutingCall): tvbox.engine.core.model.LiveCatchup {
    val loopback = "http://127.0.0.1:${call.request.local.localPort}/"
    val self = call.selfBase() + "/"
    if (self == loopback) return catchup
    fun fix(url: String) = if (url.startsWith(loopback)) self + url.removePrefix(loopback) else url
    return catchup.copy(url = fix(catchup.url), alternates = catchup.alternates.map { it.copy(url = fix(it.url)) })
}

private fun forClient(info: PlayInfo, call: RoutingCall): PlayInfo {
    val loopback = "http://127.0.0.1:${call.request.local.localPort}/"
    val self = call.selfBase() + "/"
    if (self == loopback) return info
    fun fix(url: String) = if (url.startsWith(loopback)) self + url.removePrefix(loopback) else url
    return info.copy(
        url = fix(info.url),
        relay = fix(info.relay),
        sniff = info.sniff?.let { it.copy(pageUrl = fix(it.pageUrl)) },
        subtitles = info.subtitles.map { it.copy(url = fix(it.url)) },
    )
}
