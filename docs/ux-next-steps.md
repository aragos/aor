# UX review and next steps

Reviewed on 2026-10-26 against the current implementation. This is a proposal, not a list of committed
features. [UX principles](ui-design.md) and [map contracts](map-design.md) define acceptance;
[decision coverage](ui-decision-coverage.md) separates observed behavior from remaining evidence gaps.

## Project assessment

The core, service and client already provide the right separation: domain projections calculate
costs and effects, the service owns authority and private audiences, and visual controls submit the
canonical command language. Keep those boundaries. The recent refinements applied the same pattern
across gameplay: skip empty bookkeeping, expose eligibility on objects, keep resources local to the
choice, clear completed context and show outcomes.

The review covered the core's phase progression, offers and diagnostics; service snapshots, secret
submissions, revision handling, history and persistence; the creation/setup journey, table, command
dock, cards, purchases, map and activity; geometry generation, styles, localization and verification.
The main opportunities are consistent presentation contracts and stronger evidence for difficult
positions, rather than a new game interface or changes to the reviewed mechanics.

Current limitations are concrete. Several action forms still classify command strings and manage
selection independently. Some map prompts and labels remain English literals. Error responses keep
a code but discard domain arguments. Three province labels have constrained fits; five provinces
need label-space sharing for their densest token groups. Keyboard/touch automation exists, while
manual screen-reader and contrast checks remain pending. Some setup and variant flows have fixture
evidence without a corresponding live service/browser scenario.

## Proposed work, in priority order

### 1. Unify decision context and target controls

[GameActions](../web/src/GameActions.tsx), [Controls](../web/src/Controls.tsx) and
[card decisions](../web/src/ObjectDecisions.tsx) classify command prefixes and keep separate form
state. [MapSelection](../web/src/map/MapSelection.tsx) shares target highlighting, but phase resets
and individual interruption forms still establish their own lifetimes. The core already exposes
activity, legal choices and typed offers in [GameView](../core/src/commonMain/kotlin/dev/aragos/aor/GameView.kt).

Define a stable decision identity, including owner and nested activity, and share the presentation
policy for selection, invalidation and completion. Keep selection limits tied to authoritative offers;
share the count and combined-cap behavior used by War cession with other multi-target forms. Extend
typed offers when a missing domain fact requires it. Keep canonical commands as the submission and
replay contract; avoid a parallel action protocol.

Accept when equivalent province, player, area and card choices behave consistently. Polling and
rejection preserve valid work; completed or replaced decisions leave no stale focus or targets.
Nested interruptions resume correctly, and a stale revision never triggers an implicit retry.
Verify with both live transitions and focused fixtures.

### 2. Close accessibility and setup evidence gaps

[Browser checks](../web/tests/map.browser.mjs) cover keyboard picking, touch gestures, narrow layouts,
zoom and draft continuity. [Coverage](ui-decision-coverage.md) still records manual accessibility
checks, individual setup options and private setup-return replacement as gaps. The
[creation journey](../web/src/App.tsx) depends on retaining private seat links.

Review an entire session with keyboard and a screen reader, measure contrast in every emphasis
state, and exercise setup through separate private seats with reload and restart. Check that players
can recognize whose link they are using, preserve it, change an unrevealed choice and recover from a
rejection. Review actual phone and browser-zoom use alongside the existing automation.

Accept when the recorded gaps have explicit results, each required action has an accessible name
and reachable control, and private readiness reveals no opponent choice. Add automation for defects
found; keep manual findings distinct from automated claims.

### 3. Improve crowded map legibility

[Generated geometry](../web/scripts/build-map.py) fits labels and pieces independently. Metadata
records constrained labels in Copenhagen, Toulouse and Constantinople, plus dense-group exceptions
in Copenhagen, Acre, Levant, North America and South America. The
[validator](../web/scripts/validate-map.py) and browser bounds checks prove containment; they do not
prove readability when pieces become small or share label space.

Prototype joint label/piece placement and a zoom-aware dense-group presentation for these cases.
Keep full owner, age and count information available, and compare results at Fit and at practical
zoom levels on a phone. Prefer spacing and progressive disclosure to successively smaller text.

Accept when the exceptional cases have reviewed screenshots and a stated, readable fallback with
no concealed occupants. Preserve the exact adjacency graph, contiguous areas, ownership and highlight
priority. Extend collision checks to the actual rendered labels and pieces together.

### 4. Complete decision explanations and localization

[Event text](../web/src/CardEventText.tsx) is now shared by the hand and selected card. Advance detail
still emphasizes prices and prerequisites rather than readable benefits. Some map/War picker text is
literal English. [GameService](../server/src/main/kotlin/dev/aragos/aor/GameService.kt) and
[API errors](../web/src/api.ts) carry codes without the diagnostic arguments available in the core.

Review card and advance reference copy against the rules, expose relevant benefits beside purchase
choices, and preserve typed diagnostic arguments through the transport for contextual explanations.
Centralize remaining presentation strings and quantity formatting. Reference copy must explain the
rules without becoming a second calculator or changing command encodings.

Accept when every event and advance has reviewed reference text, ordinary play exposes no raw key
or command fragment, and failures identify the affected object or resource where permitted. Check
message completeness and representative interpolation; retain private-audience tests.

### 5. Consolidate visual primitives around meaning

[Application styles](../web/src/app.css) and [map styles](../web/src/map/map.css) repeat colors,
spacing, selected/eligible states and compact detail patterns. Entity identity is already partly
shared by [map symbols](../web/src/map/MapSymbols.tsx) and table components.

Extract semantic style tokens and small shared primitives when the same interaction appears in
multiple surfaces. Start with target state, resource amounts, blockers and result notices. Keep
map-specific geometry separate from ordinary controls; avoid a framework-sized component rewrite.

Accept when the migrated surfaces share focus, contrast, units and disabled-state behavior without
changing command output. Verify the six-player table, long hands and narrow decision sheet.

### 6. Measure long-game responsiveness before changing transport

[App](../web/src/App.tsx) polls each second; [history projection](../server/src/main/kotlin/dev/aragos/aor/HistoryProjection.kt)
replays every revision delta for each snapshot. This is an architectural cost to measure, not a
measured performance defect. The generated map is also the main static payload.

Profile a late three-epoch game with six open seats, recording snapshot latency, payload size,
render time and interaction delay. If material, cache replay-derived data by effective history and
viewer, or avoid unchanged transfers. Any optimization must account for private pending submissions,
which can change without a committed revision, as well as rule versions and viewer audiences.

Accept an optimization only with before/after measurements, unchanged replay and privacy results,
correct readiness updates and stale-request behavior. Do not introduce subscriptions or client replay
without evidence that the simpler approach is insufficient.

## Sequence and parallel work

Begin with the decision-context contract and an evidence baseline. Accessibility/setup review,
crowded-map prototypes and performance measurement can proceed independently after that baseline;
they need no engine redesign. Localization and visual-primitives work can proceed alongside them in
separate files, then converge with the shared target controls. Change transport or core projections
only when the corresponding evidence identifies a need.

Deliver bounded feature branches, verify affected interactions and update the coverage inventory.
Keep accounts, bots, general undo and private plans outside this UX proposal; their deferred
architecture remains in [application design](design.md).
