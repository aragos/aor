# Age of Renaissance

A Kotlin Multiplatform engine, Ktor server and React web client for
[Age of Renaissance](https://boardgamegeek.com/boardgame/26/age-of-renaissance), inspired by
[Snellman's command-log approach](https://www.snellman.net/blog/archive/2014-12-08-command-languages-as-game-ui/).
The current application is a starter scaffold; the game engine is described in the documents below.

- [Application design](docs/design.md): architecture, storage, undo, controllers and plans.
- [Engine](docs/engine.md): replay contract, state, flow, data and verification.
- [Rules](docs/rules.md): game mechanics, variants, sources and unresolved questions.

```text
core/     platform-independent Kotlin engine and game data (JVM/JS)
server/   Ktor application, depending on core
web/      React/TypeScript client, built independently with Vite
```

Use JDK 21 and a Node version supported by Vite 7 (20.19+ or 22.12+). Install web dependencies once from the repository
root with `npm install`.

Run the server with `./gradlew :server:run`, and the web client in another terminal with `npm start`. The server uses
port 8080 and the web client uses port 5173. The IDE's **web** run configuration starts the same npm command.

Build the web client with `npm run build`; output is in `web/dist/`. It does not require a Kotlin/JS distribution.
Run server tests with `./gradlew :server:test` and core JVM tests with `./gradlew :core:jvmTest`. Use
`./gradlew :core:compileKotlinJs` to check the core JS target.
