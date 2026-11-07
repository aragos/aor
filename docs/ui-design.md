# UI contributor guide

Objects carry inspection/choices; authoritative consequences sit beside confirmation. The server owns
legality/quotes; [rules](rules.md) owns mechanics and [map design](map-design.md) spatial rendering.

## Find the implementation

Paths are relative to [web/src](../web/src/).

| Concern | Files |
| --- | --- |
| Layout | `GameTable.tsx`, `TablePanels.tsx`, `app.css` |
| Decision identity / drafts | `decision.ts`, `decisionOffers.ts`, `DecisionControls.tsx` |
| Selection / commitment | `ObjectDecisions.tsx`, `DecisionActions.tsx`, `GameActions.tsx` |
| Object details / reference | `Hand.tsx`, `AdvanceInspection.tsx`, `CardInspectionDialog.tsx`, `ReferencePopup.tsx` |
| Effects / prices | `CardPlayEffects.tsx`, `MiseryPreview.tsx`, `AdvancePrice.tsx` |
| History / plans / consent | `Activity.tsx`, `HistoryEditor.tsx`, `RewindReview.tsx`, `ActivityPlanner.tsx`, `Abandonment.tsx` |
| Auth / polling / requests | `HostedApp.tsx`, `api.ts`, `polling.ts`, `favicon.ts` |
| Identity / text / preferences | `IdentityLabel.tsx`, `messages.ts`, `labels.ts`, `preferences.ts` |

## Interaction contracts

- Render projected costs, destinations, limits, blockers and endpoints; never parse messages or
  recompute legality. Prepare defaults/sole choices, but commit explicitly. Keep inspection,
  eligibility, selection and commitment separate; references restore focus and preserve work.
  Load reference content inside the dialog so first lookup cannot change page scrolling. Cathedral
  details track public owner/non-owner usage pairs, refreshing with updates/history and resetting each
  game turn. Objects own details; Help owns lookup, variants and app behavior. Avoid duplicate inspectors.
- Beside the action, show actor, question, resources and one waiting status. Open its workspace once
  per decision. Interruptions name responder/resumed activity; Cathedral names the province.
  Keep confirmation reachable while review scrolls and retain map/hand space on phones. Phone actions
  collapse without losing drafts; polling/navigation preserve collapse, new decisions/selections reopen.
  The phone rail ends at the workspace; full-width actions keep title/Undo above status, with equal-height
  labeled Hide/Show. Player summaries show current score (advance points + cash − misery; no numeric
  score for Chaos) and order box; reference owns the full box mapping.
- Preview deterministic prices/discounts, cash/misery endpoints, shipping changes and limiting resources
  inline at consistent body size. Distinguish forecasts/conditions/unknown dice. Blockers explain causes
  without hiding legal alternatives. Purchase/Pass name their continuation; explain marker opportunities
  before Expansion Pass. Copy describes current behavior.
- Retain capital silhouettes with every capital name, including previews/reference/receipts; use handles
  before assignment. `CapitalLabel`/`LabelMessage` keep text baselines aligned and symbols centered.
  Preserve text/numbers alongside color, green→amber/red→Chaos misery order and dice colors.
- Keep the hand available; clicks prepare/toggle, dragging never selects/submits. Commodity inspection
  highlights provinces without replacing drafts. Track popups follow public updates/retain positions;
  shared commodity cards switch within one payout popup. Projected live warnings explain voided cards
  and legal no-target plays that consume a card without effect.
- Keep zoom/Fit/help beside workspace tabs, hiding them when space is insufficient; keyboard/gestures
  remain. No parallel map panel. Public remaining-card lookup lists introduced, not publicly played cards
  (including hidden hands/discards); recurring plays reset next shuffle, square plays stay excluded.
  The header's remaining-card text opens this lookup; entries open existing card/payout details and
  return without changing drafts. Keep draw-pile size separate and forecasts distinct from payouts.
- Keep grouped action buttons equally tall when labels wrap; place help beside the button with a
  separate hit target outside its border.
