#!/usr/bin/env python3
"""Sort OSV-Scanner findings into "in the shipped APK" and "build tools only".

Usage: triage.py osv.json sbom.cdx.json

The SBOM (published with every APK) lists the Maven components inside the app; anything else
OSV finds in android/gradle/verification-metadata.xml is part of the build tooling (Android Gradle
Plugin, lint, Kotlin compiler, ...), which never ships. Prints a Markdown report; exits 1 if a
shipped component has a known vulnerability.
"""
import json
import sys


def main():
    osv = json.load(open(sys.argv[1], encoding="utf-8"))
    sbom = json.load(open(sys.argv[2], encoding="utf-8"))
    shipped = set()
    for c in sbom.get("components", []):
        purl = c.get("purl", "")
        if purl.startswith("pkg:maven/"):
            group, rest = purl[len("pkg:maven/"):].split("/", 1)
            name = rest.split("@", 1)[0]
            shipped.add(f"{group}:{name}")

    rows = []
    for result in osv.get("results", []):
        source = result.get("source", {}).get("path", "")
        for p in result.get("packages", []):
            pkg = p.get("package", {})
            name, version = pkg.get("name", "?"), pkg.get("version", "?")
            ids = sorted({v.get("id") for v in p.get("vulnerabilities", [])})
            sev = sorted({str((v.get("database_specific") or {}).get("severity", "?")) for v in p.get("vulnerabilities", [])})
            rows.append((name in shipped, name, version, ids, sev, source))

    ship = [r for r in rows if r[0]]
    build = [r for r in rows if not r[0]]
    n = lambda rs: sum(len(r[3]) for r in rs)
    print("### OSV-Scanner triage\n")
    print(f"Shipped in the APK: **{len(ship)} package(s), {n(ship)} advisories**  ")
    print(f"Build tooling only: {len(build)} package(s), {n(build)} advisories\n")
    for title, rs in (("In the shipped APK", ship), ("Build tooling only", build)):
        if not rs:
            continue
        print(f"#### {title}\n")
        print("| Package | Version | Severity | Advisories | Found in |")
        print("|---|---|---|---|---|")
        for _, name, version, ids, sev, source in sorted(rs, key=lambda r: r[1]):
            print(f"| `{name}` | {version} | {', '.join(sev)} | {' '.join(ids)} | {source.split('/JS8-Tactical/')[-1]} |")
        print()
    return 1 if ship else 0


if __name__ == "__main__":
    sys.exit(main())
