# TVBox 配置兼容性

> 逐字段说明引擎对 TVBox 配置的支持情况，对照的是 TVBoxOS / takagen99 Box 的行为。遇到"安卓版能用、这里不行"的订阅，先来这里查是不是已知缺口。
>
> ✅ 支持 · 🟡 读取了但只用了一部分 · ⬜ 读取了但没用上 / 不支持

## 1. 订阅地址与配置文件

| 写法 | 支持 | 说明 |
|---|---|---|
| `http(s)://…` | ✅ | |
| `clan://localhost/…`、`file://…` | ✅ | 引擎所在机器上的文件（桌面引擎）。相对路径从用户目录找 |
| 直接填本机路径（`C:\tv\a.json`、`/home/me/a.json`） | ✅ | 同上；文件里的 `./` 引用按文件所在目录解析 |
| `clan://<别的地址>/…` | ✅ | 另一台设备上的 TVBox 文件服务 |
| `地址;pk;密钥` | ✅ | AES-ECB |
| 图片里藏配置（`…**base64`） | ✅ | |
| `2423…` 开头的 AES-CBC | ✅ | |
| 不规范 JSON（注释、首尾有多余字符） | ✅ | 与安卓版同样用 Gson 宽松模式，只取第一个 `{` 到最后一个 `}` |
| 多仓 `{"urls": [{name, url}]}` | ✅ | 在「源」页选择子仓 |
| 多仓里套多仓 | ✅ | 选中的条目本身是多仓时，展开它并加载其中第一个能用的；它的条目随后出现在下拉里（缩进显示），最多套 3 层 |
| 多仓的第一个条目失效 | ✅ | 自动往后找能用的（只在不是用户亲手选的时候；用户选中的条目失效会直接报错） |
| 配置里引用的相对路径（`./xxx.js`） | ✅ | 相对订阅地址解析 |

### HTTPS 证书

与 TVBox 一样，引擎访问订阅里的地址时**不校验 HTTPS 证书**（过期、自签、域名不符都接受）。真实配置里大量站点、直播列表、节目单的证书是坏的，TVBox 能打开正是因为它不校验。代价是这些请求可能被网络中间人看到或篡改——与配置里大量使用的明文 http 相当。桌面引擎加 `--strict-tls` 可以恢复校验。

### GitHub 加速代理

订阅作者常把代理写死在 GitHub 地址前面（`https://gh-proxy.org/https://raw.githubusercontent.com/…`），因为他们所在的网络直连 GitHub 很慢。这类代理经常失效，一失效整个订阅就是 404。所以走不走代理由使用者决定，不由订阅决定：

- **关闭（默认）**：直连 GitHub，订阅里写的代理前缀被去掉。
- **打开**：GitHub 上的文件一律经你在「源」页填的代理获取（不管订阅里写的是哪个代理），代理失败时再直连。

对引擎发出的所有请求生效：配置、多仓条目、jar、JS 脚本、`ext`、直播列表、节目单，以及 jar 蜘蛛通过宿主发的请求（后者自 `hosts` 支持起才真正接上，之前文档这么写但没有生效）。只认"代理域名后面紧跟 GitHub 域名"这一种写法；jsDelivr 之类换了地址格式的镜像不处理。

## 2. 顶层字段

| 字段 | 支持 | 说明 |
|---|---|---|
| `spider` | ✅ | 全局 jar。支持 `;md5;校验值`、`img+` 前缀、伪装成 `.png` 的 jar、`clan://`。缓存 7 天 |
| `sites` | ✅ | 见第 3 节 |
| `parses` | ✅ | 见第 4 节 |
| `flags` | ✅ | 这些播放标识走解析（VIP 解析） |
| `rules` | ✅ | 见第 5 节 |
| `lives` | 🟡 | 见第 6 节 |
| `wallpaper` | ⬜ | 引擎会返回地址，客户端还没用 |
| `hosts` | 🟡 | 域名重定向，对引擎发出的请求生效；播放器自己取的媒体流不生效，见下面"网络项" |
| `doh` | ✅ | 列出的服务器出现在「设置 → 网络 → 安全 DNS」里供选择；用不用由使用者决定，默认关闭 |
| `headers` | ✅ | `[{host, header}]`：按域名加请求头，引擎的请求和交给播放器的请求头都带上 |
| `ads` | 🟡 | 嗅探时屏蔽这些域名（桌面引擎）；TVBox 内置的那份默认广告域名表没有搬过来 |
| `proxy` | ⬜ | 部分分支用来指定哪些域名走代理，未读取 |
| `danmaku` | ✅ | 弹幕接口，按片名和集数找弹幕（写法见第 7 节"弹幕"）。**默认不用**：它会收到每一集的片名，由使用者在「设置 → 弹幕」里打开；TVBoxOS 是默认就用，没有时还会去问它内置的第三方服务，这里两样都不做 |
| `ijk`、`logo`、`notice`、`warningText` 等 | ⬜ | 与安卓播放器内核或界面相关，忽略 |

### 网络项：`hosts`、`headers`、`doh`、`ads`

