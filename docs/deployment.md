# Online deployment and operation

Use a Render Docker web service, managed PostgreSQL 18 and Firebase email/password authentication.
The repository contains a two-resource Blueprint, SQL migrations, CI, dependency updates, an account
dashboard and administrative APIs. Public hosting requires managed authentication; private-link
mode is for loopback development. Infrastructure and DNS must still be provisioned by the owner.

## Hosting choice and expected cost

**Recommend Render with managed PostgreSQL.** The starting configuration is $7/month for the
`0.5c-512mb` application, $6/month for the `0.1c-256mb` database and $0.30/month for 1 GB of database
storage: approximately **$13.30/month before tax and usage overages** on the free Hobby workspace.
The [compute prices](https://render.com/articles/production-rails-hosting-guide) and
[database storage price](https://render.com/articles/how-much-does-cloud-application-hosting-cost-for-small-businesses)
were reviewed October 28, 2026. There is no application disk. Start small and monitor database size,
CPU, connections, application memory, build minutes and outbound traffic. Hundreds of games per
month alone cannot establish capacity; simultaneous players and retained history matter.

The managed PostgreSQL options compare as follows:

| Option | Assessment for this application |
| --- | --- |
| Render app + Render PostgreSQL | About $13.30/month baseline, one Blueprint and private same-region networking. Managed database maintenance and recovery fit the operating budget. |
| DigitalOcean App Platform + managed PostgreSQL | A credible alternative with more database memory: $5 app plus database compute starting at $15.15, with separately priced storage. Less budget headroom; compare the final storage quote. See [app pricing](https://www.digitalocean.com/pricing/app-platform) and [database pricing](https://www.digitalocean.com/pricing/managed-databases). |
| Small app host + Neon | Potentially cheaper for intermittent use. Neon Free has 100 CU-hours/month and 1 GB; Launch meters compute at $0.106/CU-hour and storage at $0.35/GB-month. Continuous 0.25-CU operation is about $19.35/month in compute alone. Application polling and health checks can prevent sleep. See [Neon pricing](https://neon.com/pricing). |
| Fly.io + Fly Managed PostgreSQL | Managed PostgreSQL starts at $38/month before the app or storage, exceeding this budget. See [Fly pricing](https://docs.fly.io/about/pricing). |
| Railway app + PostgreSQL | A credible metered alternative: Hobby has a $5 minimum credited toward usage; RAM is $10/GB-month and CPU $20/vCPU-month. Railway patches its maintained database templates; backups/PITR must be enabled explicitly. Cost may be lower at idle or higher under load. Render offers a simpler fixed starting quote. See [pricing](https://docs.railway.com/pricing) and [database responsibilities](https://docs.railway.com/databases#what-railway-manages). |

Render's advantage here is the combination of predictable entry pricing, managed PostgreSQL,
GitHub-triggered Docker deploys and one provider for the application/database. The application uses
standard PostgreSQL and can move elsewhere. This entry tier has no high-availability standby;
maintenance can interrupt play. Confirm actual load on the deployed instance before claiming that
this size is sufficient. Increasing either compute tier may require revisiting the $25 budget.

Paid Render databases include continuous recovery: Hobby retains three days, Pro or higher seven.
Recovery creates another database for inspection and cutover; it is not an in-place undo and the
recovery instance is billed. See [recovery and backups](https://render.com/docs/postgresql-backups).
Render applies [minor-version security updates](https://render.com/docs/postgresql-upgrading), with
maintenance-window controls. This is independent of application dependency and container updates.

Email authentication at this scale fits the first 50,000 monthly active users in the tier-1
[Identity Platform allowance](https://cloud.google.com/identity-platform/pricing). Email sending
quotas also apply; see [Firebase limits](https://firebase.google.com/docs/auth/limits). SMS is unused.

## First launch

1. Create a Firebase project and register a web application. Enable Email/Password authentication.
   In Authentication settings, enable email enumeration protection and an enforced password policy
   of at least 12 characters. Set an appropriate verification/reset email template and contact
   address. The application requires verified email before registering a player or joining games.
   [Firebase password authentication](https://firebase.google.com/docs/auth/web/password-auth)
   describes the provider settings.
2. Create a dedicated service account with the Firebase Authentication Viewer role
   (`roles/firebaseauth.viewer`). The server verifies ID tokens and checks disabled/revoked users;
   it does not create identities or manage passwords. Generate its credential JSON and keep it out
   of the repository. Supply it as Render's secret file **`firebase-admin.json`** at
   `/etc/secrets/firebase-admin.json`. The Admin SDK uses application-default credentials. See
   [Admin setup](https://firebase.google.com/docs/admin/setup) and
   [token verification](https://firebase.google.com/docs/auth/admin/verify-id-tokens).
3. Connect the repository to Render and create a Blueprint from `render.yaml`. Confirm the plan,
   PostgreSQL 18, 1 GB database storage and matching Virginia regions. Fill `AOR_FIREBASE_PROJECT_ID`
   and `AOR_FIREBASE_WEB_API_KEY` from the web app configuration. The web API key is public configuration; the service-account JSON is
   a secret. Do not grant that service account general Editor/Owner access.
4. Create and verify the initial administrator's Firebase account. Copy its **UID**, not its email,
   to `AOR_ADMIN_UIDS`; multiple UIDs are comma-separated. An empty list grants no administration.
   Add the Render hostname and intended custom hostname to Firebase's authorized domains. Deploy,
   then sign in and register the administrator's unique handle/display name in the application.
5. Register a few players. Keep `AOR_GAME_CREATION_POLICY=approved` (default), and use **Player
   creation permissions** to approve game organizers. `registered` instead allows every verified,
   registered account to create games. Administrators always can. Games require 3–6 distinct
   registered UIDs including the creator; public handles and names help find the intended person.
6. Confirm that the database has no public IP allowlist entries and the application uses its
   internal URL. Startup applies checksum-validated Flyway schema migrations before accepting
   traffic. Health checks query PostgreSQL. The Blueprint sets `sslmode=require` because Render's
   internal certificates are self-signed; other managed endpoints default to hostname-verified TLS.
   See [Render connection security](https://render.com/docs/postgresql-creating-connecting).
7. Verify signup, verification email delivery, password reset, restart persistence, cross-account
   isolation, database restoration and a complete game on the actual service. CI runs real
   PostgreSQL tests and a non-root production-container smoke check. Provider credentials, email
   delivery and capacity on the smallest paid tiers require live checks.

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

### Storage, concurrency and schema updates

PostgreSQL owns accounts, unique handles, memberships, permissions, game metadata, private pending
submissions, immutable revision rows, upgrade archives and administrative receipts. Indexed directory
projections provide status and my-move filtering without replaying every game. The engine still owns
legality and readiness. Revisions retain their delta/suffix/checkpoint meaning; this is not a SQL
rewrite of the game rules.

Each write locks its game row and commits history, pending submissions and directory projections in
one transaction. Private submissions advance a storage generation even when the public revision
stays unchanged. Independent servers and restarted pools detect that generation before using cached
state. Permission changes and purge receipts commit atomically with their operation. Failed writes
roll back, and stale public revisions still receive 409. No write is automatically retried with fresh
randomness. All durable application state is shared, permitting overlapping deployments and multiple
instances running the same engine/schema versions. The Blueprint starts with one instance.

Flyway applies versioned database-schema changes at startup and rejects altered migration checksums.
Never edit an applied migration. Future migrations must remain compatible with any old instance
serving traffic during deploys; otherwise schedule a maintenance cutover. Schema migration does not
upgrade game rules, commands or catalog versions. That remains the explicit log-upgrade operation.

### Importing existing file data

For current game file format 5, stop the file-backed server and keep a private copy of its complete
data directory. Configure the target database secrets and run:

```sh
./gradlew :server:importFileStorage -PstorageImport=/absolute/path/to/stopped-data
```

The target application schema must be empty. One transaction imports accounts, creation permissions,
memberships, active histories, pending bids, undo policies, exact archive bytes and audit receipts.
An error rolls back the entire import; existing SQL data is never overwritten and source files are
unchanged. Use the same Firebase project to retain UID ownership. Verify counts, player views and
archives before switching traffic. Unsupported active game formats/rules require an explicit engine
upgrade first; this tool does not introduce legacy decoding. Keep the stopped source as a rollback
copy, and do not resume it after accepting new SQL moves.

## Squarespace domain

Keep Squarespace as domain/DNS provider. Choose a subdomain such as `play.example.com`, add it as a
custom domain in Render, then add the CNAME record Render supplies in Squarespace DNS. Existing
website/mail records can stay in place. Render provisions and renews TLS after DNS verification;
follow its displayed records if using an apex domain instead. Add the same hostname to Firebase's
authorized domains. See [Render custom domains](https://render.com/docs/custom-domains).

## Updates and automatic protection

`Checks / verify` runs Kotlin tests/JS compilation/formatting, web tests/build, browser interactions,
the live undo suite against PostgreSQL, an npm advisory audit, and a fresh production container
build/startup check against PostgreSQL. HTTP tests also exercise the file backend. Render's
`checksPass` setting deploys `main` after CI succeeds. Require the `verify` check on `main` and enable
the repository's dependency graph, Dependabot alerts/security updates and secret scanning where
available. The separate JVM dependency-graph workflow submits transitive Gradle dependencies for
advisory monitoring.

Dependabot proposes npm/Gradle updates daily and Docker/Actions updates weekly. Major/minor updates
need review. To automate patches, enable GitHub auto-merge and set repository variable
`AUTO_MERGE_PATCHES=true` and supply Actions secret `DEPENDENCY_MERGE_TOKEN`, a repository-scoped
bot token with contents/pull-request write and branch-protection read access. Set its expiry and
rotation policy. This uses a separate token so the resulting main push triggers CI; the default
Actions token suppresses follow-on workflow events. The supplied workflow verifies Dependabot metadata, requires classic
branch protection with the `verify` context, and enables merging after required checks. It never
executes PR code with write permissions. A ruleset-only setup needs a corresponding policy check
before automatic patch merging will activate.

For weekly runtime/base-image refreshes, set Actions secret `RENDER_API_KEY` and repository variable
`RENDER_SERVICE_ID`. Monday's successful full verification requests a cache-cleared rebuild of the
exact tested commit via the [Render deployment API](https://api-docs.render.com/reference/create-deploy).
Use a dedicated Render account/key with access only to the deployment's workspace and rotate it.
Without those settings, CI reports that automatic runtime refresh is disabled. Watch failed checks
and deployment notifications; automation does not guarantee that every future security fix is
compatible with the engine.

Render supplies [automatic DDoS protection](https://render.com/docs/ddos-protection), managed TLS and
host maintenance. Firebase manages identity credentials and abuse limits. Application boundaries
verify membership/roles, cap request bodies, reject emulator tokens in hosted mode, set CSP/security
headers, and never log bearer tokens or passwords. Revocation checks may be cached for up to 30
seconds. Private responses are not stored by intermediary caches; unchanged polls use private
ETags, and hidden tabs poll less often. Static hashed assets may be cached for a year.

## Game operations and programmatic access

The portal shows your participating games, with ongoing/my move/finished and lifecycle filters.
Preferences and creation defaults use local browser storage with an in-memory fallback. Admins
also have all-game views, creator approvals, cancellation/abandonment, log export, validation and
permanent purge controls. An admin role does not impersonate a seat or reveal its private player
view; the explicit admin log API contains authoritative private outcomes.

All hosted APIs use `Authorization: Bearer <Firebase ID token>`. `/api/config` is public;
`/api/me` exposes the signed-in identity and registered player. Directory search (`/api/players?q=`)
requires registration, returns at most 20 matches, and does not expose email addresses.

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

Ordinary members use `GET /api/games?status=ongoing`, `my-move`, `finished`, `cancelled`, `abandoned`
or `all`. Lists accept a limit of 1–100. Writes and lifecycle changes check optimistic concurrency;
stale requests return 409. Cancelled/abandoned games reject further commands and history edits.

The standard-library client avoids shell passwords and redirects. It writes token/export files
with mode 0600 and refuses to overwrite existing files. Use an HTTPS origin and Python 3.9+:

```sh
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json login --email admin@example.com
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json list --status all
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json export --include-archives --output /tmp/aor-games.json
./gradlew :server:verifyGameLogs -PgameLogs=/tmp/aor-games.json
```

The token expires in about an hour; the tool saves neither the password nor a refresh token. For
automation, supply `AOR_ADMIN_TOKEN` from your identity system instead of a token file. Exports
contain hidden card outcomes and must remain private. They are replay/upgrade artifacts, not a
full account-directory/disaster-recovery backup.

## Engine upgrades and recovery

1. Export logs before an engine change and run `:server:verifyGameLogs` with the proposed engine.
   It reports every active log's compatibility without IO/randomness during replay or storage writes.
2. If syntax/rules/data versions change, transform an exported game's `entries` deliberately.
   Use canonical text and preserve every recorded `!` outcome exactly and in order. The full log
   must replay without a pending authority request or an incomplete simultaneous transaction.
3. After deploying the corresponding engine, validate against the game's current revision:

   ```sh
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json validate GAME_ID --revision 42 --file /tmp/replacement.json
   python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json apply GAME_ID --revision 42 --file /tmp/replacement.json
   ```

4. Publication creates a full checkpoint at revision 43 and archives the complete preceding file
   under its revision/content SHA-256. Later revisions append deltas or scoped edit suffixes.
   Credentials/memberships, creation time, lifecycle and undo policy stay with the game. Pending
   unrevealed submissions are cleared and must be resubmitted. Admin identity/time/action appear
   in history. A failed replay or database write leaves the preceding game and archive set unchanged.

Only game file format **5** and the current rules/data versions are decoded for ordinary play.
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
Test recovery before launch, and retain backups beyond the Hobby three-day window if desired.
A restore loses subsequent moves; a restored database and the old database must not both accept play.
