"""How much does wrong/stale solar data hurt? Score v2 on held-out pairs with various SFI inputs."""
import json, numpy as np, pandas as pd, fit, model as m
R = json.load(open("data/final2_rounded.json")); P = R["params"]
df = pd.concat([pd.read_pickle("data/pairs.pkl"), pd.read_pickle("data/pairs_min.pkl")], ignore_index=True)
gfz = {}
for line in open("data/wspr-sample/gfz_kp_ap_sn_f107.txt"):
    if line.startswith("#") or not line.strip(): continue
    p = line.split(); gfz[f"{int(p[0]):04d}-{int(p[1]):02d}-{int(p[2]):02d}"] = (float(p[25]), [float(x) for x in p[7:15]])
dates = sorted(gfz); idx = {d: i for i, d in enumerate(dates)}
def sfi_shift(d, k):
    v = gfz[dates[idx[d] - k]][0]; return v if v > 0 else gfz[d][0]
days = sorted(df.day.unique())
test = df[df.day.isin(days[3::4] + days[-7:])].copy()
y, w = test.heard.values.astype(float), test.w.values
def auc_with(sfi, kp=None):
    r = m.v2_raw(P, test.lat_tx.values, test.lon_tx.values, test.lat_rx.values, test.lon_rx.values, test.t.values,
                 test.mhz.values, sfi, test.kp.values if kp is None else kp)[0]
    return fit.wauc(r, y, w)
true = test.sfi.values
out = {"true": auc_with(true)}
for k in (1, 7, 14, 27, 60):
    out[f"{k}d_old"] = auc_with(test.day.map(lambda d: sfi_shift(d, k)).values)
out["const_120"] = auc_with(np.full(len(test), 120.0))
out["const_70"] = auc_with(np.full(len(test), 70.0))
out["const_200"] = auc_with(np.full(len(test), 200.0))
out["kp_unknown(=2)"] = auc_with(true, np.full(len(test), 2.0))
for k, v in out.items(): print(f"{k:16s} {v:.4f}")
# storm subset: when Kp>=5, does knowing Kp help?
st = test.kp.values >= 5
if st.sum() > 1000:
    r1 = m.v2_raw(P, *[test[c].values[st] for c in ("lat_tx","lon_tx","lat_rx","lon_rx","t","mhz","sfi","kp")])[0]
    r2 = m.v2_raw(P, *[test[c].values[st] for c in ("lat_tx","lon_tx","lat_rx","lon_rx","t","mhz","sfi")], np.full(st.sum(), 2.0))[0]
    print("storm hours (Kp>=5):", st.sum(), "auc with Kp %.4f without %.4f" % (fit.wauc(r1, y[st], w[st]), fit.wauc(r2, y[st], w[st])))
