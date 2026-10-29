#!/usr/bin/env python3
"""Build the geographic schematic from authored anchors and the reviewed connection catalog.

Run from any directory using the optional dependencies in map-requirements.txt.
The game never imports this script: browser geometry is a static, validated asset.

Anchors use SVG units, not latitude and longitude. Land cells meet at catalog edge
midpoints and face centers. Coastal cells divide the shoreline buffer; water
channels restore required contacts around peninsulas. Sea cells are seeded from
their catalogued coastal boundaries. Validate the exported asset after generation.
"""

import argparse
import heapq
import json
import math
import os
from pathlib import Path

from scipy.spatial import Voronoi
from shapely import get_num_coordinates, get_parts, set_precision, union_all
from shapely.affinity import rotate
from shapely.geometry import LineString, MultiLineString, Point, Polygon, box
from shapely.ops import (
    linemerge,
    nearest_points,
    polygonize,
    polylabel,
    substring,
    split,
    transform,
    unary_union,
)
from shapely.strtree import STRtree
from shapely.prepared import prep

from support_geometry import support_paths
from map_catalog import load_catalog

from map_geography import (
    EASTERN_COMPACTION_X,
    LABEL_OFFSETS,
    PROVINCE_ISLANDS,
    SCANDINAVIAN_INTERIOR,
    compact_easting,
    display_point,
    geography,
    project,
)

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--catalog", type=Path, help="Resolved JSON from :server:exportMapCatalog")
parser.add_argument("--output", type=Path, default=ROOT / "web/src/map/boardGeometry.json")
args = parser.parse_args()
catalog_path = args.catalog.resolve() if args.catalog else None
output_path = args.output.resolve()
os.chdir(ROOT)


p, rel, seas, sea_areas = load_catalog(catalog_path)

# Geographic coastlines are independent of the game graph. The graph partitions
# land; narrow neutral interiors separate real neighbors absent from the catalog.
world, physical_land, neutral, positions, routes = geography(p)
province_points = {
    id: (
        [Point(position)]
        + [Point(display_point(*project(*point))) for point in PROVINCE_ISLANDS[id]]
        if id in PROVINCE_ISLANDS
        else [Point(position)]
    )
    for id, position in positions.items()
}
inset_panel = box(0, 630, 190, 1650)
physical_land = physical_land.union(inset_panel)
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
    owners = [
        id
        for id, points in province_points.items()
        if any(cell.covers(point) for point in points)
    ]
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
# Extend the established Kongsberg–Malmo divider through the former neutral interior.
# Preserve Stockholm's existing boundary rather than assigning it any of the reclaimed land.
interior = transform(display_point, Polygon(SCANDINAVIAN_INTERIOR)).intersection(physical_land)
border = max(get_parts(shapes["kongsberg"].boundary.intersection(shapes["malmo"].boundary)),
             key=lambda part: part.length)
first, last = border.coords[0], border.coords[-1]
dx, dy = last[0] - first[0], last[1] - first[1]
length = math.hypot(dx, dy)
assert length > 0, "Kongsberg and Malmo need a shared divider before reclaiming their interior."
divider = LineString([
    (first[0] - dx / length * 5000, first[1] - dy / length * 5000),
    (last[0] + dx / length * 5000, last[1] + dy / length * 5000),
])
reclaimed = {key: [] for key in ["kongsberg", "malmo"]}
for part in get_parts(split(interior, divider)):
    owner = min(reclaimed, key=lambda key: shapes[key].distance(part.representative_point()))
    reclaimed[owner].append(part)
shapes = {
    key: shape.difference(interior).union(
        unary_union(reclaimed[key]) if key in reclaimed else Polygon()
    ).buffer(0)
    for key, shape in shapes.items()
}
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
            shapes[a].boundary.intersection(shapes[b].boundary).buffer(6, cap_style=2)
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
    shapes[id] = unary_union(
        [
            part
            for part in parts
            if any(part.covers(point) for point in province_points[id])
        ]
    )
# Make the sparse eastern graph clear even where geographic cells share only a corner.
shapes["kongsberg"] = shapes["kongsberg"].difference(shapes["stockholm"].buffer(12)).buffer(0)
for id in ["sarai", "kamyshin", "tana"]:
    neighbors = {b if a == id else a for a, b in rel["land"] if id in (a, b)}
    non_neighbors = unary_union(
        [shape for other, shape in shapes.items() if other not in neighbors | {id}]
    )
    shapes[id] = shapes[id].difference(non_neighbors.buffer(26)).buffer(0)
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
# Drop line remnants at the frame before the subpixel-precision Voronoi clipping.
ring = (
    land_domain.buffer(38, quad_segs=8)
    .difference(land_domain)
    .intersection(world)
    .buffer(0)
)
waters = {id: [] for id in coastal}
# Nearest-shore cells can reach neutral islands or inset panels far from their owner.
# Limit the initial band to its own land; only catalogued channel repairs may extend it.
coastal_reach = {id: shape.buffer(38, quad_segs=8) for id, shape in coastal.items()}
for i, id in enumerate(owners):
    region = v.regions[v.point_region[i]]
    if -1 in region:
        continue
    cell = (
        Polygon([v.vertices[j] for j in region])
        .intersection(ring, grid_size=0.001)
        .intersection(coastal_reach[id])
    )
    waters[id].extend(part for part in get_parts(cell) if part.geom_type == "Polygon")
