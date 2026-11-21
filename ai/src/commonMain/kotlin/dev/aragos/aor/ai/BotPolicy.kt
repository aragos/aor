package dev.aragos.aor.ai

import kotlin.math.abs

/** The same selected first action and advisory total value used by the live bounded planner. */
internal data class BotPlanEvaluation(val candidate: BotCandidate, val value: Double)

/** Pure, bounded controller used unchanged in browser workers and JVM self-play. */
object BotPolicy {
    fun score(candidate: BotCandidate, model: BotModel = BotModels.default): Double =
        candidate.features.entries.sumOf { (feature, value) ->
            if (BotModels.reservedFeature(feature)) 0.0 else value * (model.weights[feature] ?: 0.0)
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
        val militaryModel = BotMilitaryModel.forDifficulty(difficulty, model)
        val activeModel = BotExpansionModel.forObservation(observation, difficulty, militaryModel)
        val ordinaryScores =
            observation.candidates.associate { candidate ->
                candidate.command to score(candidate, activeModel)
            }
        val scores =
            if (difficulty == BotDifficulty.HARD) {
                BotLeaderTiming.scores(observation, activeModel, ordinaryScores)
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
                val baseline = plan(observation, ranked, activeModel, scores).candidate
                if (observation.decision == "buy-tokens") {
                    val incumbent = BotTokenValue.choose(observation, baseline, activeModel)
                    val corrected = BotTokenCorrection.choose(observation, incumbent, activeModel)
                    BotTokenOrderControl.choose(observation, corrected, activeModel)
                } else {
                    val deferred =
                        BotLeaderTiming.deferForCash(
                            observation,
                            baseline,
                            activeModel,
                            ordinaryScores,
                        )
                    BotTargetTieBreak.choose(observation, deferred, activeModel, seed, scores)
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
        val groups: Int,
        val commands: Set<String>,
        val markers: Int,
        val overseasSlots: Int,
        val interaction: Double,
        val interest: Double,
    )

    /** One bit per distinct group among the at most 32 planning options. */
    private data class SearchOption(
        val candidate: BotCandidate,
        val group: Int,
        val markerCost: Int,
        val overseasCost: Int,
        val intrinsic: Double,
    )

    private val frontierOrder =
        compareByDescending<SearchNode> { it.value }.thenBy { it.first.command }

    /** Bounded stable insertion, preserving generation order whenever the comparator ties. */
    private fun MutableList<SearchNode>.offer(node: SearchNode) {
        val limit = 12
        if (size == limit && frontierOrder.compare(node, last()) >= 0) return
        var lower = 0
        var upper = size
        while (lower < upper) {
            val middle = (lower + upper) / 2
            if (frontierOrder.compare(node, this[middle]) < 0) {
                upper = middle
            } else {
                lower = middle + 1
            }
        }
        if (size == limit) removeAt(lastIndex)
        add(lower, node)
    }

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
        val eligible = ranked.asSequence().filter { it.family == planningFamily }.take(32).toList()
        val interest =
            if (planningFamily == "advance") {
                BotAdvanceInterest.forObservation(observation, model)
            } else null
        if (eligible.isEmpty() || eligible.size < 2 && interest == null) return highest
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
                if (BotModels.reservedFeature(feature)) 0.0
                else value * (model.weights[feature] ?: 0.0)
            } ?: (model.weights["reserveShortfall"] ?: 0.0)
        fun reserveValue(cash: Int): Double =
            if (planningFamily == "advance") {
                ((reserve - cash).coerceAtLeast(0) - originalShortfall) * reserveCoefficient
            } else 0.0
        val groups =
            eligible.map(BotCandidate::group).distinct().withIndex().associate { (index, group) ->
                group to (1 shl index)
            }
        val options = eligible.map { candidate ->
            val step = expansion?.steps[candidate.command]
            val intrinsic =
                candidate.features.entries.sumOf { (name, value) ->
                    if (
                        name == "reserveShortfall" ||
                            name.endsWith(".reserveShortfall") ||
                            BotModels.reservedFeature(name) ||
                            (interest != null && name == BotEconomicFeature.FOREGONE_INTEREST.key)
                    ) {
                        0.0
                    } else value * (model.weights[name] ?: 0.0)
                }
            SearchOption(
                candidate,
                checkNotNull(groups[candidate.group]) {
                    "Every planning option requires its group bit."
                },
                step?.markerCost ?: 0,
                step?.newOverseasCost ?: 0,
                intrinsic,
            )
        }
        val markerStock = expansion?.markerStock ?: Int.MAX_VALUE
        val overseasSlots = expansion?.overseasSlots ?: Int.MAX_VALUE
        val needsCommands = interactions != null || interest != null || sequencing != null
        var frontier = options.mapNotNull { option ->
            val candidate = option.candidate
            if (
                candidate.cashCost > observation.availableCash ||
                    candidate.tokenCost > observation.availableTokens ||
                    option.markerCost > markerStock ||
                    option.overseasCost > overseasSlots
            ) {
                null
            } else {
                val cash = observation.availableCash - candidate.cashCost
                val commands = if (needsCommands) setOf(candidate.command) else emptySet()
                val interestValue = interest?.value(cash, commands) ?: 0.0
                val ordinaryValue = option.intrinsic + reserveValue(cash)
                SearchNode(
                    first = candidate,
                    value = if (interest == null) ordinaryValue else ordinaryValue + interestValue,
                    cash = cash,
                    tokens = observation.availableTokens - candidate.tokenCost,
                    groups = option.group,
                    commands = commands,
                    markers = markerStock - option.markerCost,
                    overseasSlots = overseasSlots - option.overseasCost,
                    interaction = 0.0,
                    interest = interestValue,
                )
            }
        }
        var best = frontier.maxByOrNull(SearchNode::value) ?: return highest
        // Fewer than 2200 expansions. No full-game search or hidden-state simulation.
        repeat(4) {
            val continuations = ArrayList<SearchNode>(12)
            for (node in frontier) {
                val reserved = reserveValue(node.cash)
                for (option in options) {
                    val candidate = option.candidate
                    if (
                        (option.group and node.groups) != 0 ||
                            candidate.tokenCost > node.tokens ||
                            option.markerCost > node.markers ||
                            option.overseasCost > node.overseasSlots
                    ) {
                        continue
                    }
                    val price = sequencing?.price(candidate, node.commands) ?: candidate.cashCost
                    if (price > node.cash) continue
                    val cash = node.cash - price
                    val commands =
                        if (needsCommands) node.commands + candidate.command else emptySet()
                    val interaction = interactions?.value(commands) ?: 0.0
                    val interestValue = interest?.value(cash, commands) ?: 0.0
                    val continuation =
                        if (sequencing == null) option.intrinsic
                        else option.intrinsic + sequencing.value(candidate, node.commands)
                    val ordinaryValue =
                        node.value + continuation * 0.92 + reserveValue(cash) - reserved
                    val correctedValue =
                        if (interest == null) ordinaryValue
                        else ordinaryValue + interestValue - node.interest
                    continuations.offer(
                        node.copy(
                            value =
                                if (interactions == null) correctedValue
                                else correctedValue + 0.92 * (interaction - node.interaction),
                            cash = cash,
                            tokens = node.tokens - candidate.tokenCost,
                            groups = node.groups or option.group,
                            commands = commands,
                            markers = node.markers - option.markerCost,
                            overseasSlots = node.overseasSlots - option.overseasCost,
                            interaction = interaction,
                            interest = interestValue,
                        )
                    )
                }
            }
            frontier = continuations
            val next = frontier.maxByOrNull(SearchNode::value)
            if (next != null && next.value > best.value) best = next
        }
        return if (best.value > highest.value) BotPlanEvaluation(best.first, best.value)
        else highest
    }
}
