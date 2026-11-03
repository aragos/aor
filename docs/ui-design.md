# UX and UI principles

AoR is a visual game table. Players must understand the position, find the next decision, prepare a
choice and understand its result through visual controls and commands, on desktop and narrow screens.
[Map design](map-design.md) owns spatial contracts, [rules](rules.md) mechanics and
[design](design.md) architecture. [Coverage](ui-decision-coverage.md) records evidence;
[UX review](ux-next-steps.md) records remaining work.

## Interaction rules

1. **Render authoritative facts.** Use typed, viewer-safe engine projections for legality, prices,
   targets, commitments and effects. Visual controls submit canonical commands through the same
   authorization and revision checks as text input. Preserve private audiences in every preview.
2. **Keep choices meaningful.** Resolve setup during creation and skip empty bookkeeping. Prepare
   sole legal choices and useful numeric defaults without committing them. Retain blocked relief as
   an explicit decision for players with an advance tier, with its explanation and Pass.
3. **Organize attention around action.** Show actor, request and relevant resources together. Open
   the relevant workspace once per decision boundary; polling must not repeatedly redirect it.
   Waiting always has one prominent home in the action pane, naming all required responders; omit
   waiting labels from player boxes and the header. Interruptions name the required responder and
   the activity that resumes. Keep the private hand
   visible below the workspace column, leaving the sidebar's full height available for actions,
   without repeating held-card reminders in the workspace or action pane.
4. **Use the game objects as controls.** Click cards, advances and players for their relevant detail
   or choice; do not add a parallel Inspect button or map mode. Province connections are shown by
   map highlights, without a second province panel. Commodity cards show current payouts and a
   small question control for each full payout track, without a commodity inspector. Reference access
   must preserve the active form, prepared targets and picker. Event and leader inspections use a
   popup that returns focus when closed; advance inspection retains its workspace surface.
   Distinguish inspection, eligibility, draft selection and commitment; a component click never
   commits an order.
5. **Define draft lifetimes.** Polling and navigation preserve valid drafts and viewport. Replaced
   decisions and accepted history edits clear obsolete selections. Successful pass/card play closes
   its action context; accepted token/marker placement clears its province and amount. Rejection retains
   correctable input. Mark invalid choices and prevent stale submission. A preauthorized queued action submits once only when legal, never retries a failure.
6. **Explain consequences locally.** Show costs, modifiers, remaining resources and deterministic
   effects beside the action before commitment. State the selected card's effect explicitly, including
   the chosen commodity or targets and known cash/misery endpoints; do not hide the play's effect in
   a collapsed disclosure. Upgrades show current → new values. Partial placement explains its marker
   requirement and limiting resource beside the action, rather than in inspection. Map pickers ask
   “Pick a target” or “Pick N targets.”
   Distinguish current, projected, conditional and uncertain values; discounts, final prices and
   cash receipts are different quantities. Offer feasible alternatives.
7. **Use consistent identity and emphasis.** Capital names identify powers; before assignment use
   public handles in hosted games, retained in player overviews. Reuse symbols, owner silhouettes,
   quantities and terms. Every visible capital name carries its board silhouette, including previews,
   choices, reference lists and recorded activity. Text-only titles and accessible names retain names.
   Align capital symbols, names and surrounding sentence text vertically, including commodity receipts.
   Capital names share the surrounding text baseline, with symbols centered independently. Center
   paired resource labels and values, including icon rows; keep help footnotes intentionally raised.
   Dice state their colors. Wins/losses have text and green/red styling; color alone conveys nothing.
8. **Keep summaries compact.** Give each fact a predictable home, with decision-relevant state at
   rest and full tracks/breakdowns in optional detail. Remove repeated structure before shrinking
   text. Every extra control, panel and sentence must help a player choose, understand an effect or
   find data. Remove wording already implied by controls and implementation terms such as “signed
   whole number”; retain material exceptions in plain language. Help question marks look
   like footnotes, with a larger invisible hit area. Localize ordinary play; command syntax and
   internal IDs belong in technical views. Help is for printed data and non-obvious app behavior
   (variants, previews, navigation and undo), not general game rules already available elsewhere.
