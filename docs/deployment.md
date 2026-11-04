# Deployment and operation

Production uses a Render Docker service, managed PostgreSQL 18 and Firebase email/password or
Google authentication. The checked-in [render.yaml](../render.yaml), [Dockerfile](../Dockerfile),
[CI](../.github/workflows/checks.yml) and [deployment configuration](../deployment/) own the exact
settings. [Architecture](design.md) explains transactions and history; [open work](follow-ups.md)
tracks provider checks that local tests cannot establish.

## Configure hosting

1. Register a Firebase web application. Enable Email/Password and Google with a support email,
   email enumeration protection and an enforced password policy of at least 12 characters. Configure
   verification/reset templates. The app requires verified email and a registered unique handle.
2. Create a dedicated service account with `roles/firebaseauth.viewer` for token/revocation checks.
   Supply its credential JSON as Render's secret file `firebase-admin.json` at
   `/etc/secrets/firebase-admin.json`; keep it out of Git. The SDK uses application-default credentials.
3. Connect the repository Blueprint to `release` with Auto Sync enabled and the service's Auto-Deploy
   set to **After CI Checks Pass**. Keep application/database in the same region. Fill the Firebase
   project ID and public web API key. Confirm actual provider plans, prices, storage and recovery
   retention before provisioning; the project's budget and capacity goals are in [scope](requirements.md).
4. Verify the initial administrator's Firebase account and put its UID in `AOR_ADMIN_UIDS`. Add the
   Render/custom hostnames to Firebase authorized domains. Sign in and register the admin handle.
   Approve organizers through Player creation permissions; `approved` is the default creation policy.
5. Use the internal database URL and no public IP allowlist entries. The Blueprint uses
   `sslmode=require` for Render's internal certificates; other endpoints default to `verify-full`.
   Startup applies Flyway migrations before traffic; `/api/health` checks the database.
6. For a Squarespace-managed domain, add a Render custom domain and its supplied CNAME in Squarespace,
   retaining existing website/mail records. Render provisions TLS. Add the hostname to Firebase too.
7. Verify sign-in, email verification/reset, cross-account privacy, persistence and recovery on the
   actual deployment. Monitor CPU, application memory, database size/connections, traffic and billing.

Runtime configuration:

| Variable | Meaning/default |
| --- | --- |
| `AOR_AUTH_MODE` | `firebase` in the production image; `local` for development |
| `AOR_FIREBASE_PROJECT_ID` / `AOR_FIREBASE_WEB_API_KEY` | Required managed-auth project and public web configuration |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/etc/secrets/firebase-admin.json` |
| `AOR_ADMIN_UIDS` | Exact stable administrator UIDs; empty grants nobody that role |
| `AOR_GAME_CREATION_POLICY` | `approved` or `registered`; default `approved` |
| `AOR_STORAGE` | `postgres` in the image; locally inferred from a database URL, otherwise `file` |
| `AOR_DATABASE_URL` / `DATABASE_URL` | Secret `postgresql://user:password@host:port/database` URL; first takes precedence |
| `AOR_DATABASE_TLS` | `verify-full` by default; Blueprint uses `require` on Render's private network; `disable` only on loopback |
| `AOR_DATABASE_SCHEMA` | SQL schema, default `aor`; lowercase SQL identifier, at most 63 characters |
| `AOR_DATABASE_POOL_SIZE` | 4 connections per server by default; allowed 1–20 |
| `AOR_DATA_DIR` | File backend only; `.aor-data` locally |
| `AOR_WEB_DIR` | `/app/web`, built assets served by Ktor on the same origin |
| `AOR_HTTP_HOST` | `0.0.0.0` in the image; loopback locally |
| `PORT` / `AOR_HTTP_PORT` | Render's supplied port; explicit application port takes precedence |

The container runs Java as UID 10001 with a 256 MB heap and bounded direct memory. No writable
application volume is needed. URLs permit only `sslmode` and `sslrootcert` query parameters; unknown
driver options are rejected. Default TLS uses the JVM trust store with hostname verification;
`sslrootcert` can select a provider CA file. Keep credentials in secret environment configuration.

## Release and maintenance

