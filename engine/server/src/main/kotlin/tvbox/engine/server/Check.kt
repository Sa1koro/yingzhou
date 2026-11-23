package tvbox.engine.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import tvbox.engine.core.Engine
import tvbox.engine.core.EngineException
import tvbox.engine.core.model.PlayRequest
import tvbox.engine.core.model.SiteInfo
import tvbox.engine.core.model.SpiderDiagnostic
import tvbox.engine.core.spider.summary
import java.text.SimpleDateFormat
import java.util.Date

/**
 * `tvbox-engine --check <subscription>...`: loads each subscription the way a client would and walks every site
 * (home -> first category -> first video's detail, optionally its play address) and every live source
 * (channel list, programme guide), then says for each failure whose it is:
 *
 * - `compat`: the engine's to fix (a spider that does not load or link here, something not implemented);
 * - `unsupported`: a kind of site the engine does not run yet;
 * - `network`: the site, its jar or a module of its script did not answer, or answered with a web page;
 * - `site`: the site answered with an error of its own, its script threw, or the config names a spider its jar
 *   does not have.
 *
 * The first video is the first one the site opens itself: a recommendation site (Douban), whose videos all have a
 * `msearch:` or empty id and are found by their title on the other sites, passes without a detail.
 *
 * A multi-repository subscription is checked entry by entry, lists inside lists included. Python sites and lives are
 * skipped (with the reason) when this machine has no Python or lacks a package their spider imports.
 */
class Checker(private val engine: Engine, private val options: Options, private val log: (String) -> Unit) {

    data class Options(
        /** Which kinds to check: cms, remote, js, jar, py, live. */
        val only: Set<String> = setOf("cms", "remote", "js", "jar", "py", "live"),
        /** Also resolve the play address of the first episode. */
        val play: Boolean = false,
        /** Sites checked per config; 0 = all. */
        val maxSites: Int = 0,
        /** Configs checked per subscription. */
        val maxConfigs: Int = 40,
        val parallel: Int = 4,
        val stepTimeoutMs: Long = 45_000,
        /** Sites left out, by key, with the reason (the demo's site whose lines are all dead, in the self-test). */
        val skip: Map<String, String> = emptyMap(),
        val siteKeys: Set<String> = emptySet(),
        val mediaClient: OkHttpClient? = null,
        val checkMirrors: Boolean = false,
    )

    data class SiteCheck(
        val key: String,
        val name: String,
        val runtime: String,
        /** pass | empty | fail | skipped */
        val verdict: String,
        /** For a failure: compat | unsupported | network | site. */
        val cause: String = "",
        /** The step that failed or came back empty: home | category | detail | play. */
        val step: String = "",
        val message: String = "",
        val classes: Int = 0,
        val items: Int = 0,
        val lines: Int = 0,
        val episodes: Int = 0,
        val play: String = "",
        val ms: Long = 0,
        val diagnostic: SpiderDiagnostic? = null,
        val warning: SpiderDiagnostic? = null,
        val reached: String = "",
        val media: String = "",
        val mediaBytes: Int = 0,
        val midpoint: String = "",
    )

    data class LiveCheck(
        val name: String,
        val verdict: String,
        val cause: String = "",
        val message: String = "",
        val groups: Int = 0,
        val channels: Int = 0,
        val epg: String = "",
        val diagnostic: SpiderDiagnostic? = null,
    )

    data class ConfigCheck(
        val subscription: String,
        val name: String,
        val url: String,
        val error: String = "",
        /** The config checked earlier that has the same sites (a mirror); this one was not checked again. */
        val sameAs: String = "",
        val sites: List<SiteCheck> = emptyList(),
        val lives: List<LiveCheck> = emptyList(),
        val diagnostic: SpiderDiagnostic? = null,
    )

    /** Configs already checked, by their sites: mirrors of one config are checked once. */
    private val checkedSites = HashMap<String, String>()
    private val media = options.mediaClient?.let(::CheckMedia)

