#!/usr/bin/env bash
# Installs the Rust toolchain pinned in android/voacap/rust/rust-toolchain.toml, with the Android
# targets the VOACAP engine is built for (issue #57). rustup checks every download against the
# release's published SHA-256 sums. Used by CI; on a development machine, run it once.
set -euo pipefail
here="$(cd "$(dirname "$0")/.." && pwd)"
toml="$here/android/voacap/rust/rust-toolchain.toml"
channel="$(sed -n 's/^channel *= *"\(.*\)"/\1/p' "$toml")"
test -n "$channel"
command -v rustup >/dev/null || { echo "rustup is needed: https://rustup.rs" >&2; exit 1; }
rustup toolchain install "$channel" --profile minimal \
  --target aarch64-linux-android --target armv7-linux-androideabi
rustup run "$channel" rustc --version
