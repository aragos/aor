#!/usr/bin/env python3
"""Build the geographic schematic from authored anchors and the reviewed connection catalog.

Run from any directory using the optional dependencies in map-requirements.txt.
The game never imports this script: browser geometry is a static, validated asset.

Anchors use SVG units, not latitude and longitude. Land cells meet at catalog edge
midpoints and face centers. Coastal cells divide the shoreline buffer; water
channels restore required contacts around peninsulas. Sea cells are seeded from
their catalogued coastal boundaries. Validate the exported asset after generation.
"""

import heapq
import json
import math
import os
import re
from pathlib import Path

from scipy.spatial import Voronoi
from shapely import get_parts
from shapely.geometry import LineString, MultiLineString, Point, Polygon, box
from shapely.ops import nearest_points, polygonize, polylabel, substring, unary_union
from shapely.prepared import prep

from map_geography import LABEL_OFFSETS, display_point, geography, project

ROOT = Path(__file__).resolve().parents[2]
os.chdir(ROOT)


p = {}
for line in Path("docs/province-inventory.md").read_text().splitlines():
    c = [v.strip() for v in line.split("|")[1:-1]]
    if len(c) == 7 and re.fullmatch("[a-z]+(?:-[a-z]+)*", c[0]):
        p[c[0]] = dict(
            zip(
                ["id", "area", "market", "commodity", "capital", "coastal", "access"], c
            )
        )
s = Path("core/src/commonMain/kotlin/dev/aragos/aor/BoardConnections.kt").read_text()
rel = {}
for k in ["land", "coast", "supports"]:
    rel[k] = []
    for args in re.findall(r"(?m)^\s*" + k + r"\((.*?)\)", s):
        n = re.findall(r'"([a-z-]+)"', args)
        rel[k].extend((n[0], b) for b in n[1:])
seas = []
for sea, args in re.findall(r"sea\(\s*SeaArea\.([A-Z_]+),([\s\S]*?)\)", s):
    seas.extend((sea, b) for b in re.findall(r'"([a-z-]+)"', args))

# Geographic coastlines are independent of the game graph. The graph partitions
# land; narrow neutral interiors separate real neighbors absent from the catalog.
world, physical_land, neutral, positions, routes = geography()
land_domain = physical_land.difference(neutral)
segments = [
    LineString([positions[a], *routes.get((a, b), []), positions[b]])
    for a, b in rel["land"]
]
faces = list(polygonize(segments))
dividers = []
for (a, b), edge in zip(rel["land"], segments):
    mid = edge.interpolate(0.5, normalized=True).coords[0]
    before = edge.interpolate(0.49, normalized=True)
    after = edge.interpolate(0.51, normalized=True)
    adjacent = [
        face
        for face in faces
        if face.boundary.intersection(edge).length > edge.length * 0.99
    ]
    for face in adjacent:
        assert face.contains(
            face.centroid
        ), "Province face center must be inside its land cycle."
        dividers.append(LineString([mid, face.centroid.coords[0]]))
    dx, dy = after.x - before.x, after.y - before.y
    length = math.hypot(dx, dy)
    for sign in [-1, 1]:
        probe = Point(mid[0] - dy / length * sign, mid[1] + dx / length * sign)
        if any(face.contains(probe) for face in adjacent):
            continue
        target = (
            mid[0] - dy / length * sign * 5000,
            mid[1] + dx / length * sign * 5000,
        )
        rays = [
            line
            for line in get_parts(LineString([mid, target]).intersection(land_domain))
            if line.geom_type == "LineString" and line.distance(Point(mid)) < 0.001
        ]
        # A bay can put the edge midpoint just offshore; its interior face cuts
        # still define the province border. Never extend across a second coast.
        if not rays:
            continue
        end = rays[0].coords[-1]
        dividers.append(
            LineString(
                [
                    mid,
                    (
                        end[0] + (end[0] - mid[0]) * 0.01,
                        end[1] + (end[1] - mid[1]) * 0.01,
                    ),
                ]
            )
        )
cells = list(polygonize(unary_union([land_domain.boundary, *dividers])))
byid = {id: [] for id in positions}
for cell in cells:
    if not land_domain.covers(cell.representative_point()):
        continue
    owners = [id for id, point in positions.items() if cell.covers(Point(point))]
    assert len(owners) <= 1, f"Province labels overlap a land cell: {owners}."
    owner = (
        owners[0]
        if owners
        else min(
            positions,
            key=lambda id: Point(positions[id]).distance(cell.representative_point()),
        )
    )
    byid[owner].append(cell)