9. **Show consequential feedback.** Distinguish accepted, rejected, waiting and private-submission
   states. Keep results until dismissed or their scope ends; dismissing results never deletes
   history. Activity explains effects and excludes continuation noise in its own workspace tab or
   optional left block. Announce updates without taking focus or erasing unrelated feedback.
10. **Verify complete accessible flows.** Keyboard, touch and pointer actions need meaningful names,
    visible focus and readable contrast. Support six players, large hands, zoom, reduced motion and
    narrow screens. Keep the commit action reachable while inspecting and scrolling.

## Current table contracts

- Player boxes share the smallest equal height that fits the tallest summary, including across
  wrapped rows and in the phone Players workspace. They shrink together when status rows disappear.
  Expanded player details float below their summary without changing the player strip or map height.
  They omit the repeated player name and show written cash, tokens, deployed markers, and owned
  advances. Toggle them through the summary, without an overlapping close button. Explicitly say when
  no advances are owned. Keep income only in the summary, with a question mark beside its symbol
  opening the complete income track and a brief explanation of deployed markers and income modifiers;
  omit undeployed-marker counts and misery-relief actions here.
  Written cash is the public balance recorded at the latest simultaneous token-purchase reveal,
  initially $40; subsequent income/expenses and unrevealed bids do not alter that record.
  Show the advance count in the collapsed player box, with an accessible label.
  Group expanded advances by printed category, then printed letter; omit empty categories. Put status
  and available pool tokens on one wrapping row.
- Undo stays at the upper right of the action panel, outside its scrolling review. All final actions,
  including Pass, occupy one pinned footer. Draft choices and inputs stay with their consequences;
  the full-width turn-status box, heading and resource summaries scroll with the review. Long phone
  action panes may use up to 55% of the viewport height; avoid reserving most of that space for fixed
  headings or stacking three footer actions when two can share a row.
  Opening inspection or scrolling cannot move confirmation away. Keep native form validation and
  Enter submission intact. Number inputs ignore mouse-wheel changes while retaining focus,
  typing and arrow-key adjustment. Focusing a zero-valued number input clears it for immediate
  entry; preserve nonzero drafts. One action plus Pass uses a joined two-button control with a single
  divider; both labels remain complete and independently accessible. Larger action sets use separate
  buttons. Selected finite actions identify their operation and cost on the footer button.
  Undo withdraws an unrevealed capital bid or token purchase, retaining the local amount; after
  reveal it follows the selected policy. Its accessible name identifies the current operation.
- Activity shares workspace navigation with Map, Commodities and Advances. Each tab pairs its name
  with a distinct map, money bag, book or activity icon. On phones, use a left icon rail with accessible
  tab names, a dedicated Players tab with a people icon, and no player strip above the map. Desktop
  retains the player strip and named horizontal tabs. Selecting a workspace preserves the
  action form, prepared targets, hand and log scroll position. At viewport widths of at least
  1400px, an accessible pin control moves the same log into a separate left block; unpinning returns
  to the Activity tab. Render the log once, outside the action sidebar. Polling and temporary
  narrowing preserve the pin preference; narrower screens use the Activity tab. Tab changes and
  pinning must not reset valid drafts or activity scroll. Hide map-control buttons on phones, retaining
  gestures and keyboard zoom, fit and inspection cleanup; desktop keeps its controls. Help explains
  this navigation.
- Pending capital bids and token purchases offer withdrawal until simultaneous reveal. Clear saved
  readiness while retaining the amount in the local input; reloading must not restore a withdrawn
  submission. Committed history and resource balances change only on reveal.