waters = {id: unary_union(cells) for id, cells in waters.items()}
# Narrow the Adriatic-facing bands to leave a continuous, readable sea through the straits.
adriatic_window = box(
    *display_point(*project(11, 46.5)), *display_point(*project(21, 38))
)
adriatic_ring = land_domain.buffer(22, quad_segs=8).difference(land_domain)
waters = {
    id: shape.difference(adriatic_window.difference(adriatic_ring)).buffer(0)
    for id, shape in waters.items()
}
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
        for dx, dy in [
            (1, 0),
            (-1, 0),
            (0, 1),
            (0, -1),
            (1, 1),
            (1, -1),
            (-1, 1),
            (-1, -1),
        ]:
            r = (q[0] + dx, q[1] + dy)
            if not free(r):
                continue
            nc = cost[q] + math.hypot(dx, dy)
            if nc >= cost.get(r, 1e9):
                continue
            cost[r] = nc
            prev[r] = q
            heapq.heappush(pending, (nc + math.hypot(r[0] - end[0], r[1] - end[1]), r))
    assert end in cost, "A coastal channel must be routable through water."
    points = [end]
    while points[-1] != start:
        points.append(prev[points[-1]])
    points = [
        a.coords[0],
        *[(x * step, y * step) for x, y in reversed(points)],
        b.coords[0],
    ]
    # Pull the route into the fewest visible straight sections, rather than exposing grid stairs.
    visible = [points[0]]
    cursor = 0
    while cursor < len(points) - 1:
        target = next(
            index
            for index in range(len(points) - 1, cursor, -1)
            if index == cursor + 1
            or (
                world.covers(LineString([points[cursor], points[index]]))
                and not obstacles.intersects(
                    LineString([points[cursor], points[index]])
                )
            )
        )
        visible.append(points[target])
        cursor = target
    return LineString(visible)


def pairs(ws):
    boundaries = {id: shape.boundary for id, shape in ws.items() if not shape.is_empty}
    buffered = {id: boundary.buffer(0.02) for id, boundary in boundaries.items()}
    return {
        tuple(sorted((a, b)))
        for i, a in enumerate(boundaries)
        for b in list(boundaries)[i + 1 :]
        if buffered[a].intersection(boundaries[b]).length > 3
    }


def sea_connections(coasts, sea_regions):
    """Count shared borders, excluding corners and precision-width gaps."""
    return {
        (sea, province)
        for sea, shape in sea_regions.items()
        for province, coast in coasts.items()
        if shape.boundary.buffer(0.02).intersection(coast.boundary).length > 3
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
            .buffer(12, cap_style=2, join_style=1)
            .difference(land_domain)
            .intersection(world)
        )
        second = (
            substring(channel, channel.length / 2, channel.length)
            .buffer(12, cap_style=2, join_style=1)
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
# Point contacts must not visually imply routes absent from the reviewed catalog.
for first, second, clearance in [
    ("cagliari", "rome", 18),
    ("oran", "granada", 18),
    ("malmo", "lubeck", 24),
]:
    waters[first] = waters[first].difference(waters[second].buffer(clearance)).buffer(0)
# Visby's only neighboring coastal water is Stockholm. A near-touching band around
# another province must not look like a route when the complete board is fitted.
visby_non_neighbors = unary_union(
    [shape for province, shape in waters.items() if province not in {"visby", "stockholm"}]
)
waters["visby"] = waters["visby"].difference(visby_non_neighbors.buffer(24)).buffer(0)
assert (
    pairs(waters) == expected
), "Separating coastal non-neighbors must preserve coastal routes."
land, water, provinces, relations, contacts = shapes, waters, p, rel, seas

# Use one precision grid for both sides of every shoreline before assigning seas.
# Otherwise subpixel cracks are mistaken for sea-facing coast and grow spurious sea fingers.
land = {key: set_precision(shape, 0.001) for key, shape in land.items()}
terrain = set_precision(terrain, 0.001)
land_union = union_all([*land.values(), terrain], grid_size=0.001)
water = {
    key: set_precision(shape.difference(land_union), 0.001)
    for key, shape in water.items()
}
water_union = union_all(list(water.values()), grid_size=0.001)
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
# Cyprus fills the bay in front of Taurus and Aleppo. A simple bay front replaces
# thin perimeter strips around those coasts, which have no direct printed sea access.
bay = unary_union([water["cyprus"], water["aleppo"], water["taurus"]]).convex_hull
water["cyprus"] = (
    water["cyprus"]
    .union(bay.difference(land_union.union(water_union)).intersection(world))
    .buffer(0)
)
water["cyprus"] = water["cyprus"].difference(water["acre"].buffer(18)).buffer(0)
water_union = unary_union(list(water.values()))
assert pairs(water) == {
    tuple(sorted(pair)) for pair in relations["coast"]
}, "Bay fronts must preserve shipping adjacencies."
water = {key: set_precision(shape, 0.001) for key, shape in water.items()}
water_union = union_all(list(water.values()), grid_size=0.001)
sea_domain = set_precision(world, 0.001).difference(land_union.union(water_union))
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
        "AEGEAN_SEA": (25.2, 38.3),
        "EASTERN_MEDITERRANEAN": (30, 33.5),
        "BLACK_SEA": (34, 43.3),
    }.items()
    if sea in {name for name, _ in contacts}
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
actual = sea_connections(water, seas)
assert (
    actual == expected
), f"Sea contacts differ: missing {expected-actual}, extra {actual-expected}."


