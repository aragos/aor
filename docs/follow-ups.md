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

### Autonomous strategy discovery

The next broad experiment should learn decisions from complete games, with human suggestions used
as diagnostic positions and hypotheses. The learner must be able to try actions and combinations
that neither the current heuristic nor a human example recommends. Further scalar-weight searches
remain useful controls, but cannot discover consequences absent from their authored features.

- Qualify the experimental [purchase learner](contributing.md#purchase-policy-learning), which
  controls shipping and advance purchases throughout complete games and keeps the qualified policy
  for other decisions. Apply each choice through the actual engine and
  regenerate legal offers, including newly acquired prerequisites and changed prices. This first
  milestone cannot discover better card timing or broader expansion actions. Keep its policy and
  value boundary extensible so those phases can follow.
- Encode raw public and own-seat facts from the ordinary acting-player view, alongside legal action
  identities, costs and effects. Use stable domain identities, not transient hand references. Share
  the state encoding across candidate actions and preserve portable inference. Neither the policy
  nor value callback may receive authoritative game state or unrevealed simultaneous submissions.
- Initialize from qualified choices, then constrain early policy updates and exploration so small
  per-decision error rates do not compound into uncontrolled whole-game behavior. Keep support for
  every offered purchase, measure deviations and sequence coverage, and compare complete games
  against the unchanged initialization. Agreement with the teacher is only a starting condition.
- Collect complete trajectories with the current learner acting repeatedly across the turn. Update
  from terminal results using an on-policy method such as [PPO](https://arxiv.org/abs/1707.06347), then
  collect new games with the updated learner. A learned value baseline aims to reduce gradient variance.
  A one-action intervention followed by the old policy is not equivalent to deploying the learner
  throughout a game. Lower training loss or agreement with the old policy is not evidence of strength.
- Keep deliberate exploration of low-ranked legal alternatives and audit action coverage. Current
  bounded candidate subsets and static top-ranked beams can exclude useful actions. Regenerate legal
  choices after every transition so acquired prerequisites and capabilities expose their actual
  continuations. Broaden combinatorial choices with a bounded, recorded sampling policy.
- Rotate a learner seat against a frozen diverse opponent population, and retain historical and
  independently developed opponents for evaluation. Expand population self-play after the first
  learner shows improvement; repeatedly exploiting one familiar opponent does not establish robust
  playing strength.
- Begin with a 256-game correctness and throughput smoke. Include a controlled delayed-reward
  sequence check that must improve through policy updates, rather than merely fitting teacher
  choices, plus actual-engine prerequisite, repricing, privacy and replay checks. Stop before a
  larger run if these fail or repeated exploration produces a clear whole-game collapse.
- Provisionally budget 8,192 training games in batches of 256 and at most four development checkpoint
  evaluations; revise that budget from measured smoke costs before opening fresh training data.
  Freeze implementation, exploration, time limits, seed banks and whole-game improvement and stop
  criteria before launch. Stop at the budget if improvement is absent; do not expand the search after
  seeing validation results. Independently confirm one frozen selection on untouched full games
  with the same action-selection rule used during collection. Training loss and the delayed-reward
  check establish neither playing strength nor a serious human challenge.
- Keep all game generation, numerical learning, candidate selection, budgets, checkpoints and reports
  in ordinary code. No LLM calls are required per decision, game or update. LLM review should focus on
  implementation, failed milestones and strategic reassessment, with compact reports and selected
  replays rather than repeated manual scheduling.
- Add expensive search teachers only after proving that they improve choices on fresh continuations.
  Start with exact, visible economic sequences where useful. Arbitrary-root search across hidden
  cards requires a sampler conditioned on the acting player's permitted history; the existing exact
  session fork does not provide this. A useful learned policy/value model can later guide search and
  learn from its improved decisions through [Expert Iteration](https://arxiv.org/abs/1705.08439).

This proposal reuses the authoritative executor, replay verification, resource supervisor and paired
arenas. The purchase controller, complete-game collector, CPU learner and portable inference now provide
the first implementation. Their numerical checks do not establish playing strength; the older
four-choice commodity student remains a separate experiment. Measure simulation,
encoding, fitting and inference costs separately before deciding whether the desktop GPU helps.

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
