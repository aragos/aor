package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** A frozen whole-game comparison; screening results must not double as final qualification. */
enum class ArenaPurpose {
    SCREENING,
    CONFIRMATION,
}

/** Explicit replacement-seat role, independent of whether its ordinary model equals its rival. */
enum class ArenaSide {
    CANDIDATE,
    INCUMBENT,
}

/**
 * Model paths in the source JSON are resolved relative to that JSON's directory. Null means prior.
 */
data class ArenaSpecification(
    val purpose: ArenaPurpose,
    val configurations: List<TrainingConfiguration>,
    val candidate: BotModel,
    val incumbent: BotModel,
    val opponents: List<BotModel>,
    val pairsPerConfiguration: Int,
    val seed: Int,
    val frozenPurchase: ArenaPurchasePolicy? = null,
) {
    init {
        require(
            configurations.isNotEmpty() && configurations.distinct().size == configurations.size
        ) {
            "An arena requires distinct supported configurations."
        }
        require(opponents.size in 1..16) {
            "An arena requires one through sixteen frozen opponents."
        }
        require(
            pairsPerConfiguration in 1..10000 &&
                pairsPerConfiguration * configurations.size <= 100000
        ) {
            "An arena requires 1–10000 pairs per configuration and at most 100000 pairs overall."
        }
        require(seed in 0..100_000_000) { "Arena seed must be between zero and 100000000." }
        frozenPurchase?.validate(configurations)
    }

    fun identity(): String =
        (listOf(
                purpose,
                configurations.map { it.id },
                pairsPerConfiguration,
                seed,
                candidate.weights.toSortedMap(),
                incumbent.weights.toSortedMap(),
                opponents.map { it.weights.toSortedMap() },
            ) + listOfNotNull(frozenPurchase?.identity()))
            .joinToString("\n")

    companion object {
        fun read(source: Path): ArenaSpecification {
            val node = ObjectMapper().readTree(source.toFile())
            require(integer(node, "schemaVersion") == 1) {
                "Arena source requires schemaVersion 1."
            }
            fun model(value: JsonNode): BotModel =
                if (value.isNull) TrainingArtifacts.readModel(null)
                else {
                    require(value.isTextual) {
                        "Arena model references must be paths or null for the prior."
                    }
                    TrainingArtifacts.readModel(
                        source.toAbsolutePath().parent.resolve(value.asText()).normalize()
                    )
                }
            val ids = node.required("configurations").map { it.asText() }
            val configurations = ids.map { id ->
                requireNotNull(TrainingMatrix.configurations.singleOrNull { it.id == id }) {
                    "Unsupported arena configuration: $id."
                }
            }
            return ArenaSpecification(
                purpose = ArenaPurpose.valueOf(node.required("purpose").asText()),
                configurations = configurations,
                candidate = model(node.required("candidate")),
                incumbent = model(node.required("incumbent")),
                opponents = node.required("opponents").map(::model),
                pairsPerConfiguration = integer(node, "pairsPerConfiguration"),
                seed = integer(node, "seed"),
                frozenPurchase =
                    node["frozenPurchase"]?.let { ArenaPurchasePolicy.read(source, it) },
            )
        }

        private fun integer(node: JsonNode, name: String): Int {
            val value = node[name]
            require(value != null && value.isIntegralNumber && value.canConvertToInt()) {
                "Arena $name must be a JSON integer within the signed 32-bit range."
            }
            return value.intValue()
        }
    }
}

/** Terminal results retain all seats so replacement-seat metrics can be independently audited. */
data class ArenaGame(
    val wins: List<Double>,
    val rewards: List<Double>,
    val ranks: List<Double>,
    val scores: List<PilotReplayScore>,
    val decisions: Int,
    val elapsedMillis: Long,
) {
    companion object {
        fun from(game: SimulatedGame): ArenaGame =
            ArenaGame(
                wins = game.scores.indices.map(game::winShare),
                rewards = game.scores.indices.map(game::reward),
                ranks = game.scores.indices.map(game::rankReward),
                scores = game.scores.map(PilotReplayScore::from),
                decisions = game.steps,
                elapsedMillis = game.elapsedMillis,
            )
    }
}

/** Shared setup/chance seed and opponent lineup; only one seat's model changes. */
data class ArenaPair(
    val index: Int,
    val configuration: String,
    val seed: Int,
    val seat: Int,
    val opponentIndices: List<Int>,
    val candidate: ArenaGame,
    val incumbent: ArenaGame,
)

object StrategyArena {
    /**
     * Separate negative seed range from pilot diagnostics and ordinary parameter exploration.
     * Purpose labels do not change this bank. Different offsets need not be disjoint: an offset
     * difference divisible by 8191 can reuse seeds at different indices. Consecutive offsets less
     * than 8191 apart are disjoint; reserve them before screening or confirmation begins.
     */
    fun seed(initial: Int, index: Int): Int {
        require(initial in 0..100_000_000 && index in 0..99999) {
            "Arena seed indices exceed their reserved range."
        }
        return -1_000_000_000 + initial + index * 8191
    }

