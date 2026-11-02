#!/usr/bin/env python3
"""Check the exported SVG geometry against the independently maintained board catalog."""
import argparse
import json
import re
from pathlib import Path

from shapely import get_parts
from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import transform, unary_union
from map_geography import SCANDINAVIAN_INTERIOR, display_point
from map_catalog import load_catalog
from support_geometry import arrow_footprint, label_footprints, marker_footprints

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--catalog", type=Path, help="Resolved JSON from :server:exportMapCatalog")
parser.add_argument("--geometry", type=Path, default=ROOT / "web/src/map/boardGeometry.json")
args = parser.parse_args()
geometry = json.loads(args.geometry.read_text())
provinces, relations, sea_contacts, sea_areas = load_catalog(args.catalog)


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
    return {tuple(sorted(edge)) for edge in relations[kind]}


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
assert set(land) == set(provinces), "The drawing must contain exactly the selected map's provinces."
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
    if province not in land:
        continue
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
    expected_parts = 3 if key == "copenhagen" else 2 if key in {"cagliari", "athens"} else 1
    assert (
        len(get_parts(shape)) == expected_parts
    ), f"{key}: land must contain exactly its authored components."
assert land["cagliari"].covers(Point(1305, 1222)), "Corsica must belong to Cagliari."
assert land["athens"].covers(
    Point(2040, 1456)
), "The northern Greek island must belong to Athens."
assert land["bari"].covers(Point(1650, 1475.5)), "Bari must contain the toe of Italy."
for neighbor in ["wales", "chester"]:
    assert land["cornwall"].distance(land[neighbor]) >= 8, (
        f"Visible coastal water must separate Cornwall from {neighbor}'s land."
    )
for neighbor in ["wales", "chester", "portsmouth"]:
    assert water["cornwall"].boundary.buffer(0.03).intersection(water[neighbor].boundary).length > 12, (
        f"Cornwall must share a visible coastal-water border with {neighbor}."
    )
assert land["cornwall"].boundary.buffer(0.03).intersection(land["portsmouth"].boundary).length > 20, (
    "Portsmouth must remain Cornwall's only land neighbor."
)
bristol_channel = box(680, 575, 765, 655)
for first, second in [
    ("cornwall", "chester"),
    ("chester", "portsmouth"),
    ("cornwall", "portsmouth"),
]:
    shared_border = water[first].boundary.buffer(0.03).intersection(water[second].boundary)
    assert shared_border.intersection(bristol_channel).length > 16, (
        f"{first} and {second} must share a visible coastal border inside the Bristol Channel."
    )
assert land["chester"].boundary.buffer(0.03).intersection(land["portsmouth"].boundary).length > 20, (
    "Widening the Bristol Channel must preserve the Chester–Portsmouth land crossing."
)
assert seas["BAY_OF_BISCAY"].boundary.buffer(0.03).intersection(water["edinburgh"].boundary).length >= 100, (
    "Edinburgh must share a broad, visible frontage with the Bay of Biscay."
)
for neighbor in ["dubrovnik", "durazzo"]:
    assert land["belgrade"].boundary.buffer(0.03).intersection(land[neighbor].boundary).length > 20, (
        f"Belgrade must share a visible land border with {neighbor}."
    )
for neighbor in ["dubrovnik", "durazzo", "bari"]:
    assert water["belgrade"].boundary.buffer(0.03).intersection(water[neighbor].boundary).length > 12, (
        f"Belgrade must share a visible coastal-water border with {neighbor}."
    )
assert land["dubrovnik"].distance(land["durazzo"]) >= 18, (
    "Belgrade must visibly separate Dubrovnik and Durazzo on land."
)
assert water["dubrovnik"].distance(water["durazzo"]) >= 18, (
    "Belgrade must visibly separate Dubrovnik and Durazzo's coastal water."
)
for name, point in [
    ("northern Jutland", (1325, 292.5)),
    ("Funen", (1365, 370.5)),
    ("Zealand", (1435, 351)),
]:
    assert land["copenhagen"].covers(Point(point)), f"{name} must belong to Copenhagen."
assert land["hamburg"].covers(Point(1325, 487.5)), "Hamburg must retain its southern land."
for name, point in [
    ("eastern Funen", (1377.8, 366.5)),
    ("northern Zealand strip", (1456.9, 341.5)),
    ("southern Zealand tip", (1442.9, 402.4)),
]:
    assert land["copenhagen"].covers(Point(point)), f"{name} must belong to Copenhagen."