- Hosted registration asks only for a unique handle, preserving capitalization while search and
  uniqueness ignore case. Search, selected participants, live game labels
  and administrator player lists use `@handle`; account UIDs remain internal to selection and requests.
  Compact player boxes put the handle beside the capital name and omit `@` there. Keep this identity
  on one row; long handles truncate visually, with the full identity available on hover and to
  assistive technology.
- Require a creator-chosen, single-line game name of up to 80 characters in creation forms. Show it
  beside the colored favicon in the game header, in the browser tab, and above participants in game
  listings. Long names truncate within the available header/row width and retain their full text.
  Failed creation preserves the name and player draft.
- Purchase substeps identify their parent explicitly: Purchase · Shipping, Purchase · Advances,
  Purchase · Stabilization and Purchase · Misery relief. A purchasing Pass names the next step or
  finishing Purchase, while preserving the same canonical command and engine readiness.
- The online directory prioritizes the game list, with Own games and Other games tabs for every
  registered account and a compact labeled status select. Keep creation collapsed below the list;
  administrative controls have their own tab. Own-game filters are retained independently of browsing
  other games. Other games excludes memberships and offers public spectator links, without a My move
  filter. Preserve the own-game turn indicator while browsing other games.
  Spectator tables identify Spectating and Public information only, retain public object inspection
  and activity, and omit the private hand, action forms, undo, command console and history editing.
  Public player detail shows written cash; no private balance or card identity may be reconstructed.
- The game icon is a named link to My games; omit a separate My games link. Hosted handle and sign-out
  controls share the top header row with the game title and icon, including on phones. Game listings use one
  keyboard-accessible link per row, with a distinct background and status text for pending actions.
  Highlight every player still required to act, including simultaneous prompts; omit submitted seats
  and all action highlights for inactive games. Expose the status in accessible text as well as color.
  Omit creation dates, IDs and Open buttons. Administrative rows expand their operational controls;
  they do not grant access to another player's seat. Poll the list every 30 seconds while visible;
  omit a manual Refresh button and the question mark beside My games. Administrative changes refresh
  the list immediately.
- The header uses a larger colored favicon; page titles include the assigned capital, or the player
  name during setup. The header icon stays colored regardless of the browser tab's turn indicator.
  Include the current deck count with the epoch, for example **Epoch 2 (22 cards remaining)**;
  do not repeat that count elsewhere. Papal Decree appears as a banner in Advances. Omit the This
  turn disclosure; keep military, protection and War facts with the actions they affect, and retain
  leader and commodity lookups in their workspaces. On phones, Commands and Game options live in
  Players without a bottom bar. Desktop retains their bottom disclosures; omit a header Commands
  button. Province connections remain
  on the map. Event and advance descriptions remain available in the game. Legends, variant
  descriptions, payout boxes, printed tracks and app help open in contextual reference popups.
  Omit card and advance catalogs already available through game inspection, general game rules,
  and the game-flow walkthrough. Omit the header Help button; contextual reference popups retain
  their topic chooser. Advance inspection shows effects, the credit given to other advances in
  its category from the next turn, current-turn relief, prerequisites, owners and the current
  price breakdown directly. Use compact, aligned rows and a second price column when space permits;
  omit foldouts, printed-value links and duplicate leader-navigation controls in that inspection.
  On phones, give the open inspection the workspace area and retain a reachable close control;
  closing returns to the unchanged advance list.
  Document all undo scopes, activity boundaries, creator permissions, retained
  answers and immutable randomness; creation and history controls link to that explanation.
  Game options lists the selected settings without navigation links. Keep the auxiliary disclosures'
  labels readable and reachable in their desktop or phone location, with wrapping text when needed.