shapes = {id: unary_union(cells) for id, cells in byid.items()}
actual = {
    tuple(sorted((a, b)))
    for i, a in enumerate(shapes)
    for b in list(shapes)[i + 1 :]
    if shapes[a].boundary.intersection(shapes[b].boundary).length > 0.1
}
expected_land = {tuple(sorted(pair)) for pair in rel["land"]}
assert not expected_land - actual, f"Land borders missing: {expected_land - actual}."
# Geographic contact alone cannot create a game border. Leave a narrow band of
# neutral land at those contacts, and retain decorative islands as neutral land.
gaps = {
    id: unary_union(
        [
            shapes[a].boundary.intersection(shapes[b].boundary).buffer(2, cap_style=2)
            for a, b in actual - expected_land
            if id in (a, b)
        ]
    )
    for id in shapes
}
hinterland = unary_union(
    [face.buffer(-75) for face in faces if len(face.exterior.coords) > 9]
)
for id, polygon in list(shapes.items()):
    parts = get_parts(polygon.difference(gaps[id].union(hinterland)))
    shapes[id] = next(part for part in parts if part.covers(Point(positions[id])))
land_domain = physical_land
terrain = land_domain.difference(unary_union(list(shapes.values())))
coastal = {id: s for id, s in shapes.items() if p[id]["coastal"] == "Yes"}
seeds = []
owners = []
shore_samples = set()
for id, s in coastal.items():
    shore = MultiLineString(
        [
            line
            for line in get_parts(
                s.boundary.intersection(land_domain.boundary).difference(world.boundary)
            )
            if line.geom_type == "LineString" and not line.is_empty
        ]
    )
    if shore.length == 0:
        continue
    for distance in range(0, int(shore.length), 12):
        pt = shore.interpolate(distance)
        sample = tuple(round(value, 3) for value in pt.coords[0])
        if sample in shore_samples:
            continue
        shore_samples.add(sample)
        seeds.append(pt.coords[0])
        owners.append(id)
# Bound Voronoi cells with distant guard points.
seeds.extend([(-10000, -10000), (10000, -10000), (10000, 10000), (-10000, 10000)])
v = Voronoi(seeds)
ring = land_domain.buffer(80, quad_segs=3).difference(land_domain).intersection(world)
waters = {id: [] for id in coastal}
for i, id in enumerate(owners):
    region = v.regions[v.point_region[i]]
    if -1 in region:
        continue
    cell = Polygon([v.vertices[j] for j in region]).intersection(ring)
    waters[id].extend(part for part in get_parts(cell) if part.geom_type == "Polygon")
waters = {id: unary_union(cells) for id, cells in waters.items()}
actual = {
    tuple(sorted((a, b)))
    for i, a in enumerate(waters)
    for b in list(waters)[i + 1 :]
    if not waters[a].is_empty
    and not waters[b].is_empty
    and waters[a].boundary.intersection(waters[b].boundary).length > 2
}
expected = {tuple(sorted(p)) for p in rel["coast"]}
print("WATER missing", len(expected - actual), sorted(expected - actual))
print("WATER extra", len(actual - expected), sorted(actual - expected))

# Add narrow coastal-water channels where the schematic coast folds around a peninsula.
obstacles = prep(land_domain.buffer(3))
routing_basins = list(get_parts(world.difference(land_domain.buffer(12))))
step = 8
blocked = {}


def free(q):
    if q not in blocked:
        point = Point(q[0] * step, q[1] * step)
        blocked[q] = world.contains(point) and not obstacles.contains(point)
    return blocked[q]


