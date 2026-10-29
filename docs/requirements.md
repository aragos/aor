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
- Keep the AoR header compact and identify the assigned capital in the page title. Show the actor,
  decision and relevant resources; keep inspection separate from action forms and preserve drafts
  while inspecting other objects.
- Activate map targets immediately for location choices, including Crusades, Rebellion and
  Pirates/Vikings. Shade eligible land/coasts together. Map clicks prepare choices; confirmation
  commits them. Prefer visible finite choices and playable cards beside Pass.
- Omit expansion destinations with no commitment possible under pool/shipping limits. Preserve
  feasible partial placements; call the largest legal partial placement **maximum possible tokens**.
  During expansion, show each player's white markers and active military cards with matchup
  qualifications; omit the military box when nobody has an advantage. Losses use red result styling; results can be cleared without deleting history.
- Show costs at offer and confirmation, stabilization's resulting misery box, current commodity
  adjustments. Keep full payout boxes, legends, variant descriptions, card/advance rules, and
  printed tracks on the public `/help` page, linked contextually in a new tab. Show matching unplayed leader counts for epochs I–III
  beside each advance, with the public-information meaning above.
- Keep Help focused on reference and detailed undo policies; omit the game-flow walkthrough and
  consolidate shared rules. Color every misery box by track position: lower steps are less severe,
  progressing from green through amber to red and finally dark Chaos. Keep numeric labels.
- Show the relief step to players with an advance tier even if unaffordable or no spaces remain;
  explain the blocker and offer Pass. Permit an optional, cancelable Urban Ascendancy purchase draft
  while waiting; submit once on the legal turn while that view remains open, with no failed retries.
- Open a bounded, scrollable activity box by default and show every action once. Preserve
  entry-time names and emblems as later history is appended; cards link to their reference.
  Explain epoch/end triggers, draw reasons, shortage/surplus rolls,
  protection money, leader rebates and related cash changes; label dice colors and omit continuation noise.
- Polling preserves valid work. Decision changes and accepted edits clear obsolete drafts. Rejections
  retain correctable input; stale requests refresh without automatic resubmission.
- Use a colorful shield favicon for outstanding player decisions and grayscale while waiting.
  The game-list favicon checks all of the current player’s games regardless of filter or page.
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
from automated evidence. Toulouse and Constantinople's labels are accepted. Broader removal
of unreachable retired-rule handlers requires separate review. Bots and persistent private plans
remain deferred; the in-view Urban Ascendancy draft does not provide a general planning system.
