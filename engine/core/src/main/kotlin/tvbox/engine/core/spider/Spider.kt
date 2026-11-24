package tvbox.engine.core.spider

import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.TvboxConfig
import tvbox.engine.core.model.SpiderDiagnostic
import tvbox.engine.core.model.DriveSignInPrompt
import tvbox.engine.core.model.DriveSignInStatus

/**
 * The TVBox spider contract (`com.github.catvod.crawler.Spider`), independent of how the spider runs
 * (QuickJS, JVM/ART for jar, Python). Every method returns TVBox JSON text, or "" / null when the spider
 * has nothing; [SpiderSource] turns that into normalised models.
 */
interface Spider {
    fun diagnostic(site: String, stage: String, error: Throwable): SpiderDiagnostic = spiderDiagnostic(site, stage, error)

    suspend fun init(ext: String)
    suspend fun home(filter: Boolean): String?
    suspend fun homeVod(): String?
    suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>): String?
    suspend fun detail(id: String): String?
    suspend fun search(key: String, quick: Boolean, pg: String): String?
    suspend fun play(flag: String, id: String, vipFlags: List<String>): String?

    suspend fun playWithSignIn(flag: String, id: String, vipFlags: List<String>, onPrompt: (DriveSignInPrompt) -> Unit): String? =
        play(flag, id, vipFlags)

    suspend fun pollHostedSignIn(handle: String): DriveSignInStatus? = null

    /** A live playlist (TXT / M3U / JSON text) for a `lives` entry of `type: 3` (`liveContent`); null when the spider has none. */
    suspend fun live(url: String): String? = null

    /** Spider-side proxy (`/proxy?do=...`), used by some sites to serve media or rewrite playlists. */
    suspend fun proxy(params: Map<String, String>): ProxyResponse? = null

    val supportsDriveSignIn: Boolean get() = false
    suspend fun driveSignIn(params: Map<String, String>): ProxyResponse? = null

    fun destroy()
}

/** What a spider proxy answers. [body] is streamed to the client (it may be a video), then closed. */
class ProxyResponse(
    val status: Int,
    val contentType: String,
    val body: java.io.InputStream?,
    val headers: Map<String, String> = emptyMap(),
)

/** Something that can run spiders of some kind (js / jar / py). */
interface SpiderRuntime {
    /** "js", "jar", "py": reported in engine capabilities. */
    val kind: String

    /**
     * Whether spiders can run now. A runtime that needs something outside the engine (Python) can be present but
     * not ready; its sites then answer with [SpiderLoadException] and capabilities say false until it is.
     */
    val available: Boolean get() = true

    fun supports(site: SiteBean): Boolean

    /** Creates and initialises the spider for [site] with its [ext] as the config wrote it. May be slow (downloads, compilation). */
    suspend fun create(site: SiteBean, ext: String): Spider

    /** Called when a config becomes active (and before its spiders are created), e.g. to pick up the global `spider` jar. */
    fun onConfig(config: TvboxConfig) {}

    fun onConfig(config: TvboxConfig, origin: String) = onConfig(config)

    fun onConfigRemoved() {}

    /**
     * Handles a `/proxy?do=...` request (TVBox `ApiConfig.proxyLocal`). [params] holds the query parameters plus the
     * request headers (lower-case names). Null when this runtime has nothing for it.
     */
    suspend fun proxy(params: Map<String, String>): ProxyResponse? = null
}

/** [code] is the API error code clients get (e.g. `PY_MODULE_MISSING` when a Python spider needs a package). */
class SpiderLoadException(message: String, cause: Throwable? = null, val code: String = "SPIDER_LOAD_FAILED", val diagnostic: SpiderDiagnostic? = null) : Exception(message, cause)
