"""
Score v1 against the WSPR pairs, then fit v2 (season, magnetic latitude,
F2 lag, storm and absorption terms) and score it on held-out days/stations.
"""
import json
import sys
import zlib

import numpy as np
import pandas as pd
import torch

import model as m

torch.set_num_threads(2)
BANDS = [1, 3, 5, 7, 10, 14, 18, 21, 24, 28]
POLE_LAT, POLE_LON = 80.8, -72.7  # geomagnetic north pole (dipole), ~2025


def maglat(lat, lon):
    p, pl = np.radians(lat), np.radians(POLE_LAT)
    return np.degrees(np.arcsin(np.clip(np.sin(p) * np.sin(pl) + np.cos(p) * np.cos(pl) * np.cos(np.radians(lon - POLE_LON)), -1, 1)))


def geometry(df):
    """Everything that does not depend on the fitted parameters."""
    t = df.t.values.astype(np.int64)
    slat, slon, doy = m.subsolar(t)
    slat2, slon2, _ = m.subsolar(t - 7200)  # sun two hours earlier (F2 lags the sun)
    d = df.d.values
    hops = np.maximum(1, np.ceil(d / m.MAX_HOP_KM))
    H = 7
    g = {k: np.zeros((len(df), H), np.float32) for k in ("lat", "cz", "cz2", "mlat", "season", "active")}
    for i in range(H):
        la, lo = m.intermediate(df.lat_tx.values, df.lon_tx.values, df.lat_rx.values, df.lon_rx.values, (i + 0.5) / hops)
        g["lat"][:, i] = la
        g["cz"][:, i] = m.cos_zenith(la, lo, slat, slon)
        g["cz2"][:, i] = m.cos_zenith(la, lo, slat2, slon2)
        g["mlat"][:, i] = maglat(la, lo)
        g["season"][:, i] = np.cos(2 * np.pi * (doy - 172) / 365.0) * np.sign(la)  # +1 local summer
        g["active"][:, i] = i < hops
    g["d"] = d.astype(np.float32)
    g["hops"] = hops.astype(np.float32)
    g["mhz"] = df.mhz.values.astype(np.float32)
    g["ssn"] = np.maximum(0, 1.14 * df.sfi.values - 73.21).astype(np.float32)
    g["kp"] = df.kp.values.astype(np.float32)
    g["power"] = df.power.values.astype(np.float32)
    g["bandix"] = np.searchsorted(BANDS, df.band.values).astype(np.int64)
    return {k: torch.from_numpy(v) for k, v in g.items()}


# initial values: v1 behaviour, new terms switched off
INIT = dict(a0=4.2, a1=0.042, b0=2.3, b1=0.021, wd=0.0, wn=0.0, c0=0.1, p=0.5, alpha=0.0,
            hl=0.15, ks=0.04, eq=0.0, h=300.0, mm=1.0, ka=0.0037, l0=0.75, l1=3.6, l2=0.25, l3=0.3,
            u0=1.05, u1=0.2, w=0.25, gw1=80.0, gw2=50.0,
            es0=0.15, esw=0.3, esp=2.0, esc=0.3, esk=0.03, es2=0.3)
POSITIVE = {"a0", "a1", "b0", "b1", "c0", "p", "h", "mm", "l0", "l1", "u0", "u1", "w", "gw1", "gw2",
            "es0", "esw", "esp", "esc", "es2"}
USE_ES = True


def rel_v2(P, g):
    cz = (1 - P["alpha"]) * g["cz"] + P["alpha"] * g["cz2"]
    lit = (torch.relu(cz + P["c0"]) / (1 + P["c0"]) + 1e-6) ** P["p"]
    ssn = g["ssn"][:, None]
    s = g["season"]
    day = (P["a0"] + P["a1"] * ssn) * (1 - P["wd"] * s)
    night = (P["b0"] + P["b1"] * ssn) * (1 + P["wn"] * s)
    aml = g["mlat"].abs()
    f = night + (day - night) * lit
    f = f * (1 - P["hl"] * torch.sigmoid((aml - 60) / 4))
    f = f * (1 + P["eq"] * torch.exp(-((aml - 15) / 10) ** 2) * lit)
    f = f * (1 - P["ks"] * torch.relu(g["kp"][:, None] - 3) * (1 + aml / 45))
    f = torch.clamp(f, min=1.0)
    hop_km = g["d"] / g["hops"]
    theta = hop_km / (2 * m.R)
    h = P["h"]
    ap = torch.sqrt(m.R ** 2 + (m.R + h) ** 2 - 2 * m.R * (m.R + h) * torch.cos(theta))
    sin_i = torch.clamp(m.R * torch.sin(theta) / ap, 0, 0.999)
    sec = torch.clamp(1 / torch.sqrt(1 - sin_i ** 2), max=m.MAX_SECANT)
    act = g["active"] > 0
    muf = torch.where(act, f * sec[:, None], torch.full_like(f, 1e9)).min(dim=1).values * P["mm"]
    a = torch.relu(g["cz"]) ** 0.75 * (1 + P["ka"] * ssn)
    absorb = torch.where(act, a, torch.zeros_like(a)).max(dim=1).values
    luf = P["l0"] * (1 + P["l1"] * absorb) * (1 + P["l2"] * (sec - 1)) * g["hops"] ** P["l3"]
    mhz = g["mhz"]
    ground = torch.where(mhz < 10, torch.relu(1 - g["d"] / torch.where(mhz < 4, P["gw1"], P["gw2"])), torch.zeros_like(mhz))
    upper = torch.clamp((muf * P["u0"] - mhz) / (P["u1"] * muf), 0, 1)
    lower = torch.clamp((mhz - luf) / (P["w"] * luf), 0, 1)
    rel = torch.maximum(ground, torch.minimum(upper, lower))
    if USE_ES and "es0" in P:
        rel = torch.maximum(rel, es_rel(P, g))
    return rel


