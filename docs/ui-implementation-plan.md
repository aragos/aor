# UI revision implementation plan

Implement the accepted [UI design principles](ui-design.md) as a compact visual game table, using
the [interactive mockups](ui-proposals/index.html) as the design reference. Deliver a complete
market-selection and marker-establishment flow first, then extend the same interaction model to
the rest of the game. Every supported decision must remain available through visual controls and
the command console.

Status: the accepted principles and mockups have been implemented in the game table. The
[decision coverage inventory](ui-decision-coverage.md) records live checks, deterministic fixtures,
and remaining verification gaps. This plan retains the delivery rationale and acceptance criteria.
This revision covers presentation, interaction state, and the domain projections
needed to display reliable values. Preserve the reviewed game rules, catalogs, canonical commands,
privacy boundaries, and revision protocol. Accounts, bots, undo, private plans, and map geometry
redesign remain separate work.

The delivered revision includes all seven slices. Integrated checks passed on 2026-10-25, including
a live three-player game through 473 canonical visual actions to final scores. The coverage inventory
distinguishes that completed game from focused interruption/variant fixtures and records supplementary
manual review gaps; it does not claim that one playthrough exercised every rule combination.

## Delivery order

Deliver each slice with its focused checks. Keep existing controls available until their visual
replacement handles the same decisions; then remove the duplicate controls. Use focused feature
branches and cohesive commits. The dependency column identifies what each slice needs.

| Slice | Deliverable | Depends on |
| --- | --- | --- |
| 1 | Authoritative income and expansion previews | Existing engine and snapshot |
| 2 | Game-table shell and complete contextual expansion flow | 1 |
| 3 | Compact player overview and resources by phase | 2 |
| 4 | Commodity payout summaries and useful values on hand cards | 3 |
| 5 | Direct card play and advance purchasing | 4 |
| 6 | Remaining decisions, interruptions, and visual activity | 5 |
| 7 | Full-game verification and removal of superseded UI | 6 |

Slices 1 and 2 form the first playable milestone. Accessibility, localization, narrow-screen use,
and draft continuity are acceptance criteria for every slice, not a final styling pass.

## Implementation ownership

| Area | Code | Responsibility |
| --- | --- | --- |
| Page and connection state | [App.tsx](../web/src/App.tsx), [GameTable.tsx](../web/src/GameTable.tsx) | Polling, busy guards, stale-revision handling, workspace layout, and the phone decision sheet. |
| Map and selections | [BoardMap.tsx](../web/src/map/BoardMap.tsx), [ProvinceInspector.tsx](../web/src/map/ProvinceInspector.tsx), [MapSelection.tsx](../web/src/map/MapSelection.tsx) | Shared selected objects and target pickers, preserved viewport, authoritative marker/attack previews, and secondary partial placement. |
| Action controls | [Controls.tsx](../web/src/Controls.tsx), [ObjectDecisions.tsx](../web/src/ObjectDecisions.tsx), [GameActions.tsx](../web/src/GameActions.tsx) | Setup, revision-bound token quotes, card and advance drafts, and finite phase/interrupt choices. |
| Summaries and detail | [TablePanels.tsx](../web/src/TablePanels.tsx), [FullGamePanels.tsx](../web/src/FullGamePanels.tsx) | Compact players, payouts, hand values and advances; public references, board detail, effects and scores. |
| Visual language | [MapSymbols.tsx](../web/src/map/MapSymbols.tsx), [TableIcon.tsx](../web/src/TableIcon.tsx), [app.css](../web/src/app.css), [map.css](../web/src/map/map.css) | Shared capital silhouettes, commodity symbols, resource icons, typography, focus and availability states. |
| Domain and transport | [GameView.kt](../core/src/commonMain/kotlin/dev/aragos/aor/GameView.kt), [EconomyProjection.kt](../core/src/commonMain/kotlin/dev/aragos/aor/EconomyProjection.kt), [ExpansionProjection.kt](../core/src/commonMain/kotlin/dev/aragos/aor/ExpansionProjection.kt), [CardProjection.kt](../core/src/commonMain/kotlin/dev/aragos/aor/CardProjection.kt) | Typed domain-owned income, payout, discount, purchase and expansion projections, including reference tracks. |
| Activity and commands | [HistoryEvents.kt](../server/src/main/kotlin/dev/aragos/aor/HistoryEvents.kt), [Activity.tsx](../web/src/Activity.tsx), [CommandDock.tsx](../web/src/CommandDock.tsx) | Stable viewer-safe visual events and the redacted command console, using the same canonical submissions. |

