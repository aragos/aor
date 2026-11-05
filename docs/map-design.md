# Maps and catalogs

The catalog defines game facts; geometry draws those facts. Neither distance nor visual contact
creates a legal route. [Rules](rules.md#14-reviewed-rulings) retains source rulings;
[UI](ui-design.md) owns draft and inspection lifetimes.

## Catalog ownership

Definitions live under [core/src/commonMain/kotlin/dev/aragos/aor](../core/src/commonMain/kotlin/dev/aragos/aor/):

| Facts | Files |
| --- | --- |
| Provinces, land/coast links, seas and directed support | `BoardProvinces.kt`, `BoardConnections.kt`, `BoardMapDefinition.kt` (`StandardMapData`) |
| Derived European board | `EuropeanMapData.kt`, `BoardMapBuilder.kt` |
| Advances, cards and economy | `Advances.kt`, `Cards.kt`, `CardEffectData.kt`, `Commodities.kt`, `EconomyTables.kt` |
| Configuration/readiness | `RuleOptions.kt`, `GameCatalog.kt` |
| Recorded historical topology | `CatalogVersion.kt` |

Static DSLs construct immutable definitions, without gameplay callbacks or IO. Validators check
unique IDs, references, connection kinds, capitals, economic completeness and prerequisites.
`GameCatalog.validateFor(playerCount)` constructs the 3–6-player board and reports structural/source
issues; `requireResolvedFor` additionally rejects unresolved references. Unused areas and incident
coasts are removed together. Source review is separate from structural correctness; all currently
recorded transcription questions are resolved. New unresolved questions block affected readiness.

Printed values and physical-card lists live in code. [Rules sources](rules.md#sources) records their
provenance and edition boundary. Do not maintain parallel Markdown catalogs. The map tools retain
[standard-provinces.csv](../web/scripts/standard-provinces.csv) as a machine-readable transcription
input; their default loader combines it with `BoardConnections.kt`. When standard province facts
change, update that input and verify parity with an exported engine catalog.

## Author the European map

Edit [EuropeanMapData.kt](../core/src/commonMain/kotlin/dev/aragos/aor/EuropeanMapData.kt).
`boardMap(StandardMapData.definition) { ... }` copies the standard definition and declares differences;
it does not mutate the baseline. Supported differences belong in [rules §13](rules.md#13-variants).
Inspect a resolved draft without starting the server or opening game storage:

```sh
./gradlew -q :server:describeEuropeanMap
./gradlew -q :server:describeEuropeanMap -PfullMap=true
```

Use stable lowercase hyphenated province IDs. `changeProvince` retains unspecified facts and links;
`addProvinces` uses the existing area/market/satellite DSL. `removeProvince` also removes incident
links and its capital-order entry. Renaming requires a new province and explicit replacement links.
Province invariants still apply, including access requirements when moving into Area V.

Use `addConnections` / `removeConnections` blocks:

| Declaration | Meaning |
| --- | --- |
| `land("a", "b", "c")` | Land borders a–b and a–c |
| `coast("a", "b", "c")` | Coastal-water steps, independently of sea incidence |
| `supports("satellite", "market")` | Directed support arrow |
| `sea(SeaArea.ADRIATIC_SEA, "a", "b")` | Coastal contacts with that sea |

Land/coast endpoint order is interchangeable; support direction matters. Duplicate additions, absent
removals and invalid references reject. Province edits run in source order; connection edits apply
to the final list. An inland conversion must explicitly remove inherited coast/sea links.

`capitals(...)` gives availability order and must agree with capital flags/area cutoffs.
`minimumPlayers(area, count)` changes a cutoff. `seaArea(sea, area)` changes membership, not contacts;
null leaves a sea outside all areas. `mergeSea(source, into = target)` transfers/deduplicates contacts
and retains the target's area after connection edits. Overseas boxes remain available at every count.
New capitals also require presentation metadata. Changes to playable definitions require explicit
catalog compatibility; preserve old saves with their recorded topology and matching drawings.

## Generate and validate drawings

[BoardMap.tsx](../web/src/map/BoardMap.tsx) renders checked-in JSON selected by
[geometryAssets.ts](../web/src/map/geometryAssets.ts). Current assets are `boardGeometry.json` and
`europeanBoardGeometry.json`; supported older standard saves use `catalog13BoardGeometry.json`,
`catalog14BoardGeometry.json` or `catalog15BoardGeometry.json`. Catalogs 15 and 16 share the unchanged
European drawing. Unsupported combinations show an unavailable map, never a substituted
topology. Assets cache across polling and failed downloads can retry without sending actions.

Author anchors in [map_geography.py](../web/scripts/map_geography.py). The offline coastline subset,
`geographic-coastlines.json`, derives from public-domain
[Natural Earth 1:50m land](https://www.naturalearthdata.com/downloads/50m-physical-vectors/50m-land/).
Use a Python virtual environment, then run from the repository root:

```sh
python -m pip install -r web/scripts/map-requirements.txt
python web/scripts/build-map.py
python web/scripts/validate-map.py
```

For an engine-exported variant:

```sh
./gradlew :server:exportMapCatalog -Pmap=european -PmapOutput=/tmp/aor-european-catalog.json
python web/scripts/build-map.py --catalog /tmp/aor-european-catalog.json --output web/src/map/europeanBoardGeometry.json
python web/scripts/validate-map.py --catalog /tmp/aor-european-catalog.json --geometry web/src/map/europeanBoardGeometry.json
```

Use `-Pmap=standard` and the standard geometry to cross-check the default transcription against the
engine too. [map_catalog.py](../web/scripts/map_catalog.py) loads either source.
[map_presentation.py](../web/scripts/map_presentation.py) shares Ligurian junction presentation;
after changing it, run `python web/scripts/polish-legacy-maps.py` to update retained saved-game drawings
without changing their topology. Catalog changes and cosmetic changes have different compatibility
requirements; [deployment](deployment.md#saved-game-compatibility) owns supported versions.

## Spatial and visual contracts

- Draw exactly the catalog's land, coastal, sea and support contacts. Corner contact is not adjacency.
  Keep absent links visibly separated, each province's islands/coast unified, areas contiguous and
  neutral pockets free of implied routes. Standard Aegean/Central Mediterranean remain separate;
  the European map merges them. Preserve the reviewed graph through simplification.
- Commodity fill, pictogram and market number identify markets; pale stippled satellites stay distinct
  from Stone. Capital badges use a dark background. Owner silhouette/color is shared with player
  labels; white/filled pieces distinguish new/established and counts distinguish tokens from markers.
- Support arrows are short straight border crossings toward their catalogued market, with a clear
  tip and halo. Reserve arrow bounds around labels/pieces. Keep Cagliari's crossings legible;
  route geometry never changes the support graph.
- Land and coast share selection and reachability emphasis. Authoritative access hatching covers
  locked regions/overseas boxes while retaining readable labels and inspection. Sea-contact inspection
  highlights catalogued seas without implying legal shipping. Paint selection above ordinary borders
  and keep labels/pieces readable above it.
- Fit labels and mixed-owner/age groups within their own province footprint. Preserve every piece
  and complete accessible occupancy; zoom supports dense positions. Exceptions belong in geometry
  metadata. Toulouse and Constantinople's constrained label placements are accepted.
- Province/commodity/area groups support roving keyboard focus. Excluded areas cannot select or focus.
  Background clicks clear inspection while preserving action drafts; dragging preserves both.
- Initially fit width, or cover height when necessary to avoid empty bands. Bound zoom, pan and resize
  in both axes; preserve prepared actions and use the rendered scale for gestures. Desktop keeps
  zoom/Fit buttons beside the workspace tabs when the bar has room; narrow layouts retain gesture
  and keyboard access. No map mode or parallel province panel.

The validator checks graph parity, province/area outlines, sea exclusions, support paths and fitted
label/piece bounds. Browser geometry and map suites check rendered bounds, dense groups, reduced
boards, gestures and live placement. See [contributing](contributing.md) for running them.