assert all(
    Polygon(ring).area < 0.1
    for part in get_parts(polygon(geometry["provinces"]["copenhagen"]["outline"]))
    for ring in part.interiors
), "Copenhagen's combined outline must not contain neutral enclaves."
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
    seas["WESTERN_MEDITERRANEAN"].boundary.buffer(0.03).intersection(water["fez"].boundary).length > 20
), "Fez must share a visible frontage with the Western Mediterranean."
assert (
    land["kongsberg"].boundary.buffer(0.03).intersection(land["malmo"].boundary).length > 20
), "Kongsberg and Malmo must share a visible land border."
scandinavian_land = transform(display_point, Polygon(SCANDINAVIAN_INTERIOR)).intersection(
    unary_union([*land.values(), polygon(geometry["terrain"])])
)
reclaimed = land["kongsberg"].union(land["malmo"])
assert scandinavian_land.difference(land["stockholm"].buffer(13)).difference(reclaimed).buffer(-2.1).is_empty, (
    "The former Scandinavian interior must belong to Kongsberg and Malmo, apart from border gaps."
)
assert land["kongsberg"].distance(land["stockholm"]) >= 10, (
    "Reclaiming the interior must retain a visible separation between Kongsberg and Stockholm."
)
for province in ["kongsberg", "malmo"]:
    assert land[province].intersection(scandinavian_land).area > 1000, (
        f"{province} must receive a substantial share of the former neutral interior."
    )
assert all(part.distance(land["stettin"]) < 0.03 for part in get_parts(water["stettin"])), (
    "Stettin must have no detached coastal exclave."
)
assert (
    water["copenhagen"].boundary.buffer(0.03).intersection(water["stettin"].boundary).length > 12
), "Copenhagen and Stettin must share a visible coastal-water border."
assert (
    water["malmo"].boundary.buffer(0.03).intersection(water["stettin"].boundary).length > 12
), "Malmo and Stettin must share a visible coastal-water border."
assert (
    land["copenhagen"].boundary.buffer(0.03).intersection(land["hamburg"].boundary).length > 20
), "Copenhagen and Hamburg must share a visible mainland border."
assert water["malmo"].distance(water["lubeck"]) >= 18, (
    "Malmo and Lubeck must be visibly separated by another space."
)
for province, sea in [("malmo", "NORTH_SEA"), ("lubeck", "BALTIC_SEA"),
                      ("copenhagen", "BALTIC_SEA"), ("paris", "BAY_OF_BISCAY"),
                      ("chester", "BAY_OF_BISCAY"), ("durazzo", "ADRIATIC_SEA")]:
    assert water[province].distance(seas[sea]) >= 18, (
        f"{province} must be visibly separated from {sea}."
    )
for province, coast in water.items():
    if province not in {"visby", "stockholm"}:
        assert water["visby"].distance(coast) >= 18, (
            f"Visby must be visibly separated from {province}'s coastal water."
        )
assert set(seas) == {sea for sea, _ in sea_contacts}, (
    "The drawing must show exactly the selected map's connected seas."
)
assert ("smyrna", "taurus") not in pairs("land"), "Smyrna and Taurus have no land border."
assert ("smyrna", "taurus") not in pairs("coast"), "Smyrna and Taurus have no direct coast border."
assert land["smyrna"].distance(land["taurus"]) > 18, (
    "Adalia must visibly separate Smyrna and Taurus on land."
)
assert water["smyrna"].distance(water["taurus"]) > 18, (
    "Smyrna and Taurus coastal waters must remain visibly separated."
)
for sea in {sea for sea, province in sea_contacts if province == "smyrna"}:
    assert seas[sea].boundary.buffer(0.03).intersection(water["smyrna"].boundary).length > 12, (
        f"Smyrna must visibly adjoin {sea}."
    )
assert seas["ADRIATIC_SEA"].boundary.buffer(0.03).intersection(water["belgrade"].boundary).length > 12, (
    "Belgrade must have a visible Adriatic sea frontage."
)
for sea in ["CENTRAL_MEDITERRANEAN", "AEGEAN_SEA"]:
    if sea not in seas:
        continue
    assert geometry["seas"][sea]["area"] == "VIII", f"{sea} must belong to Area VIII."
    assert (
        len([part for part in get_parts(seas[sea]) if part.area > 1]) == 1
    ), f"{sea} must be visibly connected."
