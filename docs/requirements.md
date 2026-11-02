# Product goals and requirements

This is the acceptance contract. [Rules](rules.md) own mechanics and reviewed rulings;
[commands](commands.md) own syntax; [design](design.md) owns architecture; [UI principles](ui-design.md)
and [map design](map-design.md) own interaction. [README](../README.md) owns build commands and
[AGENTS.md](../AGENTS.md) coding conventions.

## Goals

- Complete online three-epoch games for 3–6 people, including asynchronous decisions, interruptions,
  private hands, simultaneous bids and final scores.
- Ordinary play through recognizable map, card and advance objects, with commands for expert play,
  reproducible reports and authorized edits.
- Deterministic, reviewable history through restarts and explicit upgrades.
- Simple operation for hundreds of games per month, targeting $25/month hosting. An existing
  Squarespace-managed domain can supply a subdomain.

## Domain, history and privacy

1. The engine owns legality, prices, commitments, eligibility and readiness. Use strong domain types,
   intrinsic validation and typed rejections; geometry, transport and persistence do not decide rules.
2. Replay uses canonical commands and recorded outcomes, with no IO, clock reads or fresh randomness.
   History edits preserve the outcomes of the same logical random events.
3. Ordinary revisions append deltas. Edits retain an explicit prefix and replace a suffix while
   earlier revisions remain immutable. Writes check the expected revision and publish atomically.
4. Private submissions take effect only when their complete batch commits. Views, errors, history
   and earlier revisions preserve each viewer's audience. Public leader counts use public plays,
   retaining privately discarded leaders in the counts so no hidden identity is disclosed.
5. Undo follows its four documented scopes. Setup and complete simultaneous batches are protected;
   another player's answer may be retained by reference, never supplied as a replacement.
6. Support only current rules/data/file versions. Saved-game upgrades are explicit, validated and
   auditable; schema maintenance cannot silently change game behavior.

## Player experience

- Creation selects a creator-chosen game name, players, current variants and undo policy, then starts setup automatically.
  Preserve the fixed three-epoch format and the reviewed variant catalog.
- Allow each player to withdraw their unrevealed capital bid or token purchase. Restore their
  pending action, retain the local amount as an unsaved draft, and keep withdrawn amounts private.
- Keep the AoR header compact and identify the assigned capital in the page title. Show the actor,
  decision and relevant resources in the action pane, including one consistent waiting status naming
  the required players; omit waiting labels from the header and player boxes. Keep inspection separate
  from action forms and preserve drafts
  while inspecting other objects. Use the object itself rather than separate Inspect controls;
  keep province inspection on the map. Clear the province after accepted token/marker placement,
  retaining correctable work after rejection. Show available cash alongside spending decisions; omit
  a permanent resource section. Include each player's advance count in their summary. Player boxes
  share the height of the tallest summary across all rows, without extra reserved space. Expanded
  advances follow printed category and letter order, with an explicit empty state when none are owned.
  Expanded player details omit the repeated name and close button; the summary toggles the floating
  detail without resizing the player strip or map. Show written cash in expanded player details and
  keep income in the summary, with an income-symbol question mark for its track and marker explanation.
  Put the live deck count beside the epoch.
- Activate map targets immediately for location choices, including Crusades, Rebellion and
  Pirates/Vikings. Shade eligible land/coasts together. Map clicks prepare choices; confirmation
  commits them. Ask “Pick a target” or “Pick N targets” during card targeting. Prefer visible finite
  choices; select held cards from the hand rather than a second list beside Pass.
