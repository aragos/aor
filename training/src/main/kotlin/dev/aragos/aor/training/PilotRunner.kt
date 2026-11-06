package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import java.util.concurrent.CancellationException
import kotlin.math.sqrt

/**
 * Per-game timing, terminal results and plan counts; no opponent hands or deck order are exported.
 */
data class PilotGame(
    val rewards: List<Double>,
    val wins: List<Double>,
    val ranks: List<Double>,
    val decisions: Int,
    val elapsedMillis: Long,
    val transitionNanos: Long,
    val observationNanos: Long,
    val viewNanos: Long,
    val choicesNanos: Long,
    val featuresNanos: Long,
    val policyNanos: Long,
    val plans: Int,
    val planSelectionsByStrategy: Map<String, Int>,
)

/** A confidence interval over independent paired game seeds, never over individual decisions. */
data class PilotInterval(
    val count: Int,
    val mean: Double,
    val lower95: Double?,
    val upper95: Double?,
)

internal fun pilotInterval(values: List<Double>): PilotInterval {
    require(values.isNotEmpty() && values.all(Double::isFinite)) {
        "An interval requires finite paired results."
    }
    val mean = values.average()
    if (values.size < 2) return PilotInterval(values.size, mean, null, null)
    val variance = values.sumOf { (it - mean) * (it - mean) } / (values.size - 1)
    val margin = 1.96 * sqrt(variance / values.size)
    return PilotInterval(values.size, mean, mean - margin, mean + margin)
}

/**
 * Each completed unit is atomic and reusable after interruption; unfinished units are recomputed.
 */
