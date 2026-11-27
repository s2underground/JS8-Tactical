#!/usr/bin/env python3
"""
VOACAP against the Prop tab's model, on WSPR data neither was fitted to.

Usage: compare_voacap.py <wspr-sample dir (the "unseen" set)> <vbatch binary> [out.json]

- Pairs (heard / not heard, per hour and band) are rebuilt with build_pairs.py.
- Ours: model.v2_raw with the coefficients shipped in the app (Propagation.kt, object K), daily
  F10.7 and Kp, exactly as the app would have used them that day.
- VOACAP: the hfcast port (vendor/hfcast-engine) through a small batch runner, isotropic antennas,
  residential noise (145 dBW at 3 MHz), required SNR 6 dB in 1 Hz (WSPR decodes to -28 dB in
  2500 Hz), the pair's real transmitter power. Two sunspot inputs:
    "smoothed"  the month's 13-month smoothed sunspot number (or the monthly mean where the
                smoothing window runs past the data): what VOACAP is designed for;
    "daily"     SSN from that day's F10.7, the same solar input ours gets.
  VOACAP is run at 1 W and the SNR shifted by the pair's power; reliability is then recomputed
  from VOACAP's own decile spreads (checked against its REL at 1 W).
- Score: weighted AUC, ties counted as half (0.5 = coin flip, 1 = perfect ranking of heard
  above not heard). Ranking only: neither model is recalibrated to WSPR's detection rates.
"""
import json
import math
import subprocess
import sys
from collections import defaultdict

import numpy as np
import pandas as pd

import build_pairs
import model

# Propagation.kt, object K (the shipped model).
P = dict(a0=10.01, a1=0.02908, b0=5.47, b1=0.004771, wd=0.2119, wn=0.1671, c0=0.3118, p=0.4933,
         alpha=0.6103, hl=0.6566, eq=0.9812, ks=0.02864, h=318.4, mm=1.142, ka=0.008166,
         l0=0.1276, l1=7.387, l2=1.94, l3=0.598, u0=1.037, u1=1.098, w=5.685, gw1=87.56, gw2=54.37,
         es0=0.2626, esw=0.0001575, esp=6.955, esc=6.079, esk=0.0345, es2=0.2853)
BAND_INDEX = {b: i for i, b in enumerate(sorted(build_pairs.WSPR_MHZ))}
REQ = 6.0
NOISE = 145.0


def wauc(score, y, w):
    """Weighted AUC with ties counted as half."""
    df = pd.DataFrame({"s": score, "pos": w * y, "neg": w * (1 - y)}).groupby("s", sort=True).sum()
    cneg = df.neg.cumsum() - df.neg
    num = (df.pos * (cneg + df.neg / 2)).sum()
    den = df.pos.sum() * df.neg.sum()
    return float(num / den) if den > 0 else float("nan")


def monthly_ssn(gfz_path):
    """Month -> (smoothed SSN, monthly mean) from the GFZ daily sunspot numbers."""
    daily = defaultdict(list)
    for line in open(gfz_path):
        if line.startswith("#") or not line.strip():
            continue
        p = line.split()
        sn = float(p[24])
        if sn >= 0:
            daily[(int(p[0]), int(p[1]))].append(sn)
    means = {k: sum(v) / len(v) for k, v in daily.items()}
    keys = sorted(means)
    idx = {k: i for i, k in enumerate(keys)}
    out = {}
    for k in keys:
        i = idx[k]
        if i >= 6 and i + 6 < len(keys) and all(keys[j] in means for j in range(i - 6, i + 7)):
            win = [means[keys[j]] for j in range(i - 6, i + 7)]
            out[k] = ((win[0] + win[-1]) / 2 + sum(win[1:-1])) / 12.0
        else:
            out[k] = means[k]
    return out, means


def phi(x):
    return 0.5 * (1 + np.vectorize(math.erf)(x / math.sqrt(2)))


