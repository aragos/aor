# Engine guide

The pure Kotlin engine lives in [core/src/commonMain/kotlin/dev/aragos/aor](../core/src/commonMain/kotlin/dev/aragos/aor/)
and compiles for JVM and JS. It implements [rules](rules.md) without storage, IO, authentication or
controller execution. The server supplies recorded authority outcomes and runs the engine.

## Follow an action

1. [TextCodec.kt](../core/src/commonMain/kotlin/dev/aragos/aor/TextCodec.kt) parses locale-independent
   commands into typed actions. [Entry.kt](../core/src/commonMain/kotlin/dev/aragos/aor/Entry.kt)
   separates player actions from authority outcomes; [commands](commands.md) defines the language.
2. [Game.kt](../core/src/commonMain/kotlin/dev/aragos/aor/Game.kt) applies entries to
   immutable state. Illegal entries return typed violations without changing state. Automatic
   normalization continues until a player decision, authority request or game completion.
3. [TurnFlow.kt](../core/src/commonMain/kotlin/dev/aragos/aor/TurnFlow.kt) holds replayable frames
   for phases, activities and interruptions. [TurnEngine.kt](../core/src/commonMain/kotlin/dev/aragos/aor/TurnEngine.kt)
   advances them. Nested decisions retain outer activity ownership; no callbacks or coroutines
   carry domain state.
4. [Prompt.kt](../core/src/commonMain/kotlin/dev/aragos/aor/Prompt.kt),
   [GameView.kt](../core/src/commonMain/kotlin/dev/aragos/aor/GameView.kt) and the `*Projection.kt`
   files expose viewer-safe decisions, choices and consequences. Rendering never infers legality
   from English messages or geometry.

## Find a rule owner

| Concern | Core files |
| --- | --- |
| Setup, opening deal, bids and order | `SetupDeal.kt`, `CapitalSelection.kt`, `TokenPurchase.kt`, `TurnOrderSelection.kt`, `OrderBoxes.kt` |
| Ordinary phases and Purchase steps | `TurnCommand.kt`, `TurnEngine.kt`, `PurchaseDecisions.kt` |
| Prices, advances, misery and income | `AdvancePurchases.kt`, `PlayerAdvances.kt`, `PlayerEconomy.kt`, `MiseryRules.kt` |
| Card play, leaders and War | `CardGame.kt`, `CardEffects.kt`, `LeaderRules.kt`, `WarRules.kt`, `CardMarkerPlacements.kt` |
| Expansion reach, placement and competition | `ExpansionReach.kt`, `ExpansionRules.kt`, `BoardGame.kt`, `BoardPlacementRules.kt`, `CompetitionRules.kt` |
| Deck lifecycle, epochs and draw provenance | `DeckHistory.kt`, `GameOutcome.kt`, `TurnStateOperations.kt` |
| Public automatic-effect receipts | `EffectReceipt.kt` and the operation producing the effect |
| Private-plan simulation | `ActivityPlanning.kt` |
| Static data and variants | [Maps and catalogs](map-design.md#catalog-ownership) |

Paths in the table are relative to the core package above. Keep mechanics with their logical owner;
generic state, commands and views remain independent of setup-specific rules. Intrinsic invariants
belong in domain types; internal calculations trust established state. Pure arithmetic/reach helpers
remain directly testable apart from flow.

## Replay and identity

Record actual dice, shuffle permutations, draws and random transfers. Replay consumes those outcomes
and never calls the authority provider. Configuration records all resolved options and freezes at
`start`; changing defaults cannot alter an existing game.

Activity identity represents one player's work within a phase, including interruptions. The separate
replay-derived `activity.decisionEntry` identifies a presentation decision and changes even when a
replacement has the same actor and prompt. It is neither a storage revision nor a command.
Simultaneous readiness updates do not replace the decision. Clients submit the committed revision.

Transitions expose directly applied and normalized state plus deterministic public receipts.
Income, Holy Indulgence and automatic order changes retain their actual resource endpoints and
causes. Receipts derive from replay; commands and authority outcomes remain stored truth.

History edits preserve the same logical random requests and unchanged outcomes, including participants,
context and occurrence. Equal dice or event counts alone do not prove continuity.
[Architecture](design.md#history-and-undo) owns authorization and the Linear game prefix exception.

## Information and projection

Hand references are opaque and do not encode card identities. Player projections expose public
state plus only that player's private facts; spectators receive public facts alone. Apply the same
audience to choices, diagnostics, quotes, receipts and historical views. Public counts must not
reveal private discards or choices.

Private-plan projection simulates only the owner's remaining Purchase or Buy Card activity. Hidden
deck positions and other hands become opaque placeholders preserving public counts. Earlier players
are assumed not to change relevant inputs; live actor/choices stay untouched. Apply a valid reachable
prefix and return typed invalid/blocked results. Stop at a missing decision, unknown draw, authority
request or activity boundary without pretending the suffix executed. Service persistence and execution
are in [architecture](design.md#private-plans).

## Verify changes

Start with the corresponding [core tests](../core/src/jvmTest/kotlin/dev/aragos/aor/).
`SupportedGameCompletionTest` covers full games for 3–6 players; `VariantGameCompletionTest` covers
supported options; `ReplayContractTest` covers shared replay. Preserve token/marker and physical-card
conservation, timing, private audiences and live/replayed equivalence. See [contributing](contributing.md)
for checks and [deployment](deployment.md#saved-game-compatibility) for supported versions. A changed
prompt sequence can require a rules upgrade even if parsing is unchanged.