def clean_water_pockets(coasts, sea_regions, neutral_land):
    """Reassign detached water fragments when removal preserves every printed contact."""
    coasts, sea_regions = dict(coasts), dict(sea_regions)
    sea_contacts = {
        sea: {province for name, province in contacts if name == sea}
        for sea in sea_regions
    }
    coast_neighbors = {
        key: {b if a == key else a for a, b in relations["coast"] if key in (a, b)}
        for key in coasts
    }

    def touching_coasts(shape):
        boundary = shape.boundary.buffer(0.02)
        return {
            key
            for key, coast in coasts.items()
            if boundary.intersection(coast.boundary).length > 3
        }

    count = 0
    for _ in range(3):
        changed = False
        for sea in sea_regions:
            parts = sorted(
                get_parts(sea_regions[sea]), key=lambda part: part.area, reverse=True
            )
            for part in parts[1:]:
                if part.area < 0.05:
                    continue
                remaining = sea_regions[sea].difference(part)
                if touching_coasts(remaining) != sea_contacts[sea]:
                    continue
                bordering = touching_coasts(part)
                destinations = [
                    key
                    for key, shape in sea_regions.items()
                    if key != sea
                    and bordering <= sea_contacts[key]
                    and shape.boundary.buffer(0.02).intersection(part.boundary).length
                    > 3
                ]
                if destinations:
                    target = min(
                        destinations,
                        key=lambda key: part.distance(Point(sea_centers[key])),
                    )
                    sea_regions[target] = sea_regions[target].union(part).buffer(0)
                else:
                    destinations = sorted(
                        bordering,
                        key=lambda key: coasts[key]
                        .boundary.intersection(part.boundary)
                        .length,
                        reverse=True,
                    )
                    target = None
                    for key in destinations:
                        candidate = coasts[key].union(part).buffer(0)
                        boundary = candidate.boundary.buffer(0.02)
                        neighbors = {
                            other
                            for other, shape in coasts.items()
                            if other != key
                            and boundary.intersection(shape.boundary).length > 3
                        }
                        if neighbors != coast_neighbors[key]:
                            continue
                        actual_seas = {
                            name
                            for name, shape in sea_regions.items()
                            if boundary.intersection(
                                (remaining if name == sea else shape).boundary
                            ).length
                            > 3
                        }
                        expected_seas = {
                            name for name, province in contacts if province == key
                        }
                        if actual_seas == expected_seas:
                            target = key
                            coasts[key] = candidate
                            break
                    if target is None:
                        continue
                sea_regions[sea] = remaining
                changed = True
                count += 1
        if not changed:
            break
    coast_count = 0
    for key, shape in list(coasts.items()):
        for part in get_parts(shape):
            if part.area < 0.05 or part.distance(land[key]) < 0.02:
                continue
            remaining = coasts[key].difference(part)
            if touching_coasts(remaining) - {key} != coast_neighbors[key]:
                continue
            remaining_seas = {
                sea
                for sea, region in sea_regions.items()
                if region.boundary.buffer(0.02).intersection(remaining.boundary).length
                > 3
            }
            if remaining_seas != {sea for sea, province in contacts if province == key}:
                continue
            bordering = touching_coasts(part)
            destinations = [
                sea
                for sea, region in sea_regions.items()
                if bordering <= sea_contacts[sea]
                and region.boundary.buffer(0.02).intersection(part.boundary).length > 3
            ]
            if not destinations:
                if bordering == {key} and not any(
                    region.boundary.buffer(0.02).intersection(part.boundary).length > 3
                    for region in sea_regions.values()
                ):
                    # An isolated inland pool carries no movement or sea access.
                    neutral_land = neutral_land.union(part).buffer(0)
                    coasts[key] = remaining
                    coast_count += 1
                continue
            target = min(
                destinations, key=lambda sea: part.distance(Point(sea_centers[sea]))
            )
            sea_regions[target] = sea_regions[target].union(part).buffer(0)
            coasts[key] = remaining
            coast_count += 1
    assert pairs(coasts) == {
        tuple(sorted(pair)) for pair in relations["coast"]
    }, "Sea cleanup must preserve coastal connections."
    assert sea_connections(coasts, sea_regions) == set(
        contacts
    ), "Sea cleanup must preserve sea access."
    print(
        "Reassigned",
        count,
        "detached sea pockets and",
        coast_count,
        "detached coastal pockets without changing printed access.",
        flush=True,
    )
    return coasts, sea_regions, neutral_land


water, seas, terrain = clean_water_pockets(water, seas, terrain)
land_union = union_all([*land.values(), terrain], grid_size=0.001)


def smooth_water_arc(arc, tolerance):
    """Round sea-facing corners within half the SVG-unit tolerance, fixing arc endpoints."""
    points = list(arc.simplify(tolerance).coords)
    if len(points) < 3:
        return LineString(points)
    rounded = [points[0]]
    for previous, corner, following in zip(points, points[1:], points[2:]):
        before = math.dist(previous, corner)
        after = math.dist(corner, following)
        if before == 0 or after == 0:
            continue
        left = min(tolerance / 2, before / 4)
        right = min(tolerance / 2, after / 4)
        start = tuple(c + (p - c) * left / before for p, c in zip(previous, corner))
        end = tuple(c + (n - c) * right / after for c, n in zip(corner, following))
        rounded.append(start)
        for fraction in [0.25, 0.5, 0.75]:
            rounded.append(
                tuple(
                    (1 - fraction) ** 2 * a
                    + 2 * (1 - fraction) * fraction * b
                    + fraction**2 * c
                    for a, b, c in zip(start, corner, end)
                )
            )
        rounded.append(end)
    rounded.append(points[-1])
    return LineString(rounded)


