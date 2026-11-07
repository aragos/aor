# Contributing

Follow [AGENTS.md](../AGENTS.md). Use the [README owner map](../README.md#find-the-owner) to open only
relevant contracts and code. Tests own executable evidence; documentation owns behavior and contracts.

## Checks

Run focused checks while editing. CI in [checks.yml](../.github/workflows/checks.yml) is the integrated
pre-release gate; wait for the exact commit instead of repeating the full suite locally.

| Change | Local verification |
| --- | --- |
| Kotlin / Gradle Kotlin | Finish `./gradlew formatKotlin`, then `./gradlew checkKotlinFormat` |
| Rules / flow | `./gradlew :core:jvmTest --tests '*RelevantTest'`; include `:core:jsNodeTest` for shared replay/target changes |
| AI / training | `./gradlew :ai:jvmTest :ai:jsNodeTest :training:test`; Python supervisor tests under `scripts/` |
| Service / persistence | `./gradlew :server:fastTest --tests '*RelevantTest'`; use `:server:test` for affected full-game HTTP acceptance |
| Saved-game behavior | `CatalogCompatibilityTest`, `UpgradedGameCompatibilityTest` and representative pre-change saves loading, replaying and continuing |
| Web | `npm test --workspace web`, `npm run build`, affected browser suites below |
| Map / catalogs | [Geometry generation and validation](map-design.md#generate-and-validate-drawings), geometry tests and live placement/reload |

The formatter is pinned ktfmt, Kotlin language style; `.editorconfig` matches. Serialize Gradle builds
per checkout. Use canonical text-command fixtures for game flows; browser lifecycle fixtures use
production StrictMode and live offers. Unit-file example: `node --test web/tests/api.test.mjs`.
SQL tests use PostgreSQL 18: an isolated native cluster on Linux/macOS or a disposable
`AOR_TEST_DATABASE_URL`, with a schema per fixture. CI provides the database.

For a full local source pass when needed:

```sh
./gradlew :core:jvmTest :core:jsNodeTest :ai:jvmTest :ai:jsNodeTest :ai:jsBrowserProductionLibraryDistribution :training:test :server:test checkKotlinFormat
npm test --workspace web
npm run build
```

The AI library task must precede Vite/build when its output is absent or stale; web output is
`web/dist/`. CI runs source checks and the production container in parallel. The container performs
the production web build/typecheck and SQL smoke test; the final `verify` gate requires both jobs.

Batch affected browser suites to share server, Vite and Chrome startup:

```sh
npm run test:browser --workspace web -- history.browser.mjs bots.browser.mjs
```

[browser-check.mjs](../web/scripts/browser-check.mjs) isolates game data and browser storage, cleans
owned processes and leaves artifacts outside the repository. With no selection it runs the broad
`map.browser.mjs` suite, which already includes most UI flows; select focused files during iteration.
`AOR_BROWSER_SUITE` selects one suite; `AOR_BROWSER_TIMEOUT_MS` overrides the five-minute per-suite
limit. `AOR_BROWSER_SERVER` reuses an installed executable. For SQL browser checks use
`AOR_BROWSER_SERVER="$PWD/web/scripts/postgres-browser-server.sh"` before the npm command. To exercise the
built app and worker, run `npm run build`, then set `AOR_BROWSER_PREVIEW=1` on the same browser
command. Preview mode requires existing `web/dist/index.html` and verifies its API proxy; ordinary
checks keep using the development server.

## Opponent training

Build once; the complete distribution runs independently with JDK 21+ and Python 3.10+ on macOS/Linux.
Keep `bin/` and `lib/` together; `:training:distZip` produces a portable zip.

```sh
./gradlew :training:installDist
python3 training/build/install/training/bin/train-opponents.py --mode train --output .aor-training/run --hours 48
```

Use `--help` for options, `--output .aor-training/run --status` for status, and
`--mode train --output .aor-training/run --resume --hours 24` to resume. For unattended execution,
prefix the Python command with `nohup` and append `> training-supervisor.log 2>&1 &`.
Use an absolute output path when launching from another directory; SIGTERM the supervisor PID to stop safely.

One output lock prevents concurrent writers. Resume preserves the original 72-hour allowance,
baseline, population, coverage and exploration rounds. Repeat nondefault matrix/seed/batch options;
changed settings or policy bytecode require a new output directory. Resource limits and total round
count may change. Sampled process-group limits cap CPU at 80% of logical cores and stop at 80% resident
memory; they do not reserve hardware. Logs/checkpoints stay in ignored `.aor-training/`; training
never reads production games or secrets.

For throughput work, run `./gradlew :training:benchmark --args=".aor-training/before.json"`,
then repeat after the change with `--args=".aor-training/after.json .aor-training/before.json"`.
The benchmark warms up before measuring 24 repeated games across 3–6 players, both maps and
Normal/Hard policies, plus repeated directed-regret evaluations across the full curriculum.
It records wall time, thread CPU and allocated bytes separately for games and regret, verifies
replay in warmup, and checks outcomes, coverage and regret against the baseline. Use the same JDK
and an idle machine for both runs. These fixed development games do not measure playing strength.

The default matrix covers all 128 player-count/variant combinations. `coverage.json` distinguishes
observed/missing decisions, cards and configurations; directed probes do not establish exhaustive
position coverage. `rounds.jsonl` records exploration; `evaluation.json` records independent comparison.
Review evaluation and coverage gates before updating the [browser bundle](design.md#automated-seats).
Limit specialized entries to qualified configurations and preserve compatible fallbacks for other
setups and existing saves. Failed promotion retains the prior model and diagnostic `candidate.json`.
Schema/rules/catalog metadata protects inference compatibility; human strength remains unverified
without independent playtests.

## Full-game strategy experiments

Use `arena` for a frozen complete-game comparison and `strategy-search` for a small search over
`planning.*` weights. Both use ordinary engine play, HARD opponents, the same chance seeds
on both sides of each comparison and a rotating replacement seat. Model paths resolve relative to
the source JSON; `null` selects the built-in prior. Configuration identifiers are defined by
[TrainingMatrix](../training/src/main/kotlin/dev/aragos/aor/training/TrainingMatrix.kt).

External schema-one model weights override the built-in prior in both training and browser play.
Use an explicit zero to disable a prior feature. The artifact writer preserves an in-memory
policy's exact weights by writing those zeros; see the shared
[model wire contract](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotModel.kt).

After building, copy the **entire distribution** to a new directory before starting a long run:

```sh
mkdir -p .aor-training/executors
cp -R training/build/install/training .aor-training/executors/strategy-v1
```

Keep that directory's `bin/` and `lib/` unchanged for active jobs and all resumes; copying only the
launcher does not freeze the policy. Subsequent builds can use the ordinary build directory. Changed
packaged code, model weights or source settings require a new experiment output directory.

For example, save this as `.aor-training/search-spec.json`, alongside compatible frozen
`baseline.json` and `historical.json` models. Bounds are illustrative and must be chosen before the
run. The source contracts are [ArenaSpecification](../training/src/main/kotlin/dev/aragos/aor/training/StrategyArena.kt)
and [StrategySearchSpecification](../training/src/main/kotlin/dev/aragos/aor/training/StrategySearch.kt):

```json
{
  "schemaVersion": 1,
  "purpose": "SCREENING",
  "configurations": ["players-4/standard/no-crusades/deck-true/art-true"],
  "candidate": "baseline.json",
  "incumbent": "baseline.json",
  "opponents": [null, "baseline.json", "historical.json"],
  "pairsPerConfiguration": 16,
  "seed": 310000,
  "parameters": [
    {"name": "planning.heldCommodity", "min": 0.0, "max": 2.0},
    {"name": "planning.retainedCommodity", "min": 0.0, "max": 2.0}
  ],
  "candidates": 24,
  "screeningPairs": 16,
  "selectionPairs": 64,
  "finalists": 4
}
```

```sh
python3 .aor-training/executors/strategy-v1/bin/train-opponents.py \
  --mode strategy-search --source .aor-training/search-spec.json \
  --output .aor-training/search --hours 6
python3 .aor-training/executors/strategy-v1/bin/train-opponents.py \
  --mode arena --source .aor-training/confirmation-spec.json \
  --output .aor-training/confirmation --hours 6
```

Search replaces only the named weights, retaining all others from `candidate`. Bounds must include
zero; candidate vectors include zero and midpoint anchors plus deterministic stratified samples.
When the candidate budget has room, an in-range current parameter vector is retained as another
anchor, so refinement can select the existing settings. A parameter can also specify an optional
`linkedWeights` object, such as `{"planning.endgameAdvanceIncomeCorrection": -0.8}` on
`planning.advanceIncomeCorrection`. Each linked weight equals its finite, nonzero multiplier times
the sampled primary value; it adds no sampling dimension. Primary and linked targets must be
distinct across the search, and both endpoints must stay within model weight limits. Zero disables
the whole group, and a current anchor is eligible only if all existing linked values satisfy their
relations exactly. Candidate records expose every replaced weight. Omitting links or using `{}`
preserves the unlinked candidate vectors; `null` and non-object links are rejected.
The optional search counts default to those shown above. Verify names against the implemented
[economic feature definitions](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotEconomicProjection.kt)
and the separate [expansion](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotExpansionPlanning.kt)
and [advance sequence](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotAdvancePlanning.kt) contracts.
The top finalists receive fresh selection games; frozen incumbent games are reused within each
bank. `search.json` records both rankings and exact seed banks. `candidate.json` is an unqualified
proposal. Its selection intervals are descriptive, including the winning candidate's interval.

For direct compact token-head search, copy the frozen baseline and set every reserved
`planning.tokenValue.*` coefficient to zero except `planning.tokenValue.enabled = 1`. Search only
named existing [token feature definitions](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotTokenValue.kt),
including the [plan features](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotTokenPlanProjection.kt),
while retaining the chosen proposal policy and override rule. By default these are six legal
proposals and a strict 0.02 margin. The all-zero coefficient anchor retains baseline choices exactly.
These directly optimized coefficients define uncalibrated action-advantage scores; continuation-fitted
coefficients instead estimate terminal composite advantage. Freeze the dimensions, bounds, links, opponent pool and acceptance criteria before games.
Evaluate repeated use through full games and require independent confirmation before adoption.

Two independent unit gates support token-action experiments. `planning.completeTokenAmounts = 1`
adds every offered nonnegative quantity within cash and physical stock to the existing signed
proposals. `planning.peacefulTokenMargin = 1` accepts a strictly positive head advantage without the
usual margin only when the same feasible plan increases ordinary income without increasing unused
tokens. Other choices still need an advantage above 0.02. The head must also be enabled. These gates
preserve feature meanings and cash tradeoffs; they neither require maximum spending nor predict
access unlocked by later purchases. Freeze each gate in the model before comparisons.

`planning.leaderTiming = 1` enables the shared purchase-plan comparison described in
[architecture](design.md#automated-seats). `planning.retainedLeader` weights the retained nominal
discount option in model-score units per dollar after the remaining-horizon adjustment. Search that
weight with the timing gate fixed, and compare against a separate frozen incumbent. Its zero-weight
anchor still includes the timing correction; it is not a disabled-controller ablation. Older
observations without forecast metadata retain ordinary card evaluation.

Prepare `confirmation-spec.json` separately: retain the arena fields, set `candidate` to
`search/candidate.json`, `purpose` to `CONFIRMATION`, `pairsPerConfiguration` to the predeclared final
sample count (for example 512), and `seed` to 310002. Omit search-only fields. Search uses offsets
310000 and 310001; reserve the next consecutive offset for confirmation. These banks are disjoint
because arena seeds advance by 8191; arbitrary different offsets can overlap. Track prior banks and
never reuse selection or previously inspected final seeds to qualify a changed candidate. Freeze the
candidate, opponent pool and acceptance target before this independent comparison.

Arena output contains `pair-*.json` and aggregate `evaluation.json`; search checkpoints each
stage/candidate pair and incumbent game. Repeat the same command with `--resume` to reuse completed
units, retaining the source, models and frozen distribution. `--status`, worker limits and cumulative
resource allowances work as above. Neither mode publishes a model or establishes human playing
strength; profile qualification and application checks remain separate.

Correctness changes to the controller require fresh qualification even when model weights stay
unchanged. A candidate selected under an older executor may be confirmed under a new frozen one;
report that as transfer to the new controller and evaluate both sides under that same controller.
Earlier results do not establish the strength of a changed executor. Separate small feature groups
when searching so individual gains and interactions remain reviewable.

Use `strategy-replay` to inspect a completed comparison without changing it:

```sh
python3 .aor-training/executors/strategy-v1/bin/train-opponents.py \
  --mode strategy-replay --source .aor-training/confirmation-spec.json \
  --pair .aor-training/confirmation/pair-0.json --index 0 --side candidate \
  --output .aor-training/replays/candidate-0 --hours 1
```

The inspector runs one worker for at most one hour. `replay.json` includes canonical authority
entries, the controlled seat's ordinary decision snapshots, chosen features, leading alternatives
and nonzero feature counts. Scores shown are immediate linear values; HARD may choose a different
first move through its bounded planner. The recorded pair preserves its original opponent lineup
and requires exact terminal results, excluding elapsed time. Canonical entries separately replay to
the identical final state. Keep authority outcomes separate from policy inputs: replay files include
simulated private draws for verification. Such inspection explains examples; it does not estimate
average strategy effects or replace fresh confirmation games.

## Purchase policy learning

The experimental purchase actor controls every shipping and advance decision throughout a game.
All other decisions use its frozen HARD baseline. Actual engine transitions regenerate prerequisites,
prices and legal actions after each choice. The initial residual policy gives the baseline action
97% probability and shares the remaining probability across every other offered purchase; updates
learn from terminal win share and rank. Each trained model requires fresh complete-game qualification
before browser activation.

Build `:training:installDist` and create an isolated Python environment with the pinned dependencies
in [purchase-policy-requirements.txt](../scripts/purchase-policy-requirements.txt). The numerical
learner uses CPU Float64 arithmetic; no GPU or LLM service is required.

A collection source is a JSON object with `schemaVersion: 1`, one `configuration` identifier,
`games` (1–10000), an unused arena `seed` offset, `baseline` and `opponents` model paths, and
`policy` (null for exact baseline data, otherwise an experimental purchase-policy path). Relative
paths resolve beside that source. Both baseline and learned policies use HARD opponents. The
replacement seat and lineup rotate as in the full-game arena; use the same bank for paired controls.
Optional `opponentPurchasePolicy` supplies one frozen purchase actor for every other seat. Each rival
keeps its corresponding `opponents` model for its prior recommendation and all other decisions.
Only the controlled seat produces training records. An absent or null opponent actor preserves the
original collection format; a present actor adds its own fingerprint and immutable snapshot.

```sh
python3 scripts/train-opponents.py --mode purchase-collect --source purchase-collection.json --output .aor-training/purchase-data --workers 6 --hours 2
python3 scripts/purchase_policy.py initialize --schema .aor-training/purchase-data/collection.json --seed 74101 --output .aor-training/purchase-initial
python3 scripts/purchase_policy.py update --source .aor-training/purchase-on-policy --model .aor-training/purchase-initial/policy.json --seed 74102 --output .aor-training/purchase-update
```

Run the numerical commands with the isolated environment's Python. Before `update`, collect a new
complete batch whose source names the exact input `policy.json`. Baseline imitation records have no
invented behavior probabilities and cannot be used as on-policy update data. Freeze the numerical
configuration with `--config FILE`; `--help` lists initialization, update and toy-check commands.
Carry optimizer state only through `--optimizer-from CHECKPOINT` matching the exact next input model.

`collection.json` owns the complete catalog-derived feature dictionaries and input fingerprints.
Atomic `game-N.json` records retain actor-safe sparse observations, sampled choices, likelihoods and
terminal labels. `finished.json` binds their ordered hashes. Read outcomes only after completion;
changed inputs, code, dictionaries or recorded behavior require a new collection directory.
`--resume` on the supervisor preserves completed games and its elapsed allowance. Numerical update
resume also preserves its original deadline, input hashes, epoch, model and optimizer state.

The update uses complete-game returns and a learned value baseline, clipped policy ratios, bounded
parameter gradients and a behavior-KL limit. Rejected epochs restore both parameters and optimizer.
Use the same sampled selector for collection and evaluation; greedy selection is a separate policy.
The shared [encoder and inference](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/PurchasePolicy.kt)
run on JVM and JavaScript. Numerical updates prepare validated sparse inputs once and preserve the
same Float64 arithmetic, optimizer state and portable model output.

The browser's separate [purchase asset](../web/src/ai/purchaseModel.json) is optional. Its manifest
binds the exact baseline weights, actor/encoder versions, rules, catalog and qualified setup. The
[selector](../ai/src/jsMain/kotlin/dev/aragos/aor/ai/PurchaseBotExports.kt) caches the decoded actor,
computes the ordinary baseline recommendation and binds it to the server's actor-safe projection.
Unsupported scopes and unavailable or incompatible actors retain that baseline. Preserve sampled
selection and deterministic retries; greedy inference or a changed baseline requires requalification.
The supported scope is HARD shipping and advance purchases in four-player standard-map games with
No Crusades, the deck modification and immediate Master Art discard enabled. Other decisions and
setups continue using the compatible baseline.

Freeze training budgets, checkpoint-selection rules and unused confirmation worlds before launch.
Compare complete games against both the unchanged initialization and the qualified baseline, then
test learned rival populations. When testing several seats or continuations in one world, keep them
together and compute uncertainty over independent worlds. Reports, checkpoints and generated model
files stay in isolated experiment storage; they do not activate a browser model automatically.
See [open learning work](follow-ups.md#autonomous-strategy-discovery) for broader strategy coverage.

## Card policy learning

The experimental card actor learns repeated choices within ordinary and final card windows. It
controls every command currently offered by the bot projection, including playing another card while
retaining a leader. These offers still use the existing bounded province combinations and protection
amounts. Pass is available only when legal and ends the ordinary card window. Nested war resolutions,
optional discards and other decisions retain the frozen HARD baseline.

Every seat uses a separately frozen purchase actor for shipping and advances. This keeps card timing
and its later purchases together throughout actual complete-game continuations. Card training updates
only the card model. Printed Word receipts, protection payments and other immediate consequences come
from authoritative ordinary-view quotes; nominal leader discounts are facts, not reward labels.

Build the standalone trainer as above. A card collection source uses the purchase source's
`configuration`, `games`, `seed`, `baseline` and `opponents` fields, plus mandatory
`frozenPurchasePolicy`. Optional `policy` supplies the controlled card model; null uses exact baseline
card choices. Optional `opponentCardPolicy` supplies a frozen card model for the rival seats. Each
seat's baseline still supplies its own HARD recommendation and all decisions outside these actors.

```sh
python3 scripts/train-opponents.py --mode card-collect --source card-collection.json --output .aor-training/card-data --workers 6 --hours 2
python3 scripts/card_policy.py initialize --schema .aor-training/card-data/collection.json --seed 74401 --output .aor-training/card-initial
python3 scripts/card_policy.py update --source .aor-training/card-on-policy --model .aor-training/card-initial/policy.json --seed 74402 --output .aor-training/card-update
```

Card models have distinct actor and encoder versions. A zero residual starts at the same 97% baseline
prior; the complete card dictionary is frozen before collection. Card records use explicit
`trajectoryEncoding: gzip-json-v1` and atomic `game-N.json.gz` files. Completion hashes bind the
compressed bytes. The manifest and each game bind the frozen purchase model independently. Only
controlled-seat card decisions become learning rows; the separate `purchaseAudit` records public
purchase consequences for diagnostics and is never a policy input or training target.

Before scaling, measure independent-game coverage of retained leaders, alternative card plays,
normal/final windows and later uses of discounts. Protection variants share the exploration budget,
so total nonbaseline probability alone does not establish useful exploration. Human feedback positions
are diagnostic cases, not independent strength evidence; unused discounts can still save stabilization
costs or accompany cash rebates. Keep the existing browser policy until fresh complete-game and
rival-population qualification succeeds.

### Joint card and purchase learning

The experimental `joint-collect` mode records both typed actors from each complete game.
Its [source contract](../training/src/main/kotlin/dev/aragos/aor/training/JointCollectionInput.kt)
requires `schemaVersion: 1`, `kind: "joint-collect"`, `configuration`, `games`, `seed`,
`baseline`, `bundle` and an `opponents` list of frozen bundle paths. Every
[bundle](../training/src/main/kotlin/dev/aragos/aor/training/JointActorBundle.kt) binds its setup,
F prior and exact card/purchase model bytes. A rival may explicitly use `card: null` for F
card play; the learning seat requires both actors.

```sh
python3 scripts/train-opponents.py --mode joint-collect --source joint-collection.json --output .aor-training/joint-data --workers 6 --hours 2
```

The [joint updater](../scripts/joint_trajectory.py) reads the same completed origins and terminal
labels for both phases, including games with an empty phase array. It keeps separate Adam states
and fits both actors against their original behavior bundle before publishing the next pair.
An entirely empty phase rejects the batch before either fit. Interrupted updates retain completed
phase artifacts and resume with their original deadlines; they cannot publish half a bundle.

Joint fits explicitly select `aor-ppo-checked-prefix-1`: every block of at most eight minibatches
checks KL against the original behavior over the entire phase collection. A rejected block restores
its model and Adam state while retaining earlier accepted blocks. Optional purchase exploration
uses `PurchaseReferenceObjective` for KL from a uniform distribution over all offered actions,
including Pass. Card exploration uses the separately versioned physical-card reference. These
training objectives do not change inference, terminal rewards or default legacy update behavior.
The two phase KL bounds are separate; they are not a bound on whole-game policy change.

Use a fixed prospective recipe and fresh factorial evaluation to separate card gains, purchase
gains and their interaction. Synthetic coordination results and training losses do not establish
game strength or authorize replacing the playable policy.

### Token and expansion learning

The experimental `territory-collect` mode learns token commitments and repeated expansion
choices while holding an explicit card/purchase bundle fixed. Its
[source contract](../training/src/main/kotlin/dev/aragos/aor/training/TerritoryCollectionInput.kt)
binds both new actors, their rivals, the original F prior and the frozen card/purchase actors.
The [encoders](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/TerritoryObservationEncoder.kt)
retain every command in the current bot projection and use authoritative ordinary-view
quotes. They do not add F's utility scores as features. Token offers are a bounded set of
signed amounts and cash endpoints, rather than every possible integer bid. Expansion offers
are regenerated after each action so shipping capacity, markers and remaining tokens stay current.

```sh
python3 scripts/train-opponents.py --mode territory-collect --source territory-collection.json --output .aor-training/territory-data --workers 6 --hours 2
```

The [territory updater](../scripts/territory_trajectory.py) retains both typed views of every
complete game and publishes the two actors together. Its separately versioned offered-action
references are explicit training objectives; existing card and purchase objectives are unchanged.
Both actors learn from terminal game outcomes, with separate critics, row means and Adam states.

Sealed token decisions record their actual simulator seed and batch-start number. Their global
command number includes a batch offset and must not be used to reconstruct that seed. These fields
are replay provenance, not actor features. Opponents' pending commitments remain private. Nested
combat choices and decisions outside the declared actor scopes retain F.

### Territory action-value learning

The experimental `territory-value-collect` mode uses separately versioned linear-output
[action-value models](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/TerritoryValuePolicy.kt).
Its [source contract](../training/src/main/kotlin/dev/aragos/aor/training/TerritoryValueCollectionInput.kt)
fixes the card/purchase continuation, both territory models and the exploration probability.
Collection uses epsilon-greedy choices over actual offers; evaluation uses greedy choices.
Equal values prefer the ordinary F recommendation, then canonical command order. An initial
constant-value greedy model therefore reproduces F territory choices.

```sh
python3 scripts/train-opponents.py --mode territory-value-collect --source territory-value-collection.json --output .aor-training/territory-value-data --workers 6 --hours 2
```

The [learner](../scripts/territory_value_trajectory.py) joins each complete game's token and
expansion records by their actual decision numbers. The next recorded owned decision is the
successor, including repeated expansion and skipped phases. Intervening fixed-policy actions
and chance events belong to that sampled transition. Only the last owned transition receives
the terminal game reward; incomplete games cannot supply targets.

Double-Q targets select a legal successor action with a frozen online model and evaluate that
same action with a lagged target model. Both phases use the same frozen model pair throughout
an update. Replay evicts complete games, samples phase transitions uniformly, and retains
hash-bound origin identities. Checkpoints bind replay membership, online and target models,
separate Adam states and synchronization counters before publishing the next complete pair.
The implementation preserves the earlier PPO models and collectors as distinct contracts.

`UpdateConfig.targetSteps` selects one or sixteen actual owned decisions. One is the default.
The sixteen-step profile follows the recorded sequence across both phases, stops at the real
terminal row, and otherwise bootstraps from the recorded endpoint's legal offers. It counts
neither engine commands nor turns. The intervening actions come from the collection policy,
so these uncorrected targets can carry behavior-policy bias. Checkpoints retain the selected
horizon; carrying a model and optimizer into a different horizon is rejected. Stored replay
rows and terminal calibration retain their original one-step meaning.

Action values are finite estimates, not probabilities: they may lie outside the terminal
reward range. An explicit numerical guard stops excessive growth without clipping targets.
Measure collection, replay memory, fitting and checkpoint costs before freezing a full-game
budget. Qualify the final policy on fresh games; synthetic recovery tests do not establish
playing strength or authorize changing the browser policy.

### Paired card advantage experiments

The separate [advantage collector](../training/src/main/kotlin/dev/aragos/aor/training/CardAdvantageCollectionInput.kt)
uses explicit prospective world/seat/epoch requests and paired complete-game continuations at a root
and its next own normal card decision. Each child has its own observation and labels. All descendants
stay in one original world's split; identical child inputs are averaged before decision-group
weighting. The actor, proposal sampler and frozen continuation have independent versioned contracts.
Final windows and nested resolutions retain the HARD reference, and all seats retain the frozen
purchase actor.

Build and freeze the complete distribution as above. It includes the native supervisor, numerical
learner and [bounded experiment driver](../scripts/card_advantage_experiment.py). Use the pinned JDK
21 and Python/PyTorch environment. A laboratory directory must contain its immutable window protocol,
fresh seed reservations, explicit origin requests, frozen inputs and passing preflight evidence bound
to the exact runtime. The driver rejects missing or mismatched preflight evidence before starting a
game. With those artifacts prepared:

```sh
python3 .aor-training/executors/card-advantage/bin/card_advantage_experiment.py \
  --lab .aor-training/card-advantage --protocol .aor-training/card-advantage/window-v2.json
```

Repeat the same command to resume; add `--status` to inspect the latest stage. Completed work keeps
its original identities, while incomplete work keeps its original deadline. SIGTERM stops owned
children. The driver enforces collection/fit limits, disk bounds, minimum context coverage, local
development gates, independent paired-game confirmation and opponent-population robustness. It
preserves failed or incomplete evidence, never fits a partial collection, and does not activate
browser models. Read `window-result.json` and the stage reports for the outcome. Qualification remains
specific to the declared setup and rival population; it does not establish human-level strength.

The native supervisor `card-advantage-run.py --mode evaluate` accepts optional `--batch-size 1..64`
to reuse one JVM and its parsed models across several explicit game indices. The default is 1;
collection remains one origin per JVM. Freeze the batch size before launch: resume retains that
allocation and the original stage deadline. A batch shares one remaining allowance, while every
game keeps its own validated atomic record. An interrupted batch preserves completed games for
resume; the stage is complete only after every declared game has been verified.

The [capacity correction driver](../scripts/card_advantage_compact_experiment.py) tests a separately
frozen reduction from 64 to eight active hidden units after a failed independent qualification.
The learner's `--active-hidden 8` retains the original initialization draws for those units and
exports exact zeros for the others, preserving the existing selector format and F fallback.
The previous validation set becomes development evidence. A new collection supplies the local gate,
followed by fresh paired games against the original model and incumbent, then independent
qualification. Its preflight also binds the fixed sequence toy and consumed-game batching proof.
With that separate laboratory and its six-hour clock prepared:

```sh
python3 .aor-training/executors/card-compact/bin/card_advantage_compact_experiment.py \
  --lab .aor-training/card-compact --protocol .aor-training/card-compact/window.json
```

Resume with the same command and original clock. The driver cannot select the old model for another
confirmation attempt, change the prepared recipe, reuse old validation as a gate, or activate a
browser policy. The [capacity analysis](../scripts/card_advantage_capacity.py) compares equally
weighted original worlds; narrower models and lower prediction error alone do not establish strength.

The [action-priority evaluation driver](../scripts/card_advantage_priority_experiment.py) evaluates
one already-fitted C1 control after the compact model fails its common-development gate. It performs
no new fitting. Freeze the exact candidate, an outcome-free frequency calibration on original C1
development observations, fresh world banks and a separate three-hour clock before running:

```sh
python3 .aor-training/executors/card-priority/bin/card_advantage_priority_experiment.py \
  --lab .aor-training/card-priority --protocol .aor-training/card-priority/window.json
```

The paired 256-world screen has a thirty-minute cap and advances only with a mean win-share gain
of at least two percentage points. Its confidence interval is descriptive. Passing leads to the existing independent
strength and repeated-opponent gates, with no additional samples after a failure. Evaluation uses
six workers and batches of eight only with the byte-identical, parity-profiled native runtime.
Resume preserves the original clock and completed per-game evidence; no result activates a browser
model. The driver-local screen leaves existing experiment stage definitions unchanged.

## Token-purchase continuation learning

The experimental `token-value-pilot` mode learns from complete-game continuations at ordinary
token-purchase decisions. Start from a frozen arena specification and add `trainingRoots`,
`validationRoots`, `continuationReplicates` and `regularization`. The
[pilot source contract](../training/src/main/kotlin/dev/aragos/aor/training/TokenValueData.kt)
owns their bounds and defaults. Use a fresh output and freeze the entire executor distribution as
for full-game searches:

```sh
python3 .aor-training/executors/token-value-v2/bin/train-opponents.py \
  --mode token-value-pilot --source .aor-training/token-value-spec.json \
  --output .aor-training/token-value-signal --hours 3
```

Each independently generated game contributes at most one controlled-seat root. The executor commits
to its epoch and within-epoch ordinal before creating the game, stops at that decision, and records
unreached or ineligible requests as skips. It never substitutes a root after inspecting later play.
Historical reservoir checkpoints retain their distinct sampling provenance and remain readable
with their matching frozen executors.
All alternatives and continuation repetitions from a game remain in one split. Opponents select
their sealed bids against the same root observation before the controlled amount is replaced. Negative bids spend
cash to seek earlier order without receiving tokens; the proposal set includes them and zero.
Forks preserve the actual hidden world. Additional continuation seeds vary future randomness and
do not resample existing hands or the deck; treat the originating game as the independent unit.
`continuationReplicates` accepts one through four, with two as the default. Every alternative uses
the same ordered streams; average their paired reward differences before fitting or reporting an
origin. Four repetitions double continuation work relative to two without adding independent games.
The first two streams retain their existing seeds. Changing the repetition count requires a fresh
output directory.

The learner fits observed terminal-reward differences from the incumbent action, using only public
and own-seat features. It does not label the best action in a particular hidden world as correct.
The shared [token value head](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotTokenValue.kt) owns
features, the collector's six legal alternatives and the default override margin. Optional inference
gates can expand quantity coverage and relax the margin for a feasible peaceful improvement, as
described above; the continuation collector retains its bounded alternative set. Exported coefficients use the existing
model format and require an explicit activation weight; absent or disabled heads retain ordinary
play. Training and browser inference use the same feature construction.

The `token-value-2` dataset contract retains the original 32 columns in their original order and
appends seven columns describing a peaceful expansion plan constrained by the proposed token pool,
marker stock and overseas slots. The shared feature owner defines their exact meanings, order and
normalization. Older experimental datasets belong to their original frozen executors; retaining
the earlier columns does not authorize reading an old dataset under the new contract. Resume
fingerprints include the feature version and complete column order alongside code, models and
settings, preventing silent reinterpretation.

Begin with a smoke run, then a small training/validation batch. Validation selects regularization
and measures whether the learned choices have useful continuation advantage; it is development
evidence. The source candidate and incumbent must be the same frozen baseline. Scale only after
inspecting action changes, independent-game results and label sparsity.
Repeated use of the selector can differ from the single intervention used for its labels, so its
frozen model still needs a fresh complete-game `arena` confirmation before application adoption.
Resume only with unchanged code, source, models and feature contracts.

## Automata experiments

The standalone `pilot` mode runs a fixed four-player configuration: standard map, No Crusades
initial cards, deck modification and immediate Master Art discard. It benchmarks the executor,
generates sampled terminal-outcome labels and fits a small CPU neural model. Fresh held-out scenario
validation must pass before the runner evaluates complete games.
It does not publish a browser model or change saved games. The broader
[plan](follow-ups.md#automata-training-pilot) tracks remaining qualification and broader experiments.

Build the distribution, then launch the second round on the Mac using a new output directory:

```sh
./gradlew :training:installDist
python3 training/build/install/training/bin/train-opponents.py \
  --mode pilot \
  --model .aor-training/strategic-four-player/model.json \
  --output .aor-training/pilot-round-2 \
  --benchmark-games 12 --positions 256 --samples 16 --pairs 512 \
  --seed 20261106 --hours 12
```

The baseline path refers to a locally preserved training artifact, not a tracked repository file.
On another machine, provide a compatible model explicitly. The pilot checks rules/catalog metadata,
copies the baseline into its output and fingerprints the packaged code, model and experiment settings.
Do not relabel an incompatible model. The supervisor selects bounded parallelism automatically;
`--workers N` can reduce concurrency. The same CPU, memory and cumulative 72-hour limits apply as in
ordinary training. The command stops when complete or when its time/resource budget is exhausted.

Use `nohup` as described above for background execution. On macOS, prefix the Python command with
`caffeinate -i` to keep the machine awake while the supervisor runs. Inspect progress with:

```sh
tail -f .aor-training/pilot-round-2/trainer.log
python3 training/build/install/training/bin/train-opponents.py \
  --output .aor-training/pilot-round-2 --status
```

To resume, repeat the launch command with `--resume`, retaining the seed and all four experiment
counts. The original frozen model is retained; `--model` may be omitted on resume. Only resources and
the requested remaining time may change. Complete position, validation and evaluation units are reused;
an interrupted unit is recomputed. A changed binary requires a new output directory. Keep the installed
distribution unchanged while a run is active. Small smoke runs can use `--benchmark-games 1
--positions 8 --samples 2 --pairs 2` and a separate output; they do not establish playing strength.
Such smoke runs deliberately stop before the tournament because they contain too few held-out
scenarios to qualify. Changed curriculum and model schemas require a new output directory; preserve
prior runs rather than resuming them with a new binary.

Read these artifacts after completion:

| Artifact | Meaning |
| --- | --- |
| `run.json`, `baseline.json` | Experiment identity, environment, acceptance target and frozen opponent |
| `benchmark.json` | Warmed throughput and transition/policy timings; observation time is also broken into views, legal choices and features |
| `position-*.json`, `teacher.json` | Scenario seeds, public feature vectors, sampled baseline/plan rewards and curriculum scope |
| `student.json` | Experimental schema-2 network and diagnostics against the teacher's labels on separate scenario seeds |
| `validation-*.json`, `validation.json` | Fresh paired continuation results, scenario-level uncertainty, family summaries, baseline fallback and tournament qualification |
| `pair-*.json`, `evaluation.json` | Full-game results and uncertainty when validation passes, or an explicit reason for skipping the tournament |
| `state.json`, `supervisor.json` | Resume identity, phase and resource/status information |

The aggregate observation timing is retained alongside the new breakdown for comparison with earlier
runs. It is not an additional cost to add to the view, choice and feature components.

The teacher uses versioned established-position fixtures in epoch three, with publicly empty rival
hands and a publicly determined remaining card multiset. It samples deck order and ordinary random
outcomes, then plays each continuation to game end using the production engine. These are explicit
curriculum fixtures, not claimed opening histories or a general belief sampler for arbitrary games.
The curriculum varies cash and remaining-deck horizons, uses one to three cards in the acting hand,
and includes three validated public advance/fleet profiles covering commerce, navigation and industry.
Each scenario uses the same development profile for all seats; it does not yet represent arbitrary
differences in opponents' development. Full-game transfer remains an experiment.

The four choices are retain baseline behavior, play now, hold with ordinary expansion, and hold while
prioritizing legal unopposed commodity expansion. The student uses observable features and retains
baseline behavior unless another choice's predicted terminal composite reward exceeds it by more than
0.02. This fixed margin is in the existing 0.8 win-share plus 0.2 rank-reward units, not win percentage
points. The student does not yet learn arbitrary action sequences or a recurrent policy.

With 256 positions, 204 scenarios provide fitting data and 52 are held out. Diagnostics in
`student.json` compare the student with the previously sampled teacher labels. The separate
`validation.json` gate runs fresh paired continuations for the student's already selected choice and
the baseline on every held-out scenario. Those continuation samples do not participate in fitting or
plan selection. At least 32 unique scenarios and two paired samples per scenario are required, and
the paired 95% lower bound on the student's reward improvement must be positive. The interval uses a
normal approximation across scenario means, so repeated rollouts do not count as additional independent
positions. Family results and fallback frequency are descriptive; the gate applies to the complete
predeclared held-out split.

If validation fails or has insufficient coverage, the investigation completes without a tournament.
`evaluation.json` records `skipped: true`, `experimentComplete: true`, zero games and the reason;
`promoted` remains false. A successful process exit therefore need not mean that validation passed.

Complete-game evaluation compares the baseline, a simple feature-based plan selector and the student,
with fixed mixed opponents and rotated seats. Experimental plan selection is limited to epoch three;
earlier epochs and unrelated choices retain baseline behavior. Holding one card still permits other
cards to be played. Teacher results are confined to the known-information curriculum; there is no
omniscient teacher in the full-game tournament. If validation passes, `--pairs 512` means 1,536
complete games, since each lineup tests all three policies. A single pair has no confidence interval.

The initial target is a ten-percentage-point replacement-seat win-share gain with a positive paired
95% lower bound. Neither successful execution, a smaller label error nor passing the scenario gate
proves that full-game target.
`evaluation.json` always records `promoted: false`: review the results before any broader training
or application integration. Qualification of other configurations and browser inference remains
separate work.

### Diagnose teacher and student choices

Use `pilot-diagnose` to separate the quality of the sampled teacher's plans from the student's
ability to select them. It reads a completed compatible pilot and writes a separate investigation;
source and output directories must not overlap.

```sh
./gradlew :training:installDist
caffeinate -i python3 training/build/install/training/bin/train-opponents.py \
  --mode pilot-diagnose \
  --source .aor-training/pilot-round-2 \
  --output .aor-training/pilot-round-2-diagnostic \
  --samples 32 --seed 20261107 --hours 2
```

Omit `caffeinate -i` on Linux. The same CPU, memory, elapsed-time and worker limits apply as for
training. Repeat the command with `--resume` after interruption, retaining the source, sample count,
seed and installed distribution. The source supplies the frozen baseline and student; this mode
does not accept `--model`, retrain the network or run the full-game tournament.

Before generating new outcomes, the investigation fixes each teacher choice using the original
sampled plan rewards, with numerical ties within 1e-12 resolved in strategy order so a tied baseline
is retained. This tolerance handles arithmetic roundoff, not uncertainty in the sampled rewards. It fixes
the student's choices using the original network and prediction margin. All held-out scenarios are
evaluated on negative continuation seeds, disjoint from the source experiment's nonnegative seed ranges. Each
candidate uses the same initial conditions as its baseline comparison. The report compares teacher
and student separately with the baseline, and compares teacher directly with student. Confidence
intervals use scenario means, keeping repeated continuations within their scenario.

The runner first reproduces archived baseline and student outcomes to check continuation compatibility.
Its frozen plan and source fingerprint prevent a resumed investigation from mixing changed inputs.
Read these artifacts in the diagnostic output:

| Artifact | Meaning |
| --- | --- |
| `plan.json` | Frozen teacher/student choices, selected replay cases and source fingerprint |
| `comparison-*.json` | Fresh per-scenario baseline, teacher and student outcomes |
| `diagnostic.json` | Aggregate paired comparisons and scenario-level confidence intervals |
| `replay-*.json` | Canonical entries, decision snapshots, terminal scores and replay verification |
| `run.json`, `state.json`, `supervisor.json` | Execution identity, resumable progress and resource/status information |

The investigation also replays the three largest student losses from the original validation and
the largest gain as a comparison. Canonical entries and decision snapshots make these cases
inspectable. These cases are selected for explanation; they do not replace the complete held-out
set or independently estimate average playing strength. Keep source artifacts and diagnostic
artifacts together when reviewing the result.

This is an investigation of an already examined curriculum. Its fresh continuation results help
locate planning or learning failures, but do not qualify a model for release or override the original
validation gate. Further policy changes require a separately planned evaluation with new scenarios.
