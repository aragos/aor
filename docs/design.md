# Application Design

[engine.md](engine.md) defines the pure game engine; [rules.md](rules.md) is the game-rules reference.
This document covers application architecture, storage, player interaction and development scope.

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

Use individual functional options, starting with a small supported set. Defaults and supported keys, values and
combinations are versioned with the rules. Record resolved choices so defaults cannot drift on replay. Reject
unsupported or conflicting choices with structured diagnostics; resolve new interactions as options are added.
AH1996 is the baseline. Edition names may be UI presets only when all their expanded choices are supported; logs
record those choices rather than relying on the preset name.

The following is a feature catalog, not a promise to implement every option initially. Variant mechanics live in
[rules §13](rules.md#13-variants).

| Concern                   | Choices / baseline                                                                                       |
|---------------------------|----------------------------------------------------------------------------------------------------------|
| Players and epochs        | 3–6 registered players; fixed three-epoch sequence, with no length option.                                                          |
| Setup                     | Deal/keep counts, deck adjustments, private returns and delayed reinsertion; standard setup by default.  |
| Commerce advances         | Ordering, prices and prerequisites of Improved Agriculture and Wind/Watermill.                           |
| Wind/Watermill            | Takeover card trade, or last-player shortage/surplus adjustment.                                         |
| Written Record            | Leader-discount bonus, or takeover card trade.                                                           |
| Income misery improvement | New World, or Enlightenment.                                                                             |
| Advance credits           | Individual category credit schedules.                                                                    |
| Misery relief             | Purchased tier/MR-based relief, or completion-based relief.                                              |
| Patronage                 | Protection money, or three-box leader display.                                                           |
| Master Art                | Buy Card phase discard; optional immediate discard on purchase.                                          |
| Map support               | Printed arrows; optional St. Gall → Nuremberg support.                                                   |
| Initial card restriction  | Any history card, or commodity-only first card.                                                          |
| Event-rebuilt marker bonus | Exclude (default), or include event-rebuilt markers in the expansion bonus; both are supported.          |
| Undo                      | Four scopes below; regular-turn undo by default. All edits preserve recorded random events and outcomes. |
| Resolved rulings          | Q7 recycling verified against the supplied card reference; Q14 reinsertion timing is an approved project rule. |

The [remaining rules plan](remaining-rules-plan.md) organizes the completed implementation and its
verification. Three epochs are fixed; `epochs` is not a command, creation field or selector.
The functional variant rows are configurable through the [command reference](commands.md).
Application features such as undo, controllers and private plans remain separate.

The first choice in each variant row is the baseline; declarative data and behavior handlers must both match the
chosen options. A different advance table alone does not implement an edition.

The supported `rebuilt-marker-bonus` setup command records `exclude` or `include` explicitly.
Its default is recorded alongside every resolved functional option in the initial revision; changing the option uses an ordinary
creator command before `start`. Both choices are public and survive replay and restart. The domain
policy implements bonus eligibility for ordinary expansion, event rebuilds and voluntary rebuilds;
board placements and the actual expansion reward use this policy.

Snapshots, waiting-player lists and completion flags are derived caches, never sources of game truth. Pending secret
submissions, private plans and operational controller settings may be stored separately. A snapshot is tied to its
rules/data versions, revision and log position; changing the underlying log invalidates it.

### Secret submissions

Buffer simultaneous capital bids, token purchases and improved-setup card returns durably outside the committed log
until all required players submit. Validate and append the complete batch atomically in stable player order. Bids
reveal together; returned-card identities remain private. Pending submissions have no game effect before acceptance.
Changing an unrevealed submission is a pending-submission operation, not a history edit.

The server generates and records random outcomes. Clients and strategies cannot access future shuffles, dice or
hidden state.

## 4. Revisions and undo

Keep immutable revision history: revision id, parent, author, edit command and resulting text-log changes. The engine
replays one effective revision. Requests name the expected revision; reject stale requests after an edit. Publishing
an accepted revision atomically replaces the active revision, invalidates affected caches and requires rebasing
pending submissions and previews. Earlier revisions retain the same information restrictions as live play.

The current implementation stores each revision's new log entries as a delta. Reconstructing
the active game therefore replays the accumulated deltas; the latest revision alone is not a complete
log. Each committed log transaction advances the concurrency revision used by `expectedRevision`;
unrevealed pending-bid replacements leave it unchanged. This detects stale committed-state submissions
without a separate log-position token. Keeping one revision id until a history edit would reduce
ordinary revision records, but would require another
monotonic version for command and pending-submission concurrency. General history editing and branch
selection remain future work.

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

The [UI guiding rules](ui-design.md) define visual interaction, contextual action defaults, compact
summaries, and progressive disclosure for the UI revision. The
[UI implementation plan](ui-implementation-plan.md) sequences the application changes and their
verification, beginning with contextual expansion.

The client renders server projections, prompts, legal choices and structured explanations, and builds the same
commands accepted as text. It owns translations, number formatting and optional auto-pass preferences. Auto-pass
submits an ordinary authorized command and follows the same validation as a manual pass.

Use stable message identifiers, typed arguments and entity ids for engine, revision, planning and controller errors.
Static definitions provide localization keys; translation resources belong to the presentation layer. Neither UI nor
strategies infer legality by parsing English messages. Command text and replay results are identical across locales.

## 8. Delivery and verification

Build in this order: declarative data and validation; text codec and setup loop; revisions and recorded randomness;
economy; expansion; card effects; Final Card Play and scoring; controllers and private plans. Establish localization
keys and typed explanation data at the interfaces. Transcribe the physical map carefully; do not copy unlicensed data.
Keep automatic plan execution and in-game controller replacement outside the initial scope.

Engine checks are in [engine §8](engine.md#8-verification). Service and UI checks cover:

- All four undo scopes, defaults, phase boundaries, nested interruptions, invalid suffixes, stale requests, atomic
  secret batches and revision redaction. Reject setup edits and edits that add/remove random events, substitute
  unrelated outcomes, invalidate recorded results or truncate away an event. Accept compatible edits within the
  configured scope. Rejection must leave the active revision unchanged, without generating replacement outcomes.
- Private plans, explicit submission, stopping at interruptions, retained drafts and rebasing; no automatic execution.
- Equal information and validation for humans and strategies, deterministic commands, and pending decisions on bugs.
- Supported initial options and rejection of unavailable keys, values and combinations; expand checks as options grow.
- Resolution of all presentation keys and typed parameters, with locale-independent commands and replay.
