# Age of Renaissance

A Kotlin Multiplatform engine, Ktor server and React web client for
[Age of Renaissance](https://boardgamegeek.com/boardgame/26/age-of-renaissance), inspired by
[Snellman's command-log approach](https://www.snellman.net/blog/archive/2014-12-08-command-languages-as-game-ui/).
The application runs fixed three-epoch games for 3–6 human players: setup, repeatable turns, purchases,
board expansion and competitions, all 64 history cards, nested player decisions, income and final scoring.
Private hands and simultaneous bids remain private; authoritative outcomes are recorded for replay and restart.
When War or Crusades needs a marker with all 25 deployed, its recipient may forgo placement or move
a marker from another controlled province. Commodity payments continue their quadratic formula
beyond the printed boxes. Both project rulings are implemented and replayable.

- [Application design](docs/design.md): architecture, storage, undo, controllers and plans.
- [Engine](docs/engine.md): replay contract, state, flow, data and verification.
- [Command language](docs/commands.md): complete syntax, parameters and recorded outcomes.
- [Rules](docs/rules.md): game mechanics, variants, sources and unresolved questions.
- [Setup walkthrough](docs/manual-setup.md): creation, private decisions, restart and API instructions.
- [Playing a full game](docs/manual-full-game.md): phase actions and interruption commands.
- [Minimum full game plan](docs/minimum-full-game-plan.md): agreed scope, delivery checkpoints and verification.
- [Remaining rules plan](docs/remaining-rules-plan.md): fixed three-epoch delivery, remaining variants,
  parallel agent assignments, dependencies and verification gates.
- [Board and economic inventory](docs/data-inventory.md): catalog transcription, sources and review status.
- [Map design](docs/map-design.md): the agreed visual language, board navigation, and map-based orders.
- [UI guiding rules](docs/ui-design.md): visual interaction, compact summaries, action defaults,
  and [interactive redesign proposals](docs/ui-proposals/index.html).
- [UI implementation plan](docs/ui-implementation-plan.md): delivery order, domain projections,
  and acceptance criteria for the visual redesign.
- [Rule coverage](docs/rule-coverage.md), [card inventory](docs/card-inventory.md) and
  [variant inventory](docs/variant-inventory.md): implementation evidence and selected source interpretations.

```text
core/     platform-independent Kotlin engine and game data (JVM/JS)
server/   Ktor application, depending on core
web/      React/TypeScript client, built independently with Vite
```

Use JDK 21 and a Node version supported by Vite 7 (20.19+ or 22.12+). Install web dependencies once from the repository
root with `npm install`.

Run the server with `./gradlew :server:run`, and the web client in another terminal with `npm start`. The server uses
port 8080 and the web client uses port 5173. The IDE's **web** run configuration starts the same npm command.
Open <http://localhost:5173>, create a table and save the private player links. Forms and the single-command input
use the same engine validation. Player tabs refresh automatically.
Text input accepts assigned capital prefixes and unambiguous card shorthand, for example
`venice: play wine/cloth cloth`. Saved commands retain canonical player ids and hand references;
see the [command language](docs/commands.md) for all input forms.

Games and unrevealed bids are saved in `.aor-data/` at the repository root when using `:server:run`.
Set `AOR_DATA_DIR` to an absolute directory to choose another location, or `AOR_HTTP_PORT` to change the server port. Use one server process per data directory.
Keep the player links: only their credential hashes are saved, so the server cannot recover lost links.
The local server listens on `127.0.0.1`. Accounts, general undo, private plans and bots remain deferred.

During configuration, the creator can select standard or improved setup, individual functional
rules, or a preset. The second-edition preset selects the alternate advance table, credits, relief,
leader display and interaction rules. Tournament options include commodity-only initial cards,
immediate Master Art discard and St. Gall support. Commands and guided controls use the same rules.
Game length is fixed at three epochs; there is no epoch configuration field or command. The existing
26th-advance ending can still finish a game earlier.

The creator can also choose whether markers rebuilt after events count toward the
expansion bonus: `set-option rebuilt-marker-bonus exclude` (default) or
`set-option rebuilt-marker-bonus include`. The browser has the same selector. The choice is public,
saved in the log and frozen when setup starts. Both settings apply to actual expansion rewards. Voluntary marker rebuilds never count.

New games use file format 3 with rules version `ah1996-three-epoch-1` and data version
`ah1996-catalog-2`. The initial revision records resolved setup options. Earlier development rules
versions are unsupported; no migration is provided under the current-format-only policy.

Build the web client with `npm run build`; output is in `web/dist/`. It does not require a Kotlin/JS distribution.
From `web`, run `npm test` for focused checks and `npm run test:browser` for the isolated browser suite
(Node 22 or newer and Chrome). The runner manages its own server, Vite, Chrome profile, and temporary
game files; see [browser verification](docs/map-design.md#data-and-verification) and the
[visual decision coverage](docs/ui-decision-coverage.md).
Run server tests with `./gradlew :server:test` and core JVM tests with `./gradlew :core:jvmTest`. Use
`./gradlew :core:compileKotlinJs` to check the core JS target.

Kotlin code follows the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide).
Run `./gradlew formatKotlin` after edits and `./gradlew checkKotlinFormat` to verify formatting without
changing files. Both tasks use pinned ktfmt 0.64 in Kotlin language style and include Gradle Kotlin
scripts. Repository coding and review conventions are recorded in [AGENTS.md](AGENTS.md).