- Keep the viewer's hand beneath the map/workspace column during card play, other decisions and
  waiting. On desktop, the action sidebar extends beside the hand instead of surrendering
  its lower space to it. Workspace, action and activity scrolling must not push the hand below the
  viewport or cover it. On narrow screens, coordinate the action review and confirmation above the
  hand rather than overlaying separate fixed panels. Keep short action panes sized to their content.
  Let the turn-status box and headings scroll with long action reviews, retaining reachable Undo and
  confirmation controls and usable review space on short phones, including with an expanded hand.
  On phones, use a left icon rail for workspaces and a dedicated Players tab instead of the player
  strip. Keep Commands and configuration in Players, without a bottom bar. Show Papal Decree as a
  banner in Advances; relevant actions own other turn effects. Omit the visible private-hand heading
  and card count. Size cards and the hand to their
  contents, with a height cap only when needed to retain usable workspace and actions. Compact narrow
  hands show card names only; expansion reveals their full content.
  Card summaries show current commodity payouts and adjustments, relevant leader credits and concise
  event effects. Keep unavailable cards readable and explain their blockers when selected. Show held
  cards only from the viewer's projected hand; reference catalogs cannot reveal another player's hand.
  Play, discard and trade use this same hand selection surface, with separate explicit confirmation.
  Card selection and playability remain visually distinct without redundant text badges.
  A selected card has one action context that explicitly states its play effect, chosen targets or
  commodity, and projected cash/misery consequences before confirmation. Leader credits are identified
  as becoming available after play. Show known effects directly rather than hiding them behind a rules
  disclosure; retain unresolved decisions and dice as uncertainty. Other panes do not repeat hand
  contents, held-leader reminders or potential-credit badges; public leader availability, played
  leaders and actual purchase discounts remain distinct facts.
  Support mouse/pen dragging, scrollbars, native touch scrolling and keyboard access to every card;
  dragging cannot select or play one. Offer expansion only when cards overflow or their content needs
  more space; keep Collapse available while expanded. Expand the same hand without replacing action
  drafts. Selecting a playable card returns to the compact hand. Preserve valid card selection,
  targets, amounts and hand scroll through inspection, polling and rejection; accepted actions and
  changed decisions clear obsolete drafts.
- Event and leader inspections open in a popup, including references from activity. Escape, closing
  and returning focus preserve the active action and its prepared choices. Advance inspection retains
  its existing workspace surface and directly shows category credit, relief, prerequisites and
  itemized price without foldouts or non-help links; commodity cards retain their own live values
  and direct payout reference links.
- Keep Undo at the upper right and all final actions, including Pass, in a pinned action footer.
  Join exactly one action plus Pass with a divider while retaining complete labels, independent
  activation and native form behavior. Larger sets use separate buttons; keep draft choices by their
  inputs. Undo withdraws an unrevealed bid or token purchase while preserving the local draft; after
  reveal it follows the chosen policy. Keep the full action-sidebar height available for decisions.
  Player boxes share a wrapping status/pool row. Expansion uses one current
  → projected pool balance. Center action labels and inline identities.
- Make the printed turn-position → order-box → green-die mapping discoverable beside token
  purchases, unresolved turn-order ties, player order boxes and competition previews. Before order
  resolves, show the starting-player-count track without assigning capitals or predicting private
  bids. Once resolved, show current capital assignments and identify the viewer. Show applicable
  green-die winning faces beside an attack, including Proselytism and Chaos restrictions; distinguish
  this alternative win condition from the black/white dice. Reference preserves valid action drafts.
- Omit expansion destinations with no commitment possible under pool/shipping limits. Preserve
  feasible partial placements; call the largest legal partial placement **maximum possible tokens**.
  During expansion, show each player's white markers and active military cards with matchup
  qualifications; omit the military box when nobody has an advantage. Keep results in activity;
  explicitly show a dismissible outcome to the player whose submitted action just resolved it.
  Losses use red result styling. Competition notices and pending result requests expire when their
  expansion ends; later polling or a new expansion must not restore them.
  Explain a partial placement’s marker requirement and limiting resource beside the action.
  Selecting an unavailable province explains its authoritative blocker without offering an invalid
  action. Preserve legal smaller placements and keep missing access requirements specific.
