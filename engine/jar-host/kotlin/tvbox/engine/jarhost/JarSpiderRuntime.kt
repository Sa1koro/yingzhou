package tvbox.engine.jarhost

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.config.SiteRuntime
import tvbox.engine.core.config.TvboxConfig
import tvbox.engine.core.live.LiveSourceSpec
import tvbox.engine.core.model.SpiderDiagnostic
import tvbox.engine.core.play.JarParsers
import tvbox.engine.core.spider.JarRepository
import tvbox.engine.core.spider.ProxyResponse
import tvbox.engine.core.spider.Spider
import tvbox.engine.core.spider.SpiderLoadException
import tvbox.engine.core.spider.SpiderRuntime
import tvbox.engine.core.spider.spiderArchiveId
import tvbox.engine.core.spider.spiderDiagnostic
import tvbox.engine.core.spider.summary
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import com.github.catvod.crawler.Spider as CatSpider

/**
 * TVBox jar spiders, shared by the Android app and the desktop engine. Port of TVBox `JarLoader`; subclasses
 * only decide how a downloaded jar becomes a [ClassLoader] (Android: DexClassLoader; desktop: dex2jar + URLClassLoader).
 *
 * - every site uses its own `jar`, or the config's global `spider` jar;
 * - the spider class is `com.github.catvod.spider.<api without "csp_">`;
 * - `com.github.catvod.spider.Init.init(Context)` runs once per jar;
 * - `/proxy`: the site spider's own `proxy()`, then the jars' static `com.github.catvod.spider.Proxy.proxy(Map)`.
 */
