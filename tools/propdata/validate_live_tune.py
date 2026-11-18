import json, numpy as np, pandas as pd, torch, fit, model as m
R = json.load(open("data/final2_rounded.json")); P = dict(R["params"])
df = pd.concat([pd.read_pickle("data/pairs.pkl"), pd.read_pickle("data/pairs_min.pkl")], ignore_index=True)
mod = fit.V2(); mod.load_state_dict(torch.load("data/v2_final2.pt")); det = mod.det
days = sorted(set(sorted(df.day.unique())[3::4] + sorted(df.day.unique())[-7:]))
PHIS = np.round(np.arange(0.80, 1.21, 0.05), 2)
def rel_phi(d, phi):
    Q = dict(P); Q["mm"] = P["mm"] * phi
    return m.v2_raw(Q, d.lat_tx.values, d.lon_tx.values, d.lat_rx.values, d.lon_rx.values, d.t.values, d.mhz.values, d.sfi.values, d.kp.values)[0]
def prob(d, rel):
    with torch.no_grad(): return det(torch.tensor(rel, dtype=torch.float32), fit.geometry(d)).numpy()
def llsum(d, p):
    y, w = d.heard.values.astype(float), d.w.values
    return float(-(w * (y * np.log(p) + (1 - y) * np.log(1 - p))).sum()), float(w.sum())
tot = {"base": [0, 0], "now": [0, 0]}
for day in days:
    d = df[df.day == day]
    hr = (d.t.values % 86400) // 3600
    # local net: 25 most active stations within 1500 km of the busiest receiver location that day
    centre = d.groupby(["lat_rx", "lon_rx"]).size().idxmax()
    loc = d[(m.distance_km(d.lat_tx.values, d.lon_tx.values, *centre) < 1500) & (m.distance_km(d.lat_rx.values, d.lon_rx.values, *centre) < 1500)]
    calls = pd.Series(np.concatenate([loc.call_tx.values, loc.call_rx.values])).value_counts().index[:25]
    net_all = loc[loc.call_tx.isin(calls) & loc.call_rx.isin(calls)]
    nhr = (net_all.t.values % 86400) // 3600
    for h in range(3, 24):
        target = d[hr == h]           # everything heard worldwide that hour from/to the area (all pairs)
        target = target[(m.distance_km(target.lat_tx.values, target.lon_tx.values, *centre) < 1500) | (m.distance_km(target.lat_rx.values, target.lon_rx.values, *centre) < 1500)]
        if len(target) < 50: continue
        past = net_all[(nhr >= h - 3) & (nhr < h)]
        ph = 1.0
        if len(past) >= 15:
            best = None
            for c in PHIS:
                s, wsum = llsum(past, prob(past, rel_phi(past, c)))
                score = s + 0.5 * (np.log(c) / 0.08) ** 2 * 5 * (wsum / len(past))
                if best is None or score < best[0]: best = (score, c)
            ph = best[1]
        for k, phi in (("base", 1.0), ("now", ph)):
            s, wsum = llsum(target, prob(target, rel_phi(target, phi)))
            tot[k][0] += s; tot[k][1] += wsum
    print(day, {k: round(v[0] / max(v[1],1e-9), 4) for k, v in tot.items()}, flush=True)
print("FINAL", {k: round(v[0] / v[1], 5) for k, v in tot.items()})
