#!/usr/bin/env python3
"""Builds callgrid.bin: US amateur callsign -> approximate 4-character grid square (issue #55).

Inputs (public domain, US government):
  - FCC ULS complete amateur licence file, l_amat.zip (HD.dat and EN.dat).
  - US Census Bureau Gazetteer ZCTA file (ZIP code tabulation areas with centroids).

Only the callsign, licence status and the 5-digit ZIP code are read. Names, street addresses and
everything else in the licence file are skipped and never written. The output holds callsign and
grid only, and the grid is 4 characters (about 1 x 2 degrees), never finer.

Output format (big-endian):
  header  b"JS8G"  version:u8=1  reserved:3 bytes  count:u32  data_date:u32 (yyyymmdd)
  records count x { call:u32  grid:u16 } sorted by call
  call: up to 6 characters [0-9A-Z], base 37 (0 = padding at the end, '0'..'9' = 1..10,
        'A'..'Z' = 11..36); grid: ((field_lon*18 + field_lat)*10 + square_lon)*10 + square_lat.
The app's reader is LicenceGridTable.kt; both are checked against the same test vectors.

Usage: build_callgrid.py --fcc l_amat.zip --zcta zcta.zip --out callgrid.bin --date 20261126
"""
import argparse
import csv
import io
import json
import re
import struct
import sys
import zipfile

MAGIC = b"JS8G"
VERSION = 1
CALL_RE = re.compile(r"^[A-Z0-9]{3,6}$")
ZIP_RE = re.compile(r"^[0-9]{5}")
ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
MIN_RECORDS = 300_000  # far below the real count: a guard against a truncated or changed file


def encode_call(call: str):
    """Base-37 value of a callsign of up to 6 characters, or None if it can't be encoded."""
    call = call.strip().upper()
    if not CALL_RE.match(call):
        return None
    value = 0
    for i in range(6):
        c = call[i] if i < len(call) else None
        digit = 0 if c is None else ALPHABET.index(c) + 1
        value = value * 37 + digit
    return value


def grid_index(lat: float, lon: float):
    """4-character Maidenhead grid as an index 0..32399, or None outside the valid range."""
    if not (-90.0 <= lat < 90.0 and -180.0 <= lon < 180.0):
        return None
    x = lon + 180.0
    y = lat + 90.0
    f_lon, f_lat = int(x // 20), int(y // 10)
    s_lon, s_lat = int((x % 20) // 2), int(y % 10)
    return ((f_lon * 18 + f_lat) * 10 + s_lon) * 10 + s_lat


def grid_text(index: int) -> str:
    s_lat = index % 10
    s_lon = (index // 10) % 10
    f = index // 100
    f_lon, f_lat = f // 18, f % 18
    return f"{chr(65 + f_lon)}{chr(65 + f_lat)}{s_lon}{s_lat}"


def read_dat(zf: zipfile.ZipFile, name: str):
    """Yields the fields of each line of a pipe-delimited ULS .dat file inside the zip."""
    match = [n for n in zf.namelist() if n.upper().endswith(name)]
    if not match:
        sys.exit(f"{name} not found in the FCC file")
    with zf.open(match[0]) as raw:
        for line in io.TextIOWrapper(raw, encoding="latin-1", newline=""):
            yield line.rstrip("\r\n").split("|")


def load_active_calls(zf: zipfile.ZipFile):
    """Unique system id -> callsign, for active amateur licences (HD: status A, service HA/HV)."""
    active = {}
    for f in read_dat(zf, "HD.DAT"):
        if len(f) < 7 or f[0] != "HD":
            continue
        if f[5] != "A" or f[6] not in ("HA", "HV"):
            continue
        active[f[1]] = f[4].strip().upper()
    return active


def load_zips(zf: zipfile.ZipFile, active):
    """Callsign -> 5-digit ZIP, from the licensee's entity record (EN, entity type L preferred)."""
    zips = {}
    preferred = set()
    for f in read_dat(zf, "EN.DAT"):
        if len(f) < 19 or f[0] != "EN":
            continue
        call = active.get(f[1])
        if call is None:
            continue
        m = ZIP_RE.match(f[18].strip())
        if not m:
            continue
        is_licensee = f[5].strip() == "L"
        if call in preferred and not is_licensee:
            continue
        zips[call] = m.group(0)
        if is_licensee:
            preferred.add(call)
    return zips


def load_zcta(path: str):
    """ZIP (ZCTA) -> (lat, lon) centroid, and 3-digit ZIP prefix -> mean centroid as a fallback."""
    with zipfile.ZipFile(path) as zf:
        name = [n for n in zf.namelist() if n.lower().endswith(".txt")]
        if not name:
            sys.exit("no .txt in the ZCTA file")
        with zf.open(name[0]) as raw:
            reader = csv.reader(io.TextIOWrapper(raw, encoding="utf-8-sig"), delimiter="\t")
            header = [h.strip().upper() for h in next(reader)]
            try:
                i_geo, i_lat, i_lon = header.index("GEOID"), header.index("INTPTLAT"), header.index("INTPTLONG")
            except ValueError:
                sys.exit(f"unexpected ZCTA header: {header}")
            exact = {}
            for row in reader:
                try:
                    exact[row[i_geo].strip()] = (float(row[i_lat]), float(row[i_lon]))
                except (ValueError, IndexError):
                    continue
    sums = {}
    for z, (lat, lon) in exact.items():
        s = sums.setdefault(z[:3], [0.0, 0.0, 0])
        s[0] += lat
        s[1] += lon
        s[2] += 1
    prefix = {p: (s[0] / s[2], s[1] / s[2]) for p, s in sums.items()}
    return exact, prefix


def build(fcc_path, zcta_path):
    with zipfile.ZipFile(fcc_path) as zf:
        active = load_active_calls(zf)
        zips = load_zips(zf, active)
    exact, prefix = load_zcta(zcta_path)
    records = {}
    stats = {"active": len(active), "with_zip": len(zips), "exact": 0, "prefix": 0, "unplaced": 0, "unencodable": 0}
    for call, z in zips.items():
        code = encode_call(call)
        if code is None:
            stats["unencodable"] += 1
            continue
        where, kind = exact.get(z), "exact"
        if where is None:
            where, kind = prefix.get(z[:3]), "prefix"
        g = grid_index(*where) if where else None
        if g is None:
            stats["unplaced"] += 1
            continue
        stats[kind] += 1
        records[code] = g
    return records, stats


def write(records, out_path, date):
    with open(out_path, "wb") as out:
        out.write(MAGIC + struct.pack(">B3xII", VERSION, len(records), date))
        for code in sorted(records):
            out.write(struct.pack(">IH", code, records[code]))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fcc", required=True)
    ap.add_argument("--zcta", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--date", required=True, type=int, help="yyyymmdd of the FCC data")
    ap.add_argument("--min-records", type=int, default=MIN_RECORDS)
    args = ap.parse_args()
    records, stats = build(args.fcc, args.zcta)
    stats["records"] = len(records)
    print(json.dumps(stats, indent=2))
    if len(records) < args.min_records:
        sys.exit(f"only {len(records)} records: refusing to write (expected at least {args.min_records})")
    write(records, args.out, args.date)


if __name__ == "__main__":
    main()
