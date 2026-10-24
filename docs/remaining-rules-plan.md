# Remaining rules implementation plan

Planned on 2026-10-24 against the completed [one-epoch delivery](minimum-full-game-plan.md).
Code baseline: `b8c48e224ca2c3d94436b9e8e1413f3489bd7c71` on `main`.
Implementation is integrated on `feature/all-rules-implementation` using three isolated worker
worktrees. The card, deck/setup, variant, service and browser packets are implemented and integrated. [Rule coverage](rule-coverage.md), [card sources](card-inventory.md)
and [variant sources](variant-inventory.md) distinguish executable evidence from source confidence.

The user approved this plan's Q14 timing on 2026-10-24: after regular draws on turn one for
3–4 players, or turn two for 5–6. Alternate timings may be added later as separate options.
Improved setup combined with commodity-only initial dealing remains explicitly unsupported
under the user's 2026-10-24 ruling: "get three, choose two" is incompatible with reduced
initial-deal decks for now. Both setup questions are resolved. The approved timing is a project
rule; published sources still disagree. The user also approved the implemented Pirates target
obligation, double-adjustment clearing, second-edition Patronage credit of 20, and immediate
one-time 1/2/3 relief at 2/3/4 category thresholds on 2026-10-24. No rule decisions remain pending.

Final verification: **677 core JVM tests and 85 server tests pass**; Kotlin/JS compilation,
formatting checks and the production web build pass. Standard and preset games cover every
supported player count, recorded histories replay exactly, and browser checks cover real private
setup plus the new card and variant controls. Packets F0, D1, D2, V1, R1, S1, V2, V3, R2, A1, A2
and Q1 are integrated. Showing-cards/bribery enforcement remains excluded.

## Delivery contract

- Games have a fixed three-epoch deck sequence. Remove the creation field, configuration command,
  selectors and stored mutable game-length setting. Do not replace them with a three-only selector.
- Preserve the other ending trigger: acquiring the 26th advance still ends the current turn.
  Three epochs are the fixed maximum deck sequence; that existing trigger can finish a game sooner.
