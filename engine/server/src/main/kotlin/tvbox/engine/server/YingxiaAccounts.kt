package tvbox.engine.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class YingxiaAccounts(private val file: File) {
    @Synchronized fun adopt(offer: JsonObject) {
        val saved = read(file) ?: JsonObject()
        val merged = saved.deepCopy()
        offer.text("baiduCookie")?.takeIf { "BDUSS=" in it }?.let { merged.addProperty("baiduCookie", it) }
        offer.text("ucCookie")?.takeIf { "__pus=" in it }?.let { merged.addProperty("ucCookie", it) }
        offer.text("token")?.takeIf { it.length == 32 }?.let { merged.addProperty("token", it) }
        val cookie = offer.text("quarkCookie")?.takeIf { "__pus=" in it }
        val ticket = offer.text("quarkServiceTicket")?.takeIf { it.isNotBlank() }
        if (cookie != null && ticket != null) {
            merged.addProperty("quarkCookie", cookie)
            merged.addProperty("quarkServiceTicket", ticket)
        }
        offer.get("wexPreferences")?.takeIf { it.isJsonObject }?.asJsonObject?.let { preferences ->
            val previous = merged.get("wexPreferences")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
            for (key in preferences.keySet()) preferences.text(key)?.let { previous.addProperty(key, it) }
            if (previous.size() > 0) merged.add("wexPreferences", previous)
        }
        if (merged == saved) return
        val parent = Files.createDirectories(file.absoluteFile.parentFile.toPath())
        val temporary = Files.createTempFile(parent, "accounts-", ".tmp")
        try {
            Files.writeString(temporary, merged.toString())
            Files.move(temporary, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun JsonObject.text(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    companion object {
        fun read(file: File): JsonObject? = if (file.length() !in 1..262_144) null else runCatching {
            JsonParser.parseString(file.readText()).asJsonObject
        }.getOrNull()
    }
}