- Location decisions activate map targets immediately, including Crusades, Rebellion and
  Pirates/Vikings. Shade the whole province/coast or eligible area. A map click edits the draft;
  the action button confirms it. Select playable cards from the persistent hand, without another
  card list beside Pass. The selected card's choices and consequences have one action context;
  selection does not also open an automatic duplicate inspector. Name the selected object once in
  that context. Show the selected card's effect directly, then its chosen target/commodity and projected
  consequences beside confirmation. Commodity choice buttons include the commodity’s symbol. Cash changes identify who receives or loses money; leader credits
  say they become available after play. Preserve uncertainty where decisions or dice remain unresolved.
  Card faces and inspections omit type headers. Selection and playability use pressed state and
  borders, without redundant “Selected” or “Playable” labels. Mark recurring cards with a shared badge rather
  than naming their printed shape; the public reference explains the recycling behavior.
- The viewer's hand occupies the bottom of the map/workspace column during play, purchases,
  expansion and waiting. Its desktop width matches that column; the action sidebar extends
  down beside it. Bound workspace, action and activity scrolling so long content cannot push the hand
  offscreen.
  Narrow layouts place the action review and reachable confirmation above the same hand area;
  neither it nor the hand overlays map targets. Size short action panes to their content, leaving
  more room for the workspace. Commands and configuration remain
  reachable without displacing the hand. Omit a visible private-hand title and count; retain an
  accessible name for the hand. Cards share the smallest equal height that fits the tallest card
  content, capped only when needed to leave room for the workspace and actions. Short hands shrink
  to their content; expanded rows retain equal card heights.
  Desktop compact cards and expanded hands show current commodity owner payouts and adjustments,
  relevant leader credits and concise event effects. Keep unavailable cards readable, with selection opening their blocker or
  reference while preserving the active draft. On desktop show Expand only when cards or their
  contents need more space; retain Collapse while expanded. Expand the same hand surface; selecting
  a playable card returns it to compact form. On narrow screens compact cards show only names;
  always offer Expand for a nonempty narrow hand and reveal full contents when expanded. Keep card
  order stable and preserve hand scroll through polling and inspection. Keyboard focus brings an offscreen card into view without
  scrolling the whole table.
  Other panes do not summarize hand contents, remind the player of held leaders or mark advances
  with potential held-card credits. Public unplayed-leader indicators, played leaders and
  applied purchase discounts describe distinct public or action facts. Render held-card identities
  only from the viewer's projected hand, never by reconstructing holdings from the reference catalog.
- Full-market placement is primary when legal. Otherwise use the largest engine-offered partial
  placement and label it **Place maximum possible tokens**. Omit destinations with no feasible
  pool/shipping commitment. Keep marker reduction collapsed and competition commitments exact.
  Maintain separate full inspection evaluations so selecting an omitted destination still explains
  pool/shipping limits, missing access, no route or existing control. Candidate highlights and
  enabled controls use feasible commitments, including legal partial placements.
- Turn-order reference is a printed-track lookup, linked by a visible **Turn order & green die**
  control beside token purchases and unresolved ties, a footnote beside each resolved player box,
  and the competition's green-die consequence. The shared topic stays in the reference chooser.
  Pending order shows positions and boxes for the starting player count without capital assignments;
  resolved order adds capital silhouettes, names and a distinct viewer row. Player summaries retain
  position and printed box separately from **You**, and omit misleading capital-order position
  numbers while this turn's order is unresolved. Live order changes update an open lookup.
  Competition shows engine-projected winning green faces, including Proselytism and defender Chaos;
  an empty set explicitly says green cannot win. The lookup explains the independent black/white
  route to victory and tie modifiers without adding a general rules section. Opening or closing it
  preserves bid and attack drafts; keyboard dismissal restores focus to a surviving trigger.
- Expansion uses one compact pool summary with current → projected balances while reviewing a
  placement. Keep commitment, outcome and income beside confirmation; omit repeated action headings.
  Competition omits the generic dice disclaimer. Whenever an offered route uses shipping, show
  destination use as current → projected / capacity directly, including partial commitments and
  blocked inspections. Omit the placement-details disclosure and stock-marker count.
  Offer voluntary marker reduction only when all 25 of that player's markers are deployed, using
  the engine's legal commands and previews. After freeing a marker, remove further reduction offers.
