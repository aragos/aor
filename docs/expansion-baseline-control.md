# Previous-batch expansion baseline control

This source prerequisite compares two expansion-only on-policy recipes. It does not authorize
scientific seeds, model reads, initialization, collection, fits or execution. Family 75, 76 and 77
inputs and default contracts remain unchanged. A separately reviewed finite package must bind all
concrete subjects before this new family runs.

## Fixed comparison

CONTROL and TREATMENT both start from the exact original family-75 schema-2 expansion actor, with
fresh actor Adam. Both retain exact G3 card/purchase actors, ordinary M8, F0 and qualified E/T for
all non-expansion decisions and rivals. The existing full-offer expansion collector and sampled
policy remain the only behavior owners. No action masking, new reward, league change, actor basis
change or original-E regularizer is introduced.

There are two independently seeded replicas, PRIMARY_DESKTOP and MAC_REPLICATION. Both run the
same algorithm and initialization; their training worlds, shuffle streams and development worlds
are disjoint. The desktop is preselected as the only possible candidate source; the Mac is a
replication diagnostic, never a runner-up. Host CPU/numerical environment identities are explicit.

Within each replica, each arm collects eight batches of 256 complete worlds. Corresponding batches intentionally share
the same world bank and four epoch shuffle streams. Arm execution order alternates by batch;
neither arm reads the other arm's learned state. All worlds, including empty expansion histories,
remain in evidence. One prescribed same-factory historical reproduction per arm and batch cycles
through indices 0, 85, 170 and 255. The same exact initial behavior must produce identical first
batch world trajectories and actor/Adam updates; only documentary paths/signatures may differ.

CONTROL uses the unchanged family-75 detached advantage `R - 0.5`. TREATMENT also uses `R - 0.5`
for batch 1. At batches 2–8 it uses `R - V_previous(x)`, where the separately published baseline
was fitted only on the immediately preceding completed TREATMENT batch. Original observations,
selected indices, sampler seeds, behavior likelihoods/values and terminal labels are preserved.
Gamma and lambda are one; R is the original complete-world `0.8 winShare + 0.2 rankReward`.

Both actors keep the existing Float64 PPO recipe: four epochs, learning rate .0003, clip .2,
whole-collection behavior-KL ceiling .01, uniform recorded-decision loss, no entropy/imitation/value
loss, and the same five actor optimizer slots. Actor and Adam roll back together after rejected
epochs. The actor's value parameters and their momentum are excluded. The treatment has its own
strict objective and carry types; old expansion, purchase and joint types do not accept them.

## State-only baseline

The fixed 64-dimensional basis is `h(x) = tanh(W_initial_state x + b_initial_state)` from the exact
original expansion actor. The sparse state dictionary and original units are unchanged. No action
feature, selected index, action count, command, future value, terminal label or mutable actor
parameter enters prediction. The immutable initial basis is shared across all seven baseline fits.
It is not recomputed from the evolving actor and needs no new initialization stream.

After TREATMENT updates 1–7 complete, fit one deterministic Float64 linear ridge baseline on that
batch's expansion states and actual terminal returns. A world with n recorded expansion decisions
gives each row weight `1/n`. The exact objective is the **sum of per-world mean squared errors**
plus `1 * ||slopes||²`, with an unpenalized intercept. Weights sum to the number of nonempty worlds;
there is no further division by that number, feature scaling, penalty grid or calibration selection.
Weighted centering may implement the algebraically equivalent 64-dimensional solve. Predictions
are clamped to [0,1]; targets and fit residuals are not clipped. Empty worlds create no invented
observations and are reported separately. No eligible baseline observations is a finite stop.

The completed actor update and its next-batch baseline are published as one checked treatment
transaction. The descriptor binds the immutable initial basis, dictionary, exact source batch,
completed actor update, coefficient bytes, weighted objective and eligible-world count. Targets
for batch N are sealed before its actor update and cannot depend on baseline N. Batch 8 produces
no unused baseline. Carry requires the exact preceding same-arm actor, all five populated Adam
slots when steps exist, recipe, baseline lineage and finite moments; it cannot cross arms or accept
a family-75 checkpoint as treatment carry.

## Finite execution and evidence

