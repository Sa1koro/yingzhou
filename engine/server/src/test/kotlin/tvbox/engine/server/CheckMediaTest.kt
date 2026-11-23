package tvbox.engine.server

import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tvbox.engine.core.model.PlayInfo

class CheckMediaTest {
    private val segment = ByteArray(564).also { bytes ->
        for (offset in bytes.indices step 188) bytes[offset] = 0x47
    }

    private fun probe(answer: (Request) -> Response): CheckMedia = CheckMedia(
        OkHttpClient.Builder().addInterceptor { answer(it.request()) }.build(),
    )

    private fun Request.response(
        bytes: ByteArray, code: Int = 200, type: String = "application/octet-stream", vararg headers: Pair<String, String>,
    ) = Response.Builder().request(this).code(code).message("test").protocol(Protocol.HTTP_1_1).header("Content-Type", type)
        .body(bytes.toResponseBody(type.toMediaType())).apply { headers.forEach { (name, value) -> header(name, value) } }.build()

    @Test
    fun `walks a master playlist and samples the timed midpoint using HLS byte ranges`() = runBlocking {
        val requests = mutableListOf<Request>()
        val master = "#EXTM3U\nignored\n  #EXT-X-STREAM-INF:BANDWIDTH=5000\nvariant.m3u8\n"
        val variant = "#EXTM3U\n#EXTINF:1,\nfirst.ts\n#EXTINF:1,\nsecond.ts\n#EXTINF:8,\n" +
            "#EXT-X-BYTERANGE:564@100\nhttps://cdn.test/file\n#EXT-X-ENDLIST\n"
        val check = probe { request ->
            requests += request
            when (request.url.encodedPath) {
                "/master.m3u8" -> request.response(master.toByteArray())
                "/variant.m3u8" -> request.response(variant.toByteArray())
                "/first.ts" -> request.response(segment)
                "/file" -> request.response(segment, 206, "application/octet-stream", "Content-Range" to "bytes 100-663/900")
                else -> error("Unexpected request path")
            }
        }
        val evidence = check.read(PlayInfo("direct", "https://site.test/master.m3u8", mapOf("Cookie" to "session=local", "User-Agent" to "probe")))
        assertEquals(CheckMedia.Evidence("hls", 564, "hls-segment"), evidence)
        assertEquals("session=local", requests[2].header("Cookie"))
        assertEquals("bytes=100-663", requests.last().header("Range"))
        assertEquals("probe", requests.last().header("User-Agent"))
        assertNull(requests.last().header("Cookie"))
    }

    @Test
    fun `validates the actual file midpoint range and strips credentials on redirect`() = runBlocking {
        for (valid in listOf(true, false)) {
            val check = probe { request ->
                if (request.url.host == "site.test") {
                    assertEquals("local", request.header("Authorization"))
                    request.response(byteArrayOf(), 302, "text/plain", "Location" to "https://cdn.test/movie")
                } else {
                    assertNull(request.header("Authorization"))
                    assertNull(request.header("X-Token"))
                    when (request.header("Range")) {
                        "bytes=0-16383" -> request.response(ByteArray(16_384), 206, "video/mp4", "Content-Range" to "bytes 0-16383/40000")
                        "bytes=20000-36383" -> request.response(
                            ByteArray(16_384), 206, "application/octet-stream",
                            "Content-Range" to if (valid) "bytes 20000-36383/40000" else "bytes 10000-26383/40000",
                        )
                        else -> error("Unexpected range")
                    }
                }
            }
            val info = PlayInfo("direct", "https://site.test/movie", mapOf("Authorization" to "local", "X-Token" to "private"))
            assertEquals(if (valid) "range-206" else "range-unverified", check.read(info).midpoint)
        }
    }

    @Test
    fun `playlist alone and bogus segment bodies do not count as media`() {
        val playlist = "#EXTM3U\n#EXTINF:10,\nsegment.ts\n#EXT-X-ENDLIST\n"
        for (fake in listOf("<html>error</html>".toByteArray(), "{\"error\":1}".toByteArray(), ByteArray(100))) {
            val check = probe { request ->
                request.response(if (request.url.encodedPath == "/index") playlist.toByteArray() else fake)
            }
            assertThrows<CheckMedia.Failure> { runBlocking { check.read(PlayInfo("direct", "https://site.test/index")) } }
        }
    }
}
