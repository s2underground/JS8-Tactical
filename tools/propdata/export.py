"""
Round the fitted coefficients to 4 significant figures, build the display
calibration (raw score -> share of spots relative to the best paths), and
print golden test cases for the Kotlin port. Run after final_fit.py.
"""
import json
import math

import numpy as np

import model as m

f = json.load(open("data/final2.json"))


def sig(x, n=4):
    return 0.0 if x == 0 else round(x, -int(math.floor(math.log10(abs(x)))) + (n - 1))


P = {k: sig(v) for k, v in f["params"].items()}
rel = [0.0] + [round(c[0], 3) for c in f["curve"]]
opn = np.maximum.accumulate([0.0] + [c[1] for c in f["curve"]])
val = [round(min(1.0, o / 0.65), 3) for o in opn]
k = val.index(1.0) if 1.0 in val else len(val) - 1
rel, val = rel[:k + 1], val[:k + 1]
m.SCORE_REL, m.SCORE_VAL = rel, val
json.dump({"params": P, "score_rel": rel, "score_val": val}, open("data/final2_rounded.json", "w"), indent=1)
print(json.dumps(P))
cases = [(36.0, -86.0, 40.0, -75.0, 1763490000, 7.078, 121.0, 0.3), (36.0, -86.0, 51.5, 0.0, 1763490000, 14.078, 121.0, 0.3),
         (36.0, -86.0, -33.9, 151.2, 1763438400, 14.078, 150.0, 4.0), (36.0, -86.0, 36.5, -85.0, 1763438400, 3.578, 90.0, 2.0),
         (65.0, -20.0, 35.0, 139.0, 1750000000, 21.078, 180.0, 5.5), (48.0, 2.0, 41.0, 12.5, 1750064400, 28.078, 130.0, 1.0),
         (36.0, -86.0, 37.0, -84.0, 1763484000, 7.078, 121.0, 0.3), (36.0, -86.0, 51.5, 0.0, 1763484000, 28.078, 121.0, 0.3),
         (-34.0, 18.5, 52.0, 5.0, 1700000000, 21.078, 160.0, 2.0), (10.0, -67.0, 40.0, -74.0, 1690000000, 24.922, 75.0, 3.7)]
a = np.array(cases).T
r, d, muf, luf = m.v2_raw(P, a[0], a[1], a[2], a[3], a[4].astype(np.int64), a[5], a[6], a[7])
for c, rr, ss, mu, lu in zip(cases, r, m.score(r), muf, luf):
    print("Golden(%s, %s, %s, %s, %dL, %s, %s, %s, raw = %.6f, score = %.6f, muf = %.4f, luf = %.4f)," % (
        *[repr(float(x)) for x in c[:4]], c[4] * 1000, c[5], c[6], c[7], rr, ss, mu, lu))