The snapshot now includes current income, commodity payouts, hand-card discounts and choices,
complete expansion offers, and token/shipping quotes beside the existing advance and relief offers.
The server also accepts read-only, viewer- and revision-bound token quote requests for edited amounts.
The client never treats a legal `place 1` command as evidence that a larger marker commitment is legal.

## 1 Authoritative income and expansion previews

- Project current ordinary income through `PlayerEconomy.incomeQuote`, using the initial player
  count and current marker holdings. Include Middle Class in the overview amount; keep Interest &
  Profit and relief effects in the breakdown. Represent inapplicable setup or Chaos values explicitly.
- Add a typed expansion offer for each relevant destination. Include the primary action, required
  additional pool tokens, available pool, resulting pool, marker outcome, destination-specific
  shipping usage, and typed blockers. Provide legal partial-placement choices separately.
- Reuse `BoardPlacementRules`, `ExpansionReach`, and `CompetitionRules` for both previews and
  execution. Account for existing friendly tokens, mixed occupation, exact attack commitments,
  marker stock, access, and shipping. Satellites retain their token action.
- Project deterministic income and board changes from the validated plan. Represent competition
  as a commitment with conditional outcomes; a preview must never roll dice or mutate live state.
- Extend the core view, service serialization checks, and TypeScript contract together. Prefer
  bounded snapshot offers; if an edited draft needs a separate quote request, bind it to the viewer
  and revision and discard responses for superseded drafts. Keep the existing canonical submission
  commands and `expectedRevision` check. Quotes remain derived data and do not add saved game state.

Acceptance: previews agree with actual accepted commands for empty and partially occupied markets,
contested markets, satellites, and exhausted resources. A blocked full-marker action can coexist
with a legal partial placement. Wrong-player views offer no actionable commands. Quote evaluation
has no state or randomness effects, and normal polling does not enumerate every possible command.

## 2 Game table and contextual expansion

- Build the header, workspace tabs, adjacent decision panel, hand region, and collapsible command
  dock using the existing React app. Reuse the full map and its approved geometry. Move technical
  revision information and command suggestions into the console or technical details.
- Introduce shared visual components for player identity, commodity symbols, numeric values,
  resource previews, action availability, and expandable detail. Keep ordinary text concise and
  remove instructions explaining the purpose of controls.
- Selecting an uncontrolled market prepares **Establish marker** with the required additional
  tokens. Use the engine's competition action where required and label the uncertain outcome.
  Selecting a controlled province opens inspection and relevant secondary actions.
- Show pool tokens prominently beside the board. Present commitment and remaining pool beside the
  explicit submit button. Keep partial placement secondary and stock counts in collapsed details.
  Hide irrelevant actions and show useful blocked actions with their actual reason.
- Give selections and drafts a stable owner above workspace views. Preserve valid drafts and map
  position through polling and tab changes. Revalidate against changed prompts, targets, and
  revisions; explain invalidated choices. Avoid remounting forms solely because an unrelated hand
  count or advance count changed.
- On narrow screens, place the decision in a compact sheet with an unobscured submit control and
  visible selected province. Support keyboard selection, touch, focus restoration, and announcements
  for accepted or rejected commands. Keep structured board inspection available.

Acceptance: on desktop and phone, select an empty market, inspect the marker commitment, submit once,
and see the updated board, pool, and income. Repeat with existing tokens, partial placement, blocked
access, and competition. Selection, tab changes, and polling never submit an order. Rejected and
stale submissions retain editable intent where possible; hidden or disabled actions cannot bypass
server validation. Refreshing a stale draft never resubmits it automatically. The command dock
completes the same flow.

