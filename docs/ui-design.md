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

## Common interaction principles

- **Make meaningful choices explicit.** Prepare sole legal choices and sensible numeric defaults;
  reserve extra selection steps for decisions that can change the outcome. Advance automatically
  past bookkeeping that has no effect or legal action, using the engine's normal progression.
- **Put eligibility on the object.** Show legal targets, available purchases, and relevant discounts
  where the player already reads the position. Distinguish inspection, eligibility, and selection.
- **Let context organize attention.** Open the relevant workspace at a decision boundary and keep
  its resources and modifiers nearby. Polling must preserve subsequent navigation and valid drafts.
- **Offer a useful legal next action.** Resource limits should expose appropriate alternatives,
  with an accurate preview and explicit commitment. The engine owns legality and calculations.
- **Close the action loop.** Clear completed action contexts, retain rejected drafts, and make
  consequential outcomes visible without requiring players to search a log.
- **Keep identity and navigation stable.** Use the same capital identity across surfaces, retain
  human seat names in the overview, and constrain map gestures to meaningful board space.
- **Make visual boundaries match the objects.** Emphasize a province with one outline around its
  land and coastal water. Derive area borders from member province footprints and assigned seas,
  enclose neutral spaces where needed for one continuous perimeter, and simplify shared water
  edges together so visual gaps, overlaps, or stray fragments cannot imply extra connections.
- **Use stable highlight layers.** Connections and ordinary borders sit below neighbor, eligible,
  hover/focus, and selected highlights, in that order. Apply that order across provinces and areas;
  the inspected object sits above other selected targets. Labels and pieces stay readable above
  it. Catalog insertion order must never determine emphasis.

## Visual information

Event cards show their effect text in the hand and in their selected detail.

- Make provinces, pieces, cards, commodities, and advances recognizable objects that players can
  select directly. Use text for names, quantities, short labels, game effects, and blocking conditions.
- Reuse the map's capital colors and silhouettes everywhere. Commodity symbols and colors have the
  same meaning in the map, cards, summaries, and action previews. Color always has another identifying
  cue. Piece faces retain the established distinction between new and established pieces.
- Use capital names as the public identity of a power in waiting states, targets, ownership, and
  activity. Before capital selection, use the player's name. Capital overview boxes also show the
  human player's name so seat identity stays clear.
- Give quantities readable type and clear units. Distinguish available tokens, stock tokens, markers,
  cash, market ownership, and payout values even when their displays are compact.
- Keep province names and market badges on land where space permits, then within the same
  province's coastal space. Reserve unavoidable border overlap for the most constrained shapes.
  Show capitals with a dark market badge covering both number and commodity.
- Show shared access locks on area or overseas headings and update them from authoritative viewer
  access. Province details retain specific access requirements.
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
| Players | Capital and player identity, turn position, cash, current income, misery, fleet, hand size, pool tokens from purchasing through expansion, and decision status | Complete public player position and owned advances |
| Misery | Current box value or Chaos | Movement in spaces and resulting box value; track on request |
| Shipping | Fleet type and level | Reach, access, and capacity for the selected destination; upgrade consequences |
| Commodities | Symbol, current payout for each owning power, and shortage/surplus markers | Market counts, contributing provinces, effective payout boxes, and modifiers |
| Advances | Ownership counts by category; played leaders in the advances workspace | Individual tiles, benefits, prerequisites, ownership, current offers, and applied leader credits |
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

During expansion, prominently show the active power's capital and remaining pool-token total to
every viewer beside the board. Keep it visible when the mobile decision sheet is collapsed, refresh
it with the current position, and switch to the next power when the actor passes. Preview both the
commitment and remaining pool for the viewer's draft. Keep marker stock in collapsed details during
expansion; emphasize stock when purchasing tokens or when a stock limit affects the selected action.
Use the main commodity view and card values for economic comparisons; omit a separate
nearby-commodities panel.

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

Partial placement is normally a secondary choice. When the pool cannot supply the marker commitment
and placing the entire remaining pool is legal, make that placement the primary action. Label the
remaining-token action and preview tokens rather than a marker. In other blocked cases, explain the
reason and expose legal alternatives. A market already
controlled by the player opens inspection and any relevant secondary actions. Satellites use their
own token-placement rules.

