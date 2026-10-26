# Board connection inventory

This is the reviewable transcription behind [BoardConnections.kt](../core/src/commonMain/kotlin/dev/aragos/aor/BoardConnections.kt).
All recorded connection questions are resolved, including the Gallipoli–Angora ruling below.
A structural validation pass checks references and edge types; the tables provide the transcription
for review.

## Sources and reading key

- **Board**: [AH printed board photograph](https://boardgamegeek.com/image/192678/age-of-renaissance),
  cross-checked against the sharper [3531 × 2707 board photograph](https://boardgamegeek.com/image/4041958/age-of-renaissance).
  Locators: **N** upper-left/upper-center (British Isles, Scandinavia, Baltic); **C** central/northern
  continental Europe; **W** lower-left/center (Iberia, southern France, Italy); **E** right/lower edge
  (Black Sea, Greece, Anatolia, Levant, North Africa).
- **Close-ups**: the photographed board excerpts in the [Hexagonia rules rewrite](http://www.hexagonia.com/rules/AgeOfRenaissance.pdf),
  PDF page 12, independently clarify the Barcelona–Montpellier border and Venice–Florence–Rome coastal route.
- **Errata**: official AH [Errata and Clarifications, 7 May 1998](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf),
  PDF page 1, map questions; applied wherever it overrides or clarifies the photograph.

Identifiers match the province inventory. Every row lists **only** the edges declared by that row;
reverse rows are unnecessary for undirected edges. A land edge and a coastal-water edge are different facts.
A satellite arrow is directed and requires an actual printed arrow. Shared corners do not establish a border.
Geographical area boundaries do not remove an otherwise shared land or coastal-water boundary.

## Land borders

Each listed neighbor is connected in both directions. Source is the Board region in the final column.

| Province | Neighbors | Board locator |
| --- | --- | --- |
| bergen | kongsberg | N |
| malmo | stockholm | N |
| hamburg | lubeck, amsterdam, cologne | N |
| lubeck | stettin, cologne, nuremberg, prague | N |
| stettin | danzig, breslau, prague | N |
| danzig | mitau, breslau | N |
| mitau | riga | N |
| riga | novgorod | N |
| novgorod | smolensk | N |
| smolensk | kiev | N |
| edinburgh | york, chester | N |
| york | chester, london | N |
| chester | wales, portsmouth | N |
| wales | cornwall | N |
| cornwall | portsmouth | N |
| portsmouth | london | N |
| armagh | waterford | N |
| amsterdam | bruges, cologne | C |
| bruges | cologne, strasbourg, paris | C |
| cologne | nuremberg, strasbourg | C |
| nuremberg | strasbourg, st-gall, salzburg, prague | C |
| strasbourg | paris, dijon, basel, st-gall | C |
| basel | dijon, lyons, milan, st-gall | C |
| st-gall | salzburg, milan, venice | C |
| salzburg | prague, vienna, venice | C |
| prague | breslau, vienna | C |
| breslau | vienna | C |
| vienna | budapest, esseg, venice | C |
| budapest | esseg, belgrade | C |
| esseg | venice, dubrovnik, belgrade | C |
| paris | st-malo, bordeaux, dijon | C |
| st-malo | bordeaux | C |
| dijon | bordeaux, lyons | C |
| leon | basque, toledo, lisbon | W |
| basque | toledo, barcelona, toulouse | W |
| lisbon | toledo, seville | W |
| toledo | barcelona, valencia, granada, seville | W |
| barcelona | valencia, toulouse, montpellier | W |
| valencia | granada | W |
| seville | granada | W |
| bordeaux | toulouse, montpellier, lyons | W |
| toulouse | montpellier | W |
| montpellier | lyons, marseilles | W |
| lyons | marseilles, genoa, milan | W |
| marseilles | genoa, cagliari | W |
| milan | genoa, venice | W |
| genoa | venice, florence, rome | W |
| venice | florence, dubrovnik | W |
| florence | rome | W |
| rome | naples | W |
| naples | bari | W |
| dubrovnik | belgrade, durazzo | W |
| belgrade | durazzo | W |
| kiev | kaffa | E |
| kaffa | varna, tana | E |
| tana | kamyshin, abagia | E |
| kamyshin | sarai | E |
| abagia | poti | E |
| poti | erzerum | E |
| erzerum | trebizond | E |
| trebizond | angora | E |
| varna | gallipoli | E |
| durazzo | salonika, corfu | E |
| salonika | gallipoli, corfu, athens | E |
| corfu | athens | E |
| constantinople | smyrna, angora | E; user correction 2026-10-26 |
| smyrna | adalia | E |
| adalia | taurus | E |
| taurus | aleppo | E |
| aleppo | levant | E |
| levant | acre | E |
| acre | jerusalem | E |
| jerusalem | suez | E |
| suez | cairo | E |
| cairo | alexandria | E |
| alexandria | libya | E |
| libya | barca | E |
| barca | tripoli | E |
| tripoli | tunis | E |
| tunis | algiers | E |
| algiers | oran | E |
| oran | fez | E |

## Coastal-water connections

Each edge is a single step between adjacent light-blue coastal-water spaces. It does not cross a
medium-blue sea. Iceland and West Africa have no edges in this graph: they require a sea crossing or
Seaworthy Vessels. Overseas boxes have no edges in this main-map graph.

| Province | Coastal-water neighbors | Board locator |
| --- | --- | --- |
| bergen | kongsberg | N |
| kongsberg | malmo, copenhagen | N |
| shetland-islands | bergen | N; user correction 2026-10-26 |
| malmo | stockholm, copenhagen, lubeck | N |
| stockholm | visby | N |
| copenhagen | hamburg, lubeck | N |
| hamburg | lubeck, amsterdam | N |
| lubeck | stettin | N |
| stettin | danzig | N |
| danzig | mitau | N |
| mitau | riga | N |
| riga | novgorod | N |
| edinburgh | shetland-islands, armagh, chester, york | N |
| armagh | waterford, chester | N |
| waterford | wales | N |
| chester | wales | N |
| wales | cornwall | N |
| cornwall | portsmouth | N |
| portsmouth | london, paris, st-malo | N + Errata; user correction 2026-10-26 |
| london | york, paris, bruges | N + Errata; user correction 2026-10-26 |
| amsterdam | bruges | C |
| bruges | paris | C |
| paris | st-malo | C |
| st-malo | bordeaux | C |
| bordeaux | toulouse | W |
| toulouse | basque | W |
| basque | leon | W |
| leon | lisbon | W |
| lisbon | seville | W |
| seville | granada, fez | W |
| granada | valencia, fez | W; user correction 2026-10-26: no Oran contact |
| valencia | barcelona, palma | W |
| barcelona | palma, montpellier | W |
| montpellier | marseilles | W |
| marseilles | genoa | W |
| genoa | rome, cagliari | W |
| rome | naples | W |
| naples | bari | W; user correction 2026-10-26: no Sicily contact |
| bari | sicily, corfu, durazzo | W + Errata; user correction 2026-10-26 |
| venice | florence, dubrovnik | W |
| florence | rome | W |
| dubrovnik | durazzo | W |
| durazzo | corfu | E |
| corfu | athens | E; user correction 2026-10-26: no Salonika coastal contact |
| athens | salonika, crete | E |
| salonika | gallipoli | E |
| gallipoli | varna, constantinople | E |
| constantinople | smyrna | E |
| smyrna | adalia | E; user correction 2026-10-26: no Crete contact |
| adalia | taurus, cyprus | E |
| taurus | cyprus, aleppo | E |
| cyprus | aleppo, levant | E |
| aleppo | levant | E |
| levant | acre | E |
| acre | jerusalem | E |
| jerusalem | suez | E |
| suez | cairo | E |
| cairo | alexandria | E |
| alexandria | libya | E |
| libya | barca | E |
| barca | tripoli | E |
| tripoli | tunis | E |
| tunis | algiers, sicily | E + user ruling (2026-10-25) |
| algiers | oran | E |
| oran | fez | E |
| varna | kaffa | E |
| kaffa | tana | E |
| tana | abagia | E |
| abagia | poti | E |
| poti | erzerum | E |
| erzerum | trebizond | E |
| trebizond | angora | E |

The Paris–Portsmouth, Paris–London and Bari–Corfu connections are explicit AH errata. The western and
eastern coastal waters belonging to an Italian peninsula province share that province identifier; this
inventory does not add an extra land-crossing action or shipping rule.

## Directed satellite support

Read each row as satellite → supported markets. Reversing an arrow would be a different, invalid fact.
St. Gall → Nuremberg is deliberately absent under the AH errata.

| Satellite | Supported markets | Board locator |
| --- | --- | --- |
| kongsberg | bergen | N |
| shetland-islands | bergen, edinburgh | N |
| armagh | edinburgh, chester, waterford | N |
| wales | chester, waterford | N |
| cornwall | chester, portsmouth | N |
| malmo | stockholm, lubeck | N |
| copenhagen | hamburg, lubeck | N |
| visby | stockholm | N |
| stettin | danzig, prague | N |
| mitau | danzig, riga | N |
| smolensk | novgorod, kiev | N |
| amsterdam | hamburg, bruges, cologne | C |
| breslau | danzig, prague, vienna | C |
| salzburg | nuremberg, prague, vienna, venice | C |
| st-gall | strasbourg, milan, venice | C + Errata |
| esseg | vienna, budapest, belgrade, dubrovnik | C |
| leon | basque, lisbon, toledo | W |
| palma | barcelona, valencia | W |
| toulouse | bordeaux, montpellier, basque, barcelona | W |
| cagliari | marseilles, genoa | W |
| bari | naples, sicily | W |
| kaffa | kiev, tana, varna | E |
| kamyshin | tana, sarai | E |
| abagia | tana, poti | E |
| durazzo | belgrade, salonika | E |
| gallipoli | varna, salonika, constantinople | E |
| corfu | salonika | E |
| athens | salonika, crete | E |
| adalia | smyrna, cyprus | E |
| taurus | aleppo, cyprus | E |
| levant | aleppo, cyprus | E |
| jerusalem | acre, suez | E |
| libya | alexandria | E |
| barca | tripoli | E |
| oran | algiers, fez | E |

Cagliari's arrows point to Marseilles and Genoa. Genoa's narrow west-coast land strip continues south
under Florence; the eastern arrow therefore supports Genoa. Leon has three arrows: Basque, Lisbon and
Toledo. The small Smolensk arrows point to Novgorod and Kiev.

## Sea areas and coastal-water incidence

These are incidences between a named medium-blue sea and the light-blue water of a province, not
additional direct coastal-water steps. Every incidence is undirected for static topology. The Atlantic
is the only ocean and is flagged separately; it is not a sea usable with The Heavens.

| Water body | Coastal-water provinces | Source locator |
| --- | --- | --- |
| greenland sea | iceland, edinburgh, shetland-islands | Board N (label around Iceland) |
| north sea | bergen, kongsberg, shetland-islands, copenhagen, hamburg, amsterdam, bruges, london, york, edinburgh | Board N |
| baltic sea | malmo, stockholm, visby, copenhagen, lubeck, stettin, danzig, mitau, riga, novgorod | Board N |
| bay of biscay | edinburgh, armagh, waterford, chester, wales, cornwall, portsmouth, paris, st-malo, bordeaux, toulouse, basque, leon, lisbon, seville | Board N/W + Errata (Edinburgh through Seville) |
| canary basin | seville, fez, west-africa | Board W, lower-left |
| western mediterranean | granada, valencia, barcelona, palma, montpellier, marseilles, genoa, cagliari, rome, naples, bari, sicily, tunis, algiers, oran | Board W, including west of Italian peninsula |
| adriatic sea | venice, florence, rome, naples, bari, dubrovnik, durazzo | Board W/E + Hexagonia PDF p. 12 |
| central mediterranean (including aegean) | bari, sicily, tunis, tripoli, barca, corfu, athens, crete, salonika, gallipoli, constantinople, smyrna | Board W/E; user correction 2026-10-26: one sea space |
| eastern mediterranean | barca, libya, alexandria, cairo, suez, jerusalem, acre, levant, cyprus, adalia | Board E + Errata (Barca yes; Libya to Central no); user correction 2026-10-26: no Crete incidence |
| black sea | varna, kaffa, tana, abagia, poti, erzerum, trebizond, angora, constantinople, gallipoli | Board E, upper-right |
| Norwegian Sea | No effective game connections | Errata: no game function |
| Atlantic Ocean | No main-map coastal-water incidences | Board N/W; separated from coastal water by medium-blue seas |

The Greenland Sea is the separately named medium-blue patch containing Iceland. It is not the
Norwegian Sea. The Western Mediterranean extends east of Cagliari to the western Italian coast and
northwestern Sicily. The Central Mediterranean includes the Aegean basin between Greece and Anatolia;
the two printed names identify one sea space, as confirmed by the user on 2026-10-26. Its twelve
coastal incidences are listed in one row above. A crossing between those basins uses one sea space.

Sea-to-sea movement is not a coastal-water step. The catalog stores the province/sea incidences needed
for a single sea crossing with The Heavens; no additional sea-to-sea movement graph is required.

### Geographical area membership

The user confirmed these assignments on 2026-10-26. Area borders include the entire assigned sea;
other water bodies have no area assignment. Outlines enclose neutral islands and small unassigned
water pockets among member coastal spaces, without assigning an entire unassigned sea to an area.
Sea spaces remain available on reduced boards even when their area's provinces are absent.

| Sea | Area |
| --- | --- |
| Baltic Sea | I |
| Greenland Sea | II |
| Black Sea | V |
| Eastern Mediterranean | VI |
| Adriatic Sea | VII |
| Central Mediterranean, including the Aegean | VIII |
| Canary Basin | VIII |

## Connection rulings and source status

There are no remaining source blockers. Any future unresolved question must be exposed as a typed
`ConnectionReviewBlocker` and prevent the affected configurations from being called complete.

**Gallipoli–Angora: no connection.** Confirmed by the user on 2026-10-23: “Angora / gallipoli do not
touch.” This resolves the contact obscured by the thick printed area boundary in both photographs.
The absent coastal-water edge is confirmed for all player counts. Constantinople–Angora remains connected.

**Constantinople–Angora: land and coastal-water connection.** The user confirmed their land border
on 2026-10-26. Both connections apply at every supported player count.

**Central Mediterranean–Aegean: one sea space.** Confirmed by the user on 2026-10-26. The catalog
uses Central Mediterranean as the canonical identifier and combines both sets of coastal incidences.

**Further coastal corrections, 2026-10-26.** Smyrna and Crete do not share coastal water; neither
do Sicily and Naples. Bari shares coastal water with Naples, Sicily, Corfu, and Durazzo. Portsmouth
touches both St Malo and Paris by coast; London touches both Bruges and Paris. Cagliari and Rome,
Oran and Granada, and Tana and Poti have no direct coastal connection. Granada has no Canary Basin
incidence. These are topology facts independent of the schematic's spacing.
Athens and Crete retain their coastal-water border. Crete touches the combined Central
Mediterranean/Aegean only; it does not touch the Eastern Mediterranean. The latter sea's western
edge lies east of Crete, leaving the combined sea connected around the island.
Shetland and Bergen also share a direct coastal-water border, confirmed on 2026-10-26.

**Province geography, 2026-10-26.** Corsica belongs to Cagliari, and the northern Greek island
landmass belongs to Athens. Bari contains the toe of Italy. Sarai's only land neighbor is Kamyshin;
Kamyshin connects to Sarai and Tana. Clear hinterland gaps distinguish Kamyshin from Abagia and
Poti, and Tana from Poti. Tana includes the land east of Crimea, giving it a direct Black Sea front.

**Tunis–Sicily: coastal-water connection.** Confirmed by the user on 2026-10-25: Tunis and Sicily
touch. Their coastal-water spaces share a boundary, allowing a direct galley step; they have no
land border. This connection applies at every supported player count.

Resolved during transcription: Greenland Sea exists; no support from St. Gall to Nuremberg;
Cagliari supports Marseilles/Genoa; Leon supports Toledo; Barcelona and Montpellier share a short land
and coastal-water border; Toulouse has Atlantic coastal water but does not reach the Mediterranean.
The sharper photograph also resolves Kongsberg–Malmo and Kongsberg–Copenhagen coastal connections,
Lubeck–Prague as a land border,
Milan–Salzburg as separated by St. Gall, Cagliari–Marseilles/Genoa as coastal neighbors, and Cagliari–Rome
as only a corner contact. Salonika and Constantinople have no direct coastal edge because Gallipoli’s
coastal water lies between them. At Gibraltar, Seville and Fez touch Canary Basin; Granada does not.
Fez has no Western Mediterranean sea edge (only a corner at the Oran junction).

The inventory remains available for review as a whole. Automated validation checks its structural
invariants; source references and the recorded user ruling establish the transcription decisions.