## 3 Player overview and phase resources

- Replace the default player table with compact summaries in turn order: capital identity, cash,
  current income, misery position or Chaos, fleet, hand count, and active/waiting/submitted status.
  Handle unassigned capitals and provisional order during setup without inventing a turn order.
- Render income as an icon and amount, such as `$39`, with no plus sign or visible income label.
  Keep accessible names and touch-accessible detail. Expand a player for full public state,
  income breakdown, owned advances, stock, and track reference information.
- Give each summary one normal home. Pool tokens dominate expansion; stock and the cash/token
  purchase preview become prominent during token purchasing. Marker stock stays collapsed during
  expansion unless it limits the selected action. Keep stock tokens and domination markers distinct.
- Present misery as its current box value and shipping as fleet type and level. Show movement,
  cost, reach, and capacity only where the current decision needs them. Retain full tracks in detail.
- Preserve secret submissions: show submitted status without revealing another player's pending
  amount or card. A player can review and replace their own permitted private submission.

Acceptance: three- through six-player layouts remain legible with long player names and browser zoom.
Income refreshes after board changes; conditional profit is separate. Setup, Chaos, waiting players,
and private submissions have clear states. No repeated full misery or shipping tracks, and no
prominent idle marker-stock count during expansion.

## 4 Commodity summaries and informative cards

- Project commodity payouts through `CardRules.commodityPayment`, sharing qualifying ownership and
  adjustment calculations with execution. Include owning powers, amounts, contributing markets,
  and adjustment state. Preserve zero-paying owners; omit powers with no qualifying holdings.
- Replace commodity count summaries with symbol, owning-power emblems, payouts, and compact
  shortage/surplus indicators. Put counts, contributing provinces, and payout breakdowns in detail.
  Do not add a nearby-commodities section.
- Add commodity values and effective leader discounts to the viewer's hand-card projection.
  Dual commodity cards show both alternatives. Share commodity values between hand and market
  views; share conditional leader-credit calculations with `LeaderRules`, leader data, and
  `AdvancePurchases` rather than reproducing formulas in the browser.
- Leader cards list affected advances and effective discounts, including applicable Written Record
  and event conditions. Keep printed amounts and conditions in detail. An unplayed leader's potential
  credit must not be included in a currently purchasable advance quote until the rules authorize it.
- Resolve payout timing in action previews. The mockup's new Valencia marker illustrates a Silk
  increase, but the engine counts coloured markers for a current payout. Show any after-flip value
  with its timing and adjustment assumptions; do not present it as cash received on placement.
- Make hand cards readable selectable objects. Keep hand references in the console, and use badges
  for actual conditions such as a burden rather than prose about when a card can be played.

Acceptance: summaries and hand values match core quotes for shortages, surpluses, multiple queued
adjustments, Industry, dual cards, and conditional leader discounts. Owners with `$0` remain visible;
non-owners are absent. New white markers and established coloured markers produce correctly timed
previews. Large hands remain usable without shrinking text, and opponent private cards stay hidden.

## 5 Direct card play and advance purchasing

- Selecting a playable hand card opens its draft action and relevant target picker in the decision
  panel. Use player emblems, provinces, areas, commodities, or categories as appropriate. Inspection
  remains possible when play is unavailable, with actual blockers where useful.
- Extend typed card-choice projections where current UI code guesses targets from card IDs or
  province properties. Keep private hand references in the action binding; derive eligibility,
  required target counts, protection amounts, and no-target cases from the domain.
- Replace the advance purchase dropdown with selectable advance tiles grouped by category. Show
  ownership and effective price; open purchase for an available advance, and expose meaningful
  prerequisites or resource blockers for unavailable ones. Reuse existing advance offers.
- Add engine-backed quotes for any remaining purchase previews, including token and shipping
  choices. Use compact before/after values for cash, fleet, misery, and other consequences. Selection
  prepares a draft and a deliberate action button submits it.
- Remove the corresponding duplicate forms once all their choices and error states are covered.