def es_rel(P, g):
    """Sporadic-E: summer, daytime, mid-latitude, one hop (400-2300 km) or two (to ~4500 km)."""
    s = g["season"][:, 0]
    cz = g["cz"][:, 0]
    aml = g["mlat"][:, 0].abs()
    d = g["d"]
    summer = P["esw"] + ((1 + s) / 2) ** P["esp"]
    day = torch.relu(cz + P["esc"]) / (1 + P["esc"])
    midlat = torch.exp(-((aml - 40) / 18) ** 2)
    one = torch.sigmoid((d - 400) / 80) * torch.sigmoid((2300 - d) / 200)
    two = P["es2"] * torch.sigmoid((d - 2300) / 200) * torch.sigmoid((4500 - d) / 300)
    freq = torch.exp(-P["esk"] * (g["mhz"] - 14))
    return torch.clamp(P["es0"] * summer * day * midlat * (one + two) * freq, 0, 1)


class Detect(torch.nn.Module):
    """Chance a WSPR receiver logs an open path: depends on band, power, distance."""

    def __init__(self):
        super().__init__()
        self.cb = torch.nn.Parameter(torch.full((len(BANDS),), -1.0))
        self.cp = torch.nn.Parameter(torch.tensor(0.05))
        self.cd = torch.nn.Parameter(torch.tensor(-0.3))
        self.eps = torch.nn.Parameter(torch.tensor(-4.0))

    def forward(self, rel, g):
        pmax = torch.sigmoid(self.cb[g["bandix"]] + self.cp * (g["power"] - 30) + self.cd * torch.log(g["d"] / 1000))
        eps = torch.sigmoid(self.eps)
        return torch.clamp(pmax * (eps + (1 - eps) * rel), 1e-6, 1 - 1e-6)


class V2(torch.nn.Module):
    def __init__(self, init=INIT, train_shape=True):
        super().__init__()
        self.raw = torch.nn.ParameterDict()
        for k, v in init.items():
            val = np.log(v) if k in POSITIVE else v
            self.raw[k] = torch.nn.Parameter(torch.tensor(float(val)), requires_grad=train_shape)
        self.det = Detect()

    def params(self):
        return {k: (torch.exp(v) if k in POSITIVE else v) for k, v in self.raw.items()}

    def rel(self, g):
        return rel_v2(self.params(), g)

    def forward(self, g):
        return self.det(self.rel(g), g)


def subset(g, idx):
    return {k: v[idx] for k, v in g.items()}


def bce(p, y, w):
    return -(w * (y * torch.log(p) + (1 - y) * torch.log(1 - p))).sum() / w.sum()


def train(model, g, y, w, steps, lr, batch=400_000, seed=0):
    opt = torch.optim.Adam([p for p in model.parameters() if p.requires_grad], lr=lr)
    gen = torch.Generator().manual_seed(seed)
    n = len(y)
    for s in range(steps):
        idx = torch.randint(0, n, (min(batch, n),), generator=gen)
        loss = bce(model(subset(g, idx)), y[idx], w[idx])
        opt.zero_grad()
        loss.backward()
        opt.step()
        if s % 50 == 0:
            print(f"  step {s} loss {loss.item():.5f}", flush=True)
    return model


def wauc(score, y, w):
    order = np.argsort(score, kind="mergesort")
    s, yy, ww = score[order], y[order], w[order]
    pos = ww * yy
    neg = ww * (1 - yy)
    cneg = np.cumsum(neg) - neg / 2
    return float((pos * cneg).sum() / (pos.sum() * neg.sum()))


