# Project coding guidelines

Reusable Git, style, design, documentation, and testing preferences are maintained in the user's
global Codex `AGENTS.md`. Apply that guidance alongside these project-specific contracts.

## Development branches

- Start each new development task on a new, focused feature branch before editing. Continue related
  work on that task's branch; do not reuse an unrelated task's branch or develop directly on `main`,
  `release`, or another shared integration branch.
- Preserve existing uncommitted work when creating or switching branches. Use a separate worktree
  when a new task needs isolation from ongoing changes.

## Release workflow

- A request to "release" authorizes the complete sequence: push `main` to its upstream, wait for
  CI to pass for that exact commit, push the verified commit to the remote `release` branch, and
  verify that Render successfully deploys it.
- Fetch first and preserve remote changes. Promote the verified `main` commit to `release` without
  force-pushing. If CI fails, fix the failure and verify the updated commit before promotion.
- A release is complete only when Render reports the intended commit live and the deployed
  application's `/api/health` and web entry point respond successfully. Report the commit and
  verification results; if access prevents verification, report the release as unverified.
- Verify saved-game compatibility or the required upgrade path before promoting to `release`.
- See `docs/deployment.md` for hosting configuration and operational requirements.

## Domain and compatibility

- Keep generic engine state, commands, prompts, and views independent of setup-specific rules.
- Use typed diagnostics for rejected game actions.
- Every change must keep existing saved games playable or include an implemented, documented and
  tested upgrade path to the newest version. This applies to rules, data, commands, replay, game
  files and database changes; a version bump or an export-only fallback does not satisfy it.
- Ordinary play may decode only current versions when explicit upgrades cover affected existing
  games. Upgrades must preserve game continuity, recorded outcomes, history, identities, metadata,
  memberships and pending private state, with validation, archives and optimistic concurrency.
- For changes that can affect saved games, verify representative pre-change saves can load, replay
  and continue play, directly or after upgrade. Include those regression checks with the change;
  schema maintenance must not silently alter game behavior.
- Use domain readiness instead of enumerating future stages.
- Keep domain rules and rulings in `docs/rules.md`; preserve the reviewed catalog and explicit game
  options when making implementation changes.

## Interaction and documentation

- Review UI changes against the game-table review gate in `docs/ui-design.md`: remove duplicate
  controls/panels/copy, keep consequences beside actions, retain capital symbols, and check successful
  cleanup separately from rejected/polling draft preservation. Help owns lookup data and app behavior.
- Project costs, destinations, eligibility and consequences from authoritative domain state. Keep
  inspection separate from action drafts, and preserve valid drafts through polling and inspection.
- Presentation shortcuts must preserve domain provenance and lifecycle. Keep temporary facts with
  their live owner so replacing it cannot resurrect stale capabilities.
- Activity must use entry-time identities and facts; appending history must not rewrite existing
  entries. Only explicit history edits may replace affected entries. Attribute direct actions and
  automatic phase receipts separately, retaining actual resource endpoints and public causes.
- Derive public summaries only from public information; a count must not reveal a private card or
  decision. Distinguish a private browser draft from a committed or server-saved action.
- Keep goals and acceptance in `docs/requirements.md`, reusable UI contracts in `docs/ui-design.md`,
  observed evidence in `docs/ui-decision-coverage.md`, and unresolved defaults in `docs/follow-ups.md`.
  Update their owners instead of copying requirements into historical task lists.

## Formatting and verification

- After editing Kotlin sources or Gradle Kotlin scripts, run `./gradlew formatKotlin` to completion.
  Then run `./gradlew checkKotlinFormat`. The pinned formatter is ktfmt in Kotlin language style;
  `.editorconfig` supplies matching IDE defaults.
- Run focused tests appropriate to the changed behavior. The integrated Kotlin checks are
  `./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat`.
- For browser changes, run `npm run build` from `web` and verify affected user interactions.
  Match production React StrictMode in interaction fixtures; exercise live server offers for
  lifecycle regressions rather than relying only on fabricated choices.
- Serialize Gradle builds that write to the same checkout. Browser checks can reuse an installed
  server through `AOR_BROWSER_SERVER`; use separate worktrees for concurrent build work.
