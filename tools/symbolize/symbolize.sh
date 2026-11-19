#!/usr/bin/env bash
# Symbolize native crash offsets in libmaplibre.so using MapLibre's published
# release assets. Usage: symbolize.sh <maplibre-tag> <hex offsets...>
set -u
TAG="$1"; shift
mkdir -p out work
echo "Assets of $TAG:" > out/report.txt
curl -sSL "https://api.github.com/repos/maplibre/maplibre-native/releases/tags/$TAG" -o work/release.json
python3 - <<'PY' >> out/report.txt
import json; r=json.load(open("work/release.json"))
for a in r.get("assets", []): print(a["name"], a["size"], a["browser_download_url"])
PY
# Download anything that may hold unstripped libraries.
python3 - <<'PY' > work/urls.txt
import json; r=json.load(open("work/release.json"))
for a in r.get("assets", []):
    n=a["name"].lower()
    if any(k in n for k in ("symbol","debug","unstripped","so.zip","native")) or n.endswith(".aar"):
        print(a["browser_download_url"])
PY
cd work
while read -r u; do echo "download $u" >> ../out/report.txt; curl -sSL -O "$u"; done < urls.txt
for f in *; do case "$f" in *.zip|*.aar) unzip -o -q "$f" -d "x_$f" ;; *.tar.gz|*.tgz) mkdir -p "x_$f"; tar -xzf "$f" -C "x_$f" ;; esac; done
cd ..
NDK=$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)
ADDR2LINE="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-addr2line"
READELF="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
echo >> out/report.txt
find work -name 'libmaplibre.so' -path '*arm64*' | while read -r so; do
  {
    echo "== $so"
    "$READELF" -n "$so" | grep -i "build id" || true
    "$READELF" -S "$so" | grep -c debug_info | sed 's/^/debug_info sections: /'
    "$READELF" --symbols "$so" | wc -l | sed 's/^/symbols: /'
    for off in "$@"; do echo "-- 0x$off"; "$ADDR2LINE" -C -f -i -e "$so" "0x$off"; done
  } >> out/report.txt 2>&1
done
cat out/report.txt
