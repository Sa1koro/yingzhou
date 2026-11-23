package tvbox.engine.core.spider

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okio.buffer
import okio.source
import tvbox.engine.core.model.DriveSignInPrompt
import tvbox.engine.core.model.DriveSignInStatus
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class OwnDriveSignIn(private val clock: () -> Long = System::nanoTime) {
    private data class Pending(val handle: String, val provider: String, val task: String, val start: Long)

    private val gate = Mutex()
    private var pending: Pending? = null

    suspend fun start(spider: Spider, text: String): DriveSignInPrompt? = gate.withLock {
        if (!spider.supportsDriveSignIn) return null
        val provider = missingProvider(text) ?: return null
        pending = null
        val reply = call(spider, "loginStart", provider) ?: return null
        if (reply.string("code") != "0") return null
        val qr = reply.string("qrUrl")?.takeIf { it.length <= 2048 } ?: return null
        val address = qr.toHttpUrlOrNull()?.takeIf { it.username.isEmpty() && it.password.isEmpty() } ?: return null
        val task = reply.string("taskId")?.takeIf { it.isNotBlank() && it.length <= 512 } ?: return null
        val handle = UUID.randomUUID().toString()
        pending = Pending(handle, provider, task, clock())
        DriveSignInPrompt(handle, provider, address.toString())
    }

    suspend fun poll(spider: Spider, handle: String): DriveSignInStatus = gate.withLock {
        val active = pending?.takeIf { it.handle == handle } ?: return DriveSignInStatus("expired")
        if (clock() - active.start >= TimeUnit.MINUTES.toNanos(2)) {
            pending = null
            return DriveSignInStatus("expired")
        }
        val reply = call(spider, "loginPoll", active.provider, active.task) ?: return DriveSignInStatus("waiting")
        val status = reply.string("status")?.takeIf { it in terminal } ?: "waiting"
        if (status in terminal) pending = null
        DriveSignInStatus(status)
    }

    private suspend fun call(spider: Spider, action: String, provider: String, task: String? = null): JsonObject? {
        val params = mutableMapOf("do" to "pan", "action" to action, "provider" to provider)
        task?.let { params["taskId"] = it }
        return try {
            val response = spider.driveSignIn(params) ?: return null
            withContext(Dispatchers.IO) {
                response.body?.source()?.buffer()?.use { body ->
                    if (response.status != 200 || body.request(64 * 1024 + 1L)) null else objectFrom(body.readUtf8())
                }
            }
        } catch (error: CancellationException) {
            if (error !is TimeoutCancellationException) throw error
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun missingProvider(text: String): String? {
        val reply = objectFrom(text) ?: return null
        val url = reply.get("url")
        if (url?.isJsonPrimitive == true && url.asString.isNotBlank() || url?.isJsonArray == true && !url.asJsonArray.isEmpty) return null
        return missing.matchEntire(reply.string("msg")?.trim().orEmpty())?.groupValues?.get(1)
    }

    private fun objectFrom(text: String): JsonObject? = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()

    private fun JsonObject.string(name: String): String? = get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private companion object {
        val terminal = setOf("success", "expired", "error")
        val missing = Regex("^\\[([a-z0-9]{1,24})\\]\\([^)]*\\)\\s*.*(?:配置中心|未配置|已失效|未登录).*", RegexOption.DOT_MATCHES_ALL)
    }
}