    /** [progress] gets the results so far after every config, so a report exists even when the run is cut short. */
    suspend fun run(subscriptions: List<String>, progress: (List<ConfigCheck>) -> Unit = {}): List<ConfigCheck> {
        val results = ArrayList<ConfigCheck>()
        val out = object {
            operator fun plusAssign(check: ConfigCheck) {
                results += check
                progress(results)
            }
        }
        val seen = HashSet<String>()
        for (input in subscriptions) {
            val id = engine.addSubscription(input, null).id
            val first = try {
                engine.activate(id)
            } catch (e: EngineException) {
                log("[配置失败] $input: ${e.message}")
                out += ConfigCheck(input, input, input, error = e.message ?: e.code, diagnostic = e.diagnostic)
                continue
            }
            if (first.repos.isEmpty()) {
                if (seen.add(first.url)) out += checkActive(input, input.substringAfterLast('/').substringAfterLast('\\'), first.url)
                continue
            }
            // Entry by entry. Opening an entry that is a list adds its entries to the queue.
            val names = LinkedHashMap<String, String>()
            first.repos.forEach { names[it.url] = it.name }
            val done = HashSet<String>()
            var checked = 0
            while (checked < options.maxConfigs) {
                val url = names.keys.firstOrNull { it !in done } ?: break
                done += url
                val summary = try {
                    engine.activate(id, url)
                } catch (e: EngineException) {
                    log("[配置失败] ${names[url]}: ${e.message}")
                    out += ConfigCheck(input, names[url] ?: url, url, error = e.message ?: e.code, diagnostic = e.diagnostic)
                    // A list that opened but had no working entry still told us its entries.
                    runCatching { engine.summary().repos }.getOrDefault(emptyList()).forEach { names.putIfAbsent(it.url, it.name) }
                    continue
                }
                summary.repos.forEach { names.putIfAbsent(it.url, it.name) }
                done += summary.url
                if (!seen.add(summary.url)) continue
                val trail = generateSequence(summary.repos.firstOrNull { it.url == summary.url }) { r -> summary.repos.firstOrNull { it.url == r.parent } }
                    .toList().reversed().joinToString(" › ") { it.name }
                out += checkActive(input, trail.ifEmpty { names[summary.url] ?: summary.url }, summary.url)
                checked++
            }
        }
        return results
    }

    private suspend fun checkActive(subscription: String, name: String, url: String): ConfigCheck = coroutineScope {
        val all = engine.sites()
        val signature = engine.summary().spider + "|" + all.joinToString("|") { it.key + ":" + it.name }
        if (options.siteKeys.isEmpty() && !options.checkMirrors) {
            checkedSites[signature]?.let { same ->
                log("== $name: 与「$same」的站点相同，不重复检查  $url")
                return@coroutineScope ConfigCheck(subscription, name, url, sameAs = same)
            }
            checkedSites[signature] = name
        }
        log("== $name: ${all.size} 个站点  $url")
        val selected = if (options.siteKeys.isEmpty()) all else all.filter { it.key in options.siteKeys }
        val sites = if (options.maxSites > 0) selected.take(options.maxSites) else selected
        val gate = Semaphore(options.parallel)
        val checks = sites.map { site ->
            async {
                gate.withPermit {
                    val skip = options.skip[site.key]
                    val check = when {
                        skip != null -> SiteCheck(site.key, site.name, site.runtime, "skipped", message = skip)
                            .also { log("  [跳过] ${site.name} (${site.runtime}) $skip") }
                        kind(site) in options.only -> checkSite(site)
                        else -> SiteCheck(site.key, site.name, site.runtime, "skipped")
                    }
                    if (check.verdict != "skipped") log(line(check))
                    check
                }
            }
        }.awaitAll()
        val lives = if (options.siteKeys.isEmpty() && "live" in options.only) checkLives() else emptyList()
        ConfigCheck(subscription, name, url, sites = checks, lives = lives)
    }