def simplify_water_boundaries(coasts, sea_regions, focus=None):
    """Simplify shared arcs once, keeping shorelines, junctions, and every adjacency intact."""
    regions = {
        **{
            f"coast:{key}": set_precision(shape, 0.001) for key, shape in coasts.items()
        },
        **{
            f"sea:{key}": set_precision(shape, 0.001)
            for key, shape in sea_regions.items()
        },
    }
    keys, shapes = list(regions), list(regions.values())
    domain = unary_union(shapes)
    fixed = prep(domain.boundary.buffer(0.002))
    arcs = list(get_parts(linemerge(unary_union([shape.boundary for shape in shapes]))))
    focused = None
    if focus:
        focus_ids = sorted(focus)
        border = unary_union([coasts[key].boundary for key in focus_ids])
        near_border = prep(border.buffer(0.004))
        focused = {index for index, arc in enumerate(arcs) if near_border.covers(arc)}
    tree = STRtree(shapes)
    expected = pairs(regions)
    protected = set()
    tolerances = [40, 40, 24, 24, 12, 8, 4, 1] if focus else [12] * 8 + [8, 4, 1]
    for tolerance in tolerances:
        lines = [
            (
                arc
                if index in protected or fixed.covers(arc) or
                    (focused is not None and index not in focused)
                else arc.simplify(tolerance) if focus else smooth_water_arc(arc, tolerance)
            )
            for index, arc in enumerate(arcs)
        ]
        assigned = {key: [] for key in keys}
        for cell in polygonize(unary_union(lines)):
            if not domain.covers(cell.representative_point()):
                continue
            candidates = tree.query(cell, predicate="intersects")
            if not len(candidates):
                continue
            owner = max(
                candidates, key=lambda index: shapes[index].intersection(cell).area
            )
            assigned[keys[owner]].append(cell)
        result = {key: unary_union(cells).buffer(0) for key, cells in assigned.items()}
        if any(shape.is_empty or not shape.is_valid for shape in result.values()):
            print(
                "Smoothing rejected",
                tolerance,
                "empty/invalid",
                [
                    key
                    for key, shape in result.items()
                    if shape.is_empty or not shape.is_valid
                ],
                flush=True,
            )
            continue
        result = {
            key: shape.intersection(domain).buffer(0) for key, shape in result.items()
        }
        missing = domain.difference(unary_union(list(result.values())))
        # Recover rounding residue from its exact original owner. Assigning an entire
        # sliver by greatest overlap can extend a coast into an unrelated province.
        result = {
            key: shape.union(regions[key].intersection(missing)).buffer(0)
            for key, shape in result.items()
        }
        # Remove subpixel appendages while retaining shared edges and all printed contacts.
        result = {
            key: set_precision(shape, 0)
            .buffer(-0.005, join_style=2)
            .buffer(0.005, join_style=2)
            .intersection(shape)
            .buffer(0)
            for key, shape in result.items()
        }
        actual = pairs(result)
        if actual != expected:
            changed = actual ^ expected
            # Preserve local narrow junctions rather than retaining wiggles everywhere.
            junctions = unary_union(
                [
                    (regions if pair in expected else result)[pair[0]]
                    .boundary.buffer(0.02)
                    .intersection(
                        (regions if pair in expected else result)[pair[1]].boundary
                    )
                    for pair in sorted(changed)
                ]
            ).buffer(tolerance * 2 + 2)
            nearby = prep(junctions)
            previous_protected = len(protected)
            protected.update(
                index for index, arc in enumerate(arcs) if nearby.intersects(arc)
            )
            if len(protected) == previous_protected:
                boundaries = prep(
                    unary_union(
                        [regions[key].boundary for pair in changed for key in pair]
                    ).buffer(0.02)
                )
                protected.update(
                    index
                    for index, arc in enumerate(arcs)
                    if boundaries.intersects(arc)
                )
            print(
                "Protecting",
                len(protected),
                "water arcs at",
                len(changed),
                "narrow junctions.",
                flush=True,
            )
            continue
        split = [
            key
            for key in keys
            if sum(part.area > 0.05 for part in get_parts(result[key]))
            > sum(part.area > 0.05 for part in get_parts(regions[key]))
        ]
        if split:
            print("Smoothing split water regions", tolerance, split, flush=True)
            boundaries = prep(
                unary_union([regions[key].boundary for key in split]).buffer(0.02)
            )
            protected.update(
                index for index, arc in enumerate(arcs) if boundaries.intersects(arc)
            )
            continue
        print(
            "Simplified shared water boundaries at", tolerance, "SVG units.", flush=True
        )
        if focus:
            print("Focused coastal vertices:",
                  sum(get_num_coordinates(coasts[key]) for key in focus), "->",
                  sum(get_num_coordinates(result[f"coast:{key}"]) for key in focus), flush=True)
        return (
            {key: result[f"coast:{key}"] for key in coasts},
            {key: result[f"sea:{key}"] for key in sea_regions},
        )
    raise AssertionError(
        "Shared water boundaries must simplify without changing adjacency."
    )


def open_sea_frontages(coasts, sea_regions):
    """Give printed contacts visible mouths before smoothing and rounded export."""
    repair_domain = world.difference(
        land_union.union(unary_union(list(coasts.values())))
    )
    printed_contacts = set(contacts)
    sea_regions = {
        key: union_all([part for part in get_parts(shape) if part.area >= 0.05])
        for key, shape in sea_regions.items()
    }
    for sea, province in sorted(printed_contacts):
        frontage = (
            sea_regions[sea]
            .boundary.buffer(0.02)
            .intersection(coasts[province].boundary)
            .length
        )
        if frontage >= 8:
            continue
        interior = sea_regions[sea].buffer(-1)
        assert not interior.is_empty, f"Sea {sea} needs a visible interior."
        start, end = nearest_points(interior, coasts[province])
        assert (
            start.distance(end) < 40
        ), f"Sea {sea} must be geographically close to {province}."
        forbidden = unary_union(
            [
                shape
                for key, shape in coasts.items()
                if (sea, key) not in printed_contacts
            ]
        )
        mouth = (
            LineString([start, end])
            .buffer(10)
            .intersection(repair_domain)
            .difference(forbidden.buffer(0.05))
        )
        sea_regions = {
            key: (
                shape.union(mouth).buffer(0)
                if key == sea
                else shape.difference(mouth).buffer(0)
            )
            for key, shape in sea_regions.items()
        }
        print("Opened visible sea frontage", sea, province, flush=True)
    return sea_regions


