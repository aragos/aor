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

- [Product requirements](docs/requirements.md): goals, acceptance contracts and open decisions.
- [Online deployment](docs/deployment.md): PostgreSQL, hosting comparison, Render/Firebase setup and admin tools.
- [Follow-up decisions](docs/follow-ups.md): launch prerequisites and remaining map/maintenance review.
- [Application design](docs/design.md): architecture, storage, undo, controllers and plans.
- [Engine](docs/engine.md): replay contract, state, flow, data and verification.
- [Command language](docs/commands.md): complete syntax, parameters and recorded outcomes.
- [Rules](docs/rules.md): game mechanics, variants, sources and unresolved questions.
- [Setup walkthrough](docs/manual-setup.md): creation, private decisions, restart and API instructions.
- [Playing a full game](docs/manual-full-game.md): phase actions and interruption commands.
- [Board and economic inventory](docs/data-inventory.md): catalog transcription, sources and review status.
- [Map design](docs/map-design.md): the agreed visual language, board navigation, and map-based orders.
- [European map authoring](docs/european-map.md): edit a declarative copy of the standard map and validate its differences.
- [UX and UI principles](docs/ui-design.md): goals and common interaction requirements.
- [UX review and next steps](docs/ux-next-steps.md): project findings, priorities and acceptance criteria.
- [Rule coverage](docs/rule-coverage.md), [card inventory](docs/card-inventory.md) and
  [variant inventory](docs/variant-inventory.md): implementation evidence and selected source interpretations.

```text
core/     platform-independent Kotlin engine and game data (JVM/JS)
server/   Ktor application, depending on core
web/      React/TypeScript client, built independently with Vite
```

Use JDK 21 and Node 24.12 or newer. Install web dependencies once from the repository
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
The local server listens on `127.0.0.1`. Hosted mode uses verified Firebase accounts, registered
player handles, membership-based game access and an administrative game dashboard. See
[deployment](docs/deployment.md) for configuration. Hosted deployment uses managed PostgreSQL for
games, private submissions, accounts, permissions, archives and audit receipts; no application disk
is needed. Set `AOR_STORAGE=postgres` and secret `AOR_DATABASE_URL` to use SQL locally or on another
provider. TLS defaults to `verify-full`; loopback development may use `?sslmode=disable`.
Private plans and bots remain deferred.

Choose variants on the player-registration screen: swap Timber and War between decks 1 and 2,
restrict the opening deal to no Crusades, no events or commodities only, and optionally enable an
immediate Master Art discard. Opening restrictions are mutually exclusive; excluded cards remain
in the deck for later draws. The European map is reserved pending its province and adjacency
specification. All other previous optional mechanics and presets are retired. Games have three epochs.

Undo defaults to the regular-turn policy: own actions in the current phase activity, until another
player acts or an outcome is recorded. Competitive and friendly scopes are available at creation.
Use **Undo last action** in the decision panel or the suffix editor under **Commands**. History edits
are audited, preserve existing randomness, and invalidate stale drafts. See [the design](docs/design.md#4-revisions-and-undo)
for scope limits and [commands](docs/commands.md#application-history-commands) for the API.

New games use file format 5 with rules version `ah1996-three-epoch-2` and data version
`ah1996-catalog-10`. The initial revision records resolved setup options. Earlier development rules
and catalog versions are unsupported for ordinary play. Administrators can explicitly validate and
publish upgraded logs with an archived preceding file; there is no automatic engine migration.
The current catalog includes the reviewed Baltic land/coastal connections, sea exclusions and
support arrows.
Belgrade has Adriatic coastal access between Dubrovnik and Durazzo, which no longer border each other.
Durazzo retains its coastal-water neighbors but has no direct sea-space contacts.
Smyrna adjoins both the Aegean Sea and Eastern Mediterranean.
The Aegean and Central Mediterranean are separate seas, with Crete adjoining both and the Eastern
Mediterranean.

Build the web client with `npm run build`; output is in `web/dist/`. It does not require a Kotlin/JS distribution.
From `web`, run `npm test` for focused checks and `npm run test:browser` for the isolated browser suite
(Node 24.12 or newer and Chrome). The runner manages its own server, Vite, Chrome profile, and temporary
game files; see [browser verification](docs/map-design.md#data-and-verification) and the
[visual decision coverage](docs/ui-decision-coverage.md).
Run server tests with `./gradlew :server:test` and core JVM tests with `./gradlew :core:jvmTest`. Use
`./gradlew :core:compileKotlinJs` to check the core JS target. SQL contract tests run against actual
PostgreSQL 18: an isolated native test cluster by default (Linux/macOS), or the disposable database
supplied by `AOR_TEST_DATABASE_URL`. Each fixture creates and drops its own schema. CI uses a
PostgreSQL service container. Run the same browser interactions against SQL with
`AOR_BROWSER_SERVER="$PWD/web/scripts/postgres-browser-server.sh" npm run test:browser --workspace web`
from the repository root.

Kotlin code follows the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide).
Run `./gradlew formatKotlin` after edits and `./gradlew checkKotlinFormat` to verify formatting without
changing files. Both tasks use pinned ktfmt 0.64 in Kotlin language style and include Gradle Kotlin
scripts. Repository coding and review conventions are recorded in [AGENTS.md](AGENTS.md).
