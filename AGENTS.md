# Coding guidelines

These guidelines apply throughout the repository and preserve review feedback for future changes.

## Git workflow

- Use a feature branch for all work. Create or switch to the task's feature branch before editing;
  do not work directly on `main` or another shared integration branch.
- Commit and push the feature branch when requested, setting its upstream tracking branch.

## Kotlin style and clarity

- Follow the [Android Kotlin style guide](https://developer.android.com/kotlin/style-guide):
  four-space indentation, a 100-column limit, explicit imports, and files organized around one theme.
  Use braces for loops and multiline conditionals; only the guide's single-line exceptions may omit them.
- Always run `./gradlew formatKotlin` after editing Kotlin sources or Gradle Kotlin scripts. Run
  `./gradlew checkKotlinFormat` before completing the change. The pinned formatter is ktfmt in Kotlin
  language style; `.editorconfig` supplies matching IDE defaults.
- Put intrinsic validation in the type that owns the invariant. Every `require` and `check` must
  include a human-readable explanation. Use typed diagnostics for rejected game actions.
- Add KDoc where semantics are not apparent, especially identifier formats, allowed values, protocol
  representations, ownership, and persistence or replay contracts.
- Prefer method and property references for simple forwarding lambdas. Otherwise use descriptive
  lambda parameter names instead of `it` when the meaning is not immediately clear.
- Use named arguments when the relationship between a value and its parameter is unclear. Put one
  property or parameter on each line when a declaration needs multiple lines.
- Prefer immutable values and small, cohesive operations. Keep command validation and canonical
  command text on the command implementation; let the parser dispatch by command name.

## Architecture and data

- Keep generic engine state, commands, prompts, and views separate from setup-specific rules.
- Keep player-owned state, such as hands and bids, on a player representation.
- Use domain types for player ids, die rolls, and finite rule values. Keep wire encodings at the
  transport and storage boundaries. Store roles such as creator explicitly, independently of order.
- Keep entry-specific parsing, canonical text, and transitions on the entry implementation.
- Prefer library collections that support every configured target over custom collection implementations.
- Keep high-level documentation focused on behavior and contracts; document implementation details in code.
- During initial development, support only the current game file format. Do not add legacy decoding
  or migration paths unless requested.
- Derive redundant view properties, and use domain readiness instead of enumerating future stages.
- Put declarative static data in dedicated files for each data type and construct it through typed
  DSLs. Keep the DSL small and validate its invariants.
- Separate the game driver and its rules from service authorization, transport, and replaceable
  storage. Persistence should not decide game behavior.
- Document whether stored revisions contain deltas or full snapshots; replay the effective history
  accordingly. Preserve optimistic concurrency checks when changing revision storage.

## Tests and verification

- Test one behavior per test, with clear setup, action, and assertions. Split tests that perform more
  actions after assertions to exercise a different behavior.
- Prefer immutable fixtures, separate values, or fluent transformations over reassigned state.
- Prefer readable text commands for command/replay fixtures. Test typed constructors directly when
  testing their invariants.
- Use Google Truth for precise JVM assertions. Keep multiplatform assertions in `kotlin.test` when a
  test genuinely needs to run on multiple targets; Truth is JVM-only.
- Run focused tests appropriate to the changed behavior and the formatter check. Relevant checks are
  `./gradlew :core:jvmTest :core:compileKotlinJs :server:test checkKotlinFormat` and `npm run build`.
