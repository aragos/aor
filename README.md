# Age of Renaissance

Complete, replayable three-epoch games for 3–6 players: Kotlin Multiplatform rules, Ktor authority
and persistence, React table, and local automated opponents.

## Run locally

Use JDK 21 and Node 26 LTS. From the repository root:

```sh
npm install
./gradlew :ai:jsBrowserProductionLibraryDistribution
./gradlew :server:run
```

In another terminal, `npm start`, then open <http://localhost:5173>. Vite proxies Ktor on
`127.0.0.1:8080`. The IDE's **web** configuration runs the same npm command.
File storage defaults to `.aor-data/`; use one server per directory. `AOR_DATA_DIR` selects an
absolute path; `AOR_HTTP_PORT` changes the port. Keep private player links: lost credentials
cannot be recovered. Hosted play uses Firebase accounts and PostgreSQL.

To try the qualified Hard automata profile, create a local game with one human and three Hard seats,
the Standard map, No Crusades in the initial deal, deck modification enabled and immediate Master Art
discard enabled. Keep the creator's game tab open. This profile has passed automated comparisons;
its strength against human players still needs playtesting. Other setups use compatible fallback
policies. After updating the application, restart the local server and reload the creator's tab to
use the current projections and worker.


## Find the owner

Read only the guide relevant to the change; detailed rules and syntax are lookup references.

| Area | Code | Guide |
| --- | --- | --- |
| Setup, checks, training | Build files, `scripts/` | [Contributing](docs/contributing.md) |
| Rules, replay, catalogs (JVM/JS) | `core/` | [Engine](docs/engine.md), [rules](docs/rules.md), [commands](docs/commands.md) |
| Authority, privacy, storage, history | `server/` | [Architecture](docs/design.md) |
| Table and map | `web/` | [UI](docs/ui-design.md), [maps](docs/map-design.md) |
| Opponent policies / offline simulation | `ai/`, `training/` | [Engine](docs/engine.md#opponent-policies), [training](docs/contributing.md#opponent-training) |
| Hosting, release, recovery | `deployment/`, `render.yaml` | [Deployment](docs/deployment.md) |
| Goals / outstanding work | | [Scope](docs/requirements.md), [follow-ups](docs/follow-ups.md) |

[AGENTS.md](AGENTS.md) owns repository conventions. For quick feedback:

```sh
npm test --workspace web
./gradlew :core:jvmTest :server:fastTest checkKotlinFormat
```
