# Application architecture

Pure [engine](engine.md), application authority and presentation are separate. Paths below are
relative to [server/src/main/kotlin/dev/aragos/aor](../server/src/main/kotlin/dev/aragos/aor/).

## Request flow and file map

| Layer | Owner |
| --- | --- |
| Client requests / viewer-safe table | [api.ts](../web/src/api.ts), [GameTable.tsx](../web/src/GameTable.tsx) |
| Transport / identity and access | `Application.kt`, `HostedRoutes.kt` / `Authentication.kt`, `Accounts.kt` |
| Authorization / live engine progression and randomness | `GameService.kt` / `GameDriver.kt` |
| Atomic storage / encoding and replay | `GameStore.kt`, `PostgresGameStore.kt` / `GameDocuments.kt`, `GameLogReplay.kt` |
| Authorized history and activity | `HistoryProjection.kt`, `HistoryEvents.kt`; clients render typed data |

## Stored truth and concurrency

Headers record seat identities, explicit creator and versions. Creation stores resolved options in
revision 1 and automatic setup/outcomes in revision 2. Name/lifecycle are application metadata;
snapshots, readiness and directory status are derived.

Ordinary revisions are **deltas**. History edits retain `replaceFrom` effective entries and replace
the suffix; reconstruct history in revision order. An administrative engine upgrade archives the
exact preceding document and begins a full checkpoint at the existing revision offset, then resumes
deltas. Prior revisions and archives remain immutable.

Public writes check `expectedRevision`. PostgreSQL locks the game row and commits history, metadata,
pending state and directory projections together. Every mutation, including private-only writes,
advances a storage generation; repeatable reads and generation-checked caches keep processes consistent.
File storage permits one writer per directory. Failed writes change neither state nor receipts;
retries must not generate replacement outcomes. `PostgresDatabase.kt` and [Flyway](../server/src/main/resources/db/migration/)
own schema evolution: never edit applied migrations or use schema changes as game-engine upgrades.

Warm SQL state reuse checks generation under the row lock and publishes only after its transaction
commits; enclosing transactions bypass the cache. Restart/eviction replays and validates. Shared
immutable histories contain public entry-time audit facts; private history is projected per reader.
Bounded replay checkpoints discard superseded suffixes and fall back to full replay after eviction.
Static shipping adjacency is keyed by exact map/count; advances, ownership and capacity stay live.

## Identities, privacy and private submissions

Hosted Firebase UIDs map to game-local seat IDs through memberships; creator/admin roles are explicit.
Current handles may change, but recorded names/capital symbols retain entry-time identity. Admins do
not impersonate seats. Spectators receive public projections without hands, choices or edit access.
Apply audiences to outcomes, diagnostics, plans and historical reads too. Only administrative exports
include complete hidden outcomes; public ETags exclude other players' private writes.

Simultaneous bids/purchases persist outside the log until all seats submit; validate and append the
whole batch atomically in stable player order. Pending values have no game effect and may be replaced
or withdrawn. Private writes compare public revision and an opaque owner receipt under the lock;
other owners' private writes do not conflict. Bounded UUID receipts reconcile lost responses without
retaining amounts. [Commands](commands.md) owns API fields and diagnostics.

## History and undo