def merge_detached_coast(coasts, province, recipient):
    """Move landless fragments into the surrounding coast before checking topology."""
    parts = list(get_parts(coasts[province]))
    attached = [part for part in parts if part.distance(land[province]) < 0.03]
    detached = [part for part in parts if part.distance(land[province]) >= 0.03]
    return {
        **coasts,
        province: unary_union(attached),
        recipient: union_all([coasts[recipient], *detached], grid_size=0.001),
    }


# Give Biscay a broad, continuous approach to Edinburgh's western coast. The former
# sea boundary left only a tiny mouth beside northern Ireland. Keep the northern
# Scottish coast in the Greenland Sea and stop well south of Iceland and Shetland.
biscay_front = seas["GREENLAND_SEA"].intersection(box(0, 225, 620, world.bounds[3]))
seas["BAY_OF_BISCAY"] = union_all([seas["BAY_OF_BISCAY"], biscay_front], grid_size=0.001)
seas["GREENLAND_SEA"] = seas["GREENLAND_SEA"].difference(biscay_front).buffer(0)

seas = open_sea_frontages(water, seas)
water, seas = simplify_water_boundaries(water, seas)
seas = open_sea_frontages(water, seas)

# Neighboring coastal water screens provinces from seas they do not adjoin.
# Give the separating front visible width rather than leaving a subpixel sea gap.
for province, sea, screen in [
    ("malmo", "NORTH_SEA", "copenhagen"),
    ("lubeck", "BALTIC_SEA", "stettin"),
    ("copenhagen", "BALTIC_SEA", "malmo"),
    ("paris", "BAY_OF_BISCAY", "st-malo"),
    ("chester", "BAY_OF_BISCAY", "armagh"),
    ("durazzo", "ADRIATIC_SEA", "bari"),
]:
    front = seas[sea].intersection(water[province].buffer(24))
    unclaimed = world.difference(
        unary_union([*land.values(), terrain, *water.values(), *seas.values()])
    )
    front = front.union(front.buffer(0.2).intersection(unclaimed))
    water[screen] = union_all([water[screen], front], grid_size=0.001)
    seas[sea] = seas[sea].difference(front).buffer(0)
# Join Copenhagen's new front through its existing coastal channels, retaining one
# connected coastal space without creating a new neighboring province.
attached = unary_union([
    part for part in get_parts(water["copenhagen"])
    if part.distance(land["copenhagen"]) < 0.03
])
detached = [part for part in get_parts(water["copenhagen"])
            if part.distance(land["copenhagen"]) >= 0.03]
for part in sorted(detached, key=attached.distance):
    start, end = nearest_points(attached, part)
    connector = LineString([start, end]).buffer(4).difference(land_union)
    attached = union_all([attached, part, connector], grid_size=0.001)
    water["copenhagen"] = union_all([water["copenhagen"], connector], grid_size=0.001)
    for province in water:
        if province != "copenhagen":
            water[province] = water[province].difference(connector).buffer(0)
    for sea in seas:
        seas[sea] = seas[sea].difference(connector).buffer(0)
# The new front can cut a tiny landless sliver off Lubeck's coastal band. It belongs
# to the enclosing Copenhagen front, rather than forming another apparent island.
water = merge_detached_coast(water, "lubeck", "copenhagen")
# Stettin's short detached patch serves no unique connection. Give it to the
# surrounding Copenhagen water and keep Stettin's coast attached to its mainland.
water = merge_detached_coast(water, "stettin", "copenhagen")
# Durazzo's screen can leave a tiny Bari fragment enclosed by Belgrade's coast.
water = merge_detached_coast(water, "bari", "belgrade")
seas = open_sea_frontages(water, seas)
assert pairs(water) == {
    tuple(sorted(pair)) for pair in relations["coast"]
}, f"Coastal fronts changed the reviewed connections: {pairs(water) ^ {tuple(sorted(pair)) for pair in relations['coast']}}."
assert sea_connections(water, seas) == set(contacts), (
    f"Coastal fronts changed sea contacts: {sea_connections(water, seas) ^ set(contacts)}."
)

water, seas = simplify_water_boundaries(
    water, seas, focus={"malmo", "copenhagen", "stettin", "lubeck"}
)
water, seas = simplify_water_boundaries(
    water, seas, focus={"armagh", "waterford", "edinburgh", "chester", "wales", "cornwall"}
)
water, seas = simplify_water_boundaries(
    water, seas, focus={"toulouse", "bordeaux", "montpellier", "constantinople",
                       "smyrna"}
)

# Keep the Canary Basin behind the Gibraltar coasts, with Granada facing only the
# Western Mediterranean. Absorb a trapped western-sea pocket into the same coast front.
gibraltar_gap = seas["CANARY_BASIN"].intersection(water["granada"].buffer(24))
western_pockets = unary_union(
    [
        part
        for part in get_parts(seas["WESTERN_MEDITERRANEAN"])
        if part.area < 1000 and part.distance(gibraltar_gap) < 1
    ]
)
seas["CANARY_BASIN"] = seas["CANARY_BASIN"].difference(gibraltar_gap).buffer(0)
seas["WESTERN_MEDITERRANEAN"] = (
    seas["WESTERN_MEDITERRANEAN"].difference(western_pockets).buffer(0)
)
unclaimed = world.difference(
    unary_union([*land.values(), terrain, *water.values(), *seas.values()])
)
front = gibraltar_gap.union(western_pockets)
front = front.union(front.buffer(0.2).intersection(unclaimed))
granada_front = front.intersection(seas["WESTERN_MEDITERRANEAN"].buffer(8))
if not granada_front.is_empty:
    start, end = nearest_points(water["granada"], granada_front)
    connector = (
        LineString([start, end])
        .buffer(2)
        .intersection(water["fez"].union(front).union(unclaimed))
    )
    granada_front = granada_front.union(connector)
