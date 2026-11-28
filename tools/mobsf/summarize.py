#!/usr/bin/env python3
"""Markdown summary of a MobSF scan: score, scorecard items, and the high/warning findings.

Usage: summarize.py scorecard.json report.json TAG APK

Tolerant of MobSF version differences: a missing section is skipped, never fatal.
"""
import json
import sys


def load(path):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except Exception as e:  # noqa: BLE001 - report and carry on
        print(f"_Could not read {path}: {e}_\n")
        return {}


def clean(text, limit=160):
    text = " ".join(str(text or "").split())
    return text if len(text) <= limit else text[: limit - 1] + "…"


def main():
    scorecard, report, tag, apk = load(sys.argv[1]), load(sys.argv[2]), sys.argv[3], sys.argv[4]
    print(f"### MobSF static scan of `{tag}`\n")
    print(f"APK: `{apk.split('/')[-1]}`  ")
    if report.get("version"):
        print(f"MobSF {report.get('version')}  ")
    if "security_score" in scorecard:
        print(f"**Security score: {scorecard['security_score']}/100**\n")

    for level in ("high", "warning", "hotspot", "info", "secure"):
        items = scorecard.get(level) or []
        print(f"**{level.capitalize()}: {len(items)}**\n")
        if level in ("high", "warning", "hotspot"):
            for it in items:
                print(f"- {clean(it.get('title'))} ({clean(it.get('section'), 40)})")
            if items:
                print()

    manifest = (report.get("manifest_analysis") or {}).get("manifest_findings") or []
    serious = [m for m in manifest if str(m.get("severity", "")).lower() in ("high", "warning")]
    if serious:
        print("#### Manifest findings (high/warning)\n")
        for m in serious:
            print(f"- [{m.get('severity')}] {clean(m.get('title') or m.get('rule'))} {clean(m.get('component'), 80)}")
        print()

    code = ((report.get("code_analysis") or {}).get("findings")) or {}
    rows = []
    for rule, f in code.items():
        meta = f.get("metadata") or {}
        sev = str(meta.get("severity", "")).lower()
        if sev in ("high", "warning", "error"):
            files = f.get("files") or {}
            rows.append((sev, rule, clean(meta.get("description"), 120), len(files), meta.get("masvs", "")))
    if rows:
        print("#### Code findings (high/warning)\n")
        print("| Severity | Rule | Files | MASVS | What |")
        print("|---|---|---:|---|---|")
        for sev, rule, desc, n, masvs in sorted(rows):
            print(f"| {sev} | `{rule}` | {n} | {masvs} | {desc} |")
        print()

    trackers = report.get("trackers") or {}
    if isinstance(trackers, dict) and "detected_trackers" in trackers:
        print(f"Trackers detected: {trackers.get('detected_trackers')} of {trackers.get('total_trackers')}\n")


if __name__ == "__main__":
    main()