One original attempt per replica covers training, pure admission and its fixed final screen.
Each ceiling is 3 hours plus 900 seconds of terminal-relative read-only audit. PRIMARY_DESKTOP
uses 10 workers / 16 logical CPUs; MAC_REPLICATION uses 6 workers / 10 logical CPUs. Both use
numerical CPU 1, native heap 6 GiB, group RSS 12 GiB, data 8 GiB and free disk 16 GiB.
Concrete cost/storage gates are frozen before any scientific work and may be refined only during
preparation. There are no retries, top-ups, shortened candidates, intermediate strength looks,
alternative baselines, fallback checkpoints or automatic successors.

The first two matched batches are retained operational evidence, not warmup. Both arms must have
complete/reproduced worlds, exposed branching/card offers, accepted actor changes and advancing
Adam, with exact first-batch equality. Fixed throughput/storage projections must fit the remaining
original clock, including the reserved final screen. Four consecutive batches without learning
progress in either arm stop both. Empty actor batches, invalid provenance or baseline failure stop
the attempt. A stopped prefix is never eligible for the screen. Native workers return small
receipts; original trajectories and canonical histories are losslessly archived and member-verified
before pruning. Keep only necessary actor records/prepared sparse inputs in the numerical process.

Every protocol, arm receipt, objective/continuation lineage and native screen manifest carries
the exact replica ID and protocol binding. The new learning audit admits only CONTROL8 and
TREATMENT8 from the same replica together. Its kind is
`expansion-baseline-learning-audit-1`, with `passed`, `complete`, `completedRounds=8`,
`completedBatches={CONTROL:8,TREATMENT:8}`, `scientificWorlds=4096`,
`historicalReproductions=16`, `firstBatchParityPassed`, `operationalPrefixPassed`,
`eligibleFinalActors`, `replica`, exact protocol/prepared hashes, every round/arm receipt hash, final per-arm
policy/checkpoint/identity/receipt paths and hashes, and complete archived artifact pins.
Final arm receipts use `expansion-baseline-batch-1`, explicit arm, batch 8 and complete status.
The native screen checks these subjects before loading either final actor. It does not infer
completion from filenames or require the still-active overall controller to have exited.

## Development screen and interpretation

Each replica's screen uses its own fresh 1,024-world bank and three roles: TREATMENT, CONTROL and RAW_G3. Each
role plays each world exactly once; corresponding contrasts share the same stored game. All
non-expansion policies and rivals remain G3/M8/F0/E/T. Reuse the existing native arena, sampling
and canonical replay owners through a new typed admission/input, preserving family-76's strict
completed-64 admission. Twelve prescribed canonical role reproductions and two fixed historical
raw-G3 compatibility controls precede/report alongside the fresh screen.

Maximum terminal executions per replica are 4,096 training + 16 historical training reproductions
+ 3,072 development + 12 role reproductions + 2 historical controls = **7,198**, or **14,396**
across both. Historical compatibility subjects are host-specific: frozen family-60 Linux controls
on desktop and already admitted family-75 local raw-G3 references on Mac. Their proof scopes stay
explicit; neither host creates additional reference games.

Both replicas must finish and be audited before outcome interpretation. Each replica's primary
mechanism gate is TREATMENT minus CONTROL reward with a positive 95% lower bound. Nomination is
restricted to PRIMARY_DESKTOP TREATMENT8: both replica mechanism gates must pass, and desktop
TREATMENT minus RAW_G3 win and reward must both have positive 95% lower bounds. This explicitly
requires a positive replication effect; the Mac actor is never a replacement candidate. No other
arm or checkpoint can be nominated. Qualification requires a separately reviewed fresh 99% study;
this screen cannot activate anything. The test measures this previous-batch baseline recipe's
effect on learning, not a claim that reduced prediction error proves playing strength.

Synthetic checks cover weighted ridge normalization/intercept, shared frozen hidden basis,
action/future exclusion, previous-only target binding, first-batch exact equality, arm-local strict
carry, whole-epoch rollback, paired finite gates, same-factory native composition, canonical roles,
lossless archive admission and rejection of legacy/type/phase substitutions. No real model or
episode is needed for this source milestone.
