#!/usr/bin/env bash
# Builds a self-contained desktop package: the Flutter client plus the engine and a trimmed Java runtime.
#
#   tools/package-desktop.sh     # Windows (Git Bash): dist/yingzhou-windows-x64.zip
#                                #                     dist/yingzhou-windows-x64-setup.exe (when Inno Setup is installed)
#                                # macOS:             dist/yingzhou-macos-<arm64|x86_64>.dmg
#                                # Linux:             dist/yingzhou-linux-<x64|arm64>.tar.gz and .deb
#
# Needs JDK 21 (JAVA_HOME) and Flutter; on Windows Visual Studio with the C++ desktop workload, on macOS Xcode,
# on Linux (Debian/Ubuntu) what the Linux job in .github/workflows/desktop.yml installs with apt.
# The Windows installer (tools/installer/windows.iss) also needs Inno Setup 6.3 or newer; without it only the zip is built.
# The Java runtime is jlinked from the JDK running the build, so a macOS package only runs on that CPU type
# (build on Apple Silicon for arm64, on an Intel Mac or `macos-13` CI runner for x86_64).
# macOS signing: ad-hoc by default; set SIGN_IDENTITY="Developer ID Application: ..." to sign for notarization.
# Linux: the same, a runtime for the build machine's CPU; the app links the build machine's libmpv and glibc, so a
# package built on Ubuntu 24.04 runs on 24.04 and later (and other distributions as new).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) os=windows ;;
  Darwin) os=macos ;;
  *) os=linux ;;
esac

# The whole of Java SE rather than what `jdeps` finds in the engine: spider jars are other people's code and use
# what they like. On top of it: jdk.zipfs (dex conversion opens jars through it, by name, so jdeps does not see it —
# without it every jar spider fails with "cant find zipfs support"), charsets (GBK sites), EC and PKCS11 crypto (TLS),
# sun.misc.Unsafe, the JDK http server and DNS lookups.
modules=java.se,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.httpserver,jdk.naming.dns,jdk.net,jdk.xml.dom,jdk.management

echo "== engine"
(cd "$root/engine" && ./gradlew :server:installDist --console=plain -q)

echo "== java runtime"
jre="$root/engine/server/build/jre"
rm -rf "$jre"
"$JAVA_HOME/bin/jlink" --add-modules "$modules" --strip-debug --no-header-files --no-man-pages \
  --compress zip-6 --output "$jre"

echo "== self-test (demo sites on the packaged runtime)"
# The package is only good if the trimmed runtime runs every kind of site: CMS, JS spider, jar spider, sniffing, live.
cp="$root/engine/server/build/install/tvbox-engine/lib/*"
[ "$os" = windows ] && cp="$(cygpath -w "$root/engine/server/build/install/tvbox-engine/lib")\\*"
"$jre/bin/java" -Xss1m -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote -Dstdout.encoding=UTF-8 \
  -cp "$cp" tvbox.engine.server.MainKt --demo --check --play --strict \
  || { echo "the packaged runtime failed the self-test; not packaging" >&2; exit 1; }

mkdir -p "$root/dist"

if [ "$os" = windows ]; then
  echo "== Yingxia JavaHost"
  yingxia="${YINGXIA_JAVA_BUNDLE:-$root/engine/server/build/yingxia-java}"
  if [ ! -f "$yingxia/host.jar" ]; then
    pwsh -NoProfile -File "$(cygpath -w "$root/tools/build-yingxia-java.ps1")" -OutputDirectory "$(cygpath -w "$yingxia")"
  fi
  yingxia_scripts="${YINGXIA_SCRIPT_BUNDLE:-$root/engine/server/build/yingxia-scripts}"
  if [ ! -f "$yingxia_scripts/revision.txt" ]; then
    pwsh -NoProfile -File "$(cygpath -w "$root/tools/build-yingxia-scripts.ps1")" -OutputDirectory "$(cygpath -w "$yingxia_scripts")"
  fi
  "$jre/bin/java" -Dstdout.encoding=UTF-8 -cp "$cp" tvbox.engine.server.MainKt --demo --check --only jar,js,py --play --strict \
    --yingxia-host "$(cygpath -w "$yingxia")" --yingxia-script-hosts "$(cygpath -w "$yingxia_scripts")"

  echo "== client"
  (cd "$root/client" && flutter build windows --release)

  out="$root/client/build/windows/x64/runner/Release"
  rm -rf "$out/engine"
  mkdir -p "$out/engine"
  cp -r "$root/engine/server/build/install/tvbox-engine/lib" "$out/engine/lib"
  cp -r "$jre" "$out/engine/jre"
  cp -r "$yingxia" "$out/engine/yingxia-java"
  cp -r "$yingxia_scripts" "$out/engine/yingxia-scripts"
  cp "$root/LICENSE" "$out/LICENSE.txt"
  cp "$root/THIRD-PARTY-NOTICES.md" "$out/"

  echo "== zip"
  zip="$root/dist/yingzhou-windows-x64.zip"
  rm -f "$zip"
  powershell.exe -NoProfile -Command "Compress-Archive -Path '$(cygpath -w "$out")\\*' -DestinationPath '$(cygpath -w "$zip")'"
  echo "done: $zip ($(du -h "$zip" | cut -f1)), folder: $out ($(du -sh "$out" | cut -f1))"

  echo "== installer"
  # Inno Setup's compiler: $ISCC, or on PATH (scoop install inno-setup), or where its own installer puts it.
  iscc="${ISCC:-$(command -v iscc || true)}"
  [ -n "$iscc" ] && iscc="$(cygpath -u "$iscc")"
  for dir in "/c/Program Files (x86)/Inno Setup 6" "/c/Program Files/Inno Setup 7" "/c/Program Files (x86)/Inno Setup 7"; do
    [ -z "$iscc" ] && [ -f "$dir/ISCC.exe" ] && iscc="$dir/ISCC.exe"
  done
  if [ -z "$iscc" ]; then
    echo "skipped: Inno Setup (ISCC.exe) not found; the zip above is complete without it"
    exit 0
  fi
  setup="$root/dist/yingzhou-windows-x64-setup.exe"
  log="$root/dist/installer.log"
  rm -f "$setup"
  "$iscc" "$(cygpath -w "$root/tools/installer/windows.iss")" >"$log" 2>&1 \
    || { tail -n 30 "$log" >&2; echo "the installer did not build; full output in $log" >&2; exit 1; }
  echo "done: $setup ($(du -h "$setup" | cut -f1))"
  exit 0
