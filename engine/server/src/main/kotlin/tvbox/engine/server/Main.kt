package tvbox.engine.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import tvbox.engine.api.EngineModuleOptions
import tvbox.engine.api.engineModule
import tvbox.engine.core.Engine
import tvbox.engine.core.config.ConfigLoader
import tvbox.engine.core.config.ConfigLocator
import tvbox.engine.core.model.Capabilities
import tvbox.engine.core.model.EngineInfo
import tvbox.engine.core.net.GithubProxy
import tvbox.engine.core.net.LenientTls
import tvbox.engine.core.net.NetworkProxy
import tvbox.engine.core.net.SiteHttp
import tvbox.engine.core.store.JsonFileStore
import tvbox.engine.core.spider.JarRepository
import tvbox.engine.core.store.MemoryStore
import tvbox.engine.jar.DesktopJarRuntime
import tvbox.engine.server.sniff.ChildProcesses
import tvbox.engine.server.sniff.CdpSniffer
import tvbox.engine.js.FileJsLocalStore
import tvbox.engine.js.JsEnvironment
import tvbox.engine.js.JsHttp
import tvbox.engine.js.JsModules
import tvbox.engine.js.JsRuntime
import tvbox.engine.js.MemoryJsLocalStore
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Standalone engine (desktop sidecar / Docker).
 *
 *   tvbox-engine [--port 9978] [--lan] [--data <dir>] [--name <name>] [--subscribe <url>] [--demo] [--dev-cors] [--exit-with-stdin]
 *                [--drive-folder <dir>]...
 *   tvbox-engine --check [<subscription or file or folder>...] [--only cms,js,jar,py,live] [--play] [--max-sites N] [--max-configs N]
 *                [--report <file.md>] [--strict]
 *
 * - --exit-with-stdin: exit when stdin closes. The desktop client starts the engine with a pipe on stdin, so the
 *   engine goes away with the client even when the client crashes.
 * - Port 9978 is the TVBox default; jar spiders look for their host on 9978..9999.
 * - Without --lan the engine only listens on 127.0.0.1, unless LAN sharing was switched on in the app (kept in
 *   the data dir; then it also listens on this machine's LAN addresses, see LanSharing). --lan listens on 0.0.0.0 with
 *   sharing always on. Other devices pair with a code the engine prints; --name sets the name they see.
 * - --data is where subscriptions, history and favorites are kept (default ~/.tvbox-next/engine).
 * - --demo serves a fake site under /demo and subscribes to it; it keeps data in memory unless --data is given.
 * - --dev-cors lets pages on http://localhost:* call the engine (Flutter web during development).
 * - --strict-tls checks HTTPS certificates. By default they are not checked, as in TVBox: the sites in real
 *   configs are full of expired and mismatched certificates.
 * - --check walks every site and live source of the subscriptions and reports what works (see [Checker]); nothing
 *   is saved, the port is a free one, and the exit code is 1 when something is the engine's to fix (with --strict,
 *   when anything at all failed). With --demo and no subscription it checks the built-in demo: a self-test (which
 *   leaves out 演示失效站, whose lines are dead on purpose).
 */
