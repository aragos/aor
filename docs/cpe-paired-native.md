# Paired C/P/E collection contract

This source prerequisite extends the fixed paired policy experiment to three independent
CARD, PURCHASE and EXPANSION cohorts. It does not authorize scientific model reads, stream
reservation, collection, fitting, deployment or changes to existing previews. The source base is
`5a293db`; local main `0040ce9` has no engine, AI, training or server-production changes since
their common ancestor. Existing family79 and legacy bundle contracts remain strict.

## Behavior and reference ownership

Each of two iterations collects 640 independent worlds for each assigned head. Global indices
0–511 are TRAIN and 512–639 are VALIDATION. Ten fixed 64-world shards retain global indices;
seat is `index % 4` and epoch is `1 + (index / 4) % 3`. All three current heads control the
assigned seat throughout the complete backbone and every continuation. Raw T, ordinary M8,
frozen F0, qualified E reference and rival G3/E/T roles remain fixed.

The counterfactual reference `b` is the **actual current complete-bundle sampled command** at
the selected root. `referenceIndex == sampledCommandIndex`; neither replaces the observation's
`baselineIndex`. CARD retains its ordinary M8 HARD prior, PURCHASE retains its frozen F0
`PurchaseBaselineInput` HARD prior, and EXPANSION retains the qualified E recommendation.
These are the existing encoder/controller owners. Original C/P actors keep their exact trained
nonzero G3 weights and full probabilities. No original-policy sample is needed for `b`.

The explicit `cpe-paired-bundle-1` descriptor binds CARD, PURCHASE and EXPANSION component
subjects and their complete dictionary identities. C/P policy JSON remains schema1; E remains
schema2/EXPANSION_2. Original and current descriptors bind complete snapshots. The three updated
heads must be published atomically after data from the same current bundle; a partial update is
not a candidate. Iteration two consumes that exact atomic predecessor.

## Root curriculum and interventions

One independent reservoir stream chooses uniformly among the assigned head's branching windows
in the assigned epoch of the complete current-policy backbone. Singleton windows are counted and
may provide anchors but cannot become branching roots. The completed eligible-window count and
selected ordinal are retained. Missing roots remain missing; no refill or quality filter is used.
This is a prospective world curriculum, not an unbiased sample of runtime visitation: the count
depends on the realized continuation, although selection never reads rewards or hidden features.

The actual sampled command is retained plus up to eight uniformly sampled distinct nonreference
offered indices. The reference delta is zero. Each selected action receives exactly two shared
future streams. Inclusion probabilities cover all currently offered alternatives, without
silently expanding existing action support. The complete sealed command batch, absolute PLAYER
number, batch-start number, actor seed and existing policy queue ownership are preserved.
Fresh complete controllers must reproduce the original batch at its absolute offset before
intervention; every tail uses independent controller instances and canonical replay validation.

Authority state can generate Monte Carlo return targets. It never enters actor observations,
proposal selection or outcome-conditioned winner labels. Properly marginalized privileged
returns are valid targets; choosing actions from a hidden-world hindsight winner is a different
operation and is outside this contract.

## Evidence and compatibility

Public sidecars contain only typed original observations, full original/current probabilities,
public request/stream provenance, sampled/proposed indices and original-policy anchors. Labels
are separate sidecars; full origins retain private canonical executor evidence for verification.
The first iteration retains assigned-head observations throughout TRAIN backbones, including
worlds with no eligible root. Later current TRAIN roots augment the fixed original anchor set
using stable iteration/head/world/decision identities. No validation effects select an iteration
or tune the recipe.

Shared execution, canonical, snapshot and archive owners should be reused. New typed contracts
must not weaken old TOKEN, joint two-head or final64 admissions. Existing C/P/E forward and
sampling math is reused unchanged. Constructed tests cover prior/reference separation,
current-bundle routing, reservoir selection, complete offered support, privacy, seed/offset
preservation, canonical evidence and native-to-production-JS probability/command parity.

The prospective campaign ceiling is 76,070 terminal executions: 3,840 complete backbones,
up to 69,120 tails, 24 backbone reproductions, 3,072 final three-role screen worlds, 12 screen
replays and two historical controls. The parent protocol owns the 18-hour original clock,
900-second pure audit and 48-GiB combined retention ceiling. This document authorizes only the
source prerequisite and synthetic verification; preparation and scientific launch require their
own reviewed immutable packages.