abstract class JarSpiderRuntime(
    private val context: Context,
    private val jars: JarRepository,
    private val port: () -> Int,
) : SpiderRuntime, JarParsers {
    override val kind = "jar"

    private class LoadedJar(val loader: ClassLoader, val context: Context, val proxy: Method?, val initFailure: SpiderDiagnostic?)
    private class Selection(val global: String, val referenced: Set<String>)

    private val loaded = ConcurrentHashMap<String, LoadedJar>()
    private val siteJars = ConcurrentHashMap<String, LoadedJar>()
    private val siteSpiders = ConcurrentHashMap<String, CatSpider>()
    private val loadLock = Mutex()
    private val selectionLock = Any()
    @Volatile private var selection = Selection("", emptySet())
    @Volatile private var recent: LoadedJar? = null

    /** Turns a downloaded spider jar into a class loader whose parent provides the host classes. */
    protected abstract fun classLoader(jar: File): ClassLoader

    protected open fun contextFor(loader: ClassLoader, archive: File): Context = context

    protected open fun supportsOwnDriveSignIn(loader: ClassLoader): Boolean = false

    protected open val retireUnusedLoaders = false
    protected open fun releaseLoader(loader: ClassLoader) {}

    override fun supports(site: SiteBean) = site.runtime == SiteRuntime.JAR

    override fun onConfig(config: TvboxConfig) {
        val referenced = if (retireUnusedLoaders) buildSet {
            add(config.spider)
            config.sites.mapTo(this) { it.jar }
            LiveSourceSpec.parseAll(config.livesJson).mapTo(this) { it.jar }
        } else emptySet()
        val retired = synchronized(selectionLock) {
            selection = Selection(config.spider, referenced)
            siteJars.clear()
            siteSpiders.clear()
            if (!retireUnusedLoaders) return
            val removed = loaded.keys.filterNot { it in referenced }.mapNotNull { loaded.remove(it) }
            if (recent in removed) recent = null
            removed
        }
        retired.forEach { releaseLoader(it.loader) }
    }

    override fun onConfigRemoved() {
        if (retireUnusedLoaders) onConfig(TvboxConfig())
    }

    override suspend fun create(site: SiteBean, ext: String): Spider = withContext(Dispatchers.IO) {
        val selected = selection
        val spec = site.jar.ifEmpty { selected.global }
        val archive = spiderArchiveId(spec)
        if (spec.isEmpty()) {
            val diagnostic = spiderDiagnostic(site.key, "jar.fetch", IllegalArgumentException()).copy(cause = "Missing JAR")
            throw SpiderLoadException(diagnostic.summary(), diagnostic = diagnostic)
        }
        val jar = load(selected, spec, site.key, archive)
        fun diagnose(stage: String, error: Throwable) = spiderDiagnostic(site.key, stage, error, archive, jar.initFailure)
        val className = "com.github.catvod.spider." + if (site.api.contains("csp_")) site.api.substringAfter("csp_") else site.api
        val spider = try {
            jar.loader.loadClass(className).getDeclaredConstructor().newInstance() as CatSpider
        } catch (e: ClassNotFoundException) {
            val diagnostic = diagnose("spider.create", e)
            throw failure(if (e.isAbsent(className)) diagnostic.copy(kind = "missing_spider") else diagnostic, e)
        } catch (e: Throwable) {
            throw failure(diagnose("spider.create", e), e)
        }
        try {
            spider.siteKey = site.key
            spider.initApi(com.github.catvod.crawler.SpiderApi())
            spider.init(jar.context, ext)
        } catch (e: Throwable) {
            throw failure(diagnose("spider.init", e), e)
        }
        synchronized(selectionLock) {
            if (selected === selection) {
                siteJars[site.key] = jar
                siteSpiders[site.key] = spider
                recent = jar
            }
        }
        val signInProxy = if (supportsOwnDriveSignIn(jar.loader)) jar.proxy else null
        CatSpiderAdapter(spider, ::diagnose, signInProxy, onUse = { synchronized(selectionLock) { if (selected === selection) recent = jar } })
    }

    private suspend fun load(selected: Selection, spec: String, site: String = "", archive: String? = spiderArchiveId(spec)): LoadedJar {
        loaded[spec]?.let { return it }
        return loadLock.withLock {
            loaded[spec] ?: run {
                val file = try {
                    jars.fetch(spec)
                } catch (e: Exception) {
                    throw failure(spiderDiagnostic(site, "jar.fetch", e, archive), e)
                }
                val loader = try {
                    classLoader(file)
                } catch (e: Throwable) {
                    throw failure(spiderDiagnostic(site, "jar.load", e, archive), e)
                }
                var published = false
                try {
                    val pluginContext = try {
                        contextFor(loader, file)
                    } catch (e: Throwable) {
                        throw failure(spiderDiagnostic(site, "jar.load", e, archive), e)
                    }
                    val initFailure = initJar(loader, pluginContext, site, archive)
                    val result = LoadedJar(loader, pluginContext, proxyMethod(loader), initFailure)
                    synchronized(selectionLock) {
                        if (retireUnusedLoaders && selected !== selection && spec !in selection.referenced) {
                            throw CancellationException("subscription changed while loading the spider jar")
                        }
                        loaded[spec] = result
                        published = true
                    }
                    result
                } finally {
                    if (retireUnusedLoaders && !published) releaseLoader(loader)
                }
            }
        }
    }

    /** `Init.init(Context)`, and tell the jar's own copy of com.github.catvod.Proxy (if any) where the engine is. */
    private fun initJar(loader: ClassLoader, pluginContext: Context, site: String, archive: String?): SpiderDiagnostic? {
        val className = "com.github.catvod.spider.Init"
        val initFailure = try {
            loader.loadClass(className).getMethod("init", Context::class.java).invoke(null, pluginContext)
            null
        } catch (e: Throwable) {
            if (e is ClassNotFoundException && e.isAbsent(className)) null else {
                val diagnostic = spiderDiagnostic(site, "jar.init", e, archive)
                System.err.println(diagnostic.summary())
                diagnostic
            }
        }
        com.github.catvod.Proxy.set(port())
        runCatching { loader.loadClass("com.github.catvod.Proxy").getMethod("set", Int::class.javaPrimitiveType).invoke(null, port()) }
        return initFailure
    }

    private fun proxyMethod(loader: ClassLoader): Method? =
        runCatching { loader.loadClass("com.github.catvod.spider.Proxy").getMethod("proxy", Map::class.java) }.getOrNull()

    // ---- parsers in the jar (TVBox `JarLoader.jsonExt` / `jsonExtMix`) ----

    override suspend fun jsonExt(key: String, jxs: LinkedHashMap<String, String>, url: String): String? =
        parser("com.github.catvod.parser.Json$key") { cls ->
            cls.getMethod("parse", LinkedHashMap::class.java, String::class.java).invoke(null, jxs, url)
        }

    override suspend fun jsonExtMix(flag: String, key: String, name: String, jxs: LinkedHashMap<String, HashMap<String, String>>, url: String): String? =
        parser("com.github.catvod.parser.Mix$key") { cls ->
            cls.getMethod("parse", LinkedHashMap::class.java, String::class.java, String::class.java, String::class.java)
                .invoke(null, jxs, name, flag, url)
        }

    /** Looks the class up in the most recently used jar, then the config's spider jar. */
    private suspend fun parser(className: String, call: (Class<*>) -> Any?): String? = withContext(Dispatchers.IO) {
        val selected = selection
        val candidates = listOfNotNull(recent, selected.global.takeIf { it.isNotEmpty() }?.let { runCatching { load(selected, it) }.getOrNull() })
        val cls = candidates.firstNotNullOfOrNull { jar -> runCatching { jar.loader.loadClass(className) }.getOrNull() } ?: return@withContext null
        try {
            resolvingPlayback { call(cls)?.toString() }
        } catch (e: Throwable) {
            System.err.println("$className failed: ${(e.cause ?: e)}")
            null
        }
    }

    override suspend fun proxy(params: Map<String, String>): ProxyResponse? = withContext(Dispatchers.IO) {
        params["siteKey"]?.let { siteSpiders[it] }?.let { sp ->
            runCatching { sp.proxy(HashMap(params)) }.getOrNull()?.let { toProxyResponse(it) }?.let { return@withContext it }
        }
        // A proxy url can arrive before any site was opened (a live playlist served by the jar): load the config's jar.
        val selected = selection
        if (loaded.isEmpty() && selected.global.isNotEmpty()) runCatching { load(selected, selected.global) }
        val ordered = listOfNotNull(params["siteKey"]?.let { siteJars[it] }, recent) + loaded.values
        for (jar in ordered.distinct()) {
            val method = jar.proxy ?: continue
            @Suppress("UNCHECKED_CAST")
            val result = runCatching { method.invoke(null, HashMap(params)) as Array<Any?>? }.getOrNull() ?: continue
            toProxyResponse(result)?.let { return@withContext it }
        }
        null
    }
}

