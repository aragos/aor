package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/** Resource-bounded, resumable offline training; it never starts a production service. */
fun main(arguments: Array<String>) {
    if (arguments.contentEquals(arrayOf("--help"))) {
        println(
            """
            AoR standalone offline opponent trainer (JDK 21+).
            Use train-opponents.py for enforced CPU, memory and elapsed-time limits.
            --mode coverage|train|evaluate  --output PATH  --hours HOURS (maximum 72)
            --workers N  --configurations 1..128  --seeds N  --seed N
            --rounds N  --batch N  --model PATH  --difficulty EASY|NORMAL|HARD
            Reuse a train output directory to resume its original model and completed rounds.
            Outputs: trainer.log, coverage.json, checkpoint.json, candidate.json, model.json,
            evaluation.json, state.json and append-only games.jsonl/rounds.jsonl.
            """
                .trimIndent()
        )
        return
    }
    val options = TrainingOptions.parse(arguments.toList())
    val artifacts = TrainingArtifacts(options.output)
    val startedAt = runStart(options.output)
    val maximumDeadline = startedAt.plus(Duration.ofHours(72))
    val requestedDeadline = Instant.now().plusMillis((options.hours * 3_600_000).toLong())
    val deadline = minOf(maximumDeadline, requestedDeadline)
    require(deadline.isAfter(Instant.now())) { "This run has exhausted its three-day allowance." }
    val deadlineNanos = System.nanoTime() + Duration.between(Instant.now(), deadline).toNanos()
    val ledger = CoverageLedger()
    val initial = TrainingArtifacts.readModel(options.model)
    val configurations = TrainingMatrix.stratified.take(options.configurations)
    artifacts.write(
        "run.json",
        mapOf(
            "startedAt" to startedAt.toString(),
            "deadline" to deadline.toString(),
            "mode" to options.mode,
            "workers" to options.workers,
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "requiredConfigurations" to TrainingMatrix.configurations.size,
            "requestedConfigurations" to configurations.size,
            "seedsPerConfiguration" to options.seeds,
        ),
    )
    val completed: (SimulatedGame) -> Unit = { game ->
        ledger.add(game)
        artifacts.append("games.jsonl", game)
    }
    Tournaments(options.workers, deadlineNanos).use { tournaments ->
        when (options.mode) {
            "coverage" -> {
                DecisionProbes(configurations, ledger)
                tournaments.sweep(
                    configurations = configurations,
                    seedsPerConfiguration = options.seeds,
                    seedOffset = options.seed,
                    model = initial,
                    difficulty = options.difficulty,
                ) { game ->
                    completed(game)
                    artifacts.write("coverage.json", ledger.report())
                    println(
                        "Completed ${game.configuration} seed=${game.seed} " +
                            "decisions=${game.steps} elapsedMs=${game.elapsedMillis}"
                    )
                }
                artifacts.model(initial)
            }
            "evaluate" -> {
                val comparison =
                    tournaments.compare(
                        candidate = initial,
                        incumbent = BotModels.default,
                        configurations = configurations,
                        seedsPerConfiguration = options.seeds,
                        seedOffset = options.seed,
                        heldOut = true,
                        completed = completed,
                    )
                artifacts.write("evaluation.json", comparison)
                println("Evaluation: $comparison")
            }
            "train" -> train(options, initial, tournaments, artifacts, ledger, completed)
            else -> error("Unknown training mode ${options.mode}.")
        }
    }
    artifacts.write("coverage.json", ledger.report())
    artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
}

