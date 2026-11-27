#!/usr/bin/env python3
"""
CycloneDX 1.6 SBOM (JSON) for a JS8-Tactical APK: what went into it, at which version.

  scripts/sbom.py --apk <file.apk> --version <versionName> --gradle-deps <deps.txt> --out <bom.json>

- Java/Kotlin libraries: the resolved runtime classpath, read from the output of
  `./gradlew :app:dependencies --configuration <variant>RuntimeClasspath` (what Gradle actually
  picked, after conflict resolution; constraint-only entries are left out).
- Native code built from source: every git submodule at its commit (JS8Core, llama.cpp, the
  VOACAP engine, libusb, rtl-sdr, airspyhf), and Hamlib at the commit the build workflow pins.
- The APK itself is the subject, with its SHA-256.

Standard library only, so it runs anywhere the build does.
"""
import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

# "+--- group:name:1.0 -> 1.2 (*)", "\--- group:name -> 2.0", "|    +--- project :llm"
DEP = re.compile(r"^[|\\+ \-]*?([A-Za-z0-9_.\-]+):([A-Za-z0-9_.\-]+)(?::([^\s]+))?(?: -> ([^\s]+))?(?: \((\*|c|n)\))?\s*$")


def gradle_components(text):
    seen = {}
    for line in text.splitlines():
        if "---" not in line or "project :" in line:
            continue
        m = DEP.match(line.split("---", 1)[1].strip() if "---" in line else line)
        if not m:
            continue
        group, name, declared, resolved, mark = m.groups()
        if mark in ("c", "n"):  # a version constraint, or not resolved: not a dependency
            continue
        version = resolved or declared
        if not version:
            continue
        key = f"{group}:{name}"
        seen[key] = version
    out = []
    for key in sorted(seen):
        group, name = key.split(":", 1)
        version = seen[key]
        out.append({
            "type": "library",
            "bom-ref": f"pkg:maven/{group}/{name}@{version}",
            "group": group,
            "name": name,
            "version": version,
            "purl": f"pkg:maven/{group}/{name}@{version}",
        })
    return out


def submodule_components():
    urls = {}
    gm = ROOT / ".gitmodules"
    if gm.exists():
        current = None
        for line in gm.read_text().splitlines():
            line = line.strip()
            m = re.match(r'\[submodule "(.+)"\]', line)
            if m:
                current = m.group(1)
            elif current and line.startswith("path"):
                urls.setdefault(current, {})["path"] = line.split("=", 1)[1].strip()
            elif current and line.startswith("url"):
                urls.setdefault(current, {})["url"] = line.split("=", 1)[1].strip()
    status = subprocess.run(["git", "submodule", "status"], cwd=ROOT, capture_output=True, text=True, check=True).stdout
    commits = {}
    for line in status.splitlines():
        parts = line[1:].split()
        if len(parts) >= 2:
            commits[parts[1]] = parts[0]
    out = []
    for info in urls.values():
        path, url = info.get("path"), info.get("url", "")
        sha = commits.get(path)
        if not path or not sha:
            continue
        m = re.match(r"https://github\.com/([^/]+)/([^/]+?)(?:\.git)?/?$", url)
        name = m.group(2) if m else Path(path).name
        purl = f"pkg:github/{m.group(1)}/{m.group(2)}@{sha}" if m else None
        comp = {
            "type": "library",
            "bom-ref": purl or f"git:{url}@{sha}",
            "name": name,
            "version": sha,
            "description": f"Built from source (git submodule {path})",
            "externalReferences": [{"type": "vcs", "url": url}],
        }
        if purl:
            comp["purl"] = purl
        out.append(comp)
    return sorted(out, key=lambda c: c["name"])


def hamlib_component():
    wf = (ROOT / ".github/workflows/android-build.yml").read_text()
    m = re.search(r"HAMLIB_VERSION:\s*([0-9a-f]{40})", wf)
    if not m:
        return []
    sha = m.group(1)
    return [{
        "type": "library",
        "bom-ref": f"pkg:github/Hamlib/Hamlib@{sha}",
        "name": "Hamlib",
        "version": sha,
        "description": "Built from source at this commit by the build workflow, with android/hamlib/patches",
        "purl": f"pkg:github/Hamlib/Hamlib@{sha}",
        "externalReferences": [{"type": "vcs", "url": "https://github.com/Hamlib/Hamlib"}],
    }]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", required=True)
    ap.add_argument("--version", required=True)
    ap.add_argument("--gradle-deps", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()

    apk = Path(a.apk)
    sha256 = hashlib.sha256(apk.read_bytes()).hexdigest()
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
    gradle = gradle_components(Path(a.gradle_deps).read_text())
    if len(gradle) < 10:
        sys.exit(f"only {len(gradle)} libraries read from {a.gradle_deps}: is it the dependencies task output?")
    components = gradle + submodule_components() + hamlib_component()
    app_ref = f"pkg:github/s2underground/JS8-Tactical@{commit}"
    bom = {
        "bomFormat": "CycloneDX",
        "specVersion": "1.6",
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "version": 1,
        "metadata": {
            "timestamp": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "tools": {"components": [{"type": "application", "name": "scripts/sbom.py (JS8-Tactical)"}]},
            "component": {
                "type": "application",
                "bom-ref": app_ref,
                "name": "JS8-Tactical",
                "version": a.version,
                "description": f"{apk.name} built from commit {commit}",
                "licenses": [{"license": {"id": "GPL-3.0-only"}}],
                "hashes": [{"alg": "SHA-256", "content": sha256}],
                "purl": app_ref,
                "externalReferences": [{"type": "vcs", "url": "https://github.com/s2underground/JS8-Tactical"}],
            },
        },
        "components": components,
        "dependencies": [{"ref": app_ref, "dependsOn": [c["bom-ref"] for c in components]}],
    }
    Path(a.out).write_text(json.dumps(bom, indent=1) + "\n")
    print(f"{a.out}: {len(gradle)} libraries, {len(components) - len(gradle)} native sources, APK {sha256[:12]}")


if __name__ == "__main__":
    main()