- Expansion summaries show each player's white markers and active military cards. Omit generic
  matchup help and omit the military box entirely when nobody has one. Competition and War outcomes live in
  activity. A submitted action that resolves one also opens a dismissible result for its player;
  loading or polling another player's result does not create an action notice.
  Competition receipts and suspended requests belong to that turn's Expansion and expire when it
  ends, including outcomes first received with the phase transition. Keep the activity entry.
- Reference tracks highlight all players at their public current positions using capital silhouettes,
  with the viewer outlined and shared positions retaining every marker. Income uses deployed markers;
  commodity tracks use the current effective payout box, including zero and boxes beyond ten when
  occupied. Misery includes Chaos and shipping includes no ships. Open popups follow accepted polling
  without disturbing drafts. Commodity help shows the number newly introduced in each configured epoch
  and public unplayed circulation since the last epoch shuffle. Include held cards and private discards;
  exclude future epochs and public plays, restoring recycled cards on the next shuffle. A shared card
  counts for each commodity it offers, regardless of which choice was played.
- Commodities lists every printed commodity from setup, before there are owner payout forecasts.
  Live owner forecasts begin with the first turn.
  Commodity cards show shortage/surplus badges. Commodities and selected commodity cards offer
  controls for full payout boxes in reference popups; keep current owner payouts and adjustments
  in the game. Clicking a commodity card highlights every province in play of its commodity types;
  a province's commodity icon applies the same inspection for just that commodity, preserving drafts.
  card replacement, deselection and normal selection cleanup remove obsolete highlights. Market
  tiles put the payout-help question mark beside the commodity name, without
  expandable details or replacement controls for the removed breakdown. Commodity cards link
  directly to their payout tracks; other activity cards open
  the same card-inspection popup, even after leaving the hand, with viewer-specific leader credits.
  Opening, Escape/close and focus restoration preserve the active form, target picker and prepared
  values; inspection never replaces that form. Advance inspections retain their workspace surface.
  Live values update on accepted polling snapshots after competitions, counting all current circle
  markers, including white ones. Remove the defeated owner immediately; ignore square tokens and
  Chaos. These are forecasts using current adjustments, not a change to actual colored-only payouts.
- Advances show one clickable circle per matching leader not publicly played, with its printed epoch
  inside. Each circle opens that leader’s reference popup, preserving the prepared action and restoring
  focus on close. Keep circles separate from the advance selection button. Held/future cards and
  private discards stay included; this cannot promise remaining drawable cards.
- Leader totals explicitly include the viewer's Written Record bonus, or identify the displayed
  credit as preceding Written Record. Compact hand credits may share one Written Record note per
  card when it applies to every listed total. Played leaders retain their play-time enhancement.
- Advance tiles show a price and concise credit total. An optional inspection disclosure names
  each contributing advance, Institutional Research and leaders, including Written Record. Use
  prior-turn printed credits only; blocked quotes keep their reason and cannot authorize purchase.
  Show printed costs for owned advances or when no running-game quote exists. Detailed quotes
  retain the final price floor.
  Group misery events preview every active player, including unchanged boxes and protected players,
  and record those results in activity at play time.
  Preview printed misery boxes for each affected player when playing a card or buying an advance;
  selected targets update that preview without committing. Mark dice-dependent outcomes as uncertain.
- Available cash appears with spending decisions, including blocked purchases, rather than a
  permanent resource section. Buying a card with pool tokens shows its token cost. Compact hands
  support mouse/pen dragging, scrollbars, native touch scrolling and keyboard activation; dragging
  cannot select a card or send an order. Expanded and compact hands share their card selection and
  reference behavior, without remounting the active action form.
