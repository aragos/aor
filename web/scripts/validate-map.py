#!/usr/bin/env python3
"""Check the exported SVG geometry against the independently maintained board catalog."""
import json
import re
from pathlib import Path

from shapely import get_parts
from shapely.geometry import Point, Polygon, box
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parents[2]
geometry = json.loads((ROOT / "web/src/map/boardGeometry.json").read_text())
source = (
    ROOT / "core/src/commonMain/kotlin/dev/aragos/aor/BoardConnections.kt"
).read_text()
sea_areas = dict(
    re.findall(r"(?m)^\s*([A-Z_]+)\(area = GeographicalArea\.([IVX]+)\)", source)
)


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
    # Coordinates round to .001 SVG units. Ignore point contacts and subpixel junction slivers.
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
    expected_parts = 2 if key in {"cagliari", "athens"} else 1
    assert (
        len(get_parts(shape)) == expected_parts
    ), f"{key}: land must contain exactly its authored components."
assert land["cagliari"].covers(Point(1305, 1222)), "Corsica must belong to Cagliari."
assert land["athens"].covers(
    Point(2040, 1456)
), "The northern Greek island must belong to Athens."
assert land["bari"].covers(Point(1650, 1475.5)), "Bari must contain the toe of Italy."
assert land["tana"].covers(
    Point(2650.5, 1027)
), "Tana must extend onto the land east of Crimea."
for key in ["iceland", "sicily", "crete", "cyprus", "cagliari", "palma"]:
    assert land[key].distance(frame.boundary) > 20, f"{key} must remain an island."
print("Checked compact continental frame, connected provinces, and island coastlines.")
assert (
    geometry["provinces"]["sarai"]["label"][1]
    < geometry["provinces"]["kamyshin"]["label"][1]
), "Sarai must sit north of Kamyshin."
for neighbor in ["poti", "tana", "abagia"]:
    assert (
        land["sarai"].distance(land[neighbor]) >= 16
    ), f"Sarai must be visibly separated from {neighbor}."
for neighbor in ["poti", "abagia"]:
    assert (
        land["kamyshin"].distance(land[neighbor]) >= 16
    ), f"Kamyshin must be visibly separated from {neighbor}."
assert (
    land["tana"].distance(land["poti"]) >= 16
), "Tana must be visibly separated from Poti."
assert (
    water["cagliari"].distance(water["rome"]) >= 12
), "Cagliari must be visibly separated from Rome."
assert (
    water["acre"].distance(water["cyprus"]) >= 12
), "Acre must be visibly separated from Cyprus."
assert (
    seas["CANARY_BASIN"].distance(water["granada"]) >= 12
), "The Canary Basin must be visibly separated from Granada."
assert (
    "CENTRAL_MEDITERRANEAN" in seas and "AEGEAN_SEA" not in seas
), "The Aegean and Central Mediterranean must share one sea-space identity."
assert (
    len([part for part in get_parts(seas["CENTRAL_MEDITERRANEAN"]) if part.area > 1])
    == 1
), "The combined Mediterranean must be visibly connected around Crete."
assert (
    water["oran"].distance(water["granada"]) >= 12
), "Oran must be visibly separated from Granada."
for key, shape in geometry["provinces"].items():
    assert land[key].covers(
        Point(shape["label"])
    ), f"{key}: label must be inside its province."
    x, y = shape["label"]
    left, top, width, height = shape["labelBox"]
    label_box = box(x + left, y + top, x + left + width, y + top + height)
    if shape["labelFit"] == "LAND":
        assert (
            label_box.buffer(-0.003).difference(land[key]).is_empty
        ), f"{key}: fitted labels must stay on land."
    elif shape["labelFit"] == "PROVINCE":
        footprint = polygon(shape["outline"])
        assert (
            label_box.buffer(-0.003).difference(footprint).is_empty
        ), f"{key}: fitted labels must stay within their province."
    footprint = polygon(shape["outline"])
    for count, layout in shape["pieces"].items():
        assert len(layout["points"]) == int(count), f"{key}: missing piece slots."
        half = layout["size"] * 28 / 48
        domain = land[key] if layout["fit"] == "LAND" else footprint
        boxes = [
            box(px - half, py - half, px + half, py + half)
            for px, py in layout["points"]
        ]
        for index, piece_box in enumerate(boxes):
            assert (
                piece_box.buffer(-0.003).difference(domain).is_empty
            ), f"{key}/{count}: pieces must fit their designated land or coast."
            if not layout["crowded"]:
                assert not piece_box.intersects(
                    label_box
                ), f"{key}/{count}: piece covers a label."
            assert all(
                not piece_box.intersects(other) for other in boxes[:index]
            ), f"{key}/{count}: pieces must not overlap."
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
# Allow only the subpixel error of the .001-unit exported coordinates.
precision = 0.003
whole_land = unary_union([*land.values(), polygon(geometry["terrain"])])
for name, shapes in [("sea", seas), ("coast", water)]:
    for key, shape in shapes.items():
        assert (
            shape.buffer(-precision)
            .intersection(whole_land.buffer(-precision))
            .is_empty
        ), f"{name} {key} overlaps land or an inset panel."
