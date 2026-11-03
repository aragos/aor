# Contributing

Start with the [architecture](design.md) and the relevant owner in the [engine](engine.md),
[UI](ui-design.md) or [map](map-design.md) guide. [AGENTS.md](../AGENTS.md) owns branch, coding,
compatibility and release conventions. Create a focused feature branch before editing and preserve
existing work.

## Choose checks by behavior

| Change | Start with |
| --- | --- |
| Rules, arithmetic, phase progression | [Core JVM tests](../core/src/jvmTest/kotlin/dev/aragos/aor/), beside the corresponding rule owner |
| Cross-target replay | [ReplayContractTest](../core/src/commonTest/kotlin/dev/aragos/aor/ReplayContractTest.kt) and Kotlin/JS compilation |
| History and persistence | [Server tests](../server/src/test/kotlin/dev/aragos/aor/): `HistoryEditsTest`, `HistoryRequestIdentityTest`, `PostgresStoreTest` |
| Saved-game continuity | `CatalogCompatibilityTest`, `UpgradedGameCompatibilityTest` and representative pre-change saves that load, replay and continue play |
| Plans or linear undo | `ActivityPlansTest`, `GameServiceTest`, `PostgresStoreTest`, and `planning.browser.mjs` or `history.browser.mjs` |
| Client state and interaction | [Web tests](../web/tests/): unit tests plus affected `*.browser.mjs` suites |
| Map data or drawing | [Map validation](map-design.md#generate-and-validate-drawings), geometry tests and live placement/reload |

Tests own executable behavior coverage. Use canonical text-command fixtures for game flows. Domain
tests establish calculations; service tests establish authorization, privacy and atomic persistence.
Browser fixtures establish only the interactions they exercise. Use production React StrictMode and
live server offers for lifecycle regressions. Human and provider checks remain in [open work](follow-ups.md).

## Commands

Run from the repository root. After Kotlin or Gradle Kotlin edits, finish formatting before checking:

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :ai:jvmTest :ai:jsNodeTest :training:test :server:test checkKotlinFormat
./gradlew :ai:jsBrowserProductionLibraryDistribution
npm run build
npm test --workspace web
```

The pinned formatter is ktfmt in Kotlin language style; `.editorconfig` matches the IDE defaults.
Serialize Gradle builds writing to the same checkout. `:server:fastTest` omits full-game HTTP
acceptance for shorter feedback; `:server:test` includes it. Filter a JVM test with
`--tests '*ApplicationTest'`. Run one web unit file with `node --test web/tests/api.test.mjs`.
Web output is `web/dist/`; build the AI Kotlin/JS library before starting Vite or bundling the worker.

Run affected browser suites by filename, batching to share startup:

```sh
npm run test:browser --workspace web -- map.browser.mjs history.browser.mjs bots.browser.mjs
```

[browser-check.mjs](../web/scripts/browser-check.mjs) owns an isolated server, Vite, browser
profile and game data. Suites get clean browser storage; artifacts stay outside the repository.
`AOR_BROWSER_SERVER` can reuse an installed server executable. `AOR_BROWSER_SUITE` selects one
suite; `AOR_BROWSER_TIMEOUT_MS` overrides the five-minute per-suite timeout.

SQL fixtures use PostgreSQL 18: an isolated native cluster on Linux/macOS, or the disposable database
in `AOR_TEST_DATABASE_URL`, with a schema per fixture. CI uses a service container. For SQL browser runs:

```sh
AOR_BROWSER_SERVER="$PWD/web/scripts/postgres-browser-server.sh" npm run test:browser --workspace web
```

Integrated checks are defined in [checks.yml](../.github/workflows/checks.yml). Run checks appropriate
to the change; include full server acceptance for affected server behavior.

## Opponent training

Build a standalone distribution with JDK 21 and Gradle. Subsequent execution requires JDK 21+ and
Python 3.10+ on macOS/Linux, with no server, IDE, agent, model service or Gradle process:

```sh
./gradlew :training:installDist :training:distZip
python3 training/build/install/training/bin/train-opponents.py --help
python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --hours 48
```

The complete folder `training/build/install/training/` is portable; retain its `bin/` and `lib/`
directories together. A zipped distribution is available under `training/build/distributions/`.
Use an absolute output directory when launching from another working directory.

For unattended execution from the repository root:

```sh
nohup python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --hours 48 > training-supervisor.log 2>&1 &
```

Inspect or resume it without an agent:

```sh
python3 training/build/install/training/bin/train-opponents.py --output .aor-training/run --status
tail -f .aor-training/run/trainer.log
python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --resume --hours 24
```

Resuming the same directory preserves the original 72-hour allowance, initial baseline, population,
completed coverage and completed exploration rounds. Changes to policy bytecode or matrix/seed/batch
settings require a new output directory; resource limits and the desired total round count may change.
Repeat any nondefault matrix, seed and batch options when resuming.
Send SIGTERM to the supervisor PID shown by `--status` to stop; complete checkpoints survive. One
output lock prevents concurrent writers. Ordinary training never reads production games or secrets.

The supervisor measures CPU duty cycle and aggregate resident memory for its own process group. It
limits CPU to 80% of detected logical cores, chooses a conservative JVM heap and stops at the 80%
resident-memory threshold. The three-day allowance does not reset on restart. These are sampled
process limits; they do not reserve hardware from unrelated applications. Logs, checkpoints and raw
results stay in ignored `.aor-training/` storage.

The default matrix contains all 128 player-count/variant combinations. Complete simulations cover
capital bidding/choice, all epochs and available capitals. Directed rare-decision probes complement
sampled games. `coverage.json` reports observed and missing decisions/cards/configurations separately;
it does not claim exhaustive coverage of all possible positions. `rounds.jsonl` records parameter
exploration, and `evaluation.json` records independent final comparison. A failed promotion retains
the original model in `model.json`; `candidate.json` remains available for diagnosis.

Publish a model only after reviewing `evaluation.json` and its coverage gates. Copy the validated
`model.json` into `web/src/ai/model.json`, then build and check the web client. Model schema, rules and
catalog metadata protect inference compatibility. The first implementation tunes contextual action
evaluation and bounded resource planning; richer sampled search is tracked in [open work](follow-ups.md).

## Maintain documentation

Keep each contract in its owning guide and link to implementation files and tests. Mechanics and
source rulings belong in [rules](rules.md), syntax in [commands](commands.md), and unresolved work
in [follow-ups](follow-ups.md). Update those owners as behavior changes. Git history retains completed
proposals and dated execution reports; avoid duplicate catalogs, test counts and task diaries.