water["granada"] = union_all([water["granada"], granada_front], grid_size=0.001)
water["fez"] = (
    union_all([water["fez"], front], grid_size=0.001)
    .difference(granada_front)
    .buffer(0)
)
# Keep the new Gibraltar front clear of Oran as well as of the Canary Basin.
oran_gap = water["granada"].intersection(water["oran"].buffer(18))
water["granada"] = water["granada"].difference(oran_gap).buffer(0)
western_forbidden = unary_union(
    [
        shape
        for province, shape in water.items()
        if ("WESTERN_MEDITERRANEAN", province) not in set(contacts)
    ]
)
seas["WESTERN_MEDITERRANEAN"] = (
    seas["WESTERN_MEDITERRANEAN"]
    .union(oran_gap)
    .difference(western_forbidden.buffer(0.15))
    .buffer(0)
)
assert pairs(water) == {
    tuple(sorted(pair)) for pair in relations["coast"]
}, "Gibraltar's coast front must preserve coastal routes."
assert sea_connections(water, seas) == set(
    contacts
), "Gibraltar's coast front must preserve sea access."

# Numerical remnants smaller than one SVG square unit cannot represent a game space.
water = {
    province: union_all([part for part in get_parts(shape) if part.area >= 1])
    for province, shape in water.items()
}
seas = {
    sea: union_all([part for part in get_parts(shape) if part.area >= 1])
    for sea, shape in seas.items()
}
assert pairs(water) == {
    tuple(sorted(pair)) for pair in relations["coast"]
}, "Removing drawing remnants must preserve coastal routes."
assert sea_connections(water, seas) == set(
    contacts
), "Removing drawing remnants must preserve sea access."

# Copenhagen includes the complete Danish islands, including enclosed neutral remnants.
# Match the province-outline crack closure so only genuine enclosed pockets are absorbed.
copenhagen_footprint = (
    land["copenhagen"].union(water["copenhagen"])
    .buffer(0.005, join_style=2)
    .buffer(-0.005, join_style=2)
)
copenhagen_enclaves = unary_union([
    Polygon(ring)
    for part in get_parts(copenhagen_footprint)
    for ring in part.interiors
])
other_spaces = unary_union([
    *[shape for key, shape in land.items() if key != "copenhagen"],
    *[shape for key, shape in water.items() if key != "copenhagen"],
    *seas.values(),
])
assert copenhagen_enclaves.intersection(other_spaces).area < 0.1, (
    "Copenhagen may absorb only enclosed neutral land or unclaimed water."
)
enclosed_land = terrain.intersection(copenhagen_enclaves)
land["copenhagen"] = union_all([land["copenhagen"], enclosed_land], grid_size=0.001)
terrain = terrain.difference(enclosed_land).buffer(0)
water["copenhagen"] = union_all([
    water["copenhagen"], copenhagen_enclaves.difference(land_union),
], grid_size=0.001)

# Area outlines enclose member spaces, including neutral islands and intervening unassigned water.
# Close subpixel shoreline cracks before stroking. Reset the precision model first so the
# two buffer operations cannot collapse small, legitimate coastal components on the grid.
footprints = {
    id: union_all(
        [
            set_precision(shape, 0),
            set_precision(water[id], 0) if id in water else Polygon(),
        ],
        grid_size=0.001,
    )
    .buffer(0.005, join_style=2)
    .buffer(-0.005, join_style=2)
    for id, shape in land.items()
}
areas = {
    area: union_all(
        [shape for id, shape in footprints.items() if provinces[id]["area"] == area]
        + [
            shape
            for sea, shape in seas.items()
            if sea in sea_areas and sea_areas[sea] == area
        ],
        grid_size=0.001,
    )
    for area in ["I", "II", "III", "IV", "V", "VI", "VII", "VIII"]
}


def enclose_areas(cores):
    """Create one border per area without enclosing another area's province or assigned sea."""
    result = {}
    for area in sorted(cores, key=lambda key: len(get_parts(cores[key]))):
        forbidden = unary_union(
            [shape for other, shape in {**cores, **result}.items() if other != area]
        )
        core = cores[area].buffer(0.02).buffer(-0.02)
        shape = unary_union([Polygon(part.exterior) for part in get_parts(core)])
        shape = shape.difference(forbidden).buffer(0)
        parts = [part for part in get_parts(shape) if part.area > 1]
        assert len(parts) == 1, f"Area {area} must contain its member spaces."
        outline = Polygon(parts[0].exterior).difference(forbidden).buffer(0)
        assert (
            outline.geom_type == "Polygon" and not outline.interiors
        ), f"Area {area} cannot have enclaves or exclaves."
        result[area] = outline
    return result


areas = enclose_areas(areas)


def svg_path(shape, outer_only=False):
    paths = []
    for poly in get_parts(shape):
        if poly.geom_type != "Polygon":
            continue
        for ring in [poly.exterior, *([] if outer_only else poly.interiors)]:
            pts = []
            coordinates = list(ring.coords)
            for first, second in zip(coordinates, coordinates[1:]):
                pts.append((compact_easting(first[0]), first[1]))
                # Node every crossing before the piecewise projection, so both sides of
                # a shared boundary keep exactly the same kink and remain adjacent.
                if (first[0] - EASTERN_COMPACTION_X) * (
                    second[0] - EASTERN_COMPACTION_X
                ) < 0:
                    fraction = (EASTERN_COMPACTION_X - first[0]) / (
                        second[0] - first[0]
                    )
                    pts.append(
                        (
                            EASTERN_COMPACTION_X,
                            first[1] + fraction * (second[1] - first[1]),
                        )
                    )
            paths.append("M" + "L".join(f"{x:.3f} {y:.3f}" for x, y in pts) + "Z")
    return "".join(paths)


