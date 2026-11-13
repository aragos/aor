# Common feasible interval calibration

Family82 is a source-only prerequisite authorized after family81 stopped at its fixed
calibration gate. It implements the prospective proposal
`2c73f8f0139a064029240a456c4c722fa434cb622c2e480e39cce2ead01ca301`.
No family81 panel, model, direction, world or outcome is an implementation fixture.
Only constructed fixtures are admitted until the next root source review.

The [coordinated ES contract](coordinated-neural-es-campaign.md) continues to own the
executor, optimizer, original four heads, rival ecology, public panel selection,
known-sign mean, game limits, fixed host ownership, storage and original clocks.
This change replaces only the versioned public calibration rule and its admission.
V1 protocol/receipts retain their exact interpretation; there is no fallback from V2
to V1. The explicit protocol calibration kind selects the numerical and native receipt
version. The outer V2 seal is `coordinated-es-calibration-2`; each head's recipe/receipt
kind is `coordinated-es-feasible-gap-calibration-2`. The gap-panel schema is unchanged.

## Eligibility and strict movement intervals

For each E/T head, retain at least128 represented worlds and the existing equal-world,
then equal-unique-state integer weights. The32 mutants and prescribed known-sign mean
form separate distributions. Both still require positive-gap mass at least10% and the
existing inverse-CDF q90 strictly greater than1e-8. Do not remove these guards when
adding interval feasibility. There is one calibration before semantic reward access;
actual live-head movement remains diagnostic only.

Let G be a signed largest nonreference residual gap, with positive integer weight and
total mass N. In exact arithmetic, movement at positive scale c is P(G>1/c), because
the incumbent wins exact ties. The permitted changed mass is the integer interval
`ceil(N/20)..floor(2*N/5)`. Preserve tied gap atoms; never interpolate percentiles.

The lower gap is the positive atom whose strictly-above mass is smaller than the
minimum changed mass and whose above-plus-at mass reaches it. Equivalently it is the
first ascending atom with cumulative mass strictly greater than19N/20. Its scale bound
is open: c>1/lowerGap.

If total positive mass exceeds the maximum changed mass, the upper gap is the positive
atom whose above mass is at most that maximum and whose above-plus-at mass exceeds it.
Equivalently it is the first ascending atom with cumulative mass at least3N/5. Its scale
bound is closed: c<=1/upperGap. Otherwise there is no finite upper bound from that
distribution: upperGap is null, upperAboveWeight is its positive mass and upperAtWeight
is zero. These rules specify atom and strict-tie behavior even at exact5%/40% boundaries.

Intersect each distribution's interval with the unchanged closed scale bounds[1/32,32].
Compare an upper gap against1/32 before taking its reciprocal: a gap at most1/32 supplies
no bound tighter than32, avoiding reciprocal overflow. Keep endpoint inclusivity explicit.
An original open lower bound remains open when it equals the scale floor; a strictly
larger floor is closed. Intersect the two clipped distributions, combining inclusivity
at equal endpoints. Empty or zero-width intersections stop conservatively, even when a
single closed point might pass. There is no singleton exception.

Choose exactly `sqrt(lowerScale * upperScale)` in Float64. The clipped product is safe
from overflow/underflow. Require a finite result strictly between both endpoints; if
rounding reaches an endpoint, stop. Do not use nextafter, neighboring scales, a search
ladder or alternate probes. Evaluate retained witnesses once with the actual selector's
parenthesization: `scale*alternativeMaximum > 1 + scale*reference`. Both rates must still
lie in[.05,.40]. This final check catches floating-point differences from the analytic gap
inequality; failure stops without changing the scale. No model re-forward is needed for
pure receipt auditing.

## Versioned evidence and implementation seams

The V2 per-head receipt retains all V1 subjects, coverage, distributions, exact movement
counts, scale and outcome-blind flags, with its new exact recipe. It additionally binds
`intervals` keyed by `mutants` and `prescribedMean`. Each interval records:

* `minimumChangedWeight`, `maximumChangedWeight`;
* `lowerGap`, `lowerAboveWeight`, `lowerAtWeight`;
* `upperGap`, `upperAboveWeight`, `upperAtWeight`;
* `lowerScale`, `upperScale`, `lowerInclusive`, `upperInclusive`.

`commonInterval` records the last four fields for the intersection. Native admission
checks the exact recipe, thresholds/count bounds, clipped intervals, common intersection,
midpoint and actual movement receipt. The pure numerical auditor recomputes the whole
record from the original retained signed-gap witnesses. Both generation and final native
admission join the protocol calibration version to the outer seal version before use.
Malformed, mixed-version or unbound receipts fail; they cannot select a legacy branch.

Use a small pure `coordinated_es_calibration_v2` owner sharing the immutable V1 GapPanel,
weighting and actual-score helpers. Keep V1 calibration arithmetic unchanged. Extend
the existing Plan, numeric transaction, pure generation audit and native typed receipt
dispatch; do not create a second campaign/controller or duplicate the pipeline.
Prospective package construction selects the exact V2 protocol recipe explicitly.
Operational stop classification includes empty/degenerate interval and no Float64
interior, in addition to the existing eligibility and actual-score bounds.

Focused constructed tests cover weighted atoms and exact5%/40% endpoints, missing and
ineligible worlds, no finite upper gap, disjoint/degenerate intervals, scale clipping,
rounded midpoint endpoints, actual-score common-mode rounding, receipt tampering,
strict version joins and unchanged V1 results. Native fixture evidence and logs must be
copied to immutable artifact paths before any later Gradle invocation overwrites scratch
test outputs. Finalize the native JAR before future scope/ecology/profile generation.

Only a later root review may authorize actual originals, fresh82 projection/direction/world
streams or preparation. All16 generations,32 mutants,256 worlds per generation,
2048 final pairs,139306 maximum terminal executions, shared24h+900s audit, host caps,
no retries/top-ups/shortened nominees and no qualification/activation remain unchanged.
Family81 stays closed; all existing previews and frozen packages remain untouched.