- Show costs at offer and confirmation, stabilization's current and resulting misery boxes, commodity
  adjustments. Keep event and advance descriptions in the game, with current commodity payouts and
  leader credits in leader inspections. Commodity cards retain live payouts on the card and small
  question links to full payout tracks, without an inspection box. Clicking a commodity card or a
  province's commodity icon highlights matching provinces in play, preserving the prepared action;
  ordinary selection cleanup removes the highlight. Satellites remain distinct from Stone at Fit
  and zoom; support arrows retain clean tips at high zoom and Cagliari's remain visible. Distinguish
  advance-locked geography with hatching that clears when access unlocks, preserving inspection.
  Shipping help explains the meaning of each
  fleet's numbered boxes. Every visible capital name includes its symbol, including cash/misery
  previews. Shipping upgrades show current → new shipping.
  Identify whether each leader discount includes Written Record.
  Open full payout boxes, legends, variant descriptions, printed tracks and app help in contextual
  popups. Omit duplicate card/advance catalogs available in the game; retain unique printed credits,
  relief ratings and prerequisites in contextual advance popups. Show one clickable printed-epoch
  circle per publicly unplayed matching leader beside each advance. Commodity choices include symbols.
- Label token-priced card purchases with the current token cost. Show applied category, research and
  leader discounts in optional price detail, naming the contributing advances. Keep tiles compact,
  show prices for blocked advances, and retain printed costs when no live quote exists. Card plays
  and advance purchases preview every affected player's misery boxes. Group misery events include
  unchanged/protected active players in both preview and activity; identify outcomes that still
  depend on decisions or dice as uncertain.
- Keep Help focused on printed data lookup, variants and non-obvious app behavior, including undo;
  omit general game rules and the game-flow walkthrough. Remove redundant or technical play text.
  Color every misery box by track position: lower steps are less severe,
  progressing from green through amber to red and finally dark Chaos. Keep numeric labels.
  Link track help through footnote-sized question icons beside misery and shipping values. Card displays
  use a Recurring badge when applicable, with no shape vocabulary or redundant type heading.
- Show the relief step to players with an advance tier even if unaffordable or no spaces remain;
  explain the blocker and offer Pass. Show the unused MR dollars from this turn's advance purchases.
  Also show that running MR total during advance purchasing, updating it after each purchase.
  A confirmed relief purchase also finishes purchasing; unaffordable relief requires explicit Pass.
  Permit an optional, cancelable Urban Ascendancy purchase draft
  while waiting; submit once on the legal turn while that view remains open, with no failed retries.
  Resolve event replacements in current player order. Offer all of the chooser's affected provinces
  together, enforce their shared pool budget, and review the complete group before one confirmation.
  Identify the event, recipient, pool cost and result; explain when no pool token remains and avoid
  implying another misery charge. Civil War's subsequent choice shows both actual cash and token
  losses and resulting balances, after capital replacement and rounding.
- Offer bounded, scrollable Activity beside Map, Commodities and Advances in workspace navigation.
  Give each tab a distinct icon, using a money bag for Commodities; retain accessible names on the
  phone icon rail. Populate Commodities from game creation;
  owner payout forecasts begin with the first turn. In-game options show settings without links.
  At viewport widths of at least 1400px, allow pinning the same log into a separate left block;
  unpinning returns to the Activity tab. Narrower screens use the tab while retaining the pin
  preference for wider screens. Preserve valid action drafts and log scroll through navigation,
  pinning and polling. Keep the hand and action confirmation reachable, render one log and omit a count.
  Show every action once. Preserve entry-time names and emblems as later history is appended;
  cards open the card-inspection popup without changing an action draft.
  Explain capital-tie rolls' capital-choice order and remaining rerolls, epoch/end triggers, draw
  reasons, shortage/surplus rolls,
  protection money, leader rebates and related cash/misery changes; label dice colors and omit
  continuation noise. Misery relief itemizes cash and current-turn relief rating; Holy Indulgence
  records pool/stock token movements even when no cash changes.
  Every actual misery change belongs to an entry with its cause and recorded boxes, including
  automatic New World income relief and deferred card effects. Keep direct actions and automatic
  phase receipts distinct, even when their changes cancel within one server response.
- Show each round's fully revealed token purchases as one activity entry with every participant's
  signed bid, cash cost and actual tokens received. Unrevealed bids remain private and canonical
  command history remains unchanged. Gold/Ivory's shared adjustment has one priced cancellation
  choice; unpaired remaining markers retain individual choices.
- Name every activity cash recipient or payer consistently, including the acting player. Keep the
  pin control clear of receipt text. Commodity card/market values follow current circle ownership,
  including white circles during Expansion; actual card payments retain the colored-marker rule.
