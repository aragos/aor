# Follow-up checks

Current behavior is specified in [requirements](requirements.md), [UI contracts](ui-design.md) and
[rules](rules.md). All gameplay defaults collected for follow-up were reviewed and approved on
2026-10-29. Their owning contracts are authoritative; no gameplay default remains awaiting approval.
This file retains operational checks and deferred work.

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

Strategy controllers and private plans remain deferred. Version-2 histories require an explicit,
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
