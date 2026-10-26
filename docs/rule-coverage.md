# Rule implementation and verification inventory

Audit checkpoint: 2026-10-24. This inventory follows [rules.md](rules.md), including the
project's fixed three-epoch decision and previously accepted map, marker and payout rulings.
It records code and executable evidence separately from source confidence. Passing tests show
agreement with the selected interpretation; they do not establish that a new transcription has
been reviewed against physical components.

Final integrated checkpoint on 2026-10-24: **677 core JVM tests and 85 server tests pass**,
with zero failures or skips. Kotlin/JS compilation, `checkKotlinFormat` after `formatKotlin`, and
the production browser build pass. Real setup/reload/mobile checks and nine controlled card/variant
browser scenarios pass. The evidence below covers the selected implementation policies while
retaining the source-confidence qualifications.

File names below refer to `core/src/commonMain/kotlin/dev/aragos/aor`, core test class names to
`core/src/jvmTest/kotlin/dev/aragos/aor`, and service test names to
`server/src/test/kotlin/dev/aragos/aor`.

## Baseline subsection coverage

| Rule subsection | Behavior and implementation owner | Executable evidence / limit |
| --- | --- | --- |
| §1 Glossary | Domain identities, piece sides, misery steps, advances and finite epochs in `PlayerId.kt`, `BoardState.kt`, `MiseryStep.kt`, `Advances.kt`, `Epoch.kt`. Public presentation in `GameView.kt`. | `ModelValidationTest`, `BoardStateTest`, `PlayerAdvancesTest`, `DeckLifecycleTest`. Glossary prose is explanatory, not a separate action. |
| §2.1 Player components | 36 tokens and 25 markers; stock/pool/board ownership in `PlayerState`, `BoardState`; marker availability derived from board. | `SetupTest.selectingCapitalsInitializesStartingResources`; `BoardStateTest`, `CardMarkerPlacementTest`, `ExpansionReplayTest` conserve pieces. |
| §2.2 Shared components | Three dice, 64 physical cards, 26 advances, economy/commodity tracks. `Cards.kt`, `CardEffectData.kt`, `Advances.kt`, `Commodities.kt`, `EconomyTables.kt`, `DieRoll.kt`. | `EconomicDataTest`, `CardEffectDataTest`, `LaterCardRulesTest.allPrintedDecksHaveDistinctCardsAndCompleteEffects`, `DieRollTest`. All card counts and recycling statuses verified against the supplied Liumas reference. |
| §2.3 Map | Available areas/capitals, seas, coasts, overseas spaces and support arrows in `BoardProvinces.kt`, `BoardConnections.kt`, `Capitals.kt`, `GameCatalog.kt`. | `ProvinceDataTest`, `GameCatalogTest`, `ExpansionReachTest`; reviewed map evidence remains in [data inventory](data-inventory.md). Optional St. Gall support is selected without mutating baseline data. |
| §3 Setup | Creator authorization, initial cards, private bids, repeated tie rolls, paid bids, capital selection and resources in `Command.kt`, `CapitalSelection.kt`, `SetupDeal.kt`, `GameOutcome.kt`. Every game uses epochs ONE/TWO/THREE; no length configuration. | `SetupTest`, `CapitalSelectionTest`, `SetupDealTest`, `RuleOptionsTest.gameLengthIsNotAFunctionalOption`; `ApplicationTest` also rejects the removed integer creation field. |
| §4 Turn structure | Replayable interruption stack and phase progression in `TurnFlow.kt`, `TurnEngine.kt`; Renaissance precedes automatic bookkeeping. | `TurnFlowTest`, `TurnBoundaryTest`, `PhaseOpeningOrderTest`, `SupportedGameCompletionTest`; full command games cover all supported player counts. |
| §5.1 Order of Play | Signed affordable purchases, capped physical stock, written cash, Chaos zero bids, tie insertion and printed boxes in `TokenPurchase.kt`, `TurnOrderSelection.kt`, `OrderBoxes.kt`, `Command.kt`. | `TurnOrderTest`, `TurnFlowTest`, `TurnOrderServiceTest`; private replacement/restart tests exercise persistence. |
| §5.2 Draw Card | First-player buy-off; joint Gold/Ivory buy-off; queued regular draws; empty early piles suspend for next-epoch permutations. `TurnCommand.kt`, `TurnEngine.kt`, `DeckHistory.kt`, `GameOutcome.kt`. | `TurnBoundaryTest.doubleShortageMarkersAreBoughtOffSeparately`, `TurnFlowTest`, `DeckLifecycleTest`, `QueuedDrawTest`, `DeckDrawOriginTest`. Both early boundaries preserve each draw origin and its phase/bookkeeping. |
| §5.3 Buy Card | Once-per-phase Urban Ascendancy purchase and Master Art discard in `TurnCommand.kt`; a purchase remains available on an empty epoch-one/two pile. Physical discards go through `discardCards`. | `TurnFlowTest` covers either action order, one-use limits and optional phase skipping; `DeckDrawOriginTest` verifies Urban Ascendancy suspension across both early boundaries. |
| §5.4 Play Cards | Multiple free card plays, explicit targets, burdens, hidden hands, leader display/protection, stock/pool event replacement in `CardCommand.kt`, `CardGame.kt`, `CardEffects.kt`, `LeaderRules.kt`. | `CardCommandTest`, `CardGameTest`, `CardRulesTest`, `CardMarkerPlacementTest`, `LeaderRulesTest`, `HistoryProjectionTest`. **Showing-card/bribery reporting and penalties are excluded by the user**, not missing features. |
| §5.5 Purchase | Shipping → advances → stabilization → relief; immediate abilities, delayed credits, category bans and religion misery. `TurnCommand.kt`, `AdvancePurchases.kt`, `PlayerEconomy.kt`, `PlayerAdvances.kt`. | `AdvancePurchasesTest`, `PlayerEconomyTest`, `PlayerAdvancesTest`, `TurnFlowTest`; alternate systems have separate tests below. |
| §5.6 Expansion | Civil War order, Holy Indulgence, expansion placement/competition, one escalating-price card per player, unused-token returns and bonus. `TurnEngine.kt`, `BoardGame.kt`, `ExpansionOpening.kt`. | `BoardGameTest`, `TurnBoundaryTest`, `ExpansionReplayTest`, `RebuiltMarkerOptionTest`, `DeckDrawOriginTest`; both expansion draw origins cross each early boundary, and rebuilt policies preserve tokens while changing bonus eligibility. |
| §5.7 Income | White-piece flip, initial-player-count income, New World relief, profit cap, recorded shortage/surplus dice and majority effects. `TurnEngine.kt`, `PlayerEconomy.kt`. | `PlayerEconomyTest`, `PhaseOpeningOrderTest`, `QueuedDrawTest`, `TurnFlowTest.incomeIsFollowedByANewPrivatePurchaseRound`. Multiple queued beneficiaries and Chaos cancellation are tested. |
| §6.1 Bases | Coloured pieces/capital provide expansion bases; white placements do not. `ExpansionReach.kt`, `ExpansionRules.kt`. | `ExpansionReachTest`, `ExpansionRulesTest`; card-awarded coloured markers are covered in `CardGameTest`. |
| §6.2 Overland | Adjacent land movement and access checks. `ExpansionReach.kt`, `BoardConnections.kt`. | `ExpansionReachTest`, `AutomaticRoutingTest`, `BoardGameTest`. |
| §6.3 Caravan | Two-step overland traversal, occupied-intermediate competition, no mixed ship route. `ExpansionReach.kt`, `ExpansionRules.kt`, `BoardGame.kt`. | `ExpansionReachTest`, `BoardGameTest`; existing May 1998 errata interpretation retained. |
| §6.4 Shipping | Shipping upgrades, galley seas, Heavens, per-destination capacity, ocean/overseas access and limits. `ShippingCapability.kt`, `AdvancePurchases.kt`, `ExpansionReach.kt`, `ExpansionRules.kt`. | `AdvancePurchasesTest`, `ExpansionReachTest`, `ExpansionRulesTest`, `ExpansionReplayTest.aRecordedFailedShipmentPreventsASecondOverCapacityAttack`. |
| §6.5 Domination | Market conversion, finite markers, voluntary reduction and no defensive excess placements. `BoardState.kt`, `BoardPlacementRules.kt`, `BoardGame.kt`. | `BoardStateTest`, `BoardGameTest`, `ExpansionReplayTest`, `CardMarkerPlacementTest`; Q15 marker forgo/move contract retained. |
| §6.6 Competition | Exact commitment, home-capital doubling, support, Nationalism, military, Cathedral, Proselytism, dice and takeover trades. `CompetitionRules.kt`, `BoardGame.kt`, `BoardRulesContext.kt`, `WarRules.kt`. | `CompetitionRulesTest`, `BoardGameTest`, `ExpansionRulesTest`, `ExpansionReplayTest`, `LaterCardRulesTest`; optional takeover behavior has separate coverage below. |
| §7.1 Misery track | Typed steps, clamping at zero, irreversible Chaos and player cleanup. `MiseryStep.kt`, `MiseryRules.kt`, `TurnStateOperations.kt`. | `PlayerEconomyTest`, `TurnBoundaryTest`, `DeckLifecycleTest.chaosDiscardsCirclesAndPermanentlyRemovesSquares`. |
| §7.2 Paying in misery | Fewest cumulative steps covering debt; stabilization and shortages respect cash/Chaos. `MiseryRules.kt`, `PlayerEconomy.kt`, `TurnEngine.kt`. | `PlayerEconomyTest` tests track boundaries, large debts, whole-payment stabilization and irreversible Chaos; `QueuedDrawTest` covers debt interrupting earned draws. |
| §7.3 Misery relief | Tier allowance, current-turn ratings, half-price Enlightenment and one-use rating accounting. `PlayerAdvances.kt`, `PlayerEconomy.kt`. | `PlayerEconomyTest`, `PlayerAdvancesTest`; category-completion alternative is separately selected and tested. |
| §7.4 Misery triggers | Famine, Mysticism, Revolutionary Uprisings, Civil War, War, Crusades and Religious Strife. `CardEffects.kt`, `WarRules.kt`. | `CardRulesTest`, `WarRulesTest`, `LaterCardRulesTest.religiousStrifeCountsReligionAndRespectsEnlightenedRuler`; tabletop conduct penalties excluded. |
| §8 End of Game | Final epoch's last draw triggers the current final turn; early epoch exhaustion does not. Acquiring the 26th advance can end earlier. `GameOutcome.kt`, `TurnEngine.kt`, `TurnCommand.kt`, `GameView.kt`. | `DeckLifecycleTest`, `GameEndingTest`, `GameCompletionTest`, `SupportedGameCompletionTest`. Three/four/five/six-player command games assert epoch THREE and exactly three deck shuffles; an advance-ending fixture asserts epoch ONE. |
| §8 Final Card Play | Mandatory non-burdens, retained turn effects, no new buy-off, tied War continuation and final scoring. `CardGame.kt`, `TurnEngine.kt`, `GameView.kt`. | `TurnFlowTest.finalIncomePreservesAdjustmentsAndOpensForcedCardPlay`, `CardGameTest`, `WarRulesTest`, `GameEndingTest`, `GameCompletionTest`. |
| §9.1 Commodity values | Printed values, dice totals and market assignments in `Commodities.kt`, `BoardProvinces.kt`. | `EconomicDataTest`, `ProvinceDataTest`, `GameCatalogTest`. |
| §9.2 Payout | Quadratic count-based payment, Industry, shortage/surplus and dual choices in `CardEffects.kt`, `Commodities.kt`. | `CardRulesTest`, `CommodityPayoutOverflowTest`; user-approved quadratic overflow beyond printed box ten retained. |
| §10.1 Advance table | Baseline 26 identities, categories, costs, credits, relief and prerequisites in `Advances.kt`. Selected alternatives in `RuleOptions.kt`. | `EconomicDataTest`, `GameCatalogTest`, `RuleOptionsTest`; baseline total remains $2100. |
| §10.2 Pricing | Old category credits, Institutional Research, leader discounts, Written Record and Printed Word rebates. `AdvancePurchases.kt`, `PlayerAdvances.kt`, `LeaderRules.kt`. | `AdvancePurchasesTest`, `LeaderRulesTest`, `AlternateEconomyTest`, `LaterCardRulesTest.marcoPoloPlayedAfterMongolsRebatesBothEnhancedCredits`. |
| §10.3 Advance effects | All 26 effects consumed by purchase, economy, reach, competition, card and phase owners. See detailed grouping below. | `AdvancePurchasesTest`, `PlayerEconomyTest`, `ExpansionReachTest`, `CompetitionRulesTest`, `LeaderRulesTest`, `TurnFlowTest`, `PhaseOpeningOrderTest`; alternative consumers use selected rules. |
| §11.1 Epochs/recycling | Current epoch, printed epoch, unique physical IDs, discard/removed piles, held-card exclusion and occurrence references. `Epoch.kt`, `Cards.kt`, `DeckHistory.kt`, `GameOutcome.kt`, `TurnStateOperations.kt`. | `DeckLifecycleTest`, `DeckDrawOriginTest`, `SuspendedDeckReplayTest`, `GameCompletionTest.completeGameConservesEveryPhysicalCard`, `SupportedGameCompletionTest`; conservation accounts for hands, deck, discarded, removed and setup reserves/returns. |
| §11.2 Commodity cards | All 27 physical instances and shared commodity effects. `Cards.kt`, `CardEffectData.kt`. | `CardEffectDataTest`, `LaterCardRulesTest.newCommodityCopiesKeepTheirPrintedEpochAndCommonEffect`; manifest in [card inventory](card-inventory.md). |
| §11.3 Leaders | All 19 leaders, selected-value pricing, turn-scoped Walter and persistent Mongol condition captured for Marco. `CardEffectData.kt`, `LeaderRules.kt`, `CardGame.kt`. | `LeaderRulesTest`, `LaterCardRulesTest`, catalog completeness tests. All 19 printed discount schedules match the Liumas reference, PDF page 3. |
| §11.4 Events | All 18 cards, later epoch choices, permanent voids versus same-turn protection/cancellation. `CardEffects.kt`, `CardGame.kt`, `WarRules.kt`. | `CardRulesTest`, `CardGameTest`, `LaterCardRulesTest`, `WarRulesTest`; event-specific inventory below. |
| §12 Information/randomness | Recorded shuffle permutations, draws, dice and random transfers; seat-specific views/history; private bids/returns. `GameOutcome.kt`, `Entry.kt`, `GameView.kt`; server `GameDriver.kt`, `GameStore.kt`, `GameService.kt`, `HistoryProjection.kt`. | `EntryTest`, `TextCodecTest`, `HistoryProjectionTest`, `PublicGameViewTest`, `GameServiceTest`, `TurnOrderServiceTest`, `FullGameHttpTest`. `RuleVariantServiceTest` covers private return restart/replacement, failed writes and atomic reveal; `SuspendedDeckReplayTest` covers both transition boundaries. |
| §13 Variants | Immutable explicit functional options, frozen at start; presets expand into resolved commands. `RuleOptions.kt`, `RuleOptionCommand.kt` and logical rule owners. | `RuleOptionsTest`, `AlternateEconomyTest`, `SetupDealTest`, `LaterCardRulesTest`; source and interaction limits recorded below. |
| §14 Open questions/rulings | Previously accepted exact attack, map, marker, rebuilt bonus and overflow choices retained. Q7 recycling verified from the supplied reference; Q14 timing approved by the user on 2026-10-24. | Existing regression classes above; the source verification and project rulings are recorded separately below. |

