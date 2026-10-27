"""Geographic reference and authored adjustments for the playable board.

Coastlines are simplified Natural Earth 1:50m land. Province anchors are approximate
regional centers, adjusted for readable labels and the game's coastal incidence.
The reference projection uses 55 SVG units per longitude degree and 65 per latitude
degree, then compresses longitude to 50 units and reserves an inset column.
The exported geometry also compresses SVG eastings beyond x=2500 by 30%,
keeping land, sea, and anchors in the same compact projection.
It is a schematic, not a navigation map. Iceland and West Africa are insets.
"""

import json
from pathlib import Path

from shapely import get_parts
from shapely.affinity import scale, translate
from shapely.geometry import LineString, Point, Polygon, box, shape
from shapely.ops import nearest_points, transform, unary_union

REGIONS = {
    "iceland": (-10, 60),
    "shetland-islands": (-1.5, 60.1),
    "bergen": (5.3, 60.3),
    "kongsberg": (9.5, 58.8),
    "malmo": (13.0, 55.6),
    "stockholm": (18.1, 59.3),
    "visby": (18.3, 57.4),
    "copenhagen": (9.5, 56.5),
    "hamburg": (9.5, 53.5),
    "lubeck": (11.3, 53.9),
    "stettin": (14.6, 53.5),
    "danzig": (18.6, 54.3),
    "mitau": (23.1, 56.5),
    "riga": (24.1, 57.3),
    "novgorod": (30.8, 59.4),
    "smolensk": (32.0, 55.0),
    "edinburgh": (-3.5, 56),
    "york": (-1.0, 54),
    "chester": (-2.8, 53.2),
    "london": (0.5, 51.6),
    "portsmouth": (-1, 50.5),
    "cornwall": (-4.8, 50.2),
    "wales": (-4.5, 52.3),
    "armagh": (-6.5, 54.8),
    "waterford": (-7.9, 52.4),
    "amsterdam": (4.9, 52.4),
    "bruges": (3.3, 50.9),
    "cologne": (7.0, 50.8),
    "nuremberg": (11.1, 49.6),
    "strasbourg": (7.7, 48.5),
    "basel": (7.4, 47.0),
    "st-gall": (9.8, 47.3),
    "salzburg": (13.1, 47.8),
    "prague": (14.5, 50.2),
    "breslau": (17.2, 51.2),
    "vienna": (16.4, 48.2),
    "budapest": (19.5, 47.7),
    "esseg": (18.5, 45.8),
    "paris": (2.2, 48.8),
    "st-malo": (-2.5, 48.5),
    "dijon": (4.2, 46.8),
    "bordeaux": (-0.5, 45),
    "toulouse": (-0.7, 43.9),
    "montpellier": (3.3, 43.5),
    "lyons": (5.0, 45.3),
    "marseilles": (5.4, 43.3),
    "milan": (9.2, 45.6),
    "genoa": (8.9, 44.2),
    "venice": (12.6, 45.4),
    "florence": (11.4, 43.6),
    "rome": (12.8, 41.7),
    "naples": (15.0, 40.5),
    "bari": (16.7, 39.6),
    "cagliari": (9.0, 40.0),
    "sicily": (14.0, 37.4),
    "dubrovnik": (16.5, 43.0),
    # Extend the schematic province to its reviewed Adriatic shore between its neighbors.
    "belgrade": (19.0, 43.4),
    "leon": (-6.1, 42.6),
    "basque": (-2.2, 43.0),
    "lisbon": (-8.4, 39.4),
    "toledo": (-4.0, 40.0),
    "barcelona": (1.4, 41.5),
    "valencia": (-0.4, 39.2),
    "palma": (3.0, 39.5),
    "seville": (-6.0, 37.4),
    "granada": (-3.0, 37.3),
    "west-africa": (-10.2, 32),
    "fez": (-5.0, 34.5),
    "oran": (-0.7, 35.5),
    "algiers": (3.2, 36.5),
    "tunis": (10.0, 36.0),
    "tripoli": (13.2, 32.5),
    "barca": (20.3, 32.5),
    "libya": (25.0, 31.5),
    "alexandria": (29.3, 31.0),
    "cairo": (31.6, 31.0),
    "suez": (33.5, 31.0),
    "jerusalem": (35.3, 32),
    "acre": (35.5, 33.7),
    "levant": (36.5, 35.1),
    "aleppo": (37.5, 36.8),
    "taurus": (34.5, 37.2),
    "adalia": (31.0, 36.7),
    "cyprus": (33.2, 34.7),
    "crete": (25, 34.8),
    "athens": (23.5, 37.5),
    "corfu": (20.3, 39.2),
    "salonika": (23, 40.7),
    "durazzo": (19.2, 41.5),
    "gallipoli": (26, 41.5),
    "constantinople": (29.6, 40.7),
    "smyrna": (27.4, 38.7),
    "kiev": (30.5, 50.4),
    "kaffa": (33.5, 45.7),
    "varna": (28.0, 43.5),
    "tana": (38.5, 46.1),
    "kamyshin": (44.8, 50.1),
    "sarai": (44.8, 52.3),
    "abagia": (39.5, 44.2),
    "poti": (42, 42.7),
    "erzerum": (42, 40.2),
    "trebizond": (39.2, 41.0),
    "angora": (33, 40.5),
}

