package dev.aragos.aor.ai

import kotlin.math.abs

/** Pure, bounded controller used unchanged in browser workers and JVM self-play. */
object BotPolicy {
    fun score(candidate: BotCandidate, model: BotModel = BotModels.default): Double =
        candidate.features.entries.sumOf { (feature, value) ->
            value * (model.weights[feature] ?: 0.0)
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
        val scores =
            observation.candidates.associate { candidate ->
                candidate.command to score(candidate, model)
            }
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
            BotDifficulty.HARD -> plan(observation, ranked, model, scores)
        }
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
    )

    private fun plan(
        observation: BotObservation,
        ranked: List<BotCandidate>,
        model: BotModel,
        scores: Map<String, Double>,
    ): BotCandidate {
        val planningFamily =
            when (observation.decision) {
                "purchase-advances" -> "advance"
                "expand" -> "expansion"
                else -> return ranked.first()
            }
        val options = ranked.filter { it.family == planningFamily }.take(32)
        if (options.size < 2) return ranked.first()
        val reserve = (observation.context["stabilization"] ?: 0.0).toInt()
        val originalShortfall = (reserve - observation.availableCash).coerceAtLeast(0)
        fun reserveValue(cash: Int): Double =
            if (planningFamily == "advance") {
                ((reserve - cash).coerceAtLeast(0) - originalShortfall) *
                    (model.weights["reserveShortfall"] ?: 0.0)
            } else 0.0
        val intrinsicScores = options.associate { candidate ->
            candidate.command to
                candidate.features.entries
                    .filterNot { (name, _) ->
                        name == "reserveShortfall" || name.endsWith(".reserveShortfall")
                    }
                    .sumOf { (name, value) -> value * (model.weights[name] ?: 0.0) }
        }
        fun intrinsic(candidate: BotCandidate): Double =
            checkNotNull(intrinsicScores[candidate.command]) {
                "Every planning option requires a cached intrinsic score."
            }
        var frontier = options.mapNotNull { candidate ->
            if (
                candidate.cashCost > observation.availableCash ||
                    candidate.tokenCost > observation.availableTokens
            ) {
                null
            } else {
                SearchNode(
                    candidate,
                    intrinsic(candidate) +
                        reserveValue(observation.availableCash - candidate.cashCost),
                    observation.availableCash - candidate.cashCost,
                    observation.availableTokens - candidate.tokenCost,
                    setOf(candidate.group),
                    setOf(candidate.command),
                )
            }
        }
        var best = frontier.maxByOrNull(SearchNode::value) ?: return ranked.first()
        // Fewer than 2200 expansions. No full-game search or hidden-state simulation.
        repeat(4) {
            frontier =
                frontier
                    .flatMap { node ->
                        options.mapNotNull { candidate ->
                            if (
                                candidate.command in node.commands ||
                                    candidate.group in node.groups ||
                                    candidate.cashCost > node.cash ||
                                    candidate.tokenCost > node.tokens
                            ) {
                                null
                            } else {
                                val cash = node.cash - candidate.cashCost
                                node.copy(
                                    value =
                                        node.value +
                                            intrinsic(candidate) * 0.92 +
                                            reserveValue(cash) - reserveValue(node.cash),
                                    cash = cash,
                                    tokens = node.tokens - candidate.tokenCost,
                                    groups = node.groups + candidate.group,
                                    commands = node.commands + candidate.command,
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
        return if (best.value > highestScore) best.first else ranked.first()
    }
}
