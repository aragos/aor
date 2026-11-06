# Deployment and operation

Production is a Render Docker service, PostgreSQL 18 and Firebase email/password or Google auth.
[render.yaml](../render.yaml), [Dockerfile](../Dockerfile), [CI](../.github/workflows/checks.yml) and
[deployment/](../deployment/) own exact settings; [follow-ups](follow-ups.md) tracks provider checks.

## Configure hosting

1. Register Firebase web app; enable Email/Password and Google, support email, enumeration protection
   and a 12-character minimum password policy. Configure verification/reset templates. Play requires
   verified email and a unique registered handle.
2. Supply a dedicated `roles/firebaseauth.viewer` service-account JSON as Render secret file
   `/etc/secrets/firebase-admin.json` for application-default token/revocation checks; never commit it.
3. Connect Blueprint to `release`, enable Auto Sync and **After CI Checks Pass** auto-deploy. Co-locate
   app/database; provide Firebase project/API key. Confirm actual plans, costs, storage and recovery
   retention against [scope](requirements.md). Keep internal database access, no public IP allowlist.
4. Verify the initial admin's Firebase account, put its UID in `AOR_ADMIN_UIDS`, authorize Render/custom
   domains in Firebase and register the handle. Approve organizers in Player creation permissions.
5. Add a Render custom domain/CNAME in Squarespace if desired, retaining website/mail records. Render
   supplies TLS. Test actual sign-in, verification/reset, privacy, restart persistence and recovery;
   monitor CPU, memory, database size/connections, traffic and billing.