def area_geometry(area, shape):
    """Place the badge inside clear space, favoring water near the area's center."""
    clear = shape.buffer(-60).difference(label_clearance)
    water_space = clear.intersection(unary_union(list(seas.values())))
    candidates = [
        polylabel(part, tolerance=5) for part in get_parts(water_space) if part.area > 1
    ]
    candidates = [
        point for point in candidates if water_space.boundary.distance(point) >= 36
    ]
    if not candidates:
        candidates = [
            polylabel(part, tolerance=5) for part in get_parts(clear) if part.area > 1
        ]
    if not candidates:
        candidates = [polylabel(shape, tolerance=5)]
    badge = max(
        candidates,
        key=lambda point: clear.boundary.distance(point)
        - point.distance(shape.centroid) * 0.1,
    )
    return {
        "path": svg_path(shape),
        "label": [compact_easting(badge.x), badge.y],
    }


# Overseas destinations are distinct boxes, with no implicit land or galley links.
for id, y in [
    ("north-america", 760),
    ("south-america", 950),
    ("east-indies", 1180),
    ("china", 1370),
    ("india", 1560),
]:
    land[id] = box(15, y - 66, 175, y + 66)
    footprints[id] = land[id]
    positions[id] = (95, y)


SEA_LABEL_ANGLES = {"ADRIATIC_SEA": 35}


def sea_label(sea, shape):
    nearby = shape.intersection(Point(sea_centers[sea]).buffer(280))
    largest = max(
        (p for p in get_parts(nearby) if p.geom_type == "Polygon"), key=lambda p: p.area
    )
    x, y = polylabel(largest, tolerance=5).coords[0]
    if sea in SEA_LABEL_ANGLES:
        # Follow the narrow Adriatic channel. Include the 20-unit name's bold face and halo
        # in the fitting box so province highlights cannot obscure the selected sea name.
        min_x, min_y, max_x, max_y = largest.bounds
        candidates = [
            Point(px, py)
            for px in range(math.ceil(min_x), math.floor(max_x), 4)
            for py in range(math.ceil(min_y), math.floor(max_y), 4)
            if largest.covers(rotate(
                box(px - 67, py - 22, px + 67, py + 6),
                SEA_LABEL_ANGLES[sea], origin=(px, py),
            ))
        ]
        assert candidates, f"Sea {sea} needs room for its complete name."
        x, y = min(candidates, key=Point(x, y).distance).coords[0]
    return [x, y]


def province_label(id):
    """Fit the name and market badge in land first, then within the province's own coast.

    Bounds use the renderer's SVG font and badge dimensions. Invert the eastern compression
    for horizontal text extents because the text itself keeps its displayed font size.
    """
    offset = LABEL_OFFSETS[id] if id in LABEL_OFFSETS else (0, 0)
    authored = Point(positions[id][0] + offset[0], positions[id][1] + offset[1])
    mainland = min(
        get_parts(land[id]), key=lambda part: part.distance(Point(positions[id]))
    )
    name = id.replace("-", " ").title()
    words = name.split() if len(name) > 14 else [name]
    commodities = (
        0
        if provinces[id]["commodity"] == "—"
        else len(provinces[id]["commodity"].split(","))
    )
    half_width = max(max(map(len, words)) * 9.5 + 8, 40 + commodities * 27) / 2
    top = 44 + (len(words) - 1) * 20

    def expand_x(x):
        return (
            x
            if x <= EASTERN_COMPACTION_X
            else EASTERN_COMPACTION_X + (x - EASTERN_COMPACTION_X) / 0.7
        )

    def label_box(point):
        x = compact_easting(point.x)
        return box(
            expand_x(x - half_width),
            point.y - top,
            expand_x(x + half_width),
            point.y + 24,
        )

    min_x, min_y, max_x, max_y = mainland.bounds
    step_x = max(8, (max_x - min_x) / 45)
    step_y = max(8, (max_y - min_y) / 45)
    grid = [
        Point(min_x + x * step_x, min_y + y * step_y)
        for x in range(1, math.ceil((max_x - min_x) / step_x))
        for y in range(1, math.ceil((max_y - min_y) / step_y))
    ]
    points = [
        authored,
        polylabel(mainland, tolerance=3),
        mainland.centroid,
        mainland.representative_point(),
        *grid,
    ]
    candidates = sorted(
        [point for point in points if mainland.covers(point)], key=authored.distance
    )
    for fit, domain in [("LAND", mainland), ("PROVINCE", footprints[id])]:
        # Coastal water belongs to the province too. Search its interior instead of
        # requiring every label anchor to remain on the original land polygon.
        if fit == "PROVINCE":
            west, north, east, south = domain.bounds
            coast_candidates = [
                Point(x, y)
                for x in range(math.ceil(west), math.floor(east), 8)
                for y in range(math.ceil(north), math.floor(south), 8)
            ]
            candidates = sorted([*candidates, *coast_candidates], key=authored.distance)
        candidate = next(
            (point for point in candidates if domain.covers(label_box(point))), None
        )
        if candidate is not None:
            return {
                "point": candidate.coords[0],
                "fit": fit,
                "box": [-half_width, -top, half_width * 2, top + 24],
            }
    candidate = min(
        candidates,
        key=lambda point: (
            label_box(point).difference(footprints[id]).area,
            point.distance(authored),
        ),
    )
    return {
        "point": candidate.coords[0],
        "fit": "CONSTRAINED",
        "box": [-half_width, -top, half_width * 2, top + 24],
    }


def expand_easting(x):
    """Undo displayed eastern compression for unscaled text and piece extents."""
    return (
        x
        if x <= EASTERN_COMPACTION_X
        else EASTERN_COMPACTION_X + (x - EASTERN_COMPACTION_X) / 0.7
    )


