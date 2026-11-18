import json, numpy as np, pandas as pd, torch, fit, model as m
R = json.load(open("data/final2_rounded.json")); P = R["params"]
df = pd.read_pickle("data/pairs.pkl")
week = sorted(df.day.unique())[-7:]
d = df[df.day.isin(week)].copy()
mod = fit.V2(); mod.load_state_dict(torch.load("data/v2_final2.pt"))
d["rel"] = m.v2_raw(P, d.lat_tx.values, d.lon_tx.values, d.lat_rx.values, d.lon_rx.values, d.t.values, d.mhz.values, d.sfi.values, d.kp.values)[0]
with torch.no_grad(): d["p"] = mod.det(torch.tensor(d.rel.values, dtype=torch.float32), fit.geometry(d)).numpy()
d["bucket"] = np.digitize(d.d.values, [250, 800, 2000, 4000])
d["cell"] = d.band.astype(str) + "_" + d.bucket.astype(str)
logit = lambda p: np.log(p / (1 - p)); sig = lambda x: 1 / (1 + np.exp(-x))
def fit_offsets(tr, prior_sd):
    """MAP per-cell log-odds offsets + a global offset, Newton steps."""
    cells = sorted(tr.cell.unique()); ix = {c: i for i, c in enumerate(cells)}
    k = np.array([ix[c] for c in tr.cell]); z0 = logit(np.clip(tr.p.values, 1e-6, 1 - 1e-6)); y = tr.heard.values.astype(float); w = tr.w.values
    g, off = 0.0, np.zeros(len(cells))
    for _ in range(25):
        p = sig(z0 + g + off[k]); r = w * (y - p); h = w * p * (1 - p)
        g += r.sum() / (h.sum() + 1 / 1.0 ** 2)
        p = sig(z0 + g + off[k]); r = w * (y - p); h = w * p * (1 - p)
        grad = np.bincount(k, r, len(cells)) - off / prior_sd ** 2
        hess = np.bincount(k, h, len(cells)) + 1 / prior_sd ** 2
        off += grad / hess
    return g, dict(zip(cells, off))
def ll(y, w, p): return float(-(w * (y * np.log(p) + (1 - y) * np.log(1 - p))).sum() / w.sum())
res = []
for st, x in d.groupby("call_rx"):
    tr, te = x[x.day.isin(week[:4])], x[x.day.isin(week[4:])]
    if tr.heard.sum() < 30 or te.heard.sum() < 30: continue
    g, off = fit_offsets(tr, 0.5)
    z = logit(np.clip(te.p.values, 1e-6, 1 - 1e-6)) + g + np.array([off.get(c, 0.0) for c in te.cell])
    y, w = te.heard.values.astype(float), te.w.values
    res.append((st, int(tr.heard.sum()), ll(y, w, te.p.values), ll(y, w, sig(z)), fit.wauc(te.p.values, y, w), fit.wauc(sig(z), y, w)))
r = pd.DataFrame(res, columns=["station", "heard_train", "ll_base", "ll_learned", "auc_base", "auc_learned"])
print(len(r), "receivers")
print("median logloss base %.4f learned %.4f; improved for %d of %d" % (r.ll_base.median(), r.ll_learned.median(), (r.ll_learned < r.ll_base).sum(), len(r)))
print("median AUC base %.3f learned %.3f" % (r.auc_base.median(), r.auc_learned.median()))
for n in (30, 100, 300):
    s = r[r.heard_train < n] if n == 30 else r[(r.heard_train >= n // 3) & (r.heard_train < n)]
small = r[r.heard_train < 100]; big = r[r.heard_train >= 100]
print("few observations (<100 heard): improved %d/%d, median ll %.4f -> %.4f" % ((small.ll_learned < small.ll_base).sum(), len(small), small.ll_base.median(), small.ll_learned.median()))
print("many observations (>=100):    improved %d/%d, median ll %.4f -> %.4f" % ((big.ll_learned < big.ll_base).sum(), len(big), big.ll_base.median(), big.ll_learned.median()))
