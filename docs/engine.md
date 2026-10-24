# Core Game Engine

Status: the one-epoch engine is implemented; later epochs, planning and undo remain design work. The engine implements [rules.md](rules.md); application architecture and policies live in
[design.md](design.md). Rules section references below refer to rules.md.

## 1. Contract

The engine lives in `core/src/commonMain/kotlin/dev/aragos/aor`. It has no IO, controller execution or storage.
Its inputs are versioned declarative data, configuration commands, player actions and recorded authority outcomes.
Replay of the same inputs always produces the same state on JVM and JS. Initially the server runs the engine.

```text
state.step(entry)        → new state or RuleViolation
state.prompts()          → required player decisions or system outcomes
state.viewForPlayer(id)  → public state plus that player's private information
state.legalChoices(id)   → choices permitted by that player's information
projectPlan(view, plan)  → partial projected state, entry results and unresolved inputs
```

`step` is pure. Reject an illegal entry without changing state. After acceptance, run automatic bookkeeping and
transitions until outside input is needed. System outcomes must satisfy the current request. Replay uses recorded
outcomes and never generates randomness.

## 2. Log and configuration

Each game records its rules and data versions, registered players, creator and controller assignments.
The creator's role is independent of registration order. The log records player actions and authority outcomes.
Game options are setup commands. `start` validates and freezes configuration; setup cannot be edited once play starts.
Waiting players and completion status are derived from state and prompts.

All clients submit the same commands using locale-independent text with lossless round trips.
One entry occupies one line; `!` introduces a system outcome. The complete implemented syntax,
parameter meanings, decision windows and recorded-outcome formats are documented in the
[command language reference](commands.md).