- Cancellation choices and confirmation show cost. Stabilization shows current → resulting misery
  boxes, including Chaos, on its payment button. Relief remains visible for an advance tier even
  when blocked, with Pass. Show authoritative unused MR dollars from this turn's advance purchases
  beside the relief choices. Selecting spaces prepares a draft; confirming the purchase atomically
  finishes the player's purchasing turn. An unaffordable purchase never advances the turn, and
  players with only unaffordable offers must explicitly Pass.
  During advance purchasing, show the same running MR total beside available cash, including
  while an advance purchase is selected. Derive it from the authoritative current-turn acquisitions.
  Gold and Ivory offer one joint $10 cancellation for each shared adjustment type. If commodity
  play leaves only one side with that type, label the remaining choice as that commodity alone.
- Misery boxes share a green-to-amber-to-red severity scale based on the authoritative track's
  ordered steps, with dark burgundy for Chaos. Apply it to player summaries, stabilization previews,
  final scores and the reference track. Retain printed values and accessible text.
  Place small contextual track-help icons beside misery and shipping numbers. Opening help must
  not select that player as a card target or disturb the prepared action.
- Event replacements resolve in current player order. Show every pending province for the chooser
  in one form, with explicit pool/empty choices, a shared budget and one reviewed confirmation.
  Identify the card and recipient once; province rows own their location labels. Show the current
  misery box; Civil War's earlier misery is already applied. Disable spending beyond the pool.
  After its capital replacement, Civil War shows the payable cash/token losses and remaining
  balances, using the same rounded calculation as execution.
- Purchase and Buy Card allow parallel preparation using the existing advances and hand. Label
  preview actions “Add to plan”; keep the actual required actor visible and public player summaries
  unchanged. Distinguish unsaved input, saved-for-review plans and explicit offline authorization
  with compact status labels. Help owns planning instructions, preview assumptions and boundary
  explanations. The plan list shows changes and omits Continue steps; retain those explicitly
  selected continuations in the saved plan and preserve their execution order.
  Saving never buys, reserves resources or auto-passes. Review plans remain editable on the legal
  turn; automatic plans expose cancellation before execution starts. Editing first confirms disarm.
  Show exact planned payment recipients, balances and misery effects beside each action.
  A changed consequence or draw pauses for review; no automatic discard follows a draw. Preserve
  local work through polling, navigation and inspection;
  server saves survive reload and another device. History edits cancel previous authorization.
- Activity occupies its workspace tab or optional left block, with bounded scrolling and no count.
  Keep its pin control clear of receipt text. It explains draw reasons,
  epoch/end triggers, protection money, leader rebates, related cash/misery
  changes and shortage/surplus dice. All displayed dice identify their colors. Show an action only
  once and cap the box height with scrolling. Capital-tie rolls show the resulting capital-choice
  priority among the rolling players, marking equal-result groups that must roll again. Player names
  and emblems are those at entry time,
  retained as later actions arrive. Record signed misery spaces and printed endpoints, including
  Chaos, so later state changes cannot rewrite the explanation. Name every misery cause, including
  New World income relief, debt payments and deferred War/Crusades effects. Record actual movement
  separately from any combined rule contributions; clamping must not invent intermediate boxes.
  Direct actions and automatic phase receipts retain their own changes when a response combines
  them. Source cards open the same card-inspection popup as other activity cards.
  Relief receipts show cash, rating spent and the current-turn rating sources without duplicating
  the cash expense. Every cash receipt uses the same recorded player name, emblem and signed amount,
  including the acting player. Holy Indulgence records each player's actual pool ↔ stock movements separately
  from the action that opened Expansion; include token-only transfers.
  Group a complete revealed token-purchase batch into one round receipt. Each participant retains
  a named cash cost, signed bid and actual tokens received, including zero, negative and stock-capped
  purchases. Private submissions never enter it; later rounds and polling cannot rewrite it.
  Keep replay commands and undo references separate from this presentation grouping.
