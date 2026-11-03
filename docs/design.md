# Application Design

[Requirements](requirements.md) define product goals and acceptance. [engine.md](engine.md) defines the pure game engine; [rules.md](rules.md) is the game-rules reference.
This document covers application architecture, storage, player interaction and development scope.
The three-epoch human game, selected variants, visual table, scoped history editing and registered
account hosting are implemented. [Deployment](deployment.md) owns provider setup and operation.
Phase-scoped private plans support Purchase and Buy Card. Strategy controllers and plans spanning
future phases remain deferred.

## 1. Principles

- Store static game data declaratively and game-instance data in replay logs wherever possible.
- The UI displays state and generates commands. Humans and deterministic strategies use the same game interface.
- Keep the engine platform-independent. Run validation, replay and previews on the server initially; client replay
  can follow later.
- Configure game variants and undo policies per game through commands at the beginning of the log.
- Support incomplete plans beyond the current turn through best-effort projections.
- Internationalize all user-visible non-command text. Keep command syntax and data identifiers locale-independent.

## 2. Project layout

```text
core/     Kotlin Multiplatform engine and declarative game data (JVM and JS targets)
server/   Ktor application: persistence, authorization, revisions, controllers and plans
web/      React/TypeScript client: state display and command generation
docs/     design, engine contract and rules reference
```

The web client communicates with the server; it needs no Kotlin/JS build or client-specific Kotlin bridge. The core
JS target remains available for future client replay. Keep source packages flat until implemented features justify
more structure. Build and run commands are in [README.md](../README.md).

The server separates game progression and authority outcomes from authorization, private views and
storage. Replaceable storage provides atomic updates for each game. Local development can use
files; hosted deployment uses PostgreSQL for every durable application repository.

## 3. Game storage and configuration

A game consists of player identities, metadata (e.g. when the game was created), rules/data compatibility versions and a
versioned text log. Creation records the selected options, then automatically submits `start`, which
validates and freezes configuration and resolves the opening outcomes. Setup cannot be edited
under any undo scope once play starts.

