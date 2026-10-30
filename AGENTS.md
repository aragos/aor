# Project coding guidelines

Reusable Git, style, design, documentation, and testing preferences are maintained in the user's
global Codex `AGENTS.md`. Apply that guidance alongside these project-specific contracts.

## Domain and compatibility

- Keep generic engine state, commands, prompts, and views independent of setup-specific rules.
- Use typed diagnostics for rejected game actions.
- During initial development, support only the current game file format. Do not add legacy decoding
  or migration paths unless requested.
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
