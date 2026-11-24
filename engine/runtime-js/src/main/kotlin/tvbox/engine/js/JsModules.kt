package tvbox.engine.js

import okhttp3.OkHttpClient
import okhttp3.Request
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.util.UriUtil
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Finds the source of JS modules the way TVBox `FileUtils.loadModule` does:
 *
 * - names containing `gbk.js`, `模板.js` or `cat.js` always use the bundled copy;
 * - http(s) urls are downloaded and cached (7 days, like TVBox);
 * - `assets://js/lib/x.js` and bare names of bundled libraries come from the bundle;
 * - `clan://` and `file://` go through the engine's file mapping.
 */
class JsModules(
    private val http: OkHttpClient,
    private val locator: ConfigLocator,
    private val cacheDir: File? = null,
    private val maxAgeMs: Long = TimeUnit.DAYS.toMillis(7),
    private val log: (String) -> Unit = {},
    private val assets: File? = null,
) {
    private val memory = ConcurrentHashMap<String, String>()

    fun normalize(base: String, requested: String): String = UriUtil.resolve(base, requested)

    /**
     * The spider script itself. Unlike imports it is always fetched from its own address: TVBox applies the
     * bundled-library redirects to it too, so a spider whose url contains e.g. "cat.js" got the library instead.
     */
    fun loadMain(url: String): String? =
        if (url.startsWith("http://") || url.startsWith("https://")) remote(url)?.takeUnless { isInvalid(it) } else load(url)

    /** Source text, or null when the module cannot be found or is not usable as source. */
    fun load(name: String): String? {
        val text = when {
            name.contains("gbk.js") -> bundled("gbk.js")
            name.contains("模板.js") -> bundled("模板.js")
            name.contains("cat.js") -> bundled("cat.js")
            name.startsWith("http://") || name.startsWith("https://") -> remote(name)
            name.startsWith("assets://") -> asset(name.removePrefix("assets://"))
                ?: bundled(name.removePrefix("assets://").removePrefix("js/lib/"))
            name.startsWith("clan://") || name.startsWith("file://") ->
                remote(locator.clanToAddress(name.replace("file://", "clan://localhost/")))
            else -> bundled(name.substringAfterLast('/'))
        }
        return text?.takeUnless { isInvalid(it) }
    }

    fun bundled(file: String): String? = memory.getOrPut("bundle:$file") {
        javaClass.getResourceAsStream("/tvbox/js/lib/$file")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return null
    }

    private fun asset(name: String): String? {
        val root = assets?.canonicalFile ?: return null
        val target = File(root, name).canonicalFile
        return target.takeIf { it.toPath().startsWith(root.toPath()) && it.isFile }?.let {
            memory.getOrPut("bundle:asset:$name") { it.readText() }
        }
    }

    private fun remote(url: String): String? {
        memory[url]?.let { return it }
        val cached = cacheFile(url)
        if (cached != null && cached.exists() && System.currentTimeMillis() - cached.lastModified() < maxAgeMs) {
            return cached.readText().also { memory[url] = it }
        }
        val text = try {
            http.newCall(Request.Builder().url(url).build()).execute().use { res ->
                if (!res.isSuccessful) {
                    log("js module $url: HTTP ${res.code}")
                    null
                } else {
                    res.body?.string()
                }
            }
        } catch (e: Exception) {
            log("js module $url: $e")
            null
        }
        if (text == null || isInvalid(text)) return cached?.takeIf { it.exists() }?.readText()
        memory[url] = text
        cached?.let { runCatching { it.parentFile?.mkdirs(); it.writeText(text) } }
        return text
    }

    private fun cacheFile(url: String): File? = cacheDir?.let { File(it, md5(url) + ".js") }

    fun clearMemory() = memory.keys.removeIf { !it.startsWith("bundle:") }

    companion object {
        /** Error pages and 404 bodies are not modules (TVBox `isInvalidModuleContent`). */
        fun isInvalid(content: String?): Boolean {
            if (content.isNullOrEmpty()) return true
            val lower = content.trim().trimStart('﻿').trim().lowercase()
            return lower.startsWith("<") || lower.startsWith("{\"code\":404") || lower.startsWith("404") || lower.startsWith("not found")
        }

        private fun md5(s: String): String =
            MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
