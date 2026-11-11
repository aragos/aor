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
cancel owner authorization, except answering Renaissance before the planned activity. Its pending
authorization survives Keep/swap and still checks consequences before execution. Edits disarm,
phase completion expires, and inactive games cannot execute.
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

### Offline policy selection and experiments

Offline [training](../training/src/main/kotlin/dev/aragos/aor/training/) runs as a standalone executable,
with no production storage or model service. Its historical 128-configuration matrix keeps
draw-three-keep-two disabled. Compact model assets are versioned by schema/rules/catalog. The
[model loader](../ai/src/jsMain/kotlin/dev/aragos/aor/ai/BotExports.kt) accepts legacy single-model
assets and a schema-1 bundle with an ordered `models` array of up to sixteen entries. It selects the
first valid compatible entry; malformed entries are skipped, and no match uses the rule-based prior.
Place a specialized entry before any broader matching fallback and retain legacy catalog entries for
existing games.

A scoped entry must declare both exact `rulesVersion` and `dataVersion`, plus a `configuration`
matching the observation's player count, map, initial-card rule, deck modification, immediate
Master Art discard and draw-three-keep-two option.
[BotConfiguration](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotModel.kt)
owns this public setup contract: JSON uses enum names such as `STANDARD` and `NO_CRUSADES`, not
training configuration identifiers. Missing observation setup cannot activate a scoped entry.
Legacy assets and observations omitting `drawThreeKeepTwo` mean false; enabling it cannot activate
an existing scoped model qualified without that option.
Bot observations retain the game's recorded rules version, including supported rules-4 saves.
Rules-4 model qualification does not transfer to new rules-5 games. The purchase asset uses an
ordered schema-1 `models` bundle with explicitly qualified entries for each rules version; legacy
single-asset files remain supported. Each entry retains its frozen prior and ordinary-model binding.
Current-rules entries carry independent complete-game qualification; missing or incompatible entries
use the ordinary fallback.
The current four-player entry applies the qualified `planning.tokenOrderControl.fixed16To15` gate
after the ordinary token selector: a choice of 16 becomes 15 only when 15 is offered. Its purchase
asset binds that exact ordinary model while retaining the frozen purchase prior. Separate token
qualification records the ordinary and varied-token rival comparisons; it does not establish human
playing strength. Legacy entries retain their original choices.
An independently qualified expansion model can opt into
[eight absolute coefficients](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotExpansionModel.kt)
under `planning.expansionModel.*`. A unit gate and the complete coefficient set replace only those
ordinary weights for Hard `expand` decisions, retaining the existing beam planner. Other decisions,
other difficulties and incomplete entries keep the ordinary policy. Reserved transport keys never
contribute to ordinary feature scores; support alone does not activate a model or establish its
qualification.
The [military model hook](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotMilitaryModel.kt)
accepts a unit gate and four absolute card coefficients under `planning.militaryModel.*`.
It replaces those weights only for Hard play, before any expansion override. Easy and Normal retain
the ordinary coefficients; missing or incomplete transport data also retains them. Reserved keys
never contribute to candidate scores. Export must preserve the incumbent raw ordinary weights and
bind independently qualified military coefficients through this hook, so Hard-only evidence cannot
change other difficulties. No asset activates the hook merely by including this support.
The current rules-5 four-player entry combines the independently qualified lambda1 expansion,
fixed token control and military coefficients. Its purchase actor uses the separately qualified GAE
checkpoint with the unchanged F0 purchase prior. Fresh complete-game confirmation against mixed
rivals and heldout evaluation against the complete preceding policy validate this exact checkpoint.
The purchase asset binds the complete exported ordinary weights and preserves the preceding
qualification evidence in its provenance; legacy entries retain their previous parameters. These
comparisons establish neither GAE's superiority over other training methods nor human-level play.
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

