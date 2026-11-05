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
            --workers N  --profile full|strategic-four-player  --configurations N  --seeds N  --seed N
            --rounds N  --batch N  --model PATH  --difficulty EASY|NORMAL|HARD
            The strategic-four-player profile uses the standard map, War/Timber swap,
            no Crusades in the initial deal and immediate Master Art discard.
            --configurations limits the chosen profile for smoke checks; truncated profiles
            cannot promote a model. --batch is the minimum number of paired games per round.
            Reuse a train output directory with the same profile to resume completed rounds.
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
    val ledger = CoverageLedger(options.profile)
    val initial = TrainingArtifacts.readModel(options.model)
    val configurations = options.configurations
    artifacts.write(
        "run.json",
        mapOf(
            "startedAt" to startedAt.toString(),
            "deadline" to deadline.toString(),
            "mode" to options.mode,
            "workers" to options.workers,
            "maxHeapBytes" to Runtime.getRuntime().maxMemory(),
            "profile" to options.profile.commandValue,
            "requiredConfigurations" to options.profile.configurations.size,
            "configurationIds" to configurations.map(TrainingConfiguration::id),
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
    val configurations = options.configurations
    val signature = trainingSignature(options.signatureSettings)
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
    println("Validating rare-decision curriculum across ${configurations.size} configurations.")
    val probes = DecisionProbes(configurations, ledger)
    if (!state.warmupComplete) {
        println("Covering ${configurations.size} configurations before parameter exploration.")
        tournaments.sweep(
            configurations = configurations,
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
        // Reserve measured simulation work for final evaluation, including worker contention.
        val reserveMillis =
            evaluationReserveMillis(
                ledger.averageGameMillis,
                configurations.size * options.evaluationSeeds +
                    minOf(options.batch, configurations.size) * options.roundSeeds,
                tournaments.workers,
            )
        if (
            System.nanoTime() + Duration.ofMillis(reserveMillis).toNanos() >=
                tournaments.deadlineNanos
        ) {
            break
        }
        val candidate =
            ModelExploration.propose(
                incumbent = champion,
                features = ledger.featureNames,
                random = Random(options.seed xor (round * 65537)),
                scale = if (round % 5 == 0) 0.35 else 0.12,
            )
        val roundConfigurations = options.roundConfigurations(round)
        val comparison =
            tournaments.compare(
                candidate = candidate,
                incumbent = champion,
                configurations = roundConfigurations,
                seedsPerConfiguration = options.roundSeeds,
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
    val requiredMillis =
        evaluationReserveMillis(
            ledger.averageGameMillis,
            configurations.size * options.evaluationSeeds,
            tournaments.workers,
        )
    val remainingMillis = (tournaments.deadlineNanos - System.nanoTime()) / 1_000_000
    if (remainingMillis < requiredMillis) {
        artifacts.write(
            "evaluation.json",
            mapOf(
                "promoted" to false,
                "evaluationComplete" to false,
                "profile" to options.profile.commandValue,
                "configurationIds" to configurations.map(TrainingConfiguration::id),
                "reason" to "Insufficient remaining time for independent held-out evaluation.",
                "estimatedRequiredMillis" to requiredMillis,
                "remainingMillis" to remainingMillis,
            ),
        )
        artifacts.model(state.initial)
        println(
            "Held-out evaluation deferred; candidate and checkpoints retained without promotion."
        )
        return
    }
    // These seeds never participate in parameter selection. Do not tune against this result.
    val heldOut =
        tournaments.compare(
            candidate = champion,
            incumbent = state.initial,
            configurations = configurations,
            seedsPerConfiguration = options.evaluationSeeds,
            seedOffset = options.seed + 1_000_000_000,
            opponentPool =
                (listOf(state.initial, BotModels.default, champion) + population).distinct(),
            heldOut = true,
            completed = completed,
        )
    val promoted = ledger.complete && heldOut.lower95 > 0.0
    artifacts.write(
        "evaluation.json",
        mapOf(
            "promoted" to promoted,
            "evaluationComplete" to true,
            "profile" to options.profile.commandValue,
            "configurationIds" to configurations.map(TrainingConfiguration::id),
            "opponentModels" to
                (listOf(state.initial, BotModels.default, champion) + population).distinct().size,
            "comparison" to heldOut,
            "criterion" to
                "All ${options.profile.configurations.size} ${options.profile.commandValue} configurations, " +
                    "cards and decision types; paired 95% lower reward bound above zero",
            "missingConfigurations" to ledger.missingConfigurations.sorted(),
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

internal data class TrainingOptions(
    val mode: String,
    val output: Path,
    val model: Path?,
    val workers: Int,
    val hours: Double,
    val profile: TrainingProfile,
    val configurationLimit: Int?,
    val seeds: Int,
    val seed: Int,
    val rounds: Int,
    val batch: Int,
    val difficulty: BotDifficulty,
) {
    val configurations: List<TrainingConfiguration>
        get() = profile.configurations.take(configurationLimit ?: profile.configurations.size)

    /** Repeated deals retain the pair budget when one profile contains few configurations. */
    val roundSeeds: Int
        get() = (batch + configurations.size - 1) / configurations.size

    /** Match the full matrix's minimum 256 held-out pairs within the chosen experimental scope. */
    val evaluationSeeds: Int
        get() = maxOf(seeds, 2, (256 + configurations.size - 1) / configurations.size)

    val signatureSettings: String
        get() =
            listOf(
                    profile.commandValue,
                    configurations.joinToString(",", transform = TrainingConfiguration::id),
                    seed,
                    seeds,
                    batch,
                )
                .joinToString(":")

    fun roundConfigurations(round: Int): List<TrainingConfiguration> =
        (0 until minOf(batch, configurations.size)).map { index ->
            configurations[((round.toLong() * batch + index) % configurations.size).toInt()]
        }

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
                    "--profile",
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
                    profile = TrainingProfile.fromCommand(fields["--profile"] ?: "full"),
                    configurationLimit = fields["--configurations"]?.toInt(),
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
            require(
                options.configurationLimit == null ||
                    options.configurationLimit in 1..options.profile.configurations.size
            ) {
                "Use between 1 and ${options.profile.configurations.size} configurations for ${options.profile.commandValue}."
            }
            require(options.seeds in 1..1000) { "Use between 1 and 1000 seeds per configuration." }
            require(options.rounds in 0..100000) { "Use between 0 and 100000 exploration rounds." }
            require(options.batch in 1..128) { "Use between 1 and 128 paired games per round." }
            require(options.seed in 0..100_000_000) { "The initial seed must be 0–100000000." }
            return options
        }
    }
}

/** Minimum ten-minute allowance, otherwise measured paired-game work plus a 50% safety margin. */
internal fun evaluationReserveMillis(averageGameMillis: Double, pairs: Int, workers: Int): Long =
    maxOf(
        Duration.ofMinutes(10).toMillis(),
        (averageGameMillis * pairs * 2 / workers * 1.5).toLong(),
    )
