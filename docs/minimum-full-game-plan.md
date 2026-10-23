# Minimum full game plan

Agreed on 2026-10-23. The reviewed catalogs, repeatable engine, every advance and epoch-one card,
board play, both ending triggers, durable service and playable browser are implemented and verified.
Two rare source-policy decisions remain pending; see the table below. They currently produce typed
rejections, so the implementation is not declared complete for those edge cases.

## Target and current checkpoint

Deliver a complete **one-epoch game for 3–6 human players using standard rules**, from creation
through final scoring. Use AH1996 and the corrections recorded in [rules.md](rules.md).

The engine now progresses from setup through repeatable turns, Income and Final Card Play to scores.
A complete three-player command scenario covers expansion, competition, purchases and card effects;
its recorded history replays to the identical state. Separate acceptance coverage verifies the 26th
advance ending, final-turn completion, misery-value scoring and shared victories.

The HTTP acceptance scenario completes a game through ordinary commands, then recovers the same
completed snapshot using a random provider that fails if called. Three real authenticated browser
seats also completed a game in turn 8. Pending and completed state survived separate server restarts,
stale revisions returned 409, and the 390-pixel mobile layout had no page overflow.

The original setup/turn-order checkpoint was `ef7992e`; subsequent features are submitted as stacked
PRs below. Do not merge the stack without the user's instruction.

| Remaining ruling | Proposed policy, awaiting user answer |
| --- | --- |
| All 25 domination markers deployed when War/Crusades needs more | Block a new Crusades placement; cap War transfers at the winner's available markers. Do not penalize the loser for the winner's counter shortage. An alternative is allowing voluntary marker reduction during Card Play. |
| All nine Wool markets plus Industry and a shortage requests box 11 | Continue the quadratic formula ($242), or cap at printed box 10 ($200). Board ownership tracks are not payment caps; commodity cards print ten payment boxes. |

Setup ends after capital selection. Every turn, including turn 1, begins with regular Order of Play.
That phase establishes the order and expansion pool for the same turn. Use explicit phase boundaries in timing
descriptions; remove terminology that implies purchases or order are deferred to a different turn.

The first complete game needs all advances and every card effect available in epoch one. Both rebuilt-marker
bonus settings are supported. Additional epoch decks, epoch transitions, other variants, bots, accounts, private
plans, general undo and a detailed graphical board are deferred.
A readable board representation, selectors and text commands are sufficient for the first playable interface.

## First milestone: verified board and economic data

Produce a complete, reviewable catalog with validation before implementing the repeatable turn loop.

### Deliverables

- **Board:** provinces, geographical areas, market numbers, commodities, capitals, coastal status, land borders,
  coastal-water connections, sea areas, directed satellite support, overseas boxes and access restrictions.
  Cover the areas and capitals used by every supported player count.
- **Economy:** all 26 advances and their categories, costs, credits, prerequisites and misery-relief ratings;
  commodity values, ownership tracks and printed payment boxes; income, stabilization and shipping tables.
- **Validation:** unique identifiers, valid references, symmetric connections where applicable, directed satellite
  links, valid market and commodity assignments, correct advance totals and complete player-count coverage.
  Reuse existing verified misery, shipping and order-box definitions.
- **Review inventory:** human-readable province and connection tables, economic tables, source references and any
  remaining discrepancies. A reviewer must be able to check transcription without reading Kotlin declarations.

Follow the project's typed DSL conventions, keeping declarations organized by data type. Static definitions describe
the board and economy; later milestones implement state-dependent effects.

### Sources and unresolved details

