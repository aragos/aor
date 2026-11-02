"""Topology-preserving presentation shared by current and supported saved-game drawings."""
from shapely import union_all
from shapely.geometry import Polygon


def polish_ligurian_junction(land_union, water, seas):
    # Use a simple three-way junction between Marseilles, Genoa and Cagliari. The
    # automatic coastal channel leaves a hooked Genoa border and a sea-facing notch.
    # These sectors replace only open water, preserving the mainland and island shores.
    marseilles_shore = (1217.291, 1123.423)
    genoa_front = (1296.516, 1118.880)
    marseilles_front = (1209.440, 1178.897)
    cagliari_front = (1240.430, 1208.391)
    ligurian_junction = (1254, 1152)
    support_front = tuple((a + b) / 2 for a, b in zip(marseilles_front, cagliari_front))
    ligurian_patch = Polygon([marseilles_shore, genoa_front, cagliari_front, marseilles_front])
    ligurian_sectors = {
        "marseilles": [marseilles_shore, ligurian_junction, support_front, marseilles_front],
        "genoa": [marseilles_shore, genoa_front, ligurian_junction],
        "cagliari": [genoa_front, cagliari_front, support_front, ligurian_junction],
    }
    assert ligurian_patch.intersection(land_union).area < .01, (
        "The Ligurian junction may only reshape water, never a mainland or island shore."
    )
    for province, corners in ligurian_sectors.items():
        water[province] = union_all([
            water[province].difference(ligurian_patch), Polygon(corners),
        ], grid_size=0.001)
    seas["WESTERN_MEDITERRANEAN"] = seas["WESTERN_MEDITERRANEAN"].difference(ligurian_patch)
