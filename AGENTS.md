# Project guidelines

Apply the user's global `AGENTS.md` for reusable coding conventions. Start new tasks on focused
feature branches; preserve existing work, using a separate worktree when isolation is needed.

## Read only the relevant owner

[README](README.md) is the entry point; [contributing](docs/contributing.md) owns check selection.
Read the sections needed for the task, rather than loading every guide:

| Change | Contract |
| --- | --- |
| Rules, replay, projections | [Engine](docs/engine.md); [rules](docs/rules.md) and [commands](docs/commands.md) for affected mechanics/syntax |
| Service, storage, privacy, history, bots | [Architecture](docs/design.md) |
| Client interaction | [UI review gate](docs/ui-design.md#game-table-review-gate); [map](docs/map-design.md) for spatial changes |
| Release, compatibility, recovery | [Deployment](docs/deployment.md) |
| Goals / unresolved work | [Scope](docs/requirements.md) / [follow-ups](docs/follow-ups.md) |

Keep each contract with its owner; link to code/tests instead of duplicating catalogs, completed
proposals, test inventories or execution diaries. Preserve reviewed rulings and explicit game options.

## Invariants

- Keep generic engine state, commands and views independent of setup rules, transport and storage.
  Use typed diagnostics and domain readiness.
- Existing saves must remain playable or have an implemented, documented, tested upgrade. Preserve
  continuity, outcomes, history, identities, metadata, memberships and pending private state, with
  validation, archives and optimistic concurrency. For affected changes, test representative old saves
  loading, replaying and continuing play. A version bump or export fallback is insufficient.
- Project costs, eligibility and consequences from authoritative state. Preserve valid drafts during
  polling/inspection; clear obsolete work after success. Keep temporary capabilities with their live
  owner. Public summaries use public facts; activity retains entry-time facts and separates direct
  actions from automatic receipts. Apply the UI review gate to changed interactions.

## Verification and release

- After Kotlin or Gradle Kotlin edits, finish `./gradlew formatKotlin`, then run
  `./gradlew checkKotlinFormat`. Use the focused checks in [contributing](docs/contributing.md#checks);
  CI owns integrated coverage. Do not repeat successful checks without a relevant change or failure.
- Browser changes require `npm run build` from `web` and affected interactions using production
  StrictMode and live offers. Serialize Gradle builds per checkout; batch browser suites and reuse
  an installed server via `AOR_BROWSER_SERVER`.
- A release request authorizes fetching/preserving remote changes, pushing `main`, waiting for CI on
  that exact commit, promoting it to `release` without force, and verifying Render reports it live.
  Verify saved-game compatibility before promotion, then `/api/health` and the web entry point.
  Report the commit and results; unavailable live verification means an unverified release.
