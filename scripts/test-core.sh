#!/usr/bin/env bash
# Build and test the vendored js8core tree.
#
# Usage: scripts/test-core.sh [build-dir]
#        (default: core/build)
#
# Runs the native CTest suite (including the Android compatibility tests) and
# the exhaustive JSC codeword parity check against the pre-cleanroom reference.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
BUILD="${1:-$CORE/build}"

if [ ! -f "$CORE/CMakeLists.txt" ]; then
  echo "error: $CORE does not look like a js8core source tree" >&2
  echo "       run scripts/sync-core.sh first" >&2
  exit 1
fi

echo "==> configuring $BUILD"
cmake -S "$CORE" -B "$BUILD" \
  -DCMAKE_BUILD_TYPE=Release \
  -DBUILD_TESTING=ON

echo "==> building core and tests"
cmake --build "$BUILD" --parallel 2 --target \
  js8core \
  js8core-crc-test \
  js8core-varicode-test \
  js8core-policy-test \
  verify_jsc_roundtrip \
  js8core-vox-test \
  js8core-fractional-rx-test \
  js8core-clock-math-test \
  js8core-decoder-roundtrip-test \
  js8core-engine-smoke-test \
  js8core-rig-test \
  js8core-callback-reentrancy-test \
  js8core-tx-threaded-lifecycle-test \
  js8core-session-lifecycle-test \
  js8core-rig-generation-test \
  js8core-host-callback-hardening-test \
  js8core-compat-header-test \
  js8core-android-compat-test

echo "==> running CTest"
ctest --test-dir "$BUILD" --output-on-failure --timeout 300

echo "==> running JSC codeword parity check"
CXX="${CXX:-c++}"
PARITY="$BUILD/jsc-codeword-parity"
"$CXX" -std=c++20 -O2 -pthread \
  -I "$CORE/include" \
  -I "$CORE/src/protocol" \
  "$ROOT/tools/jsc_codeword_parity_test.cpp" \
  "$BUILD/libjs8core.a" \
  -o "$PARITY"
"$PARITY"

echo "==> running the Sending bar's frame text test"
OWN_FRAMES="$BUILD/own-frame-text-test"
"$CXX" -std=c++20 -O2 -pthread \
  -I "$CORE/include" \
  -I "$ROOT/adapters/android/jni" \
  "$ROOT/tools/own_frame_text_test.cpp" \
  "$BUILD/libjs8core.a" \
  -o "$OWN_FRAMES"
"$OWN_FRAMES"

echo "==> running the JNI text conversion test"
JNI_TEXT="$BUILD/jni-text-test"
"$CXX" -std=c++20 -O2 -pthread \
  -I "$CORE/include" \
  -I "$ROOT/adapters/android/jni" \
  "$ROOT/tools/jni_text_test.cpp" \
  "$BUILD/libjs8core.a" \
  -o "$JNI_TEXT"
"$JNI_TEXT"

echo "==> running the RTL-SDR receiver (SSB DSP) test"
SSB_RX="$BUILD/ssb-rx-test"
"$CXX" -std=c++17 -O2 -Wall \
  -I "$ROOT/android/sdr/src/main/cpp" \
  "$ROOT/android/sdr/src/test/cpp/ssb_rx_test.cpp" \
  -o "$SSB_RX"
"$SSB_RX"

echo "==> core tests passed"