def route(a, b):
    start = (round(a.x / step), round(a.y / step))
    end = (round(b.x / step), round(b.y / step))

    def nearby(q):
        return min(
            (
                (q[0] + i, q[1] + j)
                for i in range(-5, 6)
                for j in range(-5, 6)
                if free((q[0] + i, q[1] + j))
            ),
            key=lambda r: (r[0] - q[0]) ** 2 + (r[1] - q[1]) ** 2,
        )

    start, end = nearby(start), nearby(end)
    pending = [(0, start)]
    cost = {start: 0}
    prev = {}
    while pending:
        _, q = heapq.heappop(pending)
        if q == end:
            break
        for dx, dy in [(1, 0), (-1, 0), (0, 1), (0, -1)]:
            r = (q[0] + dx, q[1] + dy)
            if not free(r):
                continue
            nc = cost[q] + 1
            if nc >= cost.get(r, 1e9):
                continue
            cost[r] = nc
            prev[r] = q
            heapq.heappush(pending, (nc + abs(r[0] - end[0]) + abs(r[1] - end[1]), r))
    assert end in cost, "A coastal channel must be routable through water."
    points = [end]
    while points[-1] != start:
        points.append(prev[points[-1]])
    return LineString(
        [a.coords[0], *[(x * step, y * step) for x, y in reversed(points)], b.coords[0]]
    ).simplify(5)


def pairs(ws):
    boundaries = {id: shape.boundary for id, shape in ws.items() if not shape.is_empty}
    buffered = {id: boundary.buffer(0.02) for id, boundary in boundaries.items()}
    return {
        tuple(sorted((a, b)))
        for i, a in enumerate(boundaries)
        for b in list(boundaries)[i + 1 :]
        if buffered[a].intersection(boundaries[b]).length > 3
    }


for attempt in range(10):
    actual = pairs(waters)
    missing = expected - actual
    print(
        "repair",
        attempt,
        "missing",
        sorted(missing),
        "extra",
        len(actual - expected),
        flush=True,
    )
    for a, b in sorted(missing):
        print("Opening coast connection", a, b, flush=True)
        options = [
            (
                waters[a].buffer(-2).intersection(basin),
                waters[b].buffer(-2).intersection(basin),
            )
            for basin in routing_basins
        ]
        options = [
            (first, second)
            for first, second in options
            if not first.is_empty and not second.is_empty
        ]
        if not options:
            options = [(waters[a].buffer(-2), waters[b].buffer(-2))]
        first, second = min(options, key=lambda pair: pair[0].distance(pair[1]))
        pa, pb = nearest_points(first, second)
        channel = route(pa, pb)

        first = (
            substring(channel, 0, channel.length / 2)
            .buffer(17, cap_style=2, join_style=1)
            .difference(land_domain)
            .intersection(world)
        )
        second = (
            substring(channel, channel.length / 2, channel.length)
            .buffer(17, cap_style=2, join_style=1)
            .difference(land_domain)
            .intersection(world)
            .difference(first)
        )
        # Both halves touch along their common cross-section.
        footprint = first.union(second)
        for id in waters:
            waters[id] = waters[id].difference(footprint).buffer(0)
        waters[a] = waters[a].union(first).buffer(0)
        waters[b] = waters[b].union(second).buffer(0)
    actual = pairs(waters)
    gaps = unary_union(
        [
            waters[a].boundary.buffer(0.03).intersection(waters[b].boundary).buffer(6)
            for a, b in actual - expected
        ]
    )
    waters = {id: shape.difference(gaps).buffer(0) for id, shape in waters.items()}
    if pairs(waters) == expected:
        break
actual = pairs(waters)
print("FINAL coast missing", expected - actual, "extra", actual - expected, flush=True)

assert actual == expected, f"Coastal adjacency differs: {actual ^ expected}."
land, water, provinces, relations, contacts = shapes, waters, p, rel, seas

land_union = unary_union([*land.values(), terrain])
water_union = unary_union(list(water.values()))
# Narrow bays must still open into a sea instead of being sealed by neighboring
# coastal cells. Widen their mouth without changing the coastal adjacency graph.
for id in sorted({province for _, province in contacts}):
    open_water = world.difference(land_union.union(water_union))
    if water[id].boundary.intersection(open_water.buffer(0.01)).length >= 12:
        continue
    start, end = nearest_points(water[id], open_water)
    mouth = LineString([start, end]).buffer(8).difference(land_union)
    water = {
        province: shape.union(mouth) if province == id else shape.difference(mouth)
        for province, shape in water.items()
    }
    water_union = unary_union(list(water.values()))
