# Direct paired TOKEN2 numerical contract

This is the source-only numerical prerequisite for family79. The campaign owns source,
seed, runtime, resource and complete-corpus admission. No model, corpus, seed or fit is
authorized by this document. Legacy TOKEN1, expansion MC, triple MC and PPO remain unchanged.

The new actor is explicitly schema3, `aor-token-observation-2`,
`aor-token-policy-2`: the existing Float64 residual state/action network with64 hidden
units. All five actor parameter arrays train; both value arrays remain unchanged.
The exact existing prior is1 for a singleton, otherwise .97 on the original T reference
and .03/(n−1) on each other offered action. Its logits are log(prior) plus the residual
clamped to[−20,20], with exact prior probabilities for equal residuals. There is no extra
probability floor, smoothing or sampled-support renormalization.

The sole initializer transfers canonical shared RawActorState state-weight columns and the
64 state biases from exact G3 Purchase, excluding phase-specific decision flags. The campaign
must bind the common feature units and actual source hash. A prospectively reserved seed
initializes novel TOKEN state and action coefficients; actor outputs stay zero, giving the
exact prior. This does not consume any75/78 trained expansion head or compare initializers.

Each of two iterations uses its separately completed frozen behavior actor πm; original
π0 never changes. Public input retains the full ordered offer, exact T reference index,
separate sampled behavior index, and full πm/π0 probabilities. The reference and K=min(8,n−1)
uniform distinct nonreference indices have two paired terminal returns with original stream
identities. Each nonreference inclusion probability is ρ=K/(n−1). The typed TRAIN reader
admits all512 requested worlds, retains missing and singleton records, and rejects validation
before touching its observation or labels. No action/outcome payload enters actor features.
Admission requires at least256 branching TRAIN roots and64 branching VALIDATION roots,
determined from public support before outcomes.

For each READY branching root, δa is the average of its two paired R(a)−R(reference)
contrasts. δreference is exactly0. The maximized gain is the equal-READY-root mean of
`sum_sampled_nonreference ((πθ(a)−πm(a))*δa/ρ)`. There is no additional action mean,
winner label, PPO clip, value fit or importance-ratio clip.

Subtract .1 times full-offer KL(πθ||π0). Fixed iteration1 TRAIN backbone observations are
grouped by original world, with equal weight per represented world and equal weight per
deduplicated state within it. Identity is(iteration,globalWorld,absoluteDecision,batchStart).
Iteration1's root already in that backbone is deduplicated. Iteration2 adds its fresh TRAIN
root-world groups to the original groups; the union has equal represented-world weight,
not a fixed half-mixture if representation differs. Empty groups remain counted but excluded
from the KL denominator. Singleton KL is exactly0. No validation features enter this anchor.

Each iteration uses fresh Adam(.0003),20 epochs,64 requested-world slots per minibatch,
and gradient norm cap.5. For all512 shuffled slots, gain contributions scale by512/Nready;
anchor group contributions scale by512/Nrepresented. This makes the average minibatch
objective equal the declared full-population means, including empty slots. A slot in
iteration2 owns its original backbone group and its fresh root group. No extra RNG is used
beyond the campaign's20 declared epoch permutations per iteration.

After each complete epoch, evaluate the entire retained TRAIN anchor: require mean KL≤.02
and maximum individual-state KL≤.10. A violating epoch restores both actor and Adam and ends
the fit, without retry. Nonfinite or identity failure aborts publication. Zero accepted actor
change is an operational stop. The last accepted policy is the only result; no epoch or
iteration is selected by outcomes. Iteration2 must consume the sole completed iteration1 actor
and the exact original π0, scope, dictionaries and original anchor bank.

The fit owner accepts TRAIN only and publishes model, full epoch history and completed
identity atomically. A separate public-only operation validates a completed model and persists
every full VALIDATION probability before any semantic validation labels are accepted. Validation
gain uses the same inclusion correction, averages paired futures within independent worlds,
and retains all missing/singleton coverage. No optimizer is reachable from that operation.

Source verification uses constructed observations/models only: exhaustive tiny-subset expected
gain and gradient; exact prior; full actor/frozen value scope; unequal proposal counts; missing
and singleton identity; global anchor weights; immutable π0 across iterations; accepted/rejected
epoch rollback; post-construction tamper; and prediction-before-label admission. Real source,
collection and model pins remain prerequisites to future separately reviewed execution.
