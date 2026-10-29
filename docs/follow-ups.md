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
  Supply the Render CI credentials if weekly runtime refreshes are wanted. Set billing alerts and
  document the private backup retention period.

## Human verification

- Perform the human screen-reader and physical-phone review recorded in the UI requirements.

## Later maintenance

Some unreachable retired-rule handlers remain internally. Remove them incrementally with focused
behavioral evidence; preserve baseline mechanics and the supported variants. Strategy controllers
and private plans remain deferred.