- **`hosts`**（`"域名=目标"`）：解析"域名"时改查"目标"（另一个域名或一个 IP），请求里的 Host 和 HTTPS 的域名不变。只替换一步，按整个域名匹配，域名里可以写 `*`（`cache.ott.*.itv.cmvideo.cn=…`）。IP 地址和 `localhost` 不会被重定向。
  - 生效范围：引擎发出的所有请求——配置和它引用的文件、采集站、JS 蜘蛛的 `req`、jar 蜘蛛通过宿主 `com.github.catvod.net.OkHttp` 发的请求、解析接口、直播列表、节目单、`/m3u8` 取播放列表；桌面引擎嗅探用的浏览器也按同样的规则解析（`--host-resolver-rules`）。
  - **不生效的地方**：播放器（libmpv）直接去取的媒体流和分片，用的是系统 DNS。真实配置里 `hosts` 多半正是给直播流的域名换 CDN 用的，这类频道目前仍可能打不开。jar 蜘蛛自带 HTTP 客户端（不走宿主类）时也不生效。Android 上 WebView 嗅探不生效。
- **`headers`**（`[{"host": 域名, "header": {…}}]`）：访问该域名的请求都带上这些请求头，同名的会被覆盖，重定向之后到达该域名的请求也算。播放地址或直播线路的域名命中时，这些请求头会并进交给播放器的 `headers`。
- **`doh`**（`[{"name", "url", "ips"}]`）：只是"可选项"。引擎内置腾讯、阿里、360、Google、AdGuard、Quad9 六个，配置里列的排在后面。选哪个是使用者在「设置 → 网络 → 安全 DNS」里的设置，**默认关闭（系统 DNS），订阅不能替你打开**——打开意味着引擎要访问的每个域名都会发给那个服务商。`ips` 用来直接连服务器本身，不必先用系统 DNS 查它。所选服务器查不到或连不上时退回系统 DNS（与 TVBox 一致），连不上时 30 秒内不再问它；局域网名字（没有点的名字、`.lan`、`.local` 等）和 IP 不会发出去。DoH 请求校验证书（引擎的其他请求不校验）。对播放器和嗅探浏览器不生效。
- **`ads`**：嗅探时，地址里含这些域名的请求不加载，也不会被当成视频地址。

切换订阅或改设置立即生效，不用重启；之前按旧规则建立的连接会被丢弃。

## 3. 站点 `sites[]`

没有 `key`、`type` 或 `api` 的条目会被跳过（与安卓版一致）。

| 字段 | 支持 | 说明 |
|---|---|---|
| `key`、`name` | ✅ | |
| `type: 0` | ✅ | XML 采集站 |
| `type: 1` | ✅ | JSON 采集站 |
| `type: 3` | 🟡 | 蜘蛛，按 `api` 后缀分运行时，见下表 |
| `type: 4` | ✅ | HTTP 远程蜘蛛 |
| `api` | ✅ | |
| `ext` | ✅ | 字符串、对象、数组都接受。采集站（`type: 0`、`1`）和 `type: 4` 的 `ext` 是网址时，先取回它指向的内容再发给站点（TVBox 的 `getFixUrl`）；蜘蛛（`type: 3`）原样拿到 `ext`，站点域名、`地址|密钥`、规则文件地址都由蜘蛛自己用（同 TVBox） |
| `jar` | ✅ | 站点自己的 jar，写法同 `spider` |
| `searchable` | ✅ | 为 0 时不参与聚合搜索 |
| `quickSearch` | 🟡 | 会返回给客户端，客户端暂未区分 |
| `filterable` | ✅ | |
| `playUrl` | ✅ | 拼在播放地址前面的解析前缀 |
| `categories` | ✅ | 只保留并按此顺序显示分类 |
| `timeout` | ✅ | 该站请求超时（秒） |
| `click` | ⬜ | 站点级的嗅探点击脚本，未用（`rules` 里的 `script` 是支持的） |
| `style` | ⬜ | 海报比例等，客户端未用 |
| `changeable` | ✅ | 默认 1。为 0 时，这个站点放不了时不会自动换到别的站，别的站放不了时也不会自动换到它；详情页的「换一个」里照样列出 |
| `playerType` | ⬜ | 桌面只有 libmpv 一个内核 |

站点返回的卡片 `vod_id` 以 `msearch:` 开头或为空时（豆瓣推荐站等），和 TVBox、FongMi 一样当作"只有片名"：不打开它的详情，按片名到其他可搜索的站点找同名的片，自动打开第一个能播的。详情没有播放线路、打不开、或者所有线路都放不了时，也按片名换到别的站点（只换到 `changeable` 不为 0 的站点，片名规整后必须完全相同，年份相差一年以上的不算）。

卡片的 `vod_tag` 为 `folder`（TVBox 还认 `cover`）时是**文件夹**：和 TVBox、FongMi 一样，点它不打开详情，而是用卡片的 id 请求分类，进入下一层（直播站的"平台 → 分区 → 房间"、网盘站的目录），返回回到上一层；海报左上角有文件夹标记。体检工具也会进文件夹（最多四层）找能打开的片。卡片上的 `action`（点了调用蜘蛛的 `action` 方法，配置中心、推送一类站点用）还不支持，这种卡片照常打开详情。

### 海报地址

站点给的海报（`vod_pic`）和直播台标按 TVBox / FongMi 的写法读：

