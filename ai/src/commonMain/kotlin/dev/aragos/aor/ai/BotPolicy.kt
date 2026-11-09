package dev.aragos.aor.ai

import kotlin.math.abs

/** The same selected first action and advisory total value used by the live bounded planner. */
internal data class BotPlanEvaluation(val candidate: BotCandidate, val value: Double)

/** Pure, bounded controller used unchanged in browser workers and JVM self-play. */
object BotPolicy {
    fun score(candidate: BotCandidate, model: BotModel = BotModels.default): Double =
        candidate.features.entries.sumOf { (feature, value) ->
            if (BotTokenValue.reserved(feature)) 0.0 else value * (model.weights[feature] ?: 0.0)
        }

    fun choose(
        observation: BotObservation,
        difficulty: BotDifficulty,
        seed: Int,
        model: BotModel = BotModels.default,
    ): String = chooseCandidate(observation, difficulty, seed, model).command

    fun chooseCandidate(
        observation: BotObservation,
        difficulty: BotDifficulty,
        seed: Int,
        model: BotModel = BotModels.default,
    ): BotCandidate {
        require(observation.schemaVersion == 1) {
            "Unsupported bot observation schema: ${observation.schemaVersion}."
        }
        require(observation.candidates.isNotEmpty()) { "A bot decision requires a candidate." }
        require(observation.candidates.size <= 1024) { "A bot decision has too many candidates." }
        val ordinaryScores =
            observation.candidates.associate { candidate ->
                candidate.command to score(candidate, model)
            }
        val scores =
            if (difficulty == BotDifficulty.HARD) {
                BotLeaderTiming.scores(observation, model, ordinaryScores)
            } else ordinaryScores
        val ranked =
            observation.candidates.sortedWith(
                compareByDescending<BotCandidate> { candidate ->
                        checkNotNull(scores[candidate.command]) {
                            "Every bot candidate requires a cached score."
                        }
                    }
                    .thenBy(BotCandidate::command)
            )
        return when (difficulty) {
            BotDifficulty.EASY -> easy(ranked, seed, scores)
            BotDifficulty.NORMAL -> ranked.first()
            BotDifficulty.HARD -> {
                val baseline = plan(observation, ranked, model, scores).candidate
                if (observation.decision == "buy-tokens") {
                    val incumbent = BotTokenValue.choose(observation, baseline, model)
                    BotTokenCorrection.choose(observation, incumbent, model)
                } else {
                    val deferred =
                        BotLeaderTiming.deferForCash(observation, baseline, model, ordinaryScores)
                    BotTargetTieBreak.choose(observation, deferred, model, seed, scores)
                }
            }
        }
    }

    /** Forecasts call the ordinary planner directly; no leader forecast can recursively expand. */
    internal fun evaluatePlan(observation: BotObservation, model: BotModel): BotPlanEvaluation {
        val scores = observation.candidates.associate { it.command to score(it, model) }
        val ranked =
            observation.candidates.sortedWith(
                compareByDescending<BotCandidate> { scores[it.command] }
                    .thenBy(BotCandidate::command)
            )
        return plan(observation, ranked, model, scores)
    }

    private fun easy(
        ranked: List<BotCandidate>,
        seed: Int,
        scores: Map<String, Double>,
    ): BotCandidate {
        // Keep mistakes within a reasonable band; never deliberately select a catastrophic move.
        val best =
            checkNotNull(scores[ranked.first().command]) {
                "The highest-ranked candidate requires a cached score."
            }
        val reasonable =
            ranked.take(5).filter { candidate ->
                checkNotNull(scores[candidate.command]) {
                    "An easy candidate requires a cached score."
                } >= best - 14.0
            }
        return reasonable[abs(seed.toLong() * 1103515245L + 12345L).rem(reasonable.size).toInt()]
    }

    private data class SearchNode(
        val first: BotCandidate,
        val value: Double,
        val cash: Int,
        val tokens: Int,
        val groups: Set<String>,
        val commands: Set<String>,
        val markers: Int,
        val overseasSlots: Int,
        val interaction: Double,
        val interest: Double,
    )

