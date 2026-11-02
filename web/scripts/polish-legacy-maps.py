#!/usr/bin/env python3
"""Backport the Ligurian border polish without regenerating saved-game topology.

Run with the same optional dependencies as build-map.py. This deliberately patches only the
three-way junction, retaining British connections and every land, label and piece placement.
"""
import json
import re
from pathlib import Path

from shapely import get_parts
from shapely.geometry import Polygon, box
from shapely.ops import unary_union


ROOT = Path(__file__).resolve().parents[2]
PROVINCES = ("marseilles", "genoa", "cagliari")
# Enclose the junction and its former Genoa sliver; leave every other region untouched.
DETAIL = box(1180, 1080, 1400, 1240)


def polygon(path):
    result = Polygon()
    for ring in re.findall(r"M([^Z]+)Z", path):
        values = [float(value) for value in re.findall(r"-?\d+(?:\.\d+)?", ring)]
        result = result.symmetric_difference(Polygon(list(zip(values[::2], values[1::2]))).buffer(0))
    return result


def svg_path(shape):
    return "".join(
        "M" + "L".join(f"{x:.3f} {y:.3f}" for x, y in ring.coords) + "Z"
        for part in get_parts(shape) if part.geom_type == "Polygon"
        for ring in [part.exterior, *part.interiors]
    )


def contacts(first, second):
    return {(a, b) for a, shape in first.items() for b, other in second.items()
            if a != b and shape.boundary.intersection(other.boundary.buffer(.003)).length > .5}


for version in (13, 14):
    for name in ("BoardGeometry", "EuropeanBoardGeometry"):
        path = ROOT / f"web/src/map/catalog{version}{name}.json"
        geometry = json.loads(path.read_text())
        source_name = name[0].lower() + name[1:]
        current = json.loads((path.parent / f"{source_name}.json").read_text())
        land = {key: polygon(value["path"]) for key, value in geometry["provinces"].items()}
        water = {key: polygon(value) for key, value in geometry["coasts"].items()}
        seas = {key: polygon(value["path"]) for key, value in geometry["seas"].items()}
        routes = contacts(water, water)
        sea_access = contacts(water, seas)
        for province in PROVINCES:
            source_water = polygon(current["coasts"][province])
            water[province] = water[province].difference(DETAIL).union(source_water.intersection(DETAIL))
        source_sea = polygon(current["seas"]["WESTERN_MEDITERRANEAN"]["path"])
        seas["WESTERN_MEDITERRANEAN"] = seas["WESTERN_MEDITERRANEAN"].difference(DETAIL).union(source_sea.intersection(DETAIL))
        assert contacts(water, water) == routes, "Saved games must retain every coastal route."
        assert contacts(water, seas) == sea_access, "Saved games must retain every sea contact."
        land_union = unary_union(list(land.values()))
        for province in PROVINCES:
            assert water[province].intersection(land_union).area < .1, "Coastal polish cannot cover land."
            outline = polygon(geometry["provinces"][province]["outline"])
            source_outline = polygon(current["provinces"][province]["outline"])
            outline = outline.difference(DETAIL).union(source_outline.intersection(DETAIL))
            geometry["provinces"][province]["outline"] = svg_path(outline.buffer(0))
            geometry["coasts"][province] = svg_path(water[province])
        geometry["seas"]["WESTERN_MEDITERRANEAN"]["path"] = svg_path(seas["WESTERN_MEDITERRANEAN"])
        for area, value in geometry["areas"].items():
            outline = polygon(value["path"])
            if outline.intersects(DETAIL):
                source_outline = polygon(current["areas"][area]["path"])
                value["path"] = svg_path(outline.difference(DETAIL).union(source_outline.intersection(DETAIL)))
        # The arrow must cross the polished border rather than the former stepped channel.
        for route in ("cagliari marseilles", "cagliari genoa"):
            geometry["supports"][route] = current["supports"][route]
        path.write_text(json.dumps(geometry, separators=(",", ":")) + "\n")
        print(f"Polished {path.name}; coastal and sea contacts unchanged.")
