import json
import numpy as np, pandas as pd, torch
import fit, model as m
df = pd.concat([pd.read_pickle("data/pairs.pkl"), pd.read_pickle("data/pairs_min.pkl")], ignore_index=True)
g = fit.geometry(df)
y = torch.tensor(df.heard.values, dtype=torch.float32); w = torch.tensor(df.w.values, dtype=torch.float32)
mod = fit.V2(); mod.load_state_dict(torch.load("data/v2_combined_heldout.pt"))
fit.train(mod, g, y, w, steps=400, lr=0.008, seed=7)
with torch.no_grad(): rel = mod.rel(g).numpy(); ceiling = mod.det(torch.ones(len(df)), g).numpy()
yy, ww = df.heard.values.astype(float), df.w.values
edges = np.linspace(0, 0.8, 17); b = np.clip(np.digitize(rel, edges) - 1, 0, 15)
curve = []
for k in range(16):
    sel = b == k
    if ww[sel].sum() < 2000: continue
    curve.append([float(np.average(rel[sel], weights=ww[sel])), float((ww[sel]*yy[sel]).sum() / (ww[sel]*ceiling[sel]).sum()), float(ww[sel].sum()/ww.sum())])
params = {k: float(v.detach()) for k, v in mod.params().items()}
torch.save(mod.state_dict(), "data/v2_final2.pt")
json.dump({"params": params, "curve": curve}, open("data/final2.json", "w"), indent=1)
print(json.dumps(params)); [print("  rel %.3f open %.3f share %.3f" % tuple(c)) for c in curve]
