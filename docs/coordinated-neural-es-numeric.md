# Coordinated neural ES numerical prerequisite

This isolated family81 source starts at family80 `8b7c6f66`. The approved direction
proposal is `b3ba70c4…12340`; its final supplement is `0f9fc4b5…a4906`.
Only constructed inputs are permitted during implementation. Existing models, datasets,
streams, fit receipts, campaigns and previews remain untouched.

## Typed boundary and ownership

The numerical owner supplies a fixed projection, normalized antithetic update, signed-gap
calibration and a small explicit-file adapter. The campaign owns clocks, world scheduling,
the calibration-before-fitness barrier, archives and final evaluation. Native/common
owners supply the four-head controller and public observations; no numerical module
implements simulation, policy selection from outcomes or a campaign loop.

Head order is exactly CARD, PURCHASE, EXPANSION, TOKEN, with64 coordinates per head.
C/P originals are the unchanged qualified nonzero G3 actors. The proposed E/T bases are
the original untrained75 E and original untrained79 TOKEN2 initializer; parent/root must
bind their exact subjects before preparation. No trained75/78/79 actor is a base.
Ordinary M8/F0/E/T references and their observation indices remain native-owned.

The versioned projection binds all four original raw model hashes, complete semantic
dictionaries, the protocol scope and its serialized factors. It cannot reinterpret an
old CPE, TOKEN or sampled-E fit identity. Values remain byte-semantically unchanged.
At zero coordinates the materializer copies the original actor arrays exactly.

## Fixed all-layer representation

Each head has64 independent coordinate directions. For each coordinate, independent
state and action matrix directions are rank-one outer products. Three further vectors
perturb state bias, action bias and actor readout. Normalize every factor/vector row to
RMS1. Combine coordinates with a factor1/sqrt64. Matrix block scales are1/sqrt(S) and
1/sqrt(A), where S/A are the complete state/action feature counts. Both hidden biases
and the64-element readout have scale1/sqrt64. Values are never part of the projection.

These are prospective representation constants, not return-calibrated hyperparameters.
Serialize and pin the complete factor map before scientific preparation. A prospective
explicit NumPy PCG64 seed generates standard-normal factors in head order, then block
order state-left/state-right/action-left/action-right/state-bias/action-bias/readout;
normalization uses Float64. Actual seed allocation/use requires the later root review.
Constructed tests may use literal toy factors and designated synthetic seeds.

## One normalized update

Each generation retains16 independent256-dimensional directions and both signs, on the
same256 ordered independent world identities. Fitness is the raw mean normalized reward
including exact Chaos zero. With sigma1, compute

`g = sum_j((mean(Rplus_j)-mean(Rminus_j))*epsilon_j)/32`.

Stop on nonfinite input/result or RMS(g)<=1e-12. Otherwise take `u=z+g/RMS(g)` and
`next=u/max(1,RMS(u)/4)`. Normalize all256 coordinates jointly, without per-head scaling.
There is no rank shaping, Adam, reward standardization, weight decay, candidate choice,
momentum or replacement. Mutants are `z±epsilon`, without the center's radius projection.
Only the predetermined16th mean can become the final candidate.

The update record binds the prior center, exact ordered direction/population/world
subjects and raw fitness. It retains the gradient, RMS, projection divisor and next
center, so a pure auditor can reproduce arithmetic. Partial or mismatched populations
produce no next center. The fixed mean calibration probe uses this same update owner
from zero with `v_k=(-1)^k` and artificial differences `2*(v dot epsilon_j)/256`.

## Signed-gap calibration

The panel contains all256 generation1 raw-G3 world positions, including missing ones.
For E/T independently, native extraction selects the first and last branching states
in the assigned epoch and deduplicates a sole state. Require at least128 represented
worlds. The numerical gap record binds head/world/absolute-decision/batch-start identity,
the public observation, reference index, original/projection/direction/probe subjects,
and all32 mutant gaps plus the prescribed mean gap. It contains no terminal reward.

Each signed gap is `max_nonref((r_mut-r_original)[a]-(r_mut-r_original)[reference])`;
the old sampled prior is excluded. Worlds are equally weighted, then their one or two
states; mutants receive equal probe weight. Integer weight units make the inverse-CDF
90th percentile boundary exact. Both mutant and mean populations require at least10%
positive gap mass and q90>1e-8. Set `scale=(1+2^-20)/min(q_mut,q_mean)` once and require
1/32<=scale<=32. With this same scale, both weighted movement rates must lie in[.05,.40].
Failure is an operational stop, never a scale search or panel replacement.

E/T scores are `1[action==reference]+scale*(r_mut-r_original)`. Scores must be finite;
the reference wins a tie, then the lowest original offered index. Thus zero coordinates
preserve raw reference behavior exactly. Actual live-mean E/T movement on the fixed panel
is diagnostic only; it does not create an eligibility gate or alternate candidate.

## Verification

Constructed tests cover exact-zero materialization/value freeze, all five actor blocks,
head/feature/scope binding, known-sign/global normalization, raw-return mean arithmetic,
zero/nonfinite stops, center-radius projection without mutant projection, weighted
quantile ties/missing worlds, signed rather than absolute gaps, the prescribed-mean
probe, one-scale movement admission, incumbent ties and diagnostic-only live movement.
No scientific model, world, label, seed or forward is opened by these checks. Native and
production-JS parity, exact source hashes and actual host resources remain separately
reviewed preparation prerequisites.
