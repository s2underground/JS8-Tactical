"""
Rebuild heard / not-heard pairs from the WSPR sample.

For every hour and band: each sampled transmitter that was on the air (spotted
by anyone) and each sampled receiver that was listening (reported anything)
form a pair. The pair is "heard" if that receiver spotted that transmitter in
that hour. Negatives are subsampled (weight = 1/rate) to keep things small.
"""
import glob
import os
import sys

import numpy as np
import pandas as pd

from model import distance_km

WSPR_MHZ = {1: 1.8366, 3: 3.5686, 5: 5.2872, 7: 7.0386, 10: 10.1387, 14: 14.0956,
            18: 18.1046, 21: 21.0946, 24: 24.9246, 28: 28.1246}
NEG_RATE = 0.08


def load_gfz(path):
    rows = []
    for line in open(path):
        if line.startswith("#") or not line.strip():
            continue
        p = line.split()
        y, m, d = int(p[0]), int(p[1]), int(p[2])
        kp = [float(x) for x in p[7:15]]
        f107 = float(p[25])  # observed F10.7
        rows.append((f"{y:04d}-{m:02d}-{d:02d}", kp, f107))
    return {d: (kp, f) for d, kp, f in rows}


def day_pairs(day, src, gfz, rng):
    names = ["h", "band", "call", "lat", "lon"]
    tx = pd.read_csv(f"{src}/tx_{day}.tsv", sep="\t", header=None, names=names + ["power", "n"])
    rx = pd.read_csv(f"{src}/rx_{day}.tsv", sep="\t", header=None, names=names + ["n"])
    sp = pd.read_csv(f"{src}/spots_{day}.tsv", sep="\t", header=None,
                     names=["h", "band", "tx", "rx", "txlat", "txlon", "rxlat", "rxlon", "snr", "n", "power"])
    tx = tx[tx.band.isin(WSPR_MHZ)]
    rx = rx[rx.band.isin(WSPR_MHZ)]
    pairs = tx.merge(rx, on=["h", "band"], suffixes=("_tx", "_rx"))
    pairs = pairs[pairs.call_tx != pairs.call_rx]
    heard = set(zip(sp.h, sp.band, sp.tx, sp.rx))
    key = list(zip(pairs.h, pairs.band, pairs.call_tx, pairs.call_rx))
    pairs["heard"] = np.fromiter((k in heard for k in key), dtype=bool, count=len(key))
    keep = pairs.heard | (rng.random(len(pairs)) < NEG_RATE)
    pairs = pairs[keep].copy()
    pairs["w"] = np.where(pairs.heard, 1.0, 1.0 / NEG_RATE)
    pairs["d"] = distance_km(pairs.lat_tx.values, pairs.lon_tx.values, pairs.lat_rx.values, pairs.lon_rx.values)
    pairs = pairs[pairs.d > 25]
    kp, f107 = gfz[day]
    hour_utc = (pairs.h.values % 86400) // 3600
    pairs["kp"] = np.array(kp)[np.minimum(7, hour_utc // 3)]
    pairs["sfi"] = f107
    pairs["mhz"] = pairs.band.map(WSPR_MHZ)
    pairs["t"] = pairs.h + 1800  # middle of the hour
    pairs["day"] = day
    out = pairs[["day", "t", "band", "mhz", "call_tx", "call_rx", "lat_tx", "lon_tx", "lat_rx", "lon_rx",
                 "d", "power", "sfi", "kp", "heard", "w"]]
    return out


def main(src, dst):
    gfz = load_gfz(f"{src}/gfz_kp_ap_sn_f107.txt")
    rng = np.random.default_rng(42)
    days = sorted(os.path.basename(p)[3:13] for p in glob.glob(f"{src}/tx_*.tsv"))
    parts = []
    for day in days:
        if day not in gfz or gfz[day][1] <= 0:
            print("no solar data for", day)
            continue
        p = day_pairs(day, src, gfz, rng)
        print(day, len(p), "pairs,", int(p.heard.sum()), "heard, sfi", gfz[day][1], flush=True)
        parts.append(p)
    allp = pd.concat(parts, ignore_index=True)
    allp.to_parquet(dst) if dst.endswith(".parquet") else allp.to_pickle(dst)
    print("total", len(allp), "heard", int(allp.heard.sum()))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