assert {
    sea for sea, shape in seas.items()
    if water["crete"].boundary.buffer(0.03).intersection(shape.boundary).length > 3
} == {sea for sea, province in sea_contacts if province == "crete"}, (
    "Crete's visible sea contacts must match the selected map."
)
assert (
    water["oran"].distance(water["granada"]) >= 12
), "Oran must be visibly separated from Granada."
for key, shape in geometry["provinces"].items():
    assert polygon(shape["outline"]).covers(
        Point(shape["label"])
    ), f"{key}: label must be inside its province footprint."
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

expected_seas = set(sea_contacts)
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
    f"Checked {len(expected_seas)} sea contacts, {len(land)} combined outlines, and eight contiguous area outlines."
)

# Arrows are authored from the catalog and cannot acquire new support relationships.
expected_supports = {f"{satellite} {market}" for satellite, market in relations["supports"]}
assert set(geometry["supports"]) == expected_supports, "Support arrows must match the catalog."
support_lines = {}
support_envelopes = {}
label_bounds = label_footprints(geometry["provinces"])
marker_bounds = marker_footprints(geometry["provinces"])
for edge, path in geometry["supports"].items():
    values = [float(value) for value in re.findall(r"-?\d+(?:\.\d+)?", path)]
    points = list(zip(values[::2], values[1::2]))
    assert len(points) == 2, f"{edge}: support must be one straight border crossing."
    source_id, target_id = edge.split()
    assert footprints[source_id].buffer(0.003).covers(Point(points[0])), (
        f"{edge}: support arrow must begin in its satellite."
    )
    assert footprints[target_id].buffer(0.003).covers(Point(points[-1])), (
        f"{edge}: support arrow must end in its market."
    )
    line = LineString(points)
    assert 24 <= line.length <= 48.01, f"{edge}: keep support crossings short and visible."
    envelope = arrow_footprint(line)
    assert frame.buffer(0.003).covers(envelope), f"{edge}: the complete arrow must stay on the board."
    assert envelope.intersection(label_bounds).area < 0.1, f"{edge}: arrowhead/halo overlaps a label."
    assert envelope.intersection(marker_bounds).area < 0.1, (
        f"{edge}: arrowhead/halo overlaps a normal control-marker slot."
    )
    assert all(envelope.intersection(other).area < 0.1 for other in support_envelopes.values()), (
        f"{edge}: arrowheads and halos must not overlap another support arrow."
    )
    foreign_length = sum(
        line.intersection(shape).length
        for key, shape in footprints.items() if key not in (source_id, target_id)
    )
    assert foreign_length < 0.01, (
        f"{edge}: support must not run through another province."
    )
    owned = footprints[source_id].union(footprints[target_id]).buffer(0.01)
    assert envelope.difference(owned).area < 4, (
        f"{edge}: keep the arrowhead and halo within the supported pair's footprint."
    )
    assert all(not line.crosses(other) for other in support_lines.values()), (
        f"{edge}: support shafts must not cross."
    )
    support_lines[edge] = line
    support_envelopes[edge] = envelope
print(f"Checked {len(expected_supports)} short, straight support paths, endpoints, clear labels and uncrossed shafts.")

# These compact crossings must keep a readable border beside the arrow.
shetland_border = water["shetland-islands"].boundary.intersection(water["bergen"].boundary.buffer(.003))
assert shetland_border.bounds[2] - shetland_border.bounds[0] < .01, (
    "Shetland–Bergen must share one straight north–south coastal border."
)
marseilles_border = water["cagliari"].boundary.intersection(water["marseilles"].boundary.buffer(.003))
assert marseilles_border.difference(support_envelopes["cagliari marseilles"]).length > 28, (
    "Cagliari–Marseilles must retain visible border beyond the arrowhead and halo."
)
print("Checked straight Shetland–Bergen border and visible Cagliari–Marseilles border.")

for first, second in [("marseilles", "genoa"), ("marseilles", "cagliari")]:
    shared = water[first].boundary.intersection(water[second].boundary.buffer(.003))
    assert shared.convex_hull.area < shared.length * .01, (
        f"{first}–{second}: the Ligurian junction must use a straight border without hooks."
    )
print("Checked simple Marseilles–Genoa–Cagliari junction.")
