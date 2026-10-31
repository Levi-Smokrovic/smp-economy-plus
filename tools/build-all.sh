#!/usr/bin/env bash
# Builds all Minecraft versions and checks the jars. Output: build/release/smp-economy-plus-<mod>+<mc>.jar
# Usage: tools/build-all.sh [extra Gradle arguments, e.g. --offline]
# Gradle needs JDK 25, Loom cannot set up 26.x otherwise: JAVA_HOME_25, then JAVA_HOME, then java from the PATH.
set -euo pipefail
cd "$(dirname "$0")/.."

if [ -n "${JAVA_HOME_25:-}" ]; then
  export JAVA_HOME="$JAVA_HOME_25"
fi
java_bin="${JAVA_HOME:+$JAVA_HOME/bin/}java"
java_major=$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -n 1) || true
if [ "$java_major" != "25" ]; then
  echo "build-all: JDK 25 required, but $java_bin reports '${java_major:-no version}'. Set JAVA_HOME_25 or JAVA_HOME to a JDK 25." >&2
  exit 1
fi

VER=$(sed -n 's/^mod.version=//p' gradle.properties)
MCS=$(sed -n 's/.*versions(\(.*\)).*/\1/p' settings.gradle.kts | tr -d '"' | tr ',' ' ')
OUT="build/release"

./gradlew "Reset active project" -q "$@"        # puts src/ back on the committed version (1.21.11)
rm -rf "$OUT"
status=0
# --continue: one broken version must not block the jars of the others
./gradlew clean buildAndCollect --continue "$@" || status=$?

fail=0
for mc in $MCS; do
  jar="$OUT/smp-economy-plus-$VER+$mc.jar"
  if [ ! -f "$jar" ]; then echo "FAIL $mc: $jar missing (build failed)"; fail=1; continue; fi
  list=$(unzip -Z1 "$jar")
  ok=1
  grep -q '^META-INF/jars/sgui-' <<<"$list"                   || { echo "FAIL $mc: sgui not bundled"; ok=0; }
  grep -q '^META-INF/jars/fabric-permissions-api-' <<<"$list" || { echo "FAIL $mc: permissions API not bundled"; ok=0; }
  grep -q '^META-INF/jars/common-economy-api-' <<<"$list"     || { echo "FAIL $mc: Common Economy API not bundled"; ok=0; }
  ! grep -q '^META-INF/jars/placeholder-api-' <<<"$list"      || { echo "FAIL $mc: Placeholder API must stay optional"; ok=0; }
  ! grep -qi 'polymer' <<<"$list"                             || { echo "FAIL $mc: polymer in jar"; ok=0; }
  grep -q '^LICENSE_smpeconomy$' <<<"$list"                   || { echo "FAIL $mc: LICENSE_smpeconomy missing"; ok=0; }
  grep -q '^THIRD_PARTY_NOTICES.md$' <<<"$list"               || { echo "FAIL $mc: THIRD_PARTY_NOTICES.md missing"; ok=0; }
  grep -q '^LICENSE_GPL3$' <<<"$list"                         || { echo "FAIL $mc: LICENSE_GPL3 missing"; ok=0; }
  major=$(unzip -p "$jar" dev/smpeconomy/SmpEconomy.class | xxd -s 6 -l 2 -p)   # 0041 = Java 21, 0045 = Java 25
  case "$mc" in 26.*) want=0045;; *) want=0041;; esac
  [ "$major" = "$want" ] || { echo "FAIL $mc: class version 0x$major instead of 0x$want"; ok=0; }
  info=$(unzip -p "$jar" fabric.mod.json | python3 -c 'import json,sys;d=json.load(sys.stdin);dep=d["depends"];print(d["version"],"minecraft",dep["minecraft"],"loader",dep["fabricloader"],"java",dep["java"])')
  case "$info" in "$VER+$mc "*) ;; *) echo "FAIL $mc: fabric.mod.json version mismatch ($info)"; ok=0;; esac
  if [ $ok = 1 ]; then echo "OK   $mc  class=0x$major  $info"; else fail=1; fi
done

[ $status = 0 ] || echo "Gradle build finished with errors (exit $status)"
[ $fail = 0 ] && [ $status = 0 ]