Do not ask players to choose the only possible commodity on a card. Prepare its sole legal choice
and retain the explicit Play button. Use zero as the initial protection-money draft. Defaults must
be legal, visible, and editable where alternatives exist; invalid edits must disable submission.
Label an action by what it does, such as **Buy tokens**. Submitted readiness belongs in its status.

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
- Keep submitted private decisions distinguishable from unsubmitted drafts and revealed outcomes.
  Readiness indicators reveal no opponent bid, purchase amount, or private card identity.
- Highlight legal targets on the objects used to inspect the position: provinces on the map and
  powers in capital overview boxes. Card target highlighting starts when the card is selected;
  target clicks prepare the same draft as the selection buttons. Keep selected targets distinct
  from eligible targets and revalidate them against current authoritative offers.
- During expansion, distinguish reachable provinces from unreachable ones. Reachability describes
  access from the current position, independently of token commitments and marker stock. Keep local
  blockers in the selected province's action preview. Use light fills for reachable provinces and
  dark fills for other destinations, preserving commodity colors and readable piece labels. A
  province's land and coastal water share reach shading, target and selection highlights, and focus
  or hover emphasis; selecting its coastal water edits the same province draft.
- During advance purchasing, distinguish currently purchasable advances and show applied leader
  discounts beside their final prices. Discounts are already included in the price. Misery relief
  requires an authoritative relief offer. Skip the relief step when no relief action is legal,
  including when the player has no tier. Skip stabilization when its cost is zero.

## Decisions and continuity

Keep the active decision beside its relevant objects and resource summary. A map-based action should
not require scrolling between the board and a separate form. Give the current actor, requested
decision, and waiting state clear visual emphasis. Nested interruptions identify the player who
must answer and the activity that will resume.

Open the advances workspace when the viewer enters the advances-purchase decision. Open the map
when Expansion begins for the table and when the viewer's expansion activity begins. Do this on
the decision transition so subsequent navigation and polling do not repeatedly pull the player back.
Bring decision-relevant information to its workspace: played leaders belong above the advances,
with a prominent box or exposed status and named advance discounts. Distinguish the viewer's usable
credits from the printed/conditional credits of other powers' leaders; include applicable bonuses
only in the viewer's authorized discounts. Show expansion pools only after simultaneous purchases reveal and until each power ends Expansion.
The same availability applies to capital summaries and details; zero is still a valid pool. Keep the
active power's remaining pool prominent beside the board.

Preserve valid drafts, selections, and map position through polling and view changes. Clear object
inspection, card and advance selection, target pickers, and focus at phase or purchase-step boundaries. Revalidate a
draft when the authoritative prompt or position changes. Explain invalidated choices and prevent
stale submission. Retain a rejected draft where possible so the player can correct it.

A successful pass or card play ends the selected object's action context and clears its detail and
target highlights. Rejected commands preserve the draft. Polling and ordinary workspace navigation
preserve selections; a card removed from the viewer's hand cannot leave a stale detail open.
Clicking non-inspectable map space clears area or province inspection and focus emphasis, while
dragging and active order drafts retain their state.

Show competition results beside the active decision as well as in activity history. Identify the
province, participants, win or loss, and recorded dice or Cathedral resolution. Keep the latest
relevant result visible until dismissed or replaced, even if another event follows immediately. War
rolls similarly show participants, raw dice, modified totals, and the winner or continuing tie.
Automatically highlight provinces eligible for War cession, respecting selection and overseas limits.

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

Map navigation supports buttons, modifier-scroll zoom, trackpad pinch, and two-finger touch pinch.
Area labels sit in clear interior space, favor seas, and support pointer and keyboard selection.
Their selection highlights the full area with strong contrast against the rest of the map; legal
area targets such as Black Death use those same labels to prepare their order drafts.
Zoom around the gesture location, constrain both panning and zooming to the map's bounds, and fit
the viewport to the provinces used by the table. Trim unused eastern space without clipping active
provinces. Ordinary scrolling keeps its page-navigation behavior. Keep zoom in, zoom out, and Fit
floating over the board. Omit map search and drop-down navigation menus; provinces are selected
directly on the map.

## Authoritative information

Derive availability, required commitments, prices, and effect previews from domain-owned calculations.
The browser presents these results; it must not reconstruct movement, competition, or economic rules
from geometry, English messages, or command history. Extend typed projections where a visual needs
information the current snapshot does not provide.

Visual history needs structured, viewer-safe events. Preserve redaction for private cards and unrevealed
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
