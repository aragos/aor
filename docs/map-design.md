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
- **Keep province ownership clear.** Neighboring land spaces share boundaries. A province may own
  several islands: Corsica and Sardinia belong to Cagliari, and the northern Greek island belongs
  to Athens. Bari includes Italy's toe. Coastal water uses a pale fill;
  shared coastal edges imply shipping steps without a network of route lines. Distinct sea fills and
  names identify sea access. Keep coastal bands narrow enough to leave readable sea spaces and
  visible sea contacts. Prefer simple bay fronts and straight water connections over narrow strips
  wrapping around another coast. Coastal bands originate at their own province's land, rather than
  nearby neutral islands or inset panels. Remove detached components that serve no catalogued
  connection; some provinces need more than one coastal-water component for their printed access.
- **Highlight the whole province.** Land and coastal water share reachability shading, target and
  selection highlights, and keyboard or hover emphasis. Draw one emphasized outline around their
  combined footprint, without emphasizing the internal shoreline. Coastal water remains part of
  the same province selection, with the same explicit order confirmation.
- **Crop continents at the frame.** Northern, eastern, and African frontier provinces extend to the
  playing field's outer edge. Land continues beyond that edge; do not wrap it in coastal water or sea.
  Neutral hinterland connects larger landmasses where no playable province border exists. Actual
  islands retain their coasts. The northern and eastern frame follows the playable frontier closely,
  rather than stretching provinces to a distant rectangular edge. Keep the eastern frontier compact
  by applying the same longitude compression to land, sea, connections, and labels. Constrain map
  gestures to the board bounds.
- **Preserve recognizable geography.** Use the coastlines of Britain, Iberia, Italy, Greece, and the
  Mediterranean to orient the map. Enlarge small islands and narrow straits enough to read values and
  coastal boundaries. Iceland and West Africa may use insets. Geographic proximity never adds a game
  connection; neutral land separates geographic neighbors that do not share a catalogued border.
- **Make shared boundaries agree.** Coastal water and named seas form a partition outside land and
  inset panels. Smooth shared sea-facing arcs together, preserving coastal steps and sea access.
  Remove redundant detached sea pockets and give narrow seas, especially the Adriatic, enough
  exposed water to read their connections.
- **Give every area one perimeter.** Enclose its member provinces, coastal waters, and assigned
  seas in one continuous outline, without enclaves or exclaves. Include enclosed neutral islands
  and minor unassigned water pockets; this does not assign that sea to an area. Adjacent
  borders abut, with each colored stroke clipped inside its own area. Place the Roman numeral badge
  in clear interior space, favoring sea space. Clicking or focusing the badge highlights the entire
  area, and eligible badges edit area-target drafts such as Black Death. Do not repeat membership
  inside every province. Strongly shade the rest of the map during area inspection or targeting,
  keeping the highlighted area's labels and pieces bright.
- **Make absent connections visible.** Position Sarai north of Kamyshin and leave clear separation
  from Tana, Abagia, and Poti. Its only land neighbor is Kamyshin. Proximity and a thick area outline
  must not suggest additional province connections. Kamyshin touches Sarai and Tana; Tana does not
  touch Poti. Show clear water gaps between Cagliari and Rome, Oran and Granada, and Granada's coast
  and the Canary Basin.
  Acre and Cyprus also retain a clear coastal-water gap.
  Tana extends onto the land east of Crimea so its Black Sea frontage reads directly. Shetland and
  Bergen share a coastal border.
- **Use one player identity everywhere.** Each capital has a stable color and silhouette: Venice a
  teal diamond, Genoa a blue circle, Barcelona a red shield, Paris a purple hexagon, London an ochre
  square, and Hamburg a green triangle. Markers and tokens use the same owner silhouette.
- **Distinguish pieces with their faces.** A number inside a piece is a token count; an unnumbered piece
  is a marker. A white face means new, a filled face means established. Show different owners and ages
  separately. Province fills never change with control.
- **Make support directional.** Use dark arrows with clear heads and a white outline, without circles
  at either endpoint. Draw only catalogued support, including enabled rule variants.
- **Reserve text for names and quantities.** Keep province text short. Fit names and market badges
  on land wherever possible, using their own province's coastal space when the land is too narrow.
  Apply the same fit policy to markers and token groups; compact crowded groups within their province.
  Keep pieces clear of names and badges wherever possible. Exceptionally dense groups in narrow
  provinces may share label space; inspection preserves all owner, age and count details.
  Avoid crossing another province's border unless the geometry cannot fit the label. Use a dark
  market badge, including its commodity icon, for a capital. Map text cannot be selected during dragging.
- **Show shared access once.** Put an access lock on the area or overseas heading that owns the
  shared requirement, rather than repeating it on provinces. Area V, Far East, and New World locks
  disappear as the viewer satisfies their advances. The engine projects these locks independently
  of phase, route, and shipping capacity. Province-specific requirements remain in inspection details.
- **Layer highlights by meaning.** Draw connections and ordinary borders first, then neighbor,
  eligible, hover/focus, and selected highlights in that order. Apply the same ordering to province
  and area highlights. Labels and pieces remain readable above those overlays.

## Viewing and issuing orders

The map reads the current player's public snapshot. It shows empty provinces as well as occupied ones;
inactive areas remain muted for geographic context and cannot be selected as order targets.
Floating zoom controls, modifier-scroll, pinch, and dragging navigate the board.
Keyboard selection and the existing board table provide alternatives to pointing at small spaces.

Selecting a province shows its market, commodities, area, access requirements, occupants, land and
coastal neighbors, sea contacts, and both directions of support. Selection highlights connections
across the province's land and coastal water while preserving its commodity identity. All map values
refresh with the existing game polling. The active power's capital and remaining pool stay prominent
beside the board throughout Expansion, including while the mobile decision sheet is collapsed.

During expansion, selecting an uncontrolled market prepares the engine-projected commitment needed
to establish a marker. The decision panel shows pool tokens before and after, deterministic income
changes, and actual blockers. Contested markets use the exact attack commitment and show an uncertain
outcome; satellites retain their token action. Legal partial placements are a secondary choice.
The engine chooses the route and validates the whole order. Capital selection, marker reduction,
and marker relocation remain available where applicable. Card targets, area targets, pirate targets,
and War cessions activate map target highlighting automatically, with the same selection limits as
their contextual controls.

A map click selects or edits a draft; it never submits an order. An explicit order button uses the
same command endpoint, revision check, busy guard, and error handling as the text interface.
Clicking sea or non-province land clears the inspected province or area, including keyboard focus
emphasis. Dragging preserves inspection, and clearing inspection preserves an active order's draft.
Orders become unavailable as the authoritative prompt changes. Valid drafts and the map viewport
survive polling and workspace changes. On phones, selected-object decisions use a collapsible bottom
sheet. No private opponent information is added to the map or public catalog.

## Data and verification

Geometry belongs to the browser, keyed by stable province identifiers. The public catalog owns market
values, commodities, area availability, land and coastal connections, sea contacts and optional sea
area membership, access conditions, and variant-dependent support. Game state owns pieces; capital
assignments own player identity.
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
`web/scripts/validate-map.py`. The validator checks the exported polygons against 145 land borders,
101 coastal steps, 95 sea contacts, all 108 label anchors and combined selection outlines, and the
eight continuous area outlines, including every assigned sea and excluding other areas' members.
It also checks fitted label bounds, island ownership, Italy's toe, Tana's eastern land, non-neighbor
separation, disjoint land, inset panels,
coastal water and seas, shared
area boundaries without inset gaps, and continental provinces meeting the frame without extending
water beyond the playing field.

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
