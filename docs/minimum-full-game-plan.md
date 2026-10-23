# Minimum full game plan

Agreed on 2026-10-23. Status: the user has reviewed the first data milestone;
see [the catalog inventory](data-inventory.md) for validation and recorded source rulings.
The user confirmed that Gallipoli and Angora do not touch, resolving the final data question.
The repeatable turn-flow milestone is next. Expansion policy primitives and the rebuilt-marker setup
option are implemented; board placement, competitions and expansion rewards remain later milestones.

## Target and current checkpoint

Deliver a complete **one-epoch game for 3–6 human players using standard rules**, from creation through final scoring.
Use the AH1996 baseline and the corrections and rulings recorded in [rules.md](rules.md).

The current implementation completes setup and turn 1's Order of Play, stopping at **Turn 1 — Draw Card**.
The reviewed implementation is commit `ef7992e` on `feature/turn-order`, pushed to its upstream branch.
Player access, private hands, sealed submissions, durable storage, recorded randomness, replay, optimistic
concurrency and the browser command interface are already available. The last full check passed 145 tests,
Kotlin/JS compilation, Kotlin formatting checks and the web build. Three-seat browser checks covered signed
purchases, tie choices, responsive layout and restart recovery.

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

## Subsequent milestones

| Milestone                           | Required result                                                                                                                     |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| Repeatable turn flow and economy    | Draw/discard handling, phase progression, income, stabilization, misery, shortages/surpluses and turn bookkeeping.                  |
| Purchases and advances              | Shipping upgrades, prerequisites, prices, credits, leader discounts, misery relief and immediate advance abilities.                 |
| Board play and competition          | Ownership, legal expansion, shipping, domination, combat, losses, token returns and expansion rewards.                              |
| Cards and interruptions             | Commodity payouts, supported leaders and events, targets, victim decisions, repeated War rolls and resuming interrupted activities. |
| Ending and scoring                  | Detect ending conditions, finish the turn, perform Final Card Play, calculate scores and declare winners.                           |
| Playable interface and verification | Expose board state and every required decision, preserve privacy and complete representative games through the browser.             |

Grow replayable phase and activity flow alongside the first mechanics that require nested decisions or recorded
outcomes. Keep each milestone testable through readable command scenarios and, where interactive, the browser.

The full-game acceptance target is a game completed through ordinary player commands, exercising expansion,
competition, purchases and card effects, with correct final scoring, identical replay and successful restart recovery.
Cover both supported ending triggers: exhaustion of the final deck and acquisition of all 26 advances. Finish the
current turn and Final Card Play before scoring, preserving the final turn's effects.

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

### Continuing implementation

1. Read this plan, the applicable coding instructions, [design.md](design.md), [engine.md](engine.md) and the relevant
   rules sections. Inspect Git state before editing; the setup and turn-order changes may still be on feature branches.
2. The reviewed board and economic data and the rule-option changes are submitted as the focused branches above.
   Keep unrelated changes intact.
3. Read [the catalog inventory](data-inventory.md) and recorded rulings, including the attack-commitment and
   rebuilt-marker policies. Start the repeatable turn-flow and economy milestone.
4. Connect the implemented expansion policies to the later board-play milestone; exercise both bonus settings.

For integrated code checks, use the existing commands:

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
```