fun main(args: Array<String>) {
    if ("--exit-with-stdin" in args) {
        Thread({
            runCatching { while (System.`in`.read() >= 0) Unit }
            ChildProcesses.killAll()
            Runtime.getRuntime().halt(0)
        }, "stdin-watch").apply { isDaemon = true }.start()
    }
    val check = "--check" in args
    /** The kinds a check is limited to (`--only`); null when everything runs. */
    val checkOnly = if (check) argValue(args, "--only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() else null
    val requireSites = if (check && ("--require-sites" in args || "--require-sites-file" in args)) {
        val inlineKeys = argValue(args, "--require-sites")?.takeUnless { it.startsWith("--") }.orEmpty()
        val fileKeys = if ("--require-sites-file" in args) {
            val path = argValue(args, "--require-sites-file")?.takeUnless { it.startsWith("--") }
            val content = path?.let { runCatching { File(it).readText() }.getOrNull() }
            if (content == null) {
                System.err.println("--require-sites-file needs a readable UTF-8 file")
                return Runtime.getRuntime().halt(2)
            }
            content.removePrefix("\uFEFF")
        } else ""
        val keys = (inlineKeys.split(',') + fileKeys.lines()).map(String::trim).filter(String::isNotEmpty).toSet()
        if (keys.isEmpty()) {
            System.err.println("--require-sites or --require-sites-file needs at least one site key")
            Runtime.getRuntime().halt(2)
        }
        keys
    } else emptySet()
    val port = argValue(args, "--port")?.toIntOrNull() ?: if (check) freePort() else 9978
    val host = if ("--lan" in args) "0.0.0.0" else "127.0.0.1"
    val local = "http://127.0.0.1:$port"
    val dataDir = argValue(args, "--data")?.let(::File)
        ?: if ("--demo" in args || check) null else File(System.getProperty("user.home"), ".tvbox-next/engine")
    val store = dataDir?.let { JsonFileStore(it) } ?: MemoryStore()

    // Whether GitHub is reached directly or through a proxy is the user's setting (see GithubProxy).
    val githubProxy = GithubProxy()
    // The active config's hosts / headers and the user's DNS choice, for everything built from this client.
    val network = tvbox.engine.core.net.ConfigNetwork(log = { System.err.println(it) })
    val client = OkHttpClient.Builder()
        .let { network.install(it) }
        .addInterceptor(githubProxy)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // Like TVBox, certificates are not checked (see LenientTls) unless asked for.
        .let { if ("--strict-tls" in args) it else LenientTls.apply(it) }
        .build()
    val locator = ConfigLocator("$local/file/")
    val networkProxy = NetworkProxy()
    val configClient = networkProxy.client(client, NetworkProxy.CONFIG)
    val moduleClient = networkProxy.client(client, NetworkProxy.MODULES)
    val playbackClient = networkProxy.client(client, NetworkProxy.PLAYBACK)
    // Copies of configs and site answers: on disk with the data, in memory in demo mode; a check keeps nothing.
    val dataCache = if (check) null else tvbox.engine.core.cache.DataCache(dataDir?.let { File(it, "cache/data") })
    // What jar spiders request through the host's classes (com.github.catvod.net.OkHttp) follows the same rules.
    tvbox.engine.jarhost.HostBridge.baseClient = client
    tvbox.engine.jarhost.HostBridge.playbackClient = playbackClient
    val scriptBundles = argValue(args, "--yingxia-script-hosts")?.let(::File)
    val scriptAssets = scriptBundles?.let { File(it, "ScriptHost/assets") }
    val jsRuntime = JsRuntime(
        JsEnvironment(
            modules = JsModules(moduleClient, locator, cacheDir = dataDir?.let { File(it, "js-cache") }, log = { System.err.println(it) }),
            http = JsHttp(client),
            playbackHttp = JsHttp(playbackClient),
            local = dataDir?.let { FileJsLocalStore(File(it, "js-local.json")) } ?: MemoryJsLocalStore(),
            proxyUrl = "$local/proxy",
            log = { line -> if ("[log]" !in line && "[debug]" !in line && "[info]" !in line) System.err.println(line) },
        ),
    )
    // Jar spiders keep their app data and the converted jars on disk even in --demo mode.
    val work = dataDir ?: File(System.getProperty("java.io.tmpdir"), if (check) "tvbox-next-check" else "tvbox-next-engine-$port")
    val lite = scriptAssets?.takeIf { it.isDirectory }?.let { assets ->
        YingxiaLiteSites(assets, JsEnvironment(
            modules = JsModules(moduleClient, locator, File(work, "yingxia/lite-modules"), assets = assets),
            http = JsHttp(client), playbackHttp = JsHttp(playbackClient),
            local = FileJsLocalStore(File(work, "yingxia/lite-local.json")), proxyUrl = "$local/proxy",
        ), System.err::println)
    }
    val jars = JarRepository(moduleClient, locator, File(work, "cache/jar"))
    val desktopJar = DesktopJarRuntime(File(work, "spider"), jars, { port }, File(work, "cache/d2j"))
    val yingxiaBundle = argValue(args, "--yingxia-host")?.let(::File)
    val jarRuntime = if (yingxiaBundle != null) {
        val host = YingxiaProcessHost(yingxiaBundle, File(work, "yingxia/processes"), System.err::println)
        YingxiaJarRuntime(host, jars, File(work, "yingxia"), desktopJar, lite, System.err::println)
    } else desktopJar
    fun yingxiaScript(kind: String, directory: String) = scriptBundles?.let {
        YingxiaScriptRuntime(kind, File(it, directory), File(work, "yingxia"), moduleClient, locator, System.err::println)
    }?.takeIf { it.available }
    // Python spiders run in a Python process of their own, on a Python found on this machine (or downloaded when the
    // user asks for it in the app). A check that leaves "py" out does not look for one.
    val pyRuntime = if (checkOnly != null && "py" !in checkOnly) null else tvbox.engine.py.PyRuntime(
        manager = tvbox.engine.py.PythonManager(File(work, "python"), moduleClient, log = { System.err.println(it) }, networkProxy = networkProxy),
        scripts = tvbox.engine.py.PyScripts(moduleClient, locator, File(work, "python/spiders"), log = { System.err.println(it) }),
        proxyUrl = "$local/proxy",
        network = network,
        githubProxy = githubProxy,
        strictTls = "--strict-tls" in args,
        networkProxy = networkProxy,
        log = { System.err.println(it) },
        onProcess = { p, started -> if (started) ChildProcesses.register(p) else ChildProcesses.kill(p) },
    )
    // Web page sniffing in a headless Edge/Chrome, when one is installed.
    val sniffer = if (check && "--no-sniff" in args) null else CdpSniffer.findBrowser()?.let {
        CdpSniffer(it, File(work, "sniff-profile"), client, network = network, networkProxy = networkProxy)
    }
    // The HLS streams players are served, kept on disk; an engine without a data folder (--demo) starts with none.
    val streamDir = File(work, "cache/stream").also { if (dataDir == null) it.deleteRecursively() }
    val streamCache = tvbox.engine.core.cache.StreamCache(client, tvbox.engine.core.cache.StreamStore(streamDir), log = { System.err.println(it) })
    val engine = Engine(
        info = EngineInfo(
            name = "tvbox-engine",
            version = "0.4.1468250",
            platform = "jvm-" + System.getProperty("os.name").lowercase().substringBefore(' '),
            capabilities = Capabilities(jar = false, js = false, py = false, sniff = false),
        ),
        loader = ConfigLoader(configClient, locator, dataCache),
        http = SiteHttp(client),
        configHttp = SiteHttp(configClient),
        playbackHttp = SiteHttp(playbackClient),
        networkProxy = networkProxy,
        cache = dataCache,
        proxyBase = "$local/proxy?",
        store = store,
        // A check limited to some kinds must not run anything else. Skipping the sites is not enough: a live list
        // served through a spider's proxy, or a jar parser, loads the config's jar too. So without "jar" (or "js")
        // in --only that runtime does not exist in this engine at all.
        runtimes = listOfNotNull(jarRuntime, yingxiaScript("js", "ScriptHost") ?: jsRuntime,
            yingxiaScript("py", "PythonHost") ?: pyRuntime).filter { checkOnly == null || it.kind in checkOnly },
        sniffer = sniffer,
        githubProxy = githubProxy,
        network = network,
        streamCache = streamCache,
    )
    // Episodes downloaded for offline viewing: pinned streams in the stream cache's folder (or a folder the user picks).
    val downloads = if (check) null else tvbox.engine.core.download.DownloadManager(
        client, streamCache.store, stateFile = dataDir?.let { File(it, "downloads.json") },
        resolver = { engine.config(); engine.resolvePlay(it) }, cleaner = engine::cleanPlaylist, localBase = local,
        exportDir = { File(System.getProperty("user.home"), "Downloads").takeIf { it.isDirectory } ?: File(work, "exports") },
        log = { System.err.println(it) },
    )
    engine.downloads = downloads
    val demo = if ("--demo" in args) DemoSite(if (yingxiaBundle == null) "spider.jar" else "spider-host.jar") else null
    // The user's cloud drives and folders (AList / OpenList, WebDAV, folders of this machine), kept with the data.
    val drives = tvbox.engine.core.drive.DriveService(store, client, local, localFolders = true, log = { System.err.println(it) })
    // --drive-folder <folder>, any number of times: a folder of this machine as a drive (an engine without a screen).
    args.indices.filter { args[it] == "--drive-folder" && it + 1 < args.size }.map { args[it + 1] }.forEach { folder ->
        runCatching { drives.ensureFolder(folder) }.onFailure { System.err.println("--drive-folder $folder: ${it.message}") }
    }
    val demoDrive = if (demo != null && !check) DemoDrive(work).also { it.register(drives, local) } else null
    // Who may use the engine from other devices: pairing, tokens, signed media addresses (see AccessControl).
    val access = tvbox.engine.api.access.AccessControl(store, supported = !check, forced = "--lan" in args)
    // The demo site stands in for a third-party site, which other engines (an emulator's) fetch without a token.
    if (demo != null) access.publicPrefixes = listOf("/demo/")

    // Where the clients that let a phone drive them meet the phones (the LAN remote control); a check has none.
    val remote = if (check) null else tvbox.engine.api.remote.RemoteHub()

    val serverModule: io.ktor.server.application.Application.() -> Unit = {
        engineModule(engine, EngineModuleOptions(allowLocalhostCors = "--dev-cors" in args, access = access, drives = drives, remote = remote))
        routing {
            localFileRoutes()
            if (demo != null) demoRoutes(demo)
            if (demoDrive != null) demoDriveRoutes(demoDrive)
            if (pyRuntime != null) pythonRoutes(pyRuntime)
        }
    }
    val server = embeddedServer(CIO, port = port, host = host, module = serverModule).start(wait = false)
    println("tvbox-engine listening on http://$host:$port" + (dataDir?.let { ", data in ${it.absolutePath}" } ?: ", data in memory"))
    val lan = if (check) null else tvbox.engine.server.lan.LanSharing(
        access, port, argValue(args, "--name") ?: tvbox.engine.server.lan.LanSharing.defaultName(), boundToAll = "--lan" in args, module = serverModule,
    ).also { it.start() }
    println(when {
        sniffer != null -> "sniffing with " + CdpSniffer.findBrowser()
        check && "--no-sniff" in args -> "browser sniffing disabled for this check"
        else -> "sniffing unavailable: no Edge/Chrome/Chromium found (set TVBOX_BROWSER)"
    })

    if (check) {
        val fileInputs = if ("--check-inputs-file" in args) {
            val path = argValue(args, "--check-inputs-file")?.takeUnless { it.startsWith("--") }
            val content = path?.let { runCatching { File(it).readText() }.getOrNull() }
            if (content == null) {
                System.err.println("--check-inputs-file needs a readable UTF-8 file")
                return Runtime.getRuntime().halt(2)
            }
            content.removePrefix("\uFEFF").lineSequence().map(String::trim).filter { it.isNotEmpty() && !it.startsWith('#') }.toList()
        } else emptyList()
        val given = checkInputs(args, fileInputs)
        val inputs = given.ifEmpty { listOfNotNull(demo?.let { "$local/demo/config.json" }) }
        if (inputs.isEmpty()) {
            System.err.println("--check needs a subscription address, a config file or a folder of config files")
            Runtime.getRuntime().halt(2)
        }
        val options = Checker.Options(
            only = checkOnly ?: Checker.Options().only,
            play = "--play" in args || "--probe-media" in args,
            maxSites = argValue(args, "--max-sites")?.toIntOrNull() ?: 0,
            maxConfigs = argValue(args, "--max-configs")?.toIntOrNull() ?: Checker.Options().maxConfigs,
            // The self-test leaves out the demo site that is dead on purpose.
            skip = if (given.isEmpty()) DemoSite.SELF_CHECK_SKIP else emptyMap(),
            siteKeys = requireSites,
            mediaClient = playbackClient.takeIf { "--probe-media" in args },
            checkMirrors = "--all-sources" in args,
        )
        val header = listOf(
            "引擎：${engine.info.name} ${engine.info.version}（${engine.info.platform}），Java ${System.getProperty("java.version")}",
            "范围：${options.only.joinToString(", ")}" + (if (options.play) "，含播放解析" else "") +
                (if (options.mediaClient != null) "，含 HTTP 媒体读取" else "") +
                (if (options.maxSites > 0) "，每个配置最多 ${options.maxSites} 个站点" else ""),
        )
        val report = argValue(args, "--report")?.let { File(it).absoluteFile }
        val redactReport = "--redacted-report" in args
        report?.parentFile?.mkdirs()
        // Written after every config: a run that is stopped half way still leaves its report.
        fun write(results: List<Checker.ConfigCheck>) {
            report ?: return
            val reportResults = if (redactReport) CheckReportPrivacy.summarize(results) else results
            report.writeText(Checker.markdown(reportResults, header))
            File(report.path.substringBeforeLast('.') + ".json").writeText(Checker.json(reportResults).toString())
        }
        val logCheck: (String) -> Unit = if (redactReport) { _ -> } else ::println
        val results = runBlocking { Checker(engine, options, logCheck).run(inputs, ::write) }
        report?.let { println("报告：${it.path}") }
        val sites = results.flatMap { it.sites }.filter { it.verdict != "skipped" }
        println(
            "合计 ${results.size} 个配置，${sites.size} 个站点：通过 ${sites.count { it.verdict == "pass" }}，空 ${sites.count { it.verdict == "empty" }}，" +
                "引擎兼容 ${sites.count { it.cause == "compat" }}，不支持 ${sites.count { it.cause == "unsupported" }}，" +
                "网络 ${sites.count { it.cause == "network" }}，站点报错 ${sites.count { it.cause == "site" }}",
        )
        val unverified = Checker.unverifiedSites(results, requireSites, requirePlay = options.play)
        if (unverified.isNotEmpty()) {
            val labels = if (redactReport) unverified.map { CheckReportPrivacy.label("site", it) } else unverified
            System.err.println("目标站点未通过验收：${labels.joinToString()}")
        }
        ChildProcesses.killAll()
        Runtime.getRuntime().halt(if (unverified.isEmpty() && Checker.ok(results, strict = "--strict" in args || requireSites.isNotEmpty())) 0 else 1)
    }

    val subscribe = argValue(args, "--subscribe")?.let(::localInput) ?: demo?.let { "$local/demo/config.json" }
    runBlocking {
        val result = if (subscribe != null) {
            val sub = engine.addSubscription(subscribe, if (demo != null && subscribe.endsWith("/demo/config.json")) "演示订阅" else null)
            runCatching { engine.activate(sub.id) }
        } else {
            engine.restore()
        }
        result
            ?.onSuccess { println("loaded ${it.url}: ${it.sites} sites, ${it.parses} parses, ${it.lives} live groups") }
            ?.onFailure { println("failed to load the subscription: ${it.message}") }
    }
    // Downloads that were running when the engine stopped carry on, now that episodes can be resolved.
    downloads?.start()

    Runtime.getRuntime().addShutdownHook(Thread {
        ChildProcesses.killAll()
        lan?.stop()
        server.stop(500, 2000)
        downloads?.close()
    })
    Thread.currentThread().join()
}

/** A free port in the range jar spiders look for their host in (9978..9999), or any free one. */
private fun freePort(): Int {
    for (port in 9979..9999) {
        if (runCatching { ServerSocket(port, 1, InetAddress.getLoopbackAddress()).close() }.isSuccess) return port
    }
    return ServerSocket(0).use { it.localPort }
}

/** An existing file becomes its absolute path (which the config loader reads through /file/); anything else is kept. */
private fun localInput(input: String): String = File(input).takeIf { it.isFile }?.absolutePath ?: input

/** The arguments after --check up to the next option; a folder stands for the config files in it. */
private fun checkInputs(args: Array<String>, fileInputs: List<String>): List<String> =
    (args.drop(args.indexOf("--check") + 1).takeWhile { !it.startsWith("--") } + fileInputs).flatMap { input ->
        val file = File(input)
        when {
            file.isDirectory -> file.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
                // Folders of configs often carry status files next to them.
                .filter { f -> f.readText().let { "\"sites\"" in it || "\"urls\"" in it } }
                .map { it.absolutePath }
            else -> listOf(localInput(input))
        }
    }

/**
 * /file/<path>: files of this machine, for subscriptions kept on disk (clan://localhost/..., file://..., a plain
 * path) and what they refer to with ./ and ../. TVBox serves its storage folder the same way. Only for the engine
 * itself and other programs on this machine: requests from elsewhere, or for another host name, are refused.
 */
private fun Route.localFileRoutes() {
    get("/file/{path...}") {
        val loopback = runCatching { InetAddress.getByName(call.request.local.remoteAddress).isLoopbackAddress }.getOrDefault(false)
        val host = call.request.local.serverHost
        if (!loopback || (host != "127.0.0.1" && host != "localhost" && host != "[::1]")) {
            call.respond(HttpStatusCode.Forbidden)
            return@get
        }
        val rest = call.parameters.getAll("path").orEmpty().joinToString("/").trimStart('/')
        val file = listOf(File(rest), File("/$rest"), File(System.getProperty("user.home"), rest)).firstOrNull { it.isAbsolute && it.isFile }
        if (file == null) call.respond(HttpStatusCode.NotFound) else call.respondFile(file)
    }
}

private fun argValue(args: Array<String>, name: String): String? {
    val i = args.indexOf(name)
    return if (i >= 0 && i + 1 < args.size) args[i + 1] else null
}
