#!/usr/bin/env bash
# Fuzzes the app's Kotlin code that reads text received over the air (store-and-forward
# commands, report lines, message rollup) and the diagnostics privacy scrub, with Jazzer
# (coverage-guided fuzzing for the JVM). The code under test is compiled straight from the app's
# sources, without the Android build. Harnesses: tools/fuzz/kotlin/.
#
# Usage: scripts/fuzz-kotlin.sh [seconds per target] [target ...]
#   scripts/fuzz-kotlin.sh                 # every target, 120 s each
#   scripts/fuzz-kotlin.sh 600 ReportsFuzz # one target, 10 minutes
#
# Needs a JDK (17+), curl, unzip. The Kotlin compiler and Jazzer are downloaded once into
# build/fuzz-kotlin/tools and checked against the SHA-256 values below before use.
# A finding stops that target and leaves its input in build/fuzz-kotlin/crash-<target>-*.
# Exit status is non-zero if any target found something.
set -euo pipefail
cd "$(dirname "$0")/.."

PER_TARGET="${1:-120}"
[ $# -gt 0 ] && shift
ALL_TARGETS=(StoreForwardFuzz ReportsFuzz RollupFuzz DiagnosticsPrivacyFuzz RelayProtocolFuzz)
TARGETS=("$@")
[ ${#TARGETS[@]} -eq 0 ] && TARGETS=("${ALL_TARGETS[@]}")

KOTLIN_VERSION=2.3.21
KOTLIN_SHA256=a8cfc1d62cd4d0de4d04f42575e40135bd620588c17d568a20eb9c7c259af14f
JAZZER_VERSION=0.30.0
JAZZER_SHA256=8bdeac017bcd3d9473c9772fac62111c4df830188571def1d001a1b743a62b2f
JAZZER_API_SHA256=d36a725cfedcb7f3590206866cc2836f84d12afccf7c98912f5c720e4d2208d7

OUT=build/fuzz-kotlin
TOOLS="$OUT/tools"
mkdir -p "$TOOLS"

# fetch URL FILE SHA256: download once, refuse to use anything that doesn't match.
fetch() {
  local url=$1 file=$2 sha=$3
  if [ -f "$file" ] && echo "$sha  $file" | sha256sum -c --quiet - >/dev/null 2>&1; then return; fi
  curl -fsSL --retry 3 --retry-delay 5 -o "$file.tmp" "$url"
  echo "$sha  $file.tmp" | sha256sum -c --quiet - || { echo "Checksum mismatch for $url" >&2; rm -f "$file.tmp"; exit 1; }
  mv "$file.tmp" "$file"
}

MAVEN=https://repo1.maven.org/maven2/com/code-intelligence
fetch "$MAVEN/jazzer/$JAZZER_VERSION/jazzer-$JAZZER_VERSION.jar" "$TOOLS/jazzer.jar" "$JAZZER_SHA256"
fetch "$MAVEN/jazzer-api/$JAZZER_VERSION/jazzer-api-$JAZZER_VERSION.jar" "$TOOLS/jazzer-api.jar" "$JAZZER_API_SHA256"
fetch "https://github.com/JetBrains/kotlin/releases/download/v$KOTLIN_VERSION/kotlin-compiler-$KOTLIN_VERSION.zip" \
  "$TOOLS/kotlinc.zip" "$KOTLIN_SHA256"
if [ ! -x "$TOOLS/kotlinc/bin/kotlinc" ]; then
  rm -rf "$TOOLS/kotlinc"
  unzip -q "$TOOLS/kotlinc.zip" -d "$TOOLS"
fi
STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"

# The app code under test: plain Kotlin, no Android classes.
APP=android/app/src/main/java/com/js8call/example
SOURCES=(
  "$APP/storeforward/Held.kt"
  "$APP/reports/Reports.kt"
  "$APP/reports/Geo.kt"
  "$APP/rollup/RollupEngine.kt"
  "$APP/DiagnosticsPrivacy.kt"
  "$APP/service/RelayProtocol.kt"
  "$APP/util/Js8Commands.kt"
  "$APP/util/CallsignValidator.kt"
)
echo "Compiling the fuzz targets…"
"$TOOLS/kotlinc/bin/kotlinc" -nowarn -cp "$TOOLS/jazzer-api.jar" -d "$OUT/targets.jar" \
  "${SOURCES[@]}" tools/fuzz/kotlin/*.kt

CP="$TOOLS/jazzer.jar:$TOOLS/jazzer-api.jar:$OUT/targets.jar:$STDLIB"
FAILED=()
for t in "${TARGETS[@]}"; do
  echo "== $t ($PER_TARGET s)"
  mkdir -p "$OUT/corpus/$t"
  if ! java -cp "$CP" com.code_intelligence.jazzer.Jazzer \
      --target_class="com.js8call.example.fuzz.$t" \
      --instrumentation_includes='com.js8call.**' \
      --reproducer_path="$OUT" \
      -max_total_time="$PER_TARGET" \
      -artifact_prefix="$OUT/crash-$t-" \
      "$OUT/corpus/$t"; then
    FAILED+=("$t")
  fi
done

if [ ${#FAILED[@]} -gt 0 ]; then
  echo "Findings in: ${FAILED[*]} (inputs in $OUT/crash-*)" >&2
  exit 1
fi
echo "No findings."
