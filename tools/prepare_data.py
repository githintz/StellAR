#!/usr/bin/env python3
"""Prepare bundled sky-data assets for StellAR.

Converts the d3-celestial datasets (BSD-3-Clause, (c) Olaf Frohn,
https://github.com/ofrohn/d3-celestial — itself derived from the
Hipparcos catalogue) into the compact formats bundled in
app/src/main/assets/:

  stars.csv            hip,ra_deg,dec_deg,mag,bv,name,designation
  constellations.json  [{id,name,label:[ra,dec],lines:[[[ra,dec],...],...]}]

Usage:
  python3 tools/prepare_data.py                # downloads source data
  python3 tools/prepare_data.py --source-dir /tmp/data   # use local copies
"""

import argparse
import json
import os
import urllib.request

BASE = "https://raw.githubusercontent.com/ofrohn/d3-celestial/master/data/"
FILES = ["stars.6.json", "starnames.json", "constellations.json",
         "constellations.lines.json"]

GREEK = {
    "α": "Alpha", "β": "Beta", "γ": "Gamma", "δ": "Delta", "ε": "Epsilon",
    "ζ": "Zeta", "η": "Eta", "θ": "Theta", "ι": "Iota", "κ": "Kappa",
    "λ": "Lambda", "μ": "Mu", "ν": "Nu", "ξ": "Xi", "ο": "Omicron",
    "π": "Pi", "ρ": "Rho", "σ": "Sigma", "τ": "Tau", "υ": "Upsilon",
    "φ": "Phi", "χ": "Chi", "ψ": "Psi", "ω": "Omega",
}

MAG_LIMIT = 6.0


def load(source_dir, name):
    if source_dir:
        with open(os.path.join(source_dir, name), encoding="utf-8") as f:
            return json.load(f)
    with urllib.request.urlopen(BASE + name) as r:
        return json.loads(r.read().decode("utf-8"))


def ra_from_lon(lon):
    return round(lon % 360.0, 4)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source-dir", default=None)
    ap.add_argument("--out-dir", default="app/src/main/assets")
    args = ap.parse_args()

    stars = load(args.source_dir, "stars.6.json")
    names = load(args.source_dir, "starnames.json")
    cons = load(args.source_dir, "constellations.json")
    lines = load(args.source_dir, "constellations.lines.json")

    os.makedirs(args.out_dir, exist_ok=True)

    rows = []
    for f in stars["features"]:
        hip = f["id"]
        mag = float(f["properties"]["mag"])
        if mag > MAG_LIMIT:
            continue
        bv = f["properties"].get("bv") or "0.0"
        lon, lat = f["geometry"]["coordinates"]
        info = names.get(str(hip), {})
        name = info.get("name", "")
        bayer = info.get("bayer", "")
        con = info.get("c", "")
        desig = f"{GREEK.get(bayer, bayer)} {con}".strip() if bayer else ""
        rows.append((hip, ra_from_lon(lon), round(lat, 4), round(mag, 2),
                     round(float(bv), 2), name, desig))
    rows.sort(key=lambda r: r[3])

    star_path = os.path.join(args.out_dir, "stars.csv")
    with open(star_path, "w", encoding="utf-8") as f:
        f.write("hip,ra,dec,mag,bv,name,designation\n")
        for r in rows:
            f.write(",".join(str(x) for x in r) + "\n")
    print(f"wrote {star_path}: {len(rows)} stars (mag <= {MAG_LIMIT})")

    labels = {}
    en_names = {}
    for f in cons["features"]:
        cid = f["id"]
        en_names[cid] = f["properties"].get("en") or f["properties"]["name"]
        lon, lat = f["geometry"]["coordinates"]
        labels[cid] = [ra_from_lon(lon), round(lat, 4)]

    out = []
    for f in lines["features"]:
        cid = f["id"]
        segs = [[[ra_from_lon(lon), round(lat, 4)] for lon, lat in seg]
                for seg in f["geometry"]["coordinates"]]
        out.append({
            "id": cid,
            "name": en_names.get(cid, cid),
            "label": labels.get(cid, segs[0][0]),
            "lines": segs,
        })

    con_path = os.path.join(args.out_dir, "constellations.json")
    with open(con_path, "w", encoding="utf-8") as f:
        json.dump(out, f, separators=(",", ":"))
    print(f"wrote {con_path}: {len(out)} constellations")


if __name__ == "__main__":
    main()
