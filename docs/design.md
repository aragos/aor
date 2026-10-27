# Application Design

[Requirements](requirements.md) define product goals and acceptance. [engine.md](engine.md) defines the pure game engine; [rules.md](rules.md) is the game-rules reference.
This document covers application architecture, storage, player interaction and development scope.
The three-epoch human game, selected variants, visual table and scoped history editing are implemented.
Strategy controllers and private plans remain deferred contracts.

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
storage. Replaceable storage provides atomic updates for each game.

## 3. Game storage and configuration

A game consists of player identities, metadata (e.g. when the game was created), rules/data compatibility versions and a
versioned text log. Game option values are setup commands. `start` validates and freezes configuration. Once play
starts, setup cannot be edited under any undo scope.

The supported variants are the deck-1 Timber/deck-2 War exchange, one mutually exclusive initial
card restriction, and immediate Master Art discard. The European map is reserved until its board
specification is reviewed. Baseline advance and economic rules are fixed. Presets and earlier
optional mechanics are retired; [rules §13](rules.md#13-variants) and the [command reference](commands.md)
define the current choices.

The registration screen selects players and variants together. Creation writes every resolved
option in its initial revision, so changing defaults cannot change replay. Unknown or unavailable
choices receive typed diagnostics. Three epochs are fixed; `epochs` is not an option.

Snapshots, waiting-player lists and completion flags are derived caches, never sources of game truth. Pending secret
submissions, private plans and operational controller settings may be stored separately. A snapshot is tied to its
rules/data versions, revision and log position; changing the underlying log invalidates it.

### Secret submissions

Buffer simultaneous capital bids and token purchases durably outside the committed log until all
required players submit. Validate and append the complete batch atomically in stable player order.
Bids reveal together. Pending submissions have no game effect before acceptance.
Changing an unrevealed submission is a pending-submission operation, not a history edit.

The server generates and records random outcomes. Clients and strategies cannot access future shuffles, dice or
hidden state.

## 4. Revisions and undo

Keep immutable revision history: revision id, parent, author, edit command and resulting text-log changes. The engine
replays one effective revision. Requests name the expected revision; reject stale requests after an edit. Publishing
an accepted revision atomically replaces the active revision, invalidates affected caches and requires rebasing
pending submissions and previews. Earlier revisions retain the same information restrictions as live play.

Game file format 4 stores ordinary revision entries as deltas. An edit records `replaceFrom`, the
number of effective entries to retain, and a replacement suffix containing validated actions and
unchanged recorded outcomes. Reconstruct history by applying each delta or suffix replacement in
revision order. Earlier revisions are immutable. The creation revision records the undo policy;
explicit setup policy revisions carry no engine entries.

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
| Friendly game          | The creator may edit any part of the log, excluding setup, retaining revision history. Also implies friendly turn.                                                                                                |
| Friendly turn          | The active player may undo within their current phase activity, including nested decisions by others, provided recorded random events and outcomes are preserved.                      |
| Regular turn (default) | A player may undo their own entries in the current phase activity until another player acts or a random outcome is recorded. Undo cannot cross those boundaries or the activity start. |
| Competitive            | Every committed change is final; unsubmitted text and plans remain editable.                                                                                                           |

Every editable scope is subject to the randomness constraints below. Friendly scopes may cross a recorded random
outcome while editing surrounding decisions; they cannot remove or replace the event or its outcome.

Replay the retained prefix, then validate the edited suffix in order. Report invalid dependent entries and their
locations; do not silently skip them or retain their effects. Undo may select an earlier valid prefix only if it
preserves setup and all recorded random events and outcomes. Earlier edits
may require repairing or removing dependent entries before publication. Retained decisions by other players must
still be valid; removing them removes their effects and reopens required prompts. The editor cannot invent another
player's replacement answer. Secret batches are atomic undo boundaries; revalidate pending submissions after edits.

### Randomness after edits

Any edit must be compatible with already recorded random outcomes. If an edit adds a random event or removes the need
for one, it is rejected. This applies to undo, deletion, reordering and all other history edits, including shortening
the log. There is no fresh-outcome mode or randomness policy option for edits.

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

Deferred contract.

Plans are private saved drafts of ordinary command text, tied to a player, base revision and log position. They may
be incomplete and reach future turns. The engine provides partial projections and per-entry diagnostics
([engine §5](engine.md#5-planning)). Initially there is no sharing or automatic execution: a plan becoming actionable
never submits it.

On explicit submission of a plan or selected commands, rebase and validate entries in order. Commit only the
executable prefix belonging to that player. Stop at a missing choice, another player's prompt, a random request or
an invalid command, and report what was accepted. The service may resolve normal system prompts, but must not resume
the remaining plan automatically. Preserve the remainder as a draft. If the revision changes during submission,
stop and require rebasing before accepting more entries.

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
and never execute a private plan or substitute a strategy decision implicitly.