| 写法 | 支持 | 说明 |
|---|---|---|
| `地址@Referer=…`、`@User-Agent=…`、`@Cookie=…` | ✅ | 只在出现这些标记时才截掉后缀（`…/@2x.png` 不受影响），顺序同 FongMi |
| `地址@Headers=<URL 编码的 JSON>` | ✅ | 解不开的 JSON 忽略 |
| 豆瓣图床（`doubanio.com`、`douban.com`） | ✅ | 没写 Referer 时补上豆瓣的 Referer 和浏览器 User-Agent，否则会被拒（418） |
| `data:image/…;base64,…` | ✅ | 直接显示 |
| `proxy://…`、`http://127.0.0.1:<引擎端口>/…` | ✅ | 改成当前引擎的地址（引擎在别的设备上时也能显示），配对令牌只发给引擎 |
| `//host/…` | ✅ | 按 https |
| `assets://`、`file://` | ⬜ | 显示占位图 |

Web 版浏览器不允许设置 Referer / Cookie / User-Agent，这些封面在 Web 上仍然可能出不来。

### 蜘蛛运行时（`type: 3`）

| `api` 的样子 | 运行时 | Windows / macOS | Android | 说明 |
|---|---|---|---|---|
| `csp_Xxx` | jar | ✅ | ✅ | 桌面上把 dex 转成 JVM 字节码，配安卓桩类运行 |
| `csp_Xxx`，jar 带 Android 原生库 | jar | 🟡 实验性 | ✅ | 桌面引擎可用 unidbg 调用包内 ARM32 / ARM64 JNI，支持范围见下 |
| `….js` | js | ✅ | ✅ | QuickJS，含 drpy 用到的宿主函数 |
| `….py`（`api` 里含 `.py`） | py | ✅（要有 Python 3.8+） | ⬜ | 引擎另起一个 Python 进程运行，见下面"Python 蜘蛛"；安卓内置引擎没有 Python，「源」页标"需要 Python" |

jar 蜘蛛在桌面上依赖的安卓类是桩类，覆盖了常见用法（`Context`、`SharedPreferences`、`Base64`、`Log`、`Looper`、`Bitmap` 等）。用到没有桩的类时会在引擎日志里列出来。

桌面引擎为每个蜘蛛包提供自己的类加载器和 `assets/` 资源。在开发者模式的「设置 → 开发者 → 本机内置引擎」打开 **实验性 Android 原生库兼容（JNI）**，退出并重开映舟。独立桌面或 NAS 引擎在启动进程前设置 `YINGZHOU_ANDROID_JNI=1`。默认关闭，只对自己信任的蜘蛛开启。蜘蛛的 Java 代码仍以引擎进程的权限运行。

桌面 JNI 运行时读取当前包的 Java 类层级，并用 unidbg 的 Java 代理处理包内类的构造器、方法和字段。Java 回调再次调用 JNI 时使用独立的模拟器实例，最多四层。Android 框架回调由桌面适配层响应，目录回调返回模拟路径，`java.io.File` 适配器支持创建路径对象和路径计算。在「开发者 → 日志」的源故障详情中可以查看 JNI 方法或回调签名，以及共享蜘蛛包首次初始化失败时记录的签名。

此路径可以调用随包提供、每个不超过 32 MiB 的 Android ARM 共享库。Windows x64 上已验证 `armeabi-v7a` 和 `arm64-v8a` 的 JNI 初始化、方法调用，以及通过 NDK 的 `libandroid.so` 读取蜘蛛包内的 `assets/`。JNI 可传回调用时传入的对象，也支持字符串、字节数组、`Object[]` 和 `String[]`。原生代码可通过 Android `Context` 获取资源管理器和当前蜘蛛包的类加载器，并查询本包内的类。通用 Java 回调、其他 Android 系统服务及部分 JNI 签名仍有兼容性限制。

多个站点共用一个蜘蛛包时，先看故障报告中的「共享包首次初始化」。后续站点可能只收到 `ExceptionInInitializerError`，这一栏会保留同一包第一次失败时的原因。报告会区分缺少原生库依赖、缺少库引用的符号、缺少具体 JNI 方法的绑定和 `JNI_OnLoad` 失败。站点首页返回、详情和播放解析分别有自己的故障阶段。

### Python 蜘蛛

TVBox 的安卓分支用 Chaquopy 在 App 里跑 Python；这里由引擎在本机另起一个 Python 进程（每个引擎一个，所有 Python 站点和直播共用，和 TVBox 一样）。

