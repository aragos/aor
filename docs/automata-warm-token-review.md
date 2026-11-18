# Warm TOKEN learning: optimization and pilot review

This is a source and literature review, not an experiment result. No new scientific games,
corpora, real-model forwards, numerical fits or random streams were executed for it. The source
checkpoint is `8cfa68c`; the unattended warm campaign is not yet assembled or approved to launch.

## Recommendation

Keep the paired-continuation foundation, but do not spend the proposed 14,416 collection runs
with the current recipe unchanged. Separate three questions: can the policy represent the needed
response, do a few paired continuations provide useful action credit, and does a locally improved
policy improve complete games? A small fixed experiment can test these before a long campaign.

The most concerning source issue is not the lack of randomness in the new sampler. It is the
combination of a strongly concentrated frozen reference, a `.1` penalty towards that reference,
no proximal constraint towards the actual collection policy, and an observation that omits public
bid history even while an opponent explicitly exploits that history.

## What the current objective actually estimates

The warm policy uses `p0(a|o) = .8 p94(a|o) + .2/N` and
`pi_theta(a|o) = softmax(log p0(a|o) + delta_theta(o,a))`. The new residual copies frozen94
embeddings and has a separate zero actor/value readout. All original offered actions have initial
positive probability. There is no permanent final probability floor or residual clamp.

For one captured root, the fitting gain is

```
sum over selected a != reference:
    (pi_theta(a) - pi_collection(a)) * (return(a) - return(reference)) / inclusion(a)
```

The reference and useful neighbors are mandatory. Other actions are sampled uniformly without
replacement. Using inclusion one for mandatory arms and `k/(N-M)` for random arms gives the
correct conditional Horvitz–Thompson action-sum estimator. A single legacy inclusion scalar would
be wrong. The checkpoint includes an exhaustive invented-subset test checking both value and
gradient. Shared chance streams and the same sealed rival commitments provide genuine paired
credit; a random action receives its proper weight even when the actually sampled policy action
is mandatory. See [the estimator](../scripts/paired_policy_math.py) and
[the warm public boundary](../scripts/token_anchored_data.py).

This is unbiased for the declared sampled-root surrogate, not automatically for the complete-game
policy gradient. Roots are requested by seat, epoch and token-window ordinal, and fitting conditions
on READY branching roots. Equal weighting of these roots differs from natural on-policy decision
occupancy. The changed actor then replaces all controlled TOKEN decisions in complete games.
Twenty epochs on one corpus can therefore improve the surrogate while worsening later state
visitation. The [collector](../training/src/main/kotlin/dev/aragos/aor/training/TokenPairedCollector.kt)
and `TokenPairedRequest` own that distinction.

