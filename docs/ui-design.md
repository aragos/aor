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
   Interruptions name the required responder and the activity that resumes. Keep the private hand
   visible below the workspace column, leaving the sidebar's height available for action and activity,
   without repeating held-card reminders in the workspace or action pane.
4. **Use the game objects as controls.** Click cards, advances and players for their relevant detail
   or choice; do not add a parallel Inspect button or map mode. Province connections are shown by
   map highlights, without a second province panel. Commodity cards show current payouts and a
   small question link to each full payout track, without a commodity inspector. Reference access
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
   Dice state their colors. Wins/losses have text and green/red styling; color alone conveys nothing.
8. **Keep summaries compact.** Give each fact a predictable home, with decision-relevant state at
   rest and full tracks/breakdowns in optional detail. Remove repeated structure before shrinking
   text. Every extra control, panel and sentence must help a player choose, understand an effect or
   find data. Remove wording already implied by controls and implementation terms such as “signed
   whole number”; retain material exceptions in plain language. Track help in player boxes looks
   like a footnote, with a larger invisible hit area. Localize ordinary play; command syntax and
   internal IDs belong in technical views. Help is for printed data and non-obvious app behavior
   (variants, previews, navigation and undo), not general game rules already available elsewhere.
9. **Show consequential feedback.** Distinguish accepted, rejected, waiting and private-submission
   states. Keep results until dismissed or their scope ends; dismissing results never deletes
   history. Activity opens by default, explains effects and excludes continuation noise. Announce
   updates without taking focus or erasing unrelated feedback.
10. **Verify complete accessible flows.** Keyboard, touch and pointer actions need meaningful names,
    visible focus and readable contrast. Support six players, large hands, zoom, reduced motion and
    narrow screens. Keep the commit action reachable while inspecting and scrolling.

## Current table contracts

- Expanded player details show written cash, tokens, deployed markers, and owned advances. Keep
  income only in the summary; omit undeployed-marker counts and misery-relief actions here.
  Written cash is the public balance recorded at the latest simultaneous token-purchase reveal,
  initially $40; subsequent income/expenses and unrevealed bids do not alter that record.
  Show the advance count in the collapsed player box, with an accessible label.
  Group expanded advances by printed category, then printed letter; omit empty categories.
- Pending capital bids and token purchases offer withdrawal until simultaneous reveal. Clear saved
  readiness while retaining the amount in the local input; reloading must not restore a withdrawn
  submission. Committed history and resource balances change only on reveal.
- Hosted registration asks only for a unique handle. Search, selected participants, live game labels
  and administrator player lists use `@handle`; account UIDs remain internal to selection and requests.
  Compact player boxes put the handle beside the capital name and omit `@` there. Keep this identity
  on one row; long handles truncate visually, with the full identity available on hover and to
  assistive technology.
- Hosted account, My games and sign-out controls share the game overview bar. Game listings use one
  keyboard-accessible link per row, with a distinct background and status text for pending actions.
  Omit creation dates, IDs and Open buttons. Administrative rows expand their operational controls;
  they do not grant access to another player's seat. Poll the list every 30 seconds while visible;
  omit a manual Refresh button. Administrative changes refresh the list immediately.
- The header uses **AoR**; page titles include the assigned capital, or the player name during setup.
  Include the current deck count with the epoch, for example **Epoch 2 (22 cards remaining)**;
  remove duplicate deck counts from the turn-effects section.
  Commands remain in the bottom expander; omit their header button. Province connections remain
  on the map. Event and advance descriptions remain available in the game. Legends, variant
  descriptions, printed data and app help belong on public `/help`, linked in a new tab.
  Omit general card/market/advance rules and the game-flow walkthrough from Help. Document all undo scopes, activity boundaries, creator permissions, retained
  answers and immutable randomness; creation and history controls link to that explanation.
- Location decisions activate map targets immediately, including Crusades, Rebellion and
  Pirates/Vikings. Shade the whole province/coast or eligible area. A map click edits the draft;
  the action button confirms it. Select playable cards from the persistent hand, without another
  card list beside Pass. The selected card's choices and consequences have one action context;
  selection does not also open an automatic duplicate inspector. Name the selected object once in
  that context. Show the selected card's effect directly, then its chosen target/commodity and projected
  consequences beside confirmation. Cash changes identify who receives or loses money; leader credits
  say they become available after play. Preserve uncertainty where decisions or dice remain unresolved.
  Card faces and inspections omit type headers. Mark recurring cards with a shared badge rather
  than naming their printed shape; the public reference explains the recycling behavior.
