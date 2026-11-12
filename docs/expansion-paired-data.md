# Expansion paired-action data

Source preparation only. No collection, seed reservation, model fitting, export or activation is
authorized by this document. The frozen 70/71 packages and existing previews remain independent.

## Question and baseline

Can a public state/action learner distinguish the terminal value of competing expansion allocations
under the exact current G3 card/purchase and E/T/M8/F0 continuation? This is neither an always-buy
card/grain rule nor another search over E's eight coefficients.

The current E controller calls `BotPolicy.choose` anew at every expansion decision. Its bounded
planner returns only `best.first`, not an executable queued plan. Each arm changes exactly one
offered command; every subsequent choice uses a newly composed normal controller at the original
absolute PLAYER-command offset. The incumbent arm must reproduce the actual E command before any
tail starts. The same rule applies to PASS, card draws, competitions and nested responses.

Existing valuation already includes commodity inventories: `BotEconomicProjection` subtracts own
held commodity cards from the public introduced/unplayed exposure; `BotCommodityPortfolio` prices
own physical shared-choice cards once and supplies held/public bundle interactions. The qualified
policy assigns nonzero weights to these features. These are current-payout forecasts, not learned
conditional terminal values or predictions of which player holds/draws a card. Public unplayed
counts include held cards and private discards; future deck order remains unknown.

## Prospective roots and interventions

There are exactly 1,024 independently seeded origins. Seat is `index % 4`; assigned epoch is
`1 + (index / 4) % 3`; requested ordinary expansion ordinal is `[0, 2, 4, 6][(index / 12) % 4]`.
Integer division applies. Count ordinary `expand` decisions, including pass-only windows, in the
controlled seat's first expansion turn in that epoch. Nested Windmill/Cathedral responses do not
count. Capture the exact requested ordinal only if it has at least two offered commands. An absent
epoch/turn/ordinal or nonbranching selected window remains missing; do not refill or capture a later
turn. Prefix choices are always the exact normal policy.

At a captured root, preserve the complete canonical offered-action list and the incumbent index.
The maximum eight distinct arms begin with incumbent, PASS, and expansion-card purchase when offered,
deduplicated in that order. Fill remaining slots with a seeded public-only sample: uniformly shuffle
physical destination groups, sample one offered command per group, then sample remaining commands
only if group representatives did not fill the cap. Place/attack/reduce commands share their physical
destination group. The proposal seed is separate from the origin and two future seeds; it is
provenance, never a model feature. No score, return, hidden hand, deck position, or predicted value
selects proposals. All arms and their inclusion reasons are fixed before any tail.

Two fresh future-chance streams are intentionally shared across every arm of one origin. Policy
seeds remain the original origin seed plus absolute PLAYER-command index/seat through the existing
owner. Forks preserve the original deck and hands. All arm returns are retained; no hidden-world
winner/action label is emitted. Statistical units and splits are entire origins, never tails.

Origins 0–767 are TRAIN, 768–895 VALIDATION, and 896–1023 TEST. Missing origins and all siblings stay
together. Maximum work is 16,384 terminal tails plus at most 1,024 terminal missing backbones =
17,408 terminal executions; captured prefix work is counted separately. Canonical prefix/suffix
replay is validation of recorded outcomes, not additional policy simulation. Initial executable
preparation uses incremental per-origin receipts; final host/chunk/archive resource contracts need
separate review before any collection.

## Public encoder contract

Use a new research-only `aor-expansion-paired-observation-1` envelope. Preserve every state/action
column and unit from `ExpansionObservationEncoder` EXPANSION_2 and append the following named state
columns; old encoders, policy dictionaries and save formats do not change:

* `commodity.C.cards.epoch1`, `.epoch2`, `.epoch3`, `.unplayed`, `.unplayedKnown`, `.ownHand`:
  existing public `CommodityCardCountView` and own card-inspection facts; card counts /10.
* `commodity.C.role.R.markets` and `.payout`: each current authoritative public payout owner quote,
  markets /25 and current dollars /200; absent owners have zero holdings/payout. Shared commodity
  cards contribute exposure to each offered commodity; these columns are not additive card values.
* `role.R.expansionBonusEligible`: authoritative currently eligible white-marker count /25.
* `expansion.cardCost` and `.cardCostKnown`: the existing offered token price /36 and availability
  of that quote. The old action already distinguishes offered card purchase from placement/PASS.

Here C ranges over the complete commodity enum and R over the existing relative roles 0–5. The old
raw state already retains own card-definition counts, per-role advances/cash/misery/income, complete
public board, epoch/turn/deck/ending and current order. No identifiers, seeds, provenance, selected
action, behavior value, terminal labels, future states, opponent hand contents or draw order enter
features. Sparse entries preserve existing units and the common finite [-16,16] bound.

The eligible-marker count is genuinely absent from the current view: white color alone cannot
distinguish expansion, event rebuild and voluntary rebuild. Add one narrow public projection sharing
the exact `ExpansionBonusProjection` counting owner used by `TurnEngine`, retaining its active-player and
rebuilt-marker rules. The collector passes this typed public projection to the new encoder. Do not
add a GameView/save field or duplicate bonus rules; any later runtime transport is a separate change.
This quote describes current eligibility, not a promise of winning the future bonus.

## Prospective analysis and later fit boundary

Before semantic labels, freeze coverage by split, seat, assigned epoch/ordinal, missing reason,
offered/proposed action kind, token-pool/price, own commodity hand/exposure and authoritative current
holdings/bonus counts. No TEST semantic labels or feature summaries are used during development.
Report whether late ordinals actually supply card-versus-leftovers/market choices; do not adapt the
schedule after seeing outcomes.

The intended later learner is one full state/action 64-hidden-unit tanh/sigmoid network, trained on
all paired arm returns rather than selected winners. A separate pre-label fit protocol must pin its
initializer, 20 epochs, fresh Adam .001, 64-origin batches, equal-origin/equal-nonreference-action
paired difference loss and .1 equal-origin/all-arm absolute-return anchoring; these are one proposed
recipe, not an authorized fit or grid. Include unchanged E as a validation candidate and preserve E
on predicted ties. Nominate once using validation paired error, then persist every public TEST
prediction/choice before reading TEST returns. Policy effects include every supported TEST root,
even when the selector retains E. Missing roots are missing, not invented zero-return examples.
No 67 critic is evaluated on token/expansion contexts it never supported. A held-out root proxy does
not establish whole-game strength; a later separately reviewed whole-game comparison is necessary.

At most 768 independent training origins may be too few for this full-network proposal. A later
review may instead specify initialization from existing G3 state embeddings; that would require an
explicit dictionary/phase mapping and its own frozen recipe. Neither approach is implemented or
selected here, and neither permits scoring these contexts with the unsupported 67 critic.

## Source ownership and verification

New native input/proposal/collector/main owners reuse `ArenaSpecification.controllers`,
`ExperimentSession`, `FrozenPolicyContinuation`, `TrainingArtifacts`, and ordinary canonical replay.
New common encoder/projection owners require native and JS synthetic parity. Tests cover exact
ordinal scheduling, proposal support/caps/dedup/determinism, unchanged E re-planning, absolute-offset
fork parity, exact bonus exclusions/ties, privacy and legacy encoder equality. No synthetic test
imports a human position or executes a scientific collection.

The branch starts from native 96c5edc and includes main cfe2f90 as an ancestor. Main added no core/AI/
training changes; exact pre-collector engine/AI subtree identities are retained in preparation.
Both root and continuation use this same executable. New runtime/source identities must be explicit;
old executable hashes are not relabeled as current.
