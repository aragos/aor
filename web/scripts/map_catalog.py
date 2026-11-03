"""Read standard transcription sources or a resolved engine catalog exported by Gradle."""

import csv
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def load_catalog(path=None):
    if path is not None:
        catalog = json.loads(Path(path).read_text())
        provinces = {
            province["id"]: {
                "area": (
                    province["area"]
                    or {"FAR_EAST": "Far East", "NEW_WORLD": "New World"}[province["overseas"]]
                ),
                "commodity": ",".join(province["commodities"]) or "—",
                "coastal": "Yes" if province["coastal"] else "No",
            }
            for province in catalog["provinces"]
        }
        relations = {
            kind: [(edge[first], edge[second]) for edge in catalog[key]]
            for kind, key, first, second in [
                ("land", "landBorders", "first", "second"),
                ("coast", "coastalConnections", "first", "second"),
                ("supports", "satelliteSupports", "satellite", "market"),
            ]
        }
        seas = [(edge["sea"], edge["province"]) for edge in catalog["seaCoasts"]]
        return provinces, relations, seas, catalog["seaAreas"]

    with (ROOT / "web/scripts/standard-provinces.csv").open(newline="") as source:
        provinces = {row["id"]: row for row in csv.DictReader(source)}
    source = (ROOT / "core/src/commonMain/kotlin/dev/aragos/aor/BoardConnections.kt").read_text()
    relations = {}
    for kind in ["land", "coast", "supports"]:
        relations[kind] = []
        for arguments in re.findall(r"(?m)^\s*" + kind + r"\((.*?)\)", source):
            ids = re.findall(r'"([a-z-]+)"', arguments)
            relations[kind].extend((ids[0], other) for other in ids[1:])
    seas = [
        (sea, province)
        for sea, arguments in re.findall(r"sea\(\s*SeaArea\.([A-Z_]+),([\s\S]*?)\)", source)
        for province in re.findall(r'"([a-z-]+)"', arguments)
    ]
    sea_areas = dict(
        re.findall(r"(?m)^\s*([A-Z_]+)\(area = GeographicalArea\.([IVX]+)\)", source)
    )
    return provinces, relations, seas, sea_areas
