# Third-party notices

Yingzhou (映舟) is licensed under AGPL-3.0 (see `LICENSE`). It includes code adapted from the projects below.

## 映匣 / tvbox-silicon — GPL-3.0

- The desktop Wex sign-in protocol and Java stream proxy setting follow `Runtime/JavaHost` at revision `1f29cadb6576e7dc7f102da7479c25887609f948`, particularly `OwnDriveSignIn`, `CloudDriveBridge`, and `PluginSession`. The nested DEX delegation policy follows `dalvik.system.DexClassLoader`. Yingzhou implements these behaviors through its own runtime and client interfaces.
- The Windows bundle's `engine/yingxia-java` contains the JavaHost from [tvbox-silicon](https://github.com/jo32/tvbox-silicon) at `187c878fd3a8c1d379c8ef578f5825cba7bf0050`. It runs `tvbox.runtime.ProcessHost` in a separate process and preserves the upstream Maven dependency versions and pinned Sardine Android AAR. `tools/build-yingxia-java.ps1` applies a Windows fallback for writing DEX conversion output when ZIP FS is read-only. `LICENSE.txt`, `THIRD_PARTY_NOTICES.md`, and `upstream-source.tar` in the bundle include the corresponding source and notices.
- `engine/yingxia-scripts` contains ScriptHost, PythonHost and their bundled assets from the same revision. It includes the corresponding sources, upstream notices, Node.js and CPython licenses, and the Python wheels' license metadata. See `tools/build-yingxia-scripts.ps1` for the pinned Windows runtimes.

## TVBoxOS — AGPL-3.0

- `engine/jar-host/java/com/github/catvod/**`: the host API jar spiders link against (Spider, SpiderApi, OkHttp, Proxy, ...).
- `engine/runtime-js/src/main/java/**` and the JS spider bootstrap: TVBox's JS spider host (crypto, html parsing rules, module rules).
- `engine/core/.../play/PlayResolver.kt`, `PlayRules`, `M3u8Cleaner.kt`: the parse chain, `rules` handling and m3u8 ad rules follow TVBoxOS and Box (takagen99, AGPL-3.0), which credit FongMi/TV.
- `engine/core/.../live/Catchup.kt`: live catch-up (回看) addresses follow `LivePlayActivity` of Box (takagen99, AGPL-3.0: `?playseek=` on `/PLTV/` addresses) and TVBoxOS (the `catchup` object's `type` / `source` / `replace` / `regex`, `${(b)…}` / `${(e)…}` placeholders, `appendCatchupUrl`), and `TxtSubscribe` for the M3U attributes. The catch-up modes and placeholders of M3U playlists (`shift`, `flussonic`, `xc`, `{utc}`, `{duration:N}`, `{offset:N}` …) follow the behaviour documented for Kodi's IPTV Simple PVR client (kodi-pvr/pvr.iptvsimple, GPL-2.0-or-later); no code of it is included.

- `engine/runtime-py/src/main/resources/tvbox/py/base/spider.py`: the Python spider base class keeps the method names,
  helpers and behaviour of TVBox's "pyramid" base class (`pyramid/src/python/base/spider.py` in TVBoxOS and Box,
  takagen99), including its order-agnostic `regStr` and the one-instance-per-class rule; `worker.py` follows
  `pyramid/src/python/app.py` (dependencies via `getDependence`, `init` receiving the loaded dependencies,
  `localProxy` results as `[code, mime, content, headers, base64]`) and `PythonSpider.java` / `PythonLoader.java`.
  `base/htmlParser.py` is TVBoxOS's file unchanged (its header credits its author).
- The Python spider runtime's design (one long-lived Python process spoken to over stdin / stdout, the spider's own
  `print` kept off the protocol stream, trying `searchContent` with and without `pg`, the packages Python spiders
  commonly import) learned from MisaTV (GPL-3.0, `assets/python_runner/runner.py`, `lib/services/py_spider/`); no
  code of it is included.

- `engine/core/.../net/ConfigNetwork.kt`: how `hosts`, `doh` and `ads` behave follows TVBoxOS (`OkGoHelper.CustomDns`,
  its DoH list and `ips` bootstrap, the fallback to the system's DNS, `AdBlocker`) and Box (the longer DoH list); the
  `*` in `hosts` names and the top-level `headers` list follow FongMi/TV (GPL-3.0), described from its behaviour.

- `engine/core/.../play/Subtitles.kt`: how a play answer's `subt` / `subs` are read (`#name.ext` after the address,
  the MIME types of `format`, lyrics left out) follows `PlayFragment` / `PlayActivity` of TVBoxOS and Box (takagen99,
  AGPL-3.0).
- `engine/core/.../danmaku/`: how the config's `danmaku` API is asked (`{name}` / `{episode}`, a form to `…/danmaku`,
  dandanplay-compatible `/api/v2/search/episodes` and `/api/v2/comment/{id}`, the `{url}` answers), the episode
  matching and the Bilibili XML reading (raw deflate, lenient `<d>` parsing) follow TVBoxOS (`DanmakuApi`, `Danmu`,
  `player/danmu/Parser`). The JSON forms (`comments[].p` with the colour third) follow MisaTV's `danmaku_xml.dart`
  (GPL-3.0), described from its behaviour; no code of it is included.
