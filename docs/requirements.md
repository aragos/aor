# Product goals and requirements

This is the acceptance contract for the application. [Rules](rules.md) own game behavior and reviewed
rulings; [design](design.md) owns application architecture; [commands](commands.md) own wire syntax;
[UI principles](ui-design.md) and [map contracts](map-design.md) own interaction. Build commands live
in [README](../README.md), and coding conventions in [AGENTS.md](../AGENTS.md).

## Goals

- Let 3–6 people play a complete three-epoch Age of Renaissance game online, including asynchronous
  decisions, interruptions, private hands, simultaneous bids and final scores.
- Make ordinary play available through the map, cards and contextual controls. Keep the command
  interface useful for inspection, expert play, reproducible reports and authorized history edits.
- Preserve a reviewable, deterministic record through restarts, engine changes and history edits.
- Keep deployment and routine maintenance simple, with a target hosting budget of $25/month for
  hundreds of games per month. A subdomain of the existing Squarespace-managed domain is available.

## Domain and history

1. The engine owns legality, costs, eligibility, game effects and readiness. Geometry, prose,
   persistence and authentication do not decide game rules.
2. Replay uses canonical player commands and recorded outcomes. It performs no IO, clock reads or
   live randomness. Logical random events retain their original outcomes through every history edit.
3. Strong identifiers and constrained values validate their intrinsic invariants. Rejections have
   typed diagnostics. Catalog values and reviewed connections have one logical owner.
4. Ordinary revisions append deltas; edits retain an explicit prefix and replace a suffix. Earlier
   revisions remain immutable. Every write checks the expected revision and publishes atomically.
5. Simultaneous submissions remain private and have no effect before the complete batch commits.
   Views, history, errors and earlier revisions preserve each viewer's information permissions.
6. Undo follows the four documented scopes; setup and complete simultaneous batches are protected.
   An editor may retain another player's answer by reference, but cannot supply its replacement.
7. Only the current rules/data/file versions are supported. Any operational upgrade of saved games
   must be explicit, validated and auditable; persistence never silently migrates game behavior.

## Player experience

- A map decision activates immediately. Shade eligible land and coastal water together, retain
  clear borders, and require confirmation to submit the prepared choice.
- Prefer visible finite choices. Put playable cards with Pass, collapse rare marker reduction, and
  default peaceful expansion to the largest legal commitment when a full market is unavailable.
- Retain all competition results through expansion. Offer readable player names or capitals in
  command history while retaining canonical IDs in saved entries.
- Polling preserves valid work. Changed decisions and accepted history edits invalidate stale
  drafts. Rejections retain correctable choices and never automatically resubmit them.
- Support keyboard use, phone layouts, bounded map gestures and readable inspection of dense pieces.
  Accessible names and contrast checks supplement human screen-reader and physical-phone review.
- Persist user preferences and creation defaults across at least a browser session; use durable
  browser storage where available, with a usable in-memory fallback.

## Online operation

- Players register stable accounts. Game creation selects existing registered identities, with a
  readable handle/display name and stable identifier so names alone cannot select the wrong person.
- An administrator can restrict game creation. Members see their own games and can filter ongoing,
  awaiting-their-move and finished games.
- Administrators can inspect all games, cancel or abandon them, export all authoritative command
  logs programmatically, validate explicit log upgrades, and purge games by ID to reclaim storage.
- Administrative operations are authenticated, separated from player actions and audited. Private
  game data is available only to game members and authorized administrators.
- Prefer managed authentication, TLS and platform protection. Automate dependency/security checks
  and fresh deployment images as far as the hosting platform permits.

## Open decisions and scope

The European map requires its exact province changes, memberships, connections, support arrows and
geometry before activation. Toulouse and Constantinople retain documented constrained label fits.
A full-session screen-reader/physical-phone review remains a human check. Broader removal of
unreachable retired-rule handlers needs a separately reviewed change; current configuration rejects
all retired choices. Hosting/authentication
accounts, DNS access and the initial administrator identity must be supplied before public launch.

Bots and private plans are future designs. Retired edition presets and their earlier rulings do not
select current gameplay. The current variant list is in [the variant inventory](variant-inventory.md).
