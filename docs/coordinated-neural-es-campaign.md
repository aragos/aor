# Finite coordinated neural ES prerequisite

Family81 implements proposal
`b3ba70c4f2ce27593b504608de94f816a728a5e9d4c0447799eb1bb6ef712340` and its final
supplement `0f9fc4b59d65759f3281df2320768ed6568e5564eaa3d1001e184b4fbb3a4906`.
This milestone authorizes source and constructed fixtures only. Scientific models,
projection streams, public observations, initialization, games and numerical execution
require a subsequent source/package review. Family80 remains closed, family77 held,
and every existing preview and frozen package remains unchanged.

## Policy and unit of optimization

One policy controls the evaluated seat's CARD, PURCHASE, EXPANSION and TOKEN decisions
throughout complete games. Every rival, ordinary M8 model, frozen F0 purchase baseline
and qualified E/T reference remains fixed. C/P retain the exact G3 nonzero actors,
network-prior owners, full offered actions and native sampled choice. E/T use a new
explicit execution type; they never masquerade as the existing sampled .97 actors.

The search coordinate is one 256-vector, with 64 coordinates assigned to each named
head. Its fixed, serialized block-scaled map changes state/action matrices, biases and
actor readout together. Value tensors remain unchanged. The projection owner fixes all
factor construction, ordering and scales before scientific inputs, and both hosts
consume exactly the same materialized actor bytes. There is no output-only ridge,
per-world winner label, critic fit, advantage estimate or alternate representation grid.

The E/T parameter containers are the original untrained75 E0
`8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc` and original
untrained79 T0 `812a16f1b8d26911752db0720607d260f410b821922ba2f10f1e3254aa8c9ae6`.
These are separate from the unchanged qualified raw E/T command references. Copying
the containers requires later preparation authorization; there is no new initialization.

For E/T, let b be the existing reference command at the original decision and let
`D(z,s,a) = residual(theta(z),s,a) - residual(theta(0),s,a)`, excluding the .97 log prior.
The new score is `1[a=b] + c_head * D(z,s,a)`. Apply the multiplier before adding the
margin, without a score clamp; reject nonfinite scores. Prefer b on an exact maximum
tie, then the lowest original offered index. Zero coordinates therefore preserve raw
G3 C/P/E/T behavior exactly. Current native offered commands are the action boundary;
the study does not enlarge legal token amounts or expansion commands.

## Fixed optimizer and calibration

Start at zero and perform exactly 16 generations. Each generation uses 16 independent
prescribed Gaussian 256-directions and their antithetic negatives, with sigma 1. Every
mutant plays the same 256 fresh complete worlds against the frozen rivals. The controlled
seat and ordinary setup/chance/actor seed owners remain authoritative. Shared initial
world seeds do not imply identical future RNG consumption after actions diverge.

For each direction, F is the mean normalized terminal controlled-seat reward over the
256 worlds. Compute in Float64
`g = sum((F_plus - F_minus) * epsilon) / 32`.
Nonfinite input or `RMS(g) <= 1e-12` stops unqualified. Otherwise set
`u = z + g/RMS(g)` and `next = u/max(1,RMS(u)/4)`.
No Adam, rank transform, reward standardization, momentum, decay, elite replacement or
best-generation selection is permitted. Mutants are not clipped to the center radius.
Joint normalization preserves relative head-gradient components; it also promotes weak
noisy nonzero gradients to full steps and is explicitly not a confidence test.

Generation1 raw-G3 controls complete before any mutant execution. Their public sensitivity
sidecars are separate from opaque terminal outcomes. For each E/T head and world retain
the first and last branching windows in epoch `1 + floor(globalIndex/4) % 3`, deduplicated.
Missing windows remain missing; require at least 128 represented worlds per head. Give
worlds equal weight and their retained states equal weight. No outcome selects a window.

The prescribed synthetic mean probe uses `v[k]=(-1)^k` and artificial differences
`2*dot(v,epsilon)/256` through the exact optimizer from zero. For each panel state and
probe, compute the signed strongest nonreference residual gap
`max(a != b, D(a)-D(b))`. Compute inverse-CDF q90 separately for the equally weighted
32 mutants and the one prescribed mean probe. Both need positive-gap mass at least .1
and q90 above `1e-8`. Set the single head scale to
`(1 + 2^-20)/min(q90_mutants,q90_mean)` and require it in `[1/32,32]`.
At that scale both mutant and mean-probe weighted action-change rates must lie in
`[.05,.40]` for each E/T head. Failure stops; there is no scale search or alternate probe.
Seal both multipliers before reading any game reward, including raw-control rewards.

Actual mean E/T movement on this fixed panel is diagnostic only. No live-head movement
floor, forced T change or early/alternate nominee is allowed. Each mean update is
published atomically after complete population receipts and exact numerical identities.

## Finite native work and final decision

Each generation has eight fixed 32-world shards. Desktop owns 0–4 and Mac 5–7. Each host
first runs its raw-G3 control worlds once, then its 32-mutant population on those same
worlds. A native population invocation loads the shared population once per shard;
workers publish compact per-game evidence and return small receipts. No whole-population
canonical trees or repeated model dictionaries accumulate in memory.