A release request authorizes the full promotion workflow in [AGENTS.md](../AGENTS.md): fetch and
preserve remote changes, push `main`, wait for CI on that exact commit, promote it to `release`
without force, then verify Render reports that commit live. Check `/api/health` and the web entry
point. Verify saved-game compatibility before promotion. Merging into `main` alone does not deploy.
If access prevents live verification, report the release as unverified.

[checks.yml](../.github/workflows/checks.yml) defines tests, browser checks, dependency audit and
production-container/PostgreSQL smoke checks. Enable the required `verify` check where the repository
plan permits it. Branch protection was unavailable on this private repository's plan; checking CI
directly must not be described as enforced protection.

[Dependabot](../.github/dependabot.yml) and [dependency-graph.yml](../.github/workflows/dependency-graph.yml)
own update monitoring. Optional patch auto-merge in [patch-updates.yml](../.github/workflows/patch-updates.yml)
requires `AUTO_MERGE_PATCHES=true`, secret `DEPENDENCY_MERGE_TOKEN`, GitHub auto-merge and classic
branch protection with `verify`. Use a scoped bot token with contents/PR write and protection read;
review major/minor updates. Runtime image versions remain pinned to supported release lines.

Weekly/manual runtime refresh needs secret `RENDER_API_KEY` and variable `RENDER_SERVICE_ID`.
The workflow on `main` verifies the current `release` and requests a cache-cleared rebuild of that
exact commit only if `release` has not moved. Push/PR runs do not request refreshes. Without those
settings, refresh is disabled. Monitor failures and rotate provider credentials.

Never edit applied [Flyway migrations](../server/src/main/resources/db/migration/). Keep new schemas
compatible with old instances during overlapping deployments or schedule a maintenance cutover.
Schema maintenance cannot change game rules or substitute for a game-version upgrade.

## Import file storage

For current format-7 data, stop the file-backed server, retain a private copy of the complete data
directory, configure database secrets, then run:

```sh
./gradlew :server:importFileStorage -PstorageImport=/absolute/path/to/stopped-data
```

The target schema must be empty. [StorageImport.kt](../server/src/main/kotlin/dev/aragos/aor/StorageImport.kt)
imports all durable repositories and exact archives in one transaction without modifying the source.
Use the same Firebase project for UID continuity. Private-link games retain credentials and require
local mode; import does not assign memberships. Unsupported active versions need an explicit engine
upgrade first. Verify counts, private player views and archives before switching traffic. Keep the
source stopped once SQL accepts new moves.

## Game operations and programmatic access

[HostedRoutes.kt](../server/src/main/kotlin/dev/aragos/aor/HostedRoutes.kt) owns hosted routes;
[GameAdministration.kt](../server/src/main/kotlin/dev/aragos/aor/GameAdministration.kt) owns validated
administrative log changes. Hosted APIs use `Authorization: Bearer <Firebase ID token>`.
Admins do not impersonate seats; explicit log exports contain authoritative private outcomes.

| Administrative endpoint | Contract |
| --- | --- |
| `GET /api/admin/games?status=all&limit=50&after=…` | All games, statuses and revision numbers; UUID-ordered cursor pagination |
| `GET /api/admin/players` | Registered directory and effective creator permissions |
| `POST /api/admin/players/{uid}/creation` | `{"allowed":true}` grants/revokes an individual creator permission |
| `GET /api/admin/logs?limit=20&after=…` | Paginated authoritative effective logs, headers and revision audit; no credential hashes |
| `GET /api/admin/games/{id}/log` | One current log and archive names; `?archive=<name>` reads a preceding archive |
| `POST /api/admin/games/{id}/log` | `expectedRevision`, complete canonical `entries`, optional `dryRun:true` |
| `POST /api/admin/games/{id}/status` | `expectedRevision`, `action:"cancel"` or `"abandon"`; retains read access/history |
| `POST /api/admin/games/{id}/purge` | `expectedRevision`; deletes the game and its archives, retaining a small audit receipt |

Use [scripts/game-admin.py](../scripts/game-admin.py) for login, listing, exports, validation,
publication and purge. Run `--help` and subcommand `--help` for options. It avoids shell passwords,
writes files with mode 0600, refuses overwrite, and stores neither passwords nor refresh tokens.
Use HTTPS. The login token lasts about an hour; automation can supply `AOR_ADMIN_TOKEN` instead.
Exports contain secrets and are replay artifacts, not full disaster-recovery backups.