def report(name, rel, p, df):
    y = df.heard.values.astype(float)
    w = df.w.values
    ll = float(-(w * (y * np.log(p) + (1 - y) * np.log(1 - p))).sum() / w.sum())
    cats = np.digitize(rel, [0.10, 0.33, 0.66])
    labels = ["closed", "marginal", "possible", "likely"]
    rows = []
    for c in range(4):
        sel = cats == c
        heard_rate = float((w[sel] * y[sel]).sum() / max(w[sel].sum(), 1e-9))
        share_of_heard = float((w[sel] * y[sel]).sum() / (w * y).sum())
        share_of_pairs = float(w[sel].sum() / w.sum())
        rows.append((labels[c], share_of_pairs, heard_rate, share_of_heard))
    out = {"name": name, "logloss": ll, "auc_rel": wauc(rel, y, w), "auc_p": wauc(p, y, w),
           "categories": rows}
    bands = {}
    for b in BANDS:
        sel = df.band.values == b
        if sel.sum() < 1000 or y[sel].sum() < 20:
            continue
        yb, wb, rb = y[sel], w[sel], rel[sel]
        closed = rb < 0.10
        bands[b] = {"auc": wauc(rb, yb, wb),
                    "heard_in_closed": float((wb[closed] * yb[closed]).sum() / (wb * yb).sum()),
                    "heard_rate_likely": float((wb[rb >= 0.66] * yb[rb >= 0.66]).sum() / max(wb[rb >= 0.66].sum(), 1e-9)),
                    "heard_rate_closed": float((wb[closed] * yb[closed]).sum() / max(wb[closed].sum(), 1e-9))}
    out["bands"] = bands
    return out


def main(pairs_path, out_path, steps=600):
    df = pd.read_pickle(pairs_path)
    days = sorted(df.day.unique())
    recent = days[-7:]
    hist = [d for d in days if d not in recent]
    test_days = set(recent) | set(hist[3::4])
    test_station = np.array([zlib.crc32(c.encode()) % 5 == 0 for c in df.call_tx.values])
    is_test = df.day.isin(test_days).values | test_station
    train_df, test_df = df[~is_test & ~test_station], df[df.day.isin(test_days).values | test_station]
    print(f"train {len(train_df)} rows ({train_df.heard.sum()} heard), test {len(test_df)} rows ({test_df.heard.sum()} heard)")

    g_tr, g_te = geometry(train_df), geometry(test_df)
    y_tr = torch.tensor(train_df.heard.values, dtype=torch.float32)
    w_tr = torch.tensor(train_df.w.values, dtype=torch.float32)

    results = {}
    # v1: the shipped model exactly (numpy port); only the detection layer is fitted (fair baseline).
    def v1rel(d):
        return m.v1_reliability(d.lat_tx.values, d.lon_tx.values, d.lat_rx.values, d.lon_rx.values,
                                d.t.values.astype(np.int64), d.mhz.values, d.sfi.values, d.kp.values)[0]
    r1_tr = torch.tensor(v1rel(train_df), dtype=torch.float32)
    rel1 = v1rel(test_df)
    det1 = Detect()
    opt = torch.optim.Adam(det1.parameters(), lr=0.05)
    for s in range(300):
        idx = torch.randint(0, len(y_tr), (min(400_000, len(y_tr)),))
        loss = bce(det1(r1_tr[idx], subset(g_tr, idx)), y_tr[idx], w_tr[idx])
        opt.zero_grad(); loss.backward(); opt.step()
    with torch.no_grad():
        p1 = det1(torch.tensor(rel1, dtype=torch.float32), g_te).numpy()
    results["v1"] = report("v1 (shipped)", rel1, p1, test_df)
    print(json.dumps(results["v1"], indent=1), flush=True)

    v2 = V2(train_shape=True)
    v2.det.load_state_dict(det1.state_dict())
    train(v2, g_tr, y_tr, w_tr, steps=steps, lr=0.02)
    with torch.no_grad():
        rel2 = v2.rel(g_te).numpy()
        p2 = v2(g_te).numpy()
    results["v2"] = report("v2 (fitted)", rel2, p2, test_df)
    results["v2_params"] = {k: float(v) for k, v in v2.params().items()}
    results["detect"] = {"cb": v2.det.cb.tolist(), "cp": float(v2.det.cp), "cd": float(v2.det.cd),
                         "eps": float(torch.sigmoid(v2.det.eps))}
    json.dump(results, open(out_path, "w"), indent=1)
    print(json.dumps(results, indent=1))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]) if len(sys.argv) > 3 else 600)
