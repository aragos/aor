"""Draw satellite support as short, straight crossings of the supported market's border."""

import math
from itertools import product

from shapely.geometry import LineString, Point, Polygon, box
from shapely.ops import linemerge, transform, unary_union


# Toulouse's accepted labels leave clear east/south exits. Cornwall–Chester has
# only a corner/gap, so its two drawings need explicitly placed crossings.
# Cagliari's coastal-water shafts need extra length to remain visible at Fit;
# extend Marseilles' source end to keep its arrowhead clear of the marker slot.
DIRECT_ROUTES = {
    ("cagliari", "marseilles"): [((1270.140, 1189.587), (1232.040, 1160.391))],
    ("cagliari", "genoa"): [((1323.001, 1151.561), (1292.374, 1114.602))],
    ("toulouse", "montpellier"): [((903, 1140), (935, 1140))],
    ("toulouse", "barcelona"): [((843, 1177), (843, 1216))],
    ("cornwall", "chester"): [
        ((705.884, 625.663), (712.483, 588.241)),
        ((701.037, 622.966), (701.037, 582.966)),
    ],
}


def arrow_footprint(path):
    """Reserve the shaft halo and the renderer's 18-unit-long, 20-unit-wide head."""
    start, end = map(Point, path.coords)
    dx, dy = (end.x - start.x) / path.length, (end.y - start.y) / path.length
    head = Polygon([
        (end.x, end.y),
        (end.x - 18 * dx - 10 * dy, end.y - 18 * dy + 10 * dx),
        (end.x - 18 * dx + 10 * dy, end.y - 18 * dy - 10 * dx),
    ])
    return path.buffer(4).union(head.buffer(2))


def border_crossings(source, target):
    """Try local straight crossings, keeping both halves inside their own province."""
    shared = source.boundary.buffer(.003).intersection(target.boundary)
    parts = [part for part in getattr(shared, "geoms", [shared])
             if part.geom_type == "LineString" and part.length > .01]
    if not parts:
        return
    border = linemerge(parts)
    source_interior, target_interior = source.buffer(-3), target.buffer(-3)
    source_clearance, target_clearance = source.buffer(.003), target.buffer(.003)
    for part in getattr(border, "geoms", [border]):
        count = max(2, math.ceil(part.length / 6))
        for index in range(count):
            distance = part.length * (index + .5) / count
            center = part.interpolate(distance)
            before = part.interpolate(max(0, distance - 1))
            after = part.interpolate(min(part.length, distance + 1))
            angle = math.atan2(after.y - before.y, after.x - before.x) + math.pi / 2
            variants = product([0, -.3, .3, -.6, .6, -.9, .9, -1.2, 1.2],
                               [20, 16, 24, 14], [1, -1])
            for rotation, half, sign in variants:
                dx, dy = math.cos(angle + rotation), math.sin(angle + rotation)
                start = Point(center.x - sign * half * dx, center.y - sign * half * dy)
                end = Point(center.x + sign * half * dx, center.y + sign * half * dy)
                if (not source_interior.covers(start) or not target_interior.covers(end)
                        or not source_clearance.covers(LineString([start, center]))
                        or not target_clearance.covers(LineString([center, end]))):
                    continue
                yield LineString([start, end]), abs(half - 20) + abs(distance - part.length / 2) / 20


def label_footprints(provinces):
    """Printed name and market-badge bounds in display-space SVG units."""
    return unary_union([
        box(value["label"][0] + value["labelBox"][0],
            value["label"][1] + value["labelBox"][1],
            value["label"][0] + value["labelBox"][0] + value["labelBox"][2],
            value["label"][1] + value["labelBox"][1] + value["labelBox"][3])
        for value in provinces.values()
    ])


def marker_footprints(provinces):
    """Conservative circles enclosing any capital silhouette in the normal marker slot."""
    return unary_union([
        Point(value["pieces"]["1"]["points"][0]).buffer(value["pieces"]["1"]["size"] * .72)
        for value in provinces.values()
    ])


def support_paths(relations, provinces, footprints, compact_easting):
    """Keep all catalogued directions; prefer clear, separated 40-unit border arrows.

    Names and normal marker slots keep priority. Dense multi-owner token layouts
    still paint above arrows. Automatic routes stay within the two province
    footprints; the authored Cornwall–Chester crossing bridges its tiny corner
    separator without a detour.
    """
    domains = {key: transform(lambda x, y, z=None: (compact_easting(x), y), shape)
               for key, shape in footprints.items()}
    labels, markers = label_footprints(provinces), marker_footprints(provinces)
    placed = Polygon()
    result = {}
    for source, target in relations:
        direct = [LineString(points) for points in DIRECT_ROUTES.get((source, target), [])]
        options = [(path, 0) for path in direct
                   if domains[source].covers(Point(path.coords[0]))
                   and domains[target].covers(Point(path.coords[-1]))]
        if not direct:
            options = list(border_crossings(domains[source], domains[target]))
        assert options, f"{source}–{target}: author a clear, straight support crossing."
        owned = domains[source].union(domains[target]).buffer(.003)

        def score(option):
            candidate, position_cost = option
            footprint = arrow_footprint(candidate)
            return (footprint.intersection(labels).area * 1000
                    + footprint.difference(owned).area * 500
                    + footprint.intersection(markers).area * 1000
                    + footprint.intersection(placed).area * 10
                    + position_cost)

        path, _ = min(options, key=score)
        placed = placed.union(arrow_footprint(path))
        result[f"{source} {target}"] = "M" + "L".join(
            f"{x:.3f} {y:.3f}" for x, y in path.coords
        )
    return result