## Advance-effect cross-check

| Stable advances | Rule owners | Representative tests |
| --- | --- | --- |
| Heavens; Caravan; Overland East; Seaworthy Vessels; Ocean Navigation; New World access | `ExpansionReach.kt`, `ExpansionRules.kt`, `ShippingCapability.kt`, `AdvancePurchases.kt` | `ExpansionReachTest`, `ExpansionRulesTest`, `AdvancePurchasesTest` |
| Human Body; Improved Agriculture; Enlightenment; New World income relief; Interest & Profit; Middle Class | `AdvancePurchases.kt`, `PlayerEconomy.kt`, `CardEffects.kt` | `AdvancePurchasesTest`, `PlayerEconomyTest`, `CardRulesTest`, `AlternateEconomyTest` |
| Laws of Matter; Religion/science/commercial event counts | `CardEffects.kt` | `CardRulesTest`, `LaterCardRulesTest` |
| Patronage; Written Record; Printed Word | `LeaderRules.kt`, `AdvancePurchases.kt`, `VariantInteractions.kt`, `BoardGame.kt` | `LeaderRulesTest`, `AlternateEconomyTest`, `LaterCardRulesTest`, `ExpansionReplayTest` |
| Holy Indulgence; Renaissance | `ExpansionOpening.kt`, `TurnEngine.kt`, `TurnCommand.kt` | `TurnBoundaryTest`, `PhaseOpeningOrderTest` |
| Proselytism; Cathedral; Nationalism; Cosmopolitan | `CompetitionRules.kt`, `WarRules.kt`, `BoardGame.kt` | `CompetitionRulesTest`, `WarRulesTest`, `BoardGameTest` |
| Wind/Watermill | `BoardGame.kt`, `VariantInteractions.kt` | `ExpansionReplayTest`, optional-flow tests; source distinction below |
| Industry | `CardEffects.kt`, `Commodities.kt` | `CardRulesTest`, `CommodityPayoutOverflowTest` |
| Master Art; Urban Ascendancy | `TurnCommand.kt`, `TurnEngine.kt`, `DeckHistory.kt` | `TurnFlowTest`, immediate-discard optional-flow tests |
| Institutional Research | `AdvancePurchases.kt` | `AdvancePurchasesTest`, `AlternateEconomyTest` |

