# Maps and catalogs

Catalogs own game facts; geometry draws them. Distance/contact never creates legal routes.
[Rules](rules.md#14-reviewed-rulings) owns source rulings; [UI](ui-design.md) owns interaction lifetimes.

## Catalog ownership

Paths are relative to [core/src/commonMain/kotlin/dev/aragos/aor](../core/src/commonMain/kotlin/dev/aragos/aor/).

| Facts | Files |
| --- | --- |
| Provinces, land/coast/seas, directed support | `BoardProvinces.kt`, `BoardConnections.kt`, `BoardMapDefinition.kt` (`StandardMapData`) |
| European derivation | `EuropeanMapData.kt`, `BoardMapBuilder.kt` |
| Advances, cards, economy | `Advances.kt`, `Cards.kt`, `CardEffectData.kt`, `Commodities.kt`, `EconomyTables.kt` |
| Options / readiness / recorded topology | `RuleOptions.kt`, `GameCatalog.kt` / `CatalogVersion.kt` |

Immutable static DSLs have no gameplay callbacks/IO. Validation covers IDs/references, connection kinds,
capitals, economy and prerequisites. `GameCatalog.validateFor(count)` checks the 3–6-player board;
`requireResolvedFor` also rejects unresolved references. Remove unused areas and incident coasts together.
Structural validity does not prove source accuracy; all current transcription questions are resolved,
and new unresolved questions block affected readiness.

Keep printed values/card lists in code, provenance in [rules sources](rules.md#sources). Update the
machine-readable [standard-provinces.csv](../web/scripts/standard-provinces.csv) when standard facts
change; its default loader also reads `BoardConnections.kt`. Verify parity with the engine export.

## Author the European map

Edit `EuropeanMapData.kt` with `boardMap(StandardMapData.definition) { ... }`. This copies the baseline;
[BoardMapBuilder KDoc](../core/src/commonMain/kotlin/dev/aragos/aor/BoardMapBuilder.kt) owns DSL semantics,
ordering and validation. Use stable lowercase hyphenated IDs; rename via remove/add with explicit links,
remove inherited coast/sea links for inland conversions, and supply presentation metadata for new capitals.
`land`/`coast` endpoints are symmetric; `supports` is directed; sea contacts are independent of coast steps.
Preserve overseas availability at every count. Variant behavior belongs in [rules §13](rules.md#13-variants).
Inspect without storage/server startup:

```sh
./gradlew -q :server:describeEuropeanMap
./gradlew -q :server:describeEuropeanMap -PfullMap=true
```

Playable changes require catalog compatibility and old topology/drawings; see [deployment](deployment.md#saved-game-compatibility).

## Generate and validate drawings

[BoardMap.tsx](../web/src/map/BoardMap.tsx) uses JSON selected by
[geometryAssets.ts](../web/src/map/geometryAssets.ts), which owns current/legacy asset mappings.
Unsupported combinations show an unavailable map, never substituted topology. Assets cache across
polling; failed downloads retry without sending actions.
Author anchors in [map_geography.py](../web/scripts/map_geography.py). The checked-in coastline subset
comes from public-domain [Natural Earth 1:50m land](https://www.naturalearthdata.com/downloads/50m-physical-vectors/50m-land/).
In a Python virtual environment, from the repository root:

```sh
python -m pip install -r web/scripts/map-requirements.txt
python web/scripts/build-map.py
python web/scripts/validate-map.py
```

For engine-exported European geometry (use `-Pmap=standard` and default geometry for standard parity):

```sh
./gradlew :server:exportMapCatalog -Pmap=european -PmapOutput=/tmp/aor-european.json
python web/scripts/build-map.py --catalog /tmp/aor-european.json --output web/src/map/europeanBoardGeometry.json
python web/scripts/validate-map.py --catalog /tmp/aor-european.json --geometry web/src/map/europeanBoardGeometry.json
```

[map_catalog.py](../web/scripts/map_catalog.py) loads either catalog source. After changing shared
Ligurian presentation in [map_presentation.py](../web/scripts/map_presentation.py), run
`python web/scripts/polish-legacy-maps.py`; cosmetic updates must preserve retained topology.

## Spatial and visual contracts

- Draw exact catalog land/coast/sea/support contacts; corners are not adjacency. Keep absent links
  separated, islands/coasts unified, areas contiguous and neutral pockets free of implied routes.
  Standard Aegean/Central Mediterranean stay separate; European merges them.
- Markets use commodity fill/pictogram/number; pale stippled satellites differ from Stone. Dark capital
  badges and shared owner silhouettes/colors identify players. White/filled pieces show age, counts
  distinguish tokens/markers. Preserve every piece and accessible occupancy in dense positions.
- Support arrows cross borders straight toward their catalog market with clear tips/halos, avoiding
  labels/pieces. Keep Cagliari legible. Fit labels/groups in their own footprint; exceptions live in
  geometry metadata (including accepted Toulouse/Constantinople placements).
- Land/coast share selection/reach emphasis; hatch authoritative locked regions/overseas boxes while
  preserving labels/inspection. Sea highlights show contact, never shipping legality. Paint selection
  over borders and labels/pieces above it. Excluded areas cannot select/focus; groups use roving focus.
- Background clicks clear inspection but preserve drafts; dragging preserves both. Fit width, or cover
  height to avoid empty bands; bound zoom/pan/resize in both axes and use rendered gesture scale.
  Zoom/Fit sit by workspace tabs when space allows; gestures/keyboard remain on narrow layouts.

The validator checks graph parity, outlines, sea exclusions, support paths and fitted bounds. Geometry/
browser tests cover rendering, dense/reduced boards, gestures and live placement; use [focused checks](contributing.md#checks).