    private fun kind(site: SiteInfo) = if (site.runtime == "unknown") "cms" else site.runtime

    private suspend fun checkSite(site: SiteInfo): SiteCheck {
        val started = System.currentTimeMillis()
        var step = "home"
        var check = SiteCheck(site.key, site.name, site.runtime, "pass")
        fun done() = check.copy(ms = System.currentTimeMillis() - started)
        try {
            val home = withTimeout(options.stepTimeoutMs) { engine.home(site.key) }
            var items = home.list
            check = check.copy(classes = home.classes.size, items = items.size, warning = home.warning)
            if (home.classes.isEmpty() && items.isEmpty()) {
                val warning = home.warning ?: return done().copy(verdict = "empty", step = "home", message = "首页没有分类也没有影片")
                return done().copy(verdict = "fail", cause = cause("SITE_ERROR", warning.summary(), warning), step = "home", message = warning.summary(), diagnostic = warning, warning = null)
            }
            check = check.copy(reached = "home")
            if (home.classes.isNotEmpty()) {
                step = "category"
                val page = withTimeout(options.stepTimeoutMs) { engine.category(site.key, home.classes.first().id, 1, emptyMap()) }
                if (page.list.isNotEmpty()) items = page.list
                check = check.copy(items = maxOf(check.items, page.list.size))
                if (page.list.isNotEmpty()) check = check.copy(reached = "category")
            }
            if (items.isEmpty()) return done().copy(verdict = "empty", step = step, message = "第一个分类下没有影片")
            // A recommendation site (Douban) lists videos that are found by their title on the other sites.
            var item = firstToOpen(items.map { it.id })?.let { i -> items[i] } ?: return done().copy(message = RECOMMENDATION)
            // A folder card (`vod_tag: folder`) opens the category with its id, as in TVBox: go in, a few levels at most.
            var levels = 0
            while (item.isFolder && levels < MAX_FOLDER_LEVELS) {
                step = "folder"
                val inner = withTimeout(options.stepTimeoutMs) { engine.category(site.key, item.id, 1, emptyMap()) }.list
                if (inner.isEmpty()) return done().copy(verdict = "empty", step = step, message = "文件夹《${item.name}》里没有影片")
                check = check.copy(reached = "folder")
                item = firstToOpen(inner.map { it.id })?.let { i -> inner[i] } ?: return done().copy(message = RECOMMENDATION)
                levels++
            }
            step = "detail"
            val detail = withTimeout(options.stepTimeoutMs) { engine.detail(site.key, item.id) }
            val episodes = detail.lines.sumOf { it.episodes.size }
            check = check.copy(lines = detail.lines.size, episodes = episodes)
            if (episodes == 0) return done().copy(verdict = "empty", step = "detail", message = "《${item.name}》没有可播放的剧集")
            check = check.copy(reached = "detail")
            if (options.play) {
                step = "play"
                val line = detail.lines.first { it.episodes.isNotEmpty() }
                val info = withTimeout(options.stepTimeoutMs * 2) { engine.resolvePlay(PlayRequest(site.key, line.name, line.episodes.first().id)) }
                check = check.copy(play = info.kind, reached = "play")
                if (media != null) {
                    step = "media"
                    val evidence = withTimeout(options.stepTimeoutMs * 2) { media.read(info) }
                    check = check.copy(reached = "media", media = evidence.transport, mediaBytes = evidence.bytes, midpoint = evidence.midpoint)
                }
            }
            return done()
        } catch (error: CheckMedia.Failure) {
            return done().copy(verdict = "fail", cause = if (error.reason.startsWith("需要客户端")) "unsupported" else "network", step = step, message = error.reason)
        } catch (e: Throwable) {
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val (code, message) = when (e) {
                is EngineException -> e.code to (e.message ?: e.code)
                is TimeoutCancellationException -> "SITE_TIMEOUT" to "${options.stepTimeoutMs / 1000} 秒内没有结果"
                else -> "SITE_ERROR" to (e.javaClass.name + ": " + e.message)
            }
            // No Python on this machine, or not the package the spider imports: the machine's gap, not the site's
            // or the engine's, and nothing was checked.
            if (code in MACHINE_GAPS) {
                log("  [跳过] ${site.name} (${site.runtime}) $code: ${message.take(200)}")
                return done().copy(verdict = "skipped", step = step, message = "$code: ${message.take(300)}")
            }
            val diagnostic = (e as? EngineException.SiteFailed)?.diagnostic
            return done().copy(verdict = "fail", cause = cause(code, message, diagnostic), step = step, message = "$code: ${message.replace(Regex("\\s+"), " ").take(300)}", diagnostic = diagnostic)
        }
    }

