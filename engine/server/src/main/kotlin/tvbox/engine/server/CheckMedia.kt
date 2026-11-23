package tvbox.engine.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import tvbox.engine.core.model.PlayInfo
import tvbox.engine.core.play.HlsRewriter
import java.io.IOException
import java.util.concurrent.TimeUnit

internal class CheckMedia(client: OkHttpClient) {
    private val http = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(12, TimeUnit.SECONDS).build()

    data class Evidence(val transport: String, val bytes: Int, val midpoint: String)

    class Failure(val reason: String) : IOException(reason)

    suspend fun read(info: PlayInfo): Evidence = withContext(Dispatchers.IO) {
        if (info.kind != "direct") throw Failure("需要客户端嗅探")
        val url = info.url.toHttpUrlOrNull() ?: throw Failure("播放地址不是 HTTP(S)")
        val input = Input(url, info.headers)
        val first = get(input, url, 0L..(sampleBytes - 1L), allowPlaylist = true)
        if (first.playlist != null) readHls(input, first) else readFile(input, first)
    }

    private fun readFile(input: Input, first: Sample): Evidence {
        if (!first.media) throw Failure("播放响应未识别为媒体")
        val size = first.total
        val midpoint = if (size == null || size < 2) "unknown" else {
            val start = size / 2
            try {
                val part = get(input, first.url, start..(start + minOf(size - start, sampleBytes.toLong()) - 1), allowPlaylist = false)
                if (part.rangeStart == start && part.total == size && !part.textual) "range-206" else "range-unverified"
            } catch (_: Failure) {
                "range-unverified"
            }
        }
        return Evidence("file", first.bytes, midpoint)
    }

    private fun readHls(input: Input, first: Sample): Evidence {
        var sample = first
        repeat(4) {
            val text = sample.playlist ?: throw Failure("HLS 子列表未返回播放列表")
            val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
            val variant = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF:") }
            if (variant < 0) {
                val list = try {
                    HlsRewriter.rewrite(text, sample.url.toString(), allowLive = true)
                } catch (_: IllegalArgumentException) {
                    null
                } ?: throw Failure("HLS 播放列表无可读分片")
                val start = readSegment(input, list.resources[list.segments.first()])
                val middle = list.segments.lastOrNull { list.resources[it].startMs <= list.durationMs / 2 }
                val midpoint = if (list.segments.size < 2) "single-segment" else if (middle == null || middle == list.segments.first()) {
                    "segment-unverified"
                } else try {
                    readSegment(input, list.resources[middle])
                    "hls-segment"
                } catch (_: Failure) {
                    "segment-unverified"
                }
                return Evidence("hls", start, midpoint)
            }
            val child = lines.getOrNull(variant + 1)?.takeUnless { it.startsWith('#') }
                ?.let(sample.url::resolve) ?: throw Failure("HLS 主列表无可读线路")
            sample = get(input, child, null, allowPlaylist = true)
        }
        throw Failure("HLS 主列表嵌套过深")
    }

    private fun readSegment(input: Input, segment: HlsRewriter.Resource): Int {
        val url = segment.url.toHttpUrlOrNull() ?: throw Failure("HLS 分片地址不是 HTTP(S)")
        val range = if (segment.start < 0) null else {
            val count = minOf(segment.length, sampleBytes.toLong())
            if (count <= 0 || segment.start > Long.MAX_VALUE - count) throw Failure("HLS 分片偏移无效")
            segment.start..(segment.start + count - 1)
        }
        val result = get(input, url, range, allowPlaylist = false)
        if (!result.media || (range != null && range.first != result.rangeStart)) throw Failure("HLS 分片响应无效")
        return result.bytes
    }

