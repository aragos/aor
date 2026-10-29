"""Place directed support arrows clear of province names and the normal marker slot."""

import math

from shapely.geometry import LineString, Point, box
from shapely.ops import transform, unary_union


# Display-space crossings shared by the standard and European drawings. Toulouse's
# constrained label leaves clear east/south exits; automatic routing otherwise takes
# a long coastal detour or bends around Barcelona's name.
DIRECT_ROUTES = {
    ("toulouse", "montpellier"): ((903, 1140), (943, 1140)),
    ("toulouse", "barcelona"): ((843, 1177), (843, 1216)),
}


def support_paths(relations, provinces, footprints, compact_easting):
    """Return displayed SVG paths keyed by the catalog's ``satellite market`` pair.

    Endpoints remain inside their respective province. Score alternate straight
    crossings and bent crossings against the same label and piece bounds used by
    the renderer, rather than relying on a fixed offset from province centers.
    Authored crossings take precedence for constrained spaces with clear exits.
    """
    domains = {
        key: transform(lambda x, y, z=None: (compact_easting(x), y), shape)
        for key, shape in footprints.items()
    }
    labels = {
        key: box(
            value["label"][0] + value["labelBox"][0] - 12,
            value["label"][1] + value["labelBox"][1] - 12,
            value["label"][0] + value["labelBox"][0] + value["labelBox"][2] + 12,
            value["label"][1] + value["labelBox"][1] + value["labelBox"][3] + 12,
        )
        for key, value in provinces.items()
    }
    label_obstacles = unary_union(list(labels.values()))
    marker_obstacles = unary_union([
        Point(value["pieces"]["1"]["points"][0]).buffer(24)
        for value in provinces.values()
    ])
    candidates = {}
    for key, bounds in labels.items():
        ring = bounds.buffer(8, join_style=2).exterior
        points = [ring.interpolate(index / 24, normalized=True) for index in range(24)]
        points.extend(Point(point) for point in provinces[key]["pieces"]["12"]["points"])
        candidates[key] = [
            point for point in points
            if domains[key].covers(point.buffer(3)) and not label_obstacles.contains(point)
        ]
        assert candidates[key], f"{key}: support endpoints need clear province space."
    result = {}
    for source, target in relations:
        if (source, target) in DIRECT_ROUTES:
            direct = DIRECT_ROUTES[(source, target)]
            result[f"{source} {target}"] = "M" + "L".join(
                f"{x:.3f} {y:.3f}" for x, y in direct
            )
            continue
        pairs = sorted(
            ((a, b) for a in candidates[source] for b in candidates[target]
             if a.distance(b) >= 24),
            key=lambda pair: pair[0].distance(pair[1]),
        )
        assert pairs, f"{source}–{target}: support arrow needs visible length."
        options = [LineString([a, b]) for a, b in pairs]
        for a, b in pairs[:12]:
            dx, dy = b.x - a.x, b.y - a.y
            distance = math.hypot(dx, dy)
            for offset in [-80, -40, 40, 80]:
                middle = ((a.x + b.x) / 2 - dy / distance * offset,
                          (a.y + b.y) / 2 + dx / distance * offset)
                options.append(LineString([a, middle, b]))
        owned = domains[source].union(domains[target])
        best = min(options, key=lambda path: (
            path.intersection(label_obstacles).length * 1000
            + path.intersection(marker_obstacles).length * 30
            + path.difference(owned).length * 2 + path.length
        ))
        result[f"{source} {target}"] = "M" + "L".join(
            f"{x:.3f} {y:.3f}" for x, y in best.coords
        )
    return result
