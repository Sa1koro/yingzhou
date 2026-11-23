package tvbox.engine.core.model

import kotlinx.serialization.Serializable

// Wire types of protocol v1 (docs/protocol.md). Keep field names stable; add, don't rename.

const val PROTOCOL_VERSION = 1

@Serializable
data class Capabilities(
    val jar: Boolean,
    val js: Boolean,
    val py: Boolean,
    val sniff: Boolean,
)

@Serializable
data class EngineInfo(
    val name: String,
    val version: String,
    val protocol: Int = PROTOCOL_VERSION,
    val platform: String,
    val capabilities: Capabilities,
)

@Serializable
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    /** When it last loaded as the active subscription (epoch ms); 0 when it never has (or before engines kept it). */
    val usedAt: Long = 0,
)

/** `PUT /subscriptions/{id}`: a new display name; empty goes back to the url. */
@Serializable
data class RenameSubscriptionRequest(
    val name: String = "",
)

@Serializable
data class AddSubscriptionRequest(
    val url: String,
    val name: String? = null,
)

@Serializable
data class ActivateRequest(
    /** For multi-repository subscriptions: the url of the repo entry to use. Defaults to the first one. */
    val repo: String? = null,
)

@Serializable
data class RepoInfo(
    val name: String,
    val url: String,
    /** Url of the entry this one was listed by; empty at the top level. */
    val parent: String = "",
    /** True once the entry turned out to be a list of configs itself (its entries follow it). */
    val group: Boolean = false,
    /** 0 at the top level; for indenting. */
    val depth: Int = 0,
)

/** Engine-wide settings a client can change (`/api/v1/settings`). Kept with the engine's data. */
@Serializable
data class EngineSettings(
    /**
     * False (default): files on GitHub are fetched from GitHub, and a proxy a subscription put in front of such
     * an address is taken off. True: they are fetched through [githubProxyPrefix], falling back to GitHub.
     */
    val githubProxy: Boolean = false,
    /** E.g. `https://ghfast.top/`; the GitHub address is appended to it. */
    val githubProxyPrefix: String = tvbox.engine.core.net.GithubProxy.DEFAULT_PREFIX,
    /**
     * The DNS-over-HTTPS server names are looked up with: the url of one of the choices `/api/v1/network` lists.
     * Empty (default): the system's DNS. A config can offer servers but never turns one on.
     */
    val doh: String = "",
    /**
     * True (default): video-on-demand HLS is fetched by the engine and kept on its disk, so that seeking back and
     * forth does not go back to the source (see `StreamCache`). False: players fetch segments from the source.
     */
    val streamCache: Boolean = true,
    /** How much disk the stream cache may take, in MB; the files used longest ago go when it is full. */
    val streamCacheMaxMB: Int = 2048,
    /**
     * True: ask the config's `danmaku` API for the danmaku of what is played (it is told the title and episode).
     * False (default): that API is a third party the subscription chose, so the user turns it on.
     */
    val danmakuConfigApi: Boolean = false,
    /**
     * A danmaku service of the user's own (a dandanplay-compatible API base, or an address with `{name}` /
     * `{episode}`); asked after the site and the config's API. Empty (default): none.
     */
    val danmakuApi: String = "",
    val httpProxy: String = "",
    val proxyScopes: List<String> = emptyList(),
)

@Serializable
data class ConfigSummary(
    val subscriptionId: String,
    /** The config actually loaded (for a repository, the chosen entry's url), as the subscription wrote it. */
    val url: String,
    /** Where [url] is really fetched from, when the GitHub proxy setting changes that; otherwise equal to [url]. */
    val fetchedUrl: String = "",
    /**
     * Entries of a multi-repository subscription; empty for a plain config. An entry can be a list itself
     * ("多仓" inside "多仓"): activating it marks it as a group, lists its entries after it and loads the first
     * of them that works.
     */
    val repos: List<RepoInfo> = emptyList(),
    val sites: Int,
    val parses: Int,
    val lives: Int,
    val spider: String,
    val wallpaper: String,
    /** When the config in use was downloaded (epoch milliseconds); 0 when unknown. */
    val fetchedAt: Long = 0,
    /** True when the engine started from the copy it kept of the last download and the network has not confirmed it (yet). */
    val stored: Boolean = false,
    /** True while that copy is being checked against the network; ask again in a moment. */
    val refreshing: Boolean = false,
    /** Why the last download failed while the config in use was kept; empty when it did not fail. */
    val refreshError: String = "",
    val refreshDiagnostic: SpiderDiagnostic? = null,
)

@Serializable
data class SiteInfo(
    val key: String,
    val name: String,
    val type: Int,
    val searchable: Boolean,
    val quickSearch: Boolean,
    val filterable: Boolean,
    /**
     * TVBox `changeable` (1 when the config leaves it out): false keeps the site out of switching sources, the search
     * by title on the other sites that finds a video somewhere else.
     */
    val changeable: Boolean,
    /** cms | js | jar | py | remote | unknown */
    val runtime: String,
)

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val diagnostic: SpiderDiagnostic? = null,
)

@Serializable
data class SpiderDiagnostic(
    val stage: String,
    val site: String,
    val kind: String,
    val cause: String,
    val archive: String? = null,
    val initCause: String? = null,
    val nativeOperation: String? = null,
    val initNativeOperation: String? = null,
)

@Serializable
data class ErrorBody(
    val error: ApiError,
)
