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

## Formatting and verification

- After editing Kotlin sources or Gradle Kotlin scripts, run `./gradlew formatKotlin` to completion.
  Then run `./gradlew checkKotlinFormat`. The pinned formatter is ktfmt in Kotlin language style;
  `.editorconfig` supplies matching IDE defaults.
- Run focused tests appropriate to the changed behavior. The integrated Kotlin checks are
  `./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat`.
- For browser changes, run `npm run build` from `web` and verify affected user interactions.
