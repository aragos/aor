# Finite C/P/E paired-policy source prerequisite

This protocol implements the approved family80 proposal
`8b37131e436062f04a1d58c37106ffba19b87b4c7b2d5da25b7bf1413245c999`.
The current milestone authorizes source and constructed-fixture checks only. Scientific
subjects, RNG reservation, initialization, observations, games and fitting require later
package review. Family79 remains closed, family77 remains held, and existing previews and
completed evidence are unchanged.

## Fixed experiment

Exactly two iterations each collect three independent 640-world cohorts, assigned CARD,
PURCHASE and EXPANSION. Each cohort has prospective indices 0–511 TRAIN and 512–639
VALIDATION. The controlled seat is `index % 4`, and the assigned epoch is
`1 + (index / 4 floor) % 3`. A separately reserved online reservoir stream chooses one
branching window of the assigned head in that epoch. Complete backbone window counts,
the chosen ordinal and missing roots remain recorded. No reward, future outcome, card
quality or opponent-private fact selects the root. This balanced curriculum is deliberately
not an unbiased sample of runtime visitation.

All cohorts in an iteration use one immutable current complete C/P/E bundle for prefixes
and tails. Other selectors and homogeneous G3 rivals remain fixed. Original CARD and
PURCHASE are the exact qualified nonzero G3 actors. EXPANSION reuses the exact original
untrained family75 initializer
`8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc`, with its reviewed
schema-2 dictionary, prior and factory. No trained75/78 actor or new initialization RNG
is used. Actual bytes and hidden-function identity are checked only in later authorized
preparation. The raw G3
screen comparator omits that E actor. Original-composite means exact G3 C/P plus the one
initial E actor; it is a distinct behavior from raw G3.

The following three index meanings are explicit and never substituted:

* `observation.baselineIndex` remains the original network prior: ordinary M8 HARD for
  CARD, frozen F0 `PurchaseBaselineInput` HARD for PURCHASE, and qualified E for EXPANSION.
* `sampledCommandIndex` is the actual current complete bundle's canonical backbone action,
  sampled using the unchanged native bot/actor seed owner.
* `referenceIndex` is the paired-return reference b and equals that recorded current sample.
  It does not replace the observation's prior index or require another sample/RNG call.

At each retained branching root, execute b and up to eight uniformly sampled distinct
nonreference offered actions under two shared future streams. Retain original indices,
exact canonical commands, full current/original probabilities, queues, absolute decision
offsets and inclusion probability `min(8,n-1)/(n-1)`. Reference inclusion is one and its
delta is zero. No support expansion, future-based winner label or hidden actor input is
introduced. Full ordinary backbones complete once, including worlds without a root.

Every head in every iteration requires at least 256 branching TRAIN roots and 64 branching
VALIDATION roots. Any failing head stops the entire campaign without refill or a partial
bundle. These are public exposure gates, not outcome gates.

## Numerical contract and sealed evaluation

Use the fixed family79 direct objective independently for each head: the equal-root mean
of the sum over sampled nonreference actions of
`(pi_theta[a] - pi_current[a]) * mean_two_futures(R_a-R_b) / inclusion_probability`.
The original-policy anchor is `KL(pi_theta || pi_original)`, coefficient .1. Original C/P
anchors use their full nonzero G3 distributions, not a 97% or zero-output approximation.
The identity that probability differences sum to zero makes subtracting the sampled
reference valid; proposals, not selected-action likelihoods, provide inclusion correction.

Each head has fresh Adam per iteration, 20 fixed epochs, 64 requested-world minibatch
slots, learning rate .0003, gradient norm cap .5, and the existing five actor arrays.
Value arrays remain byte/numerically unchanged. Large full-offer batches may use bounded
forward chunks; gradients accumulate with original weights before one clip and Adam step
per 64-world minibatch. No PPO ratio, value fit, critic alias or shaped reward is added.

Anchors have equal weight per represented independent world and then per deduplicated
state. Each head retains its own original iteration-1 TRAIN backbone observations and
adds that head's current iteration-2 TRAIN roots. Missing/empty worlds remain explicit,
without invented states. After every epoch require mean KL <= .02 and maximum state KL
<= .10. A violation rolls back that entire epoch's actor and Adam and ends the child fit.
Epoch zero is diagnostic only. No accepted actor change in any head stops the bundle.

There are six head optimizer transactions and at most two atomic bundle publications.
All three child completions, exact contexts, immutable inputs and value parameters must
pass before the next bundle becomes consumable. An incomplete or failed head exposes no
shortened bundle or fallback candidate. Iteration 2 collects only after publication of
the complete iteration-1 bundle.

