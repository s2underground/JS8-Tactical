#!/usr/bin/env python3
"""Prints unit-test line coverage from a JaCoCo XML report as a Markdown table.

Usage: scripts/coverage-summary.py android/app/build/reports/coverage/test/debug/report.xml

Generated code (view binding, Room, BuildConfig) is left out. The screens (ui.*) are listed on
their own line: they're tested on devices, not by unit tests, so the "app logic" figure is the
one that should go up over time.
"""
import sys
import xml.etree.ElementTree as ET

GENERATED = (".databinding", "_Impl", "BuildConfig", "/R$", "/R.")


def line_counter(el):
    for c in el.findall("counter"):
        if c.get("type") == "LINE":
            return int(c.get("covered")), int(c.get("missed"))
    return 0, 0


def main(path):
    root = ET.parse(path).getroot()
    packages = {}
    for pkg in root.findall("package"):
        name = pkg.get("name").replace("/", ".")
        if name.endswith(".databinding"):
            continue
        cov = mis = 0
        for cls in pkg.findall("class"):
            if any(g in cls.get("name") for g in GENERATED):
                continue
            c, m = line_counter(cls)
            cov += c
            mis += m
        if cov + mis:
            packages[name] = (cov, mis)

    def pct(c, m):
        return 100.0 * c / (c + m) if c + m else 0.0

    def total(names):
        c = sum(packages[n][0] for n in names)
        m = sum(packages[n][1] for n in names)
        return c, m

    ui = [n for n in packages if ".ui" in n]
    logic = [n for n in packages if n not in ui]
    lc, lm = total(logic)
    uc, um = total(ui)
    ac, am = total(packages)

    print("### Unit-test line coverage")
    print()
    print("| Scope | Lines covered | Coverage |")
    print("|---|---:|---:|")
    print(f"| App logic (everything outside ui.*) | {lc} / {lc + lm} | **{pct(lc, lm):.1f}%** |")
    print(f"| Screens (ui.*, tested on devices) | {uc} / {uc + um} | {pct(uc, um):.1f}% |")
    print(f"| All | {ac} / {ac + am} | {pct(ac, am):.1f}% |")
    print()
    print("<details><summary>By package</summary>")
    print()
    print("| Package | Lines covered | Coverage |")
    print("|---|---:|---:|")
    for n in sorted(packages):
        c, m = packages[n]
        print(f"| {n} | {c} / {c + m} | {pct(c, m):.1f}% |")
    print()
    print("</details>")


if __name__ == "__main__":
    main(sys.argv[1])