- The viewer's hand occupies the bottom of the map/workspace column during play, purchases,
  expansion and waiting. Its desktop width matches that column; the action/activity sidebar extends
  down beside it. Bound workspace, action and activity scrolling so long content cannot push the hand
  offscreen.
  Narrow layouts place the action review and reachable confirmation above the same hand area;
  neither it nor the hand overlays map targets. Commands, turn effects and configuration remain
  reachable without displacing the hand. Omit a visible private-hand title and count; retain an
  accessible name for the hand. Cards use their natural content height rather than stretching to an
  arbitrary tall row, capped only when needed to leave room for the workspace and actions.
  Compact cards show current commodity owner payouts and adjustments, relevant leader credits and
  concise event effects. Keep unavailable cards readable, with selection opening their blocker or
  reference while preserving the active draft. Show Expand only when cards or their contents need
  more space, and retain Collapse while expanded. Expand the same hand surface;
  selecting a playable card returns it to compact form. Keep card order stable and preserve hand
  scroll through polling and inspection. Keyboard focus brings an offscreen card into view without
  scrolling the whole table.
  Other panes do not summarize hand contents, remind the player of held leaders or mark advances
  with potential held-card credits. Existing public unplayed-leader counts, played leaders and
  applied purchase discounts describe distinct public or action facts. Render held-card identities
  only from the viewer's projected hand, never by reconstructing holdings from the reference catalog.
- Full-market placement is primary when legal. Otherwise use the largest engine-offered partial
  placement and label it **Place maximum possible tokens**. Omit destinations with no feasible
  pool/shipping commitment. Keep marker reduction collapsed and competition commitments exact.
  Maintain separate full inspection evaluations so selecting an omitted destination still explains
  pool/shipping limits, missing access, no route or existing control. Candidate highlights and
  enabled controls use feasible commitments, including legal partial placements.
- Expansion summaries show each player's white markers and active military cards with matchup
  caveats. Omit the military box entirely when nobody has one. Competition and War outcomes live in
  activity. A submitted action that resolves one also opens a dismissible result for its player;
  loading or polling another player's result does not create an action notice.
  Competition receipts and suspended requests belong to that turn's Expansion and expire when it
  ends, including outcomes first received with the phase transition. Keep the activity entry.
- Commodity cards show shortage/surplus badges. Markets and selected commodity cards offer
  links to full payout boxes on the reference page; keep current owner payouts and adjustments
  in the game. Commodity cards link directly to their payout tracks; other activity cards open
  the same card-inspection popup, even after leaving the hand, with viewer-specific leader credits.
  Opening, Escape/close and focus restoration preserve the active form, target picker and prepared
  values; inspection never replaces that form. Advance inspections retain their workspace surface.
  Live values update on accepted polling snapshots after competitions, counting all current circle
  markers, including white ones. Remove the defeated owner immediately; ignore square tokens and
  Chaos. These are forecasts using current adjustments, not a change to actual colored-only payouts.
- Advances show matching leaders not publicly played, grouped by printed epoch. Held/future cards
  and private discards stay counted; this cannot promise remaining drawable cards.
- Leader totals explicitly include the viewer's Written Record bonus, or identify the displayed
  credit as preceding Written Record. Compact hand credits may share one Written Record note per
  card when it applies to every listed total. Played leaders retain their play-time enhancement.
- Advance tiles show a price and concise credit total. An optional inspection disclosure names
  each contributing advance, Institutional Research and leaders, including Written Record. Use
  prior-turn printed credits only; blocked quotes keep their reason and cannot authorize purchase.
  Show printed costs for owned advances or when no running-game quote exists. Detailed quotes
  retain the final price floor.
  Preview printed misery boxes for each affected player when playing a card or buying an advance;
  selected targets update that preview without committing. Mark dice-dependent outcomes as uncertain.
- Available cash appears with spending decisions, including blocked purchases, rather than a
  permanent resource section. Buying a card with pool tokens shows its token cost. Compact hands
  support mouse/pen dragging, scrollbars, native touch scrolling and keyboard activation; dragging
  cannot select a card or send an order. Expanded and compact hands share their card selection and
  reference behavior, without remounting the active action form.
- Cancellation choices and confirmation show cost. Stabilization shows current → resulting misery
  boxes, including Chaos, on its payment button. Relief remains visible for an advance tier even
  when blocked, with Pass.
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
- Urban Ascendancy may be queued privately while waiting. The cancelable draft requires the view
  to remain open, clears on expiry or a history edit and submits once when offered; it does not auto-pass Master Art.
- Activity opens in its own sidebar outside the action panel, visible before scrolling on desktop.
  An accessible icon toggles the panel, inset while open; omit its header and count. It explains draw reasons,
  epoch/end triggers, protection money, leader rebates, related cash/misery
  changes and shortage/surplus dice. All displayed dice identify their colors. Show an action only
  once and cap the box height with scrolling. Player names and emblems are those at entry time,
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
  province tab stop, arrow traversal and focus panning at zoom; Tab can leave the map for confirmation.
- Costs and deterministic cash/misery endpoints come from domain quotes. Private submission status
  compares the editable amount with the authoritative saved value; generic operation success cannot
  label an edited or withdrawn value as saved.

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
