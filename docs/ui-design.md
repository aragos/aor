# UI contributor guide

AoR is a visual game table: recognizable objects carry inspection and choices, with consequences
beside confirmation. The server supplies viewer-safe decisions and quotes; [rules](rules.md) owns
mechanics and [map design](map-design.md) owns spatial rendering.

## Find the implementation

| Concern | Client files under [web/src](../web/src/) |
| --- | --- |
| Table/workspace layout | `GameTable.tsx`, `TablePanels.tsx`, `app.css` |
| Decision identity and draft scopes | `decision.ts`, `decisionOffers.ts`, `DecisionControls.tsx` |
| Object selection and commitment | `ObjectDecisions.tsx`, `DecisionActions.tsx`, `GameActions.tsx` |
| Cards, advances and reference | `Hand.tsx`, `AdvanceInspection.tsx`, `CardInspectionDialog.tsx`, `ReferencePopup.tsx` |
| Effects and prices | `CardPlayEffects.tsx`, `MiseryPreview.tsx`, `AdvancePrice.tsx` |
| History, plans and abandonment | `Activity.tsx`, `HistoryEditor.tsx`, `RewindReview.tsx`, `ActivityPlanner.tsx`, `Abandonment.tsx` |
| Auth, polling and requests | `HostedApp.tsx`, `api.ts`, `polling.ts`, `favicon.ts` |
| Identity, text and preferences | `IdentityLabel.tsx`, `messages.ts`, `labels.ts`, `preferences.ts` |

## Interaction contracts

- Render authoritative costs, destinations, limits, blockers and resource endpoints. Controls and
  text commands share validation. Never derive legality by parsing messages or recomputing rules.
  Prepare useful defaults and sole choices, but commit only through an explicit action.
- Keep inspection, eligibility, draft selection and commitment distinct. Cards, advances, players
  and provinces are their own controls. Reference popups restore focus and preserve prepared work.
  Tapping an expanded player box again closes its details.
  Load reference content inside the dialog so the first lookup cannot change the page's scroll area.
  Cathedral's advance details show public owner/non-owner usage pairs for the current game turn;
  accepted updates and history changes refresh the tracker, and the next game turn resets it.
  Help owns printed lookup data, variants and non-obvious app behavior; card/advance details stay
  with their objects. Avoid parallel inspectors, repeated panels and rule walkthroughs in Help.
- Show the actor, question, relevant resources and one waiting status beside the action. Open the
  relevant workspace once per decision. Interruptions name the responder and resumed activity.
  Cathedral interruptions identify the contested province beside the decision title.
  Keep confirmation reachable while the review scrolls; retain map/hand space on narrow screens.
  On phones, let the action area collapse to its title and toggle, returning space to the active
  workspace without discarding drafts. Polling and navigation preserve this choice; a new decision
  or object selection reopens the actions.
  The phone icon rail ends at the workspace; the action area below spans the whole table width.
  Keep Hide/Show actions the same height as its neighboring Undo control, with a text label.
  Keep the decision title beside Undo above the status. Player summaries show the printed order box
  beside pool/submission status; the reference owns the full position-to-box mapping.
- Show deterministic effects inline before commitment: prices, named discounts, cash/misery
  endpoints, current → new shipping and limiting pool/shipping resources. Use one body text size
  for related purchase descriptions and resource consequences. Distinguish forecasts, conditions
  and unknown dice results. A blocked choice explains its authoritative cause and preserves legal
  alternatives. Purchase steps and Pass identify their actual continuation.
- Reuse capital silhouettes with every visible capital name, including previews, references and
  receipts; use public handles before assignment. Use `CapitalLabel` for capital/name pairs and
  `LabelMessage` for translated sentences. Keep names and surrounding copy on the same text baseline;
  text rows using flex layout must align by baseline, with symbols centered inside their labels.
  [Identity browser checks](../web/tests/identityAlignment.browser.mjs) measure text alignment across
  font sizes and desktop/phone widths and run in the CI browser suite. Retain numeric/text labels
  alongside color.
  Misery uses track order from green through amber/red to dark Chaos. Dice identify their colors.
- Keep the hand available for card decisions. A card click prepares or toggles selection; dragging
  the hand does not select or submit. Commodity inspection highlights matching in-play provinces
  without replacing a draft. Track popups follow accepted public updates and retain shared positions.
  Commodity cards have one payout help control; shared cards switch commodities inside that popup.
  Explain voided cards beside the action; warn before a legal no-target play consumes a card with
  no effect. The warning comes from the projected card resolution and follows live updates.
- Keep zoom, Fit and map help beside the workspace tabs, outside the map. Hide them when the bar
  cannot fit them, including a narrow bar beside pinned Activity. Gestures and keyboard map controls
  remain available. Explanatory copy describes current actions and consequences, not past UI versions.