- **用哪个 Python**：依次是「设置 → Python 蜘蛛」里填的位置、环境变量 `TVBOX_PYTHON`、本机已装的 Python（Windows：`py -3`、`PATH` 上的 `python3` / `python`、python.org 默认的安装目录；macOS：`PATH` 和 Homebrew、python.org 的目录，苹果自带的 `/usr/bin/python3` 只在装了开发者工具时才用），最后是用户在那里点「下载 Python 运行时」下载的 CPython 3.14.10 免安装版（只有 Windows，约 12 MB，按 python.org 公布的 SHA-256 校验，放在引擎数据目录的 `python/runtime`）。版本要 3.8 或更新。应用不自带 Python，没有用户点击就不下载任何东西。
- **基类**：`from base.spider import Spider`，方法与 TVBox 的 pyramid 基类相同：`init(extend)`、`homeContent(filter)`、`homeVideoContent()`、`categoryContent(tid, pg, filter, extend)`、`detailContent(ids)`、`searchContent(key, quick, pg)`（只有 `key, quick` 两个参数的旧写法也行）、`playerContent(flag, id, vipFlags)`、`liveContent(url)`、`localProxy(param)`、`getDependence()`；工具函数 `fetch` / `post` / `postJson`（`requests` 没装时退回标准库）、`html` / `xpText`（lxml）、`regStr`（两种参数顺序都认）、`removeHtmlTags`、`cleanText`、`str2json` / `json2str`、`getProxyUrl`、`log`、`getCache` / `setCache` / `delCache`（存在引擎数据目录的文件里）、`loadSpider` / `loadModule`。`base.htmlParser` 的 `jsoup` 和 `base.localProxy` 的 `Proxy` 也有。`ext` 原样交给 `init`（和 jar、JS 蜘蛛一样，同 TVBox）；没写 `ext` 时用地址里的 `?extend=`。
- **依赖**（`getDependence()`）：先找配置里 `api` 等于这个名字的站点，用它的 `ext` 作为脚本地址；否则在蜘蛛旁边找 `<名字>.py`。加载好的依赖蜘蛛代替 `ext` 传给 `init`（同 TVBox）。
- **第三方包**：TVBox 的 Python 版自带 `requests`、`lxml`、`pycryptodome`、`beautifulsoup4`、`pyquery`、`ujson`、`jsonpath`、`cachetools`。这里一个也不自带：用户在设置里点「安装常用依赖」后，引擎用 pip（Python 没有 pip 时先从同一个源下载 pip 的 wheel）装到引擎自己的目录 `python/packages/<解释器>`，每种 Python 各一份，从不装进 Python 自己的环境；只装 wheel，不在安装时运行包里的代码。蜘蛛缺包时报 `PY_MODULE_MISSING`，提示里写着包名，设置里列出"哪个站点缺什么"，可以一键安装（包名和模块名不同的，如 `Crypto` → `pycryptodome`、`bs4` → `beautifulsoup4`，按对照表换算）。PyPI 和 Python 下载都可以换成镜像。
- **网络**：蜘蛛用 `requests`（以及 `fetch` / `post`）发的请求会经过配置的 `hosts`（在名字解析处替换，`socket.getaddrinfo`）、`headers`（按域名加请求头）和「GitHub 加速代理」开关；证书默认不检查（同引擎，`--strict-tls` 时检查，蜘蛛明确写了 `verify=True` 的照办）。不经过的：DoH（Python 用系统 DNS）；`urllib`、`http.client`、`aiohttp` 等其他库发的请求只有 `hosts` 生效。
- **出问题时**：每次调用有超时（站点的 `timeout`，默认 15 秒），超时的调用在 Python 里继续跑也不影响别的；一个 Python 进程里卡住的调用超过 8 个就换一个新进程。蜘蛛把整个进程搞崩（`os._exit`、原生库崩溃）时，这一次调用报 `PY_CRASHED`，下一次调用自动起新进程、重新加载蜘蛛；一分钟内崩溃 5 次就暂停。蜘蛛 `print` 的内容和 Python 的报错进引擎日志（前缀 `py:`）。引擎退出时 Python 进程一起退出：引擎关掉它，或者引擎异常退出导致管道断开时它自己退出。
- **不支持的**：`isVideoFormat` / `manualVideoCheck`（嗅探时的自定义判断）没有接；Chaquopy 特有的 `from com.github.catvod import …`（调用安卓 Java 类）没有对应物，用到的蜘蛛加载失败。

### 蜘蛛的本地代理

蜘蛛返回的 `proxy://…` 和 `http://127.0.0.1:9978/proxy?…` 都指向引擎的 `/proxy`：先交给对应站点的蜘蛛实例，再依次交给各个 jar 的静态 `Proxy.proxy`；`do=js` 交给 JS 蜘蛛；`do=py` 只交给 Python 蜘蛛的 `localProxy(param)`（`siteKey` 指名的那个，没有就是最近用过的，同 TVBox），返回 `[状态码, 类型, 内容, 响应头, base64?]`，内容是文本、bytes 或 base64。Python 蜘蛛的 `getProxyUrl()` 会带上自己的 `siteKey`；`playerContent` 里写死的 `http://127.0.0.1:9978/proxy`、`http://127.0.0.1:UndCover/proxy` 换成引擎实际的地址。引擎端口不是 9978 时会把端口告诉 jar。

### 配置里的网盘

TVBox 的配置格式本身没有"网盘"字段。真实配置里出现的 `drives`（`[{"name", "server", "password"?}]`，多是公开的 AList 站）写在 `csp_AList` 之类 jar 蜘蛛站点的 `ext` 里，是那个蜘蛛自己的配置：它们作为普通的蜘蛛站点在「首页」里浏览，由 jar 运行时处理，和「网盘」页无关，也不会被自动加成网盘。Box 的"存储"（本地目录、WebDAV、AList 网页）是 App 里由用户自己添加的，映舟的「网盘」对应的就是它（见 [protocol.md](protocol.md)"网盘"）：AList 只支持 v3 的 API（`/api/fs/list`，OpenList 相同），Box 还认的 v2 接口（`/api/public/path`）已经没有在维护的 AList 用了，不支持。网盘文件的播放地址是 `drive:<id>` 站点的剧集，`drive:` 开头的站点键保留给网盘。

## 4. 解析 `parses[]`

