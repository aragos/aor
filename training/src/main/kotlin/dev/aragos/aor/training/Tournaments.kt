package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt
import kotlin.random.Random

/** A confidence interval over paired games, with the controlled seat rotated across seeds. */
data class ModelComparison(
    val pairs: Int,
    val rewardDifference: Double,
    val lower95: Double,
    val upper95: Double,
    val candidateWinShare: Double,
    val incumbentWinShare: Double,
)

/**
 * Bounded offline game workers. Reserve two of the permitted CPU slots for GC and coordination.
 * [deadlineNanos] is checked both between jobs and at every simulated player decision.
 */
class Tournaments(val workers: Int, val deadlineNanos: Long) : AutoCloseable {
    init {
        val machineCpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val maxWorkers = ((machineCpus * 0.8).toInt() - 2).coerceAtLeast(1)
        require(workers in 1..maxWorkers) {
            "Use 1–$maxWorkers training workers to stay within 80% of $machineCpus CPUs."
        }
    }

    private val executor = Executors.newFixedThreadPool(workers)

    fun <T> run(jobs: List<() -> T>, completed: (T) -> Unit = {}): List<T> {
        check(System.nanoTime() < deadlineNanos) { "The training deadline has elapsed." }
        val completion = ExecutorCompletionService<T>(executor)
        val futures = jobs.map { job -> completion.submit(Callable { job() }) }
        return try {
            buildList {
                repeat(jobs.size) {
                    val remaining = (deadlineNanos - System.nanoTime()).coerceAtLeast(0)
                    val future =
                        checkNotNull(completion.poll(remaining, TimeUnit.NANOSECONDS)) {
                            "The training deadline elapsed before this batch completed."
                        }
                    val result = future.get()
                    completed(result)
                    add(result)
                }
            }
        } catch (failure: Exception) {
            futures.forEach { it.cancel(true) }
            throw failure
        }
    }

    fun sweep(
        configurations: List<TrainingConfiguration>,
        seedsPerConfiguration: Int,
        seedOffset: Int,
        model: BotModel,
        difficulty: BotDifficulty,
        skip: (TrainingConfiguration, Int) -> Boolean = { _, _ -> false },
        completed: (SimulatedGame) -> Unit,
    ): List<SimulatedGame> =
        run(
            configurations.flatMapIndexed { index, configuration ->
                (0 until seedsPerConfiguration).mapNotNull { repetition ->
                    val seed = seedOffset + index * 104729 + repetition * 8191
                    if (skip(configuration, seed)) return@mapNotNull null
                    {
                        Simulation()
                            .play(
                                configuration = configuration,
                                seed = seed,
                                competitors =
                                    List(configuration.playerCount) {
                                        Competitor(model, difficulty)
                                    },
                                deadlineNanos = deadlineNanos,
                            )
                    }
                }
            },
            completed,
        )

    fun compare(
        candidate: BotModel,
        incumbent: BotModel,
        configurations: List<TrainingConfiguration>,
        seedsPerConfiguration: Int,
        seedOffset: Int,
        opponentPool: List<BotModel> = listOf(BotModels.default, incumbent),
        heldOut: Boolean = false,
        completed: (SimulatedGame) -> Unit = {},
    ): ModelComparison {
        require(opponentPool.isNotEmpty()) { "A tournament requires an opponent population." }
        data class PairResult(
            val difference: Double,
            val candidateWins: Double,
            val incumbentWins: Double,
        )
        val pairs =
            run(
                configurations.flatMapIndexed { index, configuration ->
                    (0 until seedsPerConfiguration).map { repetition ->
                        {
                            val seed = tournamentSeed(seedOffset, index, repetition, heldOut)
                            val controlledSeat = (index + repetition) % configuration.playerCount
                            val opponents =
                                List(configuration.playerCount) { seat ->
                                    Competitor(
                                        opponentPool[
                                            (index + repetition + seat) % opponentPool.size],
                                        if ((index + seat) % 3 == 0) BotDifficulty.HARD
                                        else BotDifficulty.NORMAL,
                                    )
                                }
                            fun play(model: BotModel): SimulatedGame =
                                Simulation()
                                    .play(
                                        configuration = configuration,
                                        seed = seed,
                                        competitors =
                                            opponents.mapIndexed { seat, opponent ->
                                                if (seat == controlledSeat)
                                                    Competitor(model, BotDifficulty.HARD)
                                                else opponent
                                            },
                                        deadlineNanos = deadlineNanos,
                                    )
                            val candidateGame = play(candidate)
                            val incumbentGame = play(incumbent)
                            synchronized(completed) {
                                completed(candidateGame)
                                completed(incumbentGame)
                            }
                            PairResult(
                                candidateGame.reward(controlledSeat) -
                                    incumbentGame.reward(controlledSeat),
                                candidateGame.winShare(controlledSeat),
                                incumbentGame.winShare(controlledSeat),
                            )
                        }
                    }
                }
            )
        val mean = pairs.map(PairResult::difference).average()
        val variance =
            if (pairs.size <= 1) 0.0
            else {
                pairs.sumOf { (it.difference - mean) * (it.difference - mean) } / (pairs.size - 1)
            }
        val margin = 1.96 * sqrt(variance / pairs.size)
        return ModelComparison(
            pairs = pairs.size,
            rewardDifference = mean,
            lower95 = mean - margin,
            upper95 = mean + margin,
            candidateWinShare = pairs.map(PairResult::candidateWins).average(),
            incumbentWinShare = pairs.map(PairResult::incumbentWins).average(),
        )
    }

    override fun close() {
        executor.shutdownNow()
        executor.awaitTermination(30, TimeUnit.SECONDS)
    }
}

/** Reserve a disjoint positive seed range for final evaluation, including after integer wrap. */
internal fun tournamentSeed(offset: Int, index: Int, repetition: Int, heldOut: Boolean): Int {
    val lowBits = (offset + index * 104729 + repetition * 8191) and 0x3fffffff
    return if (heldOut) lowBits or 0x40000000 else lowBits
}

/**
 * Population-based parameter exploration preserves the rules and observation boundary. Parameter
 * proposals include count/variant interactions found during the coverage sweep, initially zero.
 */
object ModelExploration {
    fun propose(
        incumbent: BotModel,
        features: Set<String>,
        random: Random,
        scale: Double,
    ): BotModel {
        require(scale > 0.0 && scale.isFinite()) { "Mutation scale must be finite and positive." }
        val featurePool =
            (features + incumbent.weights.keys).filterNot { it.endsWith("chaos") }.sorted()
        val selected = featurePool.shuffled(random).take(maxOf(4, featurePool.size / 12)).toSet()
        val weights =
            (incumbent.weights.keys + selected).associateWith { feature ->
                val prior = incumbent.weights[feature] ?: 0.0
                if (feature !in selected) {
                    prior
                } else {
                    val baseFeature = feature.substringAfterLast('.')
                    val magnitude =
                        kotlin.math
                            .abs(BotModels.default.weights[baseFeature] ?: 1.0)
                            .coerceIn(0.05, 20.0)
                    val delta =
                        (random.nextDouble() + random.nextDouble() + random.nextDouble() - 1.5)
                    (prior + delta * magnitude * scale).coerceIn(-100.0, 100.0)
                }
            }
        return BotModel(weights = weights)
    }
}