## Event-effect cross-check

| Cards | Implemented behavior and evidence |
| --- | --- |
| Alchemist's Gold | Written cash, current-cash cap, Laws of Matter and Ruler immunity; `CardRulesTest`, `CardGameTest`. |
| Armor, Stirrups | Baseline precedence/combination and War; `CompetitionRulesTest`, `WarRulesTest`, `CardRulesTest`. |
| Civil War | Victim choice, capital reduction, misery, delayed expansion order; `CardRulesTest`, `CardGameTest`, `CardMarkerPlacementTest`. |
| Enlightened Ruler | Same-turn immunity without retroactivity or invented permanent burdens; `CardRulesTest`, `LaterCardRulesTest`. |
| Famine, Mysticism Abounds, Revolutionary Uprisings | Commodity/advance-count misery calculations and permanent Mysticism burden condition; `CardRulesTest`. |
| Papal Decree, Religious Strife | Category ban, same-turn cancellation regardless of order, religion misery, epoch-three permanent burden; `CardRulesTest`, `LaterCardRulesTest`. |
| Pirates/Vikings | Current-epoch 1/2/3 distinct coastal targets, overseas eligibility, atomic validation; `LaterCardRulesTest`, `CardGameTest`. The user approved using all eligible targets when fewer exist on 2026-10-24. |
| Rebellion | Legal marker target, foreign-capital/New World exclusions, ordinary replacement; `CardRulesTest`, `CardGameTest`. |
| Crusades | Area VI placement and misery, Walter condition, marker-stock exhaustion choices; `CardRulesTest`, `CardMarkerPlacementTest`, `LeaderRulesTest`. |
| War | Modified dice, tied rerolls, supported cessions, shortages of eligible markers, military tie priority, final play; `WarRulesTest`, `CardGameTest`, `CardMarkerPlacementTest`, `LaterCardRulesTest`. |
| Black Death | Available-area targeting, token stock returns, marker reduction and ordinary event replacement; `LaterCardRulesTest`, shared `CardGameTest` replacement coverage. `LaterCardFlowTest` verifies multiple owners, token returns before replacement and sequential pool choices. |
| Gunpowder, Long Bow | Permanent voiding of unplayed Armor/Stirrups, opponent-specific suppression of already-played effects, modified War ties; `LaterCardRulesTest`, `WarRulesTest`. |
| Mongol Armies | $10 receipt, permanent Crusades burden and play-time Marco Polo condition; `LaterCardRulesTest`, `LeaderRules.kt`/`CardEffects.kt`. |