`HistoryEdits.kt` owns suffix edits, `OutcomeIdentity.kt` random-request continuity and
`LinearHistory.kt` prefix rewind. [Commands](commands.md#application-history-commands) owns syntax/API.

| Policy | Scope |
| --- | --- |
| Competitive | Committed actions are final. |
| Regular turn (default) | Own open phase activity, until another player acts or an outcome is recorded. |
| Friendly turn | Active player's phase activity including nested responses; preserve random events/outcomes. |
| Linear game | Creator: any complete post-setup prefix, even after completion. Others: their open phase activity. |

An activity covers one phase, including interruptions under the outer owner. Setup through capital
selection is protected. Suffix edits can retain others' actions with `@entry`, never invent their
answers. Validate the entire suffix and original logical random requests/outcomes; reject added,
removed, ambiguous or incompatible requests atomically. Historical reads retain the viewer's audience.

### Linear game

Rewind retains an exact shorter prefix with no redo/suffix replacement. A unit includes the action,
required outcomes and automatic transitions; simultaneous reveal is indivisible. Targets derive
from replay boundaries, not activity rows/revisions. Check permission at the live position;
cancelled/abandoned games cannot rewind. Metadata, membership and lifecycle remain outside the prefix.
Rewind consumes no randomness: subsequent live rolls/selections are fresh, draws follow the restored
deck and only a re-requested removed shuffle gets a fresh permutation. Retained outcomes stay fixed;
players retain discarded knowledge.

Publish an attributed `undo N` revision (`replaceFrom=N`, empty suffix). Revisions increase and removed
entry identities are never reused. Under the same lock, clear submissions/rotate receipts,
disarm plans/rotate versions (only the undoing player’s plans when turn and phase are unchanged),
expire out-of-scope work and invalidate previews. In-scope saved actions may remain unarmed drafts; execution receipts remain facts. Workers must not recreate undone play;
stale/uncertain requests reconcile before new explicit confirmation.

`LinearGameMigration.kt` converts legacy Friendly game policies with an archived, audited zero-entry
revision, preserving chain, identities, outcomes, prompts, secrets, receipts and valid plan authority.
Validate old edits under their revision-time policy. Conversion alone does not clear/disarm work.
Migration is atomic, resumable and idempotent for restored/imported games too. All readers must support
both policies first; rollback needs compatible code or a restore that loses later play. Deployed
continuity remains a separate [operational check](follow-ups.md).

## Private plans

`ActivityPlans.kt` owns saved Purchase/Buy Card plans; core `ActivityPlanning.kt` owns viewer-safe
simulation. Canonical commands bind intended decisions. Saving reserves nothing and never executes;
explicit authorization binds the saved version to exact previewed consequences, including offline.
Writes check public revision/owner version; arming also checks preview token. Execution/cancellation
serialize under the lock. Disarm/delete tolerate public appends with an unchanged owner receipt;
editing an armed plan requires confirmed disarm. A durable worker scans armed-plan metadata at startup/
every five seconds, decoding only recovery candidates; unrelated legacy files produce no recovery
warnings. Reads/replay never execute.

Validate a whole deterministic segment, then atomically append owner-authored command revisions and
progress. Stop at missing answers, authority outcomes or the activity boundary. Draws resolve normally
and disarm the suffix; changed consequences pause without substituting commands. Manual actions
cancel owner authorization, edits disarm, phase completion expires, and inactive games cannot execute.
Failed writes consume no progress.

## Automated seats

New bots are local private-link seats only; hosted creation requires 3–6 registered humans. Existing
saves retain controllers/endpoints. `BotAutomation.kt` owns metadata and creator-only 30-second fenced
leases. Human seats alone have memberships/credentials; absent automation metadata means human-only.
Archives/checkpoints preserve controllers and pending decisions.

The [browser runner](../web/src/ai/botRunner.ts) sends one seat-safe observation at a time to one local
worker. [Policies](engine.md#opponent-policies) return canonical commands; the server validates,
persists and supplies actual randomness. Reads never execute bots; search never calls the server.
Writes atomically check lease epoch, revision, decision and private-submission version; UUID/hash
receipts prevent duplicate uncertain decisions. Sealed amounts stay private until ordinary reveal.
Public snapshots exclude runner identity, deadlines, seeds, hands and receipts.

Only the creator hosts/pauses/resumes/takes over. Regular-turn undo/edits retain the runner and explicit
pause; stale work rejects and refreshes automatically. Other edits/rewinds pause bots and rotate epochs
until explicit resume. Completion/closure stops bots. Creator abandonment records separate bot consent;
each human still consents. The host can inspect bot secrets/modify policy; worker isolation protects
rendering, not secrets. Replay needs neither policy nor model. [Training](contributing.md#opponent-training)
is independent of production storage and promotes only compatible, evaluated models.

## Lifecycle and activity

`GameOperations.kt` handles administrative closure; `GameAbandonment.kt` unanimous player consent.
Proposing agrees; every seat (including Chaos/offline) must agree to the same request while play
continues. Decline, withdrawal, edits, policy upgrades, closure and completion expire obsolete consent.
Unanimity atomically abandons without scoring/private revelation, clears submissions, rotates receipts
and disarms plans. UUID receipts reconcile retries, including archived checkpoints.

Activity preserves entry-time facts/identities as history grows; only explicit edits replace affected
entries. Separate direct-action and automatic-phase receipts, even when resource changes cancel.