All three heads' validation probability vectors are sealed after each fit; both complete
iteration seals must exist before any semantic VALIDATION labels are read. There is no
validation epoch/head selection. The sole mechanism endpoint is the final iteration's
equal-phase gain `mean(gain_CARD,gain_PURCHASE,gain_EXPANSION)`. Independent-cohort variance
is `sum(sample_variance_head / ready_world_count_head) / 9`, with a two-sided 95% normal
interval using `NormalDist().inv_cdf(.975)`. Per-phase estimates are descriptive. Sibling
futures, actions and states are not independent statistical units.

The fixed fresh 1,024-world screen executes FINAL, RAW_G3 and INITIAL roles once per
world, using the authoritative native sampling contract and shared world identities.
Nomination requires positive lower 95% bounds for the mechanism, FINAL-minus-RAW_G3 win
and reward, and FINAL-minus-INITIAL reward. Native screen intervals retain the existing
explicit multiplier 1.96. Only complete final iteration 2 can be nominated; no earlier
head/bundle, alternate epoch, fallback, automatic qualification or activation is allowed.
Later independent 99% qualification is separate.

## Finite two-host execution and evidence

Each head/cohort uses ten fixed 64-world shards. Desktop owns chunks 0,1,2,3,4,8 and Mac
owns 5,6,7,9; no migration, duplicate execution or top-up. Indices and RNG are global
within each head cohort, independent of host/worker count. Each cohort reproduces fixed
indices 0,213,426,639 using its original factory. Final screens use eight 128-world
shards, Desktop 0–4 and Mac 5–7, with fixed replay indices 0,341,682,1023 for all three
roles. The two predetermined historical raw-default controls execute once on Desktop
and their portable admission gates both hosts' fresh screens.

Maximum executions are 3,840 backbones + 69,120 tails + 24 backbone reproductions +
3,072 screen games + 12 screen replays + 2 historical controls = **76,070** terminals.
Exactly one shared original 64,800-second science clock covers collection, transfers,
six child fits, sealing and the screen. Pure terminal audit ends by the earlier of
persisted terminal+900 seconds and original science deadline+900 seconds. Desktop uses
10 native workers on its declared 16 logical CPUs; Mac uses 6 on 10. Native heaps are
6 GiB, process groups 12 GiB, and retained data is capped at 24 GiB per host / 48 GiB
combined. Preparation must prove actual headroom and a 16 GiB free-space floor on each
host. No retries, resumed science clock, additional iteration or successor campaign.

Mac retains the existing outbound-only SSH path to the declared Desktop. Only immutable
completed shards and complete bundle/admission publications cross hosts. Both workers
exit on peer terminal status or the original deadline; no waiting loop extends that
clock. Native workers publish one compressed origin at a time and return compact
receipts. Public and label sidecars bind the exact opaque original SHA; public sidecars
exclude terminal/prefix/private trees. Completed shards are losslessly archived and
every member verified before expanded originals may be pruned. Compact per-world screen
records avoid repeating model dictionaries across the final report.

## Typed interfaces and reuse boundary

`cpe-paired-bundle-1` contains a `heads` map for CARD/PURCHASE/EXPANSION, each binding exact
model bytes and the canonical complete encoder/state/action dictionary digest. Raw
dictionary JSON is separately pinned. New collection/public/labels/corpus/admission
types bind head, iteration, cohort index, both complete bundle identities, semantic
ecology/scope, source/range and all original streams. Stable scope identifies the
original bundle/ecology/encoders; iteration science identity additionally binds the
current complete bundle and the complete three-cohort stream table. Raw source hashes
retain host-local paths/ranges as provenance without claiming cross-host byte equality.

Reuse the existing numerical tensor kernel, bounded process execution, established
campaign supervisor, immutable transfer/archive primitives and native arena/replay
factories. Keep TOKEN2 and old two-head/territory schemas strict. Narrow shared mechanics
must retain legacy defaults, argument order, wire bytes and math; new C/P/E wrappers
own different kinds, three-head finality, 18-hour limits and phase-qualified shard tags.
Do not duplicate the entire family79 pipeline or introduce a general scheduler.

Constructed proofs cover prior/reference index separation, exact nonzero C/P likelihoods,
reservoir uniformity and offset replay, original/current bundle identity, proposal
inclusion arithmetic, bounded full-offer gradients/one-step counts, whole-epoch rollback,
all-three atomic publication, both-iteration seals, shard ownership, timeout/peer cleanup,
lossless archive tamper rejection and old TOKEN/default-path parity.

The executable base is research commit `5a293db01e3731828862852a1ce3511b8db6242d`.
Latest inspected local main `0040ce9a9f0d00ce946c1392fdc1912b54e516db` differs from their
`cfe2f90f75196bf93f49a15bfc5d04fc89b2d9e8` merge base only in web UI/tests, not engine,
AI, training or server behavior. The existing research engine is retained; no UI merge
or preview change is part of this source prerequisite.
