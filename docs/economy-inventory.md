# Economic data review inventory

This inventory records the AH1996 baseline in `AdvanceData`, `CommodityData` and `EconomyTables`.
Amounts are dollars unless stated otherwise. These declarations describe printed data; they do not implement
purchases, payouts, income phases or advance effects. The following transcription has been checked against the
sources below. Economy data has no outstanding transcription question.

## Sources and edition boundary

- [AH1996 rulebook scan](https://www.scribd.com/document/709810410/Age-of-Renaissance-regles-VO),
  especially §5.2 (25 round domination markers).
- [Reference bundle](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf): the redesigned AH1996
  Advance Log on PDF page 14, advance reference panels on pages 22–24, official errata on pages 1–3, and
  M. Boone's FAQ starting on page 5. Page numbers here count PDF pages starting with one.
- [Photograph of the printed gameboard, BGG image 192678](https://boardgamegeek.com/image/192678/age-of-renaissance):
  the commodity log at the lower left gives ownership tracks, whose lengths match the map's commodity-market counts. These tracks are not payment limits. AH1996 §7.11 states that commodity cards print ten payment boxes.
- [Hexagonia rules rewrite](http://www.hexagonia.com/rules/AgeOfRenaissance.pdf), page 6 (§3.14): reproduces the
  physical player mat showing income positions 0–25, including the 5- and 6-player values; §5.5.3 records the
  stabilization schedule and §5.5.1 the shipping upgrade cost.
- [Conboy, Condensed Complete Rules](https://tesera.ru/images/items/1180616/AOR_Condensed_Complete_Rules_2009-09.pdf),
  first-page components and player-mat description, and Income section: independently records 25 markers,
  25 white circles, and income read for the initial player count.

The EuroGames reference on bundle page 17 is a different edition. Its changed Commerce identities,
Communication/Civics credits and advance effects are excluded. In particular, baseline J is Wind/Watermill,
K is Improved Agriculture, N/O/P/Q give 10/10/10/0 credits, and V/W/X/Y/Z give 10/20/30/40/50 credits.

## Advances

`Cr` is the category credit for later purchases; it is not an immediate cash award. `MR` is the dollar relief
rating of advances purchased this turn; it is not a number of misery spaces. Prerequisites are direct,
conjunctive requirements. `Purchase misery` is a change in spaces: positive increases misery.
The table is checked against both bundle page 14 and pages 22–24.

| ID | Advance | Category | Cost | Cr | MR | Prerequisites | Purchase misery |
| --- | --- | --- | ---: | ---: | ---: | --- | ---: |
| A | The Heavens | Science | 30 | 20 | 5 | — | 0 |
| B | Human Body | Science | 60 | 20 | 10 | — | −1 |
| C | Laws of Matter | Science | 90 | 20 | 5 | — | 0 |
| D | Enlightenment | Science | 120 | 20 | 50 | — | 0 |
| E | Patronage | Religion | 30 | 20 | 10 | — | +1 |
| F | Holy Indulgence | Religion | 60 | 20 | 0 | — | +1 |
| G | Proselytism | Religion | 90 | 20 | 0 | — | +1 |
| H | Cathedral | Religion | 120 | 20 | 25 | F | +1 |
| I | Caravan | Commerce | 20 | 10 | 5 | — | 0 |
| J | Wind/Watermill | Commerce | 40 | 10 | 5 | I | 0 |
| K | Improved Agriculture | Commerce | 50 | 10 | 25 | J | −1 |
| L | Interest & Profit | Commerce | 80 | 10 | 0 | K | 0 |
| M | Industry | Commerce | 110 | 0 | 5 | L | 0 |
| N | Written Record | Communication | 30 | 10 | 5 | — | 0 |
| O | Printed Word | Communication | 60 | 10 | 10 | N | 0 |
| P | Master Art | Communication | 90 | 10 | 5 | O | 0 |
| Q | Renaissance | Communication | 120 | 0 | 100 | P | 0 |
| R | Overland East | Exploration | 40 | 20 | 5 | — | 0 |
| S | Seaworthy Vessels | Exploration | 80 | 20 | 5 | — | 0 |
| T | Ocean Navigation | Exploration | 120 | 20 | 5 | A, S | 0 |
| U | New World | Exploration | 160 | 0 | 25 | T, V | 0 |
| V | Urban Ascendancy | Civics | 20 | 10 | 5 | — | 0 |
| W | Nationalism | Civics | 60 | 20 | 5 | — | 0 |
| X | Institutional Research | Civics | 100 | 30 | 10 | — | 0 |
| Y | Cosmopolitan | Civics | 150 | 40 | 25 | R | 0 |
| Z | Middle Class | Civics | 170 | 50 | 50 | K | 0 |

| Category | Advances | Total cost |
| --- | ---: | ---: |
| Science | 4 | 300 |
| Religion | 4 | 300 |
| Commerce | 5 | 300 |
| Communication | 4 | 300 |
| Exploration | 4 | 400 |
| Civics | 5 | 500 |
| All | 26 | 2100 |

Validation requires every A–Z identifier exactly once, positive costs, nonnegative credits and MR, the printed
category totals, no repeated or self prerequisites, and an acyclic dependency graph. Enum identifiers prevent
unknown prerequisite references.

## Commodities

The photographed board confirms the ownership-track lengths below, matching the map's market counts.
AH1996 §7.11 separately states that commodity cards print ten payment boxes. Payment box n pays
`base value × n²`; the ownership-track length does not cap this payment. Gold and Ivory share
roll 12; every other total selects one commodity.

| Commodity | Base | Dice total | Ownership boxes | Card payments, boxes 1–10 |
| --- | ---: | ---: | ---: | --- |
| Stone | 1 | 2 | 8 | 1, 4, 9, 16, 25, 36, 49, 64, 81, 100 |
| Wool | 2 | 3 | 9 | 2, 8, 18, 32, 50, 72, 98, 128, 162, 200 |
| Timber | 3 | 4 | 7 | 3, 12, 27, 48, 75, 108, 147, 192, 243, 300 |
| Grain | 4 | 5 | 8 | 4, 16, 36, 64, 100, 144, 196, 256, 324, 400 |
| Cloth | 5 | 6 | 8 | 5, 20, 45, 80, 125, 180, 245, 320, 405, 500 |
| Wine | 5 | 7 | 6 | 5, 20, 45, 80, 125, 180, 245, 320, 405, 500 |
| Metal | 6 | 8 | 7 | 6, 24, 54, 96, 150, 216, 294, 384, 486, 600 |
| Fur | 7 | 9 | 5 | 7, 28, 63, 112, 175, 252, 343, 448, 567, 700 |
| Silk | 8 | 10 | 6 | 8, 32, 72, 128, 200, 288, 392, 512, 648, 800 |
| Spice | 9 | 11 | 7 | 9, 36, 81, 144, 225, 324, 441, 576, 729, 900 |
| Gold | 10 | 12 | 5 | 10, 40, 90, 160, 250, 360, 490, 640, 810, 1000 |
| Ivory | 10 | 12 | 4 | 10, 40, 90, 160, 250, 360, 490, 640, 810, 1000 |

The maximum ownership count is nine Wool markets. Industry plus a shortage can therefore request
payment box 11, beyond the printed card. The user ruled on 2026-10-23 that the quadratic formula
continues beyond the printed boxes, so this pays `2 × 11² = $242`. Printed ownership and payment
tracks do not impose a payout cap.

## Income

Income is indexed by domination markers on the map, including the capital, from 0 through 25 inclusive.
For `n` markers and `p` initial players, the mat values are `15 + n × p`; Middle Class's extra $10 is a later
state-dependent modifier. The reproduced physical mat on Hexagonia page 6 clearly shows position 0 at $15,
position 1 at $20/$21 for 5/6 players, and position 25 at $140/$165. Conboy's London example independently
checks 17 markers in a 5-player game at $100.

| Markers | 3 players | 4 players | 5 players | 6 players |
| ---: | ---: | ---: | ---: | ---: |
| 0 | 15 | 15 | 15 | 15 |
| 1 | 18 | 19 | 20 | 21 |
| 2 | 21 | 23 | 25 | 27 |
| 3 | 24 | 27 | 30 | 33 |
| 4 | 27 | 31 | 35 | 39 |
| 5 | 30 | 35 | 40 | 45 |
| 6 | 33 | 39 | 45 | 51 |
| 7 | 36 | 43 | 50 | 57 |
| 8 | 39 | 47 | 55 | 63 |
| 9 | 42 | 51 | 60 | 69 |
| 10 | 45 | 55 | 65 | 75 |
| 11 | 48 | 59 | 70 | 81 |
| 12 | 51 | 63 | 75 | 87 |
| 13 | 54 | 67 | 80 | 93 |
| 14 | 57 | 71 | 85 | 99 |
| 15 | 60 | 75 | 90 | 105 |
| 16 | 63 | 79 | 95 | 111 |
| 17 | 66 | 83 | 100 | 117 |
| 18 | 69 | 87 | 105 | 123 |
| 19 | 72 | 91 | 110 | 129 |
| 20 | 75 | 95 | 115 | 135 |
| 21 | 78 | 99 | 120 | 141 |
| 22 | 81 | 103 | 125 | 147 |
| 23 | 84 | 107 | 130 | 153 |
| 24 | 87 | 111 | 135 | 159 |
| 25 | 90 | 115 | 140 | 165 |

**Resolved source discrepancy:** the Boone FAQ components list on bundle page 6 says 24 domination markers.
The original rulebook §5.2, the printed mat's 25 white circles, Hexagonia's components and Conboy all establish
25. The catalog uses 25 and includes the separate uncovered position-zero circle (income $15).
No custom rule is needed.

## Stabilization

The printed schedule continues as the triangular number `n × (n + 1) / 2` for `n` cards. Hands have no size
limit; this is a formula rather than a truncated lookup table. The catalog accepts every nonnegative `Int`
hand size and returns `Long` dollars to avoid intermediate multiplication overflow. Negative sizes are rejected.
Middle Class halves the result, rounded up, when gameplay is implemented.

| Cards | 0 | 1 | 2 | 3 | 4 | 5 | 6 | 7 | 8 | 9 | 10 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Cost | 0 | 1 | 3 | 6 | 10 | 15 | 21 | 28 | 36 | 45 | 55 |

## Shipping, misery and order boxes

The catalog reuses `ShippingCapability` and `MiseryStep`; it introduces no duplicate track definitions.
Each shipping upgrade costs $10. These are the legal printed positions; purchase permissions and reach remain
state-dependent rules.

| Fleet | Levels | Capacity meaning |
| --- | --- | --- |
| None | 0 | No shipping |
| Galleys | 2, 4, 6, 8 | Tokens per destination; level also limits coastal-water reach |
| Seaworthy vessels | 10, 12, 14, 16 | Tokens per destination |
| Ocean navigation | 1, 2, 3, 4 | Occupied overseas boxes; shipping token capacity is unlimited |

Misery's ordered numeric positions are 0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 125, 150, 175, 200,
250, 300, 350, 400, 450, 500, 600, 700, 800, 900 and 1000. Chaos follows 1000 and has no numeric value.
The printed board photograph shows the shipping and misery tracks. [Rules §5.1](rules.md#51-order-of-play)
and the existing turn-order implementation remain the source of the already verified order boxes:

| Initial players | Boxes |
| --- | --- |
| 3 | 2, 4, 6 |
| 4 | 1, 3, 5, 6 |
| 5 | 1, 2, 3, 4, 5 |
| 6 | 1, 2, 3, 4, 5, 6 |
