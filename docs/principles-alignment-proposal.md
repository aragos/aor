# AoR principles alignment proposal

Implementation record (October 30, 2026): the code, replay/version boundary, request and private-write
contracts, component drafts/inspection, projected consequences, diagnostics, localization and automated
verification have been implemented in the owning modules. The accepted contracts now live in
[requirements](requirements.md), [UI design](ui-design.md), [engine](engine.md) and
[commands](commands.md); observed evidence lives in [decision coverage](ui-decision-coverage.md).
The review below is retained as historical rationale. Hosted backup/identity recovery, human-device
accessibility, production capacity and branch-protection availability remain explicit operational
limits in [follow-ups](follow-ups.md). Rules version 3 deliberately rejects unproved version-2
continuation upgrades; changing a header cannot reinterpret an old empty Renaissance pass safely.

Keep the existing engine, command history, visual table, and storage architecture. Align the
remaining behavior through a focused redesign of decision selection, supported by smaller fixes to
draft lifetimes, request recovery, private-write concurrency, accessibility, and rule projections.
The largest useful architectural change is a common contract connecting authoritative offers,
component selection, independent inspection, and action review.

This is a proposal, not a change to the approved game rules or acceptance contract. It applies the
[30 reusable implementation principles](https://chatgpt.com/space/page_692b09f312448191a29c6f076b78d2b6)
to repository revision `028a2d2e6639f494fa54357f578a6d8c347709e6`, reviewed on October 29, 2026.
Principle identifiers below refer to that collection; the coverage matrix gives their short meanings.
All gameplay defaults approved on October 29 remain accepted.

## Preserve the foundations

Several important contracts are already implemented and should constrain the changes:

- The pure engine accepts canonical commands and recorded outcomes, with explicit resumable decision
  frames and separate activity and decision identities.
- Substantive advance, token-purchase, expansion, and consequence calculations already have
  authoritative projections. Extend these patterns rather than replacing them.
- Player projections, public leader counts, historical views, and simultaneous submissions already
  enforce information boundaries. No new core privacy leak was established by this review.
- PostgreSQL already supplies atomic writes, independent-writer coordination, storage generations,
  immutable revisions, and private submission durability. Keep those transaction boundaries.
- The table already has direct map/card/advance actions, a separate inspector, explicit confirmation,
  scoped result notices, readable activity, and a strong game-tab polling implementation.
- Preserve reviewed topology, accepted map-label exceptions, current variants, and the distinction
  between commodity ownership forecasts and actual card payments.

Bots, general persistent private plans, client replay, new game rules, and a universal board-game
framework are outside this proposal. They are not prerequisites for applying the principles to the
supported human game.

## Workstreams and priorities

P1 protects existing intent or a history guarantee. P2 completes interaction and boundary contracts.
P3 reduces maintenance risk or strengthens a currently unused target. Operational gates apply before
relying on the deployed behavior they verify. Sizes describe relative scope, not time estimates.

| Work | Priority | Scope | Main dependency |
| --- | --- | --- | --- |
| T1 Preserve drafts through their actual lifetimes | P1 | Three focused fixes | None |
| T2 Bound requests and reconcile uncertain results | P1 | Shared client request lifecycle | None |
| T3 Protect and explain private submissions | P2 | Service precondition and form state | T2 for uncertain outcomes |
| T4 Match administrative outcomes to logical events | P1 validation gap | Shared history validator | Reproduce alternate-event case first |
| T5 Complete component interaction and independent inspection | P2 | Focused decision-layer redesign | T1; coordinate with T6 |
| T6 Complete authoritative quotes and diagnostics | P2 | Selected domain and transport contracts | None; feeds T5 |
| T7 Correct accessible meaning and navigation | P2 | Shared controls and map traversal | Basic fixes independent; final review after T5 |
| T8 Remove nonchoices and retired mechanics | P2 for nonchoices; P3 for cleanup | Small domain changes by family | Replay/version review |
| T9 Produce causal receipts at the domain boundary | P2 follow-on | Bounded engine/service refactor | Behavioral fixes and parity fixtures first |
| T10 Consolidate configuration, reference, and documentation | P2 | Catalog metadata and documentation | Coordinate with T6 and T8 |
| T11 Close verification and operational evidence gaps | P2; JS corpus P3; recovery gate | Tests, CI, and recorded human/hosted checks | Runs alongside every workstream |

### T1 Preserve drafts through their actual lifetimes

**Source-confirmed gaps.** Three different lifecycle boundaries currently discard or retain the wrong
intent:

1. [HostedApp](../web/src/HostedApp.tsx#L329) clears the session and renders a loading screen on every
   `onIdTokenChanged` callback. A refreshed token for the same account therefore unmounts the game,
   losing prepared actions, viewport, inspection, and history-editor text.
2. [UrbanAscendancyQueue](../web/src/UrbanAscendancyQueue.tsx#L12) scopes preauthorization to
   game/viewer/turn, excluding the history-edit generation. An accepted edit within the same turn can
   leave the old queue able to submit into the revised history.
3. [BoardMap](../web/src/map/BoardMap.tsx#L143) clears `selected` when closing inspection through a
   background click. [GameTable](../web/src/GameTable.tsx#L166) uses that selection to mount the
   action, and [ProvinceActions](../web/src/map/ProvinceInspector.tsx#L52) owns its partial count
   locally. Clearing inspection can consequently destroy a prepared partial placement.

**Adjustment.** Preserve the mounted game across same-account credential refresh; clear private state
on identity change, sign-out, or actual authorization loss. Sequence session refreshes so old results
cannot replace current identity. Give expansion a decision-owned draft containing destination and
amount, separate from inspection/focus. Include history-edit identity in queued-action ownership,
while retaining its intended survival across ordinary actor handoff. Keep explicit cancellation and
at-most-once submission.

**Acceptance.** In production StrictMode, prepare an action and refresh the same account's token;
retain the draft, viewport, and editor text. Sign out and confirm private state clears. Switch directly
to another account, then deliver an old session-refresh response: no private snapshot, draft, queue,
or editor text may carry across identities or be restored. Prepare a partial placement from a live
offer, clear/replace inspection and poll, then commit the original amount. Queue a purchase, accept
a same-turn history edit, and later offer the purchase: no automatic
submission may occur without fresh authorization. Ordinary handoff must still submit once.

Principles: UX2, UX3, UX6, E6, E7, M3, M8.

### T2 Bound requests and reconcile uncertain results

**Source-confirmed gap with a focused request probe.**
[request](../web/src/api.ts#L207) has no deadline or cancellation, unlike `requestSnapshot`.
[Game.mutate](../web/src/App.tsx#L99) holds `busyRef` until that request settles, and polling pauses
while busy. A stalled authentication, fetch, or response body can therefore leave the table unable
to recover without reloading. Quotes, creation, and administration also use the unbounded helper.

The [dashboard](../web/src/HostedApp.tsx#L249) has two separate interval-based read loops. Their
`active` guards handle unmounting but do not prevent an older overlapping response from overwriting
newer rows or readiness. Directory reads also lack the game tab's immediate reconnect/resume recovery.

**Adjustment.** Establish a bounded, cancellable read contract for authentication, fetch, and body
consumption. Reuse the existing [polling lifecycle](../web/src/polling.ts), with the approved directory
cadence and one active read per scope. Guard publication against superseded responses.

For writes, distinguish rejection from an unknown outcome: a client timeout does not prove that the
server rejected or canceled a command. Release the stuck UI, reconcile an authorized snapshot, retain
correctable intent, and explain uncertainty. Never automatically replay a timed-out mutation. Add a
narrow operation receipt where a snapshot cannot distinguish that request from a later change; T3
addresses private writes that do not advance the public revision.

**Acceptance.** Stall authentication, fetch, and body separately. Recovery must restore reads without
duplicate writes. Test both a request that never reached the server and a committed request whose
response was lost. Delay directory/readiness responses and prove stale results cannot overwrite
current state. Verify wakeups, StrictMode cleanup, filter preservation, and readiness independent of
the displayed page/filter.

Principles: UX3, UX6, UX10, M5, M8.

### T3 Protect and explain private submissions

**Conflict-contract gap.** [GameService](../server/src/main/kotlin/dev/aragos/aor/GameService.kt#L194)
checks the public revision, while private replacements intentionally retain that revision.
[Withdrawal](../server/src/main/kotlin/dev/aragos/aor/GameDriver.kt#L150) removes the current saved
value. Two tabs for one seat can therefore overwrite or withdraw newer private work without a stale
intent conflict. Existing row locking protects atomicity and different players' submissions; it does
not establish which version of the caller's own submission they reviewed.

**Recommended policy.** Reject stale same-seat private writes. Add an opaque owner-and-decision-specific
submission version, checked inside the existing transaction, advancing on save, replacement, and
withdrawal, including a change away from and back to the same amount. Keep the public revision guard.
Do not expose the global storage generation or make unrelated players' private saves conflict.
Document this explicitly; current same-seat behavior is effectively last-writer-wins.

**Feedback gap.** [App](../web/src/App.tsx#L103) retains a generic `saved` flag until another mutation.
[GameTable](../web/src/GameTable.tsx#L174) continues to show Saved after the player edits that amount
or withdraws the submission. Scope success to the completed operation and distinguish, for example,
“Saved privately: 7” from “Unsaved change: 9” and an unsaved draft after withdrawal.

**Acceptance.** Tab A replaces 12 with 17; a stale withdrawal or replacement from tab B must preserve
17 and report a typed conflict. Different seats must still submit concurrently. Exercise restart,
independent server pools, withdrawal versus final reveal, and timeout reconciliation. Compare other
viewers' responses to ensure the new guard discloses no private replacement. Save/edit/withdraw/reload
must clearly distinguish local, saved, and committed values.

Principles: UX3, UX10, E7, E8, M3, M4, M5.

### T4 Match administrative outcomes to logical events

**Validator asymmetry, requiring a regression scenario.**
[Administrative replacement](../server/src/main/kotlin/dev/aragos/aor/GameAdministration.kt#L179)
compares the ordered `!` outcome lines and validates candidate replay.
[Ordinary history edits](../server/src/main/kotlin/dev/aragos/aor/HistoryEdits.kt#L288) additionally
match logical requests, including competition destination/defenders, trade participants, and War
context. Identical unassigned dice can be valid input to different contests; byte equality alone
does not establish the stronger identity guarantee.

**Adjustment.** First construct a legal alternate-contest history with unchanged outcome bytes to
demonstrate the exact administrative acceptance case. Share logical-outcome validation between both
edit paths when the source is supported and replayable. Retain the separate administrative authority,
dry run, archive, and atomic checkpoint behavior. For incompatible source versions, require an
explicit validated event mapping or reject an upgrade whose identity continuity cannot be established;
do not introduce automatic legacy decoding.

**Acceptance.** Both paths reject rebinding the same dice to another destination, victim, turn, or War
event. Harmless transformations preserving requests still validate. Rejection leaves active history,
archives, membership, lifecycle, and pending work unchanged. Validation remains read-only.

Principles: E3, M6, M7, M10. The code asymmetry is established; the alternate-history scenario was not
executed in this review.

### T5 Complete component interaction and independent inspection

**Observed inconsistency.** Supported discard, trade offer, patronage claim, Renaissance swap, and
adjustment cancellation are routed through command-label lists in
[GameActions](../web/src/GameActions.tsx#L20). Existing hand cards, leader displays, player panels, and
market components do not bind those choices. For example, clicking a card during Master Art discard
inspects it rather than selecting the corresponding discard action.

Conversely, [actionable cards and advances](../web/src/TablePanels.tsx#L141) always replace the active
selection when clicked. An [eligible player summary](../web/src/TablePanels.tsx#L62) changes the card
target instead of opening details. A consistent inspect-only path is missing precisely when those
objects are also actionable.

**Target design.** Give each supported decision a small typed description connecting its subjects,
eligible targets, limits, quote, and canonical command. Bind existing components and finite-choice
alternatives to one draft. Keep the game's specific decision forms where they add clarity.

| Responsibility | Contract |
| --- | --- |
| Domain offer | Supplies action kind, subject/target references, legal bounds, blockers, and consequences. |
| Decision draft | Owns selected action, targets/amounts, decision identity, and edit invalidation. |
| Component binding | Selects the same draft through hand, leader, player/order, market, or board objects. |
| Inspection | Opens reference through a distinct, discoverable pointer/touch/keyboard affordance. |
| Review and commit | Shows the draft's actual consequences and sends its canonical command once. |

Implement discard/trade hand selection first, then patronage, order swap, and market cancellation.
Use the existing action panel and inspector. A small inspection control or consistent explicit mode
should preserve direct selection; do not require hovering, modifier keys, or a menu for ordinary play.
Keep visible finite choices as an accessible equivalent, not a competing source of selection state.

**Acceptance.** For every supported family, a component and its alternative control prepare the same
draft and command. Prepare A, inspect actionable B/player/province, close inspection, and commit A
unchanged. Intentionally selecting B replaces A. Live engine offers must drive the fixtures. Retired
`return-card`, target-selecting `written-record`, and manual `adjust-market` are cleanup targets in
T8, not production features to redesign.

Principles: UX1, UX2, UX3, UX4, UX6, UX7, E1, E4, E7.

### T6 Complete authoritative quotes and diagnostics

**Observed gaps.** [Patronize confirmation](../web/src/GameActions.tsx#L41) names the leader without
the actual protection-money payment; relief offers show spaces/cash/rating without resulting cash
and misery boxes. [Card action review](../web/src/ObjectDecisions.tsx#L120) projects misery but lacks
equivalent deterministic cash transfers for such effects as Alchemist's Gold and leader payments.
The richer existing token, advance, and Civil War reviews provide the pattern to reuse.

Choice generation and execution also repeat smaller rule decisions: optional card purchases in
[TurnEngine](../core/src/commonMain/kotlin/dev/aragos/aor/TurnEngine.kt#L90) and
[TurnCommand](../core/src/commonMain/kotlin/dev/aragos/aor/TurnCommand.kt#L88), adjustment grouping and
cost, shipping availability, and relief enumeration. This is duplicated ownership; no incorrect
numeric quote was demonstrated here.

**Adjustment.** Finish small quote/evaluation operations with their existing rule owners. Return
eligibility, bounds, costs, payer/recipient, resulting balances, and conditional effects; consume
them in choices, execution, previews, and the waiting purchase offer. Preserve inspectable blocked
options and feasible partial actions. Keep approved commodity ownership forecasts; any “if played
now” quote is a separately labeled execution projection.

Unify diagnostic structure across [RuleViolation](../core/src/commonMain/kotlin/dev/aragos/aor/RuleViolation.kt#L49)
and [CardRuleViolation](../core/src/commonMain/kotlin/dev/aragos/aor/CardEffects.kt#L21).
The [service](../server/src/main/kotlin/dev/aragos/aor/GameService.kt#L226) currently retains only the
card failure code. Carry stable codes, typed permitted entity references, and required/available
parameters through rejection, preview, and history-edit errors. Localize at presentation boundaries;
do not expose arbitrary internal explanations or hidden card identities.

**Acceptance.** Every offered executable choice is accepted in its source state, and its displayed
cost/endpoints equal execution and activity. Cover shared Gold/Ivory cancellation, blocked shipping,
relief with no affordable spaces, Patronage, and a cash-effect card. Equivalent viewer-visible states
produce equivalent diagnostics/previews. Unknown input must not become a trusted object label.

Principles: UX4, UX5, E1, E8, E10.

### T7 Correct accessible meaning and navigation

**Source-confirmed defects.** [BoardMap](../web/src/map/BoardMap.tsx#L327) styles multi-target selection
from `picker.selected` but sets `aria-pressed` from the inspected province. Several selected targets
can be announced as unselected, and a deselected last-inspected target can remain announced pressed.
[ChoiceButtons](../web/src/DecisionControls.tsx#L30) creates an unnamed group; labels elsewhere target
its non-labelable `div`. Repeated Enabled/Disabled controls in
[Variants](../web/src/Variants.tsx#L23) lack a programmatically associated question.

**Adjustment.** Use draft membership for action selection semantics and represent inspection
separately. Give finite groups a legend or explicit accessible name and appropriate single/multiple
selection semantics. Check target eligibility and capacity announcements. These fixes can land before
the wider decision redesign.

Keyboard activation already exists, but all provinces/areas enter the tab sequence and focus does
not pan a zoomed map to an offscreen object. Verify actual sequential navigation, then implement a
consistent target-traversal/roving-focus strategy with visible focus and a direct path to confirmation.
Retain finite alternatives. Complete the existing screen-reader, physical-phone, browser zoom, and
SVG/composited contrast reviews; their open status is a verification gap, not proof of universal failure.

**Acceptance.** Inspect the accessibility tree for named questions, eligibility, and exact multi-target
membership after select/deselect. Complete setup and a multi-target action using real keyboard
navigation at Fit and zoomed scales, without tests jumping directly to a target with `.focus()`.
Inspection, scrolling, and narrow layouts must leave commitment reachable.

Principles: UX2, UX8, UX9.

### T8 Remove nonchoices and retired mechanics

**Concrete nonchoice.** [Phase opening](../core/src/commonMain/kotlin/dev/aragos/aor/TurnEngine.kt#L289)
queues every unused active Renaissance owner, while
[Renaissance choices](../core/src/commonMain/kotlin/dev/aragos/aor/TurnFlow.kt#L92) may contain only
Pass when no eligible adjacent opponent exists. Use one target calculation for execution, offers,
and readiness. Reevaluate when each window becomes active because earlier swaps can alter adjacency.
Skip only publicly knowable nonchoices; preserve blocked relief and information-sensitive windows.

Apply the replay/version gate to this readiness change as well as retired-rule removal. Existing
supported logs can contain a `pass` for an empty Renaissance window; skipping the window can reject
that entry or silently interpret it as a later phase's pass. Add an affected-history regression before
changing normalization. If continuation changes, require explicit rules-version and validated upgrade
treatment, with T4's event-identity checks in place before transforming recorded histories.

**Maintenance debt.** [RuleOptions](../core/src/commonMain/kotlin/dev/aragos/aor/RuleOptions.kt),
[SetupDeal](../core/src/commonMain/kotlin/dev/aragos/aor/SetupDeal.kt), and
[VariantInteractions](../core/src/commonMain/kotlin/dev/aragos/aor/VariantInteractions.kt) retain
fixed pseudo-options and unreachable return-card, reserve, Written Record trade, and market-adjustment
branches. Related parser, DTO, frontend, and fixture cases make the active system appear broader than
the supported catalog. Current creation already rejects retired options.

**Adjustment.** Remove one proven-unreachable family at a time across domain, parser, projections,
UI, tests, and documentation. Preserve baseline behavior and all four supported option dimensions.
Review saved-log and version consequences before removal; use the declared explicit upgrade policy
if necessary. Do not weaken valid coverage just to make cleanup pass.

**Acceptance.** Renaissance with no target reaches the next real input without a pass; a valid target
still produces a choice and automatic bookkeeping occurs once. Supported maps/player counts/options
retain their results. Retired syntax fails at the intended typed boundary. Current supported logs
replay unchanged, or any required version transition is explicit and validated.

Principles: UX4, E1, E4, E5, E6, E9, M10.

### T9 Produce causal receipts at the domain boundary

**Architectural risk, not a demonstrated current history error.**
[HistoryEvents](../server/src/main/kotlin/dev/aragos/aor/HistoryEvents.kt#L206) reapplies an entry and
recognizes normalization phases to separate direct and automatic effects.
[MiseryHistory](../server/src/main/kotlin/dev/aragos/aor/MiseryHistory.kt#L50) reconstructs causes from
commands, decision frames, and known phase types. A future rule change therefore needs coordinated
domain knowledge in the service to explain its effects correctly.

**Adjustment.** Extend the deterministic transition boundary with small typed receipts produced by
the owners of direct and automatic effects: cause, affected entity, audience, and actual resource
endpoints. Start with automatic bookkeeping and deferred effects. Derive receipts during replay;
keep canonical commands/outcomes as the sole persisted game truth. The service should authorize,
redact, and group receipts rather than rediscover rule causality. Remove redundant reapplication only
after parity is demonstrated.

**Acceptance.** Existing representative histories retain entry-time identities and equivalent
activity. Offsetting direct/automatic changes remain separate even when the net response is zero.
Clamping and Chaos preserve actual endpoints. Adding a new automatic effect supplies its cause at
the domain owner without another service phase heuristic. Hidden-state equivalence and append
stability tests must continue to pass.

Principles: E1, E6, E10, M7. Schedule after behavioral fixes; preserve the substantial existing receipt
regressions throughout.

### T10 Consolidate configuration, reference, and documentation

**Observed duplication.** Supported option names, values, and defaults are independently listed in
[Variants](../web/src/Variants.tsx#L5), domain configuration, and
[ReferencePage](../web/src/ReferencePage.tsx#L18). Much of the reference page and some help labels are
literal English despite the [localization contract](design.md#7-ui-and-localization).

**Adjustment.** Expose public supported-option metadata from its domain owner and use it for creation,
validation, and reference structure. Keep prose and translation resources in presentation code;
keep mechanical definitions and prices in the catalog/domain. One source per fact does not require
moving explanatory prose into the engine. Complete localization keys for these surfaces without
changing locale-independent commands or adding new supported variants.

Correct known documentation drift as its owning changes land:

- [engine.md](engine.md#rule-sensitive-boundaries) says to skip relief without a legal purchase,
  while approved requirements and current code retain the step for tier owners even when blocked.
- [commands.md](commands.md#authority-outcomes-and-replay) still describes retired setup-return/reserve shuffle
  uses; reconcile these with T8 and verify the final command-language reference against reachable flow.
- [Decision coverage](ui-decision-coverage.md) mixes some retired rendering fixtures with supported
  flows. Distinguish live supported evidence, isolated presentation evidence, and removed mechanics.

**Acceptance.** Every supported option/default and reference entry agrees with the authoritative
catalog. Public help fetches no private state. User-visible non-command text uses the presentation
message system. Requirements describe accepted behavior; UI contracts describe reusable interaction;
coverage records observed evidence; follow-ups retain unresolved operational work. Update those owners
when implementation is accepted, rather than making this proposal a second permanent specification.

Principles: UX7, UX8, E4, E9, M2.

### T11 Close verification and operational evidence gaps

**Extend evidence around the actual risks.** Existing tests are substantial, but their scope matters:

- [SupportedGameCompletionTest](../core/src/jvmTest/kotlin/dev/aragos/aor/SupportedGameCompletionTest.kt)
  proves completion and replay for 3–6 players. Its
  [Scenario driver](../core/src/jvmTest/kotlin/dev/aragos/aor/Scenario.kt#L126) passes expansion and
  several purchase decisions. Add curated histories that attack, lose, replace exhausted markers,
  enter Chaos, and cross epochs with pending decisions. Assert conservation after each accepted entry,
  counting pending competition resources exactly once, using a test helper rather than new repeated
  whole-state checks in production adapters.
- Add offer/execution and viewer-equivalence checks to T6/T9. Keep fixtures readable and test one
  behavior at a time. Use real engine offers and production StrictMode for lifecycle regressions;
  fabricated legal-command arrays cannot prove a reachable game flow.
- All current engine test files are under `jvmTest`; [CI](../.github/workflows/checks.yml#L59) only
  compiles Kotlin/JS. Add a small `commonTest` replay/codec/ordering corpus executed on both targets
  to substantiate the documented JVM/JS equality contract. This does not require client replay.
- Make [geometry validation](../web/scripts/validate-map.py) for both resolved map catalogs an
  explicit CI check. Current CI runs rendered geometry/browser checks but does not invoke this
  independent topology validator. Keep both forms of evidence.
- Complete the human accessibility checks in T7 and measure representative six-player,
  conflict-heavy late games before considering transport, rendering, or cache changes. The existing
  local low-conflict benchmark does not establish production capacity.

**Complete the existing operational runbook.**
[Follow-ups](follow-ups.md#needed-before-public-launch) already require real identity flows,
three-account privacy, restart, backup restore, release protection, and capacity checks.
[Deployment](deployment.md) correctly distinguishes full database recovery from log export. The
[container smoke test](../scripts/check-container.py#L15) uses local authentication and checks startup
and persistence; it cannot establish hosted Firebase or backup recovery behavior.

Restore a complete private backup into an isolated database and verify accounts, permissions,
memberships, pending secrets, closed games, history, exact archives, and audit receipts. Reconnect
using the original identity provider and rehearse cutover with only one writable source. Record
revision/schema, scenarios, timing, and results without credentials or private game contents. Agree
and record backup retention and verify the existing release controls. This work needs deployment
access during implementation, not a new storage design.

**Acceptance.** Checks demonstrate their named invariants and reachable decision paths on the
applicable targets/backends. Hosted users retain their seats and private audiences after restore;
pending work survives without new randomness. Failed restore validation prevents cutover. Record
human and hosted results separately from local automation in their existing documentation owners.

Principles: UX8, UX9, E1–E10, M1, M4–M10.

## Delivery sequence

1. **Protect existing work.** Land T1 as separate auth, expansion-draft, and queue-lifetime fixes with
   regressions. In parallel, land T2 request recovery and ordered portal reads, plus T7's incorrect
   group/selection semantics. Add the T4 administrative counterexample and close its validator gap.
2. **Make commitments consistent.** Implement T3's owner-specific private precondition and explicit
   draft/saved status. Develop T6's supported offer/diagnostic contracts, then migrate T5 one family
   at a time. Existing components and canonical command paths remain usable throughout.
3. **Reduce duplicated domain knowledge.** Skip empty Renaissance windows, consolidate option/reference
   metadata and documentation, and remove retired rule families individually. Introduce T9's typed
   receipts with history parity tests, starting with automatic effects.
4. **Close the evidence.** Run focused checks per slice and integrated checks at each boundary change.
   Complete T7's full interaction review and T11's cross-target, topology, conflict-heavy game, and
   hosted recovery work. Retire completed proposal items by updating their contract/evidence owners.

Frontend, domain-offer, and service changes can proceed in parallel once the small shared contracts
are agreed. Serialize Gradle builds within a checkout, or use isolated worktrees. Protect reviewed
rules, private audiences, optimistic concurrency, canonical replay, and entry-time activity in every
slice. Full automation for a hypothetical future feature is not an acceptance condition here.

## Coverage of all 30 principles

“Preserve” identifies an established foundation; linked work closes the observed gap or strengthens
its evidence. This matrix is a review disposition, not a certification of every possible game state.

| Principle | Disposition |
| --- | --- |
| UX1 Components as controls | T5 extends the existing pattern to remaining supported families. |
| UX2 Inspection, preparation, commitment | T1 separates expansion state; T5 adds inspect-only access; T7 corrects semantics. |
| UX3 Draft lifetimes | T1 fixes identity/edit/inspection boundaries; T2–T3 cover network and private conflicts. |
| UX4 Meaningful choices and defaults | Preserve current defaults and blocked relief; T5–T6 improve consistency; T8 skips empty windows. |
| UX5 Local consequences | T6 completes actual costs, recipients, endpoints, and contextual rejections. |
| UX6 Decision-centered attention | Preserve actor/responder context; T1–T2 prevent disruptive refresh; T5 unifies selection. |
| UX7 Compact information homes | Preserve table/reference hierarchy; T5 avoids detached duplicate choices; T10 consolidates facts. |
| UX8 Visual meaning and accessibility | T7 fixes semantic defects and navigation; T10 localizes remaining surfaces; T11 records human evidence. |
| UX9 Faithful spatial presentation | Preserve reviewed graph/drawing; T7 checks zoomed keyboard use; T11 adds explicit validator gates. |
| UX10 Outcome feedback and lifetime | Preserve scoped result notices; T2 explains uncertainty; T3 distinguishes saved and unsaved work. |
| E1 Authoritative rules | Preserve existing shared calculations; T6 consolidates remaining offers; T9 removes causal duplication. |
| E2 Deterministic transitions | Preserve pure replay; T11 tests invariants and JVM/JS agreement. |
| E3 Chance bound to causes | Preserve ordinary replay/edit validation; T4 aligns administrative proof. |
| E4 Canonical actions | Preserve text language; T5 binds components to it; T8/T10 remove retired syntax/documentation. |
| E5 Resumable flow | Preserve decision frames; T8 removes only public nonchoices; T11 exercises interruptions. |
| E6 Ownership and invariants | Preserve strong values and marker provenance; T1 fixes queue/draft owners; T9/T11 strengthen effects/invariants. |
| E7 Decision, activity, revision identity | Preserve engine identities; T1 uses edit identity correctly; T3 adds private-write freshness. |
| E8 Viewer knowledge | Preserve privacy/uncertainty projections; T3/T6/T9 include viewer-equivalence checks. |
| E9 Explicit catalogs and rulings | Preserve reviewed definitions; T8 removes impossible alternatives; T10 shares option metadata. |
| E10 Explainable failures and effects | T6 carries diagnostic context; T9 produces causal receipts at the owner. |
| M1 Identity and authority | Preserve accounts/seats/roles; T1 handles refresh versus identity change; T11 verifies hosted behavior. |
| M2 Recorded game contract | Preserve frozen resolved setup; T10 aligns creation and reference metadata. |
| M3 Commitment lifecycles | T1 invalidates queues correctly; T3 distinguishes draft, pending, and committed state. |
| M4 Atomic simultaneous reveal | Preserve current transactions; T3/T11 add same-seat and failure interaction checks. |
| M5 Stale intent and atomic publication | Preserve public guards/storage generation; T2–T3 close uncertain/private cases. |
| M6 Agreed audited undo | Preserve scopes and retained answers; T4 shares semantic chance validation. |
| M7 Historical truth and audience | Preserve append stability; T4 validates event identity; T9 reduces receipt coupling. |
| M8 Easy resumption | T1 preserves work across credential refresh; T2 orders portal reads and recovers stalled work. |
| M9 Operational lifecycle | Preserve separate cancellation/abandonment/purge and audit; T11 verifies recovery of those records. |
| M10 Compatibility and recovery | Preserve explicit upgrades/archives; T4 tightens proof; T8 respects versions; T11 rehearses full recovery. |

## Review evidence and limits

The review covered core rules/flow/projections/catalogs, server authorization/storage/history/admin,
all major browser surfaces, test drivers and fixtures, CI/deployment tooling, and the documentation
owners. Findings identify source-confirmed behavior, design debt, contract choices, and missing
evidence separately.

During this review, `npm test --workspace web` passed all 34 tests. A temporary in-memory probe of
the actual transpiled `api.ts` confirmed that a stalled mutation schedules no deadline and passes no
abort signal, while a snapshot read schedules its 15-second deadline and rejects when canceled.
No browser regression, full Kotlin suite, live hosted rehearsal, or alternate-contest administrative
fixture was executed for this proposal. Existing passing coverage is evidence for its exercised
scenarios, not evidence that the newly identified combinations are already protected.