    private suspend fun checkLives(): List<LiveCheck> {
        val sources = try {
            engine.liveSources()
        } catch (e: EngineException) {
            return emptyList()
        }
        return sources.map { source ->
            // A list served by a spider runs that spider: only when its kind is being checked.
            if (source.spider.isNotEmpty() && source.spider !in options.only) return@map LiveCheck(source.name, "skipped")
            if (source.spider == "py" && !engine.info.capabilities.py) {
                return@map LiveCheck(source.name, "skipped", message = "本机没有可用的 Python").also { log("  [直播 跳过] ${it.name}: ${it.message}") }
            }
            if (!source.supported) {
                val why = if (source.spider.isEmpty()) "暂不支持这种直播源" else "${source.spider} 蜘蛛直播暂不支持"
                return@map LiveCheck(source.name, "fail", "unsupported", why).also { log("  [直播 不支持] ${it.name}: ${it.message}") }
            }
            val check = try {
                val list = withTimeout(options.stepTimeoutMs) { engine.liveList(source.index) }
                val channels = list.groups.sumOf { it.channels.size }
                if (channels == 0) {
                    LiveCheck(source.name, "empty", message = "列表里没有频道")
                } else {
                    val first = list.groups.first { it.channels.isNotEmpty() }.channels.first()
                    val epg = if (!source.hasEpg) "无节目单" else try {
                        val day = withTimeout(options.stepTimeoutMs) { engine.epg(source.index, first.name, "") }
                        "${first.name}: ${day.programs.size} 条节目"
                    } catch (e: Throwable) {
                        if (e is CancellationException && e !is TimeoutCancellationException) throw e
                        "${first.name}: 节目单失败 ${e.message.orEmpty().take(120)}"
                    }
                    LiveCheck(source.name, "pass", groups = list.groups.size, channels = channels, epg = epg)
                }
            } catch (e: Throwable) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val code = (e as? EngineException)?.code ?: if (e is TimeoutCancellationException) "SITE_TIMEOUT" else "SITE_ERROR"
                val message = e.message ?: e.javaClass.simpleName
                val diagnostic = (e as? EngineException.SiteFailed)?.diagnostic
                if (code in MACHINE_GAPS) LiveCheck(source.name, "skipped", message = "$code: ${message.take(300)}")
                else LiveCheck(source.name, "fail", cause(code, message, diagnostic), "$code: ${message.take(300)}", diagnostic = diagnostic)
            }
            log("  [直播 ${label(check.verdict, check.cause)}] ${check.name}: " + if (check.verdict == "pass") "${check.groups} 组 ${check.channels} 个频道，${check.epg}" else check.message)
            check
        }
    }

    private fun line(c: SiteCheck): String {
        val what = when (c.verdict) {
            "pass" -> "${c.classes} 个分类，${c.items} 部，" +
                (if (c.message.isNotEmpty()) c.message else "详情 ${c.lines} 条线路 ${c.episodes} 集") + (if (c.play.isEmpty()) "" else "，播放 ${c.play}")
            else -> "${c.step}: ${c.message}"
        }
        val warning = c.warning?.let { "，推荐异常 ${it.summary()}" }.orEmpty()
        return "  [${label(c.verdict, c.cause)}] ${c.name} (${c.runtime}) $what$warning  ${c.ms}ms"
    }

    companion object {
        /** Errors that say this machine lacks something a Python spider needs; such sites count as skipped. */
        private val MACHINE_GAPS = setOf("PY_UNAVAILABLE", "PY_MODULE_MISSING")

        /** How many folders deep the check goes to find a video to open. */
        private const val MAX_FOLDER_LEVELS = 4

        /** The message of a site that passes as a recommendation site: none of its videos opens there. */
        const val RECOMMENDATION = "推荐站：影片按片名到其他站点找"

        /**
         * The first video the site itself opens, by position: one whose id is not blank and not `msearch:…`
         * (TVBox opens those by searching for the title on the other sites). Null when there is none.
         */
        fun firstToOpen(ids: List<String>): Int? =
            ids.indexOfFirst { it.isNotBlank() && !it.startsWith("msearch:") }.takeIf { it >= 0 }

        private val COMPAT = Regex(
            "ClassNotFound|NoClassDefFound|NoSuchMethod|NoSuchField|VerifyError|LinkageError|UnsatisfiedLink|AbstractMethodError|" +
                "IncompatibleClassChange|IllegalAccessError|ExceptionInInitializer|ClassFormatError|ClassCastException|zipfs|" +
                "Stub!|UnsupportedOperation|not implemented|cannot load the spider jar|cannot read the dex|neither a zip nor a dex",
            RegexOption.IGNORE_CASE,
        )
        private val NETWORK = Regex(
            "HTTP \\d{3}|timeout|timed out|Unable to resolve|UnknownHost|No such host|getaddrinfo|Name or service not known|nodename nor servname|" +
                "urlopen error|cannot fetch|connect|refused|reset|SSL|certificate|handshake|unexpected end of stream|cannot download",
            RegexOption.IGNORE_CASE,
        )

        /** The config names a spider class that the jar it names does not contain. */
        private val NOT_IN_JAR = Regex("is not in the jar")

        /** Whose failure it is, from the engine's error code and message. */
        fun cause(code: String, message: String, diagnostic: SpiderDiagnostic?): String = when (diagnostic?.kind) {
            "network" -> "network"
            "native", "command", "missing_class", "missing_member" -> "compat"
            "missing_spider", "invalid_result" -> "site"
            else -> cause(code, diagnostic?.cause ?: message)
        }

        fun cause(code: String, message: String): String = when {
            code == "UNSUPPORTED_SITE" -> "unsupported"
            NOT_IN_JAR.containsMatchIn(message) -> "site"
            COMPAT.containsMatchIn(message) -> "compat"
            code == "SITE_TIMEOUT" || code == "SITE_UNREACHABLE" -> "network"
            NETWORK.containsMatchIn(message) -> "network"
            code == "SPIDER_LOAD_FAILED" -> "compat"
            else -> "site"
        }

        fun label(verdict: String, cause: String): String = when (verdict) {
            "pass" -> "通过"
            "empty" -> "空"
            "skipped" -> "跳过"
            else -> when (cause) {
                "compat" -> "引擎兼容"
                "unsupported" -> "不支持"
                "network" -> "网络"
                else -> "站点报错"
            }
        }

        /** True when nothing is the engine's to fix; with [strict], when everything passed. */
        fun ok(configs: List<ConfigCheck>, strict: Boolean): Boolean = configs.all { c ->
            if (strict) {
                c.error.isEmpty() && c.sites.all { it.verdict == "pass" || it.verdict == "skipped" } && c.lives.all { it.verdict == "pass" || it.verdict == "skipped" }
            } else {
                c.sites.none { it.cause == "compat" } && c.lives.none { it.cause == "compat" }
            }
        }

        fun unverifiedSites(configs: List<ConfigCheck>, required: Set<String>, requirePlay: Boolean): List<String> {
            val results = configs.flatMap { it.sites }.groupBy { it.key }
            return required.filter { key ->
                val matches = results[key]
                matches.isNullOrEmpty() || matches.any { it.verdict != "pass" || (requirePlay && it.play.isEmpty()) }
            }
        }

        fun markdown(configs: List<ConfigCheck>, header: List<String>): String = buildString {
            fun cell(s: String) = s.replace("|", "\\|").replace("\n", " ")
            appendLine("# 配置体检报告")
            appendLine()
            appendLine("- 时间：${SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())}")
            header.forEach { appendLine("- $it") }
            appendLine()
            appendLine("每个站点依次走：首页 → 第一个分类 → 第一部影片的详情（加 `--play` 时再解析第一集的播放地址）。")
            appendLine("「引擎兼容」是引擎要修的；「不支持」是还没做的站点类型；「网络」「站点报错」多半是站点自己失效")
            appendLine("（「网络」包括蜘蛛 jar 或脚本模块的地址打不开、只返回一个网页；「站点报错」包括蜘蛛脚本运行时报错、配置里写的蜘蛛类不在它的 jar 里）。")
            appendLine()
            appendLine("## 汇总")
            appendLine()
            appendLine("| 配置 | 站点 | 通过 | 空 | 引擎兼容 | 不支持 | 网络 | 站点报错 | 跳过 | 直播源（通过/总数） |")
            appendLine("|---|---|---|---|---|---|---|---|---|---|")
            for (c in configs) {
                if (c.error.isNotEmpty()) {
                    appendLine("| ${cell(c.name)} | 配置加载失败 | | | | | | | | |")
                    continue
                }
                if (c.sameAs.isNotEmpty()) {
                    appendLine("| ${cell(c.name)} | 同「${cell(c.sameAs)}」 | | | | | | | | |")
                    continue
                }
                fun n(p: (SiteCheck) -> Boolean) = c.sites.count(p)
                appendLine(
                    "| ${cell(c.name)} | ${c.sites.size} | ${n { it.verdict == "pass" }} | ${n { it.verdict == "empty" }} | ${n { it.cause == "compat" }} | " +
                        "${n { it.cause == "unsupported" }} | ${n { it.cause == "network" }} | ${n { it.cause == "site" }} | ${n { it.verdict == "skipped" }} | " +
                        "${c.lives.count { it.verdict == "pass" }}/${c.lives.count { it.verdict != "skipped" }} |",
                )
            }
            val stages = configs.flatMap { config ->
                listOfNotNull(config.diagnostic) + config.sites.mapNotNull { it.diagnostic } + config.lives.mapNotNull { it.diagnostic }
            }.groupingBy { it.stage }.eachCount()
            val warnings = configs.flatMap { config -> config.sites.mapNotNull { it.warning } }.groupingBy { it.stage }.eachCount()
            if (stages.isNotEmpty() || warnings.isNotEmpty()) {
                appendLine()
                appendLine("| 故障阶段 | 故障 | 警告 |")
                appendLine("|---|---|---|")
                for (stage in (stages.keys + warnings.keys).sorted()) appendLine("| ${cell(stage)} | ${stages[stage] ?: 0} | ${warnings[stage] ?: 0} |")
            }
            for (c in configs) {
                appendLine()
                appendLine("## ${c.name}")
                appendLine()
                appendLine("`${c.url}`")
                appendLine()
                if (c.error.isNotEmpty()) {
                    appendLine("配置加载失败：${c.error}")
                    continue
                }
                if (c.sameAs.isNotEmpty()) {
                    appendLine("站点与「${c.sameAs}」相同（镜像），没有重复检查。")
                    continue
                }
                val groups = listOf(
                    "引擎兼容问题（要修）" to c.sites.filter { it.cause == "compat" },
                    "不支持的类型" to c.sites.filter { it.cause == "unsupported" },
                    "站点报错" to c.sites.filter { it.cause == "site" },
                    "网络问题" to c.sites.filter { it.cause == "network" },
                    "返回为空" to c.sites.filter { it.verdict == "empty" },
                )
                for ((title, list) in groups) {
                    if (list.isEmpty()) continue
                    appendLine("### $title（${list.size}）")
                    appendLine()
                    appendLine("| 站点 | key | 类型 | 最后通过 | 失败步骤 | 信息 |")
                    appendLine("|---|---|---|---|---|---|")
                    list.forEach { appendLine("| ${cell(it.name)} | ${cell(it.key)} | ${it.runtime} | ${it.reached} | ${it.step} | ${cell(it.message)} |") }
                    appendLine()
                }
                val passed = c.sites.filter { it.verdict == "pass" }
                if (passed.isNotEmpty()) {
                    appendLine("### 通过（${passed.size}）")
                    appendLine()
                    appendLine("| 站点 | 类型 | 分类 | 影片 | 线路 | 剧集 | 最后通过 | 播放解析 | 媒体字节 | 中段 | 耗时 |")
                    appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
                    passed.forEach { appendLine("| ${cell(it.name)} | ${it.runtime} | ${it.classes} | ${it.items} | ${it.lines} | ${it.episodes} | ${it.reached} | ${cell(it.play.ifEmpty { it.message })} | ${it.mediaBytes} | ${it.midpoint} | ${it.ms}ms |") }
                    appendLine()
                }
                c.sites.forEach { site ->
                    site.warning?.let { appendLine("- ${cell(site.name)} 的首页推荐异常：${cell(it.summary())}") }
                }
                if (c.lives.isNotEmpty()) {
                    appendLine("### 直播源（${c.lives.size}）")
                    appendLine()
                    appendLine("| 直播源 | 结果 | 分组 | 频道 | 节目单 / 信息 |")
                    appendLine("|---|---|---|---|---|")
                    c.lives.forEach { appendLine("| ${cell(it.name)} | ${label(it.verdict, it.cause)} | ${it.groups} | ${it.channels} | ${cell(it.epg.ifEmpty { it.message })} |") }
                    appendLine()
                }
            }
        }

        fun json(configs: List<ConfigCheck>): JsonArray = JsonArray(
            configs.map { c ->
                buildJsonObject {
                    put("subscription", c.subscription)
                    put("name", c.name)
                    put("url", c.url)
                    put("error", c.error)
                    c.diagnostic?.let { put("diagnostic", Json.encodeToJsonElement(it)) }
                    put("sameAs", c.sameAs)
                    put("sites", JsonArray(c.sites.map { site(it) }))
                    put(
                        "lives",
                        JsonArray(
                            c.lives.map {
                                buildJsonObject {
                                    put("name", it.name); put("verdict", it.verdict); put("cause", it.cause); put("message", it.message)
                                    put("groups", it.groups); put("channels", it.channels); put("epg", it.epg)
                                    it.diagnostic?.let { diagnostic -> put("diagnostic", Json.encodeToJsonElement(diagnostic)) }
                                }
                            },
                        ),
                    )
                }
            },
        )

        private fun site(s: SiteCheck): JsonObject = buildJsonObject {
            put("key", s.key); put("name", s.name); put("runtime", s.runtime); put("verdict", s.verdict); put("cause", s.cause)
            put("step", s.step); put("message", s.message); put("classes", s.classes); put("items", s.items)
            put("lines", s.lines); put("episodes", s.episodes); put("play", s.play); put("ms", s.ms)
            put("reached", s.reached); put("media", s.media); put("mediaBytes", s.mediaBytes); put("midpoint", s.midpoint)
            s.diagnostic?.let { put("diagnostic", Json.encodeToJsonElement(it)) }
            s.warning?.let { put("warning", Json.encodeToJsonElement(it)) }
        }
    }
}