assert pairs(water) == {
    tuple(sorted(pair)) for pair in relations["coast"]
}, "Opening coastal bays must preserve shipping adjacencies."
# These coastal spaces reach seas through their neighbors, never directly.
wrap = (
    water["aleppo"]
    .buffer(5)
    .union(water["taurus"].buffer(5))
    .difference(land_union.union(water_union))
    .intersection(world)
)
water["cyprus"] = water["cyprus"].union(wrap).buffer(0)
water_union = unary_union(list(water.values()))
sea_domain = world.difference(land_union.union(water_union))
sea_centers = {
    sea: display_point(*project(*point))
    for sea, point in {
        "GREENLAND_SEA": (-7, 59.5),
        "NORTH_SEA": (3, 56),
        "BALTIC_SEA": (19.5, 56.9),
        "BAY_OF_BISCAY": (-5, 45.7),
        "CANARY_BASIN": (-9, 34.5),
        "WESTERN_MEDITERRANEAN": (5.6, 39),
        "ADRIATIC_SEA": (15, 42.7),
        "CENTRAL_MEDITERRANEAN": (18, 35.2),
        "EASTERN_MEDITERRANEAN": (30, 33.5),
        "AEGEAN_SEA": (25.2, 38.3),
        "BLACK_SEA": (34, 43.3),
    }.items()
}

