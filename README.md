# Age of Renaissance

AoR is a Kotlin Multiplatform engine, Ktor server and React client for
[Age of Renaissance](https://boardgamegeek.com/boardgame/26/age-of-renaissance), inspired by
[Snellman's command-log approach](https://www.snellman.net/blog/archive/2014-12-08-command-languages-as-game-ui/).
It supports complete three-epoch games for 3–6 people: private setup and token bids, all 64 cards,
purchases, map expansion, competitions, interruptions, income and final scoring. Commands and
recorded outcomes support deterministic replay, restart and scoped, audited history edits.

- [Requirements](docs/requirements.md): goals, acceptance contracts and scope.
- [Rules](docs/rules.md), [commands](docs/commands.md) and [variant inventory](docs/variant-inventory.md):
  game behavior, reviewed rulings, syntax and current options.
- [Setup](docs/manual-setup.md) and [full-game guide](docs/manual-full-game.md): player walkthroughs.
- [Design](docs/design.md) and [engine](docs/engine.md): architecture, privacy, persistence and replay.
- [Deployment](docs/deployment.md) and [follow-ups](docs/follow-ups.md): hosting, administration and launch prerequisites.
- [UI principles](docs/ui-design.md), [map design](docs/map-design.md),
  [decision coverage](docs/ui-decision-coverage.md) and [UX review](docs/ux-next-steps.md): interaction and evidence.
- [Principles alignment proposal](docs/principles-alignment-proposal.md): project-wide review and
  proposed improvements to decisions, engine boundaries and game management.
- [Data inventory](docs/data-inventory.md), [card inventory](docs/card-inventory.md) and
  [rule coverage](docs/rule-coverage.md): catalog sources and verification.
- [European map authoring](docs/european-map.md): alternate-board definitions and validation.

```text
core/     platform-independent Kotlin engine and game data (JVM/JS)
server/   Ktor application, depending on core
web/      React/TypeScript client, built independently with Vite
```

## Run locally

Use JDK 21 and Node 24.12 or newer. From the repository root:

```sh
npm install
./gradlew :server:run
```

Run `npm start` in another terminal and open <http://localhost:5173>. The server uses port 8080;
the web client uses 5173. The IDE's **web** configuration runs the same npm command. Save each
private player link; only credential hashes are stored, so lost links cannot be recovered.

Choose players, variants and undo policy on the creation screen. Creation records resolved options
and automatically starts setup, including the private opening deal. There is no separate Start
setup action. Current variants swap Timber and War between decks 1 and 2, restrict the opening
deal (no Crusades, no events or commodities only), enable immediate Master Art discard, or select
the European map. Opening restrictions are mutually exclusive; excluded cards remain drawable.
Loire appears on the European map with four or more players. Games always have three epochs.

The compact table shows **AoR**, and player page titles identify the assigned capital. Use the map,
hand and contextual actions for play; inspectors have their own surface and preserve prepared
choices. Markets and commodity cards offer optional full payout boxes. Activity opens by default
and explains draws, dice, cash changes and epoch/end triggers. See the guides for expansion resources,
public unplayed-leader counts and the optional Urban Ascendancy purchase draft.

The command input shares engine validation with forms. It accepts assigned capital prefixes and
unambiguous card shorthand, such as `venice: play wine/cloth cloth`. Stored commands retain canonical
player IDs and hand references. Undo defaults to own actions in the current phase activity until
another player acts or an outcome is recorded. Competitive and friendly scopes are selectable at
creation; **Commands** contains the authorized suffix editor. See [history contracts](docs/design.md#4-revisions-and-undo).

## Storage and compatibility

Local games and unrevealed submissions use `.aor-data/` at the repository root. Set `AOR_DATA_DIR`
to choose an absolute path or `AOR_HTTP_PORT` to change the port. Use one server per data directory.
The local server listens on `127.0.0.1`. Hosted mode uses verified Firebase accounts, registered
handles, membership access and managed PostgreSQL for all durable state. For SQL locally or on
another provider, set `AOR_STORAGE=postgres` and secret `AOR_DATABASE_URL`. TLS defaults to
`verify-full`; loopback development may use `?sslmode=disable`. See [deployment](docs/deployment.md).

Current games use file format 6, rules `ah1996-three-epoch-4` and data `ah1996-catalog-13`.
Creation records configuration in revision 1 and automatic setup/outcomes in revision 2. Ordinary
revisions contain deltas. Earlier versions require explicit, validated administrative upgrades with
an archived preceding file; the engine performs no automatic migration.

The reviewed catalog and generated maps include Fez's Western Mediterranean contact and the
standard/European Mediterranean distinction. [Rules](docs/rules.md) and the
[connection inventory](docs/connection-inventory.md) own the full graph and rulings. At the
25-marker limit, War/Crusades recipients may forgo an award or move another controlled marker.
Commodity payments continue their quadratic formula beyond printed box 10.

The in-app **?** controls open contextual popups for variants, map symbols, app behavior, printed
tracks, and payout boxes without replacing the live game or its prepared choices. Card and advance
inspections own their details. Public lookup data comes from the read-only `/api/reference` endpoint;
no game or private state is included. Activity uses
entry-time identities, and tab favicons indicate outstanding player decisions.

## Verify changes

```sh
./gradlew formatKotlin
./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat
npm run build
npm test --workspace web
npm run test:browser --workspace web
```

The formatter is pinned ktfmt 0.64 in Kotlin language style. Build output is `web/dist/`; no Kotlin/JS
distribution is required. The browser runner owns an isolated server, Vite, Chrome profile and game
data; it retains screenshots outside the repository. [Coverage](docs/ui-decision-coverage.md) records
what the checks establish and remaining manual review.

SQL tests use PostgreSQL 18: an isolated native cluster on Linux/macOS, or the disposable database
in `AOR_TEST_DATABASE_URL`. Each fixture owns its schema; CI uses a service container. To run browser
checks against SQL from the repository root:

```sh
AOR_BROWSER_SERVER="$PWD/web/scripts/postgres-browser-server.sh" npm run test:browser --workspace web
```

Apply [AGENTS.md](AGENTS.md) for coding, branch and verification conventions. Bots and persistent
private plans remain deferred.
