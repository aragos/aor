# Online deployment and operation

Use one Render Docker web service with a persistent disk and Firebase email/password authentication.
The repository contains the container, `render.yaml`, CI, dependency updates, an account dashboard,
and administrative APIs. Public hosting requires managed authentication; private-link mode is for
loopback development. No hosting account or domain was changed while preparing this configuration.

## Hosting choice and expected cost

The initial service uses Render's `0.5c-512mb` plan ($7/month) and 1 GB SSD ($0.25/month). Start at
about **$7.25/month plus bandwidth/build overages**, before tax. This leaves room below $25 for
moderate usage, but game count alone cannot guarantee a bill or capacity: simultaneous players,
history length and traffic matter. Monitor memory, disk, outbound traffic and build minutes after
launch. Render's Hobby allowance includes 5 GB outbound traffic; additional traffic is $0.15/GB.
These are the [published prices reviewed on October 27, 2026](https://render.com/pricing).

Email authentication at this scale fits the free tier; Identity Platform's tier-1 authentication
includes the first 50,000 monthly active users. Email sending quotas also apply. SMS is unused.
See [authentication pricing](https://cloud.google.com/identity-platform/pricing) and
[Firebase limits](https://firebase.google.com/docs/auth/limits).

This choice keeps one deployable application and the existing atomic file store. The paid service
stays awake. A disk permits one server instance and introduces a short interruption during deploys;
it cannot support horizontal scaling. Data outside `/var/data` is ephemeral. Render encrypts disks
and takes daily snapshots retained for seven days. See [disk behavior](https://render.com/docs/disks).
If concurrency outgrows this instance, measure it before increasing compute or replacing storage.

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
   1 GB disk and Virginia region. Fill `AOR_FIREBASE_PROJECT_ID` and `AOR_FIREBASE_WEB_API_KEY` from
   the web app configuration. The web API key is public configuration; the service-account JSON is
   a secret. Do not grant that service account general Editor/Owner access.
4. Create and verify the initial administrator's Firebase account. Copy its **UID**, not its email,
   to `AOR_ADMIN_UIDS`; multiple UIDs are comma-separated. An empty list grants no administration.
   Add the Render hostname and intended custom hostname to Firebase's authorized domains. Deploy,
   then sign in and register the administrator's unique handle/display name in the application.
5. Register a few players. Keep `AOR_GAME_CREATION_POLICY=approved` (default), and use **Player
   creation permissions** to approve game organizers. `registered` instead allows every verified,
   registered account to create games. Administrators always can. Games require 3–6 distinct
   registered UIDs including the creator; public handles and names help find the intended person.
6. Verify signup, verification email delivery, password reset, restart persistence, cross-account
   isolation and a complete game on the actual service. Check the first CI container build and
   memory usage on the 512 MB instance. Local tests use a fake identity verifier and cannot prove
   live provider credentials or email delivery. Docker was unavailable in the preparation workspace.

Runtime configuration:

| Variable | Meaning/default |
| --- | --- |
| `AOR_AUTH_MODE` | `firebase` in the production image; `local` for development |
| `AOR_FIREBASE_PROJECT_ID` / `AOR_FIREBASE_WEB_API_KEY` | Required managed-auth project and public web configuration |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/etc/secrets/firebase-admin.json` |
| `AOR_ADMIN_UIDS` | Exact stable administrator UIDs; empty grants nobody that role |
| `AOR_GAME_CREATION_POLICY` | `approved` or `registered`; default `approved` |
| `AOR_DATA_DIR` | `/var/data/aor` in the image; `.aor-data` locally |
| `AOR_WEB_DIR` | `/app/web`, built assets served by Ktor on the same origin |
| `AOR_HTTP_HOST` | `0.0.0.0` in the image; loopback locally |
| `PORT` / `AOR_HTTP_PORT` | Render's supplied port; explicit application port takes precedence |

The container initializes the mounted application directory, then runs Java as UID 10001 with a
256 MB heap and bounded direct memory. One process owns the data directory. Do not run a second
service against that disk or expose local private-link mode on a public interface.

## Squarespace domain

Keep Squarespace as domain/DNS provider. Choose a subdomain such as `play.example.com`, add it as a
custom domain in Render, then add the CNAME record Render supplies in Squarespace DNS. Existing
website/mail records can stay in place. Render provisions and renews TLS after DNS verification;
follow its displayed records if using an apex domain instead. Add the same hostname to Firebase's
authorized domains. See [Render custom domains](https://render.com/docs/custom-domains).

## Updates and automatic protection

`Checks / verify` runs Kotlin tests/JS compilation/formatting, web tests/build, browser interactions,
the live undo suite, an npm advisory audit, and a fresh production container build. Render's
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
   in history. A failed replay or active-file write leaves the old active file unchanged.

Only game file format **5** and the current rules/data versions are decoded for ordinary play.
Older files remain exportable to admins; there is no silent legacy decoder. An explicit validated
upgrade reconstructs a current header and checkpoint. Historic revisions before a checkpoint remain
in its archive and are inspected through admin exports rather than replayed with a mismatched engine.

Cancellation and abandonment retain files. To reclaim space:

```sh
python3 scripts/game-admin.py --url https://play.example.com --token-file /tmp/aor.admin-token.json purge GAME_ID --revision 43
```

Purge also removes previous engine archives. Permission and purge receipts record the requested
operation before its write; a receipt alone does not prove that the operation completed. Keep separate encrypted backups according to the
desired recovery window. For complete recovery, back up `/var/data/aor` (including `accounts.json`
and archives) and retain control of the same Firebase project: UIDs grant membership. Restore to
one stopped/replaced instance, not two writers. Render disk snapshots provide a seven-day fallback;
restoring one loses later moves. Test backup restoration before relying on it.