- The shield favicon is colorful when this player has a required action and grayscale otherwise.
  The game list checks all the player’s awaiting games, independently of the visible filter/page.
  Hidden game tabs check every five seconds for two minutes after opening, a visibility change,
  an accepted changed snapshot or a successful mutation, then every 30 seconds while idle. Unchanged
  responses, including 304s and identical 200s, do not extend recent activity. The game-list check
  retains its 30-second cadence. Update the icon directly from accepted responses, independently
  of rendering. Own one read/timer, bound all API requests (authentication,
  fetch and body) to 15 seconds, and discard canceled/superseded results. Visibility, resume,
  page-restoration and reconnect events refresh immediately; conditional 304 reads retain the icon.
- Command history defaults to capital aliases, with saved player-label/ID alternatives. Aliases
  cover actors, targets, attributed dice and edit authors; stored commands and `@entry` references
  remain canonical.

- A decision owns its expansion destination and partial amount independently of inspection. Background
  clicks close connection highlights without discarding that draft. Use existing object controls
  and native player disclosures, without separate inspection buttons or an Inspection mode. Finite
  hand discard/trade decisions use the hand as their card selector, with confirmation beside the
  current decision. Patronage, order swap and market cancellation controls prepare the same command
  as their component controls and share one selection state.
- Every finite group has an accessible question. Map pressed state follows exact draft membership,
  including multiple targets, rather than the most recently inspected province. The map uses a roving
  tab stop for each of the province, commodity-icon and area groups, with arrow traversal and focus
  panning at zoom;
  Tab can leave the map for confirmation. Commodity-icon inspection preserves the prepared action.
- Costs and deterministic cash/misery endpoints come from domain quotes. Private submission status
  compares the editable amount with the authoritative saved value; generic operation success cannot
  label an edited or withdrawn value as saved.

## Linear game undo interaction

