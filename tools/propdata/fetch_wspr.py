#!/usr/bin/env python3
"""
Pull a sample of WSPR reception reports for calibrating JS8-Tactical's
propagation model. Runs on a GitHub Actions runner (stdlib only).

Data: wspr.live (all spots reported to wsprnet.org), used under its terms:
free, non-commercial research with results free for everyone; GET only,
time- and band-filtered queries, no JOINs, at most 20 requests per minute.
Solar/geomagnetic indices: GFZ Potsdam Kp/ap/Ap/SN/F10.7 file (CC BY 4.0).

To keep the pull light, only a fixed hash-sample of stations is kept
(1 in 25 transmitters, 1 in 5 receivers) and spots are rolled up per hour.
For each sampled day three files are written:
  spots_<day>.tsv  hour, band, tx, rx, tx lat/lon, rx lat/lon, best SNR, count, tx power
  tx_<day>.tsv     sampled transmitters heard by anyone that hour (= on the air)
  rx_<day>.tsv     sampled receivers that reported anything that hour (= listening)
Heard / not-heard pairs are rebuilt offline from these.
"""
import datetime as dt
import os
import sys
import time
import urllib.parse
import urllib.request

URL = "https://db1.wspr.live/?query="
UA = "JS8-Tactical propagation calibration (github.com/s2underground/JS8-Tactical)"
BANDS = "(1,3,5,7,10,14,18,21,24,28)"
TX_SAMPLE = "cityHash64(tx_sign) % 25 = 0"
RX_SAMPLE = "cityHash64(rx_sign) % 5 = 0"
PAUSE_S = 4.0  # stays well under 20 requests/minute

QUERIES = {
    "spots": f"""
SELECT toUnixTimestamp(toStartOfHour(time)) AS h, band, tx_sign, rx_sign,
  round(avg(tx_lat), 3), round(avg(tx_lon), 3), round(avg(rx_lat), 3), round(avg(rx_lon), 3),
  max(snr), count(), max(power)
FROM wspr.rx
WHERE time >= toDateTime('{{d0}}') AND time < toDateTime('{{d1}}') AND band IN {BANDS}
  AND {TX_SAMPLE} AND {RX_SAMPLE}
GROUP BY h, band, tx_sign, rx_sign
FORMAT TabSeparated""",
    "tx": f"""
SELECT toUnixTimestamp(toStartOfHour(time)) AS h, band, tx_sign,
  round(avg(tx_lat), 3), round(avg(tx_lon), 3), max(power), count()
FROM wspr.rx
WHERE time >= toDateTime('{{d0}}') AND time < toDateTime('{{d1}}') AND band IN {BANDS}
  AND {TX_SAMPLE}
GROUP BY h, band, tx_sign
FORMAT TabSeparated""",
    "rx": f"""
SELECT toUnixTimestamp(toStartOfHour(time)) AS h, band, rx_sign,
  round(avg(rx_lat), 3), round(avg(rx_lon), 3), count()
FROM wspr.rx
WHERE time >= toDateTime('{{d0}}') AND time < toDateTime('{{d1}}') AND band IN {BANDS}
  AND {RX_SAMPLE}
GROUP BY h, band, rx_sign
FORMAT TabSeparated""",
}


def get(url, tries=5):
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=180) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            wait = 15 * (i + 1)
            print(f"  retry in {wait}s: {e}", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"giving up on {url[:120]}")


def days(today):
    """5th and 20th of each of the last 24 months, plus the last 7 full days."""
    out = []
    y, m = today.year, today.month
    for _ in range(24):
        for d in (5, 20):
            day = dt.date(y, m, d)
            if day < today - dt.timedelta(days=8):
                out.append(day)
        m -= 1
        if m == 0:
            y, m = y - 1, 12
    out += [today - dt.timedelta(days=k) for k in range(1, 8)]
    return sorted(set(out))


def minimum_days():
    """5th and 20th of each month in 2019-2020 (solar minimum, SFI ~65-80)."""
    return [dt.date(y, mo, d) for y in (2019, 2020) for mo in range(1, 13) for d in (5, 20)]


def main(out_dir, day_set="recent"):
    os.makedirs(out_dir, exist_ok=True)
    today = dt.datetime.now(dt.timezone.utc).date()
    for day in (minimum_days() if day_set == "minimum" else days(today)):
        d0 = f"{day} 00:00:00"
        d1 = f"{day + dt.timedelta(days=1)} 00:00:00"
        for name, q in QUERIES.items():
            path = os.path.join(out_dir, f"{name}_{day}.tsv")
            if os.path.exists(path):
                continue
            body = get(URL + urllib.parse.quote(q.format(d0=d0, d1=d1).strip()))
            with open(path, "wb") as f:
                f.write(body)
            rows = body.count(b"\n")
            print(f"{day} {name}: {rows} rows", flush=True)
            time.sleep(PAUSE_S)
    gfz = get("https://kp.gfz-potsdam.de/app/files/Kp_ap_Ap_SN_F107_since_1932.txt")
    with open(os.path.join(out_dir, "gfz_kp_ap_sn_f107.txt"), "wb") as f:
        f.write(gfz)
    with open(os.path.join(out_dir, "SOURCES.txt"), "w") as f:
        f.write(__doc__)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "wspr-sample", sys.argv[2] if len(sys.argv) > 2 else "recent")
