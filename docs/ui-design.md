# UX and UI principles

AoR is a visual game table. Players must understand the position, find the next decision, prepare a
choice and understand its result through visual controls and commands, on desktop and narrow screens.
[Map design](map-design.md) owns spatial contracts, [rules](rules.md) mechanics and
[design](design.md) architecture. [Coverage](ui-decision-coverage.md) records evidence;
[UX review](ux-next-steps.md) records remaining work.

## Interaction rules

1. **Render authoritative facts.** Use typed, viewer-safe engine projections for legality, prices,
   targets, commitments and effects. Visual controls submit canonical commands through the same
   authorization and revision checks as text input. Preserve private audiences in every preview.
2. **Keep choices meaningful.** Resolve setup during creation and skip empty bookkeeping. Prepare
   sole legal choices and useful numeric defaults without committing them. Retain blocked relief as
   an explicit decision for players with an advance tier, with its explanation and Pass.
3. **Organize attention around action.** Show actor, request and relevant resources together. Open
   the relevant workspace once per decision boundary; polling must not repeatedly redirect it.
   Interruptions name the required responder and the activity that resumes.
4. **Separate reference from action.** Cards, advances, provinces and players remain inspectable in
   a dedicated surface. Inspecting another object must preserve the active form, prepared targets
   and picker. Inspection, eligibility, draft selection and commitment have distinct cues.
5. **Define draft lifetimes.** Polling and navigation preserve valid drafts and viewport. Replaced
   decisions and accepted history edits clear obsolete selections. Successful pass/card play closes
   its action context; rejection retains correctable input. Mark invalid choices and prevent stale
   submission. A preauthorized queued action submits once only when legal, never retries a failure.
6. **Explain consequences locally.** Show costs, modifiers, remaining resources and deterministic
   effects before commitment. Distinguish current, projected, conditional and uncertain values;
   discounts, final prices and cash receipts are different quantities. Offer feasible alternatives.
7. **Use consistent identity and emphasis.** Capital names identify powers; before assignment use
   human names, retained in player overviews. Reuse symbols, owner silhouettes, quantities and terms.
   Dice state their colors. Wins/losses have text and green/red styling; color alone conveys nothing.
8. **Keep summaries compact.** Give each fact a predictable home, with decision-relevant state at
   rest and full tracks/breakdowns in optional detail. Remove repeated structure before shrinking
   text. Localize ordinary play; command syntax and internal IDs belong in technical views.
9. **Show consequential feedback.** Distinguish accepted, rejected, waiting and private-submission
   states. Keep results until dismissed or their scope ends; dismissing results never deletes
   history. Activity opens by default, explains effects and excludes continuation noise. Announce
   updates without taking focus or erasing unrelated feedback.
10. **Verify complete accessible flows.** Keyboard, touch and pointer actions need meaningful names,
    visible focus and readable contrast. Support six players, large hands, zoom, reduced motion and
    narrow screens. Keep the commit action reachable while inspecting and scrolling.

## Current table contracts

- Hosted player search, selected participants and administrator player lists show display names and
  public handles. Account UIDs remain internal to selection and requests.
- The header uses **AoR**; page titles include the assigned capital, or the player name during setup.
  Remove the redundant Board details expander; province facts remain in inspection.
- Location decisions activate map targets immediately, including Crusades, Rebellion and
  Pirates/Vikings. Shade the whole province/coast or eligible area. A map click edits the draft;
  the action button confirms it. Playable cards also appear beside Pass.
- Full-market placement is primary when legal. Otherwise use the largest engine-offered partial
  placement and label it **Place maximum possible tokens**. Omit destinations with no feasible
  pool/shipping commitment. Keep marker reduction collapsed and competition commitments exact.
- Expansion summaries show each player's white markers and the actor's military cards with
  matchup caveats. Competition results persist through expansion unless cleared; new results
  still appear, and the activity history remains intact.
- Commodity cards show shortage/surplus badges. Markets and selected commodity cards offer
  full payout boxes, collapsed by default, while preserving current owner payouts and overflow rules.
- Advances show matching leaders not publicly played, grouped by printed epoch. Held/future cards
  and private discards stay counted; this cannot promise remaining drawable cards.
- Cancellation choices and confirmation show cost. Stabilization shows its resulting misery box
  or Chaos. Relief remains visible for an advance tier even when blocked, with Pass.
- Urban Ascendancy may be queued privately while waiting. The cancelable draft requires the view
  to remain open, clears on expiry and submits once when offered; it does not auto-pass Master Art.
- Activity opens in its own sidebar outside the action panel, visible before scrolling on desktop.
  It explains draw reasons, epoch/end triggers, protection money, leader rebates, related cash
  changes and shortage/surplus dice. All displayed dice identify their colors.
- Command history defaults to capital aliases, with saved display-name/ID alternatives. Aliases
  cover actors, targets, attributed dice and edit authors; stored commands and `@entry` references
  remain canonical.

## Acceptance evidence

Review discovery, inspection, preparation, commitment and outcome together, including waiting,
interruptions, blockers, rejection and stale revisions. Domain tests own calculations; service tests
own authorization, privacy and persistence; browser tests own interaction and rendered geometry.
Fixtures prove only what they exercise. Record human accessibility/legibility findings separately.
The [design studies](ui-proposals/index.html) illustrate visual direction; current contracts govern.
