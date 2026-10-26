#!/usr/bin/env python3
"""Check the exported SVG geometry against the independently maintained board catalog."""
import json
import re
from pathlib import Path

from shapely.geometry import Point, Polygon, box

ROOT = Path(__file__).resolve().parents[2]
geometry = json.loads((ROOT / "web/src/map/boardGeometry.json").read_text())
source = (
    ROOT / "core/src/commonMain/kotlin/dev/aragos/aor/BoardConnections.kt"
).read_text()


def polygon(path):
    result = Polygon()
    for ring in re.findall(r"M([^Z]+)Z", path):
        values = [float(value) for value in re.findall(r"-?\d+(?:\.\d+)?", ring)]
        part = Polygon(list(zip(values[::2], values[1::2])))
        result = result.symmetric_difference(part.buffer(0))
    assert (
        result.is_valid and not result.is_empty
    ), "Every map shape must be a valid polygon."
    return result


def pairs(kind):
    result = set()
    for arguments in re.findall(r"(?m)^\s*" + kind + r"\((.*?)\)", source):
        ids = re.findall(r'"([a-z-]+)"', arguments)
        result.update(tuple(sorted((ids[0], other))) for other in ids[1:])
    return result


def neighbors(shapes):
    # Coordinates round to .01 SVG units. Ignore point contacts and subpixel junction slivers.
    boundaries = {key: shape.boundary for key, shape in shapes.items()}
    buffered = {key: boundary.buffer(0.03) for key, boundary in boundaries.items()}
    return {
        tuple(sorted((a, b)))
        for index, a in enumerate(shapes)
        for b in list(shapes)[index + 1 :]
        if buffered[a].intersection(boundaries[b]).length > 3
    }


land = {key: polygon(shape["path"]) for key, shape in geometry["provinces"].items()}
water = {key: polygon(path) for key, path in geometry["coasts"].items()}
seas = {key: polygon(shape["path"]) for key, shape in geometry["seas"].items()}
assert len(land) == 108, "The full board must contain all 108 provinces."
frame = polygon(geometry["frame"])
# The cropped playing field follows the continental frontier instead of requiring
# long province wedges to reach the rectangular SVG viewport.
for province in [
    "bergen",
    "stockholm",
    "smolensk",
    "kiev",
    "sarai",
    "poti",
    "aleppo",
    "jerusalem",
    "fez",
    "tunis",
    "barca",
    "cairo",
]:
    assert (
        land[province].boundary.intersection(frame.boundary.buffer(0.03)).length > 20
    ), f"{province} must meet the frame as continental land, not an island."
    assert (
        Point(geometry["provinces"][province]["label"]).distance(frame.boundary) < 300
    ), f"The frame should remain close to the continental frontier at {province}."
for kind, shapes in [("land", land), ("coast", water), ("sea", seas)]:
    for key, shape in shapes.items():
        assert (
            shape.difference(frame.buffer(0.03)).area < 0.1
        ), f"{kind} {key} extends outside the map."
for key, shape in land.items():
    assert shape.geom_type == "Polygon", f"{key}: province land must be connected."
for key in ["iceland", "sicily", "crete", "cyprus", "cagliari", "palma"]:
    assert land[key].distance(frame.boundary) > 20, f"{key} must remain an island."
print("Checked compact continental frame, connected provinces, and island coastlines.")
for key, shape in geometry["provinces"].items():
    assert land[key].covers(
        Point(shape["label"])
    ), f"{key}: label must be inside its province."
for kind, shapes in [("land", land), ("coast", water)]:
    actual, expected = neighbors(shapes), pairs(kind)
    assert (
        actual == expected
    ), f"{kind}: missing {expected - actual}; extra {actual - expected}."
    print(f"Checked {len(expected)} {kind} adjacencies.")

expected_seas = {
    (sea, province)
    for sea, arguments in re.findall(r"sea\(\s*SeaArea\.([A-Z_]+),([\s\S]*?)\)", source)
    for province in re.findall(r'"([a-z-]+)"', arguments)
}
actual_seas = {
    (sea, province)
    for sea, shape in seas.items()
    for province, coast in water.items()
    if shape.boundary.buffer(0.03).intersection(coast.boundary).length > 3
}
assert (
    actual_seas == expected_seas
), f"Sea contacts differ: {actual_seas ^ expected_seas}."
assert set(geometry["areas"]) == {"I", "II", "III", "IV", "V", "VI", "VII", "VIII"}
for area, shape in geometry["areas"].items():
    assert (
        len(re.findall("M", shape["path"])) == 1
    ), f"Area {area} needs one outer perimeter."
    outline = polygon(shape["path"])
    for province, definition in geometry["provinces"].items():
        if definition["area"] in {"FAR_EAST", "NEW_WORLD"}:
            continue
        assert outline.covers(Point(definition["label"])) == (
            definition["area"] == area
        ), f"Area {area} must enclose exactly its own province labels; mismatch at {province}."
print(
    f"Checked {len(expected_seas)} sea contacts, 108 label anchors, and eight area perimeters."
)
