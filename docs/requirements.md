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

- Creation selects players, current variants and undo policy, then starts setup automatically.
  Preserve the fixed three-epoch format and the reviewed variant catalog.
- Allow each player to withdraw their unrevealed capital bid or token purchase. Restore their
  pending action, retain the local amount as an unsaved draft, and keep withdrawn amounts private.
- Keep the AoR header compact and identify the assigned capital in the page title. Show the actor,
  decision and relevant resources; keep inspection separate from action forms and preserve drafts
  while inspecting other objects. Show available cash alongside spending decisions; omit a permanent
  resource section. Keep hand cards compact, allow dragging as well as scrolling, and include each
  player's advance count in their summary. Expanded advances follow printed category and letter order.
  Show written cash in expanded player details and keep income in the summary. Put the live deck
  count beside the epoch, with no duplicate count in turn effects.
- Activate map targets immediately for location choices, including Crusades, Rebellion and
  Pirates/Vikings. Shade eligible land/coasts together. Map clicks prepare choices; confirmation
  commits them. Prefer visible finite choices and playable cards beside Pass.
- Omit expansion destinations with no commitment possible under pool/shipping limits. Preserve
  feasible partial placements; call the largest legal partial placement **maximum possible tokens**.
  During expansion, show each player's white markers and active military cards with matchup
  qualifications; omit the military box when nobody has an advantage. Keep results in activity;
  explicitly show a dismissible outcome to the player whose submitted action just resolved it.
  Losses use red result styling. Competition notices and pending result requests expire when their
  expansion ends; later polling or a new expansion must not restore them.
  Selecting an unavailable province explains its authoritative blocker without offering an invalid
  action. Preserve legal smaller placements and keep missing access requirements specific.
- Show costs at offer and confirmation, stabilization's current and resulting misery boxes, commodity
  adjustments. Keep event and advance descriptions in the game, with current commodity payouts and
  leader credits in card inspections. Identify whether each leader discount includes Written Record.
  Keep full payout boxes, legends, variant descriptions and printed tracks on public `/help`, linked
  contextually in a new tab. Show matching unplayed leader counts for epochs I–III beside each
  advance, with the public-information meaning above.
- Label token-priced card purchases with the current token cost. Show applied category, research and
  leader discounts in optional price detail, naming the contributing advances. Keep tiles compact,
  show prices for blocked advances, and retain printed costs when no live quote exists. Card plays
  and advance purchases preview every affected player's misery boxes; identify outcomes that still
  depend on decisions or dice as uncertain.
- Keep Help focused on reference and detailed undo policies; omit the game-flow walkthrough and
  consolidate shared rules. Color every misery box by track position: lower steps are less severe,
  progressing from green through amber to red and finally dark Chaos. Keep numeric labels.
  Link track help through small question icons beside misery and shipping values. Card displays
  use a Recurring badge when applicable, with no shape vocabulary or redundant type heading.
- Show the relief step to players with an advance tier even if unaffordable or no spaces remain;
  explain the blocker and offer Pass. Permit an optional, cancelable Urban Ascendancy purchase draft
  while waiting; submit once on the legal turn while that view remains open, with no failed retries.
  Resolve event replacements in current player order. Offer all of the chooser's affected provinces
  together, enforce their shared pool budget, and review the complete group before one confirmation.
  Identify the event, recipient, pool cost and result; explain when no pool token remains and avoid
  implying another misery charge. Civil War's subsequent choice shows both actual cash and token
  losses and resulting balances, after capital replacement and rounding.
- Open bounded, scrollable activity by default, with an icon to hide/show it and no header or count.
  Show every action once. Preserve entry-time names and emblems as later history is appended;
  cards open the in-game inspector without changing an action draft.
  Explain epoch/end triggers, draw reasons, shortage/surplus rolls,
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
- Name every activity cash recipient or payer consistently, including the acting player. Inset the
  open panel's close control. Commodity card/market values follow current circle ownership,
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
- Skip publicly empty Renaissance windows at activation, using the same target calculation as swaps.
  Treat the resulting replay continuation change as an explicit rules-version boundary.
- Use a colorful shield favicon for outstanding player decisions and grayscale while waiting.
  The game-list favicon checks all of the current player’s games regardless of filter or page.
  Hidden game tabs poll more often after recent game activity or engagement, return to occasional
  checks while idle, and recover after stalled reads or browser/network suspension. Unchanged
  responses do not keep a game active. Favicon changes must not require activating the tab or
  painting the game.
- Support keyboard, touch, narrow layouts, bounded map gestures, zoom and dense-piece inspection.
  Persist preferences and creation defaults in browser storage with an in-memory fallback.

## Online operation and remaining scope

Use stable registered identities and unique public handles throughout hosted play; registration
requires no separate display name. Live views of existing games use handles while recorded activity
keeps its original identities. Account navigation shares the game overview bar. Each listed game is
one clickable row, distinguished by pending-action status, without creation dates or visible IDs.
Administrators control creation permission; members can filter their own ongoing, awaiting-move and
finished games.
Administrators may inspect games, cancel/abandon them, export logs, validate upgrades and purge by ID.
These operations are authenticated, audited and distinct from player actions.

Hosted PostgreSQL stores all durable state. Independent writers preserve optimistic revisions and
private submissions; failed transactions roll back history, metadata and audit receipts together.
Prefer managed authentication/TLS and automated dependency checks and fresh deployment images.

[Follow-ups](follow-ups.md) own launch credentials, DNS and initial administrator decisions. Human
screen-reader, physical-phone, browser-chrome zoom and composited/SVG contrast review remain separate
from automated evidence. Toulouse and Constantinople's labels are accepted. Bots and persistent private plans
remain deferred; the in-view Urban Ascendancy draft does not provide a general planning system.