```sh
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json login --email admin@example.com
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json export --include-archives --output /tmp/aor-games.json
./gradlew :server:verifyGameLogs -PgameLogs=/tmp/aor-games.json
```

## Saved-game compatibility

Current games use file format **7**, rules `ah1996-three-epoch-4` and data `ah1996-catalog-16`.
[CatalogVersion.kt](../core/src/commonMain/kotlin/dev/aragos/aor/CatalogVersion.kt) also supports
catalogs 13–15 without rewriting their topology. Catalog 14 corrected Cornwall's land/coast
links; 15 added Chester–Portsmouth coastal contact; 16 adds the standard Portsmouth–York land
border. Standard legacy drawings remain selected by recorded identity. Unused old European drawings are retired; unknown combinations never substitute
current geometry.

Changing an exported header does not migrate a catalog. A dedicated validated upgrade must preserve
continuity, outcomes, identities, history, metadata, memberships and private state, with archives and
concurrency checks. Representative pre-change saves must load, replay and continue play. Existing
Friendly game policy migration has its separate preservation contract in [architecture](design.md#linear-game).
The completed format-6 conversion is retired; earlier upgraded format-7 games keep their original
audit revisions and exact archives. There is no conversion endpoint or silent legacy decoder.

1. Export logs before an engine change and run `:server:verifyGameLogs` with the proposed engine.
   It reports every active log's compatibility without IO/randomness during replay or storage writes.
2. If syntax/rules/data versions change, transform an exported game's `entries` deliberately.
   Use canonical text and preserve every recorded `!` outcome exactly and in order, bound to its
   original logical request. Incompatible source versions without a validated event mapping reject.
   The full log must replay without a pending authority request or incomplete simultaneous transaction.
3. After deploying the corresponding engine, validate against the game's current revision:

   ```sh
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json validate GAME_ID --revision 42 --file /tmp/replacement.json
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json apply GAME_ID --revision 42 --file /tmp/replacement.json
   ```

4. Publication creates a full checkpoint at revision 43 and archives the complete preceding file
   under its revision/content SHA-256. Later revisions append deltas or scoped edit suffixes.
   The name, credentials/memberships, creation time, lifecycle and undo policy stay with the game.
   Pending unrevealed submissions are cleared and must be resubmitted. Admin identity/time/action appear
   in history. A failed replay or database write leaves the preceding game and archive set unchanged.

Only game file format **7** and the supported rules/catalog versions are decoded for ordinary play.
Older files remain exportable to admins; there is no silent legacy decoder. An explicit validated
upgrade reconstructs a current header and checkpoint. Historic revisions before a checkpoint remain
in its archive and are inspected through admin exports rather than replayed with a mismatched engine.

Cancellation and abandonment retain history. To reclaim database space for reuse:

```sh
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json purge GAME_ID --revision 43
```

Purge cascades through revision, membership and archive rows while retaining its small committed
audit receipt. PostgreSQL reuses space after vacuum; deleting rows does not shrink the provider's
provisioned disk or bill. A stale/failed purge leaves both data and receipt unchanged. Imported
file-backend `REQUESTED` receipts retain that outcome: they do not prove completion.

For complete recovery, retain encrypted logical database backups (`pg_dump`/provider exports) in
addition to managed point-in-time recovery. Include the entire application schema, account directory,
archives and receipts; admin game-log exports alone are insufficient. Keep control of the same
Firebase project and a secure copy of deployment secrets. Restore into a separate database, validate
it, then stop writes and repoint the application. Use an appropriate trusted IP temporarily if an
external backup tool needs access; the Blueprint blocks external database connections by default.
Test recovery before launch and set retention beyond the provider recovery window as needed.
A restore loses subsequent moves; a restored database and the old database must not both accept play.

Before deploying rules version `ah1996-three-epoch-4`, retain a complete private backup. Versions 2
and 3 may contain Renaissance passes for windows that no longer exist; simply changing their header
cannot interpret them safely.
The current server lists incompatible games as unavailable and permits administrative export; it
rejects unproved upgrades. Finish such games with their matching engine or supply an explicit validated
upgrade implementation. Complete the hosted recovery and identity checks in [open work](follow-ups.md).
