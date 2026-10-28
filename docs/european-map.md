# Authoring the European map

Edit **[`EuropeanMapData.kt`](../core/src/commonMain/kotlin/dev/aragos/aor/EuropeanMapData.kt)**.
Its `boardMap(StandardMapData.definition) { ... }` block starts with a complete copy of the
standard board. Declare only the differences. The standard data is not mutated or duplicated;
unchanged facts follow the standard definitions in the same build.

The active differences are listed in [rules](rules.md#13-variants). The file also contains commented
authoring examples. Choose **European map** when creating a local table, or use
`set-option map european` before `start`. Choose at least four players to include Loire (Area III).

## Check your changes

```sh
./gradlew -q :server:describeEuropeanMap
```

This validates the draft, shows province counts/capitals for each supported player count, and
lists added/removed/changed facts. For a complete listing of the resolved copy:

```sh
./gradlew -q :server:describeEuropeanMap -PfullMap=true
```

Neither command starts the server, opens game storage, or changes saved games. Bad definitions fail
with a specific explanation. The regular core tests also validate the editable draft, without
requiring it to remain identical to the standard board.

## Province definitions

Use stable lowercase hyphenated IDs. Change existing facts with `changeProvince`; unspecified
fields and connections are retained. New provinces use the existing area/market/satellite/overseas
DSL. For example:

```kotlin
boardMap(StandardMapData.definition) {
    removeProvince("west-africa")
    changeProvince("vienna") {
        copy(marketNumber = 3, commodities = setOf(Commodity.METAL))
    }
    addProvinces {
        area(GeographicalArea.VII) {
            market("trieste", number = 3, commodity = Commodity.METAL)
            satellite("istria")
        }
    }
    addConnections {
        land("trieste", "venice", "istria")
        coast("trieste", "venice", "istria")
        supports("istria", "trieste")
        sea(SeaArea.ADRIATIC_SEA, "trieste", "istria")
    }
}
```

`ProvinceDefinition.copy` can change `area`, `overseas`, `marketNumber`, `commodities`, `coastal`,
`capital`, and `access`. Province invariants still apply: satellites have number 1/no commodity,
mainland markets have one commodity, Area V requires Overland East, and overseas boxes have their
group's market/commodity count and access requirements. `area(V)` automatically supplies Overland
East for new declarations; moving an existing province into V requires adding that access condition.

Removing a province automatically removes its inherited incident connections and its capital-order
entry. New connections to a removed or misspelled province are rejected. To rename a province,
remove the old ID, add its replacement, and declare the replacement's links explicitly.

## Connection definitions

Put new links in `addConnections { ... }` and inherited links to delete in
`removeConnections { ... }`, using the same four declarations:

| Declaration | Meaning |
| --- | --- |
| `land("a", "b", "c")` | Land borders a–b and a–c |
| `coast("a", "b", "c")` | Coastal-water steps a–b and a–c; not sea incidence |
| `supports("satellite", "market", "other-market")` | Directed satellite support arrows |
| `sea(SeaArea.ADRIATIC_SEA, "a", "b")` | Provinces whose coastal water touches that sea |

Land/coast endpoint order is interchangeable. Support direction matters. Adding an existing link,
removing an absent link, or declaring a reference/edge of the wrong kind is an error. A declaration
adds/removes only the named links; it does not replace all of a province's neighbors. Province edits
run in source order; connection blocks are applied to the final province list.

If a coastal province becomes inland, remove its inherited coastal-water and sea links explicitly.
The validator will identify any that remain. Physical proximity and a shared sea never create an
implicit land border, coastal step, or support arrow.

## Setup and sea-area metadata

Inside the same `boardMap` block:

```kotlin
capitals("venice", "genoa", "barcelona", "paris", "london", "hamburg")
minimumPlayers(GeographicalArea.V, count = 4)
seaArea(SeaArea.ADRIATIC_SEA, GeographicalArea.VIII)
seaArea(SeaArea.BALTIC_SEA, null)
mergeSea(SeaArea.AEGEAN_SEA, into = SeaArea.CENTRAL_MEDITERRANEAN)
```

Capital order starts with the three always available capitals, followed by the additional capital
for each larger game. It must agree with province `capital` flags and area availability. To replace
a capital, change both province flags and this order. A draft supports 3 through its capital count,
up to 6 players. Overseas boxes remain available regardless of area cutoffs.

`seaArea` changes geographical membership, not sea connections or shipping rules. `null` leaves the
sea outside all geographical areas. Existing `SeaArea` values can be used in draft connections;
the standard board's Norwegian Sea erratum is enforced separately on the standard catalog.
`mergeSea` removes its first sea and transfers all its coastal contacts to the surviving sea,
deduplicating shared contacts and retaining the surviving sea's area. Merges apply after connection
edits. The European map uses this operation to combine the Aegean and Central Mediterranean into
one Central Mediterranean sea in Area VIII.

## Regenerate and validate the drawing

The selected map is used throughout setup, board validation, expansion, card targets, commodity
ownership and public catalogs. Wine has seven ownership boxes on the full European board;
payments use the same quadratic formula. The selected option is recorded in the initial revision
and replayed on restart. Catalog version `ah1996-catalog-12` includes the merged European sea.

Province declarations do not redraw the browser asset. After changing the map, update its anchors
in `web/scripts/map_geography.py` as needed, then export the resolved engine catalog and regenerate
the separate drawing. From the repository root, using the Python environment described in
[map design](map-design.md#data-and-verification):

```sh
./gradlew :server:exportMapCatalog -Pmap=european -PmapOutput=/tmp/aor-european-catalog.json
python web/scripts/build-map.py --catalog /tmp/aor-european-catalog.json --output web/src/map/europeanBoardGeometry.json
python web/scripts/validate-map.py --catalog /tmp/aor-european-catalog.json --geometry web/src/map/europeanBoardGeometry.json
```

The validator checks exact land, coastal-water, sea and support connections, province identities,
area outlines, labels and piece bounds. The standard asset and its default generation/validation
commands remain separate. Record further rule changes in [rules](rules.md) and advance the catalog
version when changing playable definitions. New capitals also require presentation metadata.
