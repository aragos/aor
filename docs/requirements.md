# Product scope

AoR supports complete, asynchronous, three-epoch Age of Renaissance games for 3–6 seats, including
private hands, simultaneous bids, nested decisions, all 64 cards and final scoring. The visual table
serves ordinary play; commands support expert input, reproducible reports and authorized history edits.

## Core acceptance

- The engine owns legality, costs, targets and readiness. Replay of canonical commands and recorded
  outcomes is deterministic and performs no IO or fresh randomness.
- Existing saved games remain playable, directly or through an implemented, tested upgrade.
  Preserve history, outcomes, identities, metadata, memberships and pending private state.
- Durable writes publish atomically with optimistic concurrency. Private submissions take effect
  only when a complete batch commits. Saved plans execute only with explicit authority.
- Views, previews, diagnostics, counts and history preserve the viewer's information audience.
  Hosted play requires verified, registered accounts; spectators receive public inspection only.
- The table makes decisions, consequences and results understandable on desktop and phone widths.
  Inspection, navigation and polling preserve valid work; accepted decision changes clear obsolete
  work. Keyboard and touch users can reach every action and confirmation.
- History policies protect setup and enforce their scopes. Linear game permits complete-prefix
  rewind; suffix editing preserves recorded random events. Revisions stay immutable and auditable.
- Cancellation and abandonment retain permitted inspection and stop play. Player abandonment requires
  explicit agreement by every seat on the same request; play continues until unanimity, without
  scoring or revealing private values when abandoned.

Detailed contracts live in [architecture](design.md), [UI](ui-design.md), [rules](rules.md) and
[commands](commands.md). Their implementation files and tests are the starting point for changes.

## Operation and deferred scope

Hosted play uses Firebase identity and managed PostgreSQL. The operating target is hundreds of games
per month within a $25/month hosting budget; representative production load must establish capacity.
An existing Squarespace domain can supply a subdomain. [Deployment](deployment.md) owns configuration
and recovery; [open work](follow-ups.md) distinguishes unverified launch checks from automated tests.

Online game creation requires 3–6 registered human players and does not offer automated opponents.
Existing saved games retain their controllers and can continue play.

Purchase and Buy Card support private phase-scoped plans. Local automated seats offer Easy, Normal and
Hard policies with an integrated economic evaluator. The creator's browser runs a single worker;
server-authoritative games pause automated progression when no creator browser is available.
Client hosting is for solo and trusted casual play: the host can inspect automated hands and modify
its local policy. General future-turn scripts, midgame controller replacement, trusted competitive
bot hosting and fully offline authority remain deferred.