fi

if [ "$os" = linux ]; then
  case "$(uname -m)" in
    x86_64) arch=x64 debarch=amd64 ;;
    aarch64|arm64) arch=arm64 debarch=arm64 ;;
    *) echo "no Flutter build for $(uname -m)" >&2; exit 1 ;;
  esac
  id=io.github.sa1koro.yingzhou
  version="$(sed -n 's/^version: *\([0-9][0-9.]*\).*/\1/p' "$root/client/pubspec.yaml")"

  echo "== client"
  (cd "$root/client" && flutter build linux --release)
  out="$root/client/build/linux/$arch/release/bundle"
  # The client looks for the engine next to its executable (see lib/engine/local_engine_io.dart).
  rm -rf "$out/engine"
  mkdir -p "$out/engine"
  cp -r "$root/engine/server/build/install/tvbox-engine/lib" "$out/engine/lib"
  cp -r "$jre" "$out/engine/jre"
  cp "$root/LICENSE" "$out/LICENSE.txt"
  cp "$root/THIRD-PARTY-NOTICES.md" "$out/"
  # The bundle's share/ (icons, .desktop) comes from client/linux/CMakeLists.txt.
  cp "$root/tools/installer/linux-desktop-entry.sh" "$out/install-desktop-entry.sh"
  chmod 755 "$out/install-desktop-entry.sh"

  echo "== libraries"
  # Everything the app links has to be on this machine; a library missing here would be missing for users too.
  if ldd "$out/yingzhou" | grep 'not found'; then echo "the app links libraries that are not installed" >&2; exit 1; fi

  stage="$(mktemp -d)"
  trap 'rm -rf "$stage"' EXIT

  echo "== tarball"
  cp -a "$out" "$stage/yingzhou"
  tarball="$root/dist/yingzhou-linux-$arch.tar.gz"
  rm -f "$tarball"
  tar -C "$stage" --owner=0 --group=0 --numeric-owner -czf "$tarball" yingzhou
  echo "done: $tarball ($(du -h "$tarball" | cut -f1))"

  echo "== deb"
  if ! command -v dpkg-deb >/dev/null || ! command -v dpkg-shlibdeps >/dev/null; then
    echo "skipped: the .deb needs dpkg-deb and dpkg-shlibdeps (apt install dpkg-dev); the tarball above is complete without it"
    exit 0
  fi
  # /opt/yingzhou holds the bundle as it is (the client finds its engine next to the real executable),
  # /usr/bin/yingzhou links to it, the menu entry and icons go where desktops look. dpkg's triggers refresh the
  # icon cache and the menu, so the package needs no scripts of its own.
  pkg="$stage/deb"
  mkdir -p "$pkg/DEBIAN" "$pkg/opt" "$pkg/usr/bin" "$pkg/usr/share/doc/yingzhou"
  cp -a "$out" "$pkg/opt/yingzhou"
  rm "$pkg/opt/yingzhou/install-desktop-entry.sh"
  ln -s /opt/yingzhou/yingzhou "$pkg/usr/bin/yingzhou"
  cp -r "$out/share/applications" "$out/share/icons" "$pkg/usr/share/"
  {
    echo "映舟 (Yingzhou) $version — https://github.com/Sa1koro/yingzhou"
    echo
    echo "Copyright (C) 2026 Yingzhou contributors. Licensed under the GNU Affero General Public License v3.0;"
    echo "the full text is /opt/yingzhou/LICENSE.txt, and the licences of the code it includes are in"
    echo "/opt/yingzhou/THIRD-PARTY-NOTICES.md."
  } > "$pkg/usr/share/doc/yingzhou/copyright"

  # Shared-library dependencies as dpkg-shlibdeps works them out from every ELF file in the bundle, the Java
  # runtime's included (java.desktop wants X11 and ALSA libraries when a spider draws). It reads debian/control
  # from the folder it runs in.
  mkdir -p "$stage/shlibs/debian"
  printf 'Source: yingzhou\n\nPackage: yingzhou\nArchitecture: any\n' > "$stage/shlibs/debian/control"
  elves=()
  while IFS= read -r -d '' f; do
    [ "$(head -c 4 "$f" | tr -d '\0')" = $'\x7fELF' ] && elves+=("$f")
  done < <(find "$pkg/opt/yingzhou" -type f \( -name '*.so' -o -name '*.so.*' -o -perm -u+x \) -print0)
  depends="$(cd "$stage/shlibs" && dpkg-shlibdeps -O --ignore-missing-info \
    -l"$pkg/opt/yingzhou/lib" -l"$pkg/opt/yingzhou/engine/jre/lib" -l"$pkg/opt/yingzhou/engine/jre/lib/server" \
    "${elves[@]}" 2>"$stage/shlibdeps.log" | sed -n 's/^shlibs:Depends=//p')" || true
  if [ -z "$depends" ]; then
    cat "$stage/shlibdeps.log" >&2
    echo "dpkg-shlibdeps found no dependencies" >&2
    exit 1
  fi

  cat > "$pkg/DEBIAN/control" <<EOF
