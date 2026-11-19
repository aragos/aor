package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.OPENING_HAND_FEATURE_PREFIX

/** Fit only setup returns using the exact continuation of each offered card. */
internal object OpeningHandTraining {
    private const val RETURNS = 3
    private const val REGULARIZATION = 0.025

    private data class Outcome(val reward: Double, val winShare: Double)

    private data class World(
        val configuration: TrainingConfiguration,
        val gameSeed: Int,
        val observation: BotObservation,
        val policySeed: Int,
        val baseline: Int,
        val outcomes: List<Outcome>,
    )

    private data class EvaluatedWorld(
        val configuration: TrainingConfiguration,
        val changed: Boolean,
        val rewardEffect: Double,
        val winEffect: Double,
    )

    private data class Comparison(
        val worlds: Int,
        val changedChoices: Int,
        val rewardEffect: PairedEffect,
        val winEffect: PairedEffect,
    )

    fun run(
        tournaments: Tournaments,
        configurations: List<TrainingConfiguration>,
        seeds: Int,
        seedOffset: Int,
        incumbent: BotModel,
        artifacts: TrainingArtifacts,
        ledger: CoverageLedger,
    ) {
        val setups = configurations.filter { it.rules.drawThreeKeepTwo }
        require(setups.isNotEmpty()) {
            "Opening-hand training requires Draw Three Keep Two setups."
        }
        println("Collecting each legal opening-card return in ${setups.size} setups.")
        val training = collect(tournaments, setups, seeds, seedOffset, false, incumbent, ledger)
        val features =
            training
                .flatMap { world -> world.observation.candidates.flatMap { it.features.keys } }
                .toSortedSet()
                .filter { it.startsWith(OPENING_HAND_FEATURE_PREFIX) }
        val examples = training.flatMap { world ->
            val options = world.observation.candidates
            val reference = options[world.baseline].features
            world.outcomes.mapIndexedNotNull { index, outcome ->
                if (index == world.baseline) return@mapIndexedNotNull null
                val values = options[index].features
                TokenValueExample(
                    features.map { feature ->
                        (values[feature] ?: 0.0) - (reference[feature] ?: 0.0)
                    },
                    outcome.reward - world.outcomes[world.baseline].reward,
                )
            }
        }
        val fitted = TokenValueRidge.fit(examples, REGULARIZATION, tournaments.deadlineNanos)
        val weights = features.zip(fitted).toMap()
        val model = incumbent.copy(weights = incumbent.weights + weights)
        artifacts.model(model, "candidate.json")
        artifacts.write(
            "opening-hand-fit.json",
            mapOf(
                "trainingWorlds" to training.size,
                "configurations" to setups.map(TrainingConfiguration::id),
                "regularization" to REGULARIZATION,
                "weights" to weights,
            ),
        )
        println("Evaluating the frozen opening-card model on independent paired games.")
        val holdout =
            collect(tournaments, setups, seeds, seedOffset, true, incumbent, ledger).map {
                pairedResult(it, model)
            }
        val comparison = compare(holdout)
        val qualified = comparison.rewardEffect.lower95 > 0 && comparison.winEffect.mean > 0
        artifacts.write(
            "evaluation.json",
            mapOf(
                "qualified" to qualified,
                "comparison" to comparison,
                "byPlayerCount" to
                    holdout
                        .groupBy { it.configuration.playerCount }
                        .toSortedMap()
                        .mapValues { compare(it.value) },
                "byConfiguration" to
                    holdout
                        .groupBy { it.configuration.id }
                        .toSortedMap()
                        .mapValues { compare(it.value) },
            ),
        )
        println("Opening-hand held-out qualification passed=$qualified $comparison")
    }

    private fun collect(
        tournaments: Tournaments,
        configurations: List<TrainingConfiguration>,
        seeds: Int,
        seedOffset: Int,
        heldOut: Boolean,
        incumbent: BotModel,
        ledger: CoverageLedger,
    ): List<World> =
        tournaments
            .run(
                configurations.flatMapIndexed { index, configuration ->
                    (0 until seeds).map { repetition ->
                        {
                            playReturns(
                                configuration,
                                gameSeed = tournamentSeed(seedOffset, index, repetition, heldOut),
                                seat = (index + repetition) % configuration.playerCount,
                                incumbent,
                                tournaments.deadlineNanos,
                                ledger,
                            )
                        }
                    }
                }
            )
            .sortedWith(compareBy({ it.configuration.id }, World::gameSeed))

    private fun playReturns(
        configuration: TrainingConfiguration,
        gameSeed: Int,
        seat: Int,
        incumbent: BotModel,
        deadline: Long,
        ledger: CoverageLedger,
    ): World {
        var root: SimulationDecision? = null
        val competitors =
            List(configuration.playerCount) { actor ->
                val difficulty =
                    if (seat == actor || (gameSeed + actor) % 3 == 0) BotDifficulty.HARD
                    else BotDifficulty.NORMAL
                Competitor(incumbent, difficulty)
            }
        val outcomes =
            List(RETURNS) { index ->
                val controller =
                    object : SimulationController {
                        override fun controls(decision: String): Boolean = decision == "return-card"

                        override fun choose(decision: SimulationDecision): String {
                            val previous = root
                            check(
                                previous == null ||
                                    previous.observation == decision.observation &&
                                        previous.seed == decision.seed
                            ) {
                                "Each opening return must branch from the same original private hand."
                            }
                            if (previous == null) root = decision
                            val options = decision.observation.candidates
                            check(options.size == RETURNS) {
                                "An opening hand requires exactly three legal card returns."
                            }
                            return options[index].command
                        }
                    }
                val result =
                    Simulation()
                        .play(
                            configuration = configuration,
                            seed = gameSeed,
                            competitors = competitors,
                            deadlineNanos = deadline,
                            controllers = mapOf(seat to controller),
                        )
                synchronized(ledger) { ledger.add(result) }
                Outcome(result.reward(seat), result.winShare(seat))
            }
        val decision = checkNotNull(root) { "A Draw Three game must offer an opening return." }
        return World(
            configuration,
            gameSeed,
            decision.observation,
            decision.seed,
            choose(decision.observation, decision.seed, incumbent),
            outcomes,
        )
    }

    private fun pairedResult(world: World, model: BotModel): EvaluatedWorld {
        val chosen = choose(world.observation, world.policySeed, model)
        val actual = world.outcomes[chosen]
        val reference = world.outcomes[world.baseline]
        return EvaluatedWorld(
            world.configuration,
            changed = chosen != world.baseline,
            rewardEffect = actual.reward - reference.reward,
            winEffect = actual.winShare - reference.winShare,
        )
    }

    private fun compare(worlds: List<EvaluatedWorld>): Comparison =
        Comparison(
            worlds.size,
            worlds.count(EvaluatedWorld::changed),
            pairedEffect(worlds.map(EvaluatedWorld::rewardEffect)),
            pairedEffect(worlds.map(EvaluatedWorld::winEffect)),
        )

    private fun choose(observation: BotObservation, seed: Int, model: BotModel): Int {
        val command = BotPolicy.choose(observation, BotDifficulty.HARD, seed, model)
        return observation.candidates
            .indexOfFirst { it.command == command }
            .also {
                check(it >= 0) {
                    "Opening inference must return one of its exactly evaluated cards."
                }
            }
    }
}
