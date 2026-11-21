package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.CheckpointPolicy
import dev.aragos.aor.ai.ExpansionResponse
import dev.aragos.aor.ai.MarketProfile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/** Full games using the browser checkpoint, the current GameHeader and every supported opening. */
internal object CheckpointBank {
    private val assets =
        mapOf(
            3 to
                ("three-player-continuation-model.json" to
                    "7446542a3e85f3db8b31d0fda632ae0d4f1ff809188b68cfda5899841ff57624"),
            4 to
                ("continuation-model.json" to
                    "956b39695f744ce835a72af6452e33d061b74479a8b13938e3b968f18ce4556e"),
            6 to
                ("broad-model.json" to
                    "a479db4b3c7f580ce9979049fe407cbde4db22bf75b76d3cc54ac6f2039ce500"),
        )
    private val setups =
        TrainingMatrix.configurations.flatMap { single ->
            listOf(false, true).mapNotNull { draw ->
                single.copy(rules = single.rules.copy(drawThreeKeepTwo = draw)).takeIf {
                    it.rules.supportsInitialDeal(it.playerCount)
                }
            }
        }

    private data class World(
        val setup: TrainingConfiguration,
        val seed: Int,
        val seat: Int,
        val roster: String,
    ) {
        val configuration =
            setup.id + if (setup.rules.drawThreeKeepTwo) "/draw-three-keep-two" else ""
    }

    /** Qualification uses the two pure rosters. Two extra compositions are sealed for the final. */
    private fun worlds(count: Int, first: Int, repetitions: Int, final: Boolean): List<World> {
        require(first >= 0 && repetitions in 1..128 && first + repetitions <= 1_000_000) {
            "A bank needs a bounded repetition range."
        }
        val rosters =
            if (final) listOf("shipped", "market", "mixed", "prior")
            else listOf("shipped", "market")
        return setups
            .withIndex()
            .filter { it.value.playerCount == count }
            .flatMap { (index, setup) ->
                (first until first + repetitions).flatMap { repetition ->
                    rosters.mapIndexed { rival, roster ->
                        val series = repetition + (rival / 2) * 1_000_000
                        val key = series * 512 + index * 2 + rival % 2
                        val seed = (key * 1664525 + 1013904223) or Int.MIN_VALUE
                        World(setup, seed, (index + repetition + rival) % count, roster)
                    }
                }
            }
    }

    private data class PairScore(
        val seed: Int,
        val configuration: String,
        val seat: Int,
        val roster: String,
        val control: Double,
        val candidate: Double,
        val rankDifference: Double,
    )

    private data class RolloutLabel(val inputs: List<Double>, val gain: Double)

    private data class Origin(
        val seed: Int,
        val configuration: String,
        val roster: String,
        val games: Int,
        val alternatives: List<RolloutLabel>,
    )