| `type` | 支持 | 说明 |
|---|---|---|
| 0 嗅探 | ✅ | 引擎用无头 Edge / Chrome 打开解析页，抓视频地址 |
| 1 JSON | ✅ | 读 `url` / `data.url`、`header(s)`、`user-agent`、`referer`、`parse` |
| 2 JSON 扩展 | ✅ | 调 jar 里的 `parser.Json<url>` |
| 3 JSON 聚合 | ✅ | 调 jar 里的 `parser.Mix<url>` |
| 4 超级解析 | 🟡 | 并发尝试所有 type 1 的 JSON 解析接口，取最先成功的；不并发嗅探类解析 |
| `ext.header` | ✅ | 请求解析接口时带上 |
| `ext`（整体） | ✅ | JSON 解析接口带 `ext` 时，以 `cat_ext=<base64>` 拼进地址（同 TVBox） |

默认解析器取列表第一个（与 TVBox 一致）；它失败后再并发尝试所有 JSON 解析接口。客户端目前不能手动选解析器。

## 5. 规则 `rules[]`

| 写法 | 支持 | 用途 |
|---|---|---|
| `host` + `rule` | ✅ | 嗅探：该域名下匹配这些正则的请求才算视频 |
| `host` + `filter` | ✅ | 嗅探：排除 |
| `hosts` + `regex`（`#EXT…`、时长数字） | ✅ | m3u8 去广告：删掉匹配的片段 |
| `hosts` + `regex`（其他正则） | ✅ | 嗅探规则 |
| `hosts` + `script` | ✅ | 嗅探页面加载后执行的点击脚本 |
| `exclude` | ✅ | 嗅探排除 |

去广告在规则之外还有一个保守的启发式（去掉夹在 `#EXT-X-DISCONTINUITY` 之间、来自少数路径的分片）；要删的内容超过一半时放弃，保留原列表。

## 6. 直播 `lives[]`

| 写法 | 支持 | 说明 |
|---|---|---|
| `{type: 0, url}`：TXT 列表 | ✅ | `分组,#genre#`、`频道,地址`、`地址#地址2`、`地址$线路名` |
| `{type: 0, url}`：M3U 列表 | ✅ | `group-title`、`tvg-logo`、`tvg-name`、`x-tvg-url`（多个地址依次尝试）、`#EXTVLCOPT` 与 `http-user-agent` / `http-referrer` 属性里的 UA / Referer |
| `{type: 0, url}`：JSON 列表 | ✅ | `[{"group"｜"name", "channels"｜"channel": [{"name", "urls"｜"url", "logo"}]}]` |
| `url` 写成 `http://127.0.0.1:9978/proxy?do=live&type=txt&ext=…` | ✅ | 等同 `proxy://do=live`，直接取它指向的列表 |
| `url` 或频道地址指向 `http://127.0.0.1:9978/proxy?…` | ✅ | 改指本引擎的 `/proxy`（由 jar 的代理处理）；没有蜘蛛应答时退而取 `url=` 参数里的列表 |
| `{group, channels: [{name, urls}]}` 内联 | ✅ | |
| `channels[].urls` 里是 `proxy://do=live&type=txt&ext=…` | ✅ | `ext` 是地址或其 base64 |
| 列表本身被包成配置格式（图片 base64、AES） | ✅ | |
| `epg`：带 `{name}` / `{date}` 的模板 | ✅ | 返回 `epg_data` 的那种接口 |
| `epg`：不带占位符的 DIYP 接口 | ✅ | 按 `?ch=&date=` 请求 |
| `epg`：XMLTV（`.xml` / `.xml.gz`） | ✅ | 也认 M3U 头里的 `x-tvg-url` |
| `logo`：带 `{name}` 的模板 | ✅ | |
| `ua`、`referer`、`header` | ✅ | 随线路下发给播放器 |
| `{type: 3, api, ext, jar, url}` 蜘蛛直播 | ✅ | 列表由蜘蛛给出：jar 和 Python 蜘蛛问 `liveContent(url)`，JS 蜘蛛问 `live(url)`（没写 `url` 时传脚本自己的地址，同 TVBox）。返回 TXT / M3U / JSON 文本都行，JS 也可以直接返回分组数组，Python 返回的字典 / 列表按 JSON 处理。蜘蛛没有返回、而 `url` 是个地址时，按普通列表下载它（TVBox 对这种写法也只是下载列表，蜘蛛只管频道的代理地址）。Python 蜘蛛直播要本机有 Python，没有时「直播」页标"需要 Python" |
| 蜘蛛列表里的 `proxy://…`、`http://127.0.0.1:9978/proxy?do=js&…`、`…?do=py&…` | ✅ | 指向本引擎的 `/proxy`，并补上这个直播源的蜘蛛的 `siteKey`，请求一定回到给出列表的那个蜘蛛。TVBox 是交给"最近用过的蜘蛛"；这里看直播的中途可以去点播别的站点，不带 `siteKey` 会串到别的脚本上 |
| `{type: 3}` 但没有 `api` | ✅ | 和 TVBox 一样当普通列表：下载 `url`，带上这一条的 `ua`、`header` 等请求头；列表里 `proxy://` 的频道交给配置的 `spider` jar 处理 |
| `catchup` 回看 | ✅ | 见下面"回看" |
| `playerType`、`timeout`、`boot`、`pass` | ⬜ | 忽略 |

