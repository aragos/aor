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
server/   Ktor APIs, authorization, persistence and authoritative randomness
web/      React/TypeScript table, built independently with Vite
```

## Run locally

Use JDK 21 and Node 26 LTS. From the repository root:

```sh
npm install
./gradlew :server:run
```

Run `npm start` in another terminal and open <http://localhost:5173>. Ktor listens on
`127.0.0.1:8080`; Vite proxies API requests. The IDE's **web** configuration uses the same npm
command. Creation selects players, variants and undo policy, then starts setup automatically.
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