def piece_layouts(id, label):
    """Pack up to two token ages for each capital, avoiding the province's labels.

    Prefer a complete group on land, then within its own land/coast footprint. Only
    shrink crowded groups after trying both domains at the normal readable size.
    Points and sizes are exported in displayed SVG units; they remain stable on polling.
    """
    x, y = label["point"]
    left, top, width, height = label["box"]
    label_box = box(
        expand_easting(compact_easting(x) + left - 3),
        y + top - 3,
        expand_easting(compact_easting(x) + left + width + 3),
        y + top + height + 3,
    )
    footprint = footprints[id]
    min_x, min_y, max_x, max_y = footprint.bounds
    step_x = max(6, (max_x - min_x) / 55)
    step_y = max(6, (max_y - min_y) / 55)
    preferred = Point(x, y + 43)
    candidates = sorted(
        [
            Point(min_x + col * step_x, min_y + row * step_y)
            for col in range(1, math.ceil((max_x - min_x) / step_x))
            for row in range(1, math.ceil((max_y - min_y) / step_y))
            if footprint.covers(Point(min_x + col * step_x, min_y + row * step_y))
        ],
        key=preferred.distance,
    )
    result = {}
    # A crowded footprint may need to share label space. Keep every piece within
    # its owner even then; inspection retains the full owner, age, and count list.
    for crowded in [False, True]:
        for size in [32, 27, 24, 20, 16, 14, 12]:
            half = size * 28 / 48
            for fit, domain in [("LAND", land[id]), ("PROVINCE", footprint)]:
                slots, boxes = [], []
                for point in candidates:
                    center_x = compact_easting(point.x)
                    bounds = box(
                        expand_easting(center_x - half),
                        point.y - half,
                        expand_easting(center_x + half),
                        point.y + half,
                    )
                    if (
                        not domain.covers(bounds)
                        or (not crowded and bounds.intersects(label_box))
                        or any(bounds.intersects(other) for other in boxes)
                    ):
                        continue
                    slots.append([center_x, point.y])
                    boxes.append(bounds)
                    if len(slots) == 12:
                        break
                for count in range(1, len(slots) + 1):
                    # A single marker is larger than a token; groups start at 27.
                    if count > 1 and size == 32:
                        continue
                    if str(count) not in result:
                        result[str(count)] = {
                            "size": size,
                            "fit": fit,
                            "crowded": crowded,
                            "points": slots[:count],
                        }
                if len(result) == 12:
                    return result
    assert (
        len(result) == 12
    ), f"{id}: every legal mixed token group must fit its province."
    return result


label_layouts = {id: province_label(id) for id in land}
piece_placements = {id: piece_layouts(id, label) for id, label in label_layouts.items()}


def joint_label_layout(id, label, pieces):
    """Try nearby label positions before accepting dense-group sharing or tiny pieces.

    This only changes presentation. Province geometry and every adjacency remain fixed.
    The renderer progressively discloses label details when sharing is still unavoidable.
    """
    if not any(layout["crowded"] for layout in pieces.values()):
        return label, pieces
    x, y = label["point"]
    left, top, width, height = label["box"]

    def bounds(px, py):
        sx = compact_easting(px)
        return box(expand_easting(sx + left), py + top,
                   expand_easting(sx + left + width), py + top + height)

    domain = land[id] if label["fit"] == "LAND" else footprints[id]
    overflow = bounds(x, y).difference(domain).area + 0.001

    def score(layouts, distance):
        return (sum(layout["crowded"] for layout in layouts.values()),
                -min(layout["size"] for layout in layouts.values()),
                -sum(layout["size"] for layout in layouts.values()), distance)

    best_label, best_pieces, best_score = label, pieces, score(pieces, 0)
    for dx, dy in [(dx, dy) for dx in [-24, 0, 24] for dy in [-24, 0, 24] if dx or dy]:
        point = (x + dx, y + dy)
        if not land[id].covers(Point(point)) or bounds(*point).difference(domain).area > overflow:
            continue
        candidate = {**label, "point": point}
        layouts = piece_layouts(id, candidate)
        candidate_score = score(layouts, math.hypot(dx, dy))
        if candidate_score < best_score:
            best_label, best_pieces, best_score = candidate, layouts, candidate_score
    return best_label, best_pieces


for id, label in label_layouts.items():
    label_layouts[id], piece_placements[id] = joint_label_layout(id, label, piece_placements[id])
positions = {id: layout["point"] for id, layout in label_layouts.items()}
sea_labels = {sea: sea_label(sea, shape) for sea, shape in seas.items()}
label_clearance = unary_union(
    [Point(point).buffer(85) for point in positions.values()]
    + [point.buffer(85) for points in province_points.values() for point in points[1:]]
    + [box(x - 110, y - 50, x + 110, y + 50) for x, y in sea_labels.values()]
)

# Adjust crowded labels after subdivision so typography cannot change topology.
positions = {
    id: (
        compact_easting(point[0]),
        point[1],
    )
    for id, point in positions.items()
}
min_x, min_y, max_x, max_y = world.bounds
result = {
    "bounds": [min_x, min_y, compact_easting(max_x) - min_x, max_y - min_y],
    "frame": svg_path(world),
    "provinces": {
        id: {
            "path": svg_path(shape),
            "outline": svg_path(footprints[id]),
            "label": positions[id],
            "labelFit": label_layouts[id]["fit"],
            "labelBox": label_layouts[id]["box"],
            "pieces": piece_placements[id],
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
            "label": [compact_easting(sea_labels[sea][0]), sea_labels[sea][1]],
            "labelAngle": SEA_LABEL_ANGLES[sea] if sea in SEA_LABEL_ANGLES else 0,
            "color": sea_colors[i],
            "area": sea_areas[sea] if sea in sea_areas else None,
        }
        for i, (sea, shape) in enumerate(seas.items())
    },
    "terrain": svg_path(terrain),
}
result["supports"] = support_paths(rel["supports"], result["provinces"], footprints, compact_easting)
output_path.write_text(
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