LABEL_OFFSETS = {
    "milan": (0, -18),
    "genoa": (0, 6),
    "london": (10, -6),
    "portsmouth": (-8, 4),
}

# Additional land components belonging to an existing province, in longitude/latitude.
PROVINCE_ISLANDS = {
    "copenhagen": [(10.3, 55.3), (11.7, 55.6)],
    "cagliari": [(9.1, 42.2)],
    "athens": [(23.8, 38.6)],
}

# Authored Scandinavian interior in pre-display SVG units. It is now divided between
# Kongsberg and Malmo; its outline also supplies an independent ownership check.
SCANDINAVIAN_INTERIOR = [
    (1300, -60), (1500, -60), (1540, 240),
    (1480, 300), (1420, 310), (1300, 250),
]


def project(lon, lat):
    return ((lon + 13) * 55, (61 - lat) * 65)


# SVG units before final export; keep every crossing at this same longitude.
EASTERN_COMPACTION_X = 2500


def compact_easting(x):
    """SVG easting: preserve the west and compress the eastern frontier by 30%."""
    return min(x, EASTERN_COMPACTION_X) + max(0, x - EASTERN_COMPACTION_X) * 0.7


def display_point(x, y):
    """Reserve the western inset column; final eastern compaction happens at export."""
    return (x * 10 / 11 + 200, y)


