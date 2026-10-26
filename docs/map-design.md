# Interactive map design

The first map implementation follows the approved [Iberia and northwest Africa study](map-layout-commodities.svg).
The map follows simplified geographic coastlines while province sizes and label positions serve
legibility. Movement and order legality always come from the game engine and the reviewed catalog.
The [UI guiding rules](ui-design.md) extend this visual language across the application and define
the redesign's compact summaries and default market action of establishing a marker. The
[implementation plan](ui-implementation-plan.md) sequences that revision of the current map controls.

## Visual hierarchy

- **Permanent information dominates.** Fill each province with its commodity color and repeat the
  commodity as a pictogram next to a large market number. Use neutral stippling for satellites.
  Overseas boxes show every commodity, with divided fills and separate pictograms.
- **Keep land contiguous.** Neighboring land spaces share boundaries. Coastal water uses a pale fill;
  shared coastal edges imply shipping steps without a network of route lines. Distinct sea fills and
  names identify sea access. Some provinces have more than one coastal-water component.
- **Crop continents at the frame.** Northern, eastern, and African frontier provinces extend to the
  playing field's outer edge. Land continues beyond that edge; do not wrap it in coastal water or sea.
  Neutral hinterland connects larger landmasses where no playable province border exists. Actual
  islands retain their coasts. The northern and eastern frame follows the playable frontier closely,
  rather than stretching provinces to a distant rectangular edge. Panning beyond the frame reveals
  a neutral background.
- **Preserve recognizable geography.** Use the coastlines of Britain, Iberia, Italy, Greece, and the
  Mediterranean to orient the map. Enlarge small islands and narrow straits enough to read values and
  coastal boundaries. Iceland and West Africa may use insets. Geographic proximity never adds a game
  connection; neutral land separates geographic neighbors that do not share a catalogued border.
- **Mark areas at their perimeter.** Each area retains its own colored line, including parallel lines
  at shared borders. Use one consistently styled Roman numeral badge per area. Include distant parts
  such as West Africa in the area's enclosing outline. Do not repeat membership inside every province.
- **Use one player identity everywhere.** Each capital has a stable color and silhouette: Venice a
  teal diamond, Genoa a blue circle, Barcelona a red shield, Paris a purple hexagon, London an ochre
  square, and Hamburg a green triangle. Markers and tokens use the same owner silhouette.
- **Distinguish pieces with their faces.** A number inside a piece is a token count; an unnumbered piece
  is a marker. A white face means new, a filled face means established. Show different owners and ages
  separately. Province fills never change with control.
- **Make support directional.** Use dark arrows with clear heads and a white outline, without circles
  at either endpoint. Draw only catalogued support, including enabled rule variants.
- **Reserve text for names and quantities.** Keep province text short. Use a crown for a capital and a
  lock for access restrictions. Explain symbols in a shared legend and reveal full details on selection.

## Viewing and issuing orders

The map reads the current player's public snapshot. It shows empty provinces as well as occupied ones;
inactive areas remain muted for geographic context and cannot be selected as order targets. Search,
area filtering, a province selector, zoom controls, and dragging help navigate the full board.
Keyboard selection and the existing board table provide alternatives to pointing at small spaces.

Selecting a province shows its market, commodities, area, access requirements, occupants, land and
coastal neighbors, sea contacts, and both directions of support. Selection highlights connections
without changing the province's commodity fill. All map values refresh with the existing game polling.

During expansion, selecting an uncontrolled market prepares the engine-projected commitment needed
to establish a marker. The decision panel shows pool tokens before and after, deterministic income
changes, and actual blockers. Contested markets use the exact attack commitment and show an uncertain
outcome; satellites retain their token action. Legal partial placements are a secondary choice.
The engine chooses the route and validates the whole order. Capital selection, marker reduction,
and marker relocation remain available where applicable. Card targets, area targets, pirate targets,
and War cessions use **Choose on map** in their contextual controls, with the same selection limits.

A map click selects or edits a draft; it never submits an order. An explicit order button uses the
same command endpoint, revision check, busy guard, and error handling as the text interface.
Orders become unavailable as the authoritative prompt changes. Valid drafts and the map viewport
survive polling and workspace changes. On phones, selected-object decisions use a collapsible bottom
sheet. No private opponent information is added to the map or public catalog.

## Data and verification

Geometry belongs to the browser, keyed by stable province identifiers. The public catalog owns market
values, commodities, area availability, land and coastal connections, sea contacts, access conditions,
and variant-dependent support. Game state owns pieces; capital assignments own player identity.
Geometry must never supply movement rules or decide whether an order is legal.

Changes to geometry must preserve the catalog's adjacency graph, with a shared corner counting as
neither a land border nor a coastal step. Check the six-player map and the reduced boards, mixed owners
and piece ages, distant and overseas destinations, keyboard and small-screen use, and target picking
without submission. Browser checks must also exercise actual order submission and rejected orders.

The browser build uses the checked-in `web/src/map/boardGeometry.json`; it needs no geometry libraries.
Coastlines derive from the public-domain [Natural Earth 1:50m land dataset](https://www.naturalearthdata.com/downloads/50m-physical-vectors/50m-land/).
The simplified regional subset is checked in as `web/scripts/geographic-coastlines.json`; authored
projection, province anchors, insets, and coastline adjustments live in `web/scripts/map_geography.py`.
Regeneration is offline and requires no map service or browser geometry dependency.

To edit the geographic anchors or regenerate the shapes, create a Python virtual environment, install
`web/scripts/map-requirements.txt`, then run `web/scripts/build-map.py` and
`web/scripts/validate-map.py`. The validator checks the exported polygons against 144 land borders,
101 coastal steps, 98 sea contacts, all 108 label anchors, the eight area outlines, and continental
provinces meeting the frame without extending water beyond the playing field.

Run `npm test`, `npm run build`, and `npm run test:browser` in `web` with Node 22 or newer.
The browser runner builds the server distribution and launches its own server, Vite, and Chrome on
independent ports with temporary game data and a separate Chrome profile. It closes its processes
and removes temporary data on success and failure; screenshots are retained in the printed artifact
directory. Override `CHROME_BIN` for a different Chrome location and `AOR_BROWSER_ARTIFACTS` for a
chosen screenshot directory.

The browser checks combine live commands with development-only interruption and snapshot harnesses,
excluded from the production build. They cover map navigation, reduced boards, mixed pieces,
contextual decisions, draft continuity, and responsive layouts. The
[decision coverage inventory](ui-decision-coverage.md) records exactly which flows have live-game,
fixture, and domain evidence; fixture checks alone do not establish authorization or privacy.
