import json, zlib
import numpy as np, pandas as pd, torch
import fit, model as m
mn = pd.read_pickle("data/pairs_min.pkl"); rc = pd.read_pickle("data/pairs.pkl")
def v1rel(d): return m.v1_reliability(d.lat_tx.values, d.lon_tx.values, d.lat_rx.values, d.lon_rx.values, d.t.values.astype(np.int64), d.mhz.values, d.sfi.values, d.kp.values)[0]
y = mn.heard.values.astype(float); w = mn.w.values
cur = fit.V2(); cur.load_state_dict(torch.load("data/v2_final.pt"))
with torch.no_grad(): rel_cur = cur.rel(fit.geometry(mn)).numpy()
print("on 2019-2020 minimum (unseen): v1 auc %.3f, current refit auc %.3f" % (fit.wauc(v1rel(mn), y, w), fit.wauc(rel_cur, y, w)), flush=True)

df = pd.concat([rc, mn], ignore_index=True)
days = sorted(df.day.unique()); recent = sorted(rc.day.unique())[-7:]; hist = [d for d in days if d not in recent]
test_days = set(recent) | set(hist[3::4])
test_station = np.array([zlib.crc32(c.encode()) % 5 == 0 for c in df.call_tx.values])
is_test = df.day.isin(test_days).values | test_station
tr, te = df[~is_test], df[is_test]
g_tr, g_te = fit.geometry(tr), fit.geometry(te)
y_tr = torch.tensor(tr.heard.values, dtype=torch.float32); w_tr = torch.tensor(tr.w.values, dtype=torch.float32)
mod = fit.V2(); mod.load_state_dict(torch.load("data/v2_final.pt"))
fit.train(mod, g_tr, y_tr, w_tr, steps=700, lr=0.015, seed=3)
with torch.no_grad(): rel = mod.rel(g_te).numpy()
yt, wt = te.heard.values.astype(float), te.w.values
lo = te.sfi.values < 95
r1 = v1rel(te)
print("held-out all: v1 %.3f  v2 %.3f | low-SFI held-out: v1 %.3f v2 %.3f | high-SFI held-out: v1 %.3f v2 %.3f" % (
    fit.wauc(r1, yt, wt), fit.wauc(rel, yt, wt), fit.wauc(r1[lo], yt[lo], wt[lo]), fit.wauc(rel[lo], yt[lo], wt[lo]),
    fit.wauc(r1[~lo], yt[~lo], wt[~lo]), fit.wauc(rel[~lo], yt[~lo], wt[~lo])), flush=True)
res = fit.report("v2 combined", rel, rel, te)
for b_, v in res["bands"].items(): print(b_, {k: round(x, 3) for k, x in v.items()})
v1res = fit.report("v1", r1, r1, te)
print("v1 bands:", {b_: round(v["auc"], 3) for b_, v in v1res["bands"].items()})
print({k: round(float(v.detach()), 4) for k, v in mod.params().items()})
torch.save(mod.state_dict(), "data/v2_combined_heldout.pt")
json.dump({"v2": res, "v1": v1res}, open("data/results_round4.json", "w"), indent=1)
