# Follow-up checks

Current behavior is specified in [requirements](requirements.md), [UI contracts](ui-design.md) and
[rules](rules.md). All gameplay defaults collected for follow-up were reviewed and approved on
2026-10-29. Their owning contracts are authoritative; no gameplay default remains awaiting approval.
This file retains operational checks and deferred work.

## Linear game undo design decisions

The [Linear game implementation](design.md#linear-game-undo) replaces Friendly game in new
creation and upgrades stored Friendly games on startup. The agreed choices are:

- Use fresh rolls and random selections after rewind, with draws following the restored deck
  and a fresh shuffle only when a removed shuffle is required again. This permits rerolls and play
  informed by discarded history.
- Replace Friendly game in new-game creation and automatically upgrade existing Friendly games
  through an explicit audited migration. Preserve their complete history and current saved state;
  earlier revisions retain their original validation semantics. Keep Regular turn as the default.

Before rollout, verify the migration on representative saved games and both backends, including
private-state continuity, original suffix edits, repeat/restart safety, concurrent writes and
continued play. Record per-game migration results and resolve failures before declaring completion.

Acceptance belongs in [requirements](requirements.md#linear-game-undo-acceptance),
architecture in the proposal and interaction in
[UI design](ui-design.md#linear-game-undo-interaction). Automated evidence is recorded in [decision coverage](ui-decision-coverage.md). Physical-device,
screen-reader and deployed migration verification remain operational checks.

## Needed before public launch

- The deployment setup is complete and the application runs successfully on Render. Google is
  enabled in Firebase; verify Google sign-in on the deployed application after this change ships.
- Complete a real sign-up/verification/reset flow, three-account privacy check, restart and backup
  restore on Render. Check actual memory, traffic, database size and concurrent-player load. The
  $13.30/month app plus managed PostgreSQL base is a starting configuration, not a measured capacity
  promise. CI verifies real PostgreSQL and production-container startup; live provider credentials,
  email delivery and restore procedures still need deployment checks.
- If preserving an existing file deployment, stop it, retain a private backup, import its current-format
  directory into an empty database, and verify player views and archives before switching traffic.
- Enable GitHub's required `verify` check, Dependabot security alerts and optional patch auto-merge.
  The branch-protection API returned HTTP 403 on 2026-10-30: this private repository's plan requires
  GitHub Pro or a public repository to enable that feature. CI success is checked directly for the
  promoted commits; it must not be described as enforced branch protection.
  Supply the Render CI credentials if weekly runtime refreshes are wanted. Set billing alerts and
  document the private backup retention period.

## Human verification

- Perform the human screen-reader and physical-phone review recorded in the UI requirements.

## Later maintenance

Investigate the HTTP I/O error boundary attempting a second response after headers are committed.
The dependency-integration browser run and preceding main CI both logged this during navigation.
Preserve storage-failure responses before commitment while allowing already-started responses to
terminate without another JSON reply. The existing broad I/O catch masks the original exception.

Strategy controllers and plans spanning future phases remain deferred. Version-2 histories require an explicit,
validated mapping before a version-3 upgrade; do not remove the administrative continuity rejection
or add an automatic legacy decoder to make an old file appear current.

## Principles-alignment operational evidence still required

The local automated checks do not establish hosted Firebase identity continuity, a full private
PostgreSQL backup restore, or physical-device accessibility. Deployment/isolated-database locations
and existing human review records were requested during implementation. Until those runs are recorded,
these remain launch gates, separate from green GitHub checks.

Restore the complete backup (accounts, permissions, memberships, pending secrets, closed games,
immutable history, exact archives and audit receipts) into an isolated database. Reconnect through
the original provider, record revision/schema, scenarios, timing and results without private contents,
and rehearse a single-writer cutover. Failed validation prevents cutover. Agree and record retention.
Measure a representative six-player conflict-heavy late game; the earlier low-conflict benchmark is
not a production capacity measurement. Complete screen-reader, physical-phone, browser zoom and
SVG/composited contrast review and record the actual tools/devices and findings.