def geography():
    """Return the frame, physical land, neutral interiors, labels, and land-edge bends."""
    reference = json.loads(
        Path(__file__).with_name("geographic-coastlines.json").read_text()
    )
    positions = {key: project(*point) for key, point in REGIONS.items()}
    frame = Polygon(
        [
            (0, -60),
            (2300, -60),
            (2590, 200),
            (2580, 500),
            (2520, 780),
            (2800, 750),
            (2960, 480),
            (3230, 480),
            (3280, 780),
            (3170, 1120),
            (3160, 1400),
            (2930, 1510),
            (2800, 1750),
            (2770, 2110),
            (2250, 2090),
            (2050, 2100),
            (1700, 2150),
            (1470, 2080),
            (1280, 1810),
            (1050, 1750),
            (850, 1760),
            (620, 1810),
            (400, 1850),
            (300, 1850),
            (300, 2110),
            (0, 2110),
        ]
    )
    land = transform(project, shape(reference["land"]))
    iceland = transform(project, shape(reference["iceland"]))
    iceland = scale(iceland, xfact=0.42, yfact=0.42, origin="centroid")
    iceland = translate(
        iceland, xoff=200 - iceland.centroid.x, yoff=100 - iceland.centroid.y
    )
    positions["iceland"] = (200, 100)
    west_africa = Polygon(
        [
            (60, 1820),
            (155, 1810),
            (195, 1840),
            (240, 1895),
            (260, 2080),
            (50, 2080),
            (50, 1930),
            (25, 1900),
        ]
    )
    positions["west-africa"] = (145, 1940)
    land = unary_union([land, iceland, west_africa]).intersection(frame).simplify(1.5)
    parts = list(get_parts(land))
    # Small islands retain their silhouettes but are enlarged for the value badge.
    for key, factors in [
        ("palma", (2.5, 2.5)),
        ("visby", (2.1, 1.5)),
        ("cyprus", (1.4, 1.8)),
        ("crete", (1, 2.5)),
        ("shetland-islands", (3, 2.5)),
    ]:
        position = Point(positions[key])
        original = min(parts, key=lambda part: part.distance(position))
        parts.remove(original)
        enlarged = scale(
            original, xfact=factors[0], yfact=factors[1], origin="centroid"
        )
        parts.append(
            translate(
                enlarged,
                xoff=position.x - enlarged.centroid.x,
                yoff=position.y - enlarged.centroid.y,
            )
        )
    land = unary_union([part for part in parts if part.area > 1000])
    land = land.difference(box(0, 1800, 300, 2120)).union(west_africa)
    straits = unary_union(
        [
            LineString(
                [
                    project(*point)
                    for point in [
                        (25.9, 39.9),
                        (26.2, 40.15),
                        (26.7, 40.45),
                        (27, 40.5),
                    ]
                ]
            ).buffer(12),
            LineString(
                [
                    project(*point)
                    for point in [(28.9, 40.8), (29, 41.05), (29.15, 41.3)]
                ]
            ).buffer(12),
        ]
    )
    land = land.difference(straits)
    # Simplify the Bristol estuary at board scale so Wales and Cornwall retain
    # their printed land border without disconnected pieces of either province.
    land = land.union(LineString([project(-3.8, 51.6), project(-3.7, 51.1)]).buffer(14))
    # Non-playing interiors separate graph branches that share a real landmass.
    # Strait widths are exaggerated to keep the separate coastal spaces readable.
    neutral = unary_union(
        [
            LineString([(1290, 360), (1390, 430)]).buffer(10),
            LineString([(1390, 340), (1430, 415)]).buffer(10),
            Polygon(SCANDINAVIAN_INTERIOR),
            Polygon([(1600, 340), (1740, 370), (1740, 390), (1570, 390)]),
            Polygon(
                [
                    (2380, 1400),
                    (2550, 1380),
                    (2710, 1380),
                    (3160, 1440),
                    (2930, 1510),
                    (2750, 1490),
                    (2520, 1480),
                    (2310, 1470),
                ]
            ),
        ]
    )
    # Keep the western Scandinavian land corridor open for the reviewed Kongsberg–Malmo
    # border. The northern interior still separates Kongsberg from Stockholm.
    scandinavian_corridor = [
        (9.5, 58.8), (10.5, 59.8), (11.4, 59.5), (12.7, 58.3), (13.0, 57.0), (13.0, 55.6)
    ]
    neutral = neutral.difference(
        LineString([project(*point) for point in scandinavian_corridor]).buffer(45)
    )
    playable = land.difference(neutral)
    for key, position in positions.items():
        component = min(
            get_parts(playable), key=lambda part: part.distance(Point(position))
        )
        interior = component.buffer(-12)
        if interior.is_empty:
            interior = component.buffer(-5)
        if not interior.covers(Point(position)):
            positions[key] = nearest_points(interior, Point(position))[0].coords[0]
    # Follow bays and peninsulas when a direct city-to-city chord crosses water.
    routes = {
        ("kongsberg", "malmo"): scandinavian_corridor[1:-1],
        ("wales", "cornwall"): [(-3.7, 51.3)],
        ("amsterdam", "bruges"): [(4.3, 51.6)],
        ("barcelona", "valencia"): [(0.2, 40.8)],
        ("salonika", "athens"): [(22.4, 39.2)],
        ("danzig", "mitau"): [(21.5, 55.4)],
        ("venice", "dubrovnik"): [(14.5, 45.3), (16, 44.5)],
        ("kaffa", "varna"): [(30, 46.8), (29, 45.5)],
        ("kaffa", "tana"): [(33.8, 46.7), (36.2, 47.8), (38.9, 47.4), (39.0, 46.6)],
        ("barca", "tripoli"): [(19.5, 30.5), (16.5, 30.5)],
        ("tripoli", "tunis"): [(11.7, 32.4), (10.2, 33.7)],
    }
    inset_mask = box(0, -50, 330, 200).union(box(0, 1800, 280, 2110))
    fixed_insets = land.intersection(inset_mask)
    land = (
        transform(display_point, land.difference(inset_mask))
        .union(fixed_insets)
        .buffer(0)
    )
    frame = Polygon(
        [display_point(x, y) if x else (0, y) for x, y in frame.exterior.coords]
    )
    return (
        frame,
        land,
        transform(display_point, neutral),
        {
            key: point if key in {"iceland", "west-africa"} else display_point(*point)
            for key, point in positions.items()
        },
        {
            edge: [display_point(*project(*point)) for point in bends]
            for edge, bends in routes.items()
        },
    )
