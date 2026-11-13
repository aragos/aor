# Future explicit CUDA Float64 CPE kernel

This isolated source prerequisite leaves family80's CPU campaign and every scientific
artifact unchanged. It adds no runner, initializer, model, data reader or scientific fit.
The constructed benchmark in `automata-cpe-compute-profile-20261113/report.json`
(`c314f1a01178557306a7cdd3483e03b1b3846892b534db057ed0127b058e0e88`) motivates
device work: it measured kernel speedups, not complete-fit speedups.

## Fixed boundary

The future backend is explicitly `cpe-cuda-float64-1`, device `cuda:0`, Float64 only,
deterministic algorithms, `CUBLAS_WORKSPACE_CONFIG=:16:8`, no TF32 or AMP, and one CPU
thread. It is a constructed kernel interface, not an accepted family80 fit identity.
Existing CPU constructors, completed receipts, model dictionaries and rejection contracts
retain their defaults. A future scientific CUDA runner would need separate source/package
admission and a backend-bearing transaction contract; this change supplies neither.

The three current/original actors keep their native semantics, including nonzero G3 C/P
anchors, the scoped E prior and frozen value arrays. Gain, original-policy KL, weighting,
20-epoch recipe, 64-world clip/Adam boundary, and actor-plus-Adam rollback do not change.
No scientific inputs or labels are available to the new interface. Existing atomic
three-head publication and both-iteration VAL barriers remain CPU-only and unchanged.

## Small implementation

Keep the existing CPU path arithmetic in its current owners. Extract only cohesive tensor
construction/forward hooks needed by an explicit CPE CUDA subclass, plus shared loss terms
where necessary. CUDA prepares sparse indices, values, priors, sampled-action indices and
fixed probability/target tensors once before the hot loop. Forward chunks allocate and
combine tensors on the declared device. No policy or data tensor silently changes device
inside a forward, root loss or anchor loop; explicit scalar diagnostics cross only at
minibatch/epoch boundaries. Reject raw/unprepared CUDA input at that boundary.

Reuse the existing world/action batching, gain/KL formulas and epoch snapshot/rollback
owner. Device-specific accumulation may avoid per-root scalar synchronization but still
clips and steps once for the original64-world minibatch. A new portable constructed
fixture exercises all three head contracts without constructing a fake campaign or
publishing candidate weights.

## Proof and resource limits

Local checks compare the default CPU path with the original source arithmetic exactly,
including singleton/exact-prior behavior, gradients and rollback. A bounded existing-venv
GPU proof compares constructed CPU/CUDA forward probabilities, selected log probabilities,
gradients, one Adam step, optimizer moments, frozen values and rollback within `2e-12`.
Limits or disagreement are reported; tolerances and recipe are not silently relaxed.
The proof includes large-offer microbatches with one clip/Adam step and rejects missing
deterministic configuration, wrong devices/dtypes and use through the old CPU fit path.

Remote proof invocations last at most120seconds, require at least8GiB available host memory,
use at most1GiB additional device memory and3GiB host RSS, and use the installed GPU venv
without downloads. Inputs are handwritten/constant constructed vectors, with no retained
models, worlds, validation data, initialization streams or candidate artifact. These
proofs do not authorize scientific preparation, execution, promotion or activation.