def run_voacap(binary, df, ssn_of_row):
    """VOACAP SNR/deciles for every pair; returns arrays aligned with df."""
    df = df.copy()
    df["hour"] = (df.t.values % 86400) // 3600
    df["bi"] = df.band.map(BAND_INDEX)
    df["ssn"] = ssn_of_row
    keys = ["day", "call_tx", "call_rx"]
    g = df.groupby(keys)
    lines, ids = [], {}
    for n, (k, part) in enumerate(g):
        r = part.iloc[0]
        want = sorted(set(zip(part.hour, part.bi)))
        y, m = int(r.day[:4]), int(r.day[5:7])
        lines.append("%d\t%.3f %.3f %.3f %.3f %d %d %.1f %.1f\t%s" % (
            n, r.lat_tx, r.lon_tx, r.lat_rx, r.lon_rx, y, m, r.ssn, NOISE,
            ",".join("%d:%d" % hb for hb in want)))
        ids[k] = n
    print("VOACAP paths:", len(lines), flush=True)
    out = subprocess.run([binary], input="\n".join(lines) + "\n", capture_output=True, text=True, check=True).stdout
    res = {}
    for line in out.splitlines():
        i, h, b, snr, dl, du, rel = line.split("\t")
        res[(int(i), int(h), int(b))] = (float(snr), float(dl), float(du), float(rel))
    pid = np.array([ids[k] for k in zip(df.day, df.call_tx, df.call_rx)])
    got = [res.get((p, h, b)) for p, h, b in zip(pid, df.hour, df.bi)]
    miss = sum(x is None for x in got)
    print("VOACAP cells missing:", miss, flush=True)
    arr = np.array([x if x is not None else (np.nan,) * 4 for x in got])
    return arr


def voacap_rel(arr, power_dbm):
    snr = arr[:, 0] + (power_dbm - 30.0)
    dl, du = np.maximum(arr[:, 1], 0.1), np.maximum(arr[:, 2], 0.1)
    z = np.where(snr >= REQ, (snr - REQ) / (dl / 1.2816), (snr - REQ) / (du / 1.2816))
    return phi(z), snr


def report(name, s, df):
    y, w = df.heard.values.astype(float), df.w.values
    out = {"all": wauc(s, y, w), "bands": {}, "distance": {}, "sfi": {}}
    for b in sorted(df.band.unique()):
        m = df.band.values == b
        out["bands"][str(b)] = wauc(s[m], y[m], w[m])
    for lo, hi in [(0, 500), (500, 1500), (1500, 3500), (3500, 8000), (8000, 25000)]:
        m = (df.d.values >= lo) & (df.d.values < hi)
        out["distance"]["%d-%d" % (lo, hi)] = wauc(s[m], y[m], w[m])
    for lo, hi in [(0, 100), (100, 150), (150, 400)]:
        m = (df.sfi.values >= lo) & (df.sfi.values < hi)
        out["sfi"]["%d-%d" % (lo, hi)] = wauc(s[m], y[m], w[m]) if m.any() else None
    print(name, json.dumps({k: (round(v, 3) if isinstance(v, float) else {a: (None if b is None else round(b, 3)) for a, b in v.items()}) for k, v in out.items()}), flush=True)
    return out


_T = [-28.0, -26.0, -24.0, -22.0, -20.0, -18.0, -16.0, -14.0, -10.0, -5.0, 0.0]
_R = [1.0, 0.953, 0.878, 0.79, 0.702, 0.615, 0.532, 0.454, 0.316, 0.18, 0.09]


def station_multiplier(margin_db):
    """StationModel.multiplier (Station.kt) for a margin over the reference station."""
    t = -28.0 - margin_db
    if t <= -28.0:
        r = 1 + 0.044 * (-28.0 - t)
    else:
        r = float(np.interp(t, _T, _R))
    return min(3.0, max(0.05, r))