The supported variants are the deck-1 Timber/deck-2 War exchange, one mutually exclusive initial
card restriction, immediate Master Art discard, and the European map. Map selection determines the
catalog and drawing throughout play and replay. Baseline advance and economic rules are fixed. Presets and earlier
optional mechanics are retired; [rules §13](rules.md#13-variants) and the [command reference](commands.md)
define the current choices.

The registration screen selects players and variants together. Creation writes every resolved
option in its initial revision, so changing defaults cannot change replay. Unknown or unavailable
choices receive typed diagnostics. Three epochs are fixed; `epochs` is not an option.

Snapshots, waiting-player lists and completion flags are derived caches, never sources of game truth. Pending secret
submissions, private plans and operational controller settings may be stored separately. A snapshot is tied to its
rules/data versions, revision and log position; changing the underlying log invalidates it.

### Persistence contracts

Both backends share current-format encoding and replay validation. SQL stores immutable revisions
separately from mutable metadata and private pending submissions. Accounts, unique handles, game
membership, creator permissions, exact upgrade archives and administrative receipts share the same
database. Engine-derived status/readiness fields are indexed listing projections, never inputs to
legality. The file backend remains single-process development storage.

SQL writers lock a game row and publish the full operation in one transaction. A storage generation
advances on every mutation, including pending replacements that do not advance the public revision.
Reads reconstruct a repeatable snapshot; bounded process caches check the generation first and never
cache uncommitted state. This permits independent application pools/instances without losing private
submissions. Purge and permission changes include their audit receipt in the same transaction.

Versioned Flyway migrations change the database schema, separately from explicit engine log
upgrades. A one-time, transactional importer copies a stopped current-format file directory into an
empty schema, preserving archive bytes and leaving the source unchanged. Provider configuration,
backups and cutover procedures live in [deployment](deployment.md).

### Secret submissions

Buffer simultaneous capital bids and token purchases durably outside the committed log until all
required players submit. Validate and append the complete batch atomically in stable player order.
Bids reveal together. Pending submissions have no game effect before acceptance.
Changing an unrevealed submission is a pending-submission operation, not a history edit.

The server generates and records random outcomes. Clients and strategies cannot access future shuffles, dice or
hidden state.

## 4. Revisions and undo

New games offer Competitive, Regular turn, Friendly turn and [Linear game](#linear-game-undo).
Friendly game remains readable in historical revisions and is upgraded on application startup.

Keep immutable revision history: revision id, parent, author, edit command and resulting text-log changes. The engine
replays one effective revision. Requests name the expected revision; reject stale requests after an edit. Publishing
an accepted revision atomically replaces the active revision, invalidates affected caches and requires rebasing
pending submissions and previews. Earlier revisions retain the same information restrictions as live play.

Game file format 7 stores the creator-chosen game name as intrinsic metadata, separate from engine
state and immutable entry-time player identities. Ordinary revision entries are deltas. An edit
records `replaceFrom`, the
number of effective entries to retain, and a replacement suffix containing validated actions and
unchanged recorded outcomes. Reconstruct history by applying each delta or suffix replacement in
revision order. Earlier revisions are immutable. The creation revision records the undo policy;
explicit setup policy revisions carry no engine entries.

An explicit administrative engine upgrade starts a new full checkpoint after archiving the preceding
file by revision/content hash. Its revision sequence retains the previous revision offset. The
checkpoint records the administrator and time; later writes resume delta/suffix semantics. Saved
membership, lifecycle and undo policy remain attached. Player history edits use their usual scopes;
administrative upgrades use a separate authenticated, validated API and never generate randomness.

Managed-auth UIDs identify accounts across games; engine seat IDs remain local to a game's header.
The server stores their membership mapping and a unique public handle directory. Hosted registration
stores no display name; live player labels use `@handle`, including games created before this change.
Recorded headers and activity retain their historical identities. Storage upgrade contracts belong
in [deployment](deployment.md).
Creator/admin roles are explicit. Registered games have no transferable private-seat tokens.
Cancellation and abandonment are audited operational revisions, suppress decisions and retain
read access; purge removes game history/archives and leaves a small receipt. SQL supports multiple
writers through database locks; file storage requires one writer per directory. Public APIs expose
only authorized views or explicit administrative exports.

Every committed transaction or edit advances `expectedRevision`; private bid replacements retain
it. The service rejects stale edits before replay or publication. Accepted edits invalidate decision
drafts and revalidate pending bids against the resulting phase and state. Historical revision views
include committed state only and use the same private audience restrictions.

The server accepts `undo`, `undo <entry-count>`, and setup-only `set-undo-policy <scope>` commands.
The suffix editor uses ordinary commands for the editor's own answers and `@<entry-index>` to retain
an original action, including another player's hidden answer without exposing its contents. It may
remove or retain another answer where the scope allows, but cannot invent its replacement. The
command history includes revision/author/edit audit lines. These presentation comments are separate
from the effective engine entries.

“During their turn” means the player's activity within one phase, including nested interruptions such as Cathedral.
It does not span several phases. The engine supplies activity ownership and boundaries. Completing an activity closes
its turn-scoped undo window; phase-start and simultaneous windows have their own boundaries.

| Scope                  | Permitted changes                                                                                                                                                                      |
|------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Linear game            | The creator may rewind any complete post-setup prefix; other players may rewind their currently open activity. All later play, including random outcomes, is removed together.                                                                                                |
| Friendly turn          | The active player may undo within their current phase activity, including nested decisions by others, provided recorded random events and outcomes are preserved.                      |
| Regular turn (default) | A player may undo their own entries in the current phase activity until another player acts or a random outcome is recorded. Undo cannot cross those boundaries or the activity start. |
| Competitive            | Every committed change is final; unsubmitted text and plans remain editable.                                                                                                           |

Suffix-editing scopes are subject to the randomness constraints below. Linear game uses the prefix-only exception described later. Friendly scopes may cross a recorded random
outcome while editing surrounding decisions; they cannot remove or replace the event or its outcome.

Replay the retained prefix, then validate the edited suffix in order. Report invalid dependent entries and their
locations; do not silently skip them or retain their effects. Undo may select an earlier valid prefix only if it
preserves setup and all recorded random events and outcomes. Earlier edits
may require repairing or removing dependent entries before publication. Retained decisions by other players must
still be valid; removing them removes their effects and reopens required prompts. The editor cannot invent another
player's replacement answer. Secret batches are atomic undo boundaries; revalidate pending submissions after edits.

### Randomness after edits

Suffix edits must be compatible with already recorded random outcomes. If an edit adds a random event or removes the need
for one, it is rejected. This applies to legacy-policy undo, deletion, reordering and suffix edits, including shortening
the log. Linear game has the exact-prefix exception below. There is no fresh-outcome mode or randomness policy option for edits.

Every recorded random event must still be required and accept its unchanged outcome. Match the same logical request,
not merely its position or the total number of events; an unrelated roll cannot substitute for a removed roll. Card
draws, random trades and shuffles must remain consistent with the revised deck and card ownership. Reject missing,
ambiguous or incompatible matches, including edits that create or eliminate a required draw or epoch shuffle.

For example, removing an attack that already rolled, or replacing it with a Cathedral win, is rejected. Changing a
purchase order may be accepted if it obeys the undo scope and preserves every recorded random event and outcome.
Validate the complete affected suffix before publishing. On rejection, report a structured diagnostic and leave the
active revision unchanged; do not pause for a replacement outcome, reroll or publish a partially valid revision.

Normal new actions in live play still request and record fresh outcomes. Planning may use explicit hypothetical
inputs but never consumes live randomness; ordinary replay always uses recorded outcomes. Historical revision views
retain private-information restrictions, and undo cannot erase knowledge a player has already acquired.

### Linear game undo

Offer **Linear game** (`linear-game`) for groups that want to take back play, including random
events. Replace Friendly game in the new-game chooser and upgrade existing Friendly games to this
policy through an explicit, audited migration. Regular turn remains the default; Competitive and
Friendly turn remain available unchanged. This is an application history policy, not a rules variant.
The acceptance and interaction contracts live in
[requirements](requirements.md#linear-game-undo-acceptance) and
[UI design](ui-design.md#linear-game-undo-interaction).

The central invariant is **the resulting effective log is an exact, shorter prefix of the current
effective log**. A rewind removes every later action and authority outcome together. It cannot
delete an action from the middle, reorder actions, retain a later answer, supply a replacement
answer or edit an outcome. Resume with ordinary commands from the restored position. There is one
active timeline, no redo command and no player operation that restores a discarded suffix from an
older revision. Prior revisions remain available for authorized inspection and audit.

#### Permission and boundaries

| Caller | Permission |
| --- | --- |
| Creator | Rewind any post-setup suffix, including other players' actions, earlier turns and the ending of a completed game. |
| Other player | Rewind within their currently open phase activity, including nested answers by others and random outcomes. Completing that activity closes this permission, as with Friendly turn today. |
| Spectator or nonmember | Read permitted history only; no rewind. Administrator access alone grants no player rewind permission. |

All players under Linear game use prefix-only undo; none receive the suffix editor. A nested
responder does not gain authority over the outer player's activity just by answering its prompt.
Permission is evaluated against the current live position, not against the target's former owner.
The creator may act while waiting or after engine completion. Cancellation and abandonment still
block all player mutations; rewind cannot change operational lifecycle, membership or creator role.
No voting or approval round is proposed: this policy grants the creator this authority, including
in upgraded Friendly games.

Setup remains protected through completed capital selection and entry into turn 1, matching the
existing service boundary. Opening configuration, shuffle, deal, capital bids, tie rolls and
capital choices cannot be rewound. The earliest target is the completed setup prefix. A finished
game with an active operational lifecycle can return to play; refresh its listing, readiness and
scores from the restored engine state. Rewinding cannot resurrect a cancelled or abandoned game.

An undo unit is one player action together with all immediately required authority outcomes and
automatic transitions, ending when the engine next waits for players or finishes. A simultaneous
reveal is one unit containing every participant's action and all resulting outcomes. Private saves,
plan authorization, policy/audit revisions and uncommitted browser drafts are not undo units.

Offer only retained entry counts that end complete units in the effective history and are within
the caller's scope. Derive these boundaries from replay and complete-batch constraints; do not
assume every raw entry, displayed activity row or revision is a unit. Some displayed rows group
several commands; earlier suffix edits or administrative checkpoints can also group many units
inside one revision. Never offer a boundary inside a batch or before pending authority outcomes
have resolved. Do not silently round an invalid target to a different position.

`undo` removes the most recent unit if the caller may remove it. `undo N` retains exactly `N`
effective engine entries, where `N` is a zero-based prefix length bound to `expectedRevision`.
Repeated undo walks backward through gameplay units, skipping audit-only revisions; it does not
undo an undo revision and thereby restore discarded play. Selecting the current entry count is a
no-op rejection. Selecting an older revision is not a substitute for selecting a current prefix.

#### Randomness and resumed play

Rewind itself only replays the retained prefix; it never calls the randomness provider, resolves
new system outcomes or changes retained entries. Unlike the existing policies, it may discard
random events because every subsequent dependency is also discarded. This exception applies only
to exact-prefix truncation under Linear game. Existing suffix editing and administrative engine
upgrades must continue to prove continuity of recorded random events.

**Continuation behavior:** new live requests use the ordinary authority provider.
There is no queue of removed outcomes and no RNG-seed rewind. A repeated roll or random selection
gets a fresh sample, which can coincidentally equal its earlier result. A draw still takes the top
of the restored deck; undoing a draw alone does not reshuffle it or promise a different card. An
epoch shuffle gets a new permutation only if that shuffle was removed and becomes necessary again.
Retained shuffles and all other retained outcomes remain byte-for-byte unchanged. This behavior applies to new Linear games and upgraded Friendly games.

| Sequence removed | Restored position and continuation |
| --- | --- |
| `buy-expansion-card` plus its draw | Refund tokens and restore deck, hand and purchase allowance through replay. Buying again draws the same top card unless a removed epoch shuffle is required. |
| An attack plus later Cathedral answers and dice | Restore the position before commitment. An ordinary new attack can use a different target and receives fresh dice when needed. |
| A Cathedral decline that immediately triggered dice | Restore the Cathedral prompt, retaining the preceding attack. Declining again makes a fresh roll; an eligible declaration instead follows the normal automatic-win rule. |
| An action that drew from an empty deck, including its shuffle | Restore the pre-action deck/epoch state. Repeating the action generates a new eligible shuffle and records the resulting draw. |
| An offered-card action plus its random transfer | Restore both hands and the offer prompt. A new offer requests a fresh selection from the then-eligible hand. |
| A revealed token-purchase batch | Restore the unrevealed round with no saved submissions; every participant must submit again. Previously revealed amounts remain known. |

This permits deliberate rerolls and reconsideration with information already learned. The creation
description and Help must say so. Private historical views remain audience-filtered, but the server
cannot make players forget a revealed card, bid or result. Do not describe Linear game as preserving
competitive fairness or hiding previously learned information. It grants no new access to hidden
hands, shuffle orders or another player's private history.

#### Publication, private state and audit

Reuse the existing immutable delta/suffix model. Append a revision with the current revision as
parent, the requesting player as author, canonical command `undo N`, `replaceFrom = N`, and an
empty replacement suffix. The retained entries keep their existing identities. Revision numbers
and storage generations increase even though effective entry count falls. Subsequent ordinary
actions append to that prefix; removed entry identities are never reused for new entries.

Authorize, check `expectedRevision`, validate the exact boundary, replay the prefix and publish all
effects under the same game lock/transaction. Rejection or storage failure leaves history and all
private state unchanged. A lost response is reconciled by reading the current revision and audit;
clients must not automatically retry against a newer revision, which could rewind another unit.
Concurrent rewinds or gameplay writes serialize; an operation based on the losing revision is stale.

On an accepted rewind:

- Clear all pending capital/token submissions, even when the target has the same phase and turn.
  Advance every affected owner's submission receipt, including empty slots, so an old private write
  cannot reappear. Retain bounded operation acknowledgements as receipts, never as restored amounts.
  Historical revisions do not contain pending values and must not recreate them.
- Disarm every automatic plan and rotate plan versions in the same transaction. Retain saved
  actions only as private, unarmed drafts for fresh validation/review; expire out-of-scope plans.
  Never restore former authorizations, consumed plan progress or execution permission from history.
  Existing execution receipts remain factual records of what previously ran, even if later undone.
  The post-command plan worker must find no armed work to execute immediately after a rewind.
- Invalidate affected snapshots, quotes, previews and decision scopes using the new edit revision.
  Browsers clear stale action drafts and outcomes when they receive the replacement position, even
  if its prompt text, phase and actor match the old position. Ordinary polling without an edit
  continues to preserve valid work.
- Preserve game ID/name, creation time, options, policy, metadata, memberships, credentials, roles,
  operational lifecycle and exact upgrade archives. They are outside the replayed gameplay prefix.

Show one public audit notice naming the rewind author and public destination, with a reference to
the prior revision for permitted inspection. Keep removed events out of the current activity list;
do not rewrite retained entry-time identities or receipts. Derive destination labels and removal
summaries from the viewer's projection. Never reveal hidden action text, card identities, hand
references, shuffle orders or counts that disclose a private choice. The creator's broader rewind
authority does not broaden their history-reading audience.

#### Service and implementation boundary

Keep `undo` and `undo N` on the existing authenticated command endpoint. Add an explicit history
capability to `UndoView` distinguishing prefix rewind, legacy suffix editing and no permission;
policy labels or `fromEntry` alone must not decide which controls to render. Return the latest
legal target for the primary Undo control. Provide bounded, paginated, read-only target offers
with `expectedRevision`, retained entry count, a viewer-safe destination/removed-range summary and
whether random events are removed. Fetch target offers using the ordinary game authorization and
reject stale revisions. Offers expose legal targets, not arbitrary historical state exports; the
command must revalidate the selected target under the write lock.

For Linear game, reject the history replacement endpoint even for an empty replacement. There
must be one canonical write operation for the same rewind. Preserve that endpoint's existing
behavior for other policies and Friendly games awaiting upgrade. Use typed diagnostics for
forbidden scope, protected or incomplete
boundary, no change, stale revision, inactive game and unsupported suffix editing; never use
`HISTORY_RANDOMNESS_CHANGED` merely because a permitted rewind removes randomness.

Share exact-prefix validation between live commands and stored-revision validation. The current
`GameDocuments` validator reconstructs every edit through outcome-preserving
`HistoryEdits.replacement`; merely relaxing the live undo path would produce saves that fail on
reload. Dispatch by the recorded policy and canonical operation, requiring an empty suffix for
Linear game. Validate parent chain, scope, protected setup, complete units and retained replay
without generating outcomes. Validate each historical operation against the policy in effect at
its parent revision, retaining the stricter validation for earlier Friendly game edits. Reject
documents that use the new policy to smuggle in arbitrary replacements or retroactively legalize
an invalid old edit.

#### Existing games and rollout

Existing games whose current setting is Friendly game receive Linear game functionality through
an automatic deployment migration, without requiring recreation, a creator opt-in or a new game.
Include ongoing, engine-finished, cancelled and abandoned games; preserve their lifecycle and only
permit actual rewind where the normal active-game checks allow it. Competitive, Regular turn and
Friendly turn games keep their selected policy. Imported or restored Friendly saves use the same
upgrade path before returning to ordinary play under the new behavior.

Do not alias `FRIENDLY_GAME` to `LINEAR_GAME` in the decoder or rewrite the creation revision.
Preserve the complete original revision chain and append a dedicated administrative policy-upgrade
revision with no engine entries or suffix replacement. Record the previous and new policy,
administrator, time, source revision/content hash and exact source-document archive receipt. This
is a narrowly authorized upgrade operation, not a relaxation of setup-only `set-undo-policy`.
The resulting effective policy is Linear game; revisions before the upgrade retain Friendly game
semantics for validation and historical inspection. Never revalidate earlier arbitrary suffix
edits as if they had been prefix-only rewinds.

The policy upgrade is not itself a gameplay undo unit. Rewinds may reach any legal post-setup
prefix, including positions before the upgrade and within a formerly edited suffix. Such a rewind
does not remove the upgrade revision or restore Friendly game permissions. Derive target boundaries
from the current effective entries, not from discarded branches in older revisions or archives.

Preflight the original history with its original policy rules and validate the candidate with
revision-time policy dispatch. Replay must prove identical effective entries, entry identities,
recorded outcomes, public/private engine state and prompts before and after upgrade. Preserve game
identity/name, options, metadata, creation time, memberships, credentials, roles, lifecycle, pending
private submissions, owner receipts, saved plans and execution receipts, plus existing exact
archives. The migration must not clear pending submissions, discard drafts or restore consumed
plan progress. Preserve valid automatic authorizations subject to their ordinary exact-consequence
checks; the migration itself never executes plans or generates randomness. Actual later rewinds
apply the clearing and disarming rules above.

Archive and publish each upgrade atomically under the game lock with an expected revision and
storage generation, including private writes that do not advance the public revision. If a player
or worker changes the game first, retry preflight against the new source rather than overwrite it.
If upgrade wins, stale commands and suffix-editor submissions reject and refresh to the new
capabilities. Advance the public revision and storage generation; invalidate history offers,
editor drafts and preview tokens without changing the current gameplay decision. Failure leaves
the game, private state and archives unchanged. Retrying an already upgraded game is a no-op and
does not append another upgrade receipt. Report per-game success or a typed failure; never skip a
failed game while claiming the migration complete.

This application policy upgrade needs no engine/rules version change. If its audit/archive fields
require a new file format, implement a supported format-7 upgrade that preserves the original
chain and private state rather than replacing them with a gameplay checkpoint. Keep a decoder and
validator for source saves until they can be upgraded; a version bump or export-only fallback is
insufficient. Existing engine upgrades still need their separate validated continuity mapping.

Deploy readers/validators that understand both policies and the upgrade receipt to all serving
instances before enabling migration or new Linear game creation. Run preflight and the resumable
migration, verify saved-state continuity and continued play, then retire Friendly game from new
creation. Separate creation-choice validation from parsing old saved revisions. Reflect the
replacement in remembered creation preferences and explain it beside the renamed choice; keep
Regular turn as the default. Historical views show Friendly game before the upgrade, while the
live game shows Linear game with a compact conversion notice. Rollback after policy upgrades or
new-policy games exist requires compatible code; restoring a source archive would lose subsequent
play and is not a routine rollback.

The implementation shares prefix validation between live and stored operations, publishes under
the game lock, upgrades existing histories with exact archives, and offers reviewed targets in the UI.
The implementation acceptance matrix belongs in
[requirements](requirements.md#linear-game-undo-acceptance).

## 5. Human and strategy controllers

Human seats are implemented. Strategy controllers below are a deferred contract.

Each player has a stable identity and an initially fixed human or strategy controller. Both receive the same
per-player projection, addressed prompts and legal choices, and submit through the same validation and secret-batch
protocol. Strategies cannot inspect authoritative state, other players' secrets or the randomness provider.

Strategies are deterministic: the same version, recorded configuration and visible decision context produce the same
command. Canonically order inputs and tie-breaking. Any memory must be reproducible from permitted decision history;
exclude wall-clock time, process-local state, unrecorded randomness and external model responses. Game randomness is
an authority input, separate from strategy execution.

The service dispatches prompts and records accepted commands; the engine never invokes controllers or waits for
input. Replay consumes commands without rerunning a strategy. Exceptions, invalid commands and timeouts are bugs:
leave the decision pending and report the strategy version, revision and prompt, using only information it was allowed
to see. Do not silently pass or substitute a fallback. Fixing and retrying the adapter is a service operation.

Record strategy versions and decision-affecting configuration for reproduction; keep credentials and operational
settings in service configuration. Controller replacement during a game is deferred. If added, it needs logged
assignment changes and explicit treatment of pending submissions.

## 6. Private plans

Private plans contain canonical commands bound to their intended decisions, for the owner's
remaining activity in the current Purchase or Buy Card phase. Saving has no game effect. Explicit
automatic authorization binds the saved version to the exact previewed consequences; review plans
wait for explicit confirmation on the legal turn. Future-turn scripts and strategy controllers
remain deferred.

Core projects the owner activity using shared rules, opaque deck placeholders and public counts for
other hands. Hypothetical previews assume earlier players do not change relevant inputs. Each
segment stops at the activity boundary, a missing decision or an authority outcome. Validate the
whole deterministic segment before committing any of it; planned deterministic interruptions can
continue only with an explicit answer. Draws resolve normally, then disarm the remaining plan.

The service stores plans, owner versions, bounded operation receipts and execution revision ranges
outside replay history. Private writes check public revision and owner version atomically. Arming
also checks the current preview token. Same-seat stale writes conflict; other seats' private saves
do not. Cancellation and execution serialize on the game lock. Editing an armed plan requires a
confirmed disarm; committed actions use the game's existing undo policy.
Disarm and deletion tolerate intervening public appends when the exact owner receipt remains
unchanged, so cancellation can win the lock before execution even as an earlier player finishes.
Future revisions, changed owner receipts, edits and consumed authorizations still conflict.

After writes, the service executes actionable authorizations in bounded, per-actor transactions.
The armed record is durable work: an application worker scans for actionable plans after startup
and every five seconds. Reads never execute. Each transaction appends ordinary owner-authored
command revisions and consumed plan progress together. Failed writes leave both unchanged; replay
never invokes plans. Consequence changes and invalid segments disarm for review without silently
substituting commands. Draws stop even a planned suffix that would be legal afterward.

Only owners receive plan state, operation receipts, previews and execution provenance. Public
readiness derives from real prompts, and public ETags exclude others' private writes. History edits
disarm plans; manual actions cancel that owner's authorization. Turn/phase completion expires
remaining work. Inactive games cannot execute.

The normal decoder accepts only format 7. The completed format-6-to-7 conversion is retired;
there is no conversion endpoint or game-screen activation flow. Previously upgraded games retain
their zero-entry `upgrade-planning` audit revisions and exact source archives. These historical
revisions remain part of the original revision chain and do not alter engine replay or entry IDs.
New administrative checkpoints use the ordinary archived full-checkpoint contract.

## 7. UI and localization

[UX principles](ui-design.md) own interaction requirements; [map contracts](map-design.md) own
spatial rendering. The [UX review](ux-next-steps.md) proposes subsequent work.

The client renders viewer-safe projections, prompts, legal choices and typed explanations, and sends
the same canonical commands as the text interface. Calculations stay in the core and authorization
stays in the service. Read-only quotes are authenticated and bound to the committed revision.

Decision drafts and object focus follow `activity.decisionEntry`, together with game and viewer
identity. Polling revisions and private readiness updates retain work; a new or replaced decision
clears old form state, inspection and target pickers. Target count and combined limits come from
authoritative offers. Rejections retain correctable selections, and stale revisions refresh without
retrying the command. A resumed outer activity opens a fresh presentation decision.

Visual activity is derived from typed committed entries during history replay, with stable
`revision:entryIndex` identifiers. Its card identities follow the same viewer audiences as the
command log; unrevealed private submissions and private hand references are never exposed. The client
does not parse log prose to reconstruct events or rules.

Use stable message identifiers, typed arguments and entity ids for engine, revision, planning and controller errors.
Static definitions provide localization keys; translation resources belong to the presentation layer. Neither UI nor
strategies infer legality by parsing English messages. Command text and replay results are identical across locales.

## 8. Verification

[Engine verification](engine.md#8-verification), [rule coverage](rule-coverage.md) and
[visual decision coverage](ui-decision-coverage.md) record the relevant contracts and evidence.
Keep domain arithmetic and progression tests separate from service authorization, revision,
secret-batch atomicity and restart checks. Browser checks verify full user interactions, including
rejection and stale requests. UI fixture checks cannot establish secrecy or persistence.

When controller or planning work begins, verify its contracts above before calling
it supported. Preserve recorded outcomes and private audiences, reject invalid edits atomically,
and execute a private plan only under its owner's explicit authorization. Never substitute a
strategy decision implicitly.
