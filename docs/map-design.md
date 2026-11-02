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
  On the standard map, the Aegean and Central Mediterranean are separate seas within Area VIII; Crete's coastal water
  visibly adjoins each of them and the Eastern Mediterranean.
  Smyrna's coastal water visibly adjoins both the Aegean Sea and Eastern Mediterranean.
  Fez visibly adjoins both the Canary Basin and Western Mediterranean on both maps.
  On the European map, the Aegean is part of the Central Mediterranean: show one continuous sea
  and one name, with every former Aegean contact transferred to the combined sea. Crete adjoins
  only the Central Mediterranean; Smyrna adjoins it and the Eastern Mediterranean.
  Copenhagen's coast reaches Stettin and visibly screens Malmo from the North Sea. Malmo and
  Stettin share coastal water and separate Copenhagen and Lubeck from the Baltic Sea. Visby's
  coastal band is separated from every other coast except Stockholm.
  Copenhagen owns northern Jutland, Funen and Zealand; all three parts inspect and highlight as
  one province, with the main label in Jutland. Copenhagen and Hamburg share a mainland border.
  Enclosed neutral remnants on the Danish islands belong to Copenhagen; its combined outline
  contains no internal enclaves.
  A visible band of neighboring coastal water separates Malmo from Lubeck, which have no direct
  physical contact.
  Malmo's only support arrow points to Stockholm.
- Kongsberg and Malmo share a visible land border through western Scandinavia. The neutral
  interior between them is divided between the two provinces, extending their established
  boundary. A narrow gap still separates Kongsberg from Stockholm.
- Shared coastal boundaries around Malmo, Copenhagen, Stettin and Lubeck use simple lines with
  stable junctions. Stettin has no detached coastal exclave. Preserve shorelines and every reviewed
  land, coastal-water and sea connection while simplifying these borders.
- Coastal-water borders between Ireland and Great Britain use the same simple shared lines,
  retaining recognizable shorelines and readable crossings between Armagh, Waterford, Edinburgh,
  Chester and Wales. Chester remains separated from named seas.
  The Bay of Biscay reaches Edinburgh along a broad, continuous front west of Scotland, with
  the Greenland Sea retained along its northern coast.
  Retain visible Bristol Channel water between Cornwall and Wales. Cornwall's only land neighbor
  is Portsmouth; its coastal water directly borders Wales, Chester and Portsmouth. Cornwall's
  Chester support crosses their shared coastal-water boundary.
- Belgrade reaches the Adriatic shore between Dubrovnik and Durazzo, sharing land and coastal
  borders with both and a coastal border with Bari. A visible strip of Belgrade separates
  Dubrovnik from Durazzo on land and water. The Adriatic name follows the narrow channel and
  fits clear water beside Belgrade's coast.
  Bari's coastal water separates Durazzo from the Adriatic; Durazzo touches no named seas.
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
| Satellite | Pale sage green with fine, moderately contrasting stippling at a consistent screen size, distinct from solid gray Stone |
| Capital | Dark background behind the complete market badge, including commodity |
| Owner | The same capital color and silhouette as every other player representation |
| Piece kind / age | A count identifies tokens; an unnumbered piece is a marker. White is new, filled is established. Separate owners and ages. |
| Directed support | Short, straight satellite-to-market border crossing with a contrasting arrowhead; only catalogued support |
| Area / shared access | A clear interior area badge, preferably in open sea space; muted diagonal hatching and locks mark missing access advances |

Province fills retain commodity identity when control changes. Land and coastal water receive the
same reachability, eligibility, focus and selection emphasis. Use one selected outline around their
combined footprint. Area emphasis covers the whole area and strongly dims its surroundings.
Access hatching follows the viewer's authoritative missing-advance projection, independently of
routes, ships and phase. Shade the whole area, including its water, when every province shares the
lock; otherwise shade only locked provinces' land and coastal water. Keep commodities, pieces,
labels and inspection available. Remove hatching with its lock as soon as access is acquired.
Hatching marks mandatory regional and overseas access. Leave Iceland and West Africa unhatched;
their alternative navigation requirements still apply to legal actions.
Inspecting a province highlights each directly connected sea with deep blue water, sparse diagonal
hatching, a contrasting dashed perimeter and a light name with a dark halo. Sea texture remains
distinct from the pale solid coastal bands. Hatch spacing and perimeter width stay consistent on
screen while zooming and resizing. Sea connections come from the catalog and do not imply that a
shipment is legal.

Paint connections and ordinary borders below neighbor, eligible, hover/focus and selected highlights,
in that order. The inspected object wins a tie with selected targets. Keep labels and pieces readable
above highlights; apply the same priority across provinces and areas.

Fit names, market badges, markers and token groups on land first, then within their own province's
coastal footprint. Avoid internal shorelines and foreign borders whenever space permits. Keep labels
and pieces apart, compact crowded groups, and preserve owner, age and count information in accessible
labels and at readable zoom levels.
Constrained labels and exceptionally dense groups may need overlap; record these exceptions in
geometry metadata and verify them explicitly. Do not create a new game connection to make room.

## Navigation and selection