- Public summaries use public facts only. Unplayed-card counts cannot reveal private discards.
  The epoch's remaining-card lookup lists introduced cards not publicly played, including hands
  and private discards. Recurring plays reset on the next epoch's shuffle; square plays stay excluded.
  This list is distinct from the draw-pile size shown in the header.
  Live forecasts are distinct from actual payments; for example white circles affect current
  commodity forecasts, while payments retain their colored-marker rule.
- Support keyboard, touch, visible focus, reduced motion and phone layouts. Every finite choice
  group has a named question. Roving map focus must allow Tab to reach confirmation. Preferences
  use browser storage with an in-memory fallback. Localize ordinary text; canonical syntax stays
  locale-independent.

## Draft and result lifetimes

Scope decisions by game, viewer and `activity.decisionEntry`, separately from revision. Polling,
private readiness, navigation, map gestures and inspection preserve valid drafts. A replaced decision
or accepted history edit clears obsolete form state even if actor/prompt text is unchanged. Successful
placement clears its province/amount; pass/card play closes its action context. Rejection retains
correctable input. Stale requests refresh without automatic resubmission.

Same-account credential refresh preserves the mounted table, viewport, inspector and editor.
Sign-out, identity change and authorization loss clear private work; superseded results cannot
restore it. Bound authentication, fetch and response bodies to 15 seconds. An uncertain mutation
releases the UI and reconciles an authorized snapshot; it never automatically replays the write.
Distinguish browser drafts, server-saved private submissions and committed actions using owner receipts.
Withdrawal retains an unsaved local amount, without restoring a saved submission.

Activity is one bounded log, in its workspace or pinned left block on wide screens. Pinning uses a
labeled button above the log so its destination is visible without covering entries. Keep entry-time
identities, causes, dice and resource endpoints stable as history grows. Group revealed token bids
without changing canonical history. Pair the two shortage/surplus rolls in one activity entry,
retaining each roll's dice, adjustments and consequences. A player's submitted competition result
may open a dismissible notice; polling someone else's result must not. Expansion notices expire with
their expansion, while history remains. Final scores use a dismissible/reopenable popup and leave board inspection.

The favicon reflects required player actions independently of rendering. Hidden game tabs poll every
five seconds for two minutes after engagement/changed state, then every 30 seconds while idle;
unchanged responses do not extend activity. The games list checks all owned games independently of
filter/page. Keep one read/timer and refresh after visibility, resume or reconnect.

## Review sensitive operations

Private plans distinguish unsaved input, saved-for-review work and explicit offline authorization.
Put planning help on the related action buttons instead of adding separate plan headings.
Offer new plans while waiting for a turn; turn arrival closes an unsaved preview and restores live
controls. Existing saved plans remain available for review and explicit execution on the owner's turn.
Show exact consequences, assumptions and cancellation. Saving never executes; draws or changed
consequences pause for review. Editing confirms disarm. See [architecture](design.md#private-plans).

Primary Undo withdraws an unrevealed submission when available. Linear game otherwise opens a
consequence review using only server-offered targets: destination, removed public actions/random
events, cleared submissions and paused plans. Earlier targets live under Commands. Confirm with
**Rewind to here**; stale reviews require refreshed offers and explicit confirmation. Prior revisions
are read-only. Explain fresh rolls, restored-deck draws, no redo and retained knowledge in Help.
Policy conversion refreshes capabilities and closes the legacy editor while preserving unchanged
decision drafts; actual rewind clears them. [Architecture](design.md#history-and-undo) owns scopes.

Keep abandonment inside collapsed Game options, with one consent section alongside ordinary play.
The proposal counts as agreement; all seats must agree. Label the final response
**Agree and abandon game** and explain that it ends without a winner. Nonfinal responses preserve
drafts; terminal cleanup removes mutations and retains permitted inspection.

## Game-table review gate

Review the changed flow and one neighboring flow at desktop and phone widths:

1. At rest, identify the object carrying the choice. Remove duplicate homes, headings, controls
   and copy before shrinking text. Each remaining element must help choose, explain or find data.
2. Prepare a choice. Check the question/count, capital symbols, authoritative consequences and
   limiting resources beside confirmation. Explain marker opportunities before expansion Pass.
3. Open reference and return. Verify focus restoration and unchanged draft, without another inspector.
4. Commit, reject and poll separately using live offers. Check successful cleanup, correctable-input
   preservation and polling preservation; positive feature assertions alone miss unwanted duplicates.
5. Inspect rendered views for reachable confirmation, clear targets, readable references and sensible
   scrolling. DOM existence or unreviewed screenshots do not establish clarity.

[Web tests](../web/tests/) contain focused browser suites for these flows; [contributing](contributing.md)
explains the runner. Record actual human observations separately from automated facts. Screen-reader,
physical-phone, browser-chrome zoom and SVG/composited contrast remain [open checks](follow-ups.md).
