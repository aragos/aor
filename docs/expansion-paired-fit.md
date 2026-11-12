# Expansion paired-Q experiment

This is a fixed research recipe for the `aor-expansion-paired-observation-1` data contract.
It does not modify E, deploy an expansion policy, extend the old territory loader, or claim
whole-game improvement. Its labels describe one offered first action followed by unchanged G3
continuation. The frozen family72 TRAIN/VALIDATION/TEST partition and missing requests remain intact.

## Fixed model and objective

Use one 64-wide network: `h=tanh(Ws*x+bs)`, `z=tanh(h+Wa*a+ba)`,
`q=sigmoid(w*z+b)`. Warm only state columns whose names and units are verified against the common
`RawActorState` owner, plus the state bias, from the exact G3 PURCHASE head. Unmatched state columns
and action bias are zero; the action matrix has one prospectively frozen Glorot initialization;
the scalar head is zero. Transfer no action/value weights or optimizer state. Initial Q is 0.5
for every action. Verify the actual hidden-state function on shared-column synthetic inputs;
constant output predictions cannot establish transfer parity.

Train every parameter with fresh Adam, learning rate 0.001, for all 20 epochs, with batches of
64 whole origins and prospectively frozen initialization/shuffle streams. No hyperparameter grid,
early stopping, reseeding or optimizer restart is permitted. Each captured origin contributes
equally. Its loss is the mean squared error of `q(action)-q(E)` against the mean of its two paired
terminal-return differences, over non-E arms, plus 0.1 times mean absolute-return squared error
over all arms including E. Rewards retain the collection's original terminal composite. Missing
origins contribute no fabricated states or labels.

## Coverage and selection

Before labels, require at least 256 captured TRAIN and 64 captured VALIDATION origins with E and
a distinct proposed action. Proposed card acquisition must occur in at least 64 TRAIN/16
VALIDATION origins; proposed placement or attack must occur in at least 64 TRAIN/16 VALIDATION
origins. These groups may overlap. Preserve all missing requests and descriptive phase, seat,
ordinal and action-family counts. Failure closes this recipe without relaxing floors or refilling.

The selector scores only the frozen proposed actions. Choose a non-E action only when its Q is
strictly above Q(E); exact non-E ties use canonical command order. Proposal reason, cohort,
ordinal, seed, future information and return are not model inputs. The reference index identifies
the frozen E action, not a learned feature.

Fit all 20 checkpoints with TRAIN labels only, then persist all checkpoint VALIDATION predictions
and choices before joining VALIDATION returns. Select the largest equal-origin mean paired
selected-minus-E reward. E is an explicit zero-gain candidate and wins every zero-gain tie.
Positive exact ties use lower paired MSE, then earlier epoch. MSE versus zero difference and the
TRAIN-mean difference is diagnostic; it cannot rescue nonpositive decision gain.

If E wins, leave TEST closed. Otherwise require at least 64 READY TEST origins using only status
and command-count metadata. Insufficient coverage stops before TEST feature or label consumption.
For the selected checkpoint alone, persist every TEST prediction and choice before opening TEST
returns. Report equal-origin paired reward gain and a fixed 95% interval; win and Chaos differences
are descriptive. A positive lower reward bound supports ranking within the sampled action sets
under G3 continuation, not unrestricted action selection or whole-game strength. No alternate
epoch, threshold change, refit or fallback follows TEST.

## Execution boundary

The proposed numerical ceiling is one 1,800-second CPU1/GPU attempt, 6 GiB RSS and 2 GiB derived
output, followed by at most 900 seconds for read-only audit. No game simulation is part of this
recipe. Source and synthetic tests are authorized separately from any real feature export,
semantic labels, fit, inference or seed reservation. A future execution package must bind the
completed72 corpus, exact G3 head, complete encoder dictionaries, shared-column mapping/owner
proof, numerical environment, all streams, resource bounds and this contract before execution.
No scientific streams are assigned by this document.
