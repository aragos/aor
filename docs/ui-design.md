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
   Interruptions name the required responder and the activity that resumes.
4. **Separate reference from action.** Cards, advances, provinces and players remain inspectable in
   a dedicated surface. Inspecting another object must preserve the active form, prepared targets
   and picker. Inspection, eligibility, draft selection and commitment have distinct cues.
5. **Define draft lifetimes.** Polling and navigation preserve valid drafts and viewport. Replaced
   decisions and accepted history edits clear obsolete selections. Successful pass/card play closes
   its action context; rejection retains correctable input. Mark invalid choices and prevent stale
   submission. A preauthorized queued action submits once only when legal, never retries a failure.
6. **Explain consequences locally.** Show costs, modifiers, remaining resources and deterministic
   effects before commitment. Distinguish current, projected, conditional and uncertain values;
   discounts, final prices and cash receipts are different quantities. Offer feasible alternatives.
7. **Use consistent identity and emphasis.** Capital names identify powers; before assignment use
   public handles in hosted games, retained in player overviews. Reuse symbols, owner silhouettes,
   quantities and terms.
   Dice state their colors. Wins/losses have text and green/red styling; color alone conveys nothing.
8. **Keep summaries compact.** Give each fact a predictable home, with decision-relevant state at
   rest and full tracks/breakdowns in optional detail. Remove repeated structure before shrinking
   text. Localize ordinary play; command syntax and internal IDs belong in technical views.
9. **Show consequential feedback.** Distinguish accepted, rejected, waiting and private-submission
   states. Keep results until dismissed or their scope ends; dismissing results never deletes
   history. Activity opens by default, explains effects and excludes continuation noise. Announce
   updates without taking focus or erasing unrelated feedback.
10. **Verify complete accessible flows.** Keyboard, touch and pointer actions need meaningful names,
    visible focus and readable contrast. Support six players, large hands, zoom, reduced motion and
    narrow screens. Keep the commit action reachable while inspecting and scrolling.

## Current table contracts

- Expanded player details show total income, tokens, deployed markers, and owned advances. Omit
  income components, undeployed-marker counts, and misery-relief actions from this inspection.
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
  Commands remain in the bottom expander; omit their header button. Province facts remain in
  inspection. Event and advance descriptions remain available in the game. Legends, variant
  descriptions, printed tracks and expanded rules belong on public `/help`, linked in a new tab.
  Help omits a game-flow walkthrough. Explain shared card/market rules once and link individual
  entries to them. Document all undo scopes, activity boundaries, creator permissions, retained
  answers and immutable randomness; creation and history controls link to that explanation.
- Location decisions activate map targets immediately, including Crusades, Rebellion and
  Pirates/Vikings. Shade the whole province/coast or eligible area. A map click edits the draft;
  the action button confirms it. Playable cards also appear beside Pass.
- Full-market placement is primary when legal. Otherwise use the largest engine-offered partial
  placement and label it **Place maximum possible tokens**. Omit destinations with no feasible
  pool/shipping commitment. Keep marker reduction collapsed and competition commitments exact.
- Expansion summaries show each player's white markers and active military cards with matchup
  caveats. Omit the military box entirely when nobody has one. Competition and War outcomes live in
  activity. A submitted action that resolves one also opens a dismissible result for its player;
  loading or polling another player's result does not create an action notice.
- Commodity cards show shortage/surplus badges. Markets and selected commodity cards offer
  links to full payout boxes on the reference page; keep current owner payouts and adjustments
  in the game. Activity cards open the same in-game inspector, even after leaving the hand, with
  current payouts and viewer-specific leader credits. Inspection never replaces an active form.
  Live values update on accepted polling snapshots after competitions, counting all current circle
  markers, including white ones. Remove the defeated owner immediately; ignore square tokens and
  Chaos. These are forecasts using current adjustments, not a change to actual colored-only payouts.
- Advances show matching leaders not publicly played, grouped by printed epoch. Held/future cards
  and private discards stay counted; this cannot promise remaining drawable cards.
- Leader totals explicitly include the viewer's Written Record bonus, or identify the displayed
  credit as preceding Written Record. Played leaders retain their play-time enhancement.
- Advance tiles show a price and concise credit total. An optional inspection disclosure names
  each contributing advance, Institutional Research and leaders, including Written Record. Use
  prior-turn printed credits only; blocked quotes keep their reason and cannot authorize purchase.
  Show printed costs for owned advances or when no running-game quote exists. Detailed quotes
  retain the final price floor.
  Preview printed misery boxes for each affected player when playing a card or buying an advance;
  selected targets update that preview without committing. Mark dice-dependent outcomes as uncertain.
- Available cash appears with spending decisions, including blocked purchases, rather than a
  permanent resource section. Buying a card with pool tokens shows its token cost. Compact hands
  support mouse/pen dragging, scrollbars, native
  touch scrolling and keyboard activation; dragging cannot select a card or send an order.
- Cancellation choices and confirmation show cost. Stabilization shows current → resulting misery
  boxes, including Chaos, on its payment button. Relief remains visible for an advance tier even
  when blocked, with Pass.
  Gold and Ivory offer one joint $10 cancellation for each shared adjustment type. If commodity
  play leaves only one side with that type, label the remaining choice as that commodity alone.
- Misery boxes share a green-to-amber-to-red severity scale based on the authoritative track's
  ordered steps, with dark burgundy for Chaos. Apply it to player summaries, stabilization previews,
  final scores and the reference track. Retain printed values and accessible text.
- Urban Ascendancy may be queued privately while waiting. The cancelable draft requires the view
  to remain open, clears on expiry and submits once when offered; it does not auto-pass Master Art.
- Activity opens in its own sidebar outside the action panel, visible before scrolling on desktop.
  An accessible icon toggles the panel, inset while open; omit its header and count. It explains draw reasons,
  epoch/end triggers, protection money, leader rebates, related cash/misery
  changes and shortage/surplus dice. All displayed dice identify their colors. Show an action only
  once and cap the box height with scrolling. Player names and emblems are those at entry time,
  retained as later actions arrive. Record signed misery spaces and printed endpoints, including
  Chaos, so later state changes cannot rewrite the explanation.
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
  of rendering. Own one read/timer, bound snapshot reads (authentication,
  fetch and body) to 15 seconds, and discard canceled/superseded results. Visibility, resume,
  page-restoration and reconnect events refresh immediately; conditional 304 reads retain the icon.
- Command history defaults to capital aliases, with saved player-label/ID alternatives. Aliases
  cover actors, targets, attributed dice and edit authors; stored commands and `@entry` references
  remain canonical.

## Acceptance evidence

Review discovery, inspection, preparation, commitment and outcome together, including waiting,
interruptions, blockers, rejection and stale revisions. Domain tests own calculations; service tests
own authorization, privacy and persistence; browser tests own interaction and rendered geometry.
Fixtures prove only what they exercise. Record human accessibility/legibility findings separately.
The [design studies](ui-proposals/index.html) illustrate visual direction; current contracts govern.
