"""Actual WSPR results near the user's region, 17-20Z, last week, vs v1/v2 band charts."""
import numpy as np, pandas as pd, torch, fit, model as m
df = pd.read_pickle("data/pairs.pkl")
days = sorted(df.day.unique())[-7:]
hr = (df.t % 86400) // 3600
lat0, lon0 = 36.0, -84.0
near_tx = m.distance_km(df.lat_tx.values, df.lon_tx.values, lat0, lon0) < 700
sel = df.day.isin(days) & near_tx & hr.between(17, 19)
d = df[sel].copy()
edges = [25, 250, 800, 2000, 4000, 20000]
d["bucket"] = pd.cut(d.d, edges, labels=["0-250", "250-800", "800-2k", "2k-4k", "4k+"])
mod = fit.V2(); mod.load_state_dict(torch.load("data/v2_final2.pt"))
g = fit.geometry(d)
with torch.no_grad(): d["rel2"] = mod.rel(g).numpy(); ceil = mod.det(torch.ones(len(d)), g).numpy()
d["rel1"] = m.v1_reliability(d.lat_tx.values, d.lon_tx.values, d.lat_rx.values, d.lon_rx.values, d.t.values.astype(np.int64), d.mhz.values, d.sfi.values, d.kp.values)[0]
d["ceil"] = ceil
rows = []
for (band, bucket), x in d.groupby(["band", "bucket"], observed=True):
    wsum = x.w.sum()
    if wsum < 30: continue
    heard = (x.w * x.heard).sum()
    rows.append((band, bucket, int(len(x)), round(heard / wsum, 3), round(heard / (x.w * x.ceil).sum(), 2),
                 round(np.average(x.rel1, weights=x.w), 2), round(np.average(x.rel2, weights=x.w), 2)))
out = pd.DataFrame(rows, columns=["band", "dist", "pairs", "heard_frac", "open_rate", "v1_rel", "v2_rel"])
print(out.to_string(index=False))
out.to_csv("data/compare_local.csv", index=False)
