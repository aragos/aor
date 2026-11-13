# Typed C/P/E paired numerical prerequisite

This source-only prerequisite extends the fixed direct paired-policy experiment to CARD,
PURCHASE and EXPANSION. It does not authorize models, RNG reservation, observations, games,
fits or activation. The campaign owns the prospective protocol, native source owns public
projections and terminal evidence, and this owner implements numerical contracts/publication.
The original TOKEN2 readers and completed family79 packages remain unchanged in meaning.

## Fixed scientific contract

Each of two iterations has three independent 640-world cohorts, one per head, split into
512 TRAIN and 128 VALIDATION worlds. All three cohorts use the same immutable current
complete C/P/E bundle. Collection completes before any child update; no updated head changes
another cohort's continuation. Original qualified G3 CARD and PURCHASE are nonzero actors,
with their actual full offered probability distributions as fixed anchors. Original EXPANSION
reuses the exact **untrained** family75 initializer
`8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc`, with its scoped
reference prior. No trained75/78 head or fresh initializer RNG is introduced.
Raw T, other selectors and G3 rivals remain fixed.

The observation's `baselineIndex` remains the head's original residual-prior reference:
CARD ordinary M8 HARD, PURCHASE frozen F0 `PurchaseBaselineInput` HARD, EXPANSION qualified E.
The
counterfactual return reference `referenceIndex` is a separate recorded index and must never
rewrite `baselineIndex`. The fixed outcome-blind reference is the actual current-bundle
sampled action at the selected reservoir root: `referenceIndex == sampledCommandIndex`.
Reference plus up to eight uniform distinct nonreference actions use two
paired future streams. The reference delta is zero; other deltas are action-minus-reference
terminal returns, averaged over the two streams.

For each READY TRAIN root, gain is the **sum** over sampled nonreference actions of
`(pi_theta[a] - pi_current[a]) * delta[a] / inclusion_probability`. Roots have equal weight.
There is no sampled-action renormalization, action mean, PPO clipping, value fit or winner
label. Missing and singleton roots remain explicit; no labels or fit observations are invented.
Each head requires the unchanged public exposure floors of 256 TRAIN and 64 VALIDATION READY
roots before fitting.

Each head uses all five existing 64-width actor parameter arrays. Its own two value arrays
remain exactly frozen, including the original nonzero C/P value parameters. Adam is fresh
per head and iteration: 20 fixed epochs, 64 requested-world slots per minibatch, learning
rate .0003 and aggregate gradient norm .5. Original-policy KL is `KL(pi_theta || pi_original)`
with coefficient .1. Anchors are equal represented worlds, then equal deduplicated states:
original iteration-1 TRAIN backbones plus fresh iteration-2 TRAIN roots. Empty groups are
counted but excluded. The full anchor mean must be <= .02 and maximum <= .10 after every
epoch. The first violation restores actor and Adam together and ends that child fit. Epoch
zero is diagnostic only. No accepted actor change in any head stops the whole bundle.

## Smallest numerical interfaces

New strict types own `Head`, `FrozenActor`, `Bundle`, `Context`, `PublicRoot`, `PairedTarget`,
`AnchorWorld` and `Training`. Every context pins head, iteration, whole corpus/request bank,
stable complete ecology scope, raw and semantic dictionaries, original/current head subjects,
and original/current **complete bundle** subjects. A head cannot be silently aliased to TOKEN,
another head or another bundle. Public rows preserve raw observation, complete probabilities,
action indices, reference/sample indices, absolute decision and batch-start identities. Label
objects remain detached and cannot carry actor features.

CARD uses its existing schema-1/card contract (up to 1,024 offers), PURCHASE its existing
schema-1/purchase contract, and EXPANSION the explicit schema-2/EXPANSION_2 contract. Original
C/P probabilities are verified by the actual original actor on admitted public inputs before
any update; they are not compared with a zero-output prior. Current behavior probabilities
receive the same independent forward check. No original C/P weights are reinitialized.

The proposed public API is `fit_bundle(trainings_by_head, original_bundle, current_bundle,
dictionaries_by_head, output, configs_by_head, previous=None)`, followed by
`seal_validation(bundle_completion, load_public_by_head, output)`. Completed bundle admission
requires all three exact child receipts and the original source/corpus/bundle identities.
The campaign owns the later label callback, gated on both iterations' complete probability
seals. A separate pure evaluator reports each phase descriptively and the predeclared equal-
phase mean. Its variance is `sum(phase_variance / phase_ready_worlds) / 9`; it does not pool
unequal phase counts or treat sibling futures/windows as independent samples.