class PilotRunner(
    private val options: PilotOptions,
    private val artifacts: TrainingArtifacts,
    private val baseline: BotModel,
    private val deadlineNanos: Long,
    private val workers: Tournaments,
) {
    private fun cached(name: String, compute: () -> Any): JsonNode {
        artifacts.read(name)?.let {
            return it
        }
        checkDeadline()
        artifacts.write(name, compute())
        return checkNotNull(artifacts.read(name)) { "A completed pilot unit must be readable." }
    }

    private fun checkDeadline() {
        if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
            throw CancellationException(
                "The pilot reached its deadline or was canceled; completed units are retained."
            )
        }
    }

    /**
     * Evaluate a fixed plan from a permitted sampled world using the pilot's unchanged policies.
     */
    fun continuation(
        scenario: CommodityExperimentScenario,
        seed: Int,
        strategy: CommodityExperimentStrategy,
    ): PilotGame {
        checkDeadline()
        val session =
            ExperimentSession(CommodityExperiment.sampledState(scenario, seed), seed.toLong())
        session.resolve(deadlineNanos)
        return play(
            session,
            seed,
            scenario.state.playerIds.indexOf(scenario.actor),
            PolicyKind.BASELINE,
            null,
            scenario.plan(strategy),
        )
    }

    /**
     * Record an explanatory trace without changing policy inputs, then replay canonical entries
     * against the same sampled fixture to verify every recorded transition.
     */
    fun replay(
        scenario: CommodityExperimentScenario,
        seed: Int,
        strategy: CommodityExperimentStrategy,
    ): PilotReplay {
        checkDeadline()
        val initial = CommodityExperiment.sampledState(scenario, seed)
        val session = ExperimentSession(initial, seed.toLong(), recordEntries = true)
        session.resolve(deadlineNanos)
        val decisions = mutableListOf<PilotReplayDecision>()
        val game =
            play(
                session,
                seed,
                initial.playerIds.indexOf(scenario.actor),
                PolicyKind.BASELINE,
                null,
                scenario.plan(strategy),
            ) { number, view, observation, command ->
                decisions += PilotReplayDecision.from(number, view, observation.decision, command)
            }
        val entries = session.entries.map { it.toText() }
        val replayed =
            entries.fold(initial) { state, entry ->
                checkDeadline()
                state.step(TextCodec.parse(entry))
            }
        check(replayed == session.state) {
            "Canonical diagnostic entries must reproduce the terminal state."
        }
        return PilotReplay(
            scenarioId = scenario.id,
            scenarioSeed = scenario.seed,
            seed = seed,
            strategy = strategy,
            actor = scenario.actor.value,
            commodity = scenario.commodity.name,
            game = game,
            entries = entries,
            decisions = decisions.toList(),
            finalScores = session.state.publicView().scores.map(PilotReplayScore::from),
            replayVerified = true,
        )
    }

    private fun CommodityExperimentScenario.plan(
        strategy: CommodityExperimentStrategy
    ): PilotPlan? =
        if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
            null
        } else {
            PilotPlan(strategy, commodity, cardReference, startTurn)
        }

    fun benchmark() {
        if (artifacts.read("benchmark.json") != null) return
        // Warm up each executor worker before measuring the shared benchmark workload.
        workers.run(
            List(options.workers) { index ->
                {
                    runGame(options.seed + index, PolicyKind.BASELINE, null)
                }
            }
        )
        val started = System.nanoTime()
        val games =
            workers.run(
                (0 until options.benchmarkGames).map { index ->
                    {
                        runGame(options.seed + 100 + index, PolicyKind.BASELINE, null)
                    }
                }
            )
        val wallSeconds = (System.nanoTime() - started) / 1e9
        artifacts.write(
            "benchmark.json",
            mapOf(
                "games" to games,
                "wallSeconds" to wallSeconds,
                "gamesPerHour" to games.size * 3600 / wallSeconds,
                "decisionsPerSecond" to games.sumOf { it.decisions } / wallSeconds,
                "workers" to options.workers,
                "note" to
                    "Warmed full-game workload; timings include ordinary engine semantics. No optimization is assumed.",
            ),
        )
        println(
            "Benchmark: ${games.size} games, ${games.sumOf { it.decisions }} decisions, ${wallSeconds.toInt()}s"
        )
    }

    fun teach(): List<JsonNode> {
        var completed = 0
        return workers
            .run(
                (0 until options.positions).map { index ->
                    {
                        cached("position-$index.json") { teachPosition(index) }
                    }
                }
            ) {
                completed++
                println("Teacher position $completed/${options.positions}")
            }
            .sortedBy { it.required("index").intValue() }
            .also { rows ->
                artifacts.write(
                    "teacher.json",
                    mapOf(
                        "curriculumVersion" to CommodityExperiment.VERSION,
                        "featureNames" to CommodityExperiment.featureNames,
                        "strategies" to CommodityExperimentStrategy.entries.map { it.name },
                        "positions" to rows.size,
                        "samplesPerStrategy" to options.samples,
                        "scenarioTags" to
                            rows.groupingBy { it.required("tag").asText() }.eachCount(),
                        "scope" to
                            "Established-position fixtures with publicly empty opponent hands and a known remaining card multiset; only deck order/chance sampled. Not a posterior sampler for arbitrary games.",
                        "labels" to
                            "Mean terminal 0.8 win share + 0.2 rank across each strategy's full-game continuations",
                    ),
                )
            }
    }

    private fun teachPosition(index: Int): Any {
        val scenario = CommodityExperiment.scenario(options.seed + index * 8191)
        val view = scenario.state.viewForDecision(scenario.actor)
        val features = CommodityExperiment.features(view, scenario.actor, scenario.commodity)
        val controlled = scenario.state.playerIds.indexOf(scenario.actor)
        fun continuation(sample: Int, strategy: CommodityExperimentStrategy?): PilotGame {
            checkDeadline()
            val sampleSeed = options.seed xor (index * 65537 + sample * 104729)
            val initial = CommodityExperiment.sampledState(scenario, sampleSeed)
            val session = ExperimentSession(initial, sampleSeed.toLong())
            session.resolve(deadlineNanos)
            val plan = strategy?.let {
                PilotPlan(it, scenario.commodity, scenario.cardReference, scenario.startTurn)
            }
            return play(session, sampleSeed, controlled, PolicyKind.BASELINE, null, plan)
        }
        val baselineGames = (0 until options.samples).map { continuation(it, null) }
        val baselineRewards = baselineGames.map { it.rewards[controlled] }
        val strategyResults =
            CommodityExperimentStrategy.entries.map { strategy ->
                val games =
                    if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
                        baselineGames
                    } else {
                        (0 until options.samples).map { sample ->
                            continuation(sample, strategy)
                        }
                    }
                val seat = scenario.state.playerIds.indexOf(scenario.actor)
                val rewards = games.map { it.rewards[seat] }
                mapOf(
                    "strategy" to strategy.name,
                    "rewards" to rewards,
                    "reward" to pilotInterval(rewards),
                    "winShare" to games.map { it.wins[seat] }.average(),
                    "decisions" to games.sumOf { it.decisions },
                )
            }
        return mapOf(
            "index" to index,
            "seed" to scenario.seed,
            "id" to scenario.id,
            "tag" to scenario.tag,
            "features" to features,
            "strategies" to strategyResults,
            "baselineRewards" to baselineRewards,
            "baselineReward" to pilotInterval(baselineRewards),
            "heuristicStrategy" to
                CommodityExperiment.heuristic(view, scenario.actor, scenario.commodity).ordinal,
            "split" to if (index % 5 == 0) "validation" else "train",
            "note" to
                "Recreate root using curriculum version and seed; each sample shares initial shuffle across strategies but later event streams may diverge.",
        )
    }

    fun fit(rows: List<JsonNode>): PilotStudent {
        artifacts.read("student.json")?.let {
            return PilotStudent.fromJson(it.required("network"))
        }
        fun example(row: JsonNode) =
            PilotExample(
                row.required("features").map { it.doubleValue() },
                row.required("strategies").map {
                    it.required("reward").required("mean").doubleValue()
                },
            )
        val training = rows.filter { it.required("split").asText() == "train" }
        require(training.isNotEmpty()) {
            "Use at least two positions to fit a student with a separate validation position."
        }
        val student =
            PilotStudent.fit(training.map(::example), options.seed, deadlineNanos = deadlineNanos)
        val validationRows = rows.filter { it.required("split").asText() == "validation" }
        val validation = validationRows.map(::example)
        val regrets = validation.map { row ->
            row.values.max() - row.values[student.choose(row.features)]
        }
        val differences = validationRows.map { row ->
            val data = example(row)
            data.values[student.choose(data.features)] -
                row.required("baselineReward").required("mean").doubleValue()
        }
        artifacts.write(
            "student.json",
            mapOf(
                "schemaVersion" to 2,
                "curriculumVersion" to CommodityExperiment.VERSION,
                "featureNames" to CommodityExperiment.featureNames,
                "strategies" to CommodityExperimentStrategy.entries.map { it.name },
                "network" to student,
                "minimumPredictedAdvantage" to PilotStudent.DEFAULT_MINIMUM_ADVANTAGE,
                "trainingPositions" to training.size,
                "validationPositions" to validation.size,
                "validationMeanEstimatedRegret" to regrets.average(),
                "validationRewardDifferenceFromBaseline" to pilotInterval(differences),
                "scope" to
                    "Experimental four-plan selector with baseline fallback; not a production BotModel asset",
            ),
        )
        println(
            "Student: ${training.size} training positions, ${validation.size} validation positions"
        )
        return student
    }

    fun validate(student: PilotStudent, rows: List<JsonNode>): PilotValidationReport {
        val heldOut = rows.filter { it.required("split").asText() == "validation" }
        var completed = 0
        val outcomes =
            workers.run(
                heldOut.map { row ->
                    {
                        val index = row.required("index").intValue()
                        val result =
                            cached("validation-$index.json") {
                                validatePosition(student, row.required("seed").intValue(), index)
                            }
                        PilotValidationOutcome(
                            scenarioSeed = result.required("scenarioSeed").intValue(),
                            tag = result.required("tag").asText(),
                            baselineRewards =
                                result.required("baselineRewards").map { it.doubleValue() },
                            studentRewards =
                                result.required("studentRewards").map { it.doubleValue() },
                            strategy = result.required("strategy").asText(),
                        )
                    }
                }
            ) {
                completed++
                println("Validation $completed/${heldOut.size} held-out positions")
            }
        return PilotValidation.report(outcomes).also { artifacts.write("validation.json", it) }
    }

    private fun validatePosition(
        student: PilotStudent,
        scenarioSeed: Int,
        index: Int,
    ): PilotValidationOutcome {
        val scenario = CommodityExperiment.scenario(scenarioSeed)
        val features =
            CommodityExperiment.features(
                scenario.state.viewForDecision(scenario.actor),
                scenario.actor,
                scenario.commodity,
            )
        val strategy = CommodityExperimentStrategy.entries[student.choose(features)]
        val seat = scenario.state.playerIds.indexOf(scenario.actor)
        val rewards =
            (0 until options.samples).map { sample ->
                val seed = tournamentSeed(options.seed + 400_000_000, index, sample, heldOut = true)
                val initial = CommodityExperiment.sampledState(scenario, seed)
                fun rollout(plan: PilotPlan?): Double {
                    checkDeadline()
                    val session = ExperimentSession(initial, seed.toLong())
                    session.resolve(deadlineNanos)
                    return play(session, seed, seat, PolicyKind.BASELINE, null, plan).rewards[seat]
                }
                val old = rollout(null)
                val learned =
                    if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
                        old
                    } else {
                        rollout(
                            PilotPlan(
                                strategy,
                                scenario.commodity,
                                scenario.cardReference,
                                scenario.startTurn,
                            )
                        )
                    }
                old to learned
            }
        return PilotValidationOutcome(
            scenarioSeed = scenario.seed,
            tag = scenario.tag,
            baselineRewards = rewards.map { it.first },
            studentRewards = rewards.map { it.second },
            strategy = strategy.name,
        )
    }

    fun skipEvaluation(validation: PilotValidationReport) {
        require(!validation.passed) { "A passing validation report should proceed to evaluation." }
        artifacts.write(
            "evaluation.json",
            mapOf(
                "complete" to false,
                "experimentComplete" to true,
                "skipped" to true,
                "promoted" to false,
                "configuration" to CommodityExperiment.configuration.id,
                "pairs" to 0,
                "games" to 0,
                "reason" to validation.reason,
                "validation" to validation,
            ),
        )
    }

    fun evaluate(student: PilotStudent, validation: PilotValidationReport) {
        require(validation.passed) { "Full-game evaluation requires passing held-out validation." }
        var completed = 0
        val pairs =
            workers.run(
                (0 until options.pairs).map { index ->
                    {
                        cached("pair-$index.json") {
                            val seed =
                                tournamentSeed(
                                    options.seed + 1_000_000_000,
                                    0,
                                    index,
                                    heldOut = true,
                                )
                            val seat = index % CommodityExperiment.configuration.playerCount
                            mapOf(
                                "index" to index,
                                "seed" to seed,
                                "seat" to seat,
                                "baseline" to runGame(seed, PolicyKind.BASELINE, null, seat),
                                "heuristic" to runGame(seed, PolicyKind.HEURISTIC, null, seat),
                                "student" to runGame(seed, PolicyKind.STUDENT, student, seat),
                            )
                        }
                    }
                }
            ) {
                completed++
                println("Evaluation $completed/${options.pairs} paired lineups")
            }
        fun values(kind: String, metric: String): List<Double> = pairs.map { pair ->
            pair.required(kind).required(metric)[pair.required("seat").intValue()].doubleValue()
        }
        fun comparison(kind: String): Map<String, Any?> {
            val wins = values(kind, "wins")
            val baseWins = values("baseline", "wins")
            val rewards = values(kind, "rewards")
            val baseRewards = values("baseline", "rewards")
            val interval = pilotInterval(wins.zip(baseWins) { candidate, old -> candidate - old })
            return mapOf(
                "winShare" to wins.average(),
                "baselineWinShare" to baseWins.average(),
                "winShareDifference" to interval,
                "rewardDifference" to
                    pilotInterval(rewards.zip(baseRewards) { candidate, old -> candidate - old }),
                "meanRankReward" to values(kind, "ranks").average(),
                "planSelections" to pairs.sumOf { it.required(kind).required("plans").intValue() },
                "planSelectionsByStrategy" to
                    CommodityExperimentStrategy.entries.associate { strategy ->
                        strategy.name to
                            pairs.sumOf {
                                it.required(kind)
                                    .required("planSelectionsByStrategy")[strategy.name]
                                    ?.intValue() ?: 0
                            }
                    },
                "targetMet" to
                    (interval.mean >= 0.10 && (interval.lower95 ?: Double.NEGATIVE_INFINITY) > 0),
            )
        }
        artifacts.write(
            "evaluation.json",
            mapOf(
                "complete" to true,
                "experimentComplete" to true,
                "skipped" to false,
                "promoted" to false,
                "configuration" to CommodityExperiment.configuration.id,
                "pairs" to pairs.size,
                "games" to pairs.size * 3,
                "heuristic" to comparison("heuristic"),
                "student" to comparison("student"),
                "opponents" to
                    "Frozen baseline and rule-based prior with rotated HARD/NORMAL difficulties",
                "intervalMethod" to
                    "Normal approximation over paired game-seed differences; one pair has no interval",
                "scope" to
                    "One configuration and fixed opponent pool. Teacher was evaluated only on its explicitly known-information curriculum. No browser model promotion.",
            ),
        )
    }

    private fun runGame(
        seed: Int,
        kind: PolicyKind,
        student: PilotStudent?,
        seat: Int = 0,
    ): PilotGame =
        play(
            ExperimentSession.start(CommodityExperiment.configuration, seed.toLong()),
            seed,
            seat,
            kind,
            student,
        )

    private fun play(
        session: ExperimentSession,
        seed: Int,
        controlledSeat: Int,
        kind: PolicyKind,
        student: PilotStudent?,
        initialPlan: PilotPlan? = null,
        observe: ((Int, GameView, BotObservation, String) -> Unit)? = null,
    ): PilotGame {
        val started = System.nanoTime()
        var observationNanos = 0L
        var viewNanos = 0L
        var choicesNanos = 0L
        var featuresNanos = 0L
        var policyNanos = 0L
        val controlled = session.state.playerIds[controlledSeat]
        val controller = PilotController(kind, baseline, student, initialPlan)
        while (session.state.stage != Stage.FINISHED) {
            checkDeadline()
            check(session.decisions < 12000) {
                "Pilot game $seed exceeded 12000 decisions at ${session.state.stage}."
            }
            val state = session.state
            val commands =
                session.actors().associateWith { actor ->
                    val seat = state.playerIds.indexOf(actor)
                    val startObservation = System.nanoTime()
                    val view = state.viewForDecision(actor)
                    viewNanos += System.nanoTime() - startObservation
                    val startChoices = System.nanoTime()
                    val prompt = state.prompts().single()
                    val decision =
                        when (prompt) {
                            is dev.aragos.aor.Prompt.SinglePlayer -> prompt.choice
                            is dev.aragos.aor.Prompt.MultiPlayer -> prompt.choice
                            else -> error("A pilot policy requires a player decision.")
                        }
                    val choices = state.legalChoices(actor)
                    choicesNanos += System.nanoTime() - startChoices
                    val startFeatures = System.nanoTime()
                    val observation = BotObservations.from(view, choices, actor, decision)
                    featuresNanos += System.nanoTime() - startFeatures
                    observationNanos += System.nanoTime() - startObservation
                    val startPolicy = System.nanoTime()
                    val policySeed = seed xor (session.decisions * 65537 + seat * 8191)
                    val command =
                        if (actor == controlled) {
                            controller.choose(view, observation, actor, policySeed)
                        } else {
                            val model =
                                if ((seed.toLong() + seat) % 3 == 0L) {
                                    BotModels.default
                                } else {
                                    baseline
                                }
                            val difficulty =
                                if (seat % 2 == 0) BotDifficulty.NORMAL else BotDifficulty.HARD
                            BotPolicy.choose(observation, difficulty, policySeed, model)
                        }
                    policyNanos += System.nanoTime() - startPolicy
                    if (actor == controlled) {
                        observe?.invoke(session.decisions, view, observation, command)
                    }
                    command
                }
            session.apply(commands, deadlineNanos)
        }
        val scores = session.state.publicView().scores
        val result =
            SimulatedGame(
                "pilot",
                seed,
                session.decisions,
                scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0,
            )
        return PilotGame(
            rewards = scores.indices.map(result::reward),
            wins = scores.indices.map(result::winShare),
            ranks = scores.indices.map(result::rankReward),
            decisions = session.decisions,
            elapsedMillis = (System.nanoTime() - started) / 1_000_000,
            transitionNanos = session.transitionNanos,
            observationNanos = observationNanos,
            viewNanos = viewNanos,
            choicesNanos = choicesNanos,
            featuresNanos = featuresNanos,
            policyNanos = policyNanos,
            plans = controller.plans,
            planSelectionsByStrategy = controller.selections.toMap(),
        )
    }
}