Provinces and area badges support direct pointer and keyboard inspection and target selection.
Areas excluded from the current game's catalog by player count remain dimmed background: their
badges cannot highlight, select or receive keyboard focus. Derive active areas from the catalog.
Clicking a province's commodity icon highlights every in-play province of that commodity, using the
same emphasis as commodity cards. Each icon on a multi-commodity province inspects only its own
commodity. This inspection preserves prepared actions through polling and closes with ordinary map
inspection or dismissal. Commodity icons share one roving tab stop, with arrow-key traversal and
Enter/Space activation, alongside the province and area groups.
Coastal water selects the same province as land. A click prepares a draft; the explicit action button
commits it. Sea or non-province background clicks clear inspection, focus and the inspected border;
dragging preserves them. Background clicks preserve an active action draft and picker selections.

On desktop, keep zoom in, zoom out and Fit floating over the board. Phones retain gesture and
keyboard controls without floating buttons. Always show support arrows; omit a visibility
toggle. Support modifier-scroll, trackpad pinch, two-finger touch pinch and dragging, centered on the
gesture and constrained to board bounds. Initially fit the board to the viewport width, with the view
matching the viewport's proportions. If a tall viewport would expose empty space above or below the
board, use the minimum height-cover zoom instead. Clamp both axes at every zoom, drag and resize so
neither the view nor native viewport scrolling can expose space outside the board; omit letterboxing.
Resizing must preserve the prepared action and retain the existing center/zoom where the new bounds
allow it. Zoom anchors, dragging and texture spacing follow the rendered scale in both dimensions.
Ordinary vertical and horizontal scrolling over the map pans at its rendered scale, within the
same bounds as dragging. Shift-wheel also pans horizontally; modifier-scroll retains zoom.
Normalize pixel, line and page wheel deltas. Scrolling outside the map retains the surrounding
scroll area's behavior. Keep map text unselectable during gestures.
The map shows occupancy, neighbors, sea contacts and support highlights without an inspection panel.
Costs and placement limits belong beside the action. Omit map search and navigation menus.

## Data and verification

The [European map authoring guide](european-map.md) describes the derived catalog, report and
regeneration commands for the playable European variant.

The browser selects checked-in `web/src/map/boardGeometry.json` or `europeanBoardGeometry.json`
according to the game's recorded map option. Both are keyed by stable province IDs.
Catalog-13 saves use the matching `catalog13BoardGeometry.json` or
`catalog13EuropeanBoardGeometry.json` instead, preserving the topology they actually play.
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

Dense groups use joint label/piece placement: nearby label positions are compared before accepting
shared space. This removed the twelve-group label collisions in North and South America without
changing province or area geometry. When sharing remains unavoidable, the map keeps every piece
and retains the name/market in accessible labels, rather than painting text over occupants. Each
province's accessible name includes its identity, market and complete occupancy. Zoom to read dense
pieces; do not add an occupancy section below the map. Fit is an overview; zoom for dense counts. The larger Danish province removes Copenhagen's constrained label and
dense-group sharing exceptions. Toulouse and Constantinople retain constrained geometry metadata;
the user accepted their current labels on 2026-10-29.

## Legibility review, 2026-10-27

Absent land connections use a twelve-unit neutral band so they remain distinguishable at overview
scale. Shared coastal boundaries in southwestern France and around Constantinople/Smyrna receive
the same topology-preserving simplification as the Baltic and British Isles. The independent
validator retains all 146 land, 105 coastal and 96 sea contacts, including visible sea exclusions.

Label fitting searches the province's own coastal interior when land cannot hold its name and
market badge. Piece packing uses that final label position. Toulouse and Constantinople's current
labels are accepted; preserve their placement.

Support paths retain every catalogued direction: 78 on the full standard board and 76 on the
European board. Show each as a short, straight crossing of the shared province/coastal border,
with the tip inside the supported market. Avoid detours, crossing shafts and ambiguous clusters.
The visible shaft and its contrasting halo stop inside the arrowhead so neither protrudes beside
its narrow tip at high zoom; the arrowhead retains the exact catalogued endpoint.
Cagliari's coastal-water arrows to Genoa and Marseilles use longer 48-unit shafts to remain visible;
the Marseilles route extends back into Cagliari to leave its target's marker slot clear.
Reserve the whole arrowhead and contrasting halo when avoiding names and normal marker positions;
dense token groups still take drawing priority. Arrow routing is drawing data and never changes
the support graph or a province's connections.

Regeneration includes `support_geometry.py`. Toulouse retains its clear east/south exits and accepted
label placement; the Montpellier arrow ends before the printed name. Coastal crossings may place
more of their shaft inside the market so the full arrowhead clears a narrow junction. Validators
check catalog parity, short straight paths, direction, complete arrow bounds, label clearance,
province traversal and uncrossed shafts; Cornwall–Chester uses the ordinary coastal-boundary checks.

Label fitting reserves extra room for platform font substitutions while keeping names readable.
Tight footprints retain their fitted label budget before accepting a constrained layout. Both map
assets use this clearance; changing it repositions labels and pieces without changing province
boundaries. Browser fit failures report the affected label part, bounds and overflow coordinates.
