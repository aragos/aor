# Contributing

Follow [AGENTS.md](../AGENTS.md). Use the [README owner map](../README.md#find-the-owner) to open only
relevant contracts and code. Tests own executable evidence; documentation owns behavior and contracts.

## Checks

Run focused checks while editing. CI in [checks.yml](../.github/workflows/checks.yml) is the integrated
pre-release gate; wait for the exact commit instead of repeating the full suite locally.

| Change | Local verification |
| --- | --- |
| Kotlin / Gradle Kotlin | Finish `./gradlew formatKotlin`, then `./gradlew checkKotlinFormat` |
| Rules / flow | `./gradlew :core:jvmTest --tests '*RelevantTest'`; include `:core:jsNodeTest` for shared replay/target changes |
| AI / training | `./gradlew :ai:jvmTest :ai:jsNodeTest :training:test`; Python supervisor tests under `scripts/` |
| Service / persistence | `./gradlew :server:fastTest --tests '*RelevantTest'`; use `:server:test` for affected full-game HTTP acceptance |
| Saved-game behavior | `CatalogCompatibilityTest`, `UpgradedGameCompatibilityTest` and representative pre-change saves loading, replaying and continuing |
| Web | `npm test --workspace web`, `npm run build`, affected browser suites below |
| Map / catalogs | [Geometry generation and validation](map-design.md#generate-and-validate-drawings), geometry tests and live placement/reload |

The formatter is pinned ktfmt, Kotlin language style; `.editorconfig` matches. Serialize Gradle builds
per checkout. Use canonical text-command fixtures for game flows; browser lifecycle fixtures use
production StrictMode and live offers. Unit-file example: `node --test web/tests/api.test.mjs`.
SQL tests use PostgreSQL 18: an isolated native cluster on Linux/macOS or a disposable
`AOR_TEST_DATABASE_URL`, with a schema per fixture. CI provides the database.

For a full local source pass when needed:

```sh
./gradlew :core:jvmTest :core:jsNodeTest :ai:jvmTest :ai:jsNodeTest :ai:jsBrowserProductionLibraryDistribution :training:test :server:test checkKotlinFormat
npm test --workspace web
npm run build
```

The AI library task must precede Vite/build when its output is absent or stale; web output is
`web/dist/`. CI runs source checks and the production container in parallel. The container performs
the production web build/typecheck and SQL smoke test; the final `verify` gate requires both jobs.

Batch affected browser suites to share server, Vite and Chrome startup:

```sh
npm run test:browser --workspace web -- history.browser.mjs bots.browser.mjs
```

[browser-check.mjs](../web/scripts/browser-check.mjs) isolates game data and browser storage, cleans
owned processes and leaves artifacts outside the repository. With no selection it runs the broad
`map.browser.mjs` suite, which already includes most UI flows; select focused files during iteration.
`AOR_BROWSER_SUITE` selects one suite; `AOR_BROWSER_TIMEOUT_MS` overrides the five-minute per-suite
limit. `AOR_BROWSER_SERVER` reuses an installed executable. For SQL browser checks use
`AOR_BROWSER_SERVER="$PWD/web/scripts/postgres-browser-server.sh"` before the npm command.

## Opponent training

Build once; the complete distribution runs independently with JDK 21+ and Python 3.10+ on macOS/Linux.
Keep `bin/` and `lib/` together; `:training:distZip` produces a portable zip.

```sh
./gradlew :training:installDist
python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --hours 48
```

Use `--help` for options, `--output .aor-training/run --status` for status, and
`--mode train --output .aor-training/run --resume --hours 24` to resume. For unattended execution,
prefix the Python command with `nohup` and append `> training-supervisor.log 2>&1 &`.
Use an absolute output path when launching from another directory; SIGTERM the supervisor PID to stop safely.

One output lock prevents concurrent writers. Resume preserves the original 72-hour allowance,
baseline, population, coverage and exploration rounds. Repeat nondefault matrix/seed/batch options;
changed settings or policy bytecode require a new output directory. Resource limits and total round
count may change. Sampled process-group limits cap CPU at 80% of logical cores and stop at 80% resident
memory; they do not reserve hardware. Logs/checkpoints stay in ignored `.aor-training/`; training
never reads production games or secrets.

For throughput work, run `./gradlew :training:benchmark --args=".aor-training/before.json"`,
then repeat after the change with `--args=".aor-training/after.json .aor-training/before.json"`.
The benchmark warms up before measuring 24 repeated games across 3–6 players, both maps and
Normal/Hard policies, plus repeated directed-regret evaluations across the full curriculum.
It records wall time, thread CPU and allocated bytes separately for games and regret, verifies
replay in warmup, and checks outcomes, coverage and regret against the baseline. Use the same JDK
and an idle machine for both runs. These fixed development games do not measure playing strength.

The default matrix covers all 128 player-count/variant combinations. `coverage.json` distinguishes
observed/missing decisions, cards and configurations; directed probes do not establish exhaustive
position coverage. `rounds.jsonl` records exploration; `evaluation.json` records independent comparison.
Review evaluation and coverage gates before copying validated `model.json` to `web/src/ai/model.json`
and checking the web client. Failed promotion retains the prior model and diagnostic `candidate.json`.
Schema/rules/catalog metadata protects inference compatibility; richer search remains [open work](follow-ups.md).
