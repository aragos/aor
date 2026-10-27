# UX and UI principles

The product is a visual game table for Age of Renaissance. A player should understand the position,
identify the next meaningful decision, and act with confidence. Every supported player decision must be
available through both visual controls and the command interface, on desktop and narrow screens.

This document owns application-wide interaction requirements. [Map design](map-design.md) adds
spatial contracts; [application design](design.md) owns architecture; [rules](rules.md) owns mechanics.
[Coverage](ui-decision-coverage.md) records evidence, and [next steps](ux-next-steps.md) proposes work.

## Interaction rules

1. **Present authoritative facts.** The engine owns legality, commitments, prices, eligibility and
   effects. Render typed, viewer-safe projections and submit canonical commands through the same
   authorization and revision checks as text input. Geometry, English prose and log text cannot
   supply game rules. Preserve private audiences in previews, readiness and history.

2. **Ask only meaningful questions.** The engine advances past bookkeeping with no effect or legal
   decision. Prepare a sole legal option and sensible numeric defaults without an extra selection
   step. Defaults edit a draft; an explicit, accurately named action commits it. Never hide a real
   tradeoff or silently choose an uncertain outcome.

3. **Organize attention around the decision.** Show the actor, requested choice and relevant
   resources beside the objects involved. Open the relevant workspace once at the decision boundary.
   Keep the primary action reachable while inspecting or scrolling. Interruptions identify who must
   answer and which activity resumes. Hide resources and actions outside their meaningful lifetime;
   an available value of zero differs from an unavailable value.

4. **Act through recognizable objects.** Cards, advances, players, provinces and areas provide their
   own inspection and target selection. Keep inspection, eligibility, a prepared selection and
   commitment visibly distinct. A blocked object may remain inspectable; explain its actual blocker
   when that helps planning. Put shared requirements on the group that owns them.

5. **Make action lifetimes predictable.** Polling and workspace navigation preserve valid drafts,
   inspection and viewport. A phase or purchase-step change clears obsolete selections, target
   pickers and entity focus. A successful pass or card play closes its action context; a rejection
   retains correctable input. Revalidate changed choices, identify invalidated selections and block
   stale submission. Automatic updates do not repeatedly redirect navigation.

6. **Explain consequences where they matter.** Show costs, commitments, modifiers, remaining
   resources and deterministic effects before commitment. Distinguish current, projected,
   conditional and uncertain values, with their timing and units. Reference text stays readable on
   cards and in detail. Discounts, final prices and cash gains are different quantities. Offer a
   useful legal alternative when the usual action is blocked.

7. **Use one visual language.** Capital names identify powers; before capital selection, use player
   names. Player overviews also retain the human name. Reuse entity symbols, owner silhouettes,
   colors, quantity formats and terminology across every surface. Color always has another cue.
   Emphasis reflects semantic priority rather than rendering order, and covers the whole object.

8. **Keep summaries small and explanations available.** Give each fact a predictable home and show
   decision-relevant state at rest. Put breakdowns and full tracks in detail; repeat a value only for
   a useful local comparison. Keep consequential limits and required choices visible when detail is
   closed. Remove repeated structure and instructional filler before reducing type size. Ordinary
   play uses localized names; command syntax and internal identifiers belong in technical views.

9. **Close the feedback loop.** Make acceptance, rejection, waiting and private submission states
   distinguishable. Present consequential outcomes and recorded rolls near the action as well as in
   history. Preserve an outcome until dismissed or replaced by the next relevant result. Announce
   changes without stealing focus; an unrelated update must not erase useful feedback.

10. **Make complete flows accessible.** Every action works with keyboard, touch and pointer, with a
    meaningful accessible name, visible focus and readable contrast. Information cannot depend on
    hover or color alone. Support narrow screens, large hands, six players, browser zoom and reduced
    motion. Compact layouts retain a reachable commit action and preserve the player's work.

## Acceptance

Review a complete decision: discover it, inspect options, prepare, commit, and understand the result.
Include blocked actions, waiting, interruptions, errors and return to play. Verify these contracts at
phase boundaries and during polling, rejection and stale revision recovery.

Use domain tests for calculations, service tests for authorization, secrecy and persistence, and
browser checks for actual interaction and rendered geometry. Fixture evidence establishes only what
it exercises. Record manual accessibility and legibility checks separately from automated coverage.
Keep the [decision coverage inventory](ui-decision-coverage.md) aligned with observed evidence.

The [interactive design studies](ui-proposals/index.html) preserve the accepted visual direction.
They contain illustrative state; current principles, rules and production behavior govern changes.

## Map decisions and expansion feedback

A location decision opens the map and activates its legal targets immediately. Shade the whole
province, including its coastal water, or the whole eligible area; retain outlines as an additional
cue. A map click prepares the choice and the decision's action button confirms it. Finite choices
use visible buttons. Playable hand cards also appear beside Pass in the decision panel.

For peaceful expansion, default to a full market commitment when legal. If shipping or the pool
limits that commitment, default to the largest partial placement offered by the engine. Competition
commitments retain their own rules. Voluntary marker reduction is a collapsed secondary action.
All competition results from the current turn remain available until expansion ends, including
results involving other players; the activity history retains them afterward.