TRPO's vine sampler is a close methodological relative: reset a simulator to sampled rollout
states and branch actions to estimate their values. Its discussion explicitly trades better
advantage estimates for more simulator calls. Its trust region is towards the collection policy,
under that policy's state distribution. Our action branching is useful, but our original-only
constraint does not implement that safeguard. [TRPO, sections 4–5](https://proceedings.mlr.press/v37/schulman15.pdf)

## The reference penalty can preserve the exploit

The staged warm recipe raises the old mean/max KL limits from `.02/.10` to `.25/.75`, but retains
`originalKlCoefficient=.1`. In an isolated state with matching objective/KL weighting, maximizing
`E_pi[Q] - beta KL(pi||p0)` has solution `pi(a) proportional to p0(a) exp(Q(a)/beta)`.

An illustrative calculation, not a measured game result:

| Offered actions | Initial mass on preferred bid | Initial mass on one neighbor | Neighbor mass after a genuine +.05 win advantage, beta=.1 | Same calculation, beta=.01 |
| --- | ---: | ---: | ---: | ---: |
| 43 | .80465 | .00465 | .00765 | .40951 |
| 75 | .80267 | .00267 | .00439 | .28409 |

With 75 actions, overcoming the initial preferred-bid odds requires about `.571` win advantage
when beta is `.1`. These are unconstrained per-state optima, not predictions for the shared neural
network; actual anchor/root weighting and hard limits also matter. They demonstrate why loosening
the cutoff alone can leave the policy essentially anchored to the predictable action.

Use a smaller or scheduled frozen-reference penalty as an explicit ablation. Separately constrain
the update relative to `pi_collection`; this controls stale-surrogate error without permanently
trapping the policy near the original decision. Keep the original reference immutable for identity
and diagnostics even if it is no longer the strong optimization target.

Magnetic mirror descent makes this distinction explicit: its update has a penalty towards a
reference policy and a separate penalty towards the current policy. Its experiments also examine
annealed regularization and a moving reference. These are hypotheses worth testing here, not
four-player convergence guarantees. [MMD, equation 10 and appendix H.5](https://arxiv.org/pdf/2206.05825)

Ataraxos coordinates regularization strength with update size, rather than retaining one strong
fixed penalty indefinitely. Its setup component favored Monte Carlo advantage estimates, whereas
its move component used lambda returns. This supports testing regularization schedules and argues
against assuming that adding a TD critic automatically improves our labels. [Ataraxos, Methods](https://arxiv.org/html/2511.07312v2)

## Representation and action sensitivity

TOKEN2 includes public resources, capital-choice priority, previous turn order, exact signed-bid
categories and projected purchase consequences. It does not encode public past bids. Consequently,
two positions that differ in exploitable bidding history can receive identical actor inputs while
the new public-history counterbidder chooses different responses. More optimization cannot recover
information absent from the observation.

The next representation should expose bounded, public, strictly past-turn bidding history and
candidate-specific uncertain order consequences. It must not include the rivals' currently sealed
bids, actual future order or hidden cards. Such outcomes can supervise a predictor during training;
they cannot become inference features. The existing forked counterbidder history is a useful
source of semantic tests, not a substitute for the actor's own observation contract.

Before collecting expensive labels, measure on public pilot states whether a bounded update can
actually change conditional action probabilities and how much raw-logit separation it requires.
Measure probability changes, collection/current KL, residual spread and gradient norms. Parameter
RMS movement or changed weights alone do not establish changed decisions. The new zero-head
gradient test proves arithmetic is live on invented inputs; it does not prove useful real-state
generalization. [Policy owner](../scripts/token_anchored_policy.py)

## Alternatives and sample efficiency

Whole-game ES remains useful for discrete strategy parameters or genuinely low-dimensional
behavioral changes. It is a poor default for discovering a subtle bid distinction through a tiny
random weight subspace when most perturbations produce the same actions. The original ES work
emphasizes distributed scaling; its reported competitive results often consumed several times
the data of policy-gradient alternatives. That is a tradeoff, not evidence that ES cannot work.
[Salimans et al.](https://arxiv.org/pdf/1703.03864)

A learned all-action advantage critic could amortize repeated branches. COMA demonstrates an
efficient counterfactual baseline that holds other agents' actions fixed and evaluates alternative
actions in one critic pass. It is a cooperative algorithm, so importing it wholesale would be
unjustified. First retain our authoritative Monte Carlo targets and test a cross-fitted critic as
a variance-reducing control variate, rather than replacing trustworthy labels with bootstrapped
values immediately. [COMA](https://arxiv.org/pdf/1705.08926)

Deep CFR supports the broader idea of learning action advantages and exploring alternatives,
but our collector is not CFR: it has no counterfactual reach weighting, regret accumulation,
average-strategy model or equilibrium guarantee. Its formal guarantees concern two-player
zero-sum games with perfect recall. [Deep CFR](https://proceedings.mlr.press/v97/brown19b/brown19b.pdf)

NFSP is relevant to opponent diversity: its experiments distinguish a stochastic average-policy
learning process from a highly exploitable deterministic best response. It motivates preserving
historical policies and a fixed exploitation league, not treating wins against one current opponent
as universal improvement. [NFSP](https://arxiv.org/pdf/1603.01121)

## Statistical and deployment safeguards

Use independent worlds as the uncertainty unit. Arms, two futures, paired roles and repeated
controls within one world are correlated. Keep complete controls identical across candidate cases;
keep all shared-world results together in confidence calculations. At 512 worlds and paired
standard deviation .5, a normal 99% half-width is about .057. The actual variance is unknown and
must be measured; a narrow three-point noninferiority margin may require substantially more data.

A pilot can support a mechanism, reveal a broken estimator or rule out a useful effect at its
precision. Failure to obtain significance with a small bank does not by itself falsify learning.
Predeclare minimum useful effects, confidence rules, allocation changes and a maximum budget.
Do not repeatedly examine a qualification bank and add models or samples until one passes.

AIVAT shows that known strategies and valid control variates can substantially reduce stochastic
evaluation variance without bias. That result is an avenue for a later evaluator; ordinary paired
seeds are not already an AIVAT implementation. [AIVAT](https://arxiv.org/pdf/1612.06915)

Few-run RL comparisons need uncertainty reporting; one lucky training run is not reliable evidence
of an improved training method. Report distributions across future independent campaigns as well
as paired game intervals for a particular frozen candidate. [Agarwal et al.](https://proceedings.neurips.cc/paper_files/paper/2021/file/f514cec81cb148559cf475e7426eed5e-Paper.pdf)

Collection, fitting and runtime must use the same anchored law and sampled selector. The checkpoint
separates warm/legacy corpus, actor, objective and completion versions. It preserves raw-reference
versus frozen94-reference tie behavior and rejects numerical probability underflow. Human games
remain diagnostic only. Validation probabilities should be persisted before diagnostic label
decoding; qualification worlds must be fresh after recipe and model selection.

## A bounded falsification pilot before the large run

1. Keep the proposed 12-root engineering/resource pilot: at most 303 independent simulation runs
   including three backbone reproductions. Check actual Native/Python probability parity, public
   features, focused inclusion weights, branch/history isolation, bytes and throughput. No fit or
   quality-based candidate selection occurs in this phase.
2. Predeclare a separate 48-world learning probe: 32 TRAIN roots and 16 sealed diagnostic roots,
   twelve arms, two shared futures and four prescribed backbone reproductions. This costs at most
   1,204 simulation runs. Measure nonzero paired-signal frequency, between-future disagreement and
   world-cluster uncertainty before extrapolating required sample counts.
3. On the same TRAIN corpus, fit two prospectively fixed alternatives: the current original-only
   penalty and a weaker-original/proximal-to-collection update. Compare to a per-root closed-form
   action-space update as an optimization diagnostic. Neither alternative may select labels or
   actions after viewing held-out returns. Seal all held-out predictions first.
4. If the local mechanism survives, compare both fixed learned actors and the untrained warm
   initializer against frozen94 on 64 fresh complete worlds. Three paired cases require at most
   408 runs including four two-role reproductions per case. Repeated94 controls must match exactly.
   This is a transfer diagnostic, not human-play qualification.

The complete proposal has a 1,915-run ceiling, before a separately approved larger experiment.
The exact alternative recipes, thresholds and clocks still need a prospective protocol. Useful
failure distinctions are: insufficient action sensitivity; absent/noisy causal win signal; local
overfit despite signal; and positive local gain that fails to transfer to full play. They lead to
different next steps, unlike another undifferentiated long campaign.

## Unattended execution

The existing clock, approval, bounded worker transport, process cleanup and immutable receipts are
adequate foundations. A single validated declarative DAG can run fixed collection, fit, publication
and evaluation stages with generated models bound to their sole completed predecessors. No new
source registration or model-agent wakeup is needed per round.

Finish that DAG only after the review fixes the learning recipe. Retain static models once,
compressed authority evidence once per producing host, small public/label projections for fitting,
and explicit attempted/completed/replay counters. Preserve the original deadline on interruption.
Mutable progress status must stay outside immutable scientific artifact pins. The current source
checkpoint deliberately claims no completed driver or launch-ready campaign.
