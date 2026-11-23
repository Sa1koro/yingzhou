package tvbox.engine.core.play

import tvbox.engine.core.util.UriUtil
import java.security.MessageDigest

/**
 * Points a video-on-demand media playlist at the engine: every segment, key and initialisation map becomes a
 * [Resource] with a number, and the playlist text names them `<base><number>.<ext>` instead of their address at the
 * source. The engine then serves them from its stream cache ([tvbox.engine.core.cache.StreamCache]).
 *
 * - Relative addresses are resolved against the playlist's own address first.
 * - A byte range (`#EXT-X-BYTERANGE`, `BYTERANGE=` of `#EXT-X-MAP`) moves into the resource; the tag is dropped, so
 *   the player asks for a whole file and the engine asks the source for the range.
 * - Keys and maps named more than once are one resource; `METHOD=NONE`, `data:` and other non-http addresses stay.
 * - Every other line is kept as it is.
 *
 * A playlist without `#EXT-X-ENDLIST` is live: it is not rewritten ([rewrite] answers null).
 */
object HlsRewriter {
    enum class Kind { SEGMENT, KEY, MAP }

    /**
     * @param start first byte of the range at the source, or -1 for the whole file
     * @param length bytes in the range, or -1
     * @param startMs where the segment begins in the film
     * @param name what the stream cache calls the file: stable while the source keeps its paths, whatever tokens
     *   and hosts the addresses carry this time
     */
    data class Resource(
        val kind: Kind,
        val url: String,
        val start: Long = -1,
        val length: Long = -1,
        val startMs: Long = 0,
        val durationMs: Long = 0,
        val name: String,
        val ext: String,
    )

    class Result(private val parts: List<Any>, val resources: List<Resource>) {
        /** The segments in playing order, as indexes into [resources]. */
        val segments: List<Int> = resources.indices.filter { resources[it].kind == Kind.SEGMENT }

        val durationMs: Long = segments.lastOrNull()?.let { resources[it].startMs + resources[it].durationMs } ?: 0

        /** The playlist for a player that reaches the engine's resources under [base], e.g. `http://127.0.0.1:9978/hls/3fa9c2/`. */
        fun render(base: String, firstNumber: Int = 0): String = buildString {
            for (part in parts) if (part is Int) append(base).append(firstNumber + part).append('.').append(resources[part].ext) else append(part as String)
        }

        /** Changes whenever the list of resources does: a player holding an older playlist must not get other files under its numbers. */
        val fingerprint: String = sha1(resources.joinToString("\n") { "${it.kind} ${it.name} ${it.start} ${it.length}" })
    }

    private val URI_ATTR = Regex("URI=\"(.*?)\"")
    private val BYTERANGE_ATTR = Regex(",?BYTERANGE=\"(\\d+)(?:@(\\d+))?\"")
    private val BYTERANGE_TAG = Regex("#EXT-X-BYTERANGE:\\s*(\\d+)(?:@(\\d+))?")
    private val EXTINF = Regex("#EXTINF:\\s*([\\d.]+)")

    /** Extensions a player takes as media when it checks what a segment is called. */
    private val MEDIA_EXT = setOf("ts", "m4s", "mp4", "m4v", "m4a", "aac", "mp3", "ac3", "ec3", "vtt", "webvtt", "cmfv", "cmfa", "mpeg", "mpg", "m2ts", "mts")