The experimental [purchase learner](contributing.md#purchase-policy-learning) receives only an
ordinary acting-player view and currently offered commands through an explicit simulation controller.
It encodes public and own-seat facts with stable domain identities, keeps all offered purchase actions,
and shares a state embedding across action evaluation. A fixed HARD recommendation prior provides the
starting policy; a learned residual and value baseline train from repeated decisions in complete games.
The authority and its chance stream stay outside the controller. Sealed decisions still use a common
pre-submit state. Complete-game artifacts bind model, encoder, runtime and seed identities; their
terminal labels are separate from actor inputs. Neural models use a separate schema and require
explicit qualification before browser activation.

The experimental card actor has distinct versions and controls repeated offered card plays in normal
and final windows. It shares raw actor-state projection and residual-network arithmetic with purchase
inference while preserving the purchase model's contract. Complete-game card collection fixes a
separately fingerprinted purchase actor for every seat and records only the controlled seat's card
decisions for learning. Public purchase consequences form a separate diagnostic audit, never model
inputs or targets. Nested resolutions and other decisions retain their corresponding HARD baseline.

The offline [card advantage learner](../scripts/card_advantage_train.py) instead fits paired terminal
return differences between offered actions and a frozen HARD reference. Its separately versioned
[selector](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/CardAdvantagePolicy.kt) uses three compact
networks with explicit state–action interactions. It chooses from the same bounded physical-card
proposals used during collection, with a fixed margin and unanimous positive advantage; otherwise it
retains the reference. Only normal card windows are supported. Root and next-decision interventions
have distinct actor-safe observations and labels, and repeated policy-improvement cycles recollect
under their frozen continuation. Hidden authority state remains in the executor. Complete-game
qualification and explicit browser integration are required before a trained artifact can be used
in ordinary play; the experiment driver does not activate policies.

For supported HARD purchases, creator-only automation work includes a transient, baseline-free
projection from the ordinary actor view. The decision-only view omits public unplayed cards and is
insufficient for this encoder. The browser worker binds its actual baseline recommendation to the
complete offered command list and uses the same seeded sampler as training. A separate optional asset
binds the qualified setup and exact baseline; absent or incompatible assets and unsupported scopes
retain ordinary behavior. The decoded actor is cached for the worker's lifetime. This projection is
ephemeral work, so commands, saved games, recorded history and stale-operation fencing retain their
existing contracts.

The experimental `pilot` mode uses the same engine through resumable, forkable in-memory sessions.
Its commodity teacher samples explicit known-information curriculum fixtures and trains a compact
four-choice selector, including retaining baseline behavior. Fresh held-out continuations must show
improvement before ordinary full games measure transfer without giving policies hidden state.
Pilot models use a separate experimental schema; checkpoints and models stay in training storage and
are not browser model assets. See the [experiment workflow](contributing.md#automata-experiments)
for scope and qualification.

The local [campaign controller](../scripts/automata_campaign.py) runs a frozen sequence of
experiments without language-model calls. The [campaign adapter](../scripts/automata_experiments.py)
owns the scientific decisions: exploration diagnostics, bounded token-value trials, development
selection and fresh confirmation. The controller owns process groups, resource limits, immutable
inputs, cumulative deadlines and resumable stage receipts. A completed campaign is an execution
result; scientific rejection remains explicit and no result activates a production policy.
Counterfactual continuations share an origin's hidden world only inside the simulator; fitted
features remain actor-visible, and related branches stay in the same statistical partition.
Optional [token corrections](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotTokenCorrection.kt)
preserve the incumbent's token head and add a separately fitted selector after its actual choice.
Collection and continuations use that intact incumbent; continuation-fitted correction coefficients
estimate terminal differences from its chosen action, not differences from its uncalibrated head
score. Direct whole-game optimization may instead fit uncalibrated correction scores, evaluating
their repeated use across a complete game. Zero or absent correction retains the incumbent exactly.
The explicit learning mode binds continuation checkpoints; existing model assets and saved games
retain their original behavior.
Adaptive whole-game experiments can preserve the qualified purchase actor and its immutable prior
while changing ordinary decisions. This ownership is explicit in arena sources and checkpoint
identity: an evolved ordinary model does not replace the prior used by the purchase encoder.
Search selects training parents on development worlds; independent confirmation and held-out
opponents precede a separate browser integration and human evaluation.
See [campaign operation](contributing.md#unattended-automata-campaigns) for launch and recovery.

## Lifecycle and activity

`GameOperations.kt` handles administrative closure; `GameAbandonment.kt` unanimous player consent.
Proposing agrees; every seat (including Chaos/offline) must agree to the same request while play
continues. Decline, withdrawal, edits, policy upgrades, closure and completion expire obsolete consent.
Unanimity atomically abandons without scoring/private revelation, clears submissions, rotates receipts
and disarms plans. UUID receipts reconcile retries, including archived checkpoints.

Public statistics sample authoritative scores, advance counts, cash and misery at the start of each
turn and at completion during the existing effective-history replay. They require no new stored data
and exclude discarded rewind suffixes.

Activity preserves entry-time facts/identities as history grows; only explicit edits replace affected
entries. Separate direct-action and automatic-phase receipts, even when resource changes cancel.
