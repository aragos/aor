# Application architecture

AoR separates pure game rules, application authority and presentation. [Engine](engine.md) maps
mechanics to core files; [UI](ui-design.md) owns interaction and [deployment](deployment.md) operations.

## Request flow and file map

1. [web/src/api.ts](../web/src/api.ts) sends authenticated requests with the expected game revision.
   [GameTable.tsx](../web/src/GameTable.tsx) renders viewer-safe state and decisions.
2. [Application.kt](../server/src/main/kotlin/dev/aragos/aor/Application.kt) and
   [HostedRoutes.kt](../server/src/main/kotlin/dev/aragos/aor/HostedRoutes.kt) handle transport;
   [Authentication.kt](../server/src/main/kotlin/dev/aragos/aor/Authentication.kt) and
   [Accounts.kt](../server/src/main/kotlin/dev/aragos/aor/Accounts.kt) resolve accounts and access.
3. [GameService.kt](../server/src/main/kotlin/dev/aragos/aor/GameService.kt) authorizes application
   operations. [GameDriver.kt](../server/src/main/kotlin/dev/aragos/aor/GameDriver.kt) progresses
   the engine and supplies live authority outcomes. Replay never invokes live randomness.
4. [GameStore.kt](../server/src/main/kotlin/dev/aragos/aor/GameStore.kt) and
   [PostgresGameStore.kt](../server/src/main/kotlin/dev/aragos/aor/PostgresGameStore.kt) publish
   durable changes atomically. Persistence does not decide game behavior.
5. [HistoryProjection.kt](../server/src/main/kotlin/dev/aragos/aor/HistoryProjection.kt) and
   [HistoryEvents.kt](../server/src/main/kotlin/dev/aragos/aor/HistoryEvents.kt) derive authorized
   history and activity. The client renders typed data, without parsing prose to reconstruct rules.

## Stored truth and concurrency

[GameDocuments.kt](../server/src/main/kotlin/dev/aragos/aor/GameDocuments.kt) owns document encoding
and validation; [GameLogReplay.kt](../server/src/main/kotlin/dev/aragos/aor/GameLogReplay.kt) replays
logs. Headers record player identities, explicit creator and compatibility versions. Creation writes
resolved options in revision 1, then automatic setup/outcomes in revision 2. Game name and lifecycle
are application metadata. Snapshots, readiness and directory status are derived caches.

Ordinary revisions contain **deltas**, not full snapshots. A history edit records `replaceFrom`
(the number of effective entries retained) and a replacement suffix. Reconstruct effective history
by applying revisions in order. Administrative engine upgrades archive the preceding document and
start a full checkpoint at the existing revision offset; subsequent writes resume delta semantics.
Prior revisions and exact archive bytes remain immutable.

Every public write checks `expectedRevision`. PostgreSQL locks the game row and commits history,
metadata, pending state and directory projections together. A storage generation advances on every
mutation, including private writes without a public revision change. Repeatable reads and generation-
checked caches preserve consistency across processes. File storage requires one writer per directory.
Failed writes leave state and receipts unchanged; no automatic retry may generate replacement outcomes.

Warm PostgreSQL instances retain validated game state across committed writes, checking the storage
generation under the row lock before reuse. A write publishes its cache entry only after its own
transaction commits; enclosing transactions bypass the cache. Restarts and eviction reconstruct and
validate the log. Immutable game histories share only public entry-time audit facts across viewers;
private history is projected for each authorized reader. Bounded replay checkpoints accelerate old
rewinds, discard superseded suffixes and fall back to ordinary replay after eviction. Static shipping
adjacency is shared by the exact map definition and starting player count; advances, ownership and
capacity remain live rule inputs.

[PostgresDatabase.kt](../server/src/main/kotlin/dev/aragos/aor/PostgresDatabase.kt) and
[Flyway migrations](../server/src/main/resources/db/migration/) own schema evolution. Never change an
applied migration. Database schema updates and explicit game-engine upgrades are separate operations.

## Identities, privacy and private submissions

