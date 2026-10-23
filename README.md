# Age of Renaissance

A Kotlin Multiplatform engine, Ktor server and React web client for
[Age of Renaissance](https://boardgamegeek.com/boardgame/26/age-of-renaissance), inspired by
[Snellman's command-log approach](https://www.snellman.net/blog/archive/2014-12-08-command-languages-as-game-ui/).
The application implements standard setup for 3–6 human players: private cards, sealed capital bids,
recorded tie rolls and capital selection. It stops before the initial Order of Play.

- [Application design](docs/design.md): architecture, storage, undo, controllers and plans.
- [Engine](docs/engine.md): replay contract, state, flow, data and verification.
- [Rules](docs/rules.md): game mechanics, variants, sources and unresolved questions.
- [Manual setup walkthrough](docs/manual-setup.md): run, test, restart and API instructions.

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

Games and unrevealed bids are saved in `.aor-data/` at the repository root when using `:server:run`.
Set `AOR_DATA_DIR` to an absolute directory to choose another location. Use one server process per data directory.
Keep the player links: only their credential hashes are saved, so the server cannot recover lost links.
The local server listens on `127.0.0.1`; account authentication, general undo, variants and turn play are deferred.

Build the web client with `npm run build`; output is in `web/dist/`. It does not require a Kotlin/JS distribution.
Run server tests with `./gradlew :server:test` and core JVM tests with `./gradlew :core:jvmTest`. Use
`./gradlew :core:compileKotlinJs` to check the core JS target.

Kotlin code follows the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide).
Run `./gradlew formatKotlin` after edits and `./gradlew checkKotlinFormat` to verify formatting without
changing files. Both tasks use pinned ktfmt 0.64 in Kotlin language style and include Gradle Kotlin
scripts. Repository coding and review conventions are recorded in [AGENTS.md](AGENTS.md).