sea_colors = [
    "#94b8cf",
    "#8db5c9",
    "#9bc6cf",
    "#91bad1",
    "#7eafa9",
    "#82b4c7",
    "#9bc6d1",
    "#85bac4",
    "#96c9cc",
    "#85acbd",
    "#87aebf",
]
seeds = []
owners = []
seen = set()
for id, shape in water.items():
    allowed = sorted({sea for sea, p in contacts if p == id})
    shore = shape.boundary.intersection(sea_domain.buffer(0.005))
    if not allowed or shore.is_empty:
        continue
    samples = [shore.interpolate(i).coords[0] for i in range(0, int(shore.length), 5)]
    assignments = [
        min(allowed, key=lambda s: Point(sea_centers[s]).distance(Point(q)))
        for q in samples
    ]
    quota = max(1, min(8, len(samples) // len(allowed)))
    for sea in allowed:
        if assignments.count(sea) >= quota:
            continue
        candidates = sorted(
            range(len(samples)),
            key=lambda i: Point(samples[i]).distance(Point(sea_centers[sea])),
        )
        for i in candidates:
            if assignments.count(assignments[i]) > quota:
                assignments[i] = sea
            if assignments.count(sea) >= quota:
                break
    for q, sea in zip(samples, assignments):
        key = tuple(round(n, 5) for n in q)
        if key in seen:
            continue
        seen.add(key)
        seeds.append(q)
        owners.append(sea)
for sea, pos in sea_centers.items():
    seeds.append(pos)
    owners.append(sea)
seeds.extend([(-10000, -10000), (10000, -10000), (10000, 10000), (-10000, 10000)])
v = Voronoi(seeds)
sea_parts = {s: [] for s in sea_centers}
for i, sea in enumerate(owners):
    region = v.regions[v.point_region[i]]
    if -1 in region:
        continue
    part = Polygon([v.vertices[j] for j in region]).intersection(sea_domain)
    if not part.is_empty:
        sea_parts[sea].append(part)
seas = {s: unary_union(parts).buffer(0) for s, parts in sea_parts.items()}
expected = set(contacts)
# Remove numerical slivers at triple junctions; a corner is not a sea contact.
for sea, shape in list(seas.items()):
    forbidden = unary_union(
        [coast for id, coast in water.items() if (sea, id) not in expected]
    )
    seas[sea] = shape.difference(forbidden.buffer(0.15)).buffer(0)
actual = {
    (sea, id)
    for sea, shape in seas.items()
    for id, coast in water.items()
    if shape.boundary.buffer(0.02).intersection(coast.boundary).length > 3
}
assert (
    actual == expected
), f"Sea contacts differ: missing {expected-actual}, extra {actual-expected}."

# A single envelope groups the land and coastal components of each geographical area.
areas = {}
for area in ["I", "II", "III", "IV", "V", "VI", "VII", "VIII"]:
    group = unary_union(
        [shape for id, shape in land.items() if provinces[id]["area"] == area]
        + [shape for id, shape in water.items() if provinces[id]["area"] == area]
    )
    areas[area] = group.buffer(25, quad_segs=2).buffer(-25, quad_segs=2)
# Keep each inset and island group inside one enclosing area perimeter.
for area in ["II", "VIII"]:
    areas[area] = (
        areas[area]
        .convex_hull.intersection(world)
        .difference(land_union.union(water_union).difference(areas[area]))
        .union(areas[area])
    )
area_labels = {
    "I": (1700, -60),
    "II": (170, 330),
    "III": (1000, 700),
    "IV": (350, 1150),
    "V": (3050, 900),
    "VI": (2770, 1850),
    "VII": (1550, 1450),
    "VIII": (1200, 2015),
}
# Prevent overlapping area envelopes and inset the parallel boundary on each side.
for area, shape in list(areas.items()):
    other = unary_union([s for a, s in areas.items() if a != area])
    areas[area] = shape.difference(
        other.intersection(land_union.union(water_union).difference(shape))
    )


def svg_path(shape, outer_only=False):
    paths = []
    for poly in get_parts(shape):
        if poly.geom_type != "Polygon":
            continue
        for ring in [poly.exterior, *([] if outer_only else poly.interiors)]:
            pts = list(ring.coords)
            paths.append("M" + "L".join(f"{x:.2f} {y:.2f}" for x, y in pts[:-1]) + "Z")
    return "".join(paths)


def area_outline(shape):
    outline = shape.buffer(-5, quad_segs=2)
    parts = list(get_parts(outline))
    while len(parts) > 1:
        a, b = min(
            ((a, b) for i, a in enumerate(parts) for b in parts[i + 1 :]),
            key=lambda pair: pair[0].distance(pair[1]),
        )
        start, end = nearest_points(a, b)
        outline = outline.union(LineString([start, end]).buffer(6, quad_segs=2))
        parts = list(get_parts(outline))
    return outline


def area_geometry(area, shape):
    outline = area_outline(shape)
    badge = nearest_points(outline.exterior, Point(area_labels[area]))[0]
    badge = nearest_points(world.buffer(-40), badge)[0]
    min_x, min_y, max_x, max_y = world.bounds
    label = [
        max(min_x + 38, min(max_x - 38, badge.x)),
        max(min_y + 22, min(max_y - 22, badge.y)),
    ]
    return {"path": svg_path(outline, outer_only=True), "label": label}


# Overseas destinations are distinct boxes, with no implicit land or galley links.
for id, y in [
    ("north-america", 760),
    ("south-america", 950),
    ("east-indies", 1180),
    ("china", 1370),
    ("india", 1560),
]:
    land[id] = box(15, y - 66, 175, y + 66)
    positions[id] = (95, y)


def sea_label(sea, shape):
    nearby = shape.intersection(Point(sea_centers[sea]).buffer(280))
    largest = max(
        (p for p in get_parts(nearby) if p.geom_type == "Polygon"), key=lambda p: p.area
    )
    return list(polylabel(largest, tolerance=5).coords[0])


# Adjust crowded labels after subdivision so typography cannot change topology.
positions = {
    id: (
        point[0] + LABEL_OFFSETS.get(id, (0, 0))[0],
        point[1] + LABEL_OFFSETS.get(id, (0, 0))[1],
    )
    for id, point in positions.items()
}
min_x, min_y, max_x, max_y = world.bounds
result = {
    "bounds": [min_x, min_y, max_x - min_x, max_y - min_y],
    "frame": svg_path(world),
    "provinces": {
        id: {
            "path": svg_path(shape),
            "label": positions[id],
            "area": {"Far East": "FAR_EAST", "New World": "NEW_WORLD"}.get(
                provinces[id]["area"], provinces[id]["area"]
            ),
        }
        for id, shape in land.items()
    },
    "coasts": {id: svg_path(shape) for id, shape in water.items()},
    "areas": {area: area_geometry(area, shape) for area, shape in areas.items()},
    "seas": {
        sea: {
            "path": svg_path(shape),
            "label": sea_label(sea, shape),
            "color": sea_colors[i],
        }
        for i, (sea, shape) in enumerate(seas.items())
    },
    "terrain": svg_path(terrain),
}
Path("web/src/map/boardGeometry.json").write_text(
    json.dumps(result, separators=(",", ":")) + "\n"
)
print(
    "Exported",
    len(land),
    "provinces",
    len(water),
    "coastal spaces and",
    len(seas),
    "seas.",
    flush=True,
)