private enum class PolicyKind {
    BASELINE,
    HEURISTIC,
    STUDENT,
}

private data class PilotPlan(
    val strategy: CommodityExperimentStrategy,
    val commodity: Commodity,
    val reference: String,
    val startTurn: Int,
)

/**
 * A plan belongs to one seat and one live card, and is discarded when that card leaves its hand.
 */
private class PilotController(
    private val kind: PolicyKind,
    private val baseline: BotModel,
    private val student: PilotStudent?,
    initialPlan: PilotPlan?,
) {
    private var plan = initialPlan
    private var lastPlanTurn: Int? = initialPlan?.startTurn
    var plans: Int = if (initialPlan == null) 0 else 1
        private set

    private val strategySelections =
        initialPlan?.let { mutableMapOf(it.strategy.name to 1) } ?: mutableMapOf()
    val selections: Map<String, Int>
        get() = strategySelections

    fun choose(view: GameView, observation: BotObservation, actor: PlayerId, seed: Int): String {
        if (plan?.let { current -> view.hand.none { it.reference == current.reference } } == true) {
            plan = null
        }
        if (
            kind != PolicyKind.BASELINE &&
                plan == null &&
                view.currentEpoch == 3 &&
                observation.decision == "play-cards" &&
                view.turnNumber != lastPlanTurn
        ) {
            val focus = CommodityExperiment.focus(view)
            if (focus != null) {
                val (card, commodity) = focus
                val strategy =
                    when (kind) {
                        PolicyKind.HEURISTIC ->
                            CommodityExperiment.heuristic(view, actor, commodity)
                        PolicyKind.STUDENT ->
                            CommodityExperimentStrategy.entries[
                                    checkNotNull(student) {
                                            "Student policy requires a fitted network."
                                        }
                                        .choose(
                                            CommodityExperiment.features(view, actor, commodity)
                                        )]
                        PolicyKind.BASELINE -> error("Baseline does not create experimental plans.")
                    }
                val turn = checkNotNull(view.turnNumber) { "Card plans require an active turn." }
                plan =
                    if (strategy == CommodityExperimentStrategy.KEEP_BASELINE) {
                        null
                    } else {
                        PilotPlan(strategy, commodity, card.reference, turn)
                    }
                lastPlanTurn = turn
                plans++
                strategySelections[strategy.name] = (strategySelections[strategy.name] ?: 0) + 1
            }
        }
        val current =
            plan ?: return BotPolicy.choose(observation, BotDifficulty.HARD, seed, baseline)
        return CommodityExperiment.command(
            view,
            observation,
            current.strategy,
            current.commodity,
            current.reference,
            current.startTurn,
            baseline,
            seed,
        )
    }
}
