package tvbox.engine.server

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tvbox.engine.core.spider.SpiderLoadException
import tvbox.engine.server.sniff.ChildProcesses
import java.io.BufferedWriter
import java.io.File
import java.nio.file.Files
import java.util.UUID

internal class YingxiaProcessHost(private val bundle: File, private val work: File, private val log: (String) -> Unit) {
    private class Instance(val process: Process, val root: File, val stdin: BufferedWriter)

    private var running: Instance? = null
    val revision = File(bundle, "revision.txt").takeIf { it.isFile }?.readText()?.trim()
        ?.takeIf { it.matches(Regex("[a-f0-9]{40}")) } ?: "unknown"

    fun available(): Boolean = File(bundle, "host.jar").isFile && File(bundle, "lib").isDirectory

    suspend fun request(input: JsonObject, seconds: Long): JsonObject = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        input.addProperty("request", id)
        val instance = send(input, id)
        val response = File(instance.root, "responses/$id.json")
        try {
            withTimeoutOrNull(seconds * 1000) {
                val ready = File(instance.root, "ready.json")
                while (!response.isFile) {
                    if (!instance.process.isAlive) throw unavailable("process exited")
                    delay(if (ready.isFile) 20 else 50)
                }
                if (response.length() > 16L * 1024 * 1024) throw unavailable("response too large")
                JsonParser.parseString(response.readText()).asJsonObject
            } ?: throw unavailable("request timed out")
        } finally {
            if (response.isFile) response.delete()
            else if (instance.process.isAlive) runCatching { cancel(instance, id) }
        }
    }

    @Synchronized private fun send(input: JsonObject, id: String): Instance {
        val instance = running?.takeIf { it.process.isAlive } ?: start().also { running = it }
        write(instance, input, id)
        return instance
    }

    @Synchronized private fun cancel(instance: Instance, requestId: String) {
        val id = UUID.randomUUID().toString()
        write(instance, JsonObject().apply {
            addProperty("command", "cancel")
            addProperty("request", requestId)
        }, id)
    }

    private fun write(instance: Instance, input: JsonObject, id: String) {
        val command = JsonObject().apply {
            addProperty("id", id)
            add("input", input)
        }
        instance.stdin.write(command.toString())
        instance.stdin.newLine()
        instance.stdin.flush()
    }

    private fun start(): Instance {
        if (!available()) throw unavailable("bundle missing")
        Files.createDirectories(work.toPath())
        val root = Files.createTempDirectory(work.toPath(), "process-").toFile().absoluteFile
        val java = File(System.getProperty("java.home"), "bin/${if (File.separatorChar == '\\') "java.exe" else "java"}")
        val classpath = listOf(File(bundle, "host.jar").absolutePath, File(bundle, "lib/*").absolutePath).joinToString(File.pathSeparator)
        val process = ProcessBuilder(
            java.absolutePath, "--add-opens", "java.base/java.lang=ALL-UNNAMED", "--add-opens",
            "java.base/sun.net.www.protocol.jar=ALL-UNNAMED", "-Dtvbox.lazyDex=true",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=error", "-Djava.io.tmpdir=${root.path}",
            "-cp", classpath, ENTRY_POINT, root.path,
        ).directory(root).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).apply {
            environment()["TEMP"] = root.path
            environment()["TMP"] = root.path
            environment()["TMPDIR"] = root.path
        }.start()
        ChildProcesses.register(process.toHandle())
        process.onExit().thenAccept { log("jar.host entry=$ENTRY_POINT exited status=${it.exitValue()}") }
        log("jar.host entry=$ENTRY_POINT delegate=InProcessHost revision=$revision")
        return Instance(process, root, process.outputWriter(Charsets.UTF_8))
    }

    private fun unavailable(reason: String) = SpiderLoadException("Yingxia JavaHost: $reason", code = "YINGXIA_HOST_FAILED")

    companion object {
        const val ENTRY_POINT = "tvbox.runtime.ProcessHost"
    }
}