    private fun get(input: Input, url: HttpUrl, range: LongRange?, allowPlaylist: Boolean, redirects: Int = 0): Sample {
        val request = Request.Builder().url(url)
        try {
            val sameOrigin = input.url.scheme == url.scheme && input.url.host == url.host && input.url.port == url.port
            input.headers.forEach { (name, value) ->
                val lower = name.lowercase()
                if (lower !in managedHeaders && (sameOrigin || lower in publicHeaders)) request.header(name, value)
            }
            request.header("Accept-Encoding", "identity")
            if (range != null) request.header("Range", "bytes=${range.first}-${range.last}")
            http.newCall(request.build()).execute().use { response ->
                if (response.code in redirectCodes) {
                    val target = response.header("Location")?.let(url::resolve) ?: throw Failure("媒体重定向地址无效")
                    if (redirects >= 5) throw Failure("媒体重定向过多")
                    response.close()
                    return get(input, target, range, allowPlaylist, redirects + 1)
                }
                if (!response.isSuccessful) throw Failure("媒体请求 HTTP ${response.code}")
                val body = response.body ?: throw Failure("媒体响应为空")
                val source = body.source()
                source.request(sampleBytes.toLong())
                val prefix = source.peek().readByteArray(minOf(sampleBytes.toLong(), source.buffer.size))
                if (prefix.isEmpty()) throw Failure("媒体响应为空")
                val type = response.header("Content-Type").orEmpty().substringBefore(';').lowercase()
                val head = prefix.decodeToString(endIndex = minOf(prefix.size, 512)).removePrefix("\uFEFF").trimStart()
                val isPlaylist = head.startsWith("#EXTM3U")
                val playlist = if (allowPlaylist && isPlaylist) {
                    if (response.code == 206 && range != null) {
                        response.close()
                        return get(input, url, null, allowPlaylist = true, redirects)
                    }
                    if (response.code == 206) throw Failure("HLS 响应是部分播放列表")
                    if (source.request(playlistLimit + 1L)) throw Failure("HLS 播放列表过大")
                    source.readUtf8().removePrefix("\uFEFF").trimStart()
                } else null
                val textual = isPlaylist || type.startsWith("text/") || type.endsWith("json") || type.endsWith("xml") ||
                    head.startsWith('<') || head.startsWith('{') || head.startsWith('[')
                val contentRange = response.header("Content-Range")?.let(contentRangePattern::matchEntire)
                val start = contentRange?.groupValues?.get(1)?.toLongOrNull()
                val end = contentRange?.groupValues?.get(2)?.toLongOrNull()
                val total = contentRange?.groupValues?.get(3)?.toLongOrNull()
                val honorsRange = response.code == 206 && range != null && start == range.first && end != null &&
                    end >= start && (total == null || total > end) &&
                    end == (total?.let { minOf(it - 1, range.last) } ?: range.last) && end - start + 1 == prefix.size.toLong()
                return Sample(response.request.url, prefix.size, textual, playlist,
                    !textual && (type.startsWith("video/") || type.startsWith("audio/") || hasMediaSignature(prefix)),
                    if (honorsRange) start else null,
                    if (honorsRange) total else body.contentLength().takeIf { response.code == 200 && it >= 0 })
            }
        } catch (error: IOException) {
            if (error is Failure) throw error
            throw Failure("媒体网络请求失败（${error.javaClass.simpleName}）")
        } catch (_: IllegalArgumentException) {
            throw Failure("媒体请求参数无效")
        }
    }

    private fun hasMediaSignature(prefix: ByteArray): Boolean {
        if (prefix.size >= 8 && prefix.copyOfRange(4, 8).decodeToString() in setOf("ftyp", "styp", "moof", "moov")) return true
        if (prefix.size > 376 && prefix[0] == 0x47.toByte() && prefix[188] == 0x47.toByte() && prefix[376] == 0x47.toByte()) return true
        return prefix.size >= 4 && (prefix.copyOfRange(0, 3).decodeToString() in setOf("FLV", "ID3") ||
            prefix.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1a, 0x45, 0xdf.toByte(), 0xa3.toByte())))
    }

    private data class Sample(
        val url: HttpUrl, val bytes: Int, val textual: Boolean, val playlist: String?, val media: Boolean,
        val rangeStart: Long?, val total: Long?,
    )

    private data class Input(val url: HttpUrl, val headers: Map<String, String>)

    companion object {
        val reportableReasons = setOf("需要客户端嗅探", "播放地址不是 HTTP(S)", "播放响应未识别为媒体", "HLS 分片响应无效")
        private const val sampleBytes = 16 * 1024
        private const val playlistLimit = 2 * 1024 * 1024
        private val contentRangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)", RegexOption.IGNORE_CASE)
        private val managedHeaders = setOf("host", "range", "accept-encoding", "connection", "content-length")
        private val publicHeaders = setOf("accept", "user-agent")
        private val redirectCodes = setOf(301, 302, 303, 307, 308)
    }
}