## Optional rules and source confidence

| Choice | Implementation/evidence | Remaining qualification |
| --- | --- | --- |
| Event-rebuilt marker expansion bonus | `RebuiltMarkerBonus`, explicit configuration; `RebuiltMarkerOptionTest`, `ExpansionReplayTest`, service tests. | Both reviewed policies supported; voluntary rebuilds excluded in either mode. |
| Improved deal-three/keep-two | Additional `timber-2-1`, five reserves, private owned return, exact return shuffle and timed reserve shuffle in `SetupDeal.kt`; `SetupDealTest`. | **Q14 approved project rule (2026-10-24).** Reinsert after regular draws on turn one for 3–4 players or turn two for 5–6. Alternate timings are deferred separate options; source disagreement is detailed below. |
| Commodity-only initial card | First eligible commodity in a uniform permutation; nondealt order conserved. `SetupDealTest.commodityInitialCardsPreserveEveryNondealtCardAndItsOrder`. | Combined improved/commodity setup rejected under the user-approved reduced-deck incompatibility rule; `SetupDealTest.improvedSetupIsIncompatibleWithCommodityOnlyInitialDeck` verifies rejection at start. |
| Alternate Commerce letters/prices/prerequisites | Selected definitions with stable identities; score and quotations consume selected values. `RuleOptionsTest`, `AlternateEconomyTest`. | Baseline definitions coexist unchanged. |
| Alternate credits | Communication, Civics, Patronage and Proselytism changes in `RuleOptions.kt`; delayed-credit tests. | `RuleOptionsTest`, `AlternateEconomyTest`; no global catalog mutation. Patronage's 20 credit approved on 2026-10-24. |
| Alternate income-relief owner | Enlightenment replaces New World in `PlayerEconomy.kt`. | `AlternateEconomyTest` tests both positive owner and absence of duplicate New World benefit. |
| Category-completion relief | Immediate 1/2/3 spaces at the 2/3/4 thresholds across Science/Commerce/Communication; no repeated or deferred award and no cash relief. | `AlternateEconomyTest`; the user approved these thresholds and immediate one-time awards on 2026-10-24 despite the source disagreement. |
| Immediate Master Art discard | Ordinary optional interruption after acquisition, before later purchase/stabilization; same option used by presets. | Rule owner `TurnCommand.kt` and `ImmediateMasterArtDecision`; `VariantFlowTest` covers decline, empty hand, burdens and purchase resumption. |
| Alternate Wind/Watermill | Last active owner, after first-player buy-off; neutral→shortage/surplus or adjusted→neutral. `VariantInteractions.kt`. | Follows the explicit EuroGames-differences supplement page 4. A later rewrite differs; see source record below. Clearing both markers of a double adjustment approved on 2026-10-24. |
| Alternate Written Record | Optional free takeover exchange against a non-owner, once per turn; no leader bonus. `VariantInteractions.kt`, `BoardGame.kt`, `AdvancePurchases.kt`. | `LaterCardRulesTest`, `AlternateEconomyTest`; distinct triggers share transfer frames only where rules agree. |
| Three-box Patronage | First leader exclusive; next leaders allow 1/2 additional claims; later leaders unrestricted; no protection money. `LeaderRules.kt`. | `LaterCardRulesTest.secondDisplayBoxAllowsOnlyOneOtherPatronageClaim`; `VariantFlowTest` verifies ordinary leader plays and claim limits; `LaterCardFlowTest` verifies final-turn retention. |
| St. Gall support | Selected support graph in `GameCatalog.kt`. | `RuleOptionsTest.stGallSupportIsSelectedWithoutMutatingPrintedArrows`. |
| Presets and service/browser controls | `RuleOptionCommand` expands to explicit functional entries; adapters owned by integrator. | `VariantGameCompletionTest` covers both presets for all four player counts; live setup and controlled card-control browser checks cover guided controls and mobile setup. |