Exactly two population reproductions belong to each generation: PLUS direction
`generation-1` at world0, and MINUS of that direction at world255. Each runs only on the
host owning that world. Their complete canonical evidence and ordinary factory identity
are verified. Two predetermined historical zero-policy controls execute once globally
on Desktop before fresh scientific games: family60 homogeneous-heldout candidate-side
global indices0 and4095, preserving their complete original source, lineup and canonical
subjects as in the existing79/80 controls. Historical subjects and receipts retain separate
`globalIndex` and native `index`: global0 uses chunk00/local0, and global4095 uses
chunk07/local511. The original512-world source and pair validate the native index before
execution; neither source nor pair is rewritten. Their portable admission gates both hosts.

Only the mean after all 16 complete updates is eligible. It is frozen before the untouched
2,048-world paired FINAL/RAW_G3 screen. Sixteen fixed 128-world screen shards are divided
Desktop0–9 and Mac10–15. Replay indices0,682,1365,2047 execute for both roles, once each.
The primary development gate requires both reward and win paired lower95 bounds to be
strictly positive, with the existing native normal multiplier1.96 explicitly recorded.
There is no qualification, deployment, automatic successor or earlier-checkpoint selection.

Maximum complete executions are:

* `16 * (32+1) * 256 = 135168` population and cached raw-control games;
* `2048 * 2 = 4096` final paired games;
* `16 * 2 + 4 * 2 + 2 = 42` prescribed reproductions/historical controls;
* total **139306**.

One original shared 86400-second science clock covers both workers, native games,
transfers, calibration, updates and the final screen. Audit ends by the earlier of
persisted terminal+900 and original science deadline+900. No retry, rescheduling, refill,
deadline reset or shortened successful run exists. Any missing/invalid world or partial
generation fails closed. Outcome-blind prefix resource projections must be fixed before
scientific preparation; they may stop the run but cannot change its recipe/counts.
Each original or reproduced game has at most300 wall seconds and12000 player commands,
with its deadline clamped to the unchanged campaign deadline. Exhaustion aborts the
attempt. Attempted and completed games remain separate; no fabricated terminal reward
or replacement world repairs an invalid or unfinished game.

Desktop uses10 native workers on16 logical CPUs and Mac6 on10. Concrete memory, disk,
free-space and prefix-reserve limits are pinned in the later reviewed host profiles:
6GiB Java heap,12GiB process group,64GiB retained data on Desktop/40GiB on Mac and16GiB
minimum free space per host. After two complete generations, require remaining seconds at least
`14*max(generation1Seconds,generation2Seconds)+7200`. Each duration starts before that
generation's first control and ends after both-host transfers, any calibration,
population materialization, complete game receipts and atomic ES update. For each host,
require `sharedBaseBytes + 16*max(generation1DeltaBytes,generation2DeltaBytes) + 2GiB`
at most the declared host retained-data cap. Shared input/run overhead is counted once;
each generation delta includes all retained actor, receipt, archive, transfer and numerical
artifacts. Generated temporary
copies still count while present. No scientific outcome changes these cost/storage gates.
The immutable scientific package payload (models, projection/directions, dictionaries and
historical subjects) is included in shared base bytes; installed runtime/JDK/environment
closure is independently pinned. Duplicate generated copies each count. Mac sends exactly
two compact resource censuses, after every shard transfer in generations1 and2; these
contain only byte/file counts, include their own persisted bytes, and cannot carry outcomes.
Native task ownership and publication
identity are independent of host path and worker scheduling. Mac uses only the existing
outbound SSH channel. Either peer's terminal state or the original deadline ends waiting.

## Interfaces, storage and audit ownership

The new `coordinated-es-campaign-1` protocol retains canonical UTF-8 JSON plus one LF;
raw-file and semantic digests remain distinct. A generation binds its complete world
bank, original scope, exact center, direction table, calibrated scales and all32 explicit
four-head mutants. Raw host source signatures are provenance, not cross-host semantic
identities. A complete final admission requires all16 input/output center links and
all16 complete population receipts; legacy CPE/TOKEN admissions remain strict.

The native owner supplies typed four-head artifacts, controls/public panels, compact
population records, exact reproduction, historical controls and final paired records.
The numerical owner supplies projection/materialization, prescribed directions and
synthetic mean, calibration, one exact ES update and immutable numerical receipts. The
campaign owner binds these subjects, fixed host tasks, finite process execution,
lossless storage and a no-forward terminal auditor. No owner infers identity from a
filename alone or treats a partial output as a completed transaction.

Reuse the existing campaign supervisor, bounded process owner, immutable outbound
handoff, verified archive primitives and arena/replay factories. Add finite stage tags
and new typed adapters rather than a generic scheduler or a copy of the entire pipeline.
Retain projection factors, centers, directions, compact returns, source/runtime bindings
and exact archived actor bytes. Verify every lossless archive member before deleting
only its expanded generated copy. Never delete original evidence to satisfy a quota.

The terminal auditor independently checks all generation/member/count/lineage subjects,
recomputes recorded mean-return ES arithmetic without model inference, verifies calibration
receipts and the sole final paired metrics, and distinguishes a clean operational stop
from complete scientific evidence. Both hosts must complete/audit and all owned groups
must exit before interpretation. Terminal/audit-file detection occurs at most60 seconds
apart; ordinary count progress is reported every30 minutes. Family80's late notification
and separate post-audit retention times remain recorded, without retroactive clock changes.

Focused constructed proofs cover exact zero/native/production-JS behavior, unchanged
C/P priors and sampling, E/T signed differences/ties/finite scores, sealed simultaneous
decisions and original offsets, all-layer projection, known-sign updates and radius,
quantile/world weighting, calibration failures and diagnostic-only live movement,
atomic generation publication, fixed shard coverage, no duplicate games, peer/clock
failures, compact-memory results, lossless tamper rejection and final-only admission.
