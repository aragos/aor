# Engine guide

[Core](../core/src/commonMain/kotlin/dev/aragos/aor/) implements [rules](rules.md) for JVM/JS without
IO, storage, auth or controller execution. File names below are relative to that package.

## Follow an action

| Owner | Contract |
| --- | --- |
| `TextCodec.kt`, `Entry.kt` | Parse locale-independent [commands](commands.md) into typed actions/outcomes. |
| `Game.kt` | Apply immutable transitions; typed rejection leaves state unchanged. Normalize until a player decision, authority request or completion. |
| `TurnFlow.kt`, `TurnEngine.kt` | Replayable phase/activity/interruption frames; nested decisions retain outer ownership, without callback/coroutine state. |
| `Prompt.kt`, `GameView.kt`, `*Projection.kt` | Viewer-safe decisions, choices and consequences; clients never infer legality from prose/geometry. |

## Find a rule owner

| Concern | Files |
| --- | --- |
| Setup, bids and order | `SetupDeal.kt`, `CapitalSelection.kt`, `TokenPurchase.kt`, `TurnOrderSelection.kt`, `OrderBoxes.kt` |
| Phases / Purchase | `TurnCommand.kt`, `TurnEngine.kt`, `PurchaseDecisions.kt` |
| Prices, advances, misery, income | `AdvancePurchases.kt`, `PlayerAdvances.kt`, `PlayerEconomy.kt`, `MiseryRules.kt` |
| Cards, leaders, War | `CardGame.kt`, `CardEffects.kt`, `LeaderRules.kt`, `WarRules.kt`, `CardMarkerPlacements.kt` |
| Expansion / competition | `ExpansionReach.kt`, `ExpansionRules.kt`, `BoardGame.kt`, `BoardPlacementRules.kt`, `CompetitionRules.kt` |
| Deck / epochs / provenance | `DeckHistory.kt`, `GameOutcome.kt`, `TurnStateOperations.kt` |
| Automatic public receipts / private simulation | `EffectReceipt.kt` plus effect owner / `ActivityPlanning.kt` |
| Static data / variants | [Catalog ownership](map-design.md#catalog-ownership) |

Keep mechanics/invariants with their types and owners; generic engine types stay setup-independent.
Internal calculations trust established state; arithmetic/reach helpers remain directly testable.

## Replay and identity

Record actual dice, permutations, draws and transfers; replay never calls live authority. Record all
resolved configuration and freeze at `start` so changed defaults cannot alter saves. A changed prompt
sequence can require a rules upgrade even if syntax is unchanged.

Activity identifies one phase's work including interruptions. Replay-derived `activity.decisionEntry`
identifies the presentation decision even when actor/prompt repeats; it is neither a revision nor a
command. Private readiness does not replace it. Clients submit committed revision.
Transitions expose applied/normalized state and deterministic public receipts with actual resource
endpoints/causes. Commands/outcomes remain stored truth. Suffix edits must preserve logical random
requests (participants, context, occurrence) and outcomes; equal dice/counts do not prove continuity.
[Architecture](design.md#history-and-undo) owns permissions and prefix rewind.

## Information and projection

Opaque hand references never encode identities. Project public facts plus only the viewer's private
facts, including in choices, diagnostics, quotes, receipts and history; counts cannot reveal secrets.
Private-plan simulation covers only the owner's remaining Purchase/Buy Card activity. Replace hidden
deck/hands with count-preserving opaque placeholders; assume earlier actors leave relevant inputs
unchanged. Do not alter live actor/offers. Apply a reachable prefix, returning typed blocked/invalid
results; stop at missing answers, unknown draws, authority requests or the boundary.
[Architecture](design.md#private-plans) owns persistence/execution.

## Opponent policies

[AI](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/) consumes numerical viewer-safe observations,
never authoritative `GameState`. Core projections own competition probabilities and Wind/Watermill
quotes; candidate generation adds bounded numeric/target alternatives to nonexhaustive command hints.
One economic evaluator serves Easy (reasonable variation), Normal (current ranking) and Hard (bounded
resource planning). Refresh authoritative offers after every action; no unseen-card knowledge or
full-game search. Capital evaluation includes initial hand, nearby opportunities, count and map;
epoch/variant features permit tuning without duplicating rules.

[Training](../training/src/main/kotlin/dev/aragos/aor/training/) supplies ordinary sampled outcomes;
policies see only their seat, and sealed decisions share the pre-batch position. Fixtures/whole games
check legality, privacy, decision coverage, replay and cross-target behavior. Stored moves/outcomes
make earlier play independent of model changes. [Contributing](contributing.md#opponent-training) owns
operation; [checks](contributing.md#checks) selects tests. Preserve physical-card/token/marker conservation,
timing and live/replayed equivalence.