Acceptance: complete single- and multi-target card plays, dual-commodity selection, leader protection,
burden handling, and affordable/blocked advance purchases through visual selection. A changed prompt
or lost target invalidates only the affected draft. Conditional or unavailable discounts never appear
as an already-applied purchase price. Command and visual flows produce equivalent results.

## 6 Remaining decisions and visual activity

- Apply the visual hierarchy to game creation and configuration. Extend the decision panel to
  capital bids and selection, private setup returns, token purchasing, turn-order ties, card
  buying/discarding, shipping, stabilization, misery relief, expansion card purchases, and
  end-of-game results. Keep supported variant choices and replacements of private submissions
  accessible.
- Cover nested decisions explicitly: Cathedral, event token replacement, marker relocation or
  forgoing, War cessions, market adjustments, takeover trades, Patronage, and final card play.
  Show the decision recipient and the enclosing activity that will resume. Respect target counts,
  overseas limits, and public/private card roles.
- Build a coverage checklist from every supported prompt and canonical action. Choose the typical
  action where established. Resolve any remaining ambiguous default when implementing that flow;
  do not silently select a strategic target or payment choice for the player.
- Add structured activity entries during the existing history replay, with stable identifiers and
  viewer-safe entity references. Preserve audience filtering for draws, trades, returns, and
  discards. Render a compact recent event and expandable visual activity list; retain raw command
  history in the console. Never derive visual events by parsing the redacted English log.
- Condense active effects, order information, and configuration into relevant badges or detail
  views. Keep mandatory decisions, ending conditions, and actionable threats visible. Give loading,
  reconnecting, submission failure, waiting, and finished states deliberate layouts.

Acceptance: each supported prompt has a complete visual path, including interruptions and variant
branches. Multi-player sessions preserve secrecy and decision ownership. Activity refreshes without
duplicate entries or focus theft. Full game history and reference data remain accessible in detail.

## 7 Integrated verification and completion

Use deterministic fixtures and command-driven games for behavior; use the mockups for visual review.
Extend [map browser checks](../web/tests/map.browser.mjs) and their development harness to cover
the shared decision panel, rather than relying on screenshots alone.

| Area | Required evidence |
| --- | --- |
| Projection accuracy | Focused core tests for income, qualifying ownership, payout/discount conditions, placement commitments, blockers, and preview/execution agreement. Include current variants and marker limits. |
| Service and privacy | Snapshot/serialization tests, wrong-player views, private hand and private-decision redaction, stale revisions, and equivalent visual/command submissions. Preview requests never mutate state. |
| Continuity | Polling during edits, map/workspace switching, successful submission, rejected submission, stale refresh, and prompt interruptions. Valid intent and focus survive; invalid actions cannot submit. |
| Legibility and access | Three to six players, large hands, crowded mixed pieces, long labels, keyboard-only operation, touch, browser zoom, reduced motion, and meaningful accessible names. Verify contrast and reading order. |
| Responsive layout | Desktop and narrow phone layouts, including 390 × 844 and 430 × 1100 references. No unintended horizontal page overflow; action buttons remain visible and unobstructed. |
| Game coverage | Start-to-finish visual play plus focused interruption and variant fixtures. Every supported prompt/action is accounted for, with explicit waiting and finished states. |

For Kotlin edits, run `./gradlew formatKotlin` to completion, then
`./gradlew checkKotlinFormat`. Run focused tests during each slice; use
`./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat` for integrated checks.
For browser changes, run `npm test` and `npm run build` from `web`, then verify affected interactions.
Run the browser suite with an isolated server, temporary `AOR_DATA_DIR`, and its configured Vite and
Chrome endpoints as described in [map verification](map-design.md#data-and-verification).
The browser runner must close every process it starts, including Chrome, in success and failure paths.

Completion requires the coverage checklist and integrated checks to pass, current desktop and phone
screenshots, and removal of superseded forms, duplicate summaries, unused styles, and purpose text.
Keep the accessible board detail and command console. Update the design references to the delivered
behavior; never copy illustrative mockup calculations into production. Add command-reference changes
only if an intentional canonical-command change is required.