    fun play(specification: ArenaSpecification, index: Int, deadlineNanos: Long): ArenaPair =
        pair(
            specification,
            index,
            game(specification, index, ArenaSide.CANDIDATE, deadlineNanos),
            game(specification, index, ArenaSide.INCUMBENT, deadlineNanos),
        )

    /** Play an explicit side, including its purchase actor, even when ordinary models are equal. */
    fun game(
        specification: ArenaSpecification,
        index: Int,
        side: ArenaSide,
        deadlineNanos: Long,
    ): ArenaGame =
        game(
            specification,
            index,
            when (side) {
                ArenaSide.CANDIDATE -> specification.candidate
                ArenaSide.INCUMBENT -> specification.incumbent
            },
            deadlineNanos,
            side,
        )

    /**
     * Model-search entry point; role-specific purchase actors require the explicit-side overload.
     */
    fun game(
        specification: ArenaSpecification,
        index: Int,
        model: BotModel,
        deadlineNanos: Long,
    ): ArenaGame = game(specification, index, model, deadlineNanos, side = null)

    private fun game(
        specification: ArenaSpecification,
        index: Int,
        model: BotModel,
        deadlineNanos: Long,
        side: ArenaSide?,
    ): ArenaGame {
        val lineup = lineup(specification, index)
        return ArenaGame.from(
            Simulation()
                .play(
                    configuration = lineup.configuration,
                    seed = lineup.seed,
                    competitors =
                        lineup.opponentIndices.mapIndexed { player, opponent ->
                            Competitor(
                                if (player == lineup.seat) model
                                else specification.opponents[opponent],
                                BotDifficulty.HARD,
                            )
                        },
                    deadlineNanos = deadlineNanos,
                    controllers =
                        specification.frozenPurchase
                            ?.controllers(lineup.configuration.playerCount, lineup.seat, side)
                            .orEmpty(),
                )
        )
    }

    /** Combine freshly simulated or checkpointed games without changing the arena's pairing. */
    fun pair(
        specification: ArenaSpecification,
        index: Int,
        candidate: ArenaGame,
        incumbent: ArenaGame,
    ): ArenaPair {
        val lineup = lineup(specification, index)
        return ArenaPair(
            index,
            lineup.configuration.id,
            lineup.seed,
            lineup.seat,
            lineup.opponentIndices,
            candidate,
            incumbent,
        )
    }

    internal data class Lineup(
        val configuration: TrainingConfiguration,
        val seed: Int,
        val seat: Int,
        val opponentIndices: List<Int>,
    )

    /** Rotate every controlled seat through each lineup before advancing the lineup cycle. */
    internal fun lineup(specification: ArenaSpecification, index: Int): Lineup {
        require(
            index in 0 until specification.configurations.size * specification.pairsPerConfiguration
        ) {
            "Arena game index must belong to its configured seed bank."
        }
        val configuration =
            specification.configurations[index / specification.pairsPerConfiguration]
        val repetition = index % specification.pairsPerConfiguration
        return Lineup(
            configuration = configuration,
            seed = seed(specification.seed, index),
            seat = repetition % configuration.playerCount,
            opponentIndices =
                List(configuration.playerCount) { player ->
                    (repetition / configuration.playerCount + player) % specification.opponents.size
                },
        )
    }

    fun report(pairs: List<JsonNode>): Map<String, Any> {
        require(pairs.isNotEmpty()) { "An arena report requires completed paired games." }
        fun values(model: String, metric: String): List<Double> = pairs.map { pair ->
            pair.required(model).required(metric)[pair.required("seat").intValue()].doubleValue()
        }
        val candidateWins = values("candidate", "wins")
        val incumbentWins = values("incumbent", "wins")
        return mapOf(
            "pairs" to pairs.size,
            "candidateWinShare" to candidateWins.average(),
            "incumbentWinShare" to incumbentWins.average(),
            "winShareDifference" to
                pilotInterval(candidateWins.zip(incumbentWins) { a, b -> a - b }),
            "rewardDifference" to
                pilotInterval(
                    values("candidate", "rewards").zip(values("incumbent", "rewards")) { a, b ->
                        a - b
                    }
                ),
            "candidateRank" to values("candidate", "ranks").average(),
            "incumbentRank" to values("incumbent", "ranks").average(),
            "gains" to candidateWins.zip(incumbentWins).count { (a, b) -> a > b },
            "losses" to candidateWins.zip(incumbentWins).count { (a, b) -> a < b },
        )
    }
}