- Finish all baseline AH1996 rules, then implement every optional rule listed in
  [rules §13](rules.md#13-variants). Standard setup and AH1996 behavior remain the defaults;
  both reviewed rebuilt-marker policies remain supported. Every variant also uses three epochs.
- Preserve the reviewed map, catalogs, exact-attack policy, marker-forgo/reuse ruling and quadratic
  payouts. Changes to these require an identified rule correction, not incidental refactoring.
- Record resolved functional options before `start`, freeze them at `start`, and reject unsupported
  options or combinations with typed diagnostics. Edition presets expand to explicit options.
- Keep replay deterministic, hidden information private, revisions as accumulated deltas, and
  optimistic concurrency intact. Use current-format-only compatibility; add no legacy decoding.

Showing-cards and bribery rules will not be implemented, per the user's 2026-10-24 instruction.
Do not add conduct reporting, detection, penalty commands or related controls.

Bots, accounts, general undo, private planning, automatic plan execution, additional translations
and a detailed graphical board are application work outside this rules delivery. Existing design
documents retain their contracts for that separate backlog.

## Starting gaps and delivery ownership

The table records the gaps found before implementation and their delivery owners. Completed
mechanics were retained; the closing [coverage audit](rule-coverage.md) maps each subsection to
implementation and executable evidence.

| Rules | Starting evidence / required work | Packet |
| --- | --- | --- |
| §1–2, §6, §9–10 baseline data and arithmetic | Reviewed board/economy, all 26 advances and current rulings exist. Preserve their regression coverage; audit alternate-rule consumers for hard-coded baseline data. | V1, V2, Q1 |
| §3 game length | `SetEpochs`, state/view fields, initial revision validation, HTTP creation and two browser selectors assume a configurable one-epoch game. Remove the whole contract together. | A1 |
| §3 standard setup; §4–7 turn flow | Implemented. Regress at epoch boundaries and across nested decisions, Chaos and ending triggers. | D2, R1, Q1 |
| §5.4 showing cards / bribery | Explicitly excluded from implementation by the user; retain as tabletop guidance only. | Excluded |
| §7.4 Religious Strife | Religion-based misery and Enlightened Ruler protection are missing. | R1 |
| §8 endings | Current draw exhaustion ends any deck and drops queued draws. Only epoch-three exhaustion may do that; preserve the 26th-advance trigger and final-turn rules. | D2, Q1 |
| §11.1 recycling | No current epoch, discard/removed piles or later shuffle requests. References are derived from epoch-one deck size. | D1, D2 |
| §11.2 commodities | 11 of 27 physical commodity cards exist; add 16, with unique physical identities across decks. | D1 |
| §11.3 leaders | 5 of 19 leaders exist; add 14 and Marco Polo's persistent Mongol Armies condition. Preserve Walter's same-turn condition. | D1, R1 |
| §11.4 later events | Add Black Death, Gunpowder, Long Bow, Mongol Armies and Religious Strife. | D1, R1 |
| §11.4 recycled events | Pirates/Vikings needs 1/2/3-marker selection; permanent voids and same-turn cancellations need epoch-aware semantics. | R1 |
| §12 information / randomness | Existing privacy and outcome recording must cover every new shuffle, private setup return and interruption. | D2, S1, A1, A2 |
| §13 improved setup | Added Timber, five held-out cards, deal-three/keep-two, secret returns, reshuffling and timed reinsertion. | S1 |
| §13 second-edition economy | Alternate advance ordering/costs/prerequisites, credits, relief, income-misery owner and immediate Master Art discard. | V1, V2 |
| §13 second-edition interactions | Alternate Wind/Watermill, Written Record takeover trade and three-box Patronage display. | V3 |
| §13 tournament options | Immediate Master Art discard, St. Gall support and commodity-only initial card. Reuse identical mechanics across presets. | V1, V2, S1 |
| §14 source questions | Q7 verified for all 64 cards against the user-supplied Liumas reference. Source disagreements for Q14 documented; the user approved the implemented timing on 2026-10-24. | F0, D1, S1 |

## Contracts to freeze before workers edit shared code (F0)

Owner: integrator. Read the rules, engine, design and current command reference. Check source text
where the summaries are incomplete; record exact source locators in the review inventory. The
repository's unlicensed implementations remain cross-checks, not sources to copy.

Freeze these contracts in a short initial PR, with their model changes and meaningful focused tests:

1. A finite `Epoch` type with a fixed final value of three; distinguish a card's printed epoch from
   the game's current epoch. Game length is not an option. The rollout packet removes the old API.
2. One deck/history owner for current epoch, ordered draw pile, discard/removed cards, setup
   reserves and a monotonically increasing draw-reference sequence. Avoid duplicating card location
   in turn effects. Effects can retain public play facts after a physical card changes location.
3. A shuffle request carries its exact eligible card multiset and purpose. The server shuffles only
   that request; the engine validates the recorded permutation and resumes the suspended activity.
   Keep `!shuffle <card-id> ...` as the single canonical outcome unless a demonstrated ambiguity
   requires a change. Parsing must no longer require exactly 29 arguments.
4. Printed physical card IDs are unique across all 64 cards; draw references are opaque occurrence
   IDs, unique across repeated recycling and private transfers. Use typed identities internally
   and canonical strings only at boundaries. Returning a setup card is not playing/discarding it.
5. Immutable resolved functional rules select both static definitions and behavior. Catalog,
   purchase quotes, score values, reach, event calculations and UI projections consume the same
   selected definitions. Never change the global baseline catalog to implement an option.
6. New player decisions use ordinary commands and replayable frames, preserving the outer activity
   owner. Setup returns use durable atomic secret batches, not a sequence of publicly visible plays.
7. Freeze proposed option keys, allowed values, commands, diagnostics, view fields and localization
   keys in the command/design documents before service and UI adapters depend on them. Distinguish
   authoritative hidden shuffle candidates from public epoch and pile counts.

Source checks must settle the following before the affected feature is enabled: all recycling
symbols; whether Black Death replacements differ from ordinary event reductions; Pirates/Vikings
selection obligations when fewer targets exist; newer military precedence and tie-breaking; Marco
Polo timing; improved-setup return/reinsertion shuffles; second-edition leader-display movement,
relief timing and takeover-trade limits. The user resolved combined setup compatibility on
2026-10-24: reduced initial-deal decks cannot be combined with "get three, choose two" for now.
The user-approved Q14 timing is after turn one's regular draws for 3–4 players and turn two's
for 5–6, as documented in engine §7. Alternate timings are deferred separate options.
Do not silently promote other unresolved readings to reviewed rulings.

Readiness checks belong to the selected rules/catalog, not a list of implementation stages in
`GameState`. An unavailable variant must be rejected at configuration/start, not fail midway through
play. If a source cannot settle a decision, isolate the affected rule and present a concrete ruling
proposal; continue independent work.

## Work packets

Each packet includes implementation, focused tests and its command/reference updates. Paths below
are relative to `core/src/commonMain/kotlin/dev/aragos/aor/` unless stated otherwise. New focused
files are preferred where they reduce shared-file conflicts. The integrator owns wiring through
shared entry points; workers must coordinate changes outside their stated ownership.

### D1 — Complete physical card and effect catalogs

Depends on F0. Own `Cards.kt`, `CardEffectData.kt`, new catalog files and a new
`docs/card-inventory.md`. Branch: `feature/three-epoch-card-catalog`.

- Add printed epoch metadata, complete physical IDs, commodity choices, leader discounts and
  recycling symbols. Decks contain 29/22/13 cards; totals are 27 commodities, 18 events, 19 leaders.
- Transcribe all later leaders from rules §11.3; model conditional discounts without a Walter-only
  property. Separate persistent Mongol Armies history from the current-turn Crusades condition.
- Publish a human-reviewable per-card manifest and sources. Validate all references, physical ID
  uniqueness, per-epoch/kind counts, complete effect coverage and valid advance targets.
- Adding finite event/leader IDs can break exhaustive dispatch. Coordinate that change with R1;
  stage independent declarations first if needed. Do not add fake handlers to make a branch compile.

Done when the full manifest is reviewed and catalog tests verify every printed value and symbol,
including duplicate commodities represented by distinct physical IDs.

### D2 — Deck lifecycle, recycling and draw suspension

Depends on F0; use D1's frozen catalog API while D1 is in progress. Own new deck lifecycle files,
`TurnStateOperations.kt` and focused deck tests. Coordinate `Game.kt`, `Entry.kt`, `GameOutcome.kt`,
`Prompt.kt`, `TurnEngine.kt`, `TurnCommand.kt` and `CardGame.kt` integration through the integrator.
Branch: `feature/three-epoch-deck-flow`.

- Route plays, Master Art discards and Chaos hand loss into the physical discard/removed model;
  trades transfer ownership without discarding. Square cards cannot reenter a later deck.
- On a draw request from an empty epoch-one/two pile, suspend that draw, request a shuffle of the
  next printed deck plus discarded circles, advance once, and resume the same request. Do not
  advance merely because the last card was consumed. Cards still held never join that shuffle.
- Preserve every queued beneficiary and surrounding decision across a boundary; skip eliminated
  beneficiaries according to existing Chaos rules. Several queued draws can span a shuffle.
- Drawing the last epoch-three card triggers the ending and clears undeliverable draws. Reject
  draws/shuffles beyond epoch three. If a 26th-advance ending is pending, draws still follow the
  ordinary deck rules through the remainder of that turn.
- Allocate fresh draw references independently of deck size. Repeatedly drawn copies and traded
  references must never collide with cards currently held.

Done when replay tests cover both boundaries from regular, Urban Ascendancy, expansion-purchase
and expansion-bonus draws, including nested decisions and Chaos. Include invalid shuffle
permutations, held-card exclusion, square removal, reference uniqueness and final exhaustion.

### V1 — Resolved variant options and alternate static definitions

Depends on F0. Own new functional-option/resolved-rule types, alternate advance definitions and
catalog selection helpers. Coordinate `GameCatalog.kt`, `Advances.kt`, `BoardConnections.kt` and
shared command/state integration. Branch: `feature/resolved-rule-variants`.

- Define explicit choices for setup, initial-card restriction, commerce advance table,
  Wind/Watermill behavior, Written Record behavior, income misery-relief owner, category credits,
  misery-relief system, Patronage system, immediate Master Art discard and optional St. Gall
  support. Keep the existing rebuilt-marker choice. Epoch count is absent.
- Presets expand into this same option set and record all resolved values; do not put edition
  checks throughout domain code. Define and document supported combinations from rules evidence.
- Keep advance identity stable when Improved Agriculture/Wind/Watermill swap printed letters,
  price and prerequisites. Resolve command letters and displayed letters through the selected
  table. Scoring and leader references must use the selected advance identity/value correctly.
- Validate both full tables, credits, prerequisites, support graphs and rule compatibility. Baseline
  totals and printed-arrow rules remain baseline-specific validations, not universal assumptions.

Done when selection, canonical option text, invalid combinations and preset expansion have focused
coverage, and pure baseline/alternate catalogs can coexist without global mutation.

### R1 — All later card effects and recycled-card behavior

Depends on D1 and the D2 epoch/history contract. Own `CardEffects.kt`, `LeaderRules.kt`,
`WarRules.kt`, new later-event helpers and focused effect tests. Coordinate frame wiring in
`CardGame.kt`, command choices and board adapters. Branch: `feature/later-epoch-card-rules`.

- Implement Black Death area selection, token returns, marker reductions and any required owner
  replacement choices. Restrict areas to this game's board; preserve physical pieces and stock.
- Implement Gunpowder and Long Bow for competitions and War, including opponent exclusions,
  combinations, modified ties, and the effect on already-played Armor/Stirrups. Distinguish permanent
  obsolescence of unplayed cards from opponent-specific military effects within the turn.
- Mongol Armies pays $10, permanently voids Crusades and enables Marco Polo's conditional credits
  with the source-defined timing. Verify both discount pricing and Printed Word rebates.
- Religious Strife applies Religion-based misery with Enlightened Ruler protection, clears a Papal
  Decree ban in the same turn, and makes the appropriate permanent epoch-three burden. Earlier
  Strife must not prematurely create that burden. Preserve voids through later turns/epochs.
- Pirates/Vikings uses the current epoch, including when an old card is held into a later epoch.
  Support multiple distinct targets and interrupted replacements without partial invalid mutations.
- Add all later leaders to normal play, Patronage, Written Record and Printed Word behavior.
  Verify Walter still depends on this turn's Crusades, independently of Marco Polo's condition.

Done when every later card has pure-rule and command-flow coverage; include no-target plays,
burdens, immunity, Chaos during multi-player resolution, and Final Card Play with tied Wars.

### S1 — Improved setup and commodity-only initial dealing

Depends on D1, D2 and V1 contracts. Own new setup-deal/return/reinsertion helpers and setup tests;
coordinate shared setup stages, prompts, outcomes and commands. Branch: `feature/setup-variants`.

- Move one specific epoch-two Timber instance into the initial deck; remove it from epoch two.
  Hold out Crusades, Walter, Rashid, Silk and Spice without treating them as discards.
- Deal three, privately select one return, keep two. Validate ownership and simultaneous returns;
  reveal completion without revealing returned identities. Shuffle returns into the remaining deck
  through recorded outcomes before the next setup activity.
- Reinsert the five reserves at the reviewed Q14 point through a recorded shuffle, once only.
  Track due reinsertion with the setup/deck owner; do not infer it from the size of a changing pile.
- Commodity-only initial dealing must preserve the remaining deck's composition and randomization.
  Reject its combination with improved setup under the approved reduced-deck incompatibility rule.
- Cover 3/4 versus 5/6-player timing, restarts with pending returns, stale replacements, private
  histories and a final turn occurring before a scheduled reinsertion.

Done when ordinary commands complete both setups, all physical cards are conserved and effective
history replay reproduces private hands without generating fresh randomness.

### V2 — Alternate economy and purchase effects

Depends on V1; integrate with D1's leader identities. Own `AdvancePurchases.kt`, `PlayerEconomy.kt`,
`PlayerAdvances.kt` and new alternate-economy helpers. Branch: `feature/alternate-economy-rules`.

- Apply selected costs/prerequisites/credits consistently in quotes, purchases, old-credit timing,
  relief and final scores; remove static-catalog lookups that bypass resolved rules.
- Move per-Income misery improvement from New World to Enlightenment when selected.
- Replace purchased relief with the source-defined Science/Commerce/Communication completion
  relief at the 2/3/4 thresholds. Establish timing, repeated eligibility and Chaos interactions from
  the full rule text; do not infer those details from the short summary.
- Add the immediate Master Art discard as one shared option used by both second-edition and
  tournament presets. Make it an optional ordinary decision even when acquired during Purchase;
  cover burdens, empty hands, declining and resumption before stabilization.
- Ensure the alternate Written Record rule removes the +$10 leader-discount bonus everywhere.

Done when focused tests compare baseline and alternate quotes, acquisitions, relief, Income and
scores, including mixed options and both versions of the commerce chain.

### V3 — Alternate trade, Wind/Watermill and Patronage interactions

Depends on V1, V2 and R1. Same worker as R1 to avoid concurrent edits to leader/card rules.
Own alternate interaction helpers and `LeaderRules.kt`; coordinate `BoardGame.kt`, phase-boundary
and frame changes. Branch: `feature/alternate-interaction-rules`.

- Wind/Watermill offers the last eligible player the selected shortage/surplus adjustment on Grain,
  Cloth, Wine or Metal at the source-defined phase boundary. Baseline takeover trading is disabled
  for that mode. Define what happens when the last player is in Chaos from source evidence.
- Written Record offers its source-defined takeover exchange against a non-owner, once per turn,
  using explicit offers and recorded random transfer. Share machinery with baseline Wind/Watermill
  only where payment, eligibility, choice and timing actually match; test competing triggers.
- Implement the three-box leader display with explicit roles/positions, source-defined movement,
  availability, cleanup and claims. No protection-money behavior may survive in that mode.
- Cover leaders played during Final Card Play, Patronage newly acquired during Purchase, empty
  hands, interrupted exchanges, Chaos and once-per-turn reset boundaries.

Done when each alternative and the full second-edition preset work through ordinary commands and
the baseline interaction tests still pass.

### R2 — Rule-by-rule audit

Depends on F0; final audit follows R1/S1. Own `docs/rule-coverage.md` and focused regressions for
identified gaps. Branch: `feature/rule-coverage-audit`.

- Record each subsection's behavior, implementation owner, relevant tests and any remaining
  source/ruling blocker. Distinguish tested implemented rules from recommendations to players.
  Mark showing-cards and bribery enforcement as explicitly excluded, not missing implementation.
- Audit all card removal paths, final-round exceptions, alternate-rule static lookups and baseline
  behavior affected by the new contracts. Report concrete gaps as focused work, not a second engine.

Done when every in-scope rule has evidence or an explicit unresolved item; no unresolved baseline
rule may be hidden by declaring the entire rules delivery complete.

### A1 — Three-epoch baseline service/UI rollout and removal of epoch configuration

Depends on D1, D2 and R1; R2 audits the completed baseline. Owner: integrator. Branch:
`feature/three-epoch-delivery`. Own shared wiring and server/web changes.

- Delete `Command.SetEpochs`, its parser branch, legal choice and `INVALID_EPOCHS` diagnostic;
  remove `GameState.epochs` as configuration. Reject the removed option with the normal unsupported
  option diagnostic. There is no historical-command compatibility alias.
- Remove `epochs` from `GameService.create`, `GameDriver.create` and `POST /api/games`. Strict HTTP
  field validation rejects the removed field rather than ignoring it. Creation takes player names.
- Replace the initial revision's mandatory `SetEpochs` entry with canonical resolved option entries;
  update `GameStore` initial-revision validation and all creation/replay fixtures. The default
  rebuilt-marker option remains explicit; do not leave an empty or synthetic epoch command.
- Replace the game-view configured length with public current-epoch information (and fixed total
  for display if useful). Remove both creation/configuration selectors and their draft state,
  start-button checks, messages and API types. Show progress as epoch 1/3, 2/3, 3/3 during play.
- `GameDriver` resolves parameterized shuffle requests instead of always shuffling `epochOne`.
  Cover restarts immediately before and after both transitions and pending interrupted draws.
- Bump rules/data compatibility versions when the changed contracts land; change file-format version
  only if its storage envelope changes. Update README, commands, design, engine and both manuals
  together. Earlier development versions remain unsupported; do not migrate them.

Done when a new browser game offers no length control, uses all three decks under ordinary draws,
restarts/replays identically and retains both supported endings. Removing controls alone is not
completion: an epoch-one deck must no longer trigger final scoring.

### A2 — Variant persistence, guided controls and documentation

Depends on V1, S1, V2 and V3; build on A1. Owner: integrator. Branch:
`feature/rule-variant-delivery`. Own server/web adapters and reference documentation.

- Persist every resolved option in the initial/configuration log. Extend durable secret-batch
  support to setup returns, preserving revision checks, atomic publication and private history.
- Render setup choices, private returns, multi-target Pirates, area targets, immediate discards,
  alternate trades, shortage/surplus adjustments and display claims from typed projections.
- Labels for advance letters, costs, credits and prerequisites use the selected rules. Guided
  controls and text commands must agree; unavailable combinations have actionable typed errors.
- Expose presets only when all expanded rules are ready. Record individual choices, not a preset
  name. Verify narrow screens and refresh/restart while another player owns an interruption.

Done when every supported option is selectable before `start`, frozen afterwards, usable in guided
controls and recoverable without privacy leaks or new randomness.

### Q1 — Integrated acceptance and coverage closure

Depends on A1 for baseline, A2 for full optional-rule delivery. Owner: integrator, with focused
review assignments to the three workers. Branch: `feature/all-rules-acceptance`.

- For each of 3/4/5/6 players, complete a fixed-three-epoch baseline game through commands and verify
  final scores plus replay equality. A separate scenario ends by the 26th advance in an earlier
  epoch. Avoid fixtures that force the wrong ending or depend on a one-epoch turn limit.
- Play complete improved-setup and second-edition-preset scenarios. Cover each independent custom
  option and an explicit interaction matrix: setup × initial-card restriction × player count;
  military precedence × War/competition; Marco Polo × Mongol timing × Printed Word;
  Patronage × Written Record; relief × credits × alternate commerce table; immediate Master Art
  × burdens/stabilization; both rebuilt-marker policies × event reductions; overseas Pirates.
- Recover games with a randomness provider that fails if called. Test invalid authority outcomes,
  stale revisions, atomic secret returns and redaction from every seat at both epoch boundaries.
- Verify browser creation, both epoch transitions, all new choice kinds and final scoring; include
  mobile layout. `npm run build` alone does not verify these interactions.
- Close `docs/rule-coverage.md` against rules §1–14 and update implementation status honestly.

## Parallel execution and branch ownership

Execution used one integrator and three workers (four active agents total), with separate
worktrees. The schedule below records the ownership and dependency structure used for delivery.

| Wave | Integrator | Worker A | Worker B | Worker C |
| --- | --- | --- | --- | --- |
| 0 | F0: source questions, contract PR, shared seams and assignment | Read catalog sources | Read deck/setup paths | Read variant sources |
| 1 | Review/integrate shared seams; prepare service/UI adapters against frozen contracts | D1 catalogs | D2 deck lifecycle | V1 variants/catalog selection |
| 2 | Integrate wave 1; own shared command/state wiring | R1 later cards | S1 improved setup | V2 alternate economy |
| 3 | A1 baseline rollout and integrated checks | V3 alternate interactions | R2 coverage audit | Review economy/option interactions; fix owned defects |
| 4 | A2 variant adapters, then Q1 acceptance | Review card/War/display cases | Review setup/deck/privacy cases | Review economy/preset cases |

Waves express available parallelism, not permission to start before packet dependencies are met.
D2 can start pure lifecycle work from the frozen API; integrating real later decks waits for D1.
R1 and S1 may use frozen contracts while dependencies are reviewed, but their PRs must pass with
those dependencies integrated. A1 may ship a complete three-epoch baseline before optional rules;
unready variant controls stay unavailable. A2 is a second, fully verified delivery.

- Each worker uses a separate Git worktree and feature branch. Never run several agents against
  one checkout or the same Gradle output directories. The integrator owns a separate integration
  worktree and dependency branch. Create branches from the exact recorded dependency commit.
- Branches and PRs remain focused. Stack dependent work on its verified parent. Propagate fixes
  through dependent branches while preserving their work; run focused checks on each branch and
  the integrated result. Do not merge unrelated agent branches wholesale into a shared base.
- The integrator owns `Game.kt`, `Command.kt`, `TextCodec.kt`, `Stage.kt`, `Prompt.kt`, `Entry.kt`,
  `GameOutcome.kt`, `TurnEngine.kt`, `TurnFlow.kt`, `GameView.kt`, `GameRuleContexts.kt`,
  `TurnCommand.kt`, `CardCommand.kt`, `CardGame.kt`, `BoardGame.kt`, server/web adapters and shared
  reference documents. Workers supply focused helpers and proposed wiring patches. Apply a patch
  once; do not have every worker independently modify these seams.
- D1 and R1 share catalog contracts but own different files. V3 follows R1 and reuses its worker.
  B's setup work follows its deck work. V2 follows V1 and reuses its worker. Any necessary ownership
  transfer must name the file and commit; suspend other edits to that file until the handoff lands.
- Workers keep tests in packet-specific files. Only the integrator updates shared `Scenario.kt`,
  all-game fixtures, version constants and broad existing acceptance tests.
- Run formatting inside the worker's worktree. Review formatter changes and retain only intended
  edits; do not reformat another worker's checkout.
- Commit, push and open PRs only when requested in the implementation session. This plan itself
  neither pushes nor merges the existing historical PR stack.

## Agent assignment and handoff template

An implementation coordinator can give a worker this prompt, filling in the packet and commits:

```text
Implement packet <ID> from docs/remaining-rules-plan.md.
Read applicable AGENTS.md instructions and the packet's linked rules/contracts.
Base: <dependency branch and commit>. Integration target: <branch>.
Use worktree <path> and branch <packet branch>; preserve unrelated changes.
Own only the packet's listed files and new focused helpers/tests.
Send shared-file wiring changes to the integrator with the required call sites and tests.
Do not silently change an agreed contract or enable incomplete rules.
Complete focused checks, format Kotlin, then check formatting in this worktree.
Return the handoff below. Do not push or merge unless separately instructed.
```

Every handoff includes packet ID, branch/commit, dependency commits, changed/owned files, contract
changes, commands and diagnostics added, tests run and results, source/ruling blockers, and exact
remaining integration edits. The integrator updates a small packet-status table in the implementation
PR with states such as ready, assigned, review and integrated; these are work tracking, not game state.

## Verification gates

Do not write duplicate tests for simple forwarding or documentation changes. Use meaningful rule
examples and command scenarios for changed behavior. Focused checks precede integrated checks:

```sh
./gradlew :core:jvmTest --tests 'dev.aragos.aor.<PacketTest>'
./gradlew :server:test --tests 'dev.aragos.aor.<PacketServiceTest>'
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
```

Run `npm run build` from `web` after browser changes and verify the affected interactions. Run the
integrated suite after dependencies are combined, after the baseline rollout and after variant
delivery. Report actual test counts/results at those checkpoints, rather than copying the old
one-epoch delivery's counts. A packet is complete only when its owned behavior and integration
contract pass; the full rules delivery is complete only after Q1 closes the coverage inventory.