## Reuse and publication

Factor the fixed tensor operations from `token_paired_fit.py` into a small shared owner:
gain, forward KL, bounded forward batching, anchor metrics, minibatch accumulation and epoch
rollback. Keep TOKEN-specific type/admission/publication wrappers strict and prove numerical
parity with the old owner. Reuse `purchase_policy.ResidualPolicy`, existing head contracts,
Adam encoding and canonical numerical actor identity. Do not copy a second learner pipeline
or create a generic experiment framework.

CARD/E full offers can exceed the existing 16,384-action forward cap across 64 roots.
Accumulate correctly weighted gain gradients over bounded forward chunks, accumulate anchor
gradients, then clip and step **once** for the 64-world minibatch. The TOKEN path remains one
gain chunk and preserves its original arithmetic order. This is a memory boundary, not a
change in loss normalization or Adam step count.

Child fit files are diagnostic transaction members. The top-level next-bundle descriptor and
completion receipt publish only after all three successful child updates, immutable subject
rechecks and value/scope checks. A failure preserves children and diagnostics but exposes no
consumable next bundle, no shortened candidate and no earlier-head fallback. Iteration 2
consumes only the completed iteration-1 bundle; optimizers remain fresh per iteration.

## Focused source proof

Constructed tests cover exact old TOKEN numerical/gradient/rollback parity; nonzero C/P
probability anchors; differing prior and delta-reference indices; exhaustive tiny uniform-
subset expectation and gradients; 1,024-offer chunking with one Adam step; all five actor
arrays/value freeze; equal-world anchor weighting; original-anchor retention across iterations;
head/bundle/dictionary/phase tampering; all-three atomic failure/publication; and both-
iterations/all-heads probability sealing before labels. No scientific model or row is needed.

The campaign separately owns resource feasibility, the 76,070-terminal/18-hour/48-GiB caps,
source/package admission, global clocks, exact full-bank final screen and later qualification.
Only final iteration 2 can be eligible. The balanced reservoir curriculum is not deployment
visitation and does not prove that unilateral action improvements solve all complementarity.
# Numeric interface

`cpe_paired_policy.Bundle` binds exact portable descriptor bytes plus three explicitly supplied
immutable actors. Published paths are `heads/CARD/policy.json`, `heads/PURCHASE/policy.json`,
and `heads/EXPANSION/policy.json`. Raw native dictionaries and their canonical complete semantic
hashes remain separate. `Context` includes the common `bundle_scope_sha256`, head-specific scope,
ecology, whole original/current bundle subjects, corpus receipt and request-bank subjects.

`fit_bundle(trainings, original, current, dictionaries, output, configs, previous=...)` requires
exact `Head` maps. It admits all three cohorts before the first update. Each child retains its
policy, five-slot Adam checkpoint, identity, complete epoch history and completion receipt under
`heads/HEAD/`. Only three successful children permit `bundle.json` and root `finished.json`.
A stopped or failed transaction preserves diagnostics but has no consumable next bundle.

`seal_validation(..., load_public)` invokes `load_public(Head)` for the128 original public VAL
positions and publishes one all-head probability seal. CPE reservoir roots are only READY or
MISSING; singleton backbone states remain valid zero-KL anchors. The immutable root delta
reference is `referenceIndex == sampledIndex`, separate from the observation's original prior.

`validate_seal_pair({1: subject, 2: subject})` is the intrinsic all-head/all-iteration barrier.
`evaluate_validation(subjects, output, load_labels)` runs that barrier before its first
`load_labels(iteration, Head, exact_seal_subject)` callback. It computes the declared paired
world estimates and equal-phase mean/variance, without any model call or checkpoint selection.

`cpe_paired_completed.verify(directory)` shares the intrinsic completion checks used during
publication and returns checked artifact pins. Its stdlib-only `verify_effects(subjects, report,
load_labels)` repeats the exact sealed paired arithmetic after the same two-bundle/two-seal
barrier. It imports neither Torch nor NumPy. It verifies preserved value parameters numerically
as Float64, so JSON spellings `0`, `0.0` and `-0.0` do not fabricate movement or a value change;
raw model hashes and all metadata remain separately exact.
