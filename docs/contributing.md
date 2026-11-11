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
| Kotlin/JS tooling | `./gradlew :core:jsNodeTest :core:jsBrowserTest :ai:jsNodeTest :ai:jsBrowserTest` |
| AI / training | `./gradlew :ai:jvmTest :ai:jsNodeTest :training:test`; Python supervisor tests under `scripts/` |
| Service / persistence | `./gradlew :server:fastTest --tests '*RelevantTest'`; use `:server:test` for affected full-game HTTP acceptance |
| Saved-game behavior | `CatalogCompatibilityTest`, `UpgradedGameCompatibilityTest` and representative pre-change saves loading, replaying and continuing |
| Web | `npm test --workspace web`, `npm run build`, affected browser suites below |
| Map / catalogs | [Geometry generation and validation](map-design.md#generate-and-validate-drawings), geometry tests and live placement/reload |

Kotlin/JS tooling uses its own Yarn lockfile. After changing its resolutions, run
`./gradlew kotlinUpgradeYarnLock` and the tooling checks above.

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

The default matrix covers 128 historical player-count/variant combinations, with draw-three-keep-two
disabled. `coverage.json` distinguishes
observed/missing decisions, cards and configurations; directed probes do not establish exhaustive
position coverage. `rounds.jsonl` records exploration; `evaluation.json` records independent comparison.
Review independent evaluation and coverage gates before publishing a model. Limit its
[browser bundle entry](design.md#automated-seats) to qualified configurations, preserve compatible
legacy entries, then build and check the web client. Failed promotion retains the prior model and
diagnostic `candidate.json`. Schema/rules/catalog metadata protects inference compatibility;
matching metadata alone establishes no playing-strength claim. Broader qualification remains
[open work](follow-ups.md).

### Eight-tail token evidence

The separate `ARENA_OVERLAYS_EIGHT_TAILS_1` token collection contract requires exactly eight
continuations per proposal. Its first two chance streams retain the original seed semantics;
legacy and `ARENA_OVERLAYS_1` collections still require exactly two. Historical origin reproduction
checks every declared outcome. `token_order_eight_tail.py` reuses the original zero-head initializer
with explicit basis provenance on a fresh collection, without relaxing the retained-bank ridge
contract. It fits the same five ridge penalties using eight-tail labels and training-only scales.
Two- and four-tail prefix metrics are computed after selection and cannot select a model or
change admission. Fresh whole-game gates remain necessary before activation.

`ARENA_FIXED_T_RESIDUAL_EIGHT_TAILS_1` is a separate reference contract: the controlled player
uses fixed16→15 during both origin play and all continuations. Its observation baseline and
proposal set remain anchored to the actual ordinary bid; collected roots additionally record
`referenceIndex`. Residual targets compare each action with that reference under the same fixed-T
future policy. The `MODEL_FIXED_T_RESIDUAL` selector requires a tagged
`token-order-fixed-reference-residual-1` artifact and retains fixed T unless an original proposal
beats it by more than `.02`. Ordinary selectors reject this envelope, and residual selectors reject
untagged Q models. Rebased ordinary-continuation labels cannot establish fixed-T continuation values.
The existing eight-tail fitter opts into this contract with `--fixed-reference`; all five exported
heads use the tagged envelope, and the frozen basis, penalties and admission rules stay unchanged.

The separately tagged `MODEL_FIXED_T_REFERENCE_NEIGHBORS` experiment keeps that ordinary encoded
baseline and fixed-T fallback. When they differ, proposals are ordinary, fixed T, its offered integer
neighbors, then the existing diverse proposals with stable deduplication; otherwise the old proposal
order is exact. Its `token-order-reference-neighbor-residual-1` envelope requires the matching
proposal and selector versions and cannot be substituted for the older residual artifact. A zero
head still follows fixed T. Experimental rival selectors `FIXED_16_TO_14` and `FIXED_16_TO_13` change
only an ordinary bid of 16 when their exact target is offered; all other bids remain unchanged.

`TokenOrderNeighborMainKt` prepares or augments a completed fixed-T bank using a separately pinned
source and metadata-only missing-action plan. Preparation opens no worlds. Augmentation reconstructs
only prefixes needing a new action, requires exact original request/observation/reference metadata,
then runs only missing branches on their original eight paired streams. Sidecars preserve every
original root, including skips, and retain old outcomes by their unchanged parent file hashes.
Reproduction opens only predeclared new branches; it does not rerun reference outcomes. Historical
source relocation requires explicit aliases, exact model bytes and original runtime fingerprints;
simulation core bytes and unlisted policy classes must remain identical. Reused validation is
development evidence only. New whole-game gates are required before any qualification claim.

### Bounded human expansion imitation


`--mode expansion-imitation-fit --source PATH --output NEW_PATH` runs one native, deterministic
pilot from a sanitized actor-visible observation JSONL, with a ten-minute fitting ceiling. The
source binds `observations`, `observationsSha256`, `baseline`, `baselineSha256`, `gameId` and
`gameSourceSha256`, plus `schemaVersion: 1` and optional `experimentalRulesTransfer`. Game hashes
are provenance only: do not supply original saves, server metadata, credentials or future outcomes.
The fixed pilot takes exactly 50 nontrivial expansion choices from 186 chronological gameplay
observations: 30 training, ten validation and ten descriptive audit decisions. It fits three
predeclared regularizations of eight feature corrections anchored to the frozen ordinary model.
Only training decisions determine numerical scales; each decision has equal total fitting weight.

`selection.json` freezes models and validation-based rival eligibility before the audit labels are
evaluated. Linear ranking loss and actual HARD-planner agreement are reported separately. This
single-game imitation experiment explores training-rival diversity; neither agreement nor a small
whole-game screen establishes human-level strength or qualifies application activation. The fixed
screening and collapse rules are recorded in `run.json`; an audit cannot revise selection.

Arena sources can attach `expansionImitation` with `candidateModel`, `incumbentModel`, or an
`opponentModels` array containing paths or null for ordinary behavior. Rival roles align exactly
with ordinary opponent model indices, not rotating seat positions. Models must preserve all but
the eight declared coefficients of their actual ordinary model. The overlay applies only to
`expand`; other decisions retain ordinary behavior and any disjoint frozen Purchase/token overlay.
Model bytes, roles and snapshots bind arena and replay identities. Omission preserves previous
behavior and metadata. Weight-only strategy search and frozen continuation collectors reject this
overlay rather than silently evaluating different policies. See the native
[`ExpansionImitationProtocol`](../training/src/main/kotlin/dev/aragos/aor/training/ExpansionImitationFit.kt)
for the fixed fit and
[`ArenaExpansionImitation`](../training/src/main/kotlin/dev/aragos/aor/training/ArenaExpansionImitation.kt)
for role and replay contracts.

### Bounded expansion evolution

The ordinary projection also exposes four default-zero
[`BotExpansionTargetFeature`](../ai/src/commonMain/kotlin/dev/aragos/aor/ai/BotExpansionTargets.kt)
inputs for completed attacks on opposing markets: dice-conditional market removal and the owner's
shares of public income, advance count and cash. Shares include every active player, including the
actor; missing or empty totals give zero. Optional defensive decisions can change the actual outcome.
These facts neither rank leaders nor change existing models. The eight-coordinate imitation and
evolution protocols below do not train these extra coefficients; doing so requires a separate,
explicitly scoped experiment and fresh strength comparisons.

[`prepare_expansion_evolution.py`](../scripts/prepare_expansion_evolution.py) freezes a separate
whole-game learning campaign after the fixed imitation screen has a verified terminal receipt.
Only validation-selected rivals surviving that screen's original collapse exclusion may seed it.
The preparer binds that receipt, the pilot's training-only normalization, the exact four training
and two heldout rivals, frozen Purchase environment, native runtime, and an independent reservation
of all game and proposal streams. It copies no original human save or server credentials.

[`expansion_evolution.py`](../scripts/expansion_evolution.py) owns the finite algorithm: four
eight-model generations, fixed Gaussian perturbations of eight expansion coefficients, a bounded
unique archive and a separate common-world selection race. Each generation and the selection race
include an exact ordinary-policy anchor and verify identical incumbent outcomes across candidates.
Positive generation means are selection evidence only. One frozen selected candidate may open
independent confirmation and heldout gates; every final gate requires both 99% lower bounds and
canonical historical replay checks. An eight-hour, one-attempt campaign controller owns the overall
deadline. No successful result activates a policy or establishes human-level strength.

### Portable simulation benchmark

For the learned purchase workload, [automata_benchmark.py](../scripts/automata_benchmark.py) packages
a completed generation's frozen JVM distribution, ordinary models, purchase prior and actors. Its
`prepare` command requires a historical seed audit reserving exactly 256 collection worlds and 128
paired arena worlds. Collection uses the candidate in every purchase seat; the arena compares the
candidate with its incumbent while rivals retain the candidate. The original ordinary rival pool
remains frozen. Bundle paths are relative; source provenance and local JDK/Python identities are
separate, so copying a benchmark does not rewrite or resume its source campaign.

Extract an exported bundle on macOS, native Ubuntu or Ubuntu under WSL 2. Use a local JDK 21+ and
Python 3.10+; the runner also supports the Mac's existing Python 3.9 reference environment. No Gradle,
Node, numerical Python packages or CUDA installation is needed:

```sh
python3 aor-benchmark/scripts/automata_benchmark.py run --bundle aor-benchmark --output desktop-benchmark --java /path/to/jdk/bin/java
```

The default worker sweep is 4, 6, 8 and 10, skipping counts above the host's existing supervisor
allowance. `--workers 6` selects one pass. Run on an otherwise idle machine. `--smoke --workers 2`
uses the first two worlds of each reserved bank to check execution; those worlds are intentionally
reused by the full benchmark. Each run needs a new output directory and allows one attempt per
stage, bounded by 15 minutes per stage, two hours overall, 4 GiB sampled process-group RSS/output
and 2 GiB minimum free disk. RSS is reduced further on memory-constrained hosts. Limits are sampled,
so transient overshoot is possible. SIGINT/SIGTERM clean the owned process group.

Reports retain total/native wall time, throughput, peak RSS, GC logs and all original per-world records.
Native timings include JVM startup and warmup; compare the same workload sizes on both machines.
They compare complete controlled purchase trajectories and terminal results, plus paired arena
terminal outcomes and decision counts, excluding only signatures and elapsed times. The arena does
not record full trajectories. The bundled Mac reference is checked automatically; any per-world
difference fails parity even if aggregate outcomes match. Worker, platform and smoke reuse is for
performance/determinism only, never independent strength evidence. These commands neither train
nor activate actors. Training on another host requires fresh environment binding and qualification.

After a full local reference passes, `export --bundle BUNDLE --reference RUN --archive FILE.tar.gz`
includes both the immutable bundle and all reference evidence with hashes. Preserve the generated
archive checksum alongside the transfer.

#### Additional Ubuntu worker over SSH

The Mac and desktop can run independent jobs concurrently. Give each job its own output directory
and reserve disjoint training/evaluation worlds; the benchmark's explicit world reuse is an exception.

On Windows 11 22H2 or newer, install [WSL and Ubuntu](https://learn.microsoft.com/en-us/windows/wsl/install)
from administrator PowerShell, restart if requested, then open Ubuntu and create its Linux account:

```powershell
wsl --install -d Ubuntu
wsl --update
wsl -l -v
```

Confirm Ubuntu shows version 2. Merge the following into `%UserProfile%\.wslconfig`; 24 GB is a
suggested ceiling for the 32 GB desktop, leaving memory for Windows. WSL uses all logical processors
by default. The [documented idle setting](https://learn.microsoft.com/en-us/windows/wsl/wsl-config)
keeps the distribution available after terminals disconnect:

```ini
[wsl2]
networkingMode=mirrored
memory=24GB

[general]
instanceIdleTimeout=-1
```

Run `wsl --shutdown`, then reopen Ubuntu. Mark the trusted home Wi-Fi network Private and add this
port-specific rule in administrator PowerShell, following Microsoft's
[mirrored networking instructions](https://learn.microsoft.com/en-us/windows/wsl/networking#mirrored-mode-networking):

```powershell
New-NetFirewallHyperVRule -Name "AoR-SSH" -DisplayName "AoR WSL SSH" -Direction Inbound -Action Allow -Profiles Private -VMCreatorId '{40E0AC32-46A5-438A-A0B2-2B479E8F2E90}' -Protocol TCP -LocalPorts 22
ipconfig
```

Record the Wi-Fi IPv4 address. Inside Ubuntu, install the
[SSH server](https://ubuntu.com/server/docs/how-to/security/openssh-server/),
[JDK 21](https://packages.ubuntu.com/openjdk-21-jdk), Python and session tools:

```sh
sudo apt update
sudo apt install -y openssh-server openjdk-21-jdk-headless python3 python3-venv tmux git
sudo systemctl enable --now ssh
mkdir -p ~/aor-worker
```

From the Mac, use the Ubuntu username and Windows Wi-Fi address. Install the existing public key
using the Ubuntu account password when prompted, then connect:

```sh
ssh-copy-id -i ~/.ssh/id_ed25519.pub UBUNTU_USER@DESKTOP_IP
ssh UBUNTU_USER@DESKTOP_IP
```

This connects directly to Ubuntu over the local network; no router port forwarding is needed.
Keep the desktop awake while jobs run. In Ubuntu, start `tmux new -s aor`, run the benchmark or job,
and detach with Ctrl-b then d. Reconnect with `tmux attach -t aor`; [tmux preserves running programs
across SSH disconnects](https://github.com/tmux/tmux/wiki/Getting-Started#attaching-and-detaching).
Keep code and data in `~/aor-worker` on the [Linux filesystem](https://learn.microsoft.com/en-us/windows/wsl/filesystems#file-storage-and-performance-across-file-systems).

For native Ubuntu, use the same Ubuntu package and Mac SSH commands with Ubuntu's LAN address
(`hostname -I`); omit the WSL and Hyper-V steps. If its firewall is enabled, allow TCP 22 from the
Mac's LAN address. Each host's environment, limits and results remain independently recorded.

## Unattended automata campaigns

The [controller](../scripts/automata_campaign.py) and [experiment adapter](../scripts/automata_experiments.py)
run locally without an LLM, API key, or Python numerical packages. Use Python 3.9+ and JDK 21 on
macOS or Linux. They reuse the native simulation, token-value fitter and whole-game arena.
The campaign is bounded by elapsed time, resident memory, output size and free disk space.

Build once, then prepare a new directory from an explicit compatible baseline model:

```sh
./gradlew :training:installDist
python3 scripts/automata_experiments.py prepare --runtime training/build/install/training --baseline /absolute/path/baseline.json --output .aor-training/campaign-1 --profile overnight
python3 .aor-training/campaign-1/scripts/automata_campaign.py --spec .aor-training/campaign-1/campaign.json --output .aor-training/campaign-1/run
```

Preparation copies the executor, campaign scripts and model inputs, then fingerprints the frozen
files and declares distinct training, validation, development and confirmation seed banks. Keep
the prepared directory unchanged. Use `--profile smoke` for a small integration run before a long
campaign; its results do not establish playing strength. `prepare --help` lists resource and seed
options. Parallel campaigns need distinct output directories and seed reservations.

To include the territory exploration diagnostic, add all three preparation options:
`--territory-value-bundle`, `--frozen-card-purchase-bundle` and `--frozen-purchase-policy`.
The diagnostic compares the same territory model at exploration probabilities 0, .02 and .2,
holding its card/purchase continuation and rival population fixed. An initial constant-value model
provides a particularly interpretable comparison with ordinary greedy territory play. The explicit
native registry kind `territory-value-exploration-bundle` records its actual epsilon; ordinary
`territory-value-bundle` evaluation still requires zero. Without these inputs, preparation declares
the exploration diagnostic unavailable and runs the token-only campaign.

The token trials fit action-minus-baseline terminal returns from paired continuations, with a
predeclared screen before larger trials. Legacy replacement training disables the supplied baseline's
token-value head so the existing fitter can learn a replacement; whole-game development and
confirmation compare against the original supplied baseline, including its existing head. The token
pilot uses ordinary baseline card/purchase play, separately from the optional diagnostic's neural
continuation. Its results therefore do not establish improvement to the qualified neural
card/purchase combination. Validation and
development data may select a candidate; only the selected candidate reaches the reserved fresh
confirmation bank. Unpromising experiments are recorded as scientific rejections. Successful
execution, development improvement and independent confirmation are separate outcomes; none
updates browser assets or demonstrates human-level strength.

`--learning-mode correction` instead keeps the supplied incumbent intact throughout root collection,
continuations and comparison. The original token-value head selects its normal action first; a
separate optional `planning.tokenCorrection.*` head may override it only above the fixed 0.02
predicted-advantage margin. Absent or zero correction weights retain the exact incumbent action.
The correction profile uses a bounded screen followed by one larger trial; scaling requires
positive whole-game development reward and nonnegative mean win-share, as well as its paired
training/validation signal. Confirmation uses 256 fresh paired worlds. This learns token adjustments
and retains ordinary baseline card/purchase play.

The native `token-value-pilot` source accepts `learningMode: "CORRECTION"`; omitted mode preserves
legacy replacement behavior. Mode participates in collection/checkpoint identity and fitted model
metadata. Old origins without a mode remain replacement records and cannot be reused as correction
records. The feature dictionary and origin schema remain compatible; no saved-game or rules
upgrade is involved. Use the newly built executor when selecting this opt-in mode.

The [whole-game search adapter](../scripts/automata_policy_search.py) optimizes six correction
weights directly through complete games. This evaluates repeated corrections under the resulting
policy, whereas continuation labels evaluate one changed decision followed by incumbent play.
The search scores are uncalibrated; the same strict 0.02 override threshold applies. All incumbent
weights remain intact, and the zero-correction candidate must reproduce the incumbent's complete
gameplay results exactly on the screening bank.

```sh
python3 scripts/automata_policy_search.py prepare --from-campaign .aor-training/correction-campaign --output .aor-training/policy-search --seed 91378023 --workers 4
caffeinate -i python3 .aor-training/policy-search/scripts/automata_campaign.py --spec .aor-training/policy-search/campaign.json --output .aor-training/policy-search/run
```

Preparation verifies and copies a frozen correction campaign's executor and model inputs. The
declared search tests 32 candidates on 64 paired worlds, then four finalists on a separate bank of
256 worlds. Only a candidate with positive mean win-share and composite-reward differences on
that selection bank receives confirmation on 512 fresh worlds. Both confirmation lower 95%
bounds must exceed zero; selection intervals do not qualify a candidate. The incumbent remains
the fallback even when zero correction is absent from the finalists. `--profile smoke` runs a tiny
execution check that cannot qualify a candidate.

The default ceiling is four hours, including one hour reserved for confirmation. The finite search
stops earlier when its candidates are exhausted or its gate fails. It uses the same controller
resource limits, input hashes, status reports and bounded resume contract as the continuation
campaign. It does not start another search or activate a production model. Seed reservations cover
the source campaign and the new banks; callers must also avoid other historical experiments.

The [adaptive campaign](../scripts/automata_evolution.py) searches commodity timing, expansion,
leader timing, events and the existing token head around the current policy. It retains the
qualified purchase actor and its exact original prior throughout every seat's shipping and advance
decisions. This is a different environment from the ordinary-purchase token experiments above.
The purchase bundle's baseline and provenance must match the supplied frozen baseline.

```sh
python3 scripts/automata_evolution.py prepare --from-campaign .aor-training/correction-campaign --runtime training/build/install/training --purchase-bundle web/src/ai/purchaseModel.json --opponent /absolute/path/training-rival.json --robust-opponent /absolute/path/held-out-rival.json --output .aor-training/adaptive-search --seed 91379023 --workers 4
caffeinate -i python3 .aor-training/adaptive-search/scripts/automata_campaign.py --spec .aor-training/adaptive-search/campaign.json --output .aor-training/adaptive-search/run
```

Each round includes an unchanged anchor and up to twenty proposals, screened on 48 paired worlds;
four finalists receive 192 fresh selection worlds. Positive mean win-share and composite reward
permit a provisional parent update. Failure narrows the perturbations. Two consecutive unsuccessful
rounds or four total rounds end evolution. Exact unit gates remain discrete, untouched weights stay
fixed, and accepted parents are training decisions rather than qualified improvements.

One final candidate receives 1,024 fresh confirmation worlds against the original baseline, then
512 separate robustness worlds against held-out rivals. Both stages require positive lower 95%
bounds for win-share and composite reward. The default ceiling is twelve hours, split eight/two/two
across evolution, confirmation and robustness. The finite controller owns resource limits, resume
deadlines and immutable receipts; no language model participates in generation or selection.
Smoke runs cannot qualify a candidate. Freeze distinct training and robustness opponent models
before execution, and reserve all actual game seeds before opening any bank.
`--workers` defaults to four and accepts up to 32; the native executor additionally enforces its
machine-specific worker ceiling. Independent campaigns can use separate worker pools and disjoint
seed banks when aggregate CPU and memory capacity permits. A running campaign's resource settings
remain frozen with its inputs.

An accepted result is a candidate for human evaluation. It does not establish human-level strength
or activate browser assets. Its hybrid policy keeps the original purchase prior separate from the
new ordinary policy; a qualified browser adapter and parity checks are required before human play.

Use `--profile archive` with repeated `--seed-model PATH` arguments to compare saved checkpoints
instead of generating more mutations. This freezes one to sixteen distinct nonbaseline models,
adds the original baseline as the unchanged anchor, and runs one race: 512 screening worlds,
up to three finalists on 1,024 selection worlds, then 2,048 confirmation worlds and 1,024
held-out-opponent worlds for the single winner. All banks must be fresh. Archive ranking is
selection evidence, and the same independent improvement gates apply. This permits testing
earlier checkpoints without assuming that the latest provisional parent is the strongest.
`--profile archive-strong` uses the same frozen candidate race, 2,048 confirmation worlds and
4,096 held-out-opponent worlds, with a 99% lower-bound gate for both final metrics. Reports retain
the descriptive 95% intervals separately from the qualification bounds. Choose the profile and
reserve every bank before execution; it does not extend a completed or failed test's sample.

Detach the launch command with `nohup` and redirect its output as described above. On macOS,
`caffeinate -i` before the Python command keeps an otherwise idle machine awake. The local process
refreshes `run/campaign-report.txt` and `run/campaign-report.json` during execution and writes the
final outcome even when no candidate survives:

```sh
python3 .aor-training/campaign-1/scripts/automata_campaign.py --output .aor-training/campaign-1/run --status
python3 .aor-training/campaign-1/scripts/automata_campaign.py --spec .aor-training/campaign-1/campaign.json --output .aor-training/campaign-1/run --resume
```

SIGTERM the controller PID to stop its owned process group. Resume preserves the original campaign
and stage deadlines, completed results and declared attempt limits. It refuses changed inputs,
runtime or accepted result files. Expired budgets require a new campaign, not a renewed clock.
Do not launch a duplicate while an old child group is still alive. Resource checks are sampled,
and commands must keep their descendants in the owned process group. Independent game/root
checkpoints preserve completed work; an interrupted in-flight game may need recomputation.

The campaign stops when no eligible declared experiments remain, even if time is still available.
Duplicate candidate bytes reuse their development comparison, failed signal gates stop larger
recipes, and only one selected candidate receives fresh confirmation. The time ceiling is a
backstop; `--maximum-seconds` can extend it up to 72 hours without adding trials or renewing clocks.
Trial receipts report elapsed time, collected roots and continuation outcomes alongside measured
development effects. These are workload diagnostics, not a promise of strength gained per hour.

The controller also accepts declared command stages with structured result files; its module
documents that contract. This provides a finite, auditable search space for additional learning
recipes without putting a language model in the execution loop. Changing that search space or
adding a new algorithm remains a development task.

For automatic follow-up campaigns, the [series adapter](../scripts/automata_series.py) prepares
a finite queue of fresh-seed replications from an existing frozen campaign. It reserves disjoint
worlds across the queue and source campaign, reuses the exploration diagnostic only in the first
replication, and places all stages under one controller and one cumulative resource/time budget.
It stops at the first confirmation pass for review or when the queue is exhausted. This repeats
fixed recipes; it does not invent new methods or activate a candidate. Per-campaign confidence
intervals are not corrected for multiple campaigns, so a pass is not a series-wide strength claim.

```sh
python3 scripts/automata_series.py prepare --from-campaign .aor-training/campaign-1 --output .aor-training/series-1 --seed 91376423 --campaigns 3 --workers 4
caffeinate -i python3 .aor-training/series-1/scripts/automata_campaign.py --spec .aor-training/series-1/series.json --output .aor-training/series-1/run
```

Omit `caffeinate -i` on Linux. The default backstop is 24 hours; `--maximum-seconds` permits up to
72 hours. A declaration supports at most ten campaigns, with three by default. Use new output and
seed reservations for each series. `run/series-report.txt` summarizes completed campaigns;
`run/campaign-report.txt` includes live stage progress. The controller's `--status` and `--resume`
options work with `series.json` exactly as with a single campaign. Infrastructure failures stop
the series instead of consuming new campaign seeds as retries.

A series inherits its source campaign's learning mode. To change an old replacement campaign to
correction learning, pass `--learning-mode correction --runtime training/build/install/training`
to the series preparation command after rebuilding. The runtime override is required for that
upgrade so an older executor cannot silently interpret the new source as a replacement run.
The correction profile is selected automatically; mode, profile and chosen executor are frozen
before any games run.

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

Alternatively, `candidateModels: ["anchor.json", "proposal.json", ...]` supplies a fixed ordered
pool instead of `parameters`. Candidate zero must exactly equal the source `candidate`; the count
must match `candidates` when explicitly supplied. All model weights bind checkpoint identity.
This mode reuses the same incumbent games, ranking and candidate archives, supporting local
generation of centered perturbations and discrete controller alternatives.

Arena and strategy-search sources may include `frozenPurchase: {"baseline": "prior.json",
"policy": "purchase-policy.json"}` for the qualified four-player profile. Paths resolve relative
to the source. Every seat uses this raw purchase actor and frozen prior, regardless of its ordinary
model. Both files are hashed, archived and included in checkpoint identity. Omission preserves
legacy behavior and identity. The older `token-value-pilot` rejects this option; the
`token-order-collect` experiment below requires the shared controller throughout its prefix and tails.

Direct arenas may also supply `candidatePolicy` and `incumbentPolicy` inside `frozenPurchase`.
Each optional path replaces the actor only for the evaluated seat on that side; all rival seats
retain `policy`, and every seat retains the same purchase `baseline`. Omitted roles use `policy`.
Explicit roles have separate hashes and immutable snapshots, and canonical replay uses the same
side-specific actor. This permits comparing learned purchase actors with identical ordinary models.
Weight-only strategy search rejects these overrides because its cache identifies ordinary models.

For comparison with an ordinary opponent, direct arenas may instead declare
`ordinaryEvaluatedSides: ["INCUMBENT"]` inside `frozenPurchase` (or `"CANDIDATE"`, or both).
The evaluated seat on each named side uses its ordinary model for purchase decisions, including
its own prior; rivals retain the shared frozen actor and prior. A named ordinary side must omit
its actor override. These roles bind experiment identity and metadata and require explicit arena
sides. With a null ordinary `incumbent`, this compares against the built-in default opponent.

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

Optional `sharedPurchaseBaseline` supplies a separate prior model for every purchase controller,
including rivals. Ordinary decisions still use `baseline` and the respective `opponents` models.
This permits continuing a qualified purchase actor after improving ordinary strategy without changing
its recommendation input. A supplied prior must be a model path; its hash, immutable snapshot and
collection identity bind the training rows. Omission preserves the original per-seat prior behavior.

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
binds the exact purchase-prior weights, actor/encoder versions, rules, catalog and qualified setup. The
[selector](../ai/src/jsMain/kotlin/dev/aragos/aor/ai/PurchaseBotExports.kt) caches the decoded actor,
computes the ordinary recommendation and binds the purchase prior to the server's actor-safe
projection. An optional `ordinaryBaseline` binds a separately qualified ordinary model while
`baseline` remains the frozen purchase prior. Without that field, both models must match. Invalid
bindings disable the optional actor. Unsupported scopes and unavailable or incompatible actors retain
the ordinary recommendation. Preserve sampled selection and deterministic retries; changing either
model or using greedy inference requires qualification of the resulting combination. The asset keeps
the actor's original provenance and records separate hybrid qualification evidence.
The supported scope is HARD shipping and advance purchases in existing rules-4 four-player
standard-map games with No Crusades, the deck modification and immediate Master Art discard enabled,
and draw-three-keep-two disabled. New rules-5 games, other decisions and other setups use the
compatible baseline until separately qualified.

Freeze training budgets, checkpoint-selection rules and unused confirmation worlds before launch.
Compare complete games against both the unchanged initialization and the qualified baseline, then
test learned rival populations. When testing several seats or continuations in one world, keep them
together and compute uncertainty over independent worlds. Reports, checkpoints and generated model
files stay in isolated experiment storage; they do not activate a browser model automatically.
See [open learning work](follow-ups.md#autonomous-strategy-discovery) for broader strategy coverage.

### Purchase continuation campaigns

The [continuation runner](../scripts/automata_purchase_continuation.py) extends the qualified P32
purchase actor or an explicitly qualified purchase parent with its exact selected Adam state and
original PPO objective. A qualified ordinary model supplies all other decisions; every seat keeps
the original purchase prior, and rivals keep the initial incumbent purchase actor. The ordinary
source campaign must have passed independent confirmation and robustness for that ordinary model.

```sh
python3 scripts/automata_purchase_continuation.py prepare \
  --from-campaign /absolute/qualified-campaign \
  --baseline /absolute/qualified-campaign/run/selected/candidate.json \
  --prior /absolute/qualified-campaign/inputs/purchase-prior.json \
  --purchase-policy /absolute/original-purchase-run/update-32/policy.json \
  --optimizer-from /absolute/original-purchase-run/update-32/checkpoint.json \
  --update-config /absolute/original-purchase-run/update-32-config.json \
  --numerical-python /absolute/numerical-venv/bin/python \
  --runtime training/build/install/training --seed-audit /absolute/seed-audit.json \
  --seed 4000000 --workers 6 --output .aor-training/purchase-continuation
python3 .aor-training/purchase-continuation/scripts/automata_campaign.py \
  --spec .aor-training/purchase-continuation/campaign.json \
  --output .aor-training/purchase-continuation/run
```

For a later generation, additionally pass `--from-purchase-campaign /absolute/purchase-parent`,
set `--purchase-policy` to that parent's `run/selected/policy.json`, and set `--optimizer-from` to
the selected batch's `update/checkpoint.json`. Keep `--from-campaign` bound to the ordinary model's
qualification and use the original update config, not a generated batch config. Preparation verifies
the parent's completed selection and final gates, exact actor/Adam pair, ordinary model and purchase
prior, original objective and artifact hashes. Legacy archived rows are streamed and checked against
their original receipts without restoration. The incumbent, rival actor and ordinary environment
are frozen at preparation; later browser changes do not change them. Each generation is a new bounded
experiment with fresh physical and numerical seeds, not a resume of its parent's expired clocks.

Choose unused seeds for each preparation. The required historical audit reserves actual
`reservedSeeds`, conservative native `conservativelyExpandedOffsets` with
`conservativePairsPerOffset`, or both. Include earlier purchase, joint-learning and diagnostic runs;
the source campaign alone is insufficient. Preparation pins the audit and numerical environment.
Use `--profile smoke` for a small pipeline check that cannot qualify a policy.

The search profile caps training at sixteen batches of 256 games and compares fixed checkpoints on
one shared development bank. A collapse stops the recipe. At most one actor advances to fresh paired
confirmation and separate opponents, requiring positive 99% lower bounds for both win share and
composite reward against the unchanged qualified hybrid. Training and selection have four hours;
the final stages have two hours each within one eight-hour deadline. Resume preserves these clocks,
the actor/optimizer chain and all reserved worlds. Reports never activate a browser policy.

Completed batches retain their rows in a lossless archive before their receipt is published. The
[storage helper](../scripts/purchase_storage.py) verifies every raw row against the collector's
completion hashes and every compressed member before deleting raw copies. Batch receipts bind the
archive, manifest and unchanged metadata. An interrupted batch restores exact rows before resuming;
completed batches need no extraction. For manual inspection, run `purchase_storage.py restore
/absolute/run/batches/batch-NN`; run `archive` on the same batch afterward to return to its receipted
storage layout. Archives never overwrite conflicting evidence. The campaign keeps its four-GiB
output/RSS limits and two-GiB free-space reserve.

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

Fresh expansion Q training can explicitly opt into `aor-expansion-observation-2`. It adds
thirty public current-turn Cathedral usage bits, indexed by owner/opponent roles relative to
the acting player. Existing ownership features distinguish an unused Cathedral from a player
without Cathedral. This separates attack contexts where an authoritative Cathedral declaration
is still available from those where it has been spent; the dice-only attack probability remains
unchanged. It adds information, not a preferred action or a guaranteed playing-strength gain.

Export the complete dictionary without playing games, then initialize a fresh model:

```sh
training/build/install/training/bin/training --mode territory-value-schema --configuration players-4/standard/no-crusades/deck-true/art-true --encoder aor-expansion-observation-2 --output expansion-schema.json
python3 scripts/territory_value_trajectory.py initialize --schema expansion-schema.json --phase expansion --seed 74702 --output expansion-initial
```

The same exporter accepts `aor-token-observation-1` and `aor-expansion-observation-1`.
Use the resulting model's path and hash in the existing Q bundle. Its component encoder version
selects native projection, collection validation and Python preparation; a token-v1/expansion-v2
bundle is supported. V1 model/observation bytes, seeded choices and initialization remain exact.
V1 expansion records cannot be relabelled or filled with zero usage bits for v2 training, and
an optimizer carry must retain the exact preceding versioned model. Existing PPO, card, purchase
and F policies retain their original inputs. No game-state, saved-game or rules version changes,
model migration or live activation are involved.

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

`UpdateConfig.targetSteps` selects one or sixteen actual owned decisions, or the explicit
string `"terminal"`. One is the default.
The sixteen-step profile follows the recorded sequence across both phases, stops at the real
terminal row, and otherwise bootstraps from the recorded endpoint's legal offers. It counts
neither engine commands nor turns. The intervening actions come from the collection policy,
so these uncorrected targets can carry behavior-policy bias. Checkpoints retain the selected
horizon; carrying a model and optimizer into a different horizon is rejected. Stored replay
rows and terminal calibration retain their original one-step meaning.

The terminal profile assigns the complete game's recorded return to each sampled action and
does not bootstrap. It estimates returns under the recorded continuation policy; it does not
make those returns optimal-action labels. Comparing targets on the same recorded experience
requires an explicit offline experiment that separates the behavior actor from the fitted
actor. The ordinary update command still requires each new collection to use the preceding
published pair, including when terminal targets are selected.

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

The token-order experiment uses `--mode token-order-collect --source SOURCE --output PATH
--workers N --hours H`. Its source uses the arena contract with one screening configuration,
identical ordinary candidate/incumbent models, shared `frozenPurchase` prior/actor paths,
`trainingRoots`, `validationRoots`, and two `continuationReplicates`. One prospective epoch/ordinal
root per fresh game supplies paired full-tail terminal outcomes. `dataset.json` and the ordered
origin hashes in `finished.json` identify a complete bank; skips and interrupted games provide no
training labels. The [collector](../training/src/main/kotlin/dev/aragos/aor/training/TokenOrderCollection.kt)
owns the origin contract and routing.

Token observation version 2 adds public capital-choice priority and categorical signed amounts;
version 1 and the Purchase encoder remain unchanged. Collection and inference compare the actual
ordinary baseline, its offered integer neighbors, and the original six token proposals. Direct
arena sources can add `tokenOrder.candidateModel` and/or `tokenOrder.incumbentModel` paths to
version 2 action-value models. These roles replace only the evaluated seat's token decision,
retain the separate frozen Purchase prior, and require predicted advantage above 0.02. Replays
retain the same roles and hashed snapshots. Selection data does not establish strength;
independent complete-game confirmation is required before any client integration.

Native diagnostic sources can additionally set `tokenOrder.candidateSelector` and/or
`tokenOrder.incumbentSelector`. `MODEL` is the unchanged default. `ADJACENT_VETO` requires that side's
model and keeps its original choice only when it differs from the actual ordinary baseline by one;
a larger change retains the baseline without reselecting a neighbor. `FIXED_16_TO_15` must omit that
side's model and changes only an ordinary bid of 16 to an offered legal 15. Selector roles are bound
into experiment identity and metadata; model roles retain immutable snapshots. Token replay
decisions record `ordinaryTokenBaseline`, including the ordinary policy's token heads, separately
from immediate linear scores. The model-free evaluated `ORDINARY_MINUS_TWO` control uses the same
implementation as the rival diagnostic below: subtract two from the actual ordinary token-head
choice only when that exact command is offered. It supplies a descriptive comparison, never a
learned-model fallback or qualification. These controls do not change fitting or browser play.

An optional `tokenOrder.opponentSelectors` array assigns one role per entry of the ordinary
`opponents` pool, in that exact model-index order. Arena and replay map those indices through each
rotated lineup, excluding the evaluated seat. Entries are `ORDINARY`, `FIXED_16_TO_15`, or the
diagnostic `ORDINARY_MINUS_TWO` (actual ordinary token-head choice minus two, only if
that exact amount is offered). Every rival keeps its own ordinary baseline and the shared Purchase
actor/prior. Explicit arrays bind identity and metadata; omission preserves existing roles and
identity. Search and legacy root collection reject these arena-only roles.

Token collection may explicitly opt into `continuationProtocol: "ARENA_OVERLAYS_1"` with one
`expansionImitation.candidateModel` and optional per-opponent expansion models and token selectors.
The controlled seat keeps its actual ordinary token head throughout the prefix and every tail;
candidate/incumbent token roles and an active browser fixed-token gate on that ordinary model are
rejected. Rival selectors are limited to `ORDINARY` and `FIXED_16_TO_15`, mapped by original opponent
index through each lineup. All seats retain the shared Purchase actor/prior. Expansion snapshots
must preserve the ordinary snapshot's explicit serialized coefficient dictionary outside the eight
declared expansion coefficients, so offline validation does not reconstruct native prior defaults.
Omission preserves the original collector identity, metadata and behavior; the token encoder,
proposal set, loss and inference selector remain unchanged.

The opt-in source also predeclares `replayOrigins`, one through eight distinct indices across both
banks. Completed metadata binds the protocol, those indices, the actual ordinary token reference
snapshot/hash, all overlay snapshots and every prospective root request. Run
`--mode token-order-replay --source COMPLETED_COLLECTION --output NEW_PATH --hours H` with the same
frozen native runtime to reproduce exactly those completed origins, including skips. The separate
receipt compares every observation, baseline, proposal and paired continuation outcome plus exact
serialized hashes. It never mutates the bank or selects replacement roots. The ordinary source,
snapshot files and completed receipts must remain available and unchanged. Reproduction proves
determinism; it supplies no new independent worlds or policy qualification. The offline fitter
validates this explicit protocol and includes its metadata validator hash in new-protocol provenance;
legacy input and provenance behavior remain unchanged.

The separate [frozen-head ridge fitter](../scripts/token_order_ridge.py) accepts an explicitly pinned
original Q initializer through the collector's optional supplied-initializer path; omission retains
the existing neural fitter's initialization and behavior. It trains only the 64 output weights,
preserving the hidden network, dictionaries and output bias of 0.5. Paired hidden differences are
read through the existing `ActionValue` affine head. The loss retains the original equal-root mean
over **nonbaseline** proposals. Weighted RMS scaling uses only training differences, without
centering; exact zero columns export zero weights. In Float64, each of the five fixed penalties
`[0.0001, 0.001, 0.01, 0.1, 1.0]` minimizes weighted paired MSE plus the penalty times the squared
normalized output-weight norm. Export reverses the RMS scaling into the unchanged native Q format.

Run `token_order_ridge.py --inputs BINDINGS_JSON --config CONFIG_JSON --output NEW_DIRECTORY` only
after freezing the retained collection, original initializer raw/canonical hashes and prior fit
identity. The one-attempt allowance includes validation and fitting; no resume or random feature
regeneration is allowed. Previously inspected validation chooses the lowest MSE strictly better
than the initializer, preferring stronger regularization on exact ties. This is explicitly reused
model-selection data. The existing selector margin and scaling diagnostic remain unchanged; fresh
whole-game gates are still required. The unchanged proposals generally omit bid 14 around baseline
16, so this fit cannot learn that minus-two action there. New labels or proposal changes require a
separate experiment.


The native `expansion-opportunity-collect` diagnostic uses an arena-shaped `SCREENING` source
with `diagnosticRoots` (1–128, default 128), exactly two `continuationReplicates`, identical ordinary
candidate/incumbent models, and shared `frozenPurchase`. It rejects token and side-specific Purchase
overlays. Each prospective origin selects the first ordinary expansion pass with remaining public
pool tokens and another legal action in its predeclared epoch (`index % 3 + 1`). Missing roots are
recorded as skips without replacement. A fixed public sampler selects up to three distinct offered
action families. Baseline and alternatives share each origin's two future streams and unchanged
ordinary/Purchase policies. Outputs retain EXPANSION_2 observations, full canonical prefix/tail
replay evidence and paired terminal deltas grouped by origin; no best-arm labels, fitting or policy
qualification occurs. A weak mean across these sampled alternatives does not establish that passing
is optimal: this tests recoverable opportunity under the fixed proposal rule, not exhaustive search.
Use `--mode expansion-opportunity-collect --source JSON --output NEW_PATH
--hours H --workers N`; the native allowance is at most two hours and existing attempts cannot resume.

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
