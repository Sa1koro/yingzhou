package tvbox.engine.core.spider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tvbox.engine.core.config.SiteBean
import tvbox.engine.core.model.HomeResult
import tvbox.engine.core.model.DriveSignInPrompt
import tvbox.engine.core.model.DriveSignInStatus
import tvbox.engine.core.model.VodDetail
import tvbox.engine.core.model.VodPage
import tvbox.engine.core.site.PlayerResult
import tvbox.engine.core.site.RawPlay
import tvbox.engine.core.site.SiteSource
import tvbox.engine.core.vod.SiteResultException
import tvbox.engine.core.vod.TvboxResult

/**
 * A type-3 site backed by a [Spider]. The spider is created on first use (loading can take a while) and reused.
 * Mirrors the type-3 branches of TVBox `SourceViewModel`.
 *
 * The spider gets the site's `ext` as written, like TVBox (`JarLoader.getSpider` → `spider.init(context, ext)`):
 * an `ext` that is a url (a site's domain, `address|key`, a rule file) is the spider's to use. Only CMS and
 * `type: 4` sites have an url `ext` replaced by what it points at (`getFixUrl`), see [tvbox.engine.core.site.ExtResolver].
 */
class SpiderSource(
    override val bean: SiteBean,
    private val runtime: SpiderRuntime,
    private val vipFlags: () -> List<String>,
) : SiteSource {
    private val lock = Mutex()
    private val driveSignIn = OwnDriveSignIn()
    @Volatile private var spider: Spider? = null

    /** Loading a spider (download + compile + init) gets more time than a normal call. */
    override val timeoutMs: Long
        get() = if (spider == null) maxOf(super.timeoutMs, 30_000L) else super.timeoutMs

    private suspend fun spider(): Spider = spider ?: lock.withLock {
        spider ?: runtime.create(bean, bean.ext).also { spider = it }
    }

    private inline fun <T> Spider.readResult(stage: String, read: () -> T): T = try {
        read()
    } catch (error: SiteResultException) {
        val diagnostic = diagnostic(bean.key, stage, error)
        System.err.println(diagnostic.summary())
        throw SpiderLoadException(diagnostic.summary(), error, "SITE_BAD_RESULT", diagnostic)
    }

    override suspend fun home(): HomeResult {
        val sp = spider()
        val home = sp.readResult("spider.home") { TvboxResult.parseHome(bean.key, sp.home(true).orEmptyObject()) }
        if (home.list.isNotEmpty()) return home
        // Like TVBox: when home has no videos, ask homeVideoContent for the "推荐" list.
        return try {
            val vod = sp.homeVod()
            if (vod.isNullOrBlank()) home else home.copy(list = TvboxResult.parseList(bean.key, vod).list)
        } catch (error: Throwable) {
            val warning = (error as? SpiderLoadException)?.diagnostic ?: sp.diagnostic(bean.key, "spider.recommend", error).also { System.err.println(it.summary()) }
            home.copy(warning = warning)
        }
    }

    override suspend fun category(classId: String, page: Int, filters: Map<String, String>): VodPage = spider().run {
        readResult("spider.category") { TvboxResult.parseList(bean.key, category(classId, page.toString(), true, filters).orEmptyObject()) }
    }

    override suspend fun detail(id: String): VodDetail = spider().run {
        readResult("spider.detail") { TvboxResult.parseDetail(bean.key, detail(id) ?: throw SiteResultException("detail is empty")) }
    }

    override suspend fun search(keyword: String, quick: Boolean, page: Int): VodPage = spider().run {
        readResult("spider.search") { TvboxResult.parseList(bean.key, search(keyword, quick, page.toString()).orEmptyObject()) }
    }

    override suspend fun play(line: String, episodeId: String): RawPlay = play(line, episodeId, false)

    internal class DriveSignInRequired(val prompt: DriveSignInPrompt) : Exception()

    internal suspend fun play(line: String, episodeId: String, offerDriveSignIn: Boolean): RawPlay = spider().run {
        val text = play(line, episodeId, vipFlags())
        if (offerDriveSignIn && !text.isNullOrBlank()) {
            driveSignIn.start(this, text)?.let { throw DriveSignInRequired(it) }
        }
        readResult("spider.play") {
            if (!text.isNullOrBlank()) return@readResult PlayerResult.parse(text, defaultUrl = if (episodeId.startsWith("http")) episodeId else "", flag = line)
            // TVBox plays an http id directly when the spider has no answer.
            if (episodeId.startsWith("http://") || episodeId.startsWith("https://")) return@readResult RawPlay(url = episodeId, flag = line)
            throw SiteResultException("spider returned no play info")
        }
    }

    internal suspend fun pollDriveSignIn(handle: String): DriveSignInStatus =
        spider?.let { driveSignIn.poll(it, handle) } ?: DriveSignInStatus("expired")

    suspend fun proxy(params: Map<String, String>): ProxyResponse? = spider().proxy(params)

    /** False while the runtime cannot run spiders (no Python yet): searches leave the site out. */
    val available: Boolean get() = runtime.available

    fun destroy() {
        spider?.destroy()
        spider = null
    }

    private fun String?.orEmptyObject(): String = if (isNullOrBlank()) "{}" else this
}
