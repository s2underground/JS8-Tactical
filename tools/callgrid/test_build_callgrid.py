#!/usr/bin/env python3
"""Tests for build_callgrid.py with small made-up files (no downloads). Run: python3 -I test_build_callgrid.py"""
import os
import struct
import sys
import tempfile
import zipfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_callgrid as b  # noqa: E402


def check(cond, msg):
    if not cond:
        sys.exit("FAIL: " + msg)


# Shared test vectors with LicenceGridTableTest.kt.
check(b.encode_call("K1ABC") == 1460545511, f"K1ABC -> {b.encode_call('K1ABC')}")
check(b.encode_call("W1AW") == 2292701263, f"W1AW -> {b.encode_call('W1AW')}")
check(b.encode_call("KD9ABC") == 1482983397, f"KD9ABC -> {b.encode_call('KD9ABC')}")
check(b.encode_call("W1AW/P") is None and b.encode_call("AB") is None and b.encode_call("ABCDEFG") is None, "rejects")
check(b.grid_text(b.grid_index(41.714, -72.727)) == "FN31", "W1AW grid")
check(b.grid_text(b.grid_index(35.5, -86.5)) == "EM65", "EM65")
check(b.grid_index(35.5, -86.5) == 8465, f"EM65 index {b.grid_index(35.5, -86.5)}")
check(b.grid_index(91, 0) is None, "lat range")

with tempfile.TemporaryDirectory() as d:
    fcc = os.path.join(d, "l_amat.zip")
    with zipfile.ZipFile(fcc, "w") as z:
        z.writestr("HD.dat", "\n".join([
            "HD|1|||W1AW|A|HA|01/01/2020",
            "HD|2|||K1ABC|A|HA|01/01/2020",
            "HD|3|||N0OLD|E|HA|01/01/2010",   # expired: left out
            "HD|4|||KD9ABC|A|HV|01/01/2020",
            "HD|5|||K9NOZ|A|HA|01/01/2020",   # ZIP with no ZCTA and no prefix
        ]))
        z.writestr("EN.dat", "\n".join([
            "EN|1|||W1AW|CL|||Contact||||||||||Somewhere|CT|99999|",
            "EN|1|||W1AW|L||ARRL||||||||225 Main St|Newington|CT|06111|",
            "EN|2|||K1ABC|L||Name|First||Last|||||Street|Town|TN|37027|",
            "EN|3|||N0OLD|L||Old||||||||Street|Town|MN|55401|",
            "EN|4|||KD9ABC|L||Name||||||||Street|Town|TN|37099-1234|",
            "EN|5|||K9NOZ|L||Name||||||||Street|Town|XX|00001|",
        ]))
    zcta = os.path.join(d, "zcta.zip")
    with zipfile.ZipFile(zcta, "w") as z:
        z.writestr("Gaz_zcta.txt", "GEOID\tALAND\tAWATER\tALAND_SQMI\tAWATER_SQMI\tINTPTLAT\tINTPTLONG                 \n"
                   "06111\t0\t0\t0\t0\t41.687\t-72.730\n"
                   "37027\t0\t0\t0\t0\t36.000\t-86.800\n"
                   "37010\t0\t0\t0\t0\t36.400\t-87.100\n")
    records, stats = b.build(fcc, zcta)
    check(stats["active"] == 4, f"active {stats}")
    check(len(records) == 3, f"records {records}")
    check(b.grid_text(records[b.encode_call("W1AW")]) == "FN31", "W1AW uses the licensee row, not the contact")
    check(b.grid_text(records[b.encode_call("K1ABC")]) == "EM66", "K1ABC exact ZCTA")
    check(stats["prefix"] == 1 and records.get(b.encode_call("KD9ABC")) is not None, "KD9ABC via 3-digit prefix")
    check(stats["unplaced"] == 1, "K9NOZ unplaced")
    out = os.path.join(d, "callgrid.bin")
    b.write(records, out, 20261126)
    data = open(out, "rb").read()
    check(data[:4] == b"JS8G" and data[4] == 1, "header")
    count, date = struct.unpack(">II", data[8:16])
    check(count == 3 and date == 20261126 and len(data) == 16 + 6 * 3, "size")
    calls = [struct.unpack(">I", data[16 + 6 * i:20 + 6 * i])[0] for i in range(3)]
    check(calls == sorted(calls), "sorted")
    # Nothing but callsign and grid in the output.
    check(b"ARRL" not in data and b"Main" not in data and b"06111" not in data, "no names, addresses or ZIPs")

print("build_callgrid tests passed")