    fun run(args: List<String>) {
        require(args.size == 4) {
            "checkpoint-bank requires the model directory, output directory, JSON job specification and worker count."
        }
        val spec = ObjectMapper().readTree(Path.of(args[2]).toFile())
        val count = spec.required("count").intValue()
        require(count in 3..6) { "A checkpoint bank requires three through six seats." }
        val (name, digest) =
            checkNotNull(assets[if (count == 5) 4 else count]) { "The table needs a checkpoint." }
        val path = Path.of(args[0], name)
        check(
            HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))) ==
                digest
        ) {
            "The model must match the published browser checkpoint."
        }
        val model = BotModels.fromSerializedWeights(TrainingArtifacts.readModel(path).weights)
        val shipped = Competitor(model, checkpoint = CheckpointPolicy(model, count))
        val market = Competitor(model, checkpoint = CheckpointPolicy(model, count, true))
        fun competitor(node: JsonNode): Competitor {
            if (node.isTextual)
                return when (node.textValue()) {
                    "shipped" -> shipped
                    "market" -> market
                    else -> error("Unknown checkpoint identity.")
                }
            val profile =
                MarketProfile(
                    node.required("markerScale").doubleValue(),
                    node.required("commodityScale").doubleValue(),
                    node.required("earlyShift").doubleValue(),
                    node.required("expansion").map { it.doubleValue() },
                )
            return Competitor(model, checkpoint = CheckpointPolicy(model, count, true, profile))
        }
        val control = competitor(spec.required("control"))
        val candidate = competitor(spec.required("candidate"))
        val adoption = spec["adopter"]?.let(::competitor) ?: market
        val allWorlds =
            worlds(
                count,
                spec.required("first").intValue(),
                spec.required("repeats").intValue(),
                spec.path("final").asBoolean(),
            )
        val origins =
            if (spec.path("pilot").asBoolean()) {
                val twoSetups = allWorlds.map { it.configuration }.distinct().take(2).toSet()
                allWorlds.filter { it.configuration in twoSetups }
            } else allWorlds
        val deadline =
            System.nanoTime() +
                TimeUnit.SECONDS.toNanos(spec.path("seconds").asLong(43200).coerceIn(1, 43200))
        val futures = spec.path("futures").asInt(0)
        require(futures in 0..4) { "Use at most four matched continuations per expansion choice." }
        val jobs = origins.map { world ->
            {
                fun play(focal: Competitor, rollout: RolloutDecision? = null): SimulatedGame {
                    val seats =
                        List(count) { seat ->
                            when {
                                seat == world.seat -> focal
                                world.roster == "market" -> adoption
                                world.roster == "mixed" && (seat + world.seat) % 2 == 0 -> market
                                world.roster == "prior" && (seat + world.seat) % 2 == 1 ->
                                    Competitor(model, dev.aragos.aor.ai.BotDifficulty.HARD)
                                else -> shipped
                            }
                        }
                    return Simulation()
                        .play(
                            world.setup,
                            world.seed,
                            seats,
                            deadline,
                            collectCoverage = false,
                            rollout = rollout,
                        )
                }
                if (futures > 0)
                    collect(world, futures) { branch -> play(control, branch).winShare(world.seat) }
                else {
                    val before = play(control)
                    val after = play(candidate)
                    PairScore(
                        world.seed,
                        world.configuration,
                        world.seat,
                        world.roster,
                        before.winShare(world.seat),
                        after.winShare(world.seat),
                        after.rankReward(world.seat) - before.rankReward(world.seat),
                    )
                }
            }
        }
        val artifacts = TrainingArtifacts(Path.of(args[1]))
        val rows = Tournaments(args[3].toInt(), deadline).use { pool -> pool.run(jobs) }
        artifacts.write("rows.json", rows)
    }

    private fun collect(world: World, futures: Int, play: (RolloutDecision) -> Double): Origin {
        val target = (world.seed ushr 8) % 12
        var alternatives = emptyList<ExpansionResponse.Alternative>()
        var continuation: ExpansionContinuation? = null
        var ordinaryCommand = ""
        var visited = 0
        fun futureSeed(future: Int): Int = world.seed xor (future + 1) * 1103515245
        val baseline =
            play(
                RolloutDecision { seat, observation, ordinary ->
                    if (
                        seat != world.seat ||
                            observation.decision != "expand" ||
                            visited++ != target
                    )
                        return@RolloutDecision null
                    alternatives = ExpansionResponse.alternatives(observation, ordinary)
                    ordinaryCommand = ordinary
                    BranchMove(ordinary, futureSeed(0)) { continuation = it }
                }
            )
        val gains = DoubleArray(alternatives.size)
        var games = 1
        if (alternatives.isNotEmpty()) {
            val origin = checkNotNull(continuation) { "Expansion alternatives require an origin." }
            fun score(command: String, seed: Int): Double {
                games++
                return origin.play(command, seed).winShare(world.seat)
            }
            for (future in 0 until futures) {
                val seed = futureSeed(future)
                val before = if (future == 0) baseline else score(ordinaryCommand, seed)
                for ((index, alternative) in alternatives.withIndex()) {
                    gains[index] += score(alternative.command, seed) - before
                }
            }
        }
        return Origin(
            world.seed,
            world.configuration,
            world.roster,
            games,
            alternatives.mapIndexed { i, alternative ->
                RolloutLabel(alternative.inputs, gains[i] / futures)
            },
        )
    }
}
