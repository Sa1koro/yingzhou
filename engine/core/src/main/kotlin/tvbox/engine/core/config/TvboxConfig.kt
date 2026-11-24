package tvbox.engine.core.config

/** A site entry from `sites`. Field defaults follow TVBox `ApiConfig.parseJson`. */
data class SiteBean(
    val key: String,
    val name: String,
    /** 0 = XML CMS, 1 = JSON CMS, 3 = spider (jar/js/py by api suffix), 4 = remote spider over HTTP. */
    val type: Int,
    val api: String,
    val searchable: Int = 1,
    val quickSearch: Int = 1,
    val changeable: Int = 1,
    val filterable: Int = 1,
    val playUrl: String = "",
    /** Object/array values are kept as their JSON text, strings as-is. */
    val ext: String = "",
    val jar: String = "",
    /** -1 = follow the app setting. */
    val playerType: Int = -1,
    val categories: List<String> = emptyList(),
    /** Seconds; 0 = default. */
    val timeout: Int = 0,
    val click: String = "",
    val style: String = "",
    val catPawOpen: String? = null,
) {
    val isSearchable: Boolean get() = searchable != 0

    /** Which runtime a type-3 site needs, decided by the api suffix exactly as TVBox `getCSP` does. */
    val runtime: SiteRuntime
        get() = when (type) {
            0, 1 -> SiteRuntime.CMS
            4 -> SiteRuntime.REMOTE
            3 -> when {
                catPawOpen != null || api.endsWith(".js") || api.contains(".js?") -> SiteRuntime.JS
                api.contains(".py") -> SiteRuntime.PY
                else -> SiteRuntime.JAR
            }
            else -> SiteRuntime.UNKNOWN
        }
}

enum class SiteRuntime { CMS, JS, JAR, PY, REMOTE, UNKNOWN }

/** An entry from `parses`. type: 0 = sniff in a web view, 1 = JSON, 2 = JSON extended, 3 = JSON mix, 4 = super parse. */
data class ParseBean(
    val name: String,
    val url: String,
    val type: Int = 0,
    /** `ext` object as JSON text, or empty. */
    val ext: String = "",
)

/** A fully parsed TVBox configuration. Sections not yet consumed by the engine are kept as raw JSON text. */
data class TvboxConfig(
    val spider: String = "",
    val wallpaper: String = "",
    val danmaku: String = "",
    val sites: List<SiteBean> = emptyList(),
    val parses: List<ParseBean> = emptyList(),
    /** `flags`: play flags that must go through the VIP parse list. */
    val flags: List<String> = emptyList(),
    /** `hosts`: "domain=target" overrides, split on the first '='. */
    val hosts: Map<String, String> = emptyMap(),
    val ads: List<String> = emptyList(),
    val livesJson: String = "",
    val rulesJson: String = "",
    val dohJson: String = "",
    /** `headers`: `[{"host", "header": {...}}]`, request headers per host (see `net/ConfigNetwork`). */
    val headersJson: String = "",
)

/** One entry of a multi-repository subscription (`{"urls": [...]}` without `sites`). */
data class RepoEntry(val name: String, val url: String)

/** What a subscription URL turned out to contain. */
sealed interface ConfigDocument {
    data class Single(val config: TvboxConfig) : ConfigDocument
    data class Repository(val entries: List<RepoEntry>) : ConfigDocument
}
