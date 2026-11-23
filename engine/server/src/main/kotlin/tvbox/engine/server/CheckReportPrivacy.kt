package tvbox.engine.server

import tvbox.engine.core.cache.DataCache
import tvbox.engine.core.model.SpiderDiagnostic

internal object CheckReportPrivacy {
    fun summarize(configs: List<Checker.ConfigCheck>): List<Checker.ConfigCheck> = configs.map { config ->
        config.copy(
            subscription = label("subscription", config.subscription),
            name = label("config", config.url),
            url = label("config", config.url),
            error = if (config.error.isEmpty()) "" else "配置加载失败",
            sameAs = if (config.sameAs.isEmpty()) "" else "此前配置",
            sites = config.sites.map { site ->
                site.copy(
                    key = label("site", site.key),
                    name = label("site", site.key),
                    message = when {
                        site.message == Checker.RECOMMENDATION -> site.message
                        site.step == "media" && site.message in CheckMedia.reportableReasons -> site.message
                        site.step == "media" -> "媒体请求失败"
                        site.verdict == "skipped" -> "本次未执行"
                        else -> safeErrorCode.find(site.message)?.value.orEmpty()
                    },
                    diagnostic = site.diagnostic?.let { diagnostic(it, label("site", site.key)) },
                    warning = site.warning?.let { diagnostic(it, label("site", site.key)) },
                )
            },
            lives = config.lives.map { live ->
                live.copy(name = label("live", live.name), message = "", epg = "", diagnostic = live.diagnostic?.let { diagnostic(it, "") })
            },
            diagnostic = config.diagnostic?.let { diagnostic(it, "") },
        )
    }

    fun label(kind: String, value: String): String = "$kind-${DataCache.hash(value).take(12)}"

    private fun diagnostic(source: SpiderDiagnostic, site: String) = source.copy(
        site = site,
        cause = reason(source.cause),
        initCause = source.initCause?.let(::reason),
        archive = source.archive?.takeIf { it.matches(archiveId) },
        nativeOperation = source.nativeOperation?.takeIf { it.length <= 240 && it.matches(nativeSymbol) },
        initNativeOperation = source.initNativeOperation?.takeIf { it.length <= 240 && it.matches(nativeSymbol) },
    )

    private fun reason(value: String): String {
        val hint = value.substringAfter(": ", "")
        if (hint in nativeReasons) return "UnsatisfiedLinkError: $hint"
        return value.substringBefore(": ").takeIf { it.matches(exceptionName) } ?: "诊断详情已省略"
    }

    private val safeErrorCode = Regex("^[A-Z][A-Z0-9_]{2,40}(?=:)")
    private val exceptionName = Regex("[A-Za-z_$][A-Za-z0-9_$]{0,79}")
    private val archiveId = Regex("[a-f0-9]{12}")
    private val nativeSymbol = Regex("(?:JNI method|JNI callback) (?:[A-Za-z0-9_$]+/)*[A-Za-z0-9_$]+->(?:[A-Za-z_$][A-Za-z0-9_$]*|<init>)(?:\\([A-Za-z0-9_$/;\\[]*\\)|:)[A-Za-z0-9_$/;\\[]+")
    private val nativeReasons = setOf(
        "Android JNI: unsupported signature", "Android JNI: guest timed out", "Android JNI: guest unavailable",
        "Android JNI: native call failed", "Android JNI: guest initialization failed", "Android JNI: unsupported Java callback",
        "Android JNI: Java callback failed", "Android JNI: native method not bound",
    )
}
