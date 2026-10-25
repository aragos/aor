# User interface guiding rules

The UI presents Age of Renaissance as a visual game table. Players should be able to understand the
current position and complete every supported decision through visual controls. The command language
remains an equally capable interface and the only primarily text-based part of ordinary play.

These rules guide the UI revision. [Application design](design.md) defines the architecture;
[map design](map-design.md) defines the board's visual language; [rules](rules.md) owns game behavior.
The [interactive proposals](ui-proposals/index.html) establish the accepted design direction using
illustrative positions. These principles govern implementation; the mockups provide layout and
interaction references rather than game calculations. The [implementation plan](ui-implementation-plan.md)
defines delivery slices and acceptance criteria.

## Visual information

- Make provinces, pieces, cards, commodities, and advances recognizable objects that players can
  select directly. Use text for names, quantities, short labels, game effects, and blocking conditions.
- Reuse the map's capital colors and silhouettes everywhere. Commodity symbols and colors have the
  same meaning in the map, cards, summaries, and action previews. Color always has another identifying
  cue. Piece faces retain the established distinction between new and established pieces.
- Give quantities readable type and clear units. Distinguish available tokens, stock tokens, markers,
  cash, market ownership, and payout values even when their displays are compact.
- Keep command syntax, hand references, internal identifiers, and revision details in the command
  interface or technical details. Ordinary play uses recognizable entities and localized labels.
- Use visible text for game information and useful labels. Omit commentary explaining the purpose
  of a control, instructions such as when cards can be played, and repeated interaction hints. Keep
  specific blocking conditions and consequential rules available where they affect a decision.

## Compact summaries and contextual detail

Show current state at rest. Reveal the information needed for a decision when that decision is
active. Selecting a summary opens its complete explanation. Full tracks and reference tables belong
in detail views unless the current decision benefits from them.

| Subject | Default summary | Detail when relevant |
| --- | --- | --- |
| Players | Capital identity, turn position, cash, current income, misery, fleet, hand size, and decision status | Complete public player position and owned advances |
| Misery | Current box value or Chaos | Movement in spaces and resulting box value; track on request |
| Shipping | Fleet type and level | Reach, access, and capacity for the selected destination; upgrade consequences |
| Commodities | Symbol, current payout for each owning power, and shortage/surplus markers | Market counts, contributing provinces, effective payout boxes, and modifiers |
| Advances | Ownership counts by category | Individual tiles, benefits, prerequisites, ownership, and current offers |
| Resources | A prominent pool-token total during expansion; other resources needed for the current decision | Stock and board breakdowns, pending commitments, and resulting balances |
| Turn order | Ordered player emblems with the active decision clearly marked | Order boxes and tie choices |
| Active effects | Recognizable badges with relevant quantities or duration | Full effects and affected objects |
| Hand | Readable names and decision values: commodity payouts, leader discounts by advance, and relevant event information | Complete effects, discount breakdowns, targets, and costs |
| History | Recent changes and unread activity | Visual event history and the separate command log |
| Configuration | Compact summary of selected rules | Complete settings; applicable exceptions beside affected actions |

Shipping capacity is per destination, not a single remaining allowance for the turn. Misery movement
counts spaces, while costs and scoring use box values. Commodity ownership counts and effective
payout boxes are distinct. Compact displays must preserve these distinctions.
Multiple shortage or surplus markers affect successive commodity payouts; their count must not
suggest that one payout receives the adjustment multiple times.

Commodity summaries prioritize payouts. Omit powers that own no qualifying markets by default;
retain an owner with a zero payout when an adjustment makes its holdings unprofitable. Ownership
counts remain available in detail. Commodity cards show the same current payouts, including other
owners' payments and separate values for each commodity on a choice card. Leader cards identify
the discounted advances and the viewer's effective discount, with the printed amount and applicable
bonuses available in detail. A discount is distinct from an advance's final price or a cash rebate.
Use the engine's qualifying holdings for current payouts. When an action adds new white pieces,
distinguish the current payout from a projection after pieces flip; label that future timing and any
assumed adjustments explicitly.

Each player summary includes current income alongside cash. Base it on the current position and
applicable bonuses, and identify conditional gains separately in detail. Refresh income after board
changes and show the expected change in an action preview when relevant. In the overview, use an icon
and amount without an income label or leading plus sign, matching the other entries. Keep the label
available to assistive technology and on hover; reserve signs for changes in value.
The overview amount is ordinary income, including applicable income bonuses. Show conditional
Interest & Profit separately in detail. Setup and Chaos use an explicit unavailable state where an
income quote does not apply.

During expansion, give the available pool-token total stronger emphasis than secondary resources.
Keep it visible beside the board and preview both the commitment and remaining pool. Keep markers in
stock in collapsed details during expansion; emphasize stock when purchasing tokens or when a stock
limit affects the selected action. Use the main commodity view and card values for economic
comparisons; omit a separate nearby-commodities panel.

Use one predictable home for each summary. Repeat a value in an action preview only when the local
comparison helps the player understand the commitment or result. Keep consequential changes,
mandatory decisions, resource limits, and relevant threats visible when detailed panels are closed.

