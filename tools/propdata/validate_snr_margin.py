import glob, numpy as np, pandas as pd
cols = ["h", "band", "tx", "rx", "txlat", "txlon", "rxlat", "rxlon", "snr", "n", "power"]
sp = pd.concat([pd.read_csv(f, sep="\t", header=None, names=cols) for f in glob.glob("data/wspr-sample/spots_*.tsv") + glob.glob("data/min/wspr-sample/spots_*.tsv")])
sp = sp[sp.band.isin([1, 3, 5, 7, 10, 14, 18, 21, 24, 28])]
print("spots", len(sp), "power dBm median", sp.power.median(), "quartiles", sp.power.quantile([.25, .75]).tolist())
hist = np.histogram(sp.snr, bins=np.arange(-34, 21, 1))
for lo, c in zip(hist[1], hist[0]):
    if lo <= 0: print(f"{lo:4.0f} {c:7d} {'#' * int(c / hist[0].max() * 60)}")
# survival of best-SNR among heard pairs (normalised to the -28 dB WSPR floor)
x = np.arange(-30, 1)
S = np.array([(sp.snr >= v).mean() for v in x])
base = (sp.snr >= -28).mean()
print("retained vs WSPR floor (-28):", {int(v): round(s / base, 3) for v, s in zip(x, S) if v in (-28, -26, -24, -22, -20, -18, -16, -14, -10)})
# by power: does +10 dB power shift the SNR distribution by ~10 dB? (median SNR by power bin)
sp["pbin"] = pd.cut(sp.power, [0, 23, 27, 30, 33, 37, 40, 60])
print(sp.groupby("pbin", observed=True).snr.agg(["median", "count"]))
# slope of log-density just above the floor, for extrapolating below it
sel = sp.snr.between(-27, -15)
cnt = sp.snr[sel].value_counts().sort_index()
slope = np.polyfit(cnt.index.values, np.log(cnt.values), 1)[0]
print("log-density slope per dB between -27 and -15:", round(slope, 4))