    fun rewrite(text: String, baseUrl: String, allowLive: Boolean = false): Result? {
        val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
        if (!allowLive && lines.none { it.trim() == "#EXT-X-ENDLIST" }) return null
        val fragmented = lines.any { it.startsWith("#EXT-X-MAP") }

        class Draft(val kind: Kind, val url: String, val start: Long, val length: Long, val startMs: Long, val durationMs: Long)

        val drafts = ArrayList<Draft>()
        val parts = ArrayList<Any>()
        val shared = HashMap<String, Int>() // keys and maps by address and range
        val nextOffset = HashMap<String, Long>() // where the next range without an offset begins, by address
        var range: Pair<Long, Long?>? = null
        var duration = 0L
        var clock = 0L

        fun http(url: String) = url.startsWith("http://") || url.startsWith("https://")

        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            val end = if (i == lines.lastIndex) "" else "\n"
            when {
                line.isEmpty() -> parts += raw + end
                line.startsWith("#EXT-X-KEY") || line.startsWith("#EXT-X-MAP") -> {
                    val isMap = line.startsWith("#EXT-X-MAP")
                    val uri = URI_ATTR.find(line)
                    val url = uri?.let { UriUtil.resolve(baseUrl, it.groupValues[1]) }
                    if (uri == null || url == null || !http(url)) {
                        parts += raw + end
                        continue
                    }
                    val br = if (isMap) BYTERANGE_ATTR.find(line) else null
                    val length = br?.groupValues?.get(1)?.toLong() ?: -1
                    val start = if (br == null) -1 else br.groupValues[2].toLongOrNull() ?: 0
                    val id = shared.getOrPut("$isMap $url $start $length") {
                        drafts += Draft(if (isMap) Kind.MAP else Kind.KEY, url, start, length, 0, 0)
                        drafts.lastIndex
                    }
                    val rest = if (br == null) line else line.removeRange(br.range)
                    val at = URI_ATTR.find(rest)!!
                    parts += rest.substring(0, at.range.first) + "URI=\""
                    parts += id
                    parts += "\"" + rest.substring(at.range.last + 1) + end
                }
                line.startsWith("#EXT-X-BYTERANGE") -> {
                    // Belongs to the segment that follows; the tag itself is left out.
                    BYTERANGE_TAG.find(line)?.let { range = it.groupValues[1].toLong() to it.groupValues[2].toLongOrNull() }
                }
                line.startsWith("#") -> {
                    EXTINF.find(line)?.let { duration = Math.round(it.groupValues[1].toDouble() * 1000) }
                    parts += raw + end
                }
                else -> {
                    val url = UriUtil.resolve(baseUrl, line)
                    var start = -1L
                    var length = -1L
                    range?.let { (n, offset) ->
                        start = offset ?: nextOffset[url] ?: 0
                        length = n
                        nextOffset[url] = start + n
                    }
                    range = null
                    drafts += Draft(Kind.SEGMENT, url, start, length, clock, duration)
                    clock += duration
                    duration = 0
                    parts += drafts.lastIndex
                    parts += end
                }
            }
        }
        if (drafts.none { it.kind == Kind.SEGMENT }) return null

        // A cache name is the path at the source, without host and query (tokens and CDN hosts change between
        // visits) unless the query is what tells the files of this playlist apart.
        val byKind = drafts.groupBy { it.kind }
        val pathIsEnough = byKind.mapValues { (_, list) -> list.map { path(it.url) }.toSet().size == list.map { it.url }.toSet().size }
        val resources = drafts.map { d ->
            val identity = (if (pathIsEnough[d.kind] == true) path(d.url) else pathAndQuery(d.url)) + if (d.start >= 0) "#${d.start}+${d.length}" else ""
            val known = path(d.url).substringAfterLast('/').substringAfterLast('.', "").lowercase().takeIf { it in MEDIA_EXT }
            val ext = when (d.kind) {
                Kind.KEY -> "key"
                Kind.MAP -> "mp4"
                Kind.SEGMENT -> known ?: if (fragmented) "m4s" else "ts"
            }
            Resource(d.kind, d.url, d.start, d.length, d.startMs, d.durationMs, name = d.kind.name.lowercase().take(1) + sha1(identity).take(24), ext = ext)
        }
        return Result(parts, resources)
    }

    /** The path of an http address, from the first slash after the host up to the query. */
    fun path(url: String): String = pathAndQuery(url).substringBefore('?')

    private fun pathAndQuery(url: String): String {
        val afterScheme = url.indexOf("://").let { if (it < 0) 0 else it + 3 }
        val slash = url.indexOf('/', afterScheme)
        return if (slash < 0) "/" else url.substring(slash).substringBefore('#')
    }

    fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
