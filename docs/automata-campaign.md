# Current-rules Hard campaign

The campaign compares browser-compatible Hard opponents in full games under the rules for new
tables. Job specifications call the November 20 controller `shipped` and the November 21 starting
incumbent `market`. They share the November 20 assets. The November 21 controller adds the market
and initiative settings qualified separately for three, four, five and six players. Existing
tables retain their saved controller version.

## Prepare and run

Build the JVM simulator from the feature checkout. The Mac coordinator needs Node, JDK 21 or
later, Python, SSH and rsync. Callisto needs Java, Python and its
existing CUDA PyTorch environment. The prepared bundle pins the evaluator, assets, scripts,
browser comparator and starting policies by SHA-256. For optimized discovery, pass the frozen
release qualification bundle as the reference. Its original JVM evaluator runs every sealed final
and its browser and JVM selectors check the exported models. Preparation stages both evaluators. The
seven-day clock starts when the coordinator first enters `run`.

```sh
./gradlew :training:installDist
python3 scripts/automata-campaign/campaign.py prepare --market 3 4 5 6 \
  --reference-bundle ../current-rules-automata-playtest/.aor-training/seven-day-current-rules/bundle \
  --output .aor-training/seven-day-current-rules
```

A fresh standalone pilot can omit the reference after also building
`:server:installDist` and `:ai:jsBrowserProductionLibraryDistribution`. It then uses its own evaluator
for every stage.

The prepared package can run independently of subsequent builds and source edits. To start or
resume this specific campaign, from the same checkout use:

```sh
caffeinate -i python3 .aor-training/seven-day-current-rules/bundle/campaign.py run --output .aor-training/seven-day-current-rules
```

A `pilot` in a separate output directory runs collection, fitting, selection and export across all four
counts on a small set of deals. Its seeds are separate from those used in the full campaign.
Override the worker address or PyTorch interpreters with `--callisto`, `--gpu-python` and
`--cpu-python`. Confirming different starting policies requires another output directory.

## Games, workers and selection

The Mac runs six simulation workers and Callisto ten, with a 6 GiB JVM heap per host. Each host
takes the next available shard. The Mac keeps 10 GiB of free disk and Callisto 20 GiB.

The coordinator fits a twelve-input expansion scorer from matched terminal continuations. It tries
local CPU PyTorch first and uses the RTX 4090 on failure. Use `--fit-first cuda` to reverse the
order. Validation scores the legal alternatives from many origins in one batch, keeping the
strict adoption threshold and tie order. Game execution and online inference run on CPUs.

For each table size, challengers vary market-marker income, commodity opportunity and initiative
together. On alternate generations the learner samples at least 4,096 originating worlds, then
compares up to three legal alternatives to the ordinary expansion. It attempts four matched
continuations for each choice at the sampled position. Each origin has one sampled hidden world.
The simulator retains the immutable game state at the selected expansion. Each continuation uses
the same future chance seed and absolute decision index as a replay from setup. All alternatives
and continuations for an origin stay in the same partition. Some games never present
an eligible alternative. A correction enters whole-game screening only if it improves held-out
terminal win share. The trained coefficients fit in a versioned browser model.

Each generation screens joint parameter proposals against the current champion on at least 512
paired worlds, then confirms up to two on at least 4,096 fresh pairs. Each pair changes only the
focal seat's policy. The two development rosters use shipped Hard or an adopting policy. The
coordinator freezes a count after six generations without an advance. Discovery ends at six days
or 4.4 million games. The entire run has seven days and a five-million-game cap, including terminal
rollouts. Failed attempts reserve their worst-case game count.

After selection, a frozen finalist faces shipped Hard and any distinct starting incumbent on at
least 16,384 paired worlds per comparator. Four equally weighted rosters include mixed tables and
an archived base policy. The matrix covers both maps and every supported opening across 248 setups.
Seeds for qualification, training, confirmation, rollout fitting and final evaluation occupy
separate ranges. Tied winners split one win. Record rank difference and the weakest setup alongside
the stratified paired win-share interval.

Admission requires a gain of at least one percentage point over shipped Hard with a positive
one-sided 98.75% lower bound. A distinct starting incumbent requires a positive two-sided 95%
lower bound. The 95% lower bounds must exceed −2.5 points against shipped rivals, −4.5 per roster
and −5 per opening. The exported asset must fit 150,000 bytes. The production JS selector must
agree with the JVM on both maps, finish the games, and have p95 decision time within 10% of control.
A failed gate retains the starting checkpoint for that count.

The output directory contains atomic compressed shard receipts, budget and attempt totals in
`state.json`, screen and fit results under `rounds/`, frozen finalists, and per-count comparisons
and export hashes under `final/`. The coordinator retries an unavailable shard on the other
machine. Restart using the same directory to reuse completed work. Final exports stay local until a
separate release.