- Support keyboard/touch, visible focus, reduced motion and phone widths. Name finite-choice questions;
  roving focus lets Tab reach confirmation. Localize ordinary text, never canonical syntax. Preferences
  use browser storage with an in-memory fallback. Tapping expanded player details closes them.

## Draft and result lifetimes

Scope work by game, viewer and `activity.decisionEntry`, separately from revision. Polling, readiness,
navigation, gestures and inspection preserve valid drafts. Replacement decisions/history edits clear
obsolete state even when prompt/actor repeats. Successful placement clears province/amount; Pass/card
play closes action context. Rejection retains correctable input; stale writes refresh without resubmit.

Same-account credential refresh preserves table/viewport/inspectors/editors. Sign-out, identity change
or lost authorization clears private work; superseded responses cannot restore it. Bound auth/fetch/body
to 15 seconds. Uncertain writes release UI and reconcile an authorized snapshot without replaying.
Distinguish local drafts, saved submissions and committed actions via owner receipts; withdrawal keeps
only an unsaved local amount.

Activity is one bounded workspace log or labeled left pin on wide screens. Preserve entry-time names,
causes, dice and endpoints. Province references open the map without changing
drafts; their text-sized commodities follow outside the link. Group related committed actions and
outcomes, revealed bids, and paired market rolls (including their draw rewards), preserving each
entry's facts and audience. Keep independent automatic causes and phase milestones distinct. Put
consequences below their cause, with each player's resource and order changes together. Only one's own
submitted competition can open its result notice; expansion notices expire with expansion. Final scores can dismiss/reopen, preserving inspection.

Favicon action status works independently of rendering. Hidden tabs poll every five seconds for two
minutes after engagement/change, then every 30 seconds; unchanged responses do not extend activity.
The list checks all owned games regardless of filtering/paging. Keep one read/timer; refresh on visibility,
resume and reconnect.

## Review sensitive operations

Plans distinguish drafts, saved review and explicit offline authority. Help lives on planning actions.
Offer new plans while waiting; preserve open drafts through public updates/turn arrival, refreshing
quotes without discarding choices. Saved plans stay reviewable/executable on the owner's turn. Show consequences, assumptions and cancellation; saving never executes,
draws/changed consequences pause, editing confirms disarm. [Architecture](design.md#private-plans) owns execution.
Card purchases offer one Buy/Pass choice while waiting, scheduling it directly with cancellation;
on the player's turn, ordinary Buy/Pass actions commit directly. Existing plans that include discards
remain reviewable through the full planner.

Undo first withdraws unrevealed submissions; otherwise Linear game reviews server-offered targets and
removed public actions/random events, cleared submissions and paused plans. Earlier targets live under
Commands. **Rewind to here** confirms; stale offers need fresh review/confirmation. Prior revisions are
read-only. Help explains fresh rolls, restored draws, no redo/retained knowledge. Policy conversion
refreshes capabilities/closes legacy editor but preserves valid drafts; actual rewind clears them.
[Architecture](design.md#history-and-undo) owns scopes.

Abandonment lives in collapsed Game options with one consent section. Proposing agrees; all seats must
agree. Label the final response **Agree and abandon game**, explaining no winner. Nonfinal responses
preserve drafts; closure removes mutations but retains permitted inspection.

## Game-table review gate

Check the changed flow and one neighbor at desktop/phone widths:

1. Remove duplicate homes, headings, controls and copy before shrinking text; each element helps choose,
   explain or find data.
2. Prepare a choice: question/count, capital symbols, authoritative consequences/limits beside confirmation.
3. Open reference/return: restored focus, preserved draft, no second inspector.
4. Separately commit, reject and poll with live offers: successful cleanup, correctable-input preservation
   and polling preservation. Check unwanted duplicates as well as positive assertions.
5. Inspect rendered clarity, reachable confirmation, targets, references and scrolling; DOM existence
   or unreviewed screenshots alone are insufficient.

Run affected [browser fixtures](../web/tests/) via [contributing](contributing.md#checks). Separate human
observations from automated evidence; physical-device/accessibility gaps stay in [follow-ups](follow-ups.md).
