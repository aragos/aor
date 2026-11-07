# Open work

Unresolved development and operational/human checks; automated tests do not establish playing
strength or human acceptance. Current contracts live
with their [README owners](../README.md#find-the-owner); no gameplay defaults await approval.

## Deployment and recovery

- On Render, verify Google/signup/verification/reset, three-account isolation and restart persistence.
- Rehearse [complete SQL recovery](deployment.md#recovery-and-deletion), including accounts/permissions,
  memberships, histories/archives/receipts, pending secrets and closed games. Retain Firebase identity;
  record schema/revision, timings and continuity without secrets. Validate before single-writer cutover;
  agree retention/billing alerts. Existing file deployments need [import/cutover](deployment.md#import-file-storage).
- Verify deployed [Friendly-to-Linear migration](design.md#linear-game) on representative saves/both
  backends: prior edits/checkpoints, pending secrets/plans, concurrent writes, restart, idempotence and
  continued play. Record per-game outcomes/resolve failures before declaring rollout complete.
- Configure optional patch auto-merge and Render refresh credentials if wanted; see
  [maintenance](deployment.md#release-and-maintenance).

## Human review and maintenance

- Review full-session keyboard/screen-reader play, physical-phone touch/scroll, browser-chrome zoom and
  SVG/composited/native-control contrast. Record devices/tools/findings.
- Measure six-player conflict-heavy late-game load on production-like hosting before claiming capacity
  or changing transport/caching. `performance.browser.mjs` measures local timings; low-conflict Vite
  runs do not establish production latency or isolated React cost.
- Fix the HTTP I/O catch attempting a second JSON response after headers commit. Preserve pre-commit
  storage errors; allow a started response to terminate normally.
- Rules-2/3 Renaissance histories need a validated event mapping; retain continuity rejection until
  implemented. Matching older engines remain an alternative.

## Opponent evaluation

- Run the complete training matrix/independent strength evaluation before claiming measured difficulty
  separation or trained advantage. Artifact coverage gaps block promotion; fixtures do not prove strength.
- Consider observation-safe sampled continuations, beliefs and learned leaf values after reproducible
  contextual tuning; compare at equal browser budgets. Deferred features remain in [scope](requirements.md).

## Automata training pilot

Prove that offline lookahead can find better plans, that a small learned policy can reproduce them,
and that the resulting automata improve complete-game results. Retain the production rules engine,
observation privacy, canonical commands and browser execution described in [architecture](design.md).
Prioritize improvements that survive [complete-game comparisons](contributing.md#full-game-strategy-experiments)
against a frozen opponent population. The [commodity pilot](contributing.md#automata-experiments)
remains a focused diagnostic: resumable sessions, an epoch-three fixture teacher, a small four-choice
student and fresh held-out validation. Execution alone does not qualify a model for release.
[Contributing](contributing.md#opponent-training) owns training resource limits.

### Full-game policy bottlenecks

- Re-evaluate preserved models with the current packaged policy and actual browser fallback. Historical
  results and compatible metadata do not establish current strength. Compare a frozen diverse pool,
  rotated seats and paired seeds before changing the shipped asset.
- Search a small set of meaningful economic weights with zero-weight ablations: held-card expansion,
  public future commodity exposure, rival benefits and card-retention opportunity. Inspect replays for
  premature cash-outs and cards held beyond useful opportunities; a higher synthetic payout can still
  lose the complete game through liquidity, stabilization or displaced investment.
- Measure token purchases, market access, shipping and advance choices alongside commodity timing.
  A policy that values a future market still needs to buy enough tokens and acquire useful access.
  Align contested-market forecasts with the expansion evaluator: affordable attacks can still be
  rejected after tokens are bought. Inspect unused pools and the value assigned to expiring tokens
  before treating a larger affordable-market count as a useful investment.
  Current-route forecasts also omit peaceful markets opened by the forthcoming shipping and advance
  purchases. Evaluate joint token, capability and expansion budgets while charging every investment
  and retaining stabilization cash; simply adding current-route quantities cannot solve this horizon.
  Start with a bounded set of currently affordable shipping and mobility purchases, and share the
  resulting plan value between token and purchase decisions. Forecast only information available at
  that decision: later leader draws and other players' cash receipts cannot fund an earlier plan.
  Test stateful planning where current static candidate scores miss sequential costs, unlocks or
  increasing returns; qualify each addition against its browser computation budget.
- The [commodity-bundle planner](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotExpansionPlanning.kt)
  currently values certain land placements. Extend joint valuation to shipping only after
  fixed-position probes demonstrate useful choices beyond recalculating marginal values after each
  move; preserve shared resource budgets and each physical card's choice of commodity.
- Extend purchase planning only where replay evidence supports it. Remaining examples include
  prerequisites absent from the current legal offers, the stabilization misery cliff and changes to
  the bill after Middle Class, and sequential misery changes across several relief purchases.
  Individually exact quotes need not remain additive across a sequence. Preserve the distinction
  between immediate ability changes and category credits that become usable next turn.
- Keep screening, finalist selection and final confirmation separate. Select on meaningful full-game
  samples and reserve untouched seeds for a frozen candidate and predeclared acceptance target.
  Repeatedly inspecting a final bank makes it development data. Search winner intervals do not correct
  for selecting the winner. Preserve complete distributions for reproducible resumes and comparisons.
- Review whether compact feature search is still finding useful gains. If progress stalls, use replay
  evidence to choose broader action/value learning, targeted search teachers or population self-play.
  Keep the narrow commodity curriculum as a regression probe; more fitting alone cannot repair a weak
  teacher or missing decisions. Human sessions and difficulty separation remain required to establish
  that qualified automata present a serious challenge. A decisive loss against a human should
  trigger replay diagnosis and stronger evaluation opponents even when comparisons against their
  historical population remain positive.

### Commodity teacher and broader qualification

- Run the documented Mac experiment with its frozen compatible baseline, fixed four-player profile,
  varied seeds/seats and separate scenario validation and full-game evaluation. Review benchmark,
  teacher-label diagnostics, fresh validation and paired results separately. A failed validation gate
  skips the tournament; successful execution is not a strength result.
- Retain the installed distribution and baseline with the run artifacts. Resume fingerprints enforce
  the packaged code/model/settings identity; a rebuilt experiment needs a new output directory.
- Evaluate the predeclared ten-percentage-point replacement-seat win-share target and paired 95%
  lower bound. An inconclusive result requires a separately planned experiment with fresh final
  seeds. Do not tune on the final tournament or select models by agreement with the original heuristic.
- The teacher uses synthetic epoch-three positions with publicly empty rival hands and a known
  remaining-card multiset. It now includes multiple own cards, varied cash/deck horizons and three
  public advance/fleet profiles. Within each scenario all seats share the same development profile.
  Its macro plans and baseline fallback remain deliberately narrow. Failure to transfer to complete
  games can indicate a curriculum or feature mismatch; it does not alone disprove offline learning.
- If the fresh scenario gate identifies useful gains, broaden development asymmetry and decision
  diversity while retaining immediate-liquidity, stabilization, rival-benefit and endgame counterexamples.
  Add advance/access information where current features are insufficient. Broaden experimental
  activation beyond epoch three only after training covers it.
- Measure whether the student retains teacher gains on unseen positions and actual games. If the
  simpler feature-based selector captures the gain, prefer that lower-cost result. Report performance
  by scenario family and experimental-plan activation, not only aggregate agreement with the teacher.
- Use the [teacher/student diagnostic](contributing.md#diagnose-teacher-and-student-choices) to
  independently evaluate frozen teacher choices and replay large student mistakes. Distinguish weak
  plans from selection or representation errors before increasing training volume. Diagnose on the
  existing curriculum, then qualify any resulting policy changes on separately planned new scenarios.
- Before using naturally collected hidden-information checkpoints as teacher roots, implement a sampler
  conditioned on the acting seat's permitted information/history. An exact-state fork or a new random
  seed is not a belief sampler. Preserve each simulated player's information and sealed simultaneity;
  avoid clairvoyant optimization of sampled worlds.
- Use the separate view, legal-choice and feature timings to locate observation cost before
  optimizing it. Candidate costs include catalog construction, string-key feature allocation,
  repeated projections, transition receipts and detailed coverage.
  Share immutable data or use indexed numerical inputs only where measurements justify the change.
  Keep one authoritative transition implementation and verify any receipt-free path against ordinary
  transitions, including counters, decision identity and automatic effects.
- Add queued batch inference across sessions only when useful. Compare dataset generation, fitting and
  evaluation time separately. Preserve bounded CPU/memory/elapsed time, cancellation and checkpoint
  integrity when extending supervision to additional learner processes or a GPU backend.
- Before application integration, measure the exported policy at the intended browser budget, verify
  privacy under changed hidden states and test cancellation/reload/undo. Verify representative old
  saves load, replay and continue with unchanged history and pending private state. Experimental
  `student.json` is not a production model asset.
- Success qualifies only the selected configuration. Broader release claims require the full supported
  matrix, coverage, diverse independent opponents, human evaluation and measured difficulty separation.
  Retain a compatible fallback outside qualified configurations and for incompatible model assets.

### Broader card-root acquisition

Evaluate whether broader prospectively sampled card decisions provide more useful training signal
than repeated continuation branches from a small number of early roots. The separate
[breadth driver](../scripts/card_advantage_breadth_experiment.py) keeps F and the qualified purchase
actor fixed: training observes F plus one uniformly sampled non-F proposal, while development and
validation observe every proposal. Missing action labels remain unobserved, never zero targets.
All rows from a world retain one split, and future chance is paired within each comparison.

Freeze the root schedule, world banks, consumed-world throughput/parity proof and original clock
before collecting. Count coverage on nontrivial roots, report skipped and trivial roots separately,
and stop without replacement worlds if coverage or time is insufficient. The full eight-unit model
is the sole candidate; the fitted action-priority control cannot replace it after validation.
Require the fixed local advantage/concentration gate and lower error than zero prediction before
fresh complete-game screening, independent confirmation and repeated-opponent robustness. Only after
those gates pass, require a positive paired 95% lower bound against the exact frozen 64-unit
action-priority policy on a separate 1024-world bank. This comparator does not become an alternate
candidate, and it does not relax the previous gates. A local fit improvement alone does not establish
stronger play, and no experimental driver activates assets.
Collection batching retains per-origin atomic records, fixed allocation and the original deadline.

### Continuation target diagnostic

Before further card-policy fitting, use the separate
[continuation diagnostic](../scripts/card_continuation_experiment.py) to distinguish action effects
that persist across own continuation policies from effects that depend on F's later decisions.
Capture roots prospectively under F, then compare the same two initial actions and four paired chance
streams under own-F and own-priority tails. Opponents and the purchase policy remain frozen. An
alternative tail must not change root selection or the pre-root trajectory.

Freeze the complete input bank and original time allowance before collection. Verify every compressed
record first; the analyzer reads only training roots, freezes its fixed ridge coefficients, then
opens validation and applies the declared coverage, cross-stream persistence and prediction guards.
Its output is a diagnostic classification, not a deployable policy or permission to collect more
worlds. Inadequate coverage or elapsed time does not permit replacement roots or a renewed clock.

### Policy randomness coupling diagnostic

Use the separate [coupling diagnostic](../scripts/card_policy_coupling_experiment.py) to measure
whether aligning policy samples by turn, stage, actor and decision occurrence reduces paired-return
noise. Reuse the fixed first 64 completed continuation requests, with the same F backbone, two forced
root actions, purchase actor and authority chance streams. Compare the original global-index schedule
with independently hashed global-index and semantic schedules. Only the latter two isolate the
coupling change under the intended stochastic policy kernel; legacy comparisons are descriptive.

Reserve the additional future streams before execution, retaining intentional reuse of the first
four streams. Require completed, hash-bound records and exact legacy replay before reporting the
fixed whole-world variance-ratio bootstrap. The two-hour original clock, per-origin atomic files,
batch allocation and data limits survive resume. Insufficient coverage or a failed variance guard
does not authorize replacements, policy activation or another collection.

### Autonomous strategy discovery

Build on the [complete-game purchase learner](contributing.md#purchase-policy-learning). Human
suggestions should supply diagnostic positions and hypotheses, while training explores legal choices
and repeated combinations beyond those suggestions. Qualification against other automata does not
establish a serious challenge for experienced humans.

- Evaluate the qualified purchase policy in human games and retain representative failures as
  diagnostic positions. Distinguish improved purchase priorities from contextual planning, card
  timing and expansion strength.
- Qualify the [card learner](contributing.md#card-policy-learning) with the purchase actor fixed in
  every continuation. Measure meaningful retention and alternative-card exploration, including
  prerequisite blockers, cash competition, Printed Word receipts and Patronage benefits to rivals.
  A card-window pass ends that window; playing another card can retain the leader without passing.
  Count visits to complete useful sequences, not just card identities or nonbaseline decisions.
  Verify exploration and recovery separately from numerical correctness before scaling training;
  entropy regularization at visited states does not ensure that rare successor states are reached.
- Extend repeated actor control to token commitments and expansion. Add versioned raw observations
  and bounded legal-action coverage for each phase, preserving independently qualified policies.
- Expand population self-play and retain historical and independently developed rivals. Test several
  learned automata together, balance seats across fresh worlds, and keep one world's counterfactual
  roles or continuations together when estimating uncertainty.
- Broaden supported setups only through separate qualification. Preserve the compatible baseline for
  other player counts, variants and catalogs. Freeze compute budgets, exploration, checkpoint choice
  and confirmation gates before opening fresh data; do not extend a failed search after validation.
- Investigate whether full-tail action-return comparisons improve sample efficiency for rare choices.
  Use actor-safe inputs and the frozen repeated policy for every continuation decision. Sample roots
  prospectively, retain all sibling branches in one split, and compare against equal-compute learning.
  Actual hidden worlds can supply noisy return targets; privileged per-world best-action labels or
  hidden-aware continuations would teach a different, unavailable policy. Changing a fork's future
  RNG does not resample its existing hands or deck.
  For a short forced sequence, collect the complete actor-safe observation and legal choices at each
  forced child decision, together with paired ordinary-policy and alternative continuations from
  that same child. Keep a root action's ordinary-continuation return separate from a compound
  sequence return: the first action alone cannot reproduce a benefit that requires a later pass.
  Fit expected action advantages across independent worlds and test a frozen selector on new worlds
  before repeated-use full-game qualification. Leader rebates, later discounts and stabilization
  savings explain observed paths; none alone establishes an improvement in playing strength.
- Evaluate learned-value guidance for exact visible purchase sequences only with bounded search and
  fresh action-ranking evidence. Search can amplify prediction error on unfamiliar states, and an
  end-of-phase state may be outside the value encoder's trained domain. Broader hidden-information
  search requires an actor-history-conditioned belief sampler. A verified teacher could support
  [Expert Iteration](https://arxiv.org/abs/1705.08439).
- Keep simulation, fitting, selection, budgets and reports in ordinary programs. Profile simulation,
  encoding, fitting and inference separately before moving work to specialized hardware. Use LLM
  review for failed milestones and strategic reassessment; no LLM calls are needed per game or update.

#### Next implementation window: up to 12 hours

The next window tests whether deliberately collected sequence outcomes can train stronger repeated
card decisions. Its primary deliverable is a qualified, locally playable card-and-purchase candidate,
or a preserved negative result that identifies the next bottleneck. Human-level play remains a goal
requiring separate human evaluation. Keep the current four-player profile, qualified purchase actor,
and production rules. Start with normal card windows; final windows and nested resolutions retain F.

Use a new isolated implementation worktree derived from this branch. Preserve the completed purchase
and card experiment checkouts, runtimes, models and archives. Commit cohesive collector, learner and
inference changes separately. Do not merge, push, release or activate an unqualified model in shared
branches. Reuse the existing runner, simulator, encoders and browser integration pattern.

| Elapsed time from execution start | Work and decision |
| --- | --- |
| 0–0.5 h | Freeze interfaces, resource limits, proposal rules, dataset splits, fitting settings and fresh seed banks. Assign file ownership. |
| 0.5–2.5 h | Implement collector, numerical learner and portable selector in parallel; run focused checks and the two-cycle sequence test. Commit and freeze the runtime. |
| 2.5–5 h | Collect cycle 1, fit the ensemble, inspect completed local validation and run a repeated-use development screen. Stop this recipe on a correctness failure or clear harmful transfer. |
| 5–8 h | If eligible, collect cycle 2 using P1 as the continuation, fit P2, and compare P1/P2 on one common fresh development bank. Freeze one finalist. |
| 8–10 h | Run independent full-game confirmation and learned-rival checks. Prepare narrowly scoped browser integration while the qualification result is pending. |
| 10–12 h | Activate only a qualified candidate in an isolated preview, finish browser/replay checks, archive evidence and review the next stage. |

These are ceilings, not requirements to keep working on a failed approach. Reserve the final three
hours for qualification, integration and handoff; do not start another collection after hour 8.
Admit a collection only when its full time cap plus 45 minutes for fitting still fits before hour 9. An
incomplete cycle cannot supply a convenient partial training set. A completed, eligible P1 can remain
the finalist if cycle 2 times out; otherwise finish with a diagnosis. No unplanned third cycle or
additional confirmation samples belong to this window.

**Collector and decision contract.** Extend the existing branch collector and
[simulation controller](../training/src/main/kotlin/dev/aragos/aor/training/Simulation.kt), rather than
creating another experiment framework. Export the ordinary actor-safe observation at each root and
at the next own ordinary card decision in the same window. Preserve the exact child state and RNG
position for paired continuations; private authority histories remain executor-only.

Define one versioned proposal function used by collection, JVM evaluation and browser inference.
Its set S contains the exact F recommendation, offered Pass, and up to two other physical-card groups
sampled uniformly, with one uniformly sampled offered command per group. Deduplicate commands, use
canonical ordering, and bind the sampler and decision-seed derivation. Use physical hand references
for groups and a separately salted deterministic sampling stream. Record F's explicit command and
index; canonical ordering need not put F first. The seed is execution metadata,
never a learned feature. Retain the existing bounded target/protection offers. Evaluate exactly S
at runtime; do not silently widen deployment to actions excluded by this sampling rule.

Evaluate every member of S at each sampled child too. Do not restrict child training to Pass/F while
deploying a wider selector there. After each forced action, every subsequent eligible decision uses
the frozen continuation policy; all seats retain the same qualified purchase actor. Nested decisions
remain F. A suffix ends when its original normal window ends.

For observation s, candidate a, F action b and frozen continuation pi, the target is the paired
difference `R(a then pi) - R(b then pi)`, using the existing terminal reward
`0.8 * winShare + 0.2 * rankReward`. A child intervention produces a separate row at that child's
observation. Never assign a compound sequence's return to its first action under a different
continuation. Different future streams may produce different child observations; average only
identical observation/action pairs. Keep other rows separate and cluster them by their original world.

Cycle 1 uses F card continuations. Cycle 2 uses frozen P1 for the controlled seat and a prospectively
assigned, balanced mixture of F and P1 rival lineups. Freeze each lineup before branching. Keep F as
the action-value reference in both cycles. Do not pool cycle-1 and cycle-2 return targets into one
unconditioned loss: they describe different continuation policies. Parameter warm-starting is allowed.

Use four paired future streams per world. With at most four root proposals and three non-Pass root
actions, evaluating the full child proposal sets requires at most 52 distinct tails per origin:
16 root tails plus 48 child tails minus 12 exact reuses. Reuse is valid only when observation, F
recommendation, proposal version, decision seed, chosen action and child RNG position match the
already computed frozen-policy continuation. Assert this identity; never expand the budget silently.

**Learner and portable policy.** Reuse the sparse card encoder and 64-unit state/action representation.
Add an explicit versioned advantage-selector contract; do not reinterpret a residual card-policy
artifact as a different policy without a new selector identity. Keep the qualified purchase format and
behavior unchanged. The selector should have a single inference implementation shared by JVM and JS.

Fit `q(s,a) - q(s,b)` to the paired difference with squared error, using a bounded sigmoid action-value
head with explicit state–action interaction: `h=tanh(Ws*s+bs)`, `g=tanh(Wa*a+ba)` and
`q=sigmoid(w*(h*g)+bias)`. The separately versioned selector rejects the earlier additive-head
artifacts. Weight each original world equally, then its decision groups and action pairs equally. Siblings
and chance replicas are dependent samples. Absolute q values are not identified by a difference loss
and must not be described as calibrated win probabilities.

Use three predeclared initialization seeds, one fixed optimizer configuration and a small fixed
checkpoint schedule. Select checkpoints by origin-weighted development loss, with an earlier-epoch
tie break, then retain all three seeds in the ensemble. The deployed selector chooses the proposal
with the highest mean advantage over F only when that advantage exceeds 0.02 and all three models
predict a strictly positive advantage; otherwise choose F. Rank all proposals first, breaking exact
ties by canonical command, then veto the winner if either condition fails; do not choose a lower-ranked
eligible alternative. Agreement is a conservative rule, not a confidence
interval. There is no 97% recommendation prior in this selector.

Before real collection, test a two-context task where one context requires changing both actions
from baseline A→A to B→B. Under F continuation, root B must initially have zero advantage while B's
child choosing B has positive advantage. After the child is learned, recollection under P1 must make
root B valuable; repeated P2 must execute the whole sequence. Include reversed payoffs and verify
both contexts. Preserve the earlier failed PPO tests as separate evidence.

**Data and development gates.** Reserve 256 fresh worlds for each cycle: 128 training, 64 development
for checkpoint/control calibration, and 64 local validation. These local banks are development
evidence, not the final strength test. Allocate requested normal-window roots across epochs and
visible conditions, including playable leaders and owned Printed Word, and rotate all four seats.
Choose roots prospectively, record skipped requests and never replace them after inspecting outcomes.
Freeze the exact allocation before launch; coverage requirements are at least 96 training, 48
development and 48 local-validation worlds. Require at least eight epoch-3 training worlds and four
each in development and validation, plus four training and two each development/validation worlds
with owned Printed Word and a playable leader. If a required
context is missing, record the coverage failure instead of claiming the full scope is qualified.

All descendants of an origin stay in one split. Preserve all action returns, including losses and
ties; do not produce labels naming each hidden world's best action. Decode outcomes only after the
declared collection completes and its completion hashes validate.

Fit a matched action-priority ablation that omits varying state features and context-dependent action
quotes, retaining only the constant normal-window input so its interaction head can learn. Keep the
same F reference and legal/proposal machinery. Also freeze a control calibrated
on development observations to match the candidate's overall override and Pass/other-card frequencies,
using no learned state-dependent preferences. Do not recalibrate either control on validation or
confirmation outcomes. Report realized frequencies, origin-weighted loss against zero advantage,
selected paired returns, uncertainty and coverage.

Continue from local validation only with positive mean selected paired advantage, overrides in at
least 16 independent validation worlds, and no single world contributing more than 25% of summed
positive advantage. Include F/fallback decisions with zero selected advantage and average decision
groups within each origin before computing means and concentration. Prediction loss and comparisons
with the ablation are diagnostics; they cannot
replace complete-game evidence. Use a 128-world repeated-policy development screen for P1. A mean
win-share loss exceeding five percentage points with an upper 95% bound below zero stops progression
to cycle 2. Other inconclusive results remain explicitly inconclusive.

After cycle 2, evaluate eligible P1 and P2 on the same fresh 256-world repeated-policy development
bank. Select by mean win share, preferring P1 when the difference is less than one percentage point.
Do not compare raw means from the two cycles' different local-validation distributions. P2's F
fallback can discard P1 improvements, so monotonic improvement is not assumed. Freeze the selected
ensemble, proposal rules, margin and frequency control before opening final confirmation.

**Independent qualification.** Compare the frozen finalist, current F-card/purchase baseline, and
frequency control on 1,024 new paired worlds, rotating the controlled seat. All rivals use F cards
and the qualified purchase actor in this primary bank. Require at least five percentage points of
win-share improvement over the incumbent, with a positive paired 95% lower bound. Require a positive
95% lower bound over the frequency control as a further activation requirement. Realized action
frequencies can diverge during repeated play, so beating this frozen control alone does not prove
that gains arise from contextual planning. An inconclusive result does not trigger more samples in
the same window.

Use a separate 256-world robustness bank with every controlled role against three finalist rivals,
comparing the finalist and the incumbent. Cluster all four roles by world. Require a nonnegative mean
win-share difference and a 95% lower bound above minus three percentage points. These banks require
at most 5,120 game executions; any exact duplicate reuse must be specified before launch. They test
the declared setup and rival population, not general human strength or an unrestricted league.

**Verification and integration.** The collector owner verifies unchanged consumed-game replay,
absolute decision seeds, sealed batches, exact child pairing, legal proposal membership and full-tail
replay. The learner owner verifies target arithmetic, origin weights, the two-cycle task, ensemble
selection and deterministic export. The inference owner verifies identical proposal sampling and
numerical decisions across Python/JVM/JS, including F fallback and unsupported scopes. Encode each
observation once and share it across the three networks; avoid scoring every legal target separately
for each member when only S is needed.

Run Kotlin formatting before its formatting check, serialize builds in each checkout, and run
focused checks once per cohesive change. Before enabling a qualified preview, complete the required
integrated checks and exercise actual browser decisions, legality, pause/reload/undo, stale-work
rejection and representative saved-game replay/continuation. Measure cold and warm worker latency;
keep an explicit budget in the frozen integration checklist. Never change the trained policy or
sampler to meet that budget without requalification. Leave final windows, unsupported setups and
incompatible artifacts on F. No remote deployment is part of this window.

**Execution, resource use and agent work.** One local program should drive the fixed collection,
fitting, development and confirmation stages, using the existing supervision primitives. Keep one
immutable protocol per cycle, original nonrenewable deadlines, atomic whole-origin/checkpoint writes,
completion hashes and owned-child cancellation. Record model/runtime/schema identities and fresh
seed reservations once; avoid a new provenance or orchestration framework.

Use at most six native workers, each with `ActiveProcessorCount=1` and a 1 GiB heap, and one numerical
CPU thread with sequential ensemble fits. Cap cycle-1 collection at 120 minutes and cycle 2 at 150
minutes, within the 12-hour window. The two cycles permit at most 26,624 tails plus 512 backbones.
Check space before and during execution: preserve at least 8 GiB free and cap new experiment data at
4 GiB. Compress whole-origin data and store identical model/observation payloads once by hash. Never
truncate actions, observations or returns to meet a limit; stop with incomplete status instead.

Assign three agents disjoint ownership: collector/executor, numerical learner, and portable
inference/evaluation. The coordinator owns protocol decisions, shared interfaces, serialized builds,
commits and milestone reviews. Exchange concise contracts and summaries rather than whole logs.
Programs handle games, batches, fitting, retries, resource checks and progress records without LLM
calls per move, game or update. Agents review contract completion, learning/transfer failures and
qualification results. Keep brief user-facing progress updates while long local jobs run.

Deliver committed code, pinned models/configurations, a compact status/result report, a resumable
execution command, and an isolated preview only if qualified. Include predetermined held-out replay
examples and a failure inventory; examples illustrate behavior and do not replace statistics.

If this window finishes before the user returns, review the evidence and begin a separately bounded
next stage without another permission request. If card learning qualifies, prioritize coordinating
token commitments and expansion with the learned card/purchase policy, then broader opponent
populations. If it fails, identify whether targets, representation, exploration or repeated-policy
transfer failed and choose one falsifiable corrective experiment. Freeze new budgets and untouched
confirmation worlds before that stage; preserve failed results and do not extend a consumed test bank.

### Capacity correction after failed card qualification

When the completed experiment shows overfitting and fails independent qualification, test one
predeclared reduction to eight active units using the same cycle-1 training/development splits,
optimizer, target, proposal sampler and incumbent fallback. Preserve the original 64-unit actor as
a paired comparator. The previous validation outcomes informed this hypothesis and are development
evidence; require fresh local and full-game banks. Do not pool targets from different continuations.
Use the [bounded capacity driver](contributing.md#paired-card-advantage-experiments), its fixed
sequence preflight and unchanged-game batching proof. Stop on failed gates instead of sweeping
widths, changing rewards or extending confirmation. Distinguish evidence of better generalization,
stronger complete-game play and useful contextual planning; none automatically implies the others.

### Hardware experiment

Start the pilot on the current Mac: executor profiling, branching, teacher validation, dataset
generation, the first small student and initial competitive evaluation. Keep artifacts portable, but
defer desktop setup until the approach shows useful gains or measured throughput limits iteration.
Move to the desktop when GPU fitting/batched inference becomes substantial, or when a CPU benchmark
demonstrates worthwhile simulation throughput. A GPU does not resolve a simulator bottleneck.

Run the same executor revision, configuration, policy, seeds and workload on candidate machines.
Compare sustained game/continuation throughput at several worker counts, peak memory, GC and total
wall time. Report configured CPU/memory limits, operating system, JDK and hardware alongside results.
Use local storage and long-lived workers; choose concurrency from measurements rather than advertised
thread count. Existing supervision supports macOS/Linux; Windows would need a supported Linux
environment or an implemented and verified supervisor port.

The JVM simulation uses CPU execution. Faster sustained cores and additional effective parallelism
can increase sample production; sufficient RAM supports active sessions and checkpoint data. A GPU
requires an explicit learner/inference backend and useful batches; it does not accelerate the engine
automatically. Measure dataset generation and fitting separately before investing in GPU integration.
The first small student should remain feasible without a GPU. Hardware-specific speeds and capacity
belong in run artifacts, not assumed training guarantees.

The later desktop target has a Ryzen 7 9800X3D, 32 GB DDR5-6000 CL30 system memory and an RTX 4090 with
24 GB GPU memory. On migration, use it as a single-host CPU simulator plus optional GPU learner. It has
eight physical cores and sixteen hardware threads; benchmark 4, 6, 8 and 10 simulation workers when
permitted by the supervisor, reserving CPU capacity for learning and coordination. Neither thread
count nor gaming performance establishes simulator speedup. GPU fitting should use batches, with
CPU fitting retained as a baseline for the first small student. Stream datasets and bound checkpoint
retention within system RAM; GPU memory is separate from the JVM heap. No hardware upgrade is a
prerequisite for this pilot.

Choose native Ubuntu dual boot or Ubuntu under WSL 2 when migration is justified; neither setup is
required for the Mac pilot. Follow NVIDIA's supported CUDA driver path for the chosen environment.
Verify the CPU and RAM visible to that environment before choosing worker/heap limits,
and measure its actual throughput. References: [processor specifications](https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9800X3d.html)
and [CUDA on WSL](https://docs.nvidia.com/cuda/wsl-user-guide/index.html).

### Expansion after the pilot

Use measured full-game failure modes to broaden observations and actions, then assess policy/value
models through population self-play. Add historical opponents and exploiters, richer permitted history,
and targeted search teachers for purchases, bidding, other card effects and endgame decisions. Distill
into compact runtime policies and retain only additions that improve strength at measured training
and browser budgets. This is simulator-generated learning performed offline, not a dependency on a
fixed human-game dataset or a production model service.

Research foundations: [Expert Iteration](https://arxiv.org/abs/1705.08439) motivates learning from an
expensive planner; [DouZero](https://arxiv.org/abs/2106.06135) motivates a compact learned action scorer
for variable legal actions; [PSRO](https://arxiv.org/abs/1711.00832) motivates diverse opponent mixtures.
[ReBeL](https://arxiv.org/abs/2007.13544) informs hidden-information planning, but its two-player
zero-sum convergence guarantee does not establish optimality for this multiplayer game.

User-authored future-turn scripts and midgame controller replacement remain deferred as defined in
[scope](requirements.md); internal speculative planning does not authorize future command batches.

#### Joint token and expansion priorities

Test a small fixed set of economic weight changes before adding another learner architecture.
The [joint economy driver](../scripts/joint_economy_experiment.py) screens the incumbent and
14 predeclared perturbations on 64 fresh worlds, selects three on 192 separate worlds, and
confirms one on 1,024 further worlds. Each stage caches one incumbent game per world. Rival
populations alternate in blocks of four worlds so both cover every controlled registration seat.
Candidates change only token buying and expansion; purchases retain the exact qualified F prior
and purchase actor, and other decisions retain F. The original priority card model is an
experimental rival, not a qualified replacement.

Keep candidate weights, model identities, rival populations, paired gates and bootstrap fixed
before the screen. Consumed-game incumbent parity and fixed-input behavioral feasibility must
pass for the exact runtime before admission. All stages share one nonrenewable three-hour clock,
six single-core workers and batches of eight, with per-game atomic records and the existing
completion barrier. A failed selection or confirmation ends the experiment without additional
samples, new candidates or automatic browser activation.

#### Repeated card-policy training signal

The [trajectory driver](../scripts/card_trajectory_experiment.py) tests the existing card actor
and PPO update over 32 complete batches of 256 games. It retains the original 97% recommendation
prior, every offered card choice, exact model/Adam checkpoint carry, and the frozen F/purchase32
policies elsewhere. This tests a larger stationary training run; neither earlier payoff-reversal
failure is repaired or reclassified.

Freeze four development checkpoints and reuse one paired control bank for F, the unchanged
initial actor and the priority card comparator. Stop for the declared harmful-play or insufficient
probability-movement conditions; accepted epochs alone do not establish stronger play. Only one
eligible checkpoint can reach the fresh four-arm confirmation bank. All training, fitting and
evaluation share one original three-hour clock, with compressed complete-game records, fixed input
hashes and a 4 GiB data cap. A missing checkpoint, changed input or exhausted budget must preserve
evidence and stop progression. No result automatically changes the browser policy.