private fun train(
    options: TrainingOptions,
    initial: BotModel,
    tournaments: Tournaments,
    artifacts: TrainingArtifacts,
    ledger: CoverageLedger,
    completed: (SimulatedGame) -> Unit,
) {
    val signature =
        trainingSignature(
            "${options.seed}:${options.configurations}:${options.seeds}:${options.batch}"
        )
    val restored = artifacts.read("state.json")
    var state =
        if (restored != null) {
            TrainingCheckpoint.restore(restored, signature, ledger)
        } else {
            TrainingCheckpoint(signature, initial, initial, listOf(initial, BotModels.default))
        }
    if (options.model != null) {
        require(initial == state.initial) {
            "A resumed run must retain its original baseline model."
        }
    }
    state.save(artifacts, ledger)
    println("Validating rare-decision curriculum across ${options.configurations} configurations.")
    val probes = DecisionProbes(TrainingMatrix.stratified.take(options.configurations), ledger)
    if (!state.warmupComplete) {
        println("Covering ${options.configurations} configurations before parameter exploration.")
        tournaments.sweep(
            configurations = TrainingMatrix.stratified.take(options.configurations),
            seedsPerConfiguration = options.seeds,
            seedOffset = options.seed,
            model = state.initial,
            difficulty = BotDifficulty.NORMAL,
            skip = { configuration, seed -> ledger.contains(configuration.id, seed) },
        ) { game ->
            completed(game)
            artifacts.write("coverage.json", ledger.report())
            state.save(artifacts, ledger)
            println(
                "Coverage ${game.configuration} seed=${game.seed} elapsedMs=${game.elapsedMillis}"
            )
        }
        state = state.copy(warmupComplete = true)
        state.save(artifacts, ledger)
    }
    var champion = state.champion
    var championRegret = probes.regret(champion)
    val population = state.population.toMutableList()
    artifacts.model(champion, "checkpoint.json")
    for (round in state.nextRound until options.rounds) {
        // Leave time for an independent held-out evaluation instead of spending the whole budget.
        if (System.nanoTime() + Duration.ofMinutes(10).toNanos() >= tournaments.deadlineNanos) {
            break
        }
        val candidate =
            ModelExploration.propose(
                incumbent = champion,
                features = ledger.featureNames,
                random = Random(options.seed xor (round * 65537)),
                scale = if (round % 5 == 0) 0.35 else 0.12,
            )
        val matrix = TrainingMatrix.stratified
        val configurations =
            (0 until minOf(options.batch, options.configurations)).map { index ->
                matrix[(round * options.batch + index) % options.configurations]
            }
        val comparison =
            tournaments.compare(
                candidate = candidate,
                incumbent = champion,
                configurations = configurations,
                seedsPerConfiguration = 1,
                seedOffset = options.seed + 100_000_000 + round * 200003,
                opponentPool = population.toList(),
                completed = completed,
            )
        val candidateRegret = probes.regret(candidate)
        val accepted =
            comparison.rewardDifference > 0.015 && candidateRegret <= championRegret + 0.5
        if (accepted) {
            champion = candidate
            championRegret = candidateRegret
            population.add(candidate)
            if (population.size > 8) population.removeAt(2)
            artifacts.model(champion, "checkpoint.json")
        }
        artifacts.append(
            "rounds.jsonl",
            mapOf(
                "round" to round,
                "accepted" to accepted,
                "comparison" to comparison,
                "featureWeights" to candidate.weights.size,
                "directedRegret" to candidateRegret,
            ),
        )
        artifacts.write("coverage.json", ledger.report())
        state =
            state.copy(champion = champion, population = population.toList(), nextRound = round + 1)
        state.save(artifacts, ledger)
        println("Round $round accepted=$accepted $comparison")
    }
    artifacts.model(champion, "candidate.json")
    // These seeds never participate in parameter selection. Do not tune against this result.
    val heldOut =
        tournaments.compare(
            candidate = champion,
            incumbent = state.initial,
            configurations = TrainingMatrix.stratified.take(options.configurations),
            seedsPerConfiguration = maxOf(2, options.seeds),
            seedOffset = options.seed + 1_000_000_000,
            opponentPool = population.take(2),
            heldOut = true,
            completed = completed,
        )
    val completeCoverage =
        options.configurations == TrainingMatrix.configurations.size &&
            ledger.missingDecisionTypes.isEmpty() &&
            ledger.missingCards.isEmpty()
    val promoted = completeCoverage && heldOut.lower95 > 0.0
    artifacts.write(
        "evaluation.json",
        mapOf(
            "promoted" to promoted,
            "comparison" to heldOut,
            "criterion" to
                "All 128 configurations, cards and decision types; paired 95% lower reward bound above zero",
            "missingDecisionTypes" to ledger.missingDecisionTypes.sorted(),
            "missingCards" to ledger.missingCards.sorted(),
        ),
    )
    artifacts.model(if (promoted) champion else state.initial)
    println("Held-out evaluation promoted=$promoted $heldOut")
}

/** A resumed output directory retains its original three-day deadline. */
private fun runStart(output: Path): Instant {
    val path = output.resolve("started-at.txt")
    if (Files.exists(path)) return Instant.parse(Files.readString(path).trim())
    val now = Instant.now()
    Files.writeString(path, now.toString(), java.nio.file.StandardOpenOption.CREATE_NEW)
    return now
}

private data class TrainingOptions(
    val mode: String,
    val output: Path,
    val model: Path?,
    val workers: Int,
    val hours: Double,
    val configurations: Int,
    val seeds: Int,
    val seed: Int,
    val rounds: Int,
    val batch: Int,
    val difficulty: BotDifficulty,
) {
    companion object {
        fun parse(arguments: List<String>): TrainingOptions {
            require(arguments.size % 2 == 0) { "Use --option value pairs for training arguments." }
            val fields = arguments.chunked(2).associate { it[0] to it[1] }
            require(fields.size == arguments.size / 2) { "Training options may not repeat." }
            val allowed =
                setOf(
                    "--mode",
                    "--output",
                    "--model",
                    "--workers",
                    "--hours",
                    "--configurations",
                    "--seeds",
                    "--seed",
                    "--rounds",
                    "--batch",
                    "--difficulty",
                )
            require(fields.keys.all(allowed::contains)) { "Unknown training option." }
            val cpus =
                System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                    ?: Runtime.getRuntime().availableProcessors()
            val options =
                TrainingOptions(
                    mode = fields["--mode"] ?: "coverage",
                    output = Path.of(fields["--output"] ?: ".aor-training/run"),
                    model = fields["--model"]?.let(Path::of),
                    workers =
                        fields["--workers"]?.toInt() ?: ((cpus * 0.8).toInt() - 2).coerceAtLeast(1),
                    hours = fields["--hours"]?.toDouble() ?: 12.0,
                    configurations = fields["--configurations"]?.toInt() ?: 128,
                    seeds = fields["--seeds"]?.toInt() ?: 1,
                    seed = fields["--seed"]?.toInt() ?: 19790531,
                    rounds = fields["--rounds"]?.toInt() ?: 96,
                    batch = fields["--batch"]?.toInt() ?: 16,
                    difficulty = BotDifficulty.valueOf(fields["--difficulty"] ?: "NORMAL"),
                )
            require(options.mode in setOf("coverage", "train", "evaluate")) {
                "Mode must be coverage, train, or evaluate."
            }
            require(options.hours.isFinite() && options.hours > 0.0 && options.hours <= 72.0) {
                "A training invocation may run for more than zero and at most 72 hours."
            }
            require(options.configurations in 1..128) { "Use between 1 and 128 configurations." }
            require(options.seeds in 1..1000) { "Use between 1 and 1000 seeds per configuration." }
            require(options.rounds in 0..100000) { "Use between 0 and 100000 exploration rounds." }
            require(options.batch in 1..128) { "Use between 1 and 128 configurations per round." }
            require(options.seed in 0..100_000_000) { "The initial seed must be 0–100000000." }
            return options
        }
    }
}