/** Reusable, independently checkpointed arena. It never trains or publishes a model. */
object StrategyArenaMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for an arena." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                }
        ) {
            "Arena options may not repeat and must be mode, source, output, workers or hours."
        }
        require(fields["--mode"] == "arena") { "Arena mode must be explicit." }
        val source =
            Path.of(requireNotNull(fields["--source"]) { "An arena requires --source PATH." })
                .toRealPath()
        val output =
            Path.of(requireNotNull(fields["--output"]) { "An arena requires --output PATH." })
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) {
            "Arena output must not contain its source specification."
        }
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val workerCount = fields["--workers"]?.toInt() ?: ((cpus * .8).toInt() - 2).coerceAtLeast(1)
        val hours = fields["--hours"]?.toDouble() ?: 3.0
        require(hours.isFinite() && hours > 0 && hours <= 72) {
            "Arena hours must be positive and at most 72."
        }
        val specification = ArenaSpecification.read(source)
        val signature = trainingSignature("arena-1:${specification.identity()}")
        val artifacts = TrainingArtifacts(output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == "strategy-arena" &&
                    previous["signature"]?.asText() == signature
        ) {
            "Arena code, models or settings changed; use a new output directory."
        }
        if (previous == null) {
            requireFreshOutput(output)
            artifacts.write(
                "state.json",
                mapOf(
                    "kind" to "strategy-arena",
                    "signature" to signature,
                    "phase" to "initialized",
                ),
            )
        }
        artifacts.ensureModelSnapshot(specification.candidate, "candidate.json")
        artifacts.ensureModelSnapshot(specification.incumbent, "incumbent.json")
        specification.opponents.forEachIndexed { index, model ->
            artifacts.ensureModelSnapshot(model, "opponent-$index.json")
        }
        specification.frozenPurchase?.snapshot(artifacts)
        val startPath = output.resolve("started-at.txt")
        val started =
            if (Files.exists(startPath)) {
                Instant.parse(Files.readString(startPath).trim())
            } else {
                Instant.now().also { Files.writeString(startPath, it.toString()) }
            }
        val remaining =
            minOf(
                Duration.between(Instant.now(), started.plus(Duration.ofHours(72))).toNanos(),
                (hours * 3_600_000_000_000).toLong(),
            )
        require(remaining > 0) { "The arena has exhausted its three-day allowance." }
        val deadline = System.nanoTime() + remaining
        val total = specification.configurations.size * specification.pairsPerConfiguration
        artifacts.write(
            "run.json",
            specification.frozenPurchase?.metadata().orEmpty() +
                mapOf(
                    "kind" to "strategy-arena",
                    "signature" to signature,
                    "purpose" to specification.purpose.name,
                    "source" to source.toString(),
                    "configurations" to specification.configurations.map { it.id },
                    "pairs" to total,
                    "seed" to specification.seed,
                    "workers" to workerCount,
                    "opponentModels" to specification.opponents.size,
                    "difficulty" to "HARD",
                    "startedAt" to started.toString(),
                    "promoted" to false,
                ),
        )
        var completed = 0
        val pairs =
            Tournaments(workerCount, deadline).use { workers ->
                workers.run(
                    List(total) { index ->
                        {
                            val name = "pair-$index.json"
                            artifacts.read(name)
                                ?: run {
                                    artifacts.write(
                                        name,
                                        StrategyArena.play(specification, index, deadline),
                                    )
                                    checkNotNull(artifacts.read(name)) {
                                        "Completed arena pairs must be readable."
                                    }
                                }
                        }
                    }
                ) {
                    completed++
                    if (completed % 8 == 0 || completed == total)
                        println("Arena $completed/$total paired games")
                }
            }
        artifacts.write(
            "evaluation.json",
            specification.frozenPurchase?.metadata().orEmpty() +
                mapOf(
                    "complete" to true,
                    "promoted" to false,
                    "humanStrengthVerified" to false,
                    "purpose" to specification.purpose.name,
                    "overall" to StrategyArena.report(pairs),
                    "byConfiguration" to
                        pairs
                            .groupBy { it.required("configuration").asText() }
                            .mapValues { (_, group) -> StrategyArena.report(group) },
                    "intervalMethod" to "Normal approximation across independent paired game seeds",
                ),
        )
        artifacts.write(
            "state.json",
            mapOf("kind" to "strategy-arena", "signature" to signature, "phase" to "complete"),
        )
        artifacts.write("finished.json", mapOf("at" to Instant.now().toString()))
        println("Arena complete: ${StrategyArena.report(pairs)}")
    }

    /** Unidentified checkpoints must never be treated as results from a newly initialized arena. */
    internal fun requireFreshOutput(output: Path) {
        Files.list(output).use { paths ->
            require(
                paths.allMatch {
                    it.fileName.toString() in
                        setOf("supervisor.json", ".supervisor.lock", "trainer.log")
                }
            ) {
                "Arena output already contains artifacts from another run."
            }
        }
    }
}