private fun ClassNotFoundException.isAbsent(className: String) = cause == null &&
    (message == className || message?.startsWith("Didn't find class \"$className\" on path:") == true)

private fun failure(diagnostic: SpiderDiagnostic, error: Throwable, fallbackCode: String = "SPIDER_LOAD_FAILED"): SpiderLoadException {
    val code = (error as? SpiderLoadException)?.code ?: fallbackCode
    System.err.println(diagnostic.summary())
    return SpiderLoadException(diagnostic.summary(), error, code, diagnostic)
}

/** Adapts a TVBox jar spider (blocking calls) to the engine's suspending [Spider]. */
private class CatSpiderAdapter(
    private val spider: CatSpider,
    private val diagnose: (String, Throwable) -> SpiderDiagnostic,
    private val signInProxy: Method?,
    private val onUse: () -> Unit,
) : Spider {
    override fun diagnostic(site: String, stage: String, error: Throwable): SpiderDiagnostic = diagnose(stage, error)

    private suspend fun <T> io(stage: String, block: () -> T): T = withContext(Dispatchers.IO) {
        onUse()
        try {
            block()
        } catch (error: Throwable) {
            val diagnostic = diagnose(stage, error)
            val code = when {
                error is LinkageError -> "SPIDER_INCOMPATIBLE"
                error is tvbox.engine.core.net.SiteHttpException -> if (error.message?.startsWith("timeout") == true) "SITE_TIMEOUT" else "SITE_UNREACHABLE"
                else -> "SITE_ERROR"
            }
            throw failure(diagnostic, error, code)
        }
    }

    override suspend fun init(ext: String) {}
    override suspend fun home(filter: Boolean): String? = io("spider.home") { spider.homeContent(filter) }
    override suspend fun homeVod(): String? = io("spider.recommend") { spider.homeVideoContent() }
    override suspend fun category(tid: String, pg: String, filter: Boolean, extend: Map<String, String>): String? =
        io("spider.category") { spider.categoryContent(tid, pg, filter, HashMap(extend)) }
    override suspend fun detail(id: String): String? = io("spider.detail") { spider.detailContent(listOf(id)) }
    override suspend fun search(key: String, quick: Boolean, pg: String): String? = io("spider.search") { spider.searchContent(key, quick, pg) }
    override suspend fun play(flag: String, id: String, vipFlags: List<String>): String? = io("spider.play") { resolvingPlayback { spider.playerContent(flag, id, vipFlags) } }
    override suspend fun live(url: String): String? = io("spider.live") { spider.liveContent(url) }

    override suspend fun proxy(params: Map<String, String>): ProxyResponse? =
        io("spider.proxy") { spider.proxy(HashMap(params)) }?.let { toProxyResponse(it) }

    override val supportsDriveSignIn: Boolean get() = signInProxy != null

    override suspend fun driveSignIn(params: Map<String, String>): ProxyResponse? = io("spider.login") {
        @Suppress("UNCHECKED_CAST")
        val result = signInProxy?.invoke(null, HashMap(params)) as? Array<Any?>
        result?.let(::toProxyResponse)
    }

    override fun destroy() {
        runCatching { spider.destroy() }
    }
}

private inline fun <T> resolvingPlayback(block: () -> T): T {
    val previous = HostBridge.resolvingPlayback.get()
    HostBridge.resolvingPlayback.set(true)
    return try {
        block()
    } finally {
        if (previous == null) HostBridge.resolvingPlayback.remove() else HostBridge.resolvingPlayback.set(previous)
    }
}

/** TVBox proxy results: `[code, mime, InputStream | byte[] | String, headers?]`. */
fun toProxyResponse(result: Array<Any?>): ProxyResponse? {
    if (result.size < 3) return null
    val code = (result[0] as? Number)?.toInt() ?: return null
    val mime = result[1] as? String ?: "application/octet-stream"
    val body: InputStream? = when (val b = result[2]) {
        is InputStream -> b
        is ByteArray -> ByteArrayInputStream(b)
        is String -> ByteArrayInputStream(b.toByteArray())
        else -> null
    }
    val headers = (result.getOrNull(3) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v.toString() } ?: emptyMap()
    return ProxyResponse(code, mime, body, headers)
}