Use the existing rules and references in [rules §14](rules.md#14-open-questions) and
[Sources](rules.md#sources). A candidate photographic board reference is
[the high-resolution gameboard image](https://boardgamegeek.com/image/192678/age-of-renaissance).
Its suitability and legibility still need verification. Cross-check the printed board against the documented errata.
Transcribe facts from references; the unlicensed implementation repositories listed in the rules remain cross-checks.

The board questions currently include the Loire market, Area I satellite count, Libya's area, sea-area definitions,
and complete land/coastal-water connections. Resolve them against the reference and isolate any discrepancies for
a user ruling. Distinguish the board ownership tracks from the commodity cards' payment boxes.

The user resolved the gameplay decisions on 2026-10-23: reject attack commitments above the calculated
requirement, counting existing attacker tokens; support both `exclude` (default) and `include` for event-rebuilt
markers counting toward the expansion bonus. The latter is a public setup option frozen at `start` and recorded
in the replay log. The official errata settles Caravan attack-through through a fully occupied province.
These policy primitives are tested; board-play must use them before spending tokens, rolling competitions
or awarding the expansion bonus. Improved-setup reinsertion is outside the selected standard-setup scope;
recycling becomes relevant when additional epochs are implemented.

### Completion criteria

Every required value has a verifiable reference, the inventory has been reviewed, and catalog validation passes for
3, 4, 5 and 6 players. Missing data or unresolved discrepancies are explicit and prevent declaring the affected
configuration complete. Required data is verified rather than replaced with guessed values.

Validate the new catalog with focused tests and the core JVM/JS checks. Format Kotlin edits and run the formatting
check according to [AGENTS.md](../AGENTS.md). Make no gameplay or public HTTP API changes in this data milestone.

## Implementation status

| Milestone | Verified result |
| --- | --- |
| Board/economic data | Reviewed typed catalogs, every supported player count and the Gallipoli/Angora ruling. Ownership-track versus payment-box correction documented. |
| Repeatable turn flow/economy | Draws/discards, ordered phases, income, stabilization, misery, shortages/surpluses, phase-opening decisions and turn resets. |
| Purchases/advances | All 26 advances, prerequisites, old credits, immediate effects, leaders, Patronage, shipping and relief. |
| Board/competition | Pieces, reach, shipping quotas, domination, exact attacks, dice, Cathedral, Windmill, returns, shared card prices and both expansion-reward options. |
| Cards/interruptions | All 29 epoch-one cards, victim decisions, temporary immunity, no-effect plays, persistent tied War and physical replacements. Marker exhaustion and box 11 await the rulings above. |
| Ending/scoring | Both triggers, completing the current turn, forced Final Card Play, preserved effects and scores. Empty-deck queued draws and post-Chaos choices covered by regressions. |
| Interface/service | Guided phase controls, readable searchable board, catalog/ownership/effects, private offers/history, scores, full-game HTTP/browser completion and restart recovery. |

Additional epochs, epoch transitions, other variants, bots, accounts, private plans, general undo and
a detailed graphical board remain deferred. Only `epochs 1` is accepted; unsupported lengths are
rejected before starting. Games use current rules/data versions only.

## Resume in a new session

### Feature delivery workflow

Use a focused branch and cohesive commits for each feature or rule decision. Verify the branch in
isolation, push it with upstream tracking and submit a pull request with its validation results.
Stack dependent branches so each pull request exposes its own change. Keep implementation work
separate from unrelated decisions and preserve the reviewed milestone checkpoints.

Submitted checkpoints:

| Feature | Branch | Pull request |
| --- | --- | --- |
| Economic catalog | `feature/economic-catalog` | [#1](https://github.com/aragos/aor/pull/1) |
| Board catalog and map ruling | `feature/board-catalog` | [#2](https://github.com/aragos/aor/pull/2) |
| Exact attack commitment | `feature/exact-attack-commitment` | [#3](https://github.com/aragos/aor/pull/3) |
| Rebuilt-marker option | `feature/rebuilt-marker-option` | [#4](https://github.com/aragos/aor/pull/4) |
| Delivery workflow | `feature/full-game-roadmap` | [#5](https://github.com/aragos/aor/pull/5) |
| Board pieces | `feature/board-state-model` | [#6](https://github.com/aragos/aor/pull/6) |
| Card data | `feature/epoch-one-card-data` | [#7](https://github.com/aragos/aor/pull/7) |
| Advance ownership | `feature/player-advance-model` | [#8](https://github.com/aragos/aor/pull/8) |
| Advance and shipping purchases | `feature/advance-purchases` | [#9](https://github.com/aragos/aor/pull/9) |
| Player economy | `feature/player-economy` | [#10](https://github.com/aragos/aor/pull/10) |
| Expansion reach | `feature/expansion-reach` | [#11](https://github.com/aragos/aor/pull/11) |
| Pure card rules | `feature/epoch-one-card-rules` | [#12](https://github.com/aragos/aor/pull/12) |
| Competition rules | `feature/board-competition` | [#13](https://github.com/aragos/aor/pull/13) |
| Repeatable turns and service | `feature/repeatable-turn-service` | [#14](https://github.com/aragos/aor/pull/14) |
| Turn opening rules | `feature/turn-opening-rules` | [#15](https://github.com/aragos/aor/pull/15) |
| Commodity payment contract | `feature/commodity-payment-data` | [#16](https://github.com/aragos/aor/pull/16) |
| Expansion actions | `feature/board-actions` | [#17](https://github.com/aragos/aor/pull/17) |
| Phase window ordering | `feature/phase-window-order` | [#18](https://github.com/aragos/aor/pull/18) |
| Card actions and interruptions | `feature/card-actions` | [#19](https://github.com/aragos/aor/pull/19) |
| Legal action projection | `feature/legal-action-projection` | [#20](https://github.com/aragos/aor/pull/20) |
| Complete games and endings | `feature/game-ending-acceptance` | [#21](https://github.com/aragos/aor/pull/21) |
| Interruption regressions | `feature/interruption-regressions` | [#22](https://github.com/aragos/aor/pull/22) |
| Private history | `feature/private-game-history` | [#23](https://github.com/aragos/aor/pull/23) |
| Outcome and Chaos cleanup | `feature/outcome-cleanup` | [#24](https://github.com/aragos/aor/pull/24) |
| Private economy quotes | `feature/private-economy-quotes` | [#25](https://github.com/aragos/aor/pull/25) |
| Complete browser interface | `feature/full-game-interface` | [#26](https://github.com/aragos/aor/pull/26) |
| Versioned contract and playing guide | `feature/epoch-one-delivery` | [#27](https://github.com/aragos/aor/pull/27) |

### Continuing implementation

1. Read applicable instructions, inspect Git state and preserve unrelated work. Continue on a feature branch.
2. Resolve the two pending project rulings above before removing their typed guards. Add focused
   regressions for the selected policy and update rules, this checkpoint and the playing guide.
3. Review the stacked PRs; do not merge automatically. Existing original development game files
   are unsupported under the current-format-only policy.

For integrated code checks, use the existing commands:

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
```
