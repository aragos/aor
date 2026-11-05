# Age of Renaissance

AoR implements complete three-epoch games for 3–6 players with a Kotlin Multiplatform engine,
Ktor server and React client. Canonical commands and recorded random outcomes make games replayable
through restarts and authorized history edits.

## Start here

| Guide | What it owns |
| --- | --- |
| [Contributing](docs/contributing.md) | Development workflow, test selection and verification commands |
| [Architecture](docs/design.md) | Request flow, storage, privacy, history and private plans |
| [Engine](docs/engine.md) | State machines, rule owners, projections and replay |
| [Rules](docs/rules.md) | Mechanics, supported variants, reviewed rulings and source provenance |
| [Commands](docs/commands.md) | Canonical command language and application operations |
| [UI](docs/ui-design.md) | Interaction contracts, draft lifetimes and review gate |
| [Maps and catalogs](docs/map-design.md) | Data ownership, map authoring, generation and validation |
| [Deployment](docs/deployment.md) | Hosting, release, administration and recovery |
| [Product scope](docs/requirements.md) / [open work](docs/follow-ups.md) | Goals and remaining operational checks |

```text
core/     pure Kotlin engine and catalogs, compiled for JVM and JS
ai/       observation-only opponent policies, compiled for browser workers and JVM training
training/ standalone offline simulation, parameter exploration and evaluation executable
server/   Ktor APIs, authorization, persistence and authoritative randomness
web/      React/TypeScript table and local opponent worker, bundled with Vite
```

## Run locally

Use JDK 21 and Node 26 LTS. From the repository root:

```sh
npm install
./gradlew :ai:jsBrowserProductionLibraryDistribution
./gradlew :server:run
```

Run `npm start` in another terminal and open <http://localhost:5173>. Ktor listens on
`127.0.0.1:8080`; Vite proxies API requests. The IDE's **web** configuration uses the same npm
command. Creation selects players, variants and undo policy, then starts setup automatically.
Local games can add Easy, Normal or Hard automated seats during creation. The creator's open browser
runs them with server validation, persistence and randomness. They pause while no host is open.
Online game creation requires 3–6 registered human players; automated opponents are unavailable.
Keep each private player link: only credential hashes are stored, so lost links cannot be recovered.

Local storage defaults to `.aor-data/`. Use one server per directory; `AOR_DATA_DIR` selects an
absolute path and `AOR_HTTP_PORT` changes the port. Hosted play uses Firebase accounts and
PostgreSQL; see [deployment](docs/deployment.md) for configuration and compatibility procedures.

For a quick development check:

```sh
npm test --workspace web
./gradlew :core:jvmTest :server:fastTest checkKotlinFormat
```

See [contributing](docs/contributing.md) for complete checks and focused browser runs. Follow
[AGENTS.md](AGENTS.md) for repository conventions.

## Offline opponent training

Training runs independently of the game server, IDE and coding agents. Build its distribution once:

```sh
./gradlew :training:installDist
python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --hours 48
```

The supervisor enforces CPU, memory and elapsed-time limits and writes logs, coverage, resumable
checkpoints and evaluation results. See [contributing](docs/contributing.md#opponent-training)
for background execution, resuming and publishing a validated model.
