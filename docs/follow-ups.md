# Open work

These checks are unresolved operational work, separate from executable tests. Current behavior lives
in [scope](requirements.md), [architecture](design.md), [UI](ui-design.md) and [rules](rules.md).
There are no gameplay defaults awaiting approval.

## Deployment and recovery

- Verify Google sign-in, signup, verification/reset delivery, three-account isolation and restart
  persistence on Render. Retain the same Firebase project through database recovery.
- Restore a complete private PostgreSQL backup into an isolated database: accounts, permissions,
  memberships, histories, exact archives, receipts, pending secrets and closed games. Record schema/
  revision, timings and continuity results without private contents. Rehearse single-writer cutover;
  failed validation prevents switching. Agree backup retention and set billing alerts.
- Verify deployed Friendly-to-Linear policy migration on representative saves and both backends,
  including earlier suffix edits, checkpoints, pending secrets/plans, concurrent writes, restart,
  repeat safety and continued play. Record per-game results and resolve failures before claiming
  rollout complete. Conversion preserves state; an actual rewind clears submissions/disarms plans.
- Enable required `verify`, security alerts and optional patch auto-merge where available. The private
  repository's branch-protection API returned 403 under its plan; direct CI checks are not enforced
  protection. Supply Render refresh credentials if weekly rebuilds are wanted.
- For an existing file deployment, follow [import/cutover](deployment.md#import-file-storage) before
  changing traffic. Game-log exports alone cannot restore the full application.

## Human review and maintenance

- Review full-session keyboard/screen-reader play, physical-phone touch/scrolling, browser-chrome
  zoom and SVG/composited/native-control contrast. Record actual devices/tools and findings.
- Measure a representative six-player conflict-heavy late game on production-like hosting before
  changing transport/caching or claiming capacity. `performance.browser.mjs` records local timings;
  earlier low-conflict Vite measurements do not establish production latency or isolated React cost.
- Investigate the HTTP I/O catch attempting a second JSON response after headers are committed.
  Preserve pre-commit storage errors while allowing a started response to terminate normally.
- Older rules-2/3 histories need an explicit validated event mapping to current Renaissance windows.
  Retain continuity rejection until that upgrade exists; matching older engines remain an alternative.

Strategy controllers and plans across future phases remain deferred, as defined in [scope](requirements.md).
