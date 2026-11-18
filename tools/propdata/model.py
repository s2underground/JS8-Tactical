"""Vectorised numpy copies of the app's propagation model, for calibration."""
import numpy as np

R = 6371.0
H_F2 = 300.0
MAX_HOP_KM = 3500.0
MAX_SECANT = 3.6
D2R = np.pi / 180


def subsolar(t_sec):
    """t_sec: unix seconds (array). Returns (lat, lon) in degrees. Mirrors Sun.subsolarPoint."""
    t = np.asarray(t_sec, dtype=np.int64)
    days = t // 86400
    # day of year (1-based) from unix days
    dts = np.array(days, dtype="datetime64[D]")
    years = dts.astype("datetime64[Y]")
    doy = (dts - years).astype(np.int64) + 1
    hours = (t % 86400) / 3600.0
    g = 2 * np.pi / 365.0 * (doy - 1 + (hours - 12) / 24)
    decl = (0.006918 - 0.399912 * np.cos(g) + 0.070257 * np.sin(g) - 0.006758 * np.cos(2 * g)
            + 0.000907 * np.sin(2 * g) - 0.002697 * np.cos(3 * g) + 0.00148 * np.sin(3 * g))
    eq = 229.18 * (0.000075 + 0.001868 * np.cos(g) - 0.032077 * np.sin(g) - 0.014615 * np.cos(2 * g) - 0.040849 * np.sin(2 * g))
    lon = -15.0 * (hours - 12 + eq / 60.0)
    lon = ((lon + 540) % 360) - 180
    return np.degrees(decl), lon, doy


def cos_zenith(lat, lon, slat, slon):
    phi = lat * D2R
    dec = slat * D2R
    h = (lon - slon) * D2R
    return np.clip(np.sin(phi) * np.sin(dec) + np.cos(phi) * np.cos(dec) * np.cos(h), -1, 1)


def distance_km(lat1, lon1, lat2, lon2):
    p1, p2 = lat1 * D2R, lat2 * D2R
    dphi = p2 - p1
    dl = (lon2 - lon1) * D2R
    a = np.sin(dphi / 2) ** 2 + np.cos(p1) * np.cos(p2) * np.sin(dl / 2) ** 2
    return 2 * R * np.arcsin(np.sqrt(np.clip(a, 0, 1)))


def intermediate(lat1, lon1, lat2, lon2, f):
    la1, lo1, la2, lo2 = lat1 * D2R, lon1 * D2R, lat2 * D2R, lon2 * D2R
    d = 2 * np.arcsin(np.sqrt(np.clip(np.sin((la2 - la1) / 2) ** 2 + np.cos(la1) * np.cos(la2) * np.sin((lo2 - lo1) / 2) ** 2, 0, 1)))
    small = d < 1e-9
    d = np.where(small, 1e-9, d)
    x1 = np.sin((1 - f) * d) / np.sin(d)
    x2 = np.sin(f * d) / np.sin(d)
    x = x1 * np.cos(la1) * np.cos(lo1) + x2 * np.cos(la2) * np.cos(lo2)
    y = x1 * np.cos(la1) * np.sin(lo1) + x2 * np.cos(la2) * np.sin(lo2)
    z = x1 * np.sin(la1) + x2 * np.sin(la2)
    lat = np.degrees(np.arctan2(z, np.sqrt(x * x + y * y)))
    lon = np.degrees(np.arctan2(y, x))
    return np.where(small, lat1, lat), np.where(small, lon1, lon)


def secant(hop_km, h=H_F2, max_sec=MAX_SECANT):
    theta = hop_km / (2 * R)
    ap = np.sqrt(R * R + (R + h) ** 2 - 2 * R * (R + h) * np.cos(theta))
    sin_i = np.clip(R * np.sin(theta) / ap, 0, 0.999)
    return np.minimum(max_sec, 1 / np.sqrt(1 - sin_i ** 2))


# ---------------------------------------------------------------- v1 (shipped)

def v1_fof2(lat, coszen, ssn, k):
    day = 4.2 + 0.042 * ssn
    night = 2.3 + 0.021 * ssn
    lit = (np.maximum(0, coszen + 0.1) / 1.1) ** 0.5
    f = night + (day - night) * lit
    f = np.where(np.abs(lat) > 60, f * 0.85, f)
    storm = np.maximum(0, k - 3)
    f = f * (1 - 0.04 * storm * np.where(np.abs(lat) > 45, 1.5, 1.0))
    return np.maximum(1.0, f)