## Typical actions and useful defaults

Select the usual action for the context and make it the primary control. A playable card opens play;
an available advance opens purchase. Less common actions remain accessible as secondary choices.
When the typical action is unclear, settle the intended player behavior before choosing a default.

**Selecting a market that the player does not already control defaults to establishing their marker.**
Prefill the required legal commitment, accounting for pieces already present, available markers,
access, shipping, and competition. An empty or partially occupied market may use placement; a
contested market may require attack. Show the appropriate action and its consequences. A competition
preview must distinguish the commitment from its uncertain outcome.

Partial placement is a secondary choice. If establishing a marker is unavailable, explain the reason
and expose any legal alternatives without silently changing the player's intent. A market already
controlled by the player opens inspection and any relevant secondary actions. Satellites use their
own token-placement rules.

Defaults prepare a draft. Selecting an object, changing a quantity, or navigating never submits an
order. A clearly labeled action button commits it. Preview the cost, resources remaining, targets,
and deterministic changes; label conditional or uncertain outcomes.

## Action availability

- Hide actions unrelated to the current phase, applicable rules, or selected object.
- Show a blocked action when its existence or unlock condition helps the player plan. Make its
  unavailable state clear and explain the actual reason, such as missing cash, access, or capacity.
- Keep blocked destinations and objects inspectable when they provide useful board context. An
  inspectable object is not necessarily an eligible order target.
- Make the usual legal action visually prominent. Unavailable controls must never look ready to
  submit, and changing an option must not bypass authoritative validation.
- Keep submitted sealed decisions distinguishable from unsubmitted drafts and revealed outcomes.
  Readiness indicators reveal no opponent bid, purchase amount, or private card identity.

## Decisions and continuity

Keep the active decision beside its relevant objects and resource summary. A map-based action should
not require scrolling between the board and a separate form. Give the current actor, requested
decision, and waiting state clear visual emphasis. Nested interruptions identify the player who
must answer and the activity that will resume.

Preserve valid drafts, selections, and map position through polling and view changes. Revalidate a
draft when the authoritative prompt or position changes. Explain invalidated choices and prevent
stale submission. Retain a rejected draft where possible so the player can correct it.

The command dock is easy to open and may stay pinned. Visual controls and commands use the same
canonical actions, authorization, validation, revision checks, and private-information boundaries.

## Legibility and access

Use a consistent visual hierarchy, sufficient contrast, readable numbers, and generous interactive
targets. Compression should reduce repeated structure and irrelevant detail before reducing type
size. Avoid dense icon strings whose meaning requires memorizing a legend.

Every interactive visual supports keyboard and touch use, with a meaningful accessible name and
visible focus. Hover can supplement selection but must not be the only way to obtain information.
Keep accessible structured descriptions available. Announce important changes without stealing
focus. Respect reduced-motion preferences and retain readable labels under browser zoom.

Adapt the layout to available space. On small screens, show the current workspace and a compact
decision sheet, with a reachable submit control. Give large hands and six-player summaries deliberate
overflow behavior. Switching views preserves the player's work.

## Authoritative information

Derive availability, required commitments, prices, and effect previews from domain-owned calculations.
The browser presents these results; it must not reconstruct movement, competition, or economic rules
from geometry, English messages, or command history. Extend typed projections where a visual needs
information the current snapshot does not provide.

Visual history needs structured, viewer-safe events. Preserve redaction for private cards and sealed
decisions. Illustrative prototype data must not become a second implementation of game rules.

## Review criteria

Review complete decisions, including blocked actions, errors, waiting, and interruptions. The first
implementation slice should let a player select a market, understand the default marker commitment,
submit, and see the result while the map and action remain visible together.

Check crowded six-player positions, large hands, multiple commodity adjustments, destination-specific
shipping limits, missing markers, partial occupation, and contested markets. Verify keyboard and
touch operation, narrow screens, polling, stale submissions, and draft preservation. Confirm that
all supported decisions remain available through both visual controls and commands.

The proposal viewer contains three compatible studies: the compact game table, commodity summaries,
and contextual market actions. Open `docs/ui-proposals/index.html` in a browser. It runs locally with
illustrative state and never submits a game command.

| Study | Desktop preview | Narrow preview |
| --- | --- | --- |
| Compact game table | [Game table](ui-proposals/01-game-table.png) | Included in the responsive viewer |
| Commodity summaries | [Commodities](ui-proposals/02-commodities.png) | [Commodities on a narrow screen](ui-proposals/06-narrow-commodities.png) |
| Establish a marker | [Market action](ui-proposals/03-market-action.png) | [Decision sheet](ui-proposals/05-narrow-market-action.png) |
| Blocked destination | [Unavailable market](ui-proposals/04-unavailable-market.png) | Included in the responsive viewer |

The map reuses the approved Iberia study with an empty Valencia for the interaction example. The
viewer demonstrates selection, marker establishment, partial placement, access rejection, commodity
inspection, player/card details, and the command dock. Other provinces support inspection. Full
advance purchasing and card play are later design slices.