for first_name, first, second_name, second in [
    ("sea", seas, "coast", water),
    ("sea", seas, "sea", seas),
    ("coast", water, "coast", water),
]:
    for first_id, first_shape in first.items():
        for second_id, second_shape in second.items():
            if first is second and first_id >= second_id:
                continue
            assert (
                first_shape.buffer(-precision)
                .intersection(second_shape.buffer(-precision))
                .is_empty
            ), f"{first_name} {first_id} overlaps {second_name} {second_id}."
print("Checked disjoint land, inset panels, coastal water, and named seas.")

for province, coast in water.items():
    for part in get_parts(coast):
        if part.area < 1 or part.distance(land[province]) < 0.02:
            continue
        remaining = coast.difference(part)
        coastal_neighbors = {
            other
            for other, shape in water.items()
            if other != province
            and remaining.boundary.buffer(0.03).intersection(shape.boundary).length > 3
        }
        expected_neighbors = {
            b if a == province else a for a, b in pairs("coast") if province in (a, b)
        }
        sea_neighbors = {
            sea
            for sea, shape in seas.items()
            if shape.boundary.buffer(0.03).intersection(remaining.boundary).length > 3
        }
        assert coastal_neighbors != expected_neighbors or sea_neighbors != {
            sea for sea, key in expected_seas if key == province
        }, f"{province}: detached coastal water must serve a catalogued connection."

footprints = {}
# Combined outlines close .005-unit shoreline cracks before export; allow that operation
# and coordinate rounding without treating its microscopic corner changes as missing land.
outline_precision = 0.01
for key, shape in geometry["provinces"].items():
    expected = (
        land[key]
        .union((water[key] if key in water else Polygon()))
        .buffer(0.005, join_style=2)
        .buffer(-0.005, join_style=2)
    )
    actual = polygon(shape["outline"])
    assert (
        actual.symmetric_difference(expected).buffer(-outline_precision).is_empty
    ), f"{key}: selection outline must be the union of land and coast."
    assert all(
        not Polygon(ring).buffer(-precision).is_empty
        for part in get_parts(actual)
        for ring in part.interiors
    ), f"{key}: selection outline must not emphasize precision-width shoreline cracks."
    footprints[key] = actual

assert set(geometry["areas"]) == {"I", "II", "III", "IV", "V", "VI", "VII", "VIII"}
areas = {key: polygon(shape["path"]) for key, shape in geometry["areas"].items()}
expected_areas = {
    area: unary_union(
        [
            footprints[key]
            for key, shape in geometry["provinces"].items()
            if shape["area"] == area
        ]
        + [
            shape
            for sea, shape in seas.items()
            if sea in sea_areas and sea_areas[sea] == area
        ]
    )
    for area in areas
}
for area, outline in areas.items():
    expected = expected_areas[area]
    area_precision = 0.03
    assert (
        expected.difference(outline).buffer(-area_precision).is_empty
    ), f"Area {area} must enclose its provinces, coastal waters, and assigned seas."
    assert (
        outline.geom_type == "Polygon"
        and not outline.interiors
        and geometry["areas"][area]["path"].count("M") == 1
    ), f"Area {area} must have one contiguous outline without enclaves or exclaves."
    for province, footprint in footprints.items():
        if geometry["provinces"][province]["area"] != area:
            assert (
                footprint.buffer(-area_precision)
                .intersection(outline.buffer(-area_precision))
                .is_empty
            ), f"Area {area} cannot enclose another area's province {province}."
    for sea, shape in seas.items():
        assigned = sea in sea_areas and sea_areas[sea] == area
        assert geometry["seas"][sea]["area"] == (
            sea_areas[sea] if sea in sea_areas else None
        ), f"{sea}: exported area membership must match the catalog."
        if assigned:
            assert (
                shape.difference(outline).buffer(-precision).is_empty
            ), f"Area {area} must include all of {sea}."
        elif sea in sea_areas:
            assert (
                shape.buffer(-precision)
                .intersection(outline.buffer(-precision))
                .is_empty
            ), f"Area {area} must not include another area's sea {sea}."
    for province, definition in geometry["provinces"].items():
        if definition["area"] in {"FAR_EAST", "NEW_WORLD"}:
            continue
        assert outline.covers(Point(definition["label"])) == (
            definition["area"] == area
        ), f"Area {area} must enclose exactly its own province labels; mismatch at {province}."
for first, shape in areas.items():
    for second, other in areas.items():
        if first >= second:
            continue
        assert (
            shape.buffer(-precision).intersection(other.buffer(-precision)).is_empty
        ), f"Areas {first} and {second} overlap."
        shared = (
            expected_areas[first]
            .boundary.buffer(precision)
            .intersection(expected_areas[second].boundary)
        )
        if shared.length > 3:
            # Check positions along the border; rounding each vertex can slightly change
            # its total length even when both exported boundaries meet at every point.
            assert (
                shared.difference(
                    shape.boundary.buffer(area_precision).intersection(
                        other.boundary.buffer(area_precision)
                    )
                ).length
                < precision
            ), f"Areas {first} and {second} must abut along their shared boundary."
print(
    f"Checked {len(expected_seas)} sea contacts, 108 combined outlines, and eight contiguous area outlines."
)