These contracts govern [Linear game](design.md#linear-game-undo). Legacy policy revisions
remain readable under their original semantics.

- Creation describes Linear game beside its choice: the creator can rewind earlier play, players
  can rewind their current activity, and later play is removed together. State that new rolls and
  random selections can change while a draw follows the restored deck. Help explains the limits
  and that information already learned remains known. The existing policy default stays Regular
  turn. Upgraded Friendly games show Linear game and a compact notice that Friendly game has been
  replaced with linear undo, including random events. Help explains the new continuation behavior;
  historical views before conversion retain the Friendly game label.
- Keep one primary Undo at the upper right. Under Linear game it opens a compact consequence review
  for the latest removable unit: destination, removed public actions, removal of random events,
  clearing saved submissions and pausing automatic plans. Confirm with **Rewind to here**. Use the
  same review for an earlier target selected under Commands; do not add a second timeline or a
  replacement-command textarea. Earlier targets use safe action labels and the destination's
  capital symbols; raw entry counts remain in the technical Commands view.
- Only present server-offered legal targets for this viewer. Never make an activity grouping imply
  that a protected partial batch or an isolated die/draw is independently removable. Prior revision
  inspection stays read-only. A missing permission or an inactive game cannot be bypassed by opening
  an old review. The creator can reach rewind review in a finished active game without a live prompt.
- Preserve the existing unrevealed-submission shortcut: primary Undo withdraws the caller's own
  saved bid/token amount and retains the unsaved local draft. That operation does not rewind history.
  Earlier history rewind remains reachable under Commands where permitted; its review explicitly
  says pending amounts will be cleared and will need resubmission.
- Successful rewind closes review, clears obsolete decisions/results and refreshes the table,
  scores and pending status. A stale review requires refreshed targets and another explicit
  confirmation. Polling and rejection preserve valid drafts; neither commits an offered target.
  Keep focus restoration, keyboard activation, phone scrolling and confirmation reachable. Expose
  one author-attributed rewind notice without duplicating removed events in live Activity; Help
  owns the explanation of old revisions and absent redo.
- Policy conversion refreshes capabilities and closes the old suffix editor without submitting its
  contents. Explain that earlier edits remain in history and new changes use rewind. Preserve the
  current action draft, saved private submissions and plan status when the underlying decision is
  unchanged. Only an actual rewind clears submissions and disarms plans. A stale pre-conversion
  write refreshes the new policy without automatic resubmission.

## Acceptance evidence

Review discovery, inspection, preparation, commitment and outcome together, including waiting,
interruptions, blockers, rejection and stale revisions. Domain tests own calculations; service tests
own authorization, privacy and persistence; browser tests own interaction and rendered geometry.
Fixtures prove only what they exercise. Record human accessibility/legibility findings separately.
The [design studies](ui-proposals/index.html) illustrate visual direction; current contracts govern.

## Review gate for the game-table principle

Before accepting a UI change, review a normal turn at desktop and phone widths in this order:

1. Start at rest: which object carries the choice, and what can be removed without losing a fact,
   action or reference? Count duplicate homes, labels and controls as defects, not extra coverage.
2. Prepare a choice: does the prompt say what to pick and how many? Do names retain symbols?
   Are current → new values, limiting resources and exceptions next to the action?
3. Open help/reference and return: is it lookup data or non-obvious app behavior? Does it preserve
   the draft, without requiring another inspector or restating general game rules?
4. Commit, reject and poll: accepted placement clears its selection; rejection retains correctable
   work; polling alone preserves a valid draft. Check these separately in live offers.
5. Inspect rendered desktop/phone views: do footnote links, card references, targets and confirmation
   remain legible and reachable? DOM existence and screenshots captured without review cannot
   establish visual clarity. Record automated facts and human observations separately.

Apply these questions to the changed flow and one neighboring flow (for example commodity and event
cards, full and partial placement, or shipping and advance purchase). A positive “feature exists”
assertion is insufficient: verify the unwanted duplicate is absent and the remaining path still works.

- Advance details show prerequisites and the printed dollar relief rating beside the effect; explain
  that relief applies to purchases this turn. Master Art discard decisions explicitly direct the
  responder to select from the persistent hand. Finite action buttons toggle their draft off; a clear
  selection control is available beside the consequence review. Marker reduction shows authoritative
  pool, stock and income endpoints and explains lost commodity control before confirmation.
- The header owns the compact final-turn status. Final scores open once when the game ends in a
  dismissible modal, with a header control to reopen them. Escape and close restore focus and leave
  the board available for inspection. Show “Resumes” only for an actual single-player interruption.
- Activity records public position changes with entry-time capital identities and before/after
  positions. Automatic Civil War movement appears when Expansion starts, separately from the
  original card play and any subsequent Renaissance swap.

## Table refinements and unanimous abandonment

Use a consequence review for expansion Pass while a reachable marker commitment remains. Repeating
an inspected commodity card toggles highlights without discarding its action draft. Render waiting
names and punctuation in a single text flow. Track Help waits for completed capital selection;
access hatching leaves overseas labels readable.

Advance review shares printed future-credit facts with inspection and shows the authoritative price
breakdown inline. Show accumulated MR → projected MR once, in dollars; omit an unchanged projection
when the gain is zero. Future category credit names its category, other advances and next-turn timing.

Place Abandon game in auxiliary tools (Players on phones). Confirm that proposing counts as agreement.
Show one consent section alongside the ordinary decision, with proposer, agreed seats and outstanding
responders using capital symbols. Agreement lasts as play continues; the last agreement irreversibly
ends the game without a winner. Use Agree and abandon game for the last responder. Keep play available
until unanimity and preserve valid drafts through nonfinal success, polling and rejection. Terminal
cleanup removes action controls and stale results; retain permitted inspection and the unanimous cause.