### 回看（catchup）

回看规则从这几处读，频道自己的优先：

| 写法 | 支持 | 说明 |
|---|---|---|
| M3U `#EXTINF` 的 `catchup`、`catchup-source`、`catchup-days`、`catchup-correction` | ✅ | `catchup-correction` 单位是小时（同 Kodi IPTV Simple），加到填进地址的开始 / 结束时间上 |
| M3U `#EXTM3U` 头上的同名属性 | ✅ | 作为所有频道的默认值，频道没写的项从这里取 |
| M3U `catchup-replace`、`timeshift="天数"`（SIPTV） | ✅ | 后者等于 `catchup="shift"` 加天数 |
| JSON 列表 / 配置内联频道的 `catchup`（对象，或类型字符串加 `catchup-source`） | ✅ | |
| `lives[].catchup` 对象：`type`、`source`、`replace`、`regex`（再加 `days`） | ✅ | 用于自己没有完整规则的频道；`regex` 不为空时，只有地址包含或匹配它的线路才能回看（TVBox） |
| 没有任何规则、地址里有 `/PLTV/` 的频道 | ✅ | 同 TVBox：`/PLTV/` 换成 `/TVOD/`，后面加 `?playseek=开始-结束` |

类型（`type` / `catchup`）：

| 类型 | 地址怎么来 |
|---|---|
| `default`、`vod` | `source` 就是完整地址 |
| `append`、不写 | 直播地址（先按 `replace` 的 `正则,替换` 改）后面接 `source`；直播地址已有查询串时 `source` 里的 `?` 换成 `&`（TVBox） |
| `shift`、`timeshift` | 直播地址加 `?utc={utc}&lutc={lutc}`（已有查询串时用 `&`） |
| `flussonic`、`flussonic-hls`、`flussonic-ts`、`fs` | 由直播地址推出：先给归档播放列表 `<名>-{utc}-{duration}.m3u8`（mpegts 为 `archive-{utc}-{duration}.ts`，可拖动），再给 Kodi 的 `timeshift_rel-{offset:1}.m3u8` / `timeshift_abs-{utc}.ts` 作为备选 |
| `xc`（Xtream Codes） | `http://主机/timeshift/用户/密码/{duration:60}/{Y}-{m}-{d}:{H}-{M}/编号.ts`（直播是 `.m3u8` 时用 `.m3u8`） |

占位符：`{utc}` / `${start}` 开始，`{utcend}` / `${end}` 结束，`{lutc}` / `${now}` / `${timestamp}` 现在（都是秒级时间戳）；`{duration}` / `${duration}` 秒数，`{duration:N}` 以 N 秒为单位；`{offset:N}` 从开始到现在；`{Y}{m}{d}{H}{M}{S}` 开始时间的各个字段；`{utc:YmdHMS}`、`${end:Ymd-H-M}` 这样带格式的（Kodi 的字母，也认 `yyyyMMddHHmmss` 这种 Java 写法）；TVBox 的 `${(b)yyyyMMddHHmmss}` / `${(e)yyyyMMddHHmmss}`（开始 / 结束，格式写 `timestamp` 时为时间戳）。日期按中国时间写，和节目单一致。不认识的占位符原样保留。还在播的节目只回看到现在。

天数：`catchup-days` / `days`，没写时 7 天（TVBox 列一周的节目单）。`rtp://`、`udp://` 组播地址不能回看。

不支持：Kodi 的 `{catchup-id}`（XMLTV 节目的 `catchup-id`）、`tvg-rec`、`|` 后面的播放器参数。

节目单按频道名匹配，匹配前会规整名字：`CCTV-1 综合` → `CCTV1`，`CCTV-5+ 体育赛事` → `CCTV5+`，去掉"高清 / 超清 / HD / 4K / 频道"等后缀。规整后查不到再用原名。

## 7. 播放结果（蜘蛛 `playerContent` 的返回）

| 字段 | 支持 | 说明 |
|---|---|---|
| `url` | ✅ | |
| `parse: 0` | ✅ | 直接播 |
| `parse: 1` | ✅ | 交给嗅探 |
| `jx: 1` | ✅ | 走解析列表 |
| `header` / `headers` | ✅ | 对象或 JSON 字符串都行 |
| `flag` | ✅ | |
| `playUrl` | ✅ | |
| `subt` | ✅ | 一个字幕地址；`地址#文件名.ass` 里 `#` 后面是显示的名字（扩展名说明格式），不发给服务器 |
| `subs` | ✅ | `[{name, lang, format, url}]`，全部列出（Box 只取第一条）；`format` 认 `application/x-subrip`、`text/x-ssa`、`text/vtt`、`text/lrc` 等 MIME 类型；名字里有 lyric / lrc / 歌词的是歌词，不当字幕。也接受写成 JSON 字符串或单个对象 |
| `danmaku` | ✅ | 弹幕地址（`http(s)://`、`proxy://`），或直接写进来的弹幕（XML 文本、JSON），见下面"弹幕" |
| `format` | 🟡 | 引擎按地址和响应判断格式，不看这个字段 |

### 字幕

`subt` 在前，`subs` 依次在后，同一地址只算一次。地址可以是 `http(s)://` 或 `proxy://`（蜘蛛自己的代理）；`file://` 等本地路径不支持。