    private fun plan(
        observation: BotObservation,
        ranked: List<BotCandidate>,
        model: BotModel,
        scores: Map<String, Double>,
    ): BotPlanEvaluation {
        val highest =
            BotPlanEvaluation(
                ranked.first(),
                checkNotNull(scores[ranked.first().command]) {
                    "The highest-ranked candidate requires a cached score."
                },
            )
        val planningFamily =
            when (observation.decision) {
                "purchase-advances" -> "advance"
                "expand" -> "expansion"
                else -> return highest
            }
        val options = ranked.filter { it.family == planningFamily }.take(32)
        val interest =
            if (planningFamily == "advance") {
                BotAdvanceInterest.forObservation(observation, model)
            } else null
        if (options.isEmpty() || options.size < 2 && interest == null) return highest
        val expansion = observation.expansionPlanning
        val interactions =
            if (planningFamily == "expansion") {
                BotExpansionInteractions.forObservation(observation, model)
            } else null
        val sequencing =
            if (planningFamily == "advance") {
                BotAdvanceSequencing.forObservation(observation, model)
            } else null
        val reserve = (observation.context["stabilization"] ?: 0.0).toInt()
        val originalShortfall = (reserve - observation.availableCash).coerceAtLeast(0)
        // Older observations retain their global-only reconstruction. Current projections include
        // the exact active coefficient even when no individual offer yet crosses the reserve.
        val reserveCoefficient =
            observation.reserveShortfallBasis?.entries?.sumOf { (feature, value) ->
                value * (model.weights[feature] ?: 0.0)
            } ?: (model.weights["reserveShortfall"] ?: 0.0)
        fun reserveValue(cash: Int): Double =
            if (planningFamily == "advance") {
                ((reserve - cash).coerceAtLeast(0) - originalShortfall) * reserveCoefficient
            } else 0.0
        val intrinsicScores = options.associate { candidate ->
            candidate.command to
                candidate.features.entries
                    .filterNot { (name, _) ->
                        name == "reserveShortfall" ||
                            name.endsWith(".reserveShortfall") ||
                            BotTokenValue.reserved(name) ||
                            (interest != null && name == BotEconomicFeature.FOREGONE_INTEREST.key)
                    }
                    .sumOf { (name, value) -> value * (model.weights[name] ?: 0.0) }
        }
        fun intrinsic(candidate: BotCandidate): Double =
            checkNotNull(intrinsicScores[candidate.command]) {
                "Every planning option requires a cached intrinsic score."
            }
        var frontier = options.mapNotNull { candidate ->
            val step = expansion?.steps[candidate.command]
            val markerCost = step?.markerCost ?: 0
            val overseasCost = step?.newOverseasCost ?: 0
            if (
                candidate.cashCost > observation.availableCash ||
                    candidate.tokenCost > observation.availableTokens ||
                    markerCost > (expansion?.markerStock ?: Int.MAX_VALUE) ||
                    overseasCost > (expansion?.overseasSlots ?: Int.MAX_VALUE)
            ) {
                null
            } else {
                val cash = observation.availableCash - candidate.cashCost
                val commands = setOf(candidate.command)
                val interestValue = interest?.value(cash, commands) ?: 0.0
                val ordinaryValue = intrinsic(candidate) + reserveValue(cash)
                SearchNode(
                    first = candidate,
                    value = if (interest == null) ordinaryValue else ordinaryValue + interestValue,
                    cash = cash,
                    tokens = observation.availableTokens - candidate.tokenCost,
                    groups = setOf(candidate.group),
                    commands = commands,
                    markers = (expansion?.markerStock ?: Int.MAX_VALUE) - markerCost,
                    overseasSlots = (expansion?.overseasSlots ?: Int.MAX_VALUE) - overseasCost,
                    interaction = 0.0,
                    interest = interestValue,
                )
            }
        }
        var best = frontier.maxByOrNull(SearchNode::value) ?: return highest
        // Fewer than 2200 expansions. No full-game search or hidden-state simulation.
        repeat(4) {
            frontier =
                frontier
                    .flatMap { node ->
                        options.mapNotNull { candidate ->
                            val price =
                                sequencing?.price(candidate, node.commands) ?: candidate.cashCost
                            val step = expansion?.steps[candidate.command]
                            val markerCost = step?.markerCost ?: 0
                            val overseasCost = step?.newOverseasCost ?: 0
                            if (
                                candidate.command in node.commands ||
                                    candidate.group in node.groups ||
                                    price > node.cash ||
                                    candidate.tokenCost > node.tokens ||
                                    markerCost > node.markers ||
                                    overseasCost > node.overseasSlots
                            ) {
                                null
                            } else {
                                val cash = node.cash - price
                                val commands = node.commands + candidate.command
                                val interaction = interactions?.value(commands) ?: 0.0
                                val interestValue = interest?.value(cash, commands) ?: 0.0
                                val continuation =
                                    if (sequencing == null) intrinsic(candidate)
                                    else
                                        intrinsic(candidate) +
                                            sequencing.value(candidate, node.commands)
                                val ordinaryValue =
                                    node.value + continuation * 0.92 + reserveValue(cash) -
                                        reserveValue(node.cash)
                                val correctedValue =
                                    if (interest == null) ordinaryValue
                                    else ordinaryValue + interestValue - node.interest
                                node.copy(
                                    value =
                                        if (interactions == null) correctedValue
                                        else
                                            correctedValue +
                                                0.92 * (interaction - node.interaction),
                                    cash = cash,
                                    tokens = node.tokens - candidate.tokenCost,
                                    groups = node.groups + candidate.group,
                                    commands = commands,
                                    markers = node.markers - markerCost,
                                    overseasSlots = node.overseasSlots - overseasCost,
                                    interaction = interaction,
                                    interest = interestValue,
                                )
                            }
                        }
                    }
                    .sortedWith(
                        compareByDescending<SearchNode> { it.value }.thenBy { it.first.command }
                    )
                    .take(12)
            val next = frontier.maxByOrNull(SearchNode::value)
            if (next != null && next.value > best.value) best = next
        }
        val highestScore =
            checkNotNull(scores[ranked.first().command]) {
                "The highest-ranked candidate requires a cached score."
            }
        return if (best.value > highestScore) BotPlanEvaluation(best.first, best.value) else highest
    }
}
