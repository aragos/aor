# Parallel purchasing proposal

Implemented as private activity plans. Current contracts live in [requirements](requirements.md),
[design](design.md#6-private-plans) and [UI design](ui-design.md); this proposal records the design
and its alternatives.

Let every player prepare purchases during the Purchase phase, then resolve those purchases in
the existing turn order. Offer two explicit choices: **Save for review**, which always waits for
the player, and **Execute automatically on my turn**, which authorizes the server to act even
when the browser is closed. Apply the same planning mechanism to Urban Ascendancy purchases
and Master Art discards in Buy Card.

This would remove waiting between players who already know what they want, while preserving
the opportunity to reconsider after seeing earlier actions. Preparation happens in parallel;
game effects still happen in order. A player choosing review can still hold up the phase.

## Why retain ordered resolution

[Purchase rules](rules.md#55-purchase) give each player shipping, advances, stabilization and
misery relief in sequence. Buying prerequisites and advances such as Institutional Research,
Written Record and Middle Class can affect later choices in the same activity. Earlier players'
[Patronage payments](../core/src/commonMain/kotlin/dev/aragos/aor/CardGame.kt) can also increase a
later player's cash. Renaissance can change order before purchasing starts.

Buy Card also depends on order: purchases consume a shared deck, can cross an epoch boundary,
and reveal a new private card to its buyer. A discard chosen after drawing can depend on that
new information.

| Approach | Benefit | Cost and consequence |
| --- | --- | --- |
| Browser queue | Small extension of the existing Urban Ascendancy control. | Requires an open browser and reliable polling; cannot provide durable automatic execution. |
| Private server plans with ordered execution | Everyone can prepare at once; automatic plans run offline; existing mechanics and replay remain usable. | Review plans still wait for their owners; execution needs durable authorization and conflict handling. Recommended. |
| Simultaneous commitment and batch resolution | Can resolve a whole phase after everyone submits. | Requires new policies for resources, payments, deck allocation and information timing, plus a rules upgrade for existing games. |

The recommended approach adds an application capability, with no simultaneous reveal of purchase
plans and no new gameplay variant. It preserves each game's selected options and undo policy.

## Player choices and cancellation

Use the existing Advances workspace and action pane to prepare the ordered purchase sequence.
Keep the viewer's hand available for discard decisions. Waiting players see their planning
controls alongside a single status naming whose turn is resolving.

| Choice | What is saved | When it executes | What the player can change |
| --- | --- | --- | --- |
| Local draft | Browser input only. | Never. | Freely editable; no promise of recovery on another device. |
| Save for review | Private server plan, available after reload or on another device. | Only after the owner reviews the current preview and explicitly confirms on their turn. | Replace or delete before confirmation, including after earlier players act. |
| Execute automatically on my turn | Private server plan plus explicit authorization for its exact version and preview. | On the legal turn, if validation and the authorized consequences still match. | Cancel or replace until execution starts; afterward the game's normal undo policy applies. |

**Save for review** is the default. Saving does not reserve money, buy anything, signal readiness
to opponents, or finish a turn. When the turn arrives, show the saved sequence with an updated
preview and changes since saving. Let the owner change or remove any unexecuted item before
confirming. This reconsideration works even under Competitive undo because nothing has committed.

Automatic execution requires a separate, explicit action. Display “Will execute automatically
when your turn starts, including while you are offline” and a Cancel automatic execution control.
On an already active turn, label the action **Execute now**, so arming cannot unexpectedly submit
a purchase behind a waiting label. An empty plan never implies Pass; skipping a remaining step or
finishing the turn must be an explicit part of the plan.

Cancellation and execution serialize on the same game lock. If cancellation wins, nothing further
executes. If execution wins, report exactly what committed and which undo options remain. There
is no grace period after the turn begins. Editing an armed plan first disarms it on the server;
only a confirmed disarm makes editing safe from execution of the previous version. A failed or
uncertain cancellation must keep the armed status until an authoritative refresh resolves it.

For example, the third player saves an advance sequence for review. The first player's purchases
reveal a different strategy; the third player removes one advance and redirects their spending.
No history edit is needed. If the third player had authorized automatic execution, that strategic
change alone would not stop it: choosing automatic execution accepts the risk of later public
information, subject to the consequence checks below.

## Purchase plan contents and previews

Represent a plan as a typed, ordered sequence of ordinary commands, with each entry bound to its
intended step or decision. Include shipping upgrade or skip, Patronage claims and advances in
order, the end of advance buying, stabilization payment, and relief or skip. Permit incomplete
plans; execution stops where an answer is missing. Preserve canonical command syntax rather than
introducing a second purchase language.

Preview the sequence using shared core calculations, including:

- Prerequisites bought earlier in the sequence, immediate abilities, and category credits that
  become available only in later rounds.
- Each payment's recipient, protection money, leader access and discounts, remaining cash,
  shipping, misery endpoints, tiers and current-turn MR dollars.
- Stabilization after planned advances and any explicitly selected Master Art discard; relief
  using the resulting state. Relief confirmation still finishes purchasing.
- Immediate Master Art interruptions under the selected variant, entry into Chaos, ending
  triggers and any other boundary that prevents later planned entries from applying.

The [planning projection contract](engine.md#5-planning) is deferred today. Implement the narrow
projection needed here: evaluate the viewer's hypothetical remaining activity using only their
permitted information. While waiting, clearly state the assumption that earlier actions do not
change the relevant inputs. Do not invent opponents' passes or query future draws to make the
preview appear exact. Missing decisions and unknown outcomes block dependent entries. Once the
actual turn arrives, replace those assumptions with authoritative validation.

Waiting does not make ordinary purchase commands legal. A planning preview is separate from
the live legal-choice list; only the real actor can commit purchases through the engine.

### Automatic execution checks

For the first release, authorize the exact previewed consequences, rather than offering a rule
language of spending ceilings, substitutions and conditional choices. Before execution, recompute
the deterministic segment against the live state. Pause for review if a price, recipient,
discount source, relevant starting resource, hand reference, eligibility, resulting resource or
misery endpoint differs from what the player authorized. Even a cheaper purchase requires review
under this initial policy. Unrelated public revisions do not disarm an otherwise identical plan.

Validate the complete segment up to the next missing answer, interruption, outcome request or
activity boundary before committing it. If an entry in that segment is invalid, commit none of
that segment, retain the input and return a typed explanation identifying the entry. Do not skip
an invalid purchase, substitute another advance, switch cash stabilization to misery, or infer a
Pass to keep the phase moving.

An explicitly planned answer can handle a deterministic interruption belonging to the same
activity, such as discarding a known held card for immediate Master Art. Otherwise pause at the
interruption. Never answer Renaissance or another player's prompt as part of a purchase plan.
Stop at a draw or other outcome request; the authority resolves and records the ordinary outcome,
but the remaining plan requires fresh review and authorization. Do not execute a suffix merely
because its commands become legal after new information arrives.

Committing an authorized segment atomically does not make its engine entries one protected
simultaneous batch. Record each action with its normal activity ownership and preserve the
configured history-edit boundaries. Show executed entries separately from the remaining draft.

## Shared behavior for buying and discarding cards

Buy Card currently combines Urban Ascendancy's optional $10 purchase and Master Art's optional
discard in one activity. Reuse the same plan storage, modes, previews, cancellation and executor;
retain the engine's existing allowances and canonical `buy-card`, `discard-card` and `pass` actions.

| Intent | Planned behavior |
| --- | --- |
| Buy a card and inspect it before deciding what to discard | Authorize `buy-card`; stop at the draw. If a discard choice remains, notify the owner and retain the proposed remainder for review. |
| Discard a specific existing card without buying | Save or authorize that exact private hand reference, with an explicit finish action if the player wants to end their activity. |
| Buy, then discard a specific existing card | Store the proposed discard, but pause after the draw in the first release. The owner can keep or replace it after inspecting the new card. |
| Skip buying or discarding | Store the explicit applicable continuation or finish action; lack of a plan never acts as a skip. |
| Discard immediately after buying Master Art during Purchase | Bind the chosen card or explicit skip to that interruption, separate from the ordinary Buy Card allowance. |

A fully automatic “buy and then discard whatever is best” would require a strategy controller.
Likewise, automatically discarding the newly drawn card would require a new explicit conditional
instruction. Neither is needed for this proposal. After a draw, normal engine progression may
finish an activity with no choices remaining; pausing a plan does not create an artificial prompt.

Validate funds and deck availability on the real turn, preserve shared-deck order and ordinary
epoch/shuffle outcomes, and never reserve a draw when saving. Never use knowledge of the next
card to approve, reject or price a plan. Discard identities retain the existing private audience
in previews, diagnostics and committed history.

Replace the browser-only Urban Ascendancy queue with this
shared mechanism once available. Its current optional queue submits once when a legal offer is
observed and needs the view to remain open. A server plan must not be created or armed implicitly
from that browser state; offer an explicit save or authorization instead.

## Application architecture and durability

Keep plans and execution authorization with the service's private game data, outside the
committed engine log. Core owns parsing, projected consequences, legality and activity/decision
identity. The service owns seat authorization, persistence and execution scheduling. File and
PostgreSQL storage provide atomic operations without deciding gameplay.

A plan record needs a strong identifier, owner, game, turn and phase occurrence, effective-history
lineage, intended activity/decision identities, base revision/log position, canonical actions,
authorized consequences, saved mode, opaque version, progress and a typed pause reason. Restrict
the initial scope to the current Purchase or Buy Card phase and the owner's remaining activity.
Derive phase identity from engine/history provenance, not just a stage name or polling revision.
Do not create a general future-turn scripting system in this change.

Use explicit private save, replace, delete, arm, disarm and review/execute operations. Every write
checks the expected public revision and the owner's opaque plan version in the same transaction;
disarm/deletion may tolerate public progress only while that exact authorization remains unconsumed.
Repeated values still receive a new version. Concurrent tabs cannot overwrite or rearm an obsolete
plan. Distinct owners' private writes do not conflict solely because someone else saved a plan.
Bind confirmation to the current preview and plan version; stale requests refresh for review.

After an ordinary action makes another activity actionable, schedule server execution of its
armed plan. Persist the work signal with that action; a recovery worker must also discover armed
actionable plans after restart. Reads and browser polling never trigger purchases. Each bounded
execution transaction locks the game, rechecks authorization and current state, validates one
segment, and atomically appends normal revisions with the plan's consumed progress and receipt.
Continue through subsequent armed players in separate transactions, stopping at review or an
unplanned decision. Browser closure and process replacement must not strand authorized work.

Record automatic actions as authorized by the plan owner, with private execution provenance
linking the plan version and operation to the committed entry range. Do not attribute later
players' actions to the player whose request happened to trigger the executor. Keep automatic
phase receipts separate from direct purchases in Activity.

A unique execution receipt prevents duplicate application after a restart or uncertain response.
A failed publication leaves both game and plan progress unchanged. Technical failures retain
unconsumed authorization for durable recovery; changed consequences or a missing choice disarm
the remainder and require review. A client reconciles an unknown outcome instead of replaying
the mutation. Replay uses recorded actions and outcomes, never the executor or the private plan.

### Privacy and lifecycle

Only the owner sees the saved plan, armed status, preview and private errors. Opponents and
spectators see ordinary committed effects and the real required actor. Do not expose a count of
plans, selected cards, readiness based on optional planning, or a distinct public reason why the
actor is waiting. Owner-specific snapshot receipts/ETags change on plan writes; unrelated viewers'
responses do not reveal those writes. Pending review must still appear as an outstanding decision
in the owner's game list and notifications.

Preserve local and server drafts through polling, inspection and same-account credential refresh.
Signing out clears browser secrets; it does not silently cancel durable authorization that was
explicitly advertised as working offline. Loss of seat authority, cancellation, abandonment or
game completion prevents execution. A history edit or engine upgrade disarms all affected plans
and retains their unexecuted contents for review; it must never resurrect a consumed authorization
when an earlier phase becomes active again. Ordinary phase completion expires remaining work.

## Upgrading ongoing games

The recommended path changes service storage and UI capabilities while preserving existing
engine transitions and replay text. Existing games can acquire empty private-plan storage without
restarting, redealing, changing order or replaying a purchase differently. Keep rules/data versions
unchanged only after demonstrating identical continuation and outcomes; if implementation changes
those contracts, require a separate explicit engine upgrade.

Provide a narrow, explicit upgrade from the supported source game format to the new format:

1. Dry-run validation checks the expected revision, source format, replay and all private durable
   state. Report the target format and affected games without private contents. Allow dry runs
   during any phase, including mid-Purchase and unrevealed bidding.
2. During publication, lock the game and verify its storage generation as well as public revision,
   so a concurrent private bid replacement is not overwritten. Archive the exact source bytes by
   hash and apply conversion plus its administrative audit receipt atomically.
3. Add empty plan state and record capability activation. Preserve the full existing revision
   sequence and entry identities, current state and prompt, recorded outcome identities,
   memberships, credential hashes, lifecycle, undo policy, metadata, archives, pending bids and
   their private freshness/operation receipts. An appended administrative revision may invalidate
   old clients' public revision guards, but must not reset a pending secret batch.
4. Validate replay/state equality and each preserved field before publication. On failure leave
   the original untouched. In PostgreSQL, schema migration adds the storage infrastructure;
   per-game activation remains explicit and auditable. File conversion uses the same validator.

The existing [administrative log replacement](../server/src/main/kotlin/dev/aragos/aor/GameAdministration.kt)
is an engine checkpoint operation: it replaces effective history and constructs fresh private
state. Do not use it as the storage converter for this feature. Preserve original revisions rather
than rewriting them as an initial plan checkpoint. The ordinary decoder should continue accepting
only the new current format; put source-version handling in the explicit upgrader, with a dry-run
inventory and backup before cutover. Do not add decoders for unrelated historical formats.

For a game already in Purchase, the active player can plan only their remaining steps; later
players can prepare full activities. Players who finished cannot reopen them through planning.
The same rule applies mid-Buy Card, preserving already-used purchase/discard allowances. Plans
may be saved during phase-opening Renaissance windows, but automatic authorization waits until
those windows finish and the settled order has been reviewed. Upgrading never executes a plan
or converts an existing browser draft into authorization.

## Delivery and verification

Implement private save/review and truthful core previews first, together with the explicit storage
upgrade so ongoing games can participate. Then add automatic authorization, durable scheduling
and receipts. Apply the shared mechanism to Buy Card and immediate Master Art, then remove the
special browser queue. This ordering makes the review workflow useful before offline execution
is enabled.

Verify the following before enabling automatic execution:

- **Ordered equivalence:** manual commands and authorized plans produce the same canonical
  actions, state, prices, endpoints, outcomes and entry-time Activity. Cover prerequisite chains,
  immediate ability changes, prior-round credits, Patronage, stabilization, relief and Chaos.
- **Review and cancellation:** new public information can change a saved plan without a history
  edit, including under Competitive undo. Exercise cancellation on both sides of the execution
  lock, editing an armed plan, changed consequences, an incomplete sequence and invalid segments.
- **Card information boundaries:** draw pauses, private known-card discards, missing hand
  references, immediate Master Art allowances, final-epoch exhaustion and recorded shuffles retain
  their ordinary behavior. Previews cannot depend on the actual hidden deck order.
- **Durability and concurrency:** both backends preserve plans through restart, execute once with
  two workers, handle save/arm/disarm conflicts across tabs, recover from an uncertain response,
  and roll back progress with a failed write. Reopened history cannot rearm an old plan.
- **Upgrade continuity:** compare before/after effective history, revision identities, outcomes,
  state, prompt, membership and private pending data. Include mid-Purchase, mid-Buy Card, pending
  bid replacement, Renaissance, nested interruption and inactive games. Exercise conversion
  failure, archive integrity and file/PostgreSQL parity.
- **Viewer and browser behavior:** prove owner/opponent/spectator privacy for snapshots, ETags,
  lists, errors and history. Use live offers and production React StrictMode to check polling,
  inspection, reload, cross-device saves, action cleanup and actionable review notifications.

This proposal extends the earlier deferred private-plan contract with explicit authorization for
automatic execution. Adopted goals and acceptance live in [requirements](requirements.md), reusable
interaction contracts in [UI design](ui-design.md), architecture/projection contracts in
[design](design.md) and [engine](engine.md), and service operation syntax in [commands](commands.md).
Actual test evidence belongs in [decision coverage](ui-decision-coverage.md). General
strategy controllers, future-turn scripts and simultaneous batch mechanics remain deferred.