- `engine/core/.../drive/AListBackend.kt`, `DriveFiles.kt`: reading AList through `/api/fs/list` and playing from its
  `/d/` route, the drive's start folder, sorting by name or date and the video extensions follow Box (takagen99,
  AGPL-3.0: `AlistDriveViewModel`, `AbstractDriveViewModel`, `StorageDriveType`).

## MisaTV — GPL-3.0

- `engine/core/.../drive/WebDavBackend.kt` (`Propfind`): the `PROPFIND` request body and the parsing of its answer
  (elements matched by local name whatever the prefix, the `propstat` with status 200, hrefs as paths or whole urls
  made relative to the DAV folder) are ported from MisaTV's `lib/services/webdav/dav.dart` and `dav_service.dart`.
  Like MisaTV, files of a WebDAV with an account are relayed by a local server that adds the credentials.

## Win-box — MIT

`engine/runtime-jar/src/main/java/android/**`, `androidx/**` and `dalvik/**` started from Win-box's Android stubs
(`resources/jvm/stubs-src`). The dex2jar repairs in `engine/runtime-jar/src/main/kotlin/tvbox/engine/jar/ClassRepairs.kt`
follow Win-box's `classFix.ts` and `DexJarRepair.java`.

```
MIT License

Copyright (c) 2026 yagami-hue

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Libraries

- dex2jar (`de.femtopedia.dex2jar`) — Apache-2.0
- [unidbg](https://github.com/zhkl0228/unidbg/tree/v0.9.8) (`unidbg-android`, `unidbg-api`, `unidbg-unicorn2` 0.9.8) — Apache-2.0 for unidbg's code. Used by the desktop engine for ARM Android JNI. The upstream artifacts also carry Android runtime libraries and the native [Unicorn](https://github.com/unicorn-engine/unicorn) emulator, which retain their upstream licences.
- The unidbg runtime dependencies include `com.github.zhkl0228:unicorn:1.0.14`, `keystone:0.9.7`, `demumble:1.0.4` (their published POMs declare GPL v2) and `capstone:3.1.8` (BSD). Their native components retain their own notices. See [the upstream dependency declarations](https://repo.maven.apache.org/maven2/com/github/zhkl0228/).
- android-json (`com.vaadin.external.google:android-json`, AOSP's org.json) — Apache-2.0
- AndroidX Collection — Apache-2.0
- ASM — BSD-3-Clause
- OkHttp (with `okhttp-dnsoverhttps`), Gson, Jsoup, Ktor, kotlinx — Apache-2.0 / MIT
- quickjs-kt — Apache-2.0; QuickJS — MIT
- path_provider (Flutter, client) — BSD-3-Clause
- file_selector (`file_selector` and its platform packages, flutter.dev; picking a subtitle or danmaku file in the client) — BSD-3-Clause
- qr (`qr`, Copyright 2014 the Dart QR project authors; the QR code of the "手机输入" page in the client) — BSD-3-Clause
- JmDNS (`org.jmdns:jmdns`, mDNS announcement of a shared engine) — Apache-2.0
- AndroidX Core (`androidx.core:core`, the Android app's `FileProvider` for sharing exported downloads) — Apache-2.0
- bonsoir (`bonsoir`, `bonsoir_platform_interface`, `bonsoir_android`, `bonsoir_darwin`, `bonsoir_linux`, `bonsoir_windows`; finding engines on the LAN in the client), Copyright (c) 2019 Hugo DELAUNAY "Skyost" — MIT

## Downloaded only when the user asks

Not part of the repository or the packages. The 「Python 蜘蛛」 settings can download the official Windows embeddable
CPython (Python Software Foundation License Version 2) from python.org, and install pip (MIT) and the packages Python
spiders commonly use with it: requests (Apache-2.0), lxml (BSD-3-Clause), pycryptodome (BSD-2-Clause / public
domain), beautifulsoup4 (MIT), pyquery (BSD-3-Clause), ujson (BSD-3-Clause), jsonpath (MIT), cachetools (MIT), and
what pip pulls in for them (urllib3, certifi, idna, charset-normalizer, soupsieve, cssselect, ...), each under its own
licence, into the engine's data folder.

## Docker image

The engine image (`engine/Dockerfile`) is built from Eclipse Temurin 21 (GPL-2.0 with Classpath Exception; the
trimmed runtime is made from it with jlink) and Debian bookworm, whose packages keep their own licences: Chromium
(BSD-3-Clause and others), tini (MIT), curl (curl licence), Liberation fonts (OFL-1.1). The repository contains only
the build files, not these components.

## Tools

- The Windows installer is built with [Inno Setup](https://jrsoftware.org/isinfo.php), Copyright (C) 1997-2026 Jordan Russell,
  portions Copyright (C) 2000-2026 Martijn Laan; its Simplified Chinese messages are by Zhenghan Yang (Kira).