### Source/ruling record

- **Q7 recycling verified:** the user supplied Chris Conboy/Liumas's complete card reference.
  Visual inspection of PDF page 2's event recycling arrows and page 3's commodity/leader recycling
  statements confirms all 64 implemented statuses: 40 recycle, 24 are removed. The 29/22/13 epoch
  counts and all 19 leader discount schedules also match. The aid is a reference catalog rather
  than physical-card photographs; [card inventory](card-inventory.md) records exact artifact
  metadata, checksum and page locators. No catalog data change was required.
- **Q14 reinsertion:** [Hexagonia's rewrite, §4.2, PDF page 7](https://board-game-rules.com/wp-content/uploads/2025/01/age-of-renaissance_Official-Rules.pdf)
  describes beginnings of turns one/two; the
  [2014 Euro-with-notes rewrite, §8.1, printed page 4](https://www.scribd.com/document/628528619/Age-of-Renaissance-Euro-Rules-with-Notes)
  instead describes 3–4-player reinsertion immediately after returns and 5–6-player reinsertion
  after turn two. The EuroGames-differences supplement in the
  [reference bundle, PDF page 4](https://www.goblins.net/files/downloads/Age%20of%20Renaissance.pdf)
  describes reinserting after the initial deal without a player-count split. These are different
  timing policies. The user approved reinsertion after regular draws on turn one for 3–4 players
  or turn two for 5–6 on 2026-10-24. This project rule is implemented in
  `SetupDeal.afterRegularDraw` / `reinsertionTurn`; it does not claim these sources agree.
  Alternate timings may be added later as separate options.
- **Combined improved/commodity setup:** neither the cited sources nor the reviewed reference
  specifies whether every player needs a commodity among their first three, exactly which draw
  is restricted, or whether the pair should be unsupported. The user resolved compatibility on
  2026-10-24: "get three, choose two" is incompatible with reduced initial-deal decks for now.
  The current rejection is an approved project rule, not an unresolved interpretation.
- **Alternate Wind/Watermill source conflict:** the EuroGames-differences supplement page 4
  specifies the last-moving owner and transitions through neutral. The 2014 Euro-with-notes
  rewrite's Wind/Watermill entry immediately before §10 Conduct of Diplomacy
  (printed page 12)
  describes an owner moving last and permits changing among all three statuses. Implementation
  follows the more explicit edition-differences supplement; its policy should be reflected in
  the summary/reference, rather than implying all rewrites agree. Choosing the last active owner
  when other owners are in Chaos follows the ordinary Chaos rule. The user approved clearing all
  markers on an adjusted commodity, including double adjustments, on 2026-10-24.
- **Other later-card readings:** no-target non-burden plays, reduced Pirates target counts,
  Black Death's stock/pool replacements and play-time Marco capture are documented in
  [rules](rules.md) and [card inventory](card-inventory.md). Tests verify these selected behaviors;
  they should not be presented as independent user rulings unless approved.

## Persistence/privacy review and final acceptance

Read-only review of the integrated server's setup-return path found the intended structure:
`GameDriver.submitSetupReturn` validates a seat's held reference but stores incomplete selections
only in `StoredGame.pendingReturns`. The final submission commits every return in registration
order followed by recorded shuffle outcomes in one revision. `GameStore` validates ownership and
phase when reloading pending selections, rejects partial committed batches, and atomically writes
the replacement file. `GameService` enforces optimistic revision checks and exposes only the
viewer's selected reference. `HistoryProjection` hides other seats' returned references and all
shuffle identities. No concrete privacy or atomicity defect was found in this review.

Final acceptance evidence:

| Interaction | Evidence |
| --- | --- |
| Private setup-return replacement, restart, atomic reveal, stale rejection and failed final write | `RuleVariantServiceTest` verifies saved bytes, private audiences, unchanged committed hands until reveal and one-revision publication. |
| Both epoch-boundary suspensions and all draw origins | `SuspendedDeckReplayTest`, `DeckDrawOriginTest`, `DeckLifecycleTest` and complete HTTP recovery with a randomness provider that fails if called. |
| Standard, improved/second-edition and independent tournament presets for all player counts | `SupportedGameCompletionTest`, `GameCompletionTest`, `VariantGameCompletionTest`; replay equality, physical-card conservation and selected scores. Existing all-player Chaos termination remains possible. |
| Later events, military, Marco timing, final-turn retention and victim interruptions | `LaterCardRulesTest`, `LaterCardFlowTest` and retained baseline regressions. |
| Alternate purchases, immediate discards, three-box claims and competing takeover triggers | `VariantFlowTest` exercises ordinary commands, stacked interruptions, once-per-turn limits and hand-reference conservation. |
| Removed epoch API and controls | `ApplicationTest` rejects an integer `epochs` creation field; live browser creation/configuration has no length selector. Current epoch is public progress. |
| Guided setup, presets, private replacement/reload and narrow screens | A live three-seat Chrome check passed on 2026-10-24, including 390px configuration and return layouts. |
| Guided area/multiple targets, Patronage, Written Record target selection, immediate discard and J/K mapping | Nine controlled Chrome scenarios verify rendered choices and canonical submitted commands; engine command flows are independently covered above. |

The improved/commodity-only incompatibility and Q14 reinsertion timing are both approved project
rules described above. Source confidence limits
remain recorded even though the selected behavior passes executable checks.

General undo, bots, accounts, private plans and automatic plan execution remain application work
outside this rules delivery. Showing cards and bribery enforcement are explicitly excluded by the
user. Neither group should be reported as an unresolved game-rule implementation defect.