| Runtime variable | Meaning/default |
| --- | --- |
| `AOR_AUTH_MODE` | Image: `firebase`; development: `local` |
| `AOR_FIREBASE_PROJECT_ID` / `AOR_FIREBASE_WEB_API_KEY` | Required auth project / public web config |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/etc/secrets/firebase-admin.json` |
| `AOR_ADMIN_UIDS` | Exact stable UIDs; empty grants no admin |
| `AOR_GAME_CREATION_POLICY` | `approved` (default) or `registered` |
| `AOR_STORAGE` | Image: `postgres`; local: inferred from database URL, else `file` |
| `AOR_DATABASE_URL` / `DATABASE_URL` | Secret PostgreSQL URL; first takes precedence |
| `AOR_DATABASE_TLS` | `verify-full`; Blueprint: `require` for Render internal certificates; `disable` only on loopback |
| `AOR_DATABASE_SCHEMA` / `AOR_DATABASE_POOL_SIZE` | `aor` (lowercase SQL identifier, ≤63 chars) / 4 connections (1–20) |
| `AOR_DATA_DIR` / `AOR_WEB_DIR` | File backend: `.aor-data` / image assets: `/app/web` |
| `AOR_HTTP_HOST` / `PORT`, `AOR_HTTP_PORT` | Image: `0.0.0.0` / Render port; explicit application port wins |

Startup applies Flyway before traffic; `/api/health` checks SQL. The image runs UID 10001 with 256 MB
heap/bounded direct memory and no writable application volume. Database URLs allow only `sslmode`/
`sslrootcert`; TLS defaults to hostname verification/JVM trust, with an optional provider CA file.

## Release and maintenance

[AGENTS.md](../AGENTS.md#verification-and-release) owns promotion and live verification. A merge to
`main` alone does not deploy. CI runs source tests/catalogs/browser checks and production-container
build/SQL smoke in parallel; `verify` requires both. The container covers web typecheck/bundling.
Require `verify` where the plan permits; direct CI checks are not enforced branch protection
(the private repository's plan previously returned 403).

[Dependabot](../.github/dependabot.yml) and [dependency graph](../.github/workflows/dependency-graph.yml)
own monitoring. [Patch auto-merge](../.github/workflows/patch-updates.yml) needs `AUTO_MERGE_PATCHES=true`,
`DEPENDENCY_MERGE_TOKEN`, GitHub auto-merge and classic protection requiring `verify`. Scope the bot token
to contents/PR write and protection read; review major/minor updates.
Weekly/manual runtime refresh needs `RENDER_API_KEY` and `RENDER_SERVICE_ID`: it verifies selected
`release` and rebuilds that exact commit with cleared cache only if the branch has not moved.
Push/PR runs do not refresh. Missing credentials disable refresh; monitor failures and rotate secrets.

Never edit applied [Flyway migrations](../server/src/main/resources/db/migration/). New schemas must
support overlapping old instances or require a maintenance cutover; schema updates cannot change rules.

## Import file storage

Stop the format-7 file server, privately copy its complete directory, configure SQL secrets and run:

```sh
./gradlew :server:importFileStorage -PstorageImport=/absolute/path/to/stopped-data
```

The target schema must be empty. [StorageImport.kt](../server/src/main/kotlin/dev/aragos/aor/StorageImport.kt)
imports all repositories/exact archives in one transaction without changing the source. Retain the
Firebase project for UID continuity. Private-link games keep credentials, require local mode and gain
no memberships. Unsupported active versions require an explicit upgrade first. Verify counts, private
views and archives before traffic; keep the source stopped once SQL accepts moves.

## Game operations and programmatic access

[HostedRoutes.kt](../server/src/main/kotlin/dev/aragos/aor/HostedRoutes.kt) owns hosted routes;
[GameAdministration.kt](../server/src/main/kotlin/dev/aragos/aor/GameAdministration.kt) validates log
changes. Authenticate with `Authorization: Bearer <Firebase ID token>`. Admins cannot impersonate seats;
log exports include authoritative secrets.

| Admin endpoint | Contract |
| --- | --- |
| `GET /api/admin/games?status=all&limit=50&after=…` | Games/status/revision, UUID cursor |
| `GET /api/admin/players` | Directory/effective creation permissions |
| `POST /api/admin/players/{uid}/creation` | `{"allowed":true}` grants/revokes creation |
| `GET /api/admin/logs?limit=20&after=…` | Effective logs, headers/audit; no credential hashes |
| `GET /api/admin/games/{id}/log[?archive=<name>]` | Current log/archive list or preceding archive |
| `POST /api/admin/games/{id}/log` | `expectedRevision`, complete `entries`, optional `dryRun:true` |
| `POST /api/admin/games/{id}/status` | `expectedRevision`, `action:"cancel"` or `"abandon"`; retain history/read access |
| `POST /api/admin/games/{id}/purge` | `expectedRevision`; remove game/archives, retain audit receipt |

[scripts/game-admin.py](../scripts/game-admin.py) provides login/export/validate/apply/purge; use
`--help` and subcommand help. It avoids shell passwords, writes 0600 without overwriting and stores
no passwords/refresh tokens. Use HTTPS. Tokens last about an hour; automation may use `AOR_ADMIN_TOKEN`.

```sh
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json login --email admin@example.com
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json export --include-archives --output /tmp/aor-games.json
./gradlew :server:verifyGameLogs -PgameLogs=/tmp/aor-games.json
```

## Saved-game compatibility

[Game.kt](../core/src/commonMain/kotlin/dev/aragos/aor/Game.kt) owns the current rules/data versions;
[CatalogVersion.kt](../core/src/commonMain/kotlin/dev/aragos/aor/CatalogVersion.kt) owns supported
historical catalogs. Ordinary play decodes file format **7** only. Recorded catalogs retain their
topology/matching drawings; unknown combinations never substitute current geometry. Header edits are
not migrations. [AGENTS.md](../AGENTS.md#invariants) owns preservation requirements;
[Linear policy conversion](design.md#linear-game) has its separate private-state-preserving contract.
Retired format-6 conversion endpoints/legacy decoders are unavailable; already-upgraded files retain
audit revisions/exact archives. Unsupported files remain admin-exportable.

1. Before an engine change, retain a complete private backup, export logs and run `:server:verifyGameLogs`
   with the proposed engine. It replays all active logs without writes/live randomness.
2. A syntax/rules/catalog upgrade needs an implemented, tested event mapping. Preserve every `!` outcome
   exactly/in order, bound to its original logical request. Reject unprovable source continuity; replay
   must finish without a pending authority request or incomplete simultaneous transaction.
3. With the corresponding engine deployed, validate/apply canonical replacement entries against the
   current revision:

   ```sh
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json validate GAME_ID --revision 42 --file /tmp/replacement.json
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json apply GAME_ID --revision 42 --file /tmp/replacement.json
   ```

4. Publication checkpoints at revision 43 and archives the complete predecessor under revision/content
   SHA-256; subsequent revisions are deltas/scoped suffixes. Preserve name, credentials/memberships,
   creation time, lifecycle and undo policy; clear unrevealed submissions for resubmission. Attribute
   admin/time/action. Failed replay/write changes nothing. Inspect pre-checkpoint history through exact
   archives, not a mismatched engine. This replacement operation does not replace the required upgrade
   implementation/preservation tests.

Rules-2/3 Renaissance histories may contain removed windows; reject unproved upgrades. Finish with a
matching engine or implement a validated mapping, rather than changing the header.

## Recovery and deletion

Cancellation/abandonment retain history. `game-admin.py … purge GAME_ID --revision 43` deletes game,
revisions, memberships and archives atomically, retaining a committed audit receipt; stale/failed
purge changes nothing. Imported file-backend `REQUESTED` receipts do not prove completion. PostgreSQL
vacuum reuses space but does not shrink provisioned disk/billing.

Keep encrypted complete logical backups (`pg_dump`/provider exports) alongside managed point-in-time
recovery: whole schema, accounts, permissions, archives, receipts and pending secrets. Game-log exports
alone are insufficient. Retain the same Firebase project and deployment secrets. Restore to a separate
database, validate, stop writes and repoint; never allow both databases to accept play. Temporarily
allow a trusted IP if external backup tools need access. Test recovery before launch and set retention
beyond the provider window as needed. Restore loses subsequent moves; [follow-ups](follow-ups.md) tracks
actual hosted recovery/identity verification.