Firebase UIDs identify hosted accounts; engine seat IDs belong to one game. Membership maps those
identities. Creator/admin roles are explicit. Unique public handles identify current players;
recorded entries keep their original names and capital symbols. Administrative access does not
impersonate a seat. Spectators use public projections and have no private hand, choice or edit access.

Simultaneous capital bids and token purchases persist outside the committed engine log until all
required seats submit. Validate and append a complete batch atomically in stable player order.
Pending values have no game effect and can be replaced or withdrawn. Private operations compare both
public revision and an opaque owner receipt within the transaction; another seat's private write does
not conflict. Bounded UUID operation receipts reconcile uncertain responses without retaining amounts.

Apply audience restrictions to actions, outcomes, diagnostics, plans and old revisions as well as
live state. Public ETags exclude other players' private writes. Only explicit administrative exports
contain complete authoritative hidden outcomes.

## History and undo

[HistoryEdits.kt](../server/src/main/kotlin/dev/aragos/aor/HistoryEdits.kt) owns suffix edits;
[OutcomeIdentity.kt](../server/src/main/kotlin/dev/aragos/aor/OutcomeIdentity.kt) proves random-event
continuity. [LinearHistory.kt](../server/src/main/kotlin/dev/aragos/aor/LinearHistory.kt) owns prefix
rewind. [Commands](commands.md#application-history-commands) defines their API.

| Policy | Permission |
| --- | --- |
| Competitive | Committed actions are final. |
| Regular turn (default) | Own entries in the open phase activity, until another player acts or a random outcome is recorded. |
| Friendly turn | The active player's current phase activity, including nested responses; preserve random events and outcomes. |
| Linear game | Creator: any complete post-setup prefix, including after engine completion. Others: their currently open phase activity. |

A turn scope means one phase activity, not several phases. Nested responses retain the outer owner's
scope. Setup through capital selection is protected. Historical reads retain the viewer's audience.
Suffix edits may retain another player's action through `@entry` references, but cannot invent their
replacement answer. Replay must validate the whole suffix and match every unchanged random outcome
to its original logical request; reject added, removed, ambiguous or incompatible requests atomically.

### Linear game

The effective log after rewind must be an exact shorter prefix. One unit contains an action, all
immediately required authority outcomes and automatic transitions; a simultaneous reveal is one
complete unit. Legal targets derive from replay boundaries, not activity rows or revision numbers.
There is no redo or suffix replacement. Permission is checked against the current live position.
Cancelled/abandoned games cannot rewind; metadata, membership and lifecycle are outside the prefix.

Rewind itself consumes no randomness. New live rolls and selections use fresh samples; draws follow
the restored deck. Only a removed shuffle requested again gets a new permutation. Retained outcomes
stay unchanged. This permits rerolls and decisions informed by discarded history.

Publication appends an author-attributed `undo N` revision with `replaceFrom = N` and an empty suffix.
Revision numbers increase; retained entry identities stay stable and removed identities are never
reused. Under the same lock, clear pending submissions and rotate owner receipts, disarm plans and
rotate plan versions, expire out-of-scope work and invalidate previews. Retain in-scope saved actions
only as unarmed drafts and execution receipts as historical facts. The worker must not recreate
undone play. Stale or uncertain requests require reconciliation and new explicit confirmation.

[LinearGameMigration.kt](../server/src/main/kotlin/dev/aragos/aor/LinearGameMigration.kt) upgrades
legacy Friendly game policies through an archived, audited zero-entry revision. Preserve the original
chain, entry identities, outcomes, prompts, private state, receipts and valid plan authorizations;
validate each old edit under its revision-time policy. Conversion alone does not clear submissions
or disarm plans. The operation is atomic, resumable and idempotent, including restored/imported games.
All readers must understand both policies before migration. Rollback requires compatible code;
restoring a source archive loses subsequent play. Verify deployed continuity separately from tests.

## Private plans

[ActivityPlans.kt](../server/src/main/kotlin/dev/aragos/aor/ActivityPlans.kt) owns saved Purchase and
Buy Card plans; [ActivityPlanning.kt](../core/src/commonMain/kotlin/dev/aragos/aor/ActivityPlanning.kt)
owns viewer-safe simulation. Plans contain canonical commands bound to intended decision names.
Saving for review never reserves resources or executes. Explicit authorization binds a saved version
to exact previewed consequences and can execute while offline.

Private plan writes check public revision and owner version; arming also checks the preview token.
Cancellation and execution serialize under the game lock. Disarm/delete can tolerate intervening
public appends if the owner receipt is unchanged. Editing an armed plan requires confirmed disarm.
The durable worker scans after startup and every five seconds; reads and replay never execute plans.

Validate a whole deterministic segment before committing it. Append ordinary owner-authored command
revisions and consumed progress atomically. Stop at an activity boundary, missing answer or authority
outcome. Draws resolve normally and disarm the suffix; changed consequences pause for review without
substituting commands. Manual actions cancel the owner's authorization; history edits disarm plans;
phase completion expires them and inactive games cannot execute. Failed writes consume no progress.

## Automated seats

New automated seats are available only in local private-link games. Hosted creation rejects automated
opponents and requires 3–6 registered human players. Existing saved games retain their controller
metadata and creator automation endpoints so they can continue without a save migration.

[BotAutomation.kt](../server/src/main/kotlin/dev/aragos/aor/BotAutomation.kt) owns application-level
controller metadata and creator-only browser leases. Bot seats retain ordinary engine identities;
only human seats have account memberships or private-link credentials. Existing documents without
automation fields decode as human-only games. The PostgreSQL membership migration permits explicit
automated seats without manufacturing accounts, while archives and checkpoints preserve controller
metadata and pending decisions.

The browser's [runner](../web/src/ai/botRunner.ts) sends one seat-specific observation to one local
worker at a time. [AI](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/) consumes viewer-safe public facts,
the deciding seat's private facts and bounded candidate features. It returns an ordinary canonical
command. The server validates and records it and supplies actual authority outcomes. Reads do not
execute bots; search makes no speculative server calls.

Thirty-second leases carry a fencing epoch. Bot commands check the lease, public revision, decision
identity and owner's private-submission version atomically. Bounded UUID/hash receipts reconcile
uncertain responses without repeating decisions. Accepted sealed amounts remain private and do not
advance public revision until the ordinary batch reveal. Public snapshots exclude runner identity,
lease deadlines, seeds, bot hands and operation receipts.

Only the creator can host, pause, resume or explicitly take over a runner. Regular-turn undo and
history edits preserve the runner and any explicit pause: their own-action scope cannot cross another
seat's action or a recorded outcome. The new revision rejects stale bot work, and the runner fetches a
fresh decision automatically. Other history edits and prefix rewinds pause bots and rotate their epoch,
preventing stale or renewed workers from recreating undone play without explicit resume. Completion
and closure stop new bot work. Creator abandonment consent also records a separate agreement for each
bot; all other human seats still need their own consent.

The browser host is trusted with bot information and execution. A Web Worker isolates computation
from rendering, not secrets from its host. Competitive trusted execution and fully local authority
are separate hosting modes. Persisted commands replay without loading a model or running a policy.

Offline [training](../training/src/main/kotlin/dev/aragos/aor/training/) runs as a standalone executable,
with no production storage or model service. Its matrix covers the complete player-count/variant
product. Compact model assets are versioned by schema/rules/catalog. The
[model loader](../ai/src/jsMain/kotlin/dev/aragos/aor/ai/BotExports.kt) accepts legacy single-model
assets and a schema-1 bundle with an ordered `models` array of up to sixteen entries. It selects the
first valid compatible entry; malformed entries are skipped, and no match uses the rule-based prior.
Place a specialized entry before any broader matching fallback and retain legacy catalog entries for
existing games.

A scoped entry must declare both exact `rulesVersion` and `dataVersion`, plus a `configuration`
matching the observation's player count, map, initial-card rule, deck modification and immediate
Master Art discard. [BotConfiguration](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotModel.kt)
owns this public setup contract: JSON uses enum names such as `STANDARD` and `NO_CRUSADES`, not
training configuration identifiers. Missing observation setup cannot activate a scoped entry.
Unscoped legacy entries keep their existing compatibility behavior. Model selection changes future
choices only; it neither rewrites recorded commands nor requires a saved-game migration.

[Full-game experiments](contributing.md#full-game-strategy-experiments) freeze the candidate,
incumbent and opponent population, rotate the replacement seat and pair chance seeds. Low-dimensional
search changes only named planning weights and uses separate screening and selection banks; its
chosen candidate still requires independent confirmation. Training checks coverage explicitly and
retains compatible fallbacks when promotion evidence is insufficient.
[Contributing](contributing.md#opponent-training) owns operation and resource limits.

Planning inputs remain projections of public facts and the acting seat's own hand. Current-route
[expansion forecasts](../core/src/commonMain/kotlin/dev/aragos/aor/ExpansionForecastView.kt)
support card timing and token purchases without authorizing future actions. Shared resource budgets
constrain combinations; new white pieces never create an invented same-turn route. The controller
reconstructs contextual stabilization penalties over the whole proposed purchase sequence.
Optional learned corrections value commodity combinations, public area losses, interest costs and
immediate Institutional Research savings. Normal advance category credits still begin next turn.
Purchase plans account for cumulative interest receipts. A final-turn advance residual values printed
points, quoted cash and immediate own misery changes; it remains an additive estimate, with later
income, stabilization and sequential misery changes outside that residual.
The server builds the seat-specific projections and candidate features. The browser evaluates those
inputs and runs bounded planning; complete-game evaluation and parameter selection remain offline.
Optional [leader timing forecasts](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotLeaderPlanning.kt)
compare the existing shipping and advance planner with and without a leader's credit. Both arms use
the same post-play cash and hand costs, so rebates and stabilization savings are counted once.
The comparison respects eligibility, price floors and shipping expenditure; a separate retained
option values unowned future discounts. Worthwhile own commodity receipts can precede the leader,
allowing the next observation to revalue the actual budget. These bounded forecasts authorize no
purchase during card play and assume no other player's future actions.
Optional player-target metadata comes from authorized public card choices. A separately gated Hard
correction uses the decision seed to break exact-score ties among the chosen card's opponent targets.
It preserves the selected card and timing; disabled models and older observations retain their
previous choices. The seed makes repeated evaluation of the same decision reproducible.

The experimental `pilot` mode uses the same engine through resumable, forkable in-memory sessions.
Its commodity teacher samples explicit known-information curriculum fixtures and trains a compact
four-choice selector, including retaining baseline behavior. Fresh held-out continuations must show
improvement before ordinary full games measure transfer without giving policies hidden state.
Pilot models use a separate experimental schema; checkpoints and models stay in training storage and
are not browser model assets. See the [experiment workflow](contributing.md#automata-experiments)
for scope and qualification.

## Lifecycle and activity

[GameOperations.kt](../server/src/main/kotlin/dev/aragos/aor/GameOperations.kt) handles administrative
closure; [GameAbandonment.kt](../server/src/main/kotlin/dev/aragos/aor/GameAbandonment.kt) handles
unanimous player consent. A proposal includes the proposer's agreement; every seat, including Chaos
and offline seats, must agree to that same request. Play continues while pending. Decline, withdrawal,
history edits, policy upgrades, administrative closure and completion close obsolete consent.
Final agreement atomically abandons without scoring/revealing secrets, clears submissions, rotates
receipts and disarms plans. UUID receipts reconcile retries, including archived checkpoints.

Activity uses entry-time facts and stable entry identities. Appending history cannot rewrite past
labels or effects; explicit history edits may replace affected entries. Keep direct action receipts
and automatic phase receipts separate, including when their resource changes cancel in one response.
See [contributing](contributing.md) for verification and [deployment](deployment.md) for compatibility.