交给播放器的不是站点的地址，而是引擎的 `/subtitle?url=…`：引擎带上播放结果里的 `header` 去取，再交给播放器 UTF-8 文本——

| 字幕文件 | 支持 | 说明 |
|---|---|---|
| SRT、ASS / SSA、WebVTT | ✅ | 原样转交；SRT 时间里的 `.` 改成 `,` |
| TTML / DFXP、LRC、B 站 JSON 字幕（`{"body":[{from,to,content}]}`） | ✅ | 转成 SRT |
| MicroDVD 等其他文本格式 | 🟡 | 原样转交，由播放器自己识别 |
| GBK、Big5、UTF-16（有无 BOM）、UTF-8 | ✅ | 按内容识别，不信服务器声明的编码；GBK 和 Big5 按常用字出现的多少区分 |
| gzip / deflate 压缩的文件 | ✅ | 按内容识别并解压 |
| 图形字幕（SUP、VobSub）、`.zip` 字幕包 | ⬜ | |

字幕由 libmpv 用 libass 绘制，ASS 的字体、颜色、位置照原样显示。安卓客户端由 media_kit 画纯文本字幕。

### 弹幕

一集的弹幕按顺序找，找到就停：① 播放结果里的 `danmaku`（引擎在解析播放地址时记下，按"站点 + 线路 + 剧集"）；② 配置顶层的 `danmaku` 接口，**使用者打开了才问**；③ 使用者自己填的弹幕接口。结果在引擎里按集保留 30 分钟（最近 24 集）。

| 弹幕接口的写法（配置 `danmaku`，或自己填的） | 支持 | 说明 |
|---|---|---|
| 带 `{name}`、`{episode}` 的地址 | ✅ | 填进片名和集名（URL 编码）后 GET |
| 以 `/danmaku` 结尾的地址 | ✅ | POST 表单 `name`、`episode`（同 TVBoxOS） |
| 其他地址 | ✅ | 当作弹弹play 兼容接口的根（如 danmu_api 一类自建服务）：`/api/v2/search/episodes?anime=&episode=` 找剧集（片名相同的优先，再按集名、集数，电影取唯一的一集），`/api/v2/comment/{id}?format=json` 取弹幕 |
| 接口的回答 | ✅ | 弹幕本身（下表的格式），或弹幕地址：`{"url"}`、`[{"url"}]`（取第一个）、纯文本地址 |
| TVBoxOS 内置的第三方弹幕服务 | ⬜ | 不内置任何第三方服务（会把看的内容发出去） |
| jar 蜘蛛的弹幕搜索界面（`onClick` / `onLongClick`） | ⬜ | |

| 弹幕格式 | 支持 | 说明 |
|---|---|---|
| B 站 XML `<d p="时间,模式,字号,颜色,…">` | ✅ | 模式 1/2/3/6 滚动、4 底部、5 顶部；7（高级）、8（代码）弹幕不显示。实体、CDATA、属性顺序、单双引号都按宽松方式读 |
| B 站接口的 raw deflate（`Content-Encoding: deflate`）、gzip | ✅ | 按内容识别并解压 |
| JSON `{comments: [{p, m}]}`（弹弹play 类服务） | ✅ | `p` = 时间,模式,颜色,用户；五段以上按 B 站的顺序（字号在颜色前）。也认 `data`、`list` 等字段，或直接一个数组 |
| DPlayer / ArtPlayer：`[时间, 类型, 颜色, 作者, 文字]`、`{text, time, color, type}` | ✅ | 类型 0 滚动、1 顶部、2 底部 |
| ASS（弹幕转换工具生成的） | ✅ | `\move` 为滚动，`\an8` 顶部、`\an2` 底部（或样式里的对齐），颜色取 `\c&HBBGGRR&` |
| B 站 protobuf 分段弹幕 | ⬜ | |
| 编码 | ✅ | 同字幕：UTF-8 / UTF-16 / GBK / Big5 自动识别 |

### 播放地址是 m3u8 时

地址里带 `.m3u8` 的直链会经引擎的 `/m3u8`：去广告（第 5 节），点播的再进播放缓存（引擎代为下载分片并存盘，拖动进度条不再回片源，见使用说明第 4 节）。

| 播放列表里的写法 | 支持 | 说明 |
|---|---|---|
| 主播放列表（多码率、`#EXT-X-MEDIA` 音轨） | ✅ | 每个码率各自缓存 |
| 相对地址、带 token 的地址、每次解析换 CDN 域名 | ✅ | 缓存按片源路径命名，不看域名和查询串 |
| `#EXT-X-KEY`（AES-128） | ✅ | 密钥也经引擎取；解密仍由播放器做 |
| `#EXT-X-MAP`（fMP4 初始化段） | ✅ | |
| `#EXT-X-BYTERANGE`（所有分片在一个文件里） | ✅ | 引擎按范围向片源请求，每个范围单独缓存 |
| 分片伪装成 `.png` / `.jpg` 等扩展名 | ✅ | 交给播放器时一律叫 `.ts` |
| 直播（没有 `#EXT-X-ENDLIST`） | ✅ | 只去广告，不缓存 |
| `SAMPLE-AES`、`skd://` 等 DRM 密钥 | ⬜ | 地址原样留给播放器，播放器本身也不支持 |
| 需要请求头（`header`）才给分片的片源 | ✅ | 引擎请求片源时带上；没写 User-Agent 时用播放器自己的 |
| 只认播放器、拒绝引擎下载的片源 | 🟡 | 自动退回由播放器直接取（没有缓存，拖动变慢） |

