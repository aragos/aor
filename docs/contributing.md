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
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
npm test --workspace web
```

The pinned formatter is ktfmt in Kotlin language style; `.editorconfig` matches the IDE defaults.
Serialize Gradle builds writing to the same checkout. `:server:fastTest` omits full-game HTTP
acceptance for shorter feedback; `:server:test` includes it. Filter a JVM test with
`--tests '*ApplicationTest'`. Run one web unit file with `node --test web/tests/api.test.mjs`.
Web output is `web/dist/`; the browser needs no Kotlin/JS distribution.

Run affected browser suites by filename, batching to share startup:

```sh
npm run test:browser --workspace web -- map.browser.mjs history.browser.mjs
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

## Maintain documentation

Keep each contract in its owning guide and link to implementation files and tests. Mechanics and
source rulings belong in [rules](rules.md), syntax in [commands](commands.md), and unresolved work
in [follow-ups](follow-ups.md). Update those owners as behavior changes. Git history retains completed
proposals and dated execution reports; avoid duplicate catalogs, test counts and task diaries.
