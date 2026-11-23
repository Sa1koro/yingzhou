#!/usr/bin/env bash
# Checks real subscriptions against the engine: every site (home -> category -> detail) and every live source,
# with a report that separates what is the engine's to fix from sites that are simply dead.
#
#   tools/check-configs.sh                                   # the configs in ../tvbox-ysc-config/output
#   tools/check-configs.sh <address | file | folder>...       # your own subscriptions
#   ONLY=cms,live tools/check-configs.sh ...                 # kinds to check: cms, remote, js, jar, py, live
#   PLAY=1 tools/check-configs.sh ...                        # also resolve the first episode's play address
#   MAX_SITES=10 tools/check-configs.sh ...                  # at most this many sites per config
#
# The report goes to dist/check-report.md (and .json). This runs the subscriptions' own spider code (jar / js)
# on this machine, exactly as the app does when you browse those sites.
#
# It uses the packaged Java runtime when there is one (client/build/.../engine/jre or engine/server/build/jre),
# so a module missing from the trimmed runtime shows up here, and JAVA_HOME otherwise.
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
lib="$root/engine/server/build/install/tvbox-engine/lib"
if [ ! -d "$lib" ] || [ -n "$(find "$root/engine" -name '*.kt' -newer "$lib" -not -path '*/build/*' -print -quit)" ]; then
  echo "== engine"
  (cd "$root/engine" && ./gradlew :server:installDist --console=plain -q)
  touch "$lib"
fi

java=""
for candidate in "$root/engine/server/build/jre/bin/java" "$root/client/build/windows/x64/runner/Release/engine/jre/bin/java" "${JAVA_HOME:-}/bin/java"; do
  if [ -x "$candidate" ] || [ -x "$candidate.exe" ]; then java="$candidate"; break; fi
done
[ -n "$java" ] || { echo "no Java runtime: run tools/package-desktop.sh once or set JAVA_HOME" >&2; exit 2; }

[ $# -gt 0 ] || set -- "$root/../tvbox-ysc-config/output"
report="${REPORT:-$root/dist/check-report.md}"
cp="$lib/*"
case "$(uname -s)" in
  MINGW*|MSYS*|CYGWIN*) cp="$(cygpath -w "$lib")\\*"; report="$(cygpath -w "$report")" ;;
esac

args=(--check "$@" --report "$report")
[ -n "${ONLY:-}" ] && args+=(--only "$ONLY")
[ -n "${PLAY:-}" ] && args+=(--play)
[ -n "${PROBE_MEDIA:-}" ] && args+=(--probe-media)
[ -n "${ALL_SOURCES:-}" ] && args+=(--all-sources)
[ -n "${REDACTED_REPORT:-}" ] && args+=(--redacted-report)
[ -n "${NO_SNIFF:-}" ] && args+=(--no-sniff)
[ -n "${MAX_SITES:-}" ] && args+=(--max-sites "$MAX_SITES")
[ -n "${MAX_CONFIGS:-}" ] && args+=(--max-configs "$MAX_CONFIGS")
[ -n "${STRICT:-}" ] && args+=(--strict)
[ -n "${REQUIRE_SITES:-}" ] && args+=(--require-sites "$REQUIRE_SITES")

echo "== check (java: $java)"
exec "$java" -Xss1m -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote \
  -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "$cp" tvbox.engine.server.MainKt "${args[@]}"