Record dice, shuffle orders, draws and random trade selections as outcomes, not an RNG seed. Tests may supply any
valid outcome; live outcomes come from the server. Storage, secret-batch acceptance, revision authorization and
edit constraints are specified in [design §3–4](design.md#3-game-storage-and-configuration).

For revision validation, replay the retained prefix and edited suffix with the ordinary engine. Identify the first
invalid entry or unresolved input, never skip it. Expose each phase activity's id, owning player and entry boundaries;
nested frames retain the activity id. Expose other-player actions, random outcomes and simultaneous-batch boundaries
so the service can enforce undo policy without inferring it from display text.

Revision validation must also compare required random requests with the original revision. Preserve every recorded
event and its unchanged compatible outcome, matched to the same logical request. Reject added or missing random
events and missing, ambiguous or incompatible outcome matches, even if the total event count is unchanged. This
includes draws, random trades, epoch shuffles and edits that truncate the log. Report a typed violation; never request
replacement randomness to make an edit valid. Ordinary new live actions may still request fresh outcomes.

## 3. State, flow and validation

`GameState` is immutable and contains:

- **Players:** stable identity, capital (unset before selection), cash, written cash, tokens in stock/pool/board,
  domination markers, hand references, advances and purchase turns, misery, fleet type/level and capital-choice order.
- **Board:** pieces per province and player, with white/colored status.
- **Turn:** phase, order and printed box numbers, shortages/surpluses, leaders and Leader Display, protection money,
  Patronage claims, active events and pending wars. Track Renaissance/Wind/Watermill use, Cathedral uses per opponent,
  shipping delivered per destination, token-card purchases/shared price and expansion-bonus eligibility. Distinguish
  purchase-phase credit eligibility from immediately active abilities and current-turn MR.
- **Deck/history:** draw and discard piles, removed cards, persistent event-voiding/leader-bonus flags, recorded
  end-of-turn cash and any pending end reason.
- **Configuration and flow:** resolved options and the current stack of frames.

A frame is a small state machine for a phase, a player's activity, a competition, a war or a decision window. The top
frame emits a prompt or automatically pushes/pops frames. The stack is replayable state; use no domain coroutines or
callbacks.

Prompts address one player, several players making simultaneous decisions, or the authority supplying an outcome.

Open optional windows only for eligible players: Renaissance, Patronage claims and an attacker's or defender's Cathedral declaration.
Accept an action or pass. Mandatory interruptions include Civil War's loss choice, the War loser's province choices
and capital-order-based token-bid tie choices. Preserve nested activity ownership through these interruptions.

Return typed violations with stable message ids, typed arguments and affected entity ids. Enumerate small finite
legal-choice sets, such as playable cards or reachable provinces; validate large spaces such as token allocations.
Choices and diagnostics must not expose another player's private information.

### Rule-sensitive boundaries

Use the detailed rules as the single source of mechanics. Frames must retain these timing distinctions:

| Sequence      | Engine requirement                                                                                                                                                                               | Rules             |
| ------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------- |
| Setup         | Deal before capital selection; retain capital-choice order for later turn-order ties. Setup ends after capital selection.                                                                        | §3                |
| Order of Play | Begin every turn, including turn 1, with signed token purchases, stock caps and tie choices. Assign printed boxes for the starting player count. Order and pool apply to the current turn.       | §5.1              |
| Draw/Buy Card | Cancellation precedes draws; Master Art's discard does not require Urban Ascendancy's purchase.                                                                                                  | §5.2–5.3          |
| Play Cards    | One ordinary round per player; War re-rolls at following players' round starts. Distinguish Leader Display and Patronage eligibility; clean up leaders/protection money after Purchase.          | §5.4, §11.4       |
| Purchase      | Ship upgrade → advances → stabilization → relief per player. New prerequisites/abilities apply immediately; category credits exclude this phase's purchases; MR uses this turn's purchases.      | §5.5, §7.3, §10.2 |
| Expansion     | Civil War order change → Renaissance opportunity after that change → Holy Indulgence → placement. Track shared token-card price, per-player limit and qualifying new markers.                    | §5.6, §10.3       |
| Income        | Flip, income, New World, Interest & Profit, then shortages/surpluses and majority effects. Do not double income or stack identical markers on one payout.                                        | §5.7              |
| Deck          | Advance epochs only on a draw request from an empty pile; recycle discarded circle cards, never cards still held. Final exhaustion prevents every further draw.                                  | §11.1             |
| Chaos         | Discard the hand; retain neutral pieces and Nationalism, prohibit other players' advances against them, stop player prompts and retain starting-player tables.                                   | §7.1              |
| Ending        | Log the trigger, finish the current turn and Income, then Final Card Play before cleanup/scoring. Preserve same-turn effects and final shortages/surpluses; enforce forced plays and exceptions. | §8                |

Preserve once-per-turn limits, general rounding up and distinctions between misery spaces and values. Do not resolve
uncertain rules implicitly. Event-rebuilt marker eligibility follows the configured `RebuiltMarkerBonus`
policy; voluntary rebuilds never qualify. Caravan attack-through follows the official errata recorded in
[rules §14](rules.md#14-open-questions).

## 4. Information boundaries

Use stable card references with `Known(value)` or `Hidden` identities. References must not encode secret identities.
Player projections expose public state and only that player's private information, including their hand and draws.
Playing a card reveals it; improved-setup returns do not. Apply the same permissions to history, plans and diagnostics.

The server may inspect hidden values for authoritative validation. Redact actions as well as outcomes when serving
logs: hands, private draws/discards/returns, trades, shuffles and unrevealed bids require appropriate treatment.
Simultaneous batches take effect atomically; bid reveal does not make returned-card identities public.

Future client replay may consume redacted logs with placeholders such as `!draw venice <hidden>`. It must reproduce
public state and the viewing player's private information without inspecting unknown identities. Hidden-dependent
validation remains authoritative; visible effects must be applicable without leaking their hidden causes.

## 5. Planning

A plan uses ordinary command objects/text plus a base revision and log position. Projection operates only on the
viewing player's information; it never queries future live randomness or other players' hidden values.

Apply the valid reachable prefix. Return a projected state, per-entry results and unresolved prompts/inputs. At a
missing decision, hidden prerequisite or random request, preserve the state reached and mark dependent entries blocked
with a cause. Mark entries invalid under known state with typed diagnostics; never pretend blocked entries executed.

Incomplete commands may annotate intended targets and known costs without applying effects. Explicit hypothetical
inputs can extend projections but must be marked as assumptions. This represents partial plans beyond the current
turn without inventing opponents' choices. Projection never changes committed state. Private draft storage and
explicit submission are in [design §6](design.md#6-private-plans).

## 6. Declarative data and effects

Define data in Kotlin DSL declarations under the core sources. Declarations construct immutable values: no gameplay
callbacks, randomness, IO or state-dependent logic. Use stable ids and localization keys. Complex behavior is code
keyed by data ids.

| Definitions    | Contents                                                                                                                                            |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------|
| Provinces      | Id, geographical area (none for overseas), optional overseas grouping, market number, commodities, capital, coastal status and access requirements. |
| Adjacency/seas | Symmetric land/coastal links, sea membership and ocean flag, directed satellite support.                                                            |
| Advances       | Id, letter, category, cost, credit, prerequisite letters, MR and purchase misery.                                                                   |
| Cards          | Id, epoch, circle/square, kind, commodity choices, conditional leader discounts or event id.                                                        |
| Tables         | Commodity values/rolls, payout lengths, misery ladder, income, order boxes and stabilization.                                                       |

Build-time validation checks unique ids, all references, symmetric links, satellite targets, commodity values,
overseas commodities/areas in play, advance category totals (300/300/300/300/400/500), leader targets and epoch card
counts before setup adjustments. Validate the supported configurations' data, prerequisites and effect handlers.
Required missing data prevents a playable configuration; placeholders belong only in labeled test fixtures.

The static board and economic definitions and their source references are recorded in the
[catalog review inventory](data-inventory.md). Printed payout lengths are transcribed and all recorded connection
questions are resolved, including the user-confirmed absence of Gallipoli–Angora contact. Catalog validation and
reference readiness cover all four supported player counts without changing gameplay.
Recycling classification uses the current Q7 reading.

Implement behavior at fixed hook points:

| Hooks                                                                             | Responsibilities                                                                                                              |
|-----------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------|
| `advancePrice`, `onAdvanceBought`                                                 | Credits, leaders, Written Record, Patronage, Institutional Research, purchase misery and fleet changes.                       |
| `placementReach`, `competitionRequirement`, `competitionRoll`, `afterCompetition` | Movement/access, capacity, satellite/Nationalism/capital modifiers, military ties, Proselytism, Cathedral and Wind/Watermill. |
| `payoutBox`, `incomePhase`                                                        | Shortage/surplus, Industry, Middle Class, New World and Interest & Profit.                                                    |
| `stabilizationCost`, `miseryReliefCost`                                           | Middle Class and Enlightenment.                                                                                               |
| `eventImmunity`, `phaseStartWindow`                                               | Immunities, Renaissance and Expansion's ordered start effects.                                                                |

Return structured modifiers and explanation trees with values, operations and source ids, for example
`Modifier(+1, source = Satellite(milan))`. Rendering and translation belong to presentation code.

Events use frames for targets, victim decisions and repeated War rounds; no general effect DSL is needed. Persistent
voiding and conditional bonuses belong in history, not just the current-turn cards. Preserve these exceptions:

- Reach starts from colored pieces or the home capital, not new pieces except Caravan attack-through. Enforce unused
  areas, access advances, per-destination shipping and overseas occupancy limits (rules §6).
- Count existing attacker tokens, combine multi-defender modifiers correctly and apply capital doubling after
  Nationalism. Cathedral precedes rolling, cannot defeat a Cathedral-owning defender and never applies to War. Return
  all committed attackers on failure. `AttackRequirement` rejects any commitment other than the exact
  calculated requirement, counting existing attacker tokens before added tokens (rules §6.6).
- Wind/Watermill requires taking controlled territory, both players holding cards, payment and a successful roll.
  The winner chooses the card given; the card taken is random (rules §6.6).
- Printed Word rebates occur when a leader is played for already-owned advances; neither Patronage nor Written
  Record applies to rebates (rules §10.2).
- Burdens still count for stabilization; only Master Art or Wind/Watermill permits their voluntary removal/trade.
  Chaos discards the entire hand. Event marker reductions use stock tokens, then pool tokens, else leave an empty
  province (rules §5.4, §7.1, §11.4).

## 7. Rule options

Replay builds `GameOptions` from setup commands and checks supported choices before `start`. Engine rules consume
resolved functionality, not edition labels. Data-changing options select definitions; behavior-changing options select
handlers. Application defaults, feature catalog and rollout scope are
in [design §3](design.md#3-game-storage-and-configuration).

Use these recorded project rulings and current readings of the remaining open questions:

- **Q7:** remove leaders, Armor, Stirrups, Long Bow, Gunpowder and Mongol Armies; recycle other cards.
- **Attack commitment:** reject excess tokens; existing plus added tokens must equal the requirement.
- **Rebuilt-marker bonus:** support `exclude` (default) and `include`, configured before `start` and
  recorded explicitly in the replay log. `RebuiltMarkerBonus.counts` implements both eligibility policies.
- **Q14:** reinsert improved setup's five held-out cards after turn 1's draw for 3–4 players or turn 2's for 5–6.

Resolved baseline rules are not extra switches: turn 1 draws, Holy Indulgence shortfalls use cash, unused coastal
waters cannot be traversed and Industry adds a box to the otherwise applicable payout.

## 8. Verification

Start with the existing core package; introduce packages only as implemented mechanics grow. Separate pure arithmetic
and reach/combat functions from flow so they can be tested directly.

- **Examples:** Genoa's 15-token capital defense, three Wine markets paying $45, both stabilization-in-misery examples
  and the Interest & Profit cap.
- **Scenarios:** readable header/text-log fixtures covering rule-sensitive timing, hidden choices, persistent effects,
  shipping, Cathedral, successive shortages, Chaos and both end triggers through Final Card Play.
- **Invariants after accepted entries:** 36 tokens per player across stock, pool, board and pending competition,
  counted once; 25 markers across mat/board; nonnegative cash; market capacity outside competitions; dominated markets
  hold only a marker; satellites hold at most one token and never a marker. Live and replayed states must match.
- **Codec:** every command, including configuration, round-trips through print/parse.
- **Revision validation:** accept compatible edits and reject changed setup, added/removed random requests, unrelated
  outcome substitutions, invalid card/deck matches and truncation across recorded events. Preserve exact outcomes;
  rejection must not generate randomness or change state.
- **Information:** full and projected/redacted replay agree on public and viewing-player private state. Test private
  setup returns, discards, trades and shuffles; choices, explanations and diagnostics must not leak secrets.
- **Planning:** partial, invalid, future and blocked commands return honest diagnostics/projections without modifying
  the live log or consuming live randomness.

Service/controller/revision and localization checks belong to [design §8](design.md#8-delivery-and-verification).