Package: yingzhou
Version: $version
Architecture: $debarch
Maintainer: Yingzhou contributors <https://github.com/Sa1koro/yingzhou>
Installed-Size: $(du -sk --apparent-size "$pkg/opt" "$pkg/usr" | awk '{s += $1} END {print s}')
Depends: $depends
Recommends: fonts-noto-cjk
Suggests: google-chrome-stable | microsoft-edge-stable | chromium | chromium-browser, avahi-daemon
Section: video
Priority: optional
Homepage: https://github.com/Sa1koro/yingzhou
Description: TVBox-compatible player for films, series and live TV
 映舟 (Yingzhou) plays films, series and live TV from TVBox-style
 subscriptions. It carries its own engine and Java runtime, and comes with
 no subscriptions or content of its own.
 .
 Web page sniffing uses Chrome, Edge or Chromium when one is installed;
 finding engines on the local network uses avahi-daemon.
EOF
  deb="$root/dist/yingzhou-linux-$arch.deb"
  rm -f "$deb"
  dpkg-deb --build --root-owner-group -Zxz "$pkg" "$deb" >/dev/null
  dpkg-deb --info "$deb" | sed -n '/Package:/,$p'
  echo "done: $deb ($(du -h "$deb" | cut -f1))"
  exit 0
fi

echo "== client"
(cd "$root/client" && flutter build macos --release)
app="$root/client/build/macos/Build/Products/Release/Yingzhou.app"
# The client looks for the engine in Contents/Resources/engine (see lib/engine/local_engine_io.dart).
res="$app/Contents/Resources/engine"
rm -rf "$res"
mkdir -p "$res"
cp -R "$root/engine/server/build/install/tvbox-engine/lib" "$res/lib"
cp -R "$jre" "$res/jre"

echo "== sign"
identity="${SIGN_IDENTITY:--}"
entitlements="$root/client/macos/Runner/Release.entitlements"
# Every Mach-O in the runtime, then the app (adding files invalidated Flutter's signature).
find "$res/jre" -type f \( -name '*.dylib' -o -perm -u+x \) -print0 |
  xargs -0 -I{} codesign --force --timestamp=none --options runtime --entitlements "$entitlements" --sign "$identity" "{}"
codesign --force --deep --timestamp=none --options runtime --entitlements "$entitlements" --sign "$identity" "$app"
codesign --verify --deep --strict "$app"

echo "== dmg"
arch="$(uname -m)"
stage="$(mktemp -d)"
trap 'rm -rf "$stage"' EXIT
cp -R "$app" "$stage/"
ln -s /Applications "$stage/Applications"
dmg="$root/dist/yingzhou-macos-$arch.dmg"
rm -f "$dmg"
hdiutil create -volname "Yingzhou" -srcfolder "$stage" -ov -format UDZO "$dmg" >/dev/null
echo "done: $dmg ($(du -h "$dmg" | cut -f1))"
if [ "$identity" = "-" ]; then
  echo "note: ad-hoc signed; a downloaded copy needs right-click > Open the first time (or: xattr -dr com.apple.quarantine \"/Applications/Yingzhou.app\")"
fi