def missed(s, df, closed):
    """Share of real receptions (weighted) on paths the model called closed."""
    m = df.heard.values.astype(bool)
    return float((df.w.values[m] * (s[m] < closed)).sum() / df.w.values[m].sum())


def main(src, binary, out_path="voacap_comparison.json", *_flags):
    gfz = build_pairs.load_gfz(f"{src}/gfz_kp_ap_sn_f107.txt")
    rng = np.random.default_rng(42)
    import glob, os
    days = sorted(os.path.basename(p)[3:13] for p in glob.glob(f"{src}/tx_*.tsv"))
    parts = [build_pairs.day_pairs(d, src, gfz, rng) for d in days if d in gfz and gfz[d][1] > 0]
    df = pd.concat(parts, ignore_index=True)
    print("pairs", len(df), "heard", int(df.heard.sum()), "days", df.day.nunique(), flush=True)

    raw, _, _, _ = model.v2_raw(P, df.lat_tx.values, df.lon_tx.values, df.lat_rx.values, df.lon_rx.values,
                                df.t.values, df.mhz.values, df.sfi.values, df.kp.values)
    ours = model.score(raw)
    results = {"pairs": int(len(df)), "heard": int(df.heard.sum()), "days": int(df.day.nunique())}
    results["ours"] = report("ours", ours, df)
    results["ours_missed_closed"] = missed(ours, df, 0.10)
    # Ours with the transmitter's power applied the way the app applies a station's power
    # (StationModel: margin over the 27 dBm reference -> share of paths kept), not clipped at 1 so
    # the ranking keeps its order.
    ours_power = ours * np.array([station_multiplier(p - 27.0) for p in df.power.values.astype(float)])
    results["ours_power"] = report("ours with tx power", ours_power, df)
    if len(sys.argv) > 4 and sys.argv[4] == "--ours-only":
        json.dump(results, open(out_path, "w"), indent=1)
        return

    smoothed, _ = monthly_ssn(f"{src}/gfz_kp_ap_sn_f107.txt")
    ssn_sm = np.array([smoothed[(int(d[:4]), int(d[5:7]))] for d in df.day])
    ssn_daily = np.maximum(0, 1.14 * df.sfi.values - 73.21)
    for label, ssn in [("smoothed", ssn_sm), ("daily", ssn_daily)]:
        arr = run_voacap(binary, df, ssn)
        ok = ~np.isnan(arr[:, 0])
        sub = df[ok]
        rel_p, snr_p = voacap_rel(arr[ok], sub.power.values.astype(float))
        rel_1w = arr[ok, 3]
        # Sanity: the recomputed reliability at 1 W matches VOACAP's own REL.
        chk, _ = voacap_rel(arr[ok], np.full(ok.sum(), 30.0))
        print(label, "REL check (mean abs diff at 1 W):", round(float(np.nanmean(np.abs(chk - rel_1w))), 4), flush=True)
        results[f"voacap_{label}_rel"] = report(f"voacap {label} REL (real power)", rel_p, sub)
        results[f"voacap_{label}_rel_1w"] = report(f"voacap {label} REL (1 W all)", rel_1w, sub)
        results[f"voacap_{label}_snr"] = report(f"voacap {label} SNR (real power)", snr_p, sub)
        results[f"voacap_{label}_missed_closed"] = missed(rel_p, sub, 0.10)
        if label == "smoothed":
            results["ours_same_pairs"] = report("ours (same pairs)", ours[ok], sub)
            # Do they fail in different places? A plain average of ranks, as a hint for a blend.
            r1 = pd.Series(ours[ok]).rank(pct=True).values
            r2 = pd.Series(rel_p).rank(pct=True).values
            results["rank_average"] = report("rank average (ours + VOACAP)", (r1 + r2) / 2, sub)
    json.dump(results, open(out_path, "w"), indent=1)
    print("written", out_path)


if __name__ == "__main__":
    main(*sys.argv[1:])
