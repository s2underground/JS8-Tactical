#!/usr/bin/env bash
# Fuzz the JS8 frame decoders (the code that handles over-the-air input) with libFuzzer,
# AddressSanitizer and UndefinedBehaviorSanitizer.
#
# Usage: scripts/fuzz-core.sh [seconds]      (default: 300)
#
# Needs clang with libFuzzer (e.g. apt install clang).
# Crashes are written to build/fuzz/ as crash-* files; rerun one with
#   build/fuzz/js8_frame_fuzz build/fuzz/crash-<id>
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CORE="$ROOT/core"
OUT="$ROOT/build/fuzz"
SECONDS_TO_RUN="${1:-300}"
CXX="${CXX:-clang++}"

if [ ! -f "$CORE/include/js8core/protocol/varicode.hpp" ]; then
  echo "error: core/ is empty; run: git submodule update --init core" >&2
  exit 1
fi

mkdir -p "$OUT/corpus"
echo "==> building $OUT/js8_frame_fuzz"
"$CXX" -std=c++20 -g -O1 -fno-omit-frame-pointer \
  -fsanitize=fuzzer,address,undefined -fno-sanitize-recover=undefined \
  -I"$CORE/include" -I"$CORE/src" -I"$CORE" \
  "$ROOT/tools/fuzz/js8_frame_fuzz.cpp" \
  "$CORE"/src/protocol/varicode_*.cpp \
  "$CORE"/src/protocol/jsc.cpp "$CORE"/src/protocol/jsc_map.cpp \
  "$CORE"/src/protocol/constants.cpp "$CORE"/src/protocol/submode.cpp \
  -o "$OUT/js8_frame_fuzz"

echo "==> fuzzing for ${SECONDS_TO_RUN}s"
cd "$OUT"
./js8_frame_fuzz -max_total_time="$SECONDS_TO_RUN" -max_len=256 -print_final_stats=1 corpus