def v1_reliability(txlat, txlon, rxlat, rxlon, t, mhz, sfi, k):
    ssn = np.maximum(0, 1.14 * sfi - 73.21)
    slat, slon, _ = subsolar(t)
    d = distance_km(txlat, txlon, rxlat, rxlon)
    hops = np.maximum(1, np.ceil(d / MAX_HOP_KM))
    sec = secant(d / hops)
    muf = np.full(d.shape, np.inf)
    absorb = np.zeros(d.shape)
    for i in range(7):
        active = i < hops
        mlat, mlon = intermediate(txlat, txlon, rxlat, rxlon, (i + 0.5) / hops)
        cz = cos_zenith(mlat, mlon, slat, slon)
        f = v1_fof2(mlat, cz, ssn, k) * sec
        muf = np.where(active, np.minimum(muf, f), muf)
        a = np.maximum(0, cz) ** 0.75 * (1 + 0.0037 * ssn)
        absorb = np.where(active, np.maximum(absorb, a), absorb)
    luf = (1.0 + 3.6 * absorb) * (1 + 0.25 * (sec - 1)) * hops ** 0.3 * 0.75
    ground = np.where(mhz < 10, np.maximum(0, 1 - d / np.where(mhz < 4, 80.0, 50.0)), 0.0)
    upper = np.clip((muf * 1.05 - mhz) / (0.2 * muf), 0, 1)
    lower = np.clip((mhz - luf) / (0.25 * luf), 0, 1)
    return np.maximum(ground, np.minimum(upper, lower)), d, muf, luf


# ---------------------------------------------------------------- v2 (WSPR-calibrated)

POLE_LAT, POLE_LON = 80.8, -72.7


def maglat(lat, lon):
    p, pl = lat * D2R, POLE_LAT * D2R
    return np.degrees(np.arcsin(np.clip(np.sin(p) * np.sin(pl) + np.cos(p) * np.cos(pl) * np.cos((lon - POLE_LON) * D2R), -1, 1)))


def sigmoid(x):
    return 1 / (1 + np.exp(-x))


def v2_raw(P, txlat, txlon, rxlat, rxlon, t, mhz, sfi, k):
    t = np.asarray(t, dtype=np.int64)
    ssn = np.maximum(0, 1.14 * sfi - 73.21)
    slat, slon, doy = subsolar(t)
    slat2, slon2, _ = subsolar(t - 7200)
    d = distance_km(txlat, txlon, rxlat, rxlon)
    hops = np.maximum(1, np.ceil(d / MAX_HOP_KM))
    sec = secant(d / hops, h=P["h"])
    muf = np.full(d.shape, np.inf)
    absorb = np.zeros(d.shape)
    es = None
    for i in range(7):
        active = i < hops
        la, lo = intermediate(txlat, txlon, rxlat, rxlon, (i + 0.5) / hops)
        cz0 = cos_zenith(la, lo, slat, slon)
        cz = (1 - P["alpha"]) * cz0 + P["alpha"] * cos_zenith(la, lo, slat2, slon2)
        lit = (np.maximum(0, cz + P["c0"]) / (1 + P["c0"]) + 1e-6) ** P["p"]
        s = np.cos(2 * np.pi * (doy - 172) / 365.0) * np.sign(la)
        aml = np.abs(maglat(la, lo))
        day = (P["a0"] + P["a1"] * ssn) * (1 - P["wd"] * s)
        night = (P["b0"] + P["b1"] * ssn) * (1 + P["wn"] * s)
        f = night + (day - night) * lit
        f = f * (1 - P["hl"] * sigmoid((aml - 60) / 4))
        f = f * (1 + P["eq"] * np.exp(-((aml - 15) / 10) ** 2) * lit)
        f = f * (1 - P["ks"] * np.maximum(0, k - 3) * (1 + aml / 45))
        f = np.maximum(1.0, f)
        muf = np.where(active, np.minimum(muf, f * sec), muf)
        a = np.maximum(0, cz0) ** 0.75 * (1 + P["ka"] * ssn)
        absorb = np.where(active, np.maximum(absorb, a), absorb)
        if i == 0:
            summer = P["esw"] + ((1 + s) / 2) ** P["esp"]
            dayes = np.maximum(0, cz0 + P["esc"]) / (1 + P["esc"])
            midlat = np.exp(-((aml - 40) / 18) ** 2)
            one = sigmoid((d - 400) / 80) * sigmoid((2300 - d) / 200)
            two = P["es2"] * sigmoid((d - 2300) / 200) * sigmoid((4500 - d) / 300)
            es = P["es0"] * summer * dayes * midlat * (one + two)
    muf = muf * P["mm"]
    luf = P["l0"] * (1 + P["l1"] * absorb) * (1 + P["l2"] * (sec - 1)) * hops ** P["l3"]
    ground = np.where(mhz < 10, np.maximum(0, 1 - d / np.where(mhz < 4, P["gw1"], P["gw2"])), 0.0)
    upper = np.clip((muf * P["u0"] - mhz) / (P["u1"] * muf), 0, 1)
    lower = np.clip((mhz - luf) / (P["w"] * luf), 0, 1)
    es_f = np.clip(es * np.exp(-P["esk"] * (mhz - 14)), 0, 1)
    return np.maximum(np.maximum(ground, np.minimum(upper, lower)), es_f), d, muf, luf


SCORE_REL = [0.0, 0.005, 0.074, 0.124, 0.174, 0.225, 0.275, 0.324, 0.375, 0.425, 0.476, 0.522, 0.577, 0.622, 0.723]
SCORE_VAL = [0.0, 0.055, 0.152, 0.211, 0.274, 0.360, 0.469, 0.549, 0.651, 0.740, 0.826, 0.872, 0.909, 0.949, 1.0]


def score(rel):
    return np.interp(rel, SCORE_REL, SCORE_VAL)
