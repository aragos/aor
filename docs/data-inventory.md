# Board and economic catalog review

The first milestone in [the minimum full game plan](minimum-full-game-plan.md) now has a static
catalog and a review inventory. The engine still stops at Turn 1 — Draw Card. This work adds no
gameplay commands, state transitions or public HTTP endpoints.

Review the transcription using these tables:

- [Provinces](province-inventory.md): every province, area, market number, commodity, capital,
  coastal flag and access restriction, including all five overseas boxes.
- [Connections](connection-inventory.md): land borders, coastal-water connections, seas and
  directed satellite support, with the official map corrections and recorded rulings.
- [Economy](economy-inventory.md): all 26 AH1996 advances, printed commodity tracks, income,
  stabilization, shipping, misery and order boxes.

The primary map reference is [BGG photograph 192678](https://boardgamegeek.com/image/192678/age-of-renaissance),
a straight-on photograph of the Avalon Hill board at 2462 × 1399 pixels. It is suitable for reading
province labels, market numbers, area boundaries and the commodity track.
[BGG photograph 4041958](https://boardgamegeek.com/image/4041958/age-of-renaissance), at 3531 × 2707
pixels, resolves fine land borders, support arrows and the printed Aegean Sea label that are hard
to distinguish in the first photograph. [BGG photograph 667248](https://boardgamegeek.com/image/667248/age-of-renaissance)
provides another view. The user resolved the obscured Gallipoli–Angora contact on 2026-10-23:
the provinces do not touch. The photographs are not copied into this repository.

The [reference PDF bundle](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf)
was checked directly, including its official errata and advance panels. Its FAQ summary tables
contain several errors, documented in the province and economic inventories. Individual printed
values take precedence over inconsistent totals. No implementation data was copied from the
unlicensed repositories listed in the rules.

## Validation and completion

The typed builders reject invalid local definitions, duplicate identifiers, incomplete economic
catalogs and cyclic advance prerequisites. Cross-catalog validation checks graph references,
connection types, capital coverage, all geographical areas and overseas boxes, and that commodity
market counts match every printed payout-track length.

`GameCatalog.validateFor(playerCount)` validates each configuration for 3–6 starting players and
returns its static board. Unused areas and all their coastal connections are removed together;
sea areas remain available. It reports unresolved source questions separately from structural
validation. `GameCatalog.requireResolvedFor(playerCount)` additionally rejects configurations with
unresolved references. Passing structural checks does not resolve those questions or certify human review.

| Players | Areas | Mainland markets | Satellites | Overseas boxes | Total spaces |
| ---: | --- | ---: | ---: | ---: | ---: |
| 3 | IV–VIII | 43 | 19 | 5 | 67 |
| 4 | III–VIII | 54 | 24 | 5 | 83 |
| 5 | II–VIII | 62 | 29 | 5 | 96 |
| 6 | I–VIII | 68 | 35 | 5 | 108 |

Status: **reviewed by the user; all recorded data questions resolved**. The province and
economy questions were resolved from references. The user confirmed that Gallipoli and Angora do
not touch, resolving the final connection question. All four player counts pass the reference-readiness
guard. Any future unresolved source question must block the affected configuration's readiness.
The data review is complete and the repeatable turn loop can proceed. Subsequent user rulings reject
excess attack commitments and make event-rebuilt marker bonus eligibility a setup option; their policy
primitives are implemented and tested separately from this static-data milestone.

Run the integrated checks from the repository root:

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
```

Verified on 2026-10-23 after the expansion-policy additions: formatter and formatting check passed;
215 core JVM tests and 52 server tests passed, including 88 catalog tests. Kotlin/JS compilation
and the web production build passed.
These checks validate the data structures and existing behavior, including the confirmed absence
of the Gallipoli–Angora coastal connection and readiness for all four player counts.
