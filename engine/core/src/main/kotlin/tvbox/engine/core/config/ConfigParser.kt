package tvbox.engine.core.config

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * Parses decoded config text. Mirrors TVBox `ApiConfig.parseJson` / `parseApiCollection` and the
 * `DefaultConfig.safeJson*` helpers, so that sloppy real-world configs are accepted the same way.
 */
object ConfigParser {

    class InvalidConfigException(message: String) : Exception(message)

    fun parse(text: String): ConfigDocument {
        val root = parseRoot(text)
        parseRepository(root)?.let { return ConfigDocument.Repository(it) }
        if (!root.has("sites") || !root.get("sites").isJsonArray) {
            throw InvalidConfigException("config has neither \"sites\" nor \"urls\"")
        }
        return ConfigDocument.Single(parseConfig(root))
    }

    private fun parseRoot(text: String): JsonObject {
        val element = try {
            JsonParser.parseString(trimToObject(text))
        } catch (e: Exception) {
            throw InvalidConfigException("not valid JSON: ${e.message}")
        }
        if (!element.isJsonObject) throw InvalidConfigException("top level is not a JSON object")
        return element.asJsonObject
    }

    /** `{"urls": [...]}` without `sites` is a list of configs to choose from. */
    private fun parseRepository(root: JsonObject): List<RepoEntry>? {
        if (root.has("sites") || !root.has("urls") || !root.get("urls").isJsonArray) return null
        return root.getAsJsonArray("urls").mapNotNull { element ->
            val (name, url) = when {
                element.isJsonObject -> {
                    val item = element.asJsonObject
                    val url = str(item, "url", "").ifEmpty { str(item, "api", "") }
                    str(item, "name", "") to url
                }
                element.isJsonPrimitive -> "" to element.asString
                else -> "" to ""
            }
            val trimmedUrl = url.trim()
            if (trimmedUrl.isEmpty()) null else RepoEntry(name.trim().ifEmpty { trimmedUrl }, trimmedUrl)
        }
    }

    private fun parseConfig(root: JsonObject): TvboxConfig = TvboxConfig(
        spider = str(root, "spider", ""),
        wallpaper = str(root, "wallpaper", ""),
        danmaku = str(root, "danmaku", ""),
        sites = root.getAsJsonArray("sites").mapNotNull { parseSite(it) },
        parses = root.arrayOrNull("parses")?.mapNotNull { parseParse(it) } ?: emptyList(),
        flags = strList(root, "flags"),
        hosts = root.arrayOrNull("hosts")?.mapNotNull { entry ->
            val parts = runCatching { entry.asString }.getOrNull()?.split("=", limit = 2)
            if (parts != null && parts.size == 2) parts[0] to parts[1] else null
        }?.toMap() ?: emptyMap(),
        ads = strList(root, "ads"),
        livesJson = root.arrayOrNull("lives")?.toString() ?: "",
        rulesJson = root.arrayOrNull("rules")?.toString() ?: "",
        dohJson = root.arrayOrNull("doh")?.toString() ?: "",
        headersJson = root.arrayOrNull("headers")?.toString() ?: "",
    )

    /** Entries without key, type or api are skipped, as in TVBox. */
    private fun parseSite(element: JsonElement): SiteBean? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        if (!obj.has("key") || !obj.has("type") || !obj.has("api")) return null
        return runCatching {
            val key = obj.get("key").asString.trim()
            SiteBean(
                key = key,
                name = if (obj.has("name")) obj.get("name").asString.trim() else key,
                type = obj.get("type").asInt,
                api = obj.get("api").asString.trim(),
                searchable = int(obj, "searchable", 1),
                quickSearch = int(obj, "quickSearch", 1),
                changeable = int(obj, "changeable", 1),
                filterable = if (key.startsWith("py_")) 1 else int(obj, "filterable", 1),
                playUrl = str(obj, "playUrl", ""),
                ext = str(obj, "ext", ""),
                jar = str(obj, "jar", ""),
                playerType = int(obj, "playerType", -1),
                categories = strList(obj, "categories"),
                timeout = int(obj, "timeout", 0),
                click = str(obj, "click", ""),
                style = str(obj, "style", ""),
                catPawOpen = obj.get("catPawOpen")?.takeUnless { it.isJsonNull }?.let { str(obj, "catPawOpen", "") },
            )
        }.getOrNull()
    }

    private fun parseParse(element: JsonElement): ParseBean? {
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject
        return runCatching {
            ParseBean(
                name = obj.get("name").asString.trim(),
                url = obj.get("url").asString.trim(),
                type = int(obj, "type", 0),
                ext = if (obj.has("ext") && obj.get("ext").isJsonObject) obj.get("ext").toString() else "",
            )
        }.getOrNull()
    }

    // ---- DefaultConfig.safeJson* equivalents ----

    /** Objects and arrays come back as JSON text; primitives as their string value; anything else -> default. */
    private fun str(obj: JsonObject, key: String, default: String): String = runCatching {
        if (!obj.has(key)) return default
        val value = obj.get(key)
        when {
            value.isJsonObject || value.isJsonArray -> value.toString().trim()
            value.isJsonPrimitive -> value.asJsonPrimitive.asString.trim()
            else -> default
        }
    }.getOrDefault(default)

    private fun int(obj: JsonObject, key: String, default: Int): Int = runCatching {
        if (obj.has(key)) obj.getAsJsonPrimitive(key).asInt else default
    }.getOrDefault(default)

    private fun strList(obj: JsonObject, key: String): List<String> = runCatching {
        if (!obj.has(key)) return emptyList()
        val value = obj.get(key)
        if (value.isJsonArray) value.asJsonArray.map { it.asString } else emptyList()
    }.getOrDefault(emptyList())

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        if (has(key) && get(key).isJsonArray) getAsJsonArray(key) else null

    /** Drops anything before the first '{' and after the last '}' (TVBox `trimJsonObject`). */
    private fun trimToObject(text: String): String {
        val t = text.trim()
        val start = t.indexOf('{')
        val end = t.lastIndexOf('}')
        return if (start >= 0 && end > start) t.substring(start, end + 1) else t
    }
}