- Polling preserves valid work. Decision changes and accepted edits clear obsolete drafts. Rejections
  retain correctable input; stale requests refresh without automatic resubmission. Same-account
  credential refresh preserves the mounted table, viewport, inspector and history editor; sign-out,
  identity changes and authorization loss clear private work. Superseded session results cannot restore it.
- Bound credentials, fetch and response bodies to 15 seconds. An uncertain mutation releases the UI,
  reconciles an authorized snapshot and explains that its outcome is unknown; never replay it automatically.
- Private saves, replacements and withdrawals check an opaque owner-specific receipt inside the same
  transaction as the public revision. A stale same-seat write conflicts; another seat's private write
  does not. Saving and returning to an earlier amount still changes the receipt. Distinguish the saved
  private value from an unsaved browser draft, including after withdrawal.
- Present shipping, advances, stabilization and misery relief as steps within Purchase, including
  continuation buttons that identify the next step or finish purchasing.
- Offer Renaissance only in Buy Card, Play Cards, Purchase, Expansion and Final Card Play. Skip
  publicly empty windows at activation, using the same target calculation as swaps. Treat changed
  replay continuation as an explicit rules-version boundary.
- Explain Master Art discard through the hand, show prerequisites and dollar relief in advance details,
  preview marker reduction consequences with a clear selection control, and record public order
  movements with their causes. Keep the ending status in the header and final scores in a reusable popup.
- Use a colorful shield favicon for outstanding player decisions and grayscale while waiting.
  The game-list favicon checks all of the current player’s games regardless of filter or page.
  Hidden game tabs poll more often after recent game activity or engagement, return to occasional
  checks while idle, and recover after stalled reads or browser/network suspension. Unchanged
  responses do not keep a game active. Favicon changes must not require activating the tab or
  painting the game.
- Start the map fitted to the width of its viewport and constrain dragging/zooming in both axes so
  the view cannot expose empty space outside the board. A viewport taller than the board's proportions
  uses the minimum height-cover view needed to avoid empty bands. Resizing preserves the prepared
  action and bounds the resulting view. Hide map-control buttons on phones while retaining gesture
  and keyboard zoom, fit and inspection cleanup; desktop keeps its controls.
- Support keyboard, touch, narrow layouts, bounded map gestures, zoom and dense-piece inspection.
  Persist preferences and creation defaults in browser storage with an in-memory fallback.

## Online operation and remaining scope

Use stable registered identities and unique public handles throughout hosted play; registration
requires no separate display name. Live views of existing games use handles while recorded activity
keeps its original identities. The game icon links to the games list without a separate My games link;
the user handle and sign-out control share the top bar with the game title and icon. Each listed game is
one clickable row showing the creator-chosen game name and participants, distinguished by
pending-action status, without creation dates or visible IDs. Require a name in the creation form;
retain it in the game header and browser tab across reloads and history edits.
Administrators control creation permission. Every registered user can switch between Own games and
Other games, with a compact status filter. Own games includes ongoing, awaiting-move and finished games;
Other games excludes the viewer’s memberships and opens a read-only spectator table. Spectators can
inspect the public board, players, advances, commodities, activity and final scores. Spectator responses
exclude private hands, unrevealed bids and token amounts, private commands and choices, saved-submission
receipts, and history-edit or action permissions. Public inspection must never impersonate a seat.
Spectating requires the same authenticated, verified and registered account as hosted play.
Administrators may inspect games, cancel/abandon them, export logs, validate upgrades and purge by ID.
These operations are authenticated, audited and distinct from player actions.

Hosted PostgreSQL stores all durable state. Independent writers preserve optimistic revisions and
private submissions; failed transactions roll back history, metadata and audit receipts together.
Prefer managed authentication/TLS and automated dependency checks and fresh deployment images.

[Follow-ups](follow-ups.md) own launch credentials, DNS and initial administrator decisions. Human
screen-reader, physical-phone, browser-chrome zoom and composited/SVG contrast review remain separate
from automated evidence. Toulouse and Constantinople's labels are accepted. Bots and persistent private plans
remain deferred; the in-view Urban Ascendancy draft does not provide a general planning system.
