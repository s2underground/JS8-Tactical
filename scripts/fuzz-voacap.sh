#!/usr/bin/env bash
# Fuzz the VOACAP engine's C entry points (android/voacap/rust over vendor/hfcast-engine) with
# libFuzzer. The Rust library is built with SanitizerCoverage (stable rustc flags) and
# `--cfg fuzzing`, which lets a panic abort instead of becoming an error; the C harness runs under
# AddressSanitizer. Requests are what the app builds itself, but the engine should still never
# crash, hang or run away on any input.
#
# Usage: scripts/fuzz-voacap.sh [seconds]      (default: 300)
# Needs cargo (the toolchain in android/voacap/rust/rust-toolchain.toml) and clang with libFuzzer.
# Crashes are written to build/fuzz-voacap/ as crash-* files.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CRATE="$ROOT/android/voacap/rust"
OUT="$ROOT/build/fuzz-voacap"
SECONDS_TO_RUN="${1:-300}"
CC="${CC:-clang}"

if [ ! -f "$ROOT/vendor/hfcast-engine/Cargo.toml" ]; then
  echo "error: vendor/hfcast-engine is empty; run: git submodule update --init vendor/hfcast-engine" >&2
  exit 1
fi

mkdir -p "$OUT/corpus"
cp "$ROOT"/tools/fuzz/voacap-seeds/*.json "$OUT/corpus/"

echo "==> building the engine with coverage instrumentation"
HOST="$(rustc -vV | sed -n 's/^host: //p')"
RUSTFLAGS="--cfg fuzzing -Cdebug-assertions -Coverflow-checks -Cpasses=sancov-module \
  -Cllvm-args=-sanitizer-coverage-level=4 -Cllvm-args=-sanitizer-coverage-inline-8bit-counters \
  -Cllvm-args=-sanitizer-coverage-pc-table -Cllvm-args=-sanitizer-coverage-trace-compares" \
CARGO_TARGET_DIR="$OUT/cargo" \
  cargo build --release --locked --offline --manifest-path "$CRATE/Cargo.toml" --target "$HOST"

echo "==> building $OUT/voacap_fuzz"
"$CC" -g -O1 -fno-omit-frame-pointer -fsanitize=fuzzer,address \
  "$ROOT/tools/fuzz/voacap_fuzz.c" "$OUT/cargo/$HOST/release/libjs8voacap.a" \
  -lpthread -ldl -lm -o "$OUT/voacap_fuzz"

echo "==> fuzzing for ${SECONDS_TO_RUN}s"
cd "$OUT"
# A run is milliseconds; anything past 20 s or 2 GB is a finding, not a slow input.
./voacap_fuzz -max_total_time="$SECONDS_TO_RUN" -max_len=1024 -timeout=20 -rss_limit_mb=2048 \
  -dict="$ROOT/tools/fuzz/voacap.dict" -print_final_stats=1 corpus