不是 m3u8 的直链（mp4、flv、mkv…）不经引擎，由播放器自己缓存。

### 离线下载

下载走和播放相同的解析链（`parse: 1` 嗅探、`jx: 1` 解析接口、站点的 `playUrl` 都一样），每次开始下载都重新解析。见使用说明第 4 节"下载（离线看）"。

| 情况 | 下载 | 导出为一个文件 |
|---|---|---|
| 点播 m3u8（含主播放列表、相对地址、token） | ✅ 主播放列表取最高码率 | ✅ `.ts` |
| `#EXT-X-KEY` AES-128 | ✅ 密钥一起下 | ✅ 解密后拼成 `.ts` |
| `#EXT-X-MAP` fMP4 | ✅ | ✅ `.mp4`（只有一个初始化段时） |
| `#EXT-X-BYTERANGE` | ✅ | ✅ |
| 分片伪装成图片（前面加了图片头） | ✅ 能离线播放 | ⬜ 需要 ffmpeg，不附带 |
| 音轨单独成流的主播放列表（`#EXT-X-MEDIA TYPE=AUDIO` 带 `URI`） | 🟡 只下视频那一档；有自带音轨的档位时优先它 | 同左 |
| `SAMPLE-AES` 等 DRM | ⬜ 下得下来也放不了 | ⬜ |
| 直链 mp4 / flv / mkv… | ✅ 断点续传 | ✅ 原样 |
| 直播（m3u8 没有 `#EXT-X-ENDLIST`，或直链不给文件大小） | ❌ 拒绝 | — |
| 需要客户端嗅探的线路（引擎没有浏览器内核） | ❌ 拒绝 | — |
| 片源的地址会过期 | ✅ 下到一半被拒时重新解析一次接着下 | — |

## 8. 拿真实订阅体检

`tools/check-configs.sh` 会像客户端一样加载订阅，逐个仓库、逐个站点走「首页 → 第一个分类 → 第一部影片的详情」（`PLAY=1` 时再解析第一集的播放地址），逐个直播源拉频道列表和节目单，把结果写到 `dist/check-report.md`：

```bash
tools/check-configs.sh                      # ../tvbox-ysc-config/output 里的配置
tools/check-configs.sh <订阅地址 | 文件 | 目录>
ONLY=cms,remote,live tools/check-configs.sh # 只查采集站、远程站和直播（引擎里不装蜘蛛运行时，不会加载任何 jar / js / py）
ONLY=py tools/check-configs.sh              # 只查 Python 站点和 Python 蜘蛛直播
```

报告把失败分成四类：**引擎兼容**（引擎要修）、**不支持**（还没做的类型）、**网络**、**站点报错**（后两类多半是站点自己失效，在安卓 TVBox 上同样打不开）。Python 站点和直播在本机没有 Python、或者缺蜘蛛要的包时算"跳过"，原因写在报告里（这是这台机器缺东西，装上就好，不是站点或引擎的问题）。它用打包后的精简 Java 运行时跑，所以运行时缺模块之类的问题也会在这里暴露。

修复桌面 JNI 兼容性时，合并前用待发布构建复测报告里的实际站点及对应蜘蛛包。用 `YINGZHOU_ANDROID_JNI=1 REQUIRE_SITES=站点键1,站点键2 tools/check-configs.sh <本地配置>` 检查目标站点。缺失、跳过、空结果或失败都会返回非零。涉及播放时加 `PLAY=1` 检查播放解析，并用 Windows 客户端确认实际播放。记录构建版本、平台、缓存标识和结果。若初始化失败，保留「共享包首次初始化」和 JNI 签名。

> 体检会在本机运行订阅里的蜘蛛代码（jar / js / py），和你在应用里点开这些站点时做的事一样。只对你信得过的订阅运行。

`ALL_SOURCES=1` 会遍历各个配置的站点，包括站点名单相同的镜像。`PROBE_MEDIA=1` 在解析后限量读取文件或 HLS 分片，并尝试验证中段字节或中段分片。报告里的 `reached` 标记实际通过的最后一步，`step` 标记首次失败的步骤。客户端首帧和拖动恢复需单独验收。

分享体检结果时使用 `REDACTED_REPORT=1`。共享报告用哈希标识订阅和站点，保留结构化 JNI 诊断。引擎和第三方蜘蛛的原始控制台输出另行保管。`NO_SNIFF=1` 关闭网页嗅探。Windows 可用 `--check-inputs-file <UTF-8 文件>` 传订阅地址，用 `--require-sites-file <UTF-8 文件>` 传站点键，每行一个值。

## 9. 调研数据

对 `tvbox-ysc-config` 聚合的公开配置做过一次统计（2026-11），用来决定先做什么：

- 518 个站点里 406 个是 jar 蜘蛛，其余主要是 JS 蜘蛛和采集站，Python 只有 4 个。
- `rules` 绝大多数是 m3u8 去广告规则。
- `lives` 以 `type: 0` 的 TXT / M3U 和 `proxy://` 为主，少量 `type: 3`。
