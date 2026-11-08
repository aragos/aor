# Open work

Unresolved operational/human checks; automated tests do not establish these. Current contracts live
with their [README owners](../README.md#find-the-owner); no gameplay defaults await approval.

## Deployment and recovery

- On Render, verify Google/signup/verification/reset, three-account isolation and restart persistence.
- Rehearse [complete SQL recovery](deployment.md#recovery-and-deletion), including accounts/permissions,
  memberships, histories/archives/receipts, pending secrets and closed games. Retain Firebase identity;
  record schema/revision, timings and continuity without secrets. Validate before single-writer cutover;
  agree retention/billing alerts. Existing file deployments need [import/cutover](deployment.md#import-file-storage).
- Verify deployed [Friendly-to-Linear migration](design.md#linear-game) on representative saves/both
  backends: prior edits/checkpoints, pending secrets/plans, concurrent writes, restart, idempotence and
  continued play. Record per-game outcomes/resolve failures before declaring rollout complete.
- Configure optional patch auto-merge and Render refresh credentials if wanted; see
  [maintenance](deployment.md#release-and-maintenance).

## Human review and maintenance

- Review full-session keyboard/screen-reader play, physical-phone touch/scroll, browser-chrome zoom and
  SVG/composited/native-control contrast. Record devices/tools/findings.
- Measure six-player conflict-heavy late-game load on production-like hosting before claiming capacity
  or changing transport/caching. `performance.browser.mjs` measures local timings; low-conflict Vite
  runs do not establish production latency or isolated React cost.
- Fix the HTTP I/O catch attempting a second JSON response after headers commit. Preserve pre-commit
  storage errors; allow a started response to terminate normally.
- Rules-2/3 Renaissance histories need a validated event mapping; retain continuity rejection until
  implemented. Matching older engines remain an alternative.

## Opponent evaluation

- Run the complete training matrix/independent strength evaluation before claiming measured difficulty
  separation or trained advantage. Artifact coverage gaps block promotion; fixtures do not prove strength.
- Consider observation-safe sampled continuations, beliefs and learned leaf values after reproducible
  contextual tuning; compare at equal browser budgets. Deferred features remain in [scope](requirements.md).
