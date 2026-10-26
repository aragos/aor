# Interactive map contracts

The map is a geographic schematic of the reviewed board graph. Recognizable coastlines help players
orient themselves; legibility may change distances and shapes. Movement, support and legal actions
come from the catalog and engine. The [UX principles](ui-design.md) govern interaction and continuity;
[rules](rules.md) and the [connection inventory](connection-inventory.md) own individual rulings.

## Spatial ownership

- Land cells meet exactly where the catalog declares land borders. Coastal cells meet exactly where
  it declares coastal steps; a shared corner is not adjacency. Leave visible separation for absent
  connections. Neutral land and water cannot imply an extra route.
- Each province owns its land, islands and coastal water. Coastal bands originate at their own
  shoreline. Simplify sea-facing edges together so neighboring cells abut without gaps, overlaps,
  wraparounds or stray fragments. Keep named seas and their coastal contacts clearly visible.
- A sea may belong to an area; membership is optional and catalogued. Area outlines enclose member
  provinces, their coasts and assigned seas in one continuous perimeter, without enclaves or
  exclaves. Adjacent area borders abut. Enclosed neutral pockets do not confer movement or ownership.
- Crop continental frontiers closely to the playing field. Land beyond the frame receives no coastal
  band. Compact eastern geography consistently across every map layer; keep inset panels distinct
  from playable water. Geometry changes must preserve all connection and ownership invariants.

## Visual encoding

| Information | Encoding |
| --- | --- |
| Commodity / market | Permanent commodity fill, pictogram and readable market number; divided fills for multiple commodities |
| Satellite | Neutral stippling |
| Capital | Dark background behind the complete market badge, including commodity |
| Owner | The same capital color and silhouette as every other player representation |
| Piece kind / age | A count identifies tokens; an unnumbered piece is a marker. White is new, filled is established. Separate owners and ages. |
| Directed support | Arrowhead with a contrasting outline; only catalogued support |
| Area / shared access | A clear interior area badge, preferably in open sea space; locks on the owning area or overseas heading |

Province fills retain commodity identity when control changes. Land and coastal water receive the
same reachability, eligibility, focus and selection emphasis. Use one selected outline around their
combined footprint. Area emphasis covers the whole area and strongly dims its surroundings.

Paint connections and ordinary borders below neighbor, eligible, hover/focus and selected highlights,
in that order. The inspected object wins a tie with selected targets. Keep labels and pieces readable
above highlights; apply the same priority across provinces and areas.

Fit names, market badges, markers and token groups on land first, then within their own province's
coastal footprint. Avoid internal shorelines and foreign borders whenever space permits. Keep labels
and pieces apart, compact crowded groups, and preserve owner, age and count information in inspection.
Constrained labels and exceptionally dense groups may need overlap; record these exceptions in
geometry metadata and verify them explicitly. Do not create a new game connection to make room.

## Navigation and selection

Provinces and area badges support direct pointer and keyboard inspection and target selection.
Coastal water selects the same province as land. A click prepares a draft; the explicit action button
commits it. Sea or non-province background clicks clear inspection and focus; dragging preserves them
and background clicks preserve an active action draft.

Keep zoom in, zoom out and Fit floating over the board. Support modifier-scroll, trackpad pinch,
two-finger touch pinch and dragging, centered on the gesture and constrained to board bounds.
Ordinary scrolling navigates the page. Keep map text unselectable during gestures. The board table
provides a structured alternative to spatial inspection; omit map search and navigation menus.

## Data and verification

The browser consumes checked-in `web/src/map/boardGeometry.json`, keyed by stable province IDs.
The catalog owns connections, memberships and access; game state owns pieces and assigned capitals.
Geometry does not determine eligibility or calculate game effects.

The simplified coastline source is the public-domain
[Natural Earth 1:50m land dataset](https://www.naturalearthdata.com/downloads/50m-physical-vectors/50m-land/).
The regional subset is `web/scripts/geographic-coastlines.json`; authored anchors and adjustments
live in `web/scripts/map_geography.py`. Regeneration is offline. From the repository root, in a Python
virtual environment:

```sh
python -m pip install -r web/scripts/map-requirements.txt
python web/scripts/build-map.py
python web/scripts/validate-map.py
```

The validator compares exported geometry against the independent catalog, including graph equality,
land/coast/sea separation, combined province outlines, contiguous areas, shared boundaries and fitted
label and piece bounds. Browser checks verify actual text and piece bounds, mixed owners and ages,
reduced boards, gestures and order submission. See [README](../README.md) for build commands and
[decision coverage](ui-decision-coverage.md) for evidence, runner options and remaining manual checks.
