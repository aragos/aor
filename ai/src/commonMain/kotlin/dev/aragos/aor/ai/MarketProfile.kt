package dev.aragos.aor.ai

/** Portable coefficients fitted against paired terminal win share. All scales are dimensionless. */
data class MarketProfile(
    val markerScale: Double = 1.0,
    val commodityScale: Double = 1.0,
    val earlyShift: Double = 0.0,
    val expansion: List<Double> = emptyList(),
) {
    init {
        require(
            markerScale in .25..2.0 && commodityScale in .25..2.0 && earlyShift in -0.25..0.25
        ) {
            "Market scales must be 0.25–2 and the initiative adjustment within 0.25."
        }
        require(
            (expansion.isEmpty() || expansion.size == ExpansionResponse.WIDTH) &&
                expansion.all { it.isFinite() && it in -4.0..4.0 }
        ) {
            "The expansion response requires one finite bounded weight per input."
        }
    }
}

/** Observation-only alternatives and inputs shared by terminal rollout collection and inference. */
object ExpansionResponse {
    const val WIDTH = 12

    data class Alternative(val command: String, val inputs: List<Double>)

    fun alternatives(observation: BotObservation, ordinary: String): List<Alternative> {
        if (observation.decision != "expand") return emptyList()
        val baseline = observation.candidates.first { it.command == ordinary }
        val others = observation.candidates.filter { it.command != ordinary }
        val markets = others.filter {
            (it.features[BotEconomicFeature.MARKET_MARKER_INCOME.key] ?: 0.0) > 0.0
        }
        fun opportunity(candidate: BotCandidate): Double =
            (candidate.features["income"] ?: 0.0) + (candidate.features["commodity"] ?: 0.0) -
                candidate.tokenCost
        val pressure = markets.partition { (it.features["risk"] ?: 0.0) > 0.0 }
        val candidates =
            listOfNotNull(
                pressure.first.maxByOrNull(::opportunity),
                pressure.second.maxByOrNull(::opportunity),
                others.firstOrNull { it.command == "pass" },
            )
        val origin = inputs(baseline, observation.availableTokens)
        return candidates.map { action ->
            Alternative(
                action.command,
                inputs(action, observation.availableTokens).mapIndexed { i, value ->
                    value - origin[i]
                },
            )
        }
    }

    private fun inputs(candidate: BotCandidate, pool: Int): List<Double> {
        fun feature(name: String, scale: Double): Double =
            ((candidate.features[name] ?: 0.0) / scale).coerceIn(-2.0, 2.0)
        val risk = feature("risk", 6.0)
        val tokens = candidate.tokenCost.toDouble() / pool.coerceAtLeast(1)
        val income = feature(BotEconomicFeature.MARKET_MARKER_INCOME.key, 4.0)
        return listOf(
            tokens,
            risk,
            income,
            feature("progress", 1.0),
            feature("reach", 1.0),
            feature("commodity", 12.0),
            feature(BotEconomicFeature.HELD_COMMODITY.key, 40.0),
            feature(BotEconomicFeature.PUBLIC_COMMODITY.key, 80.0),
            feature(BotEconomicFeature.COMMODITY_DENIAL.key, 80.0),
            if (candidate.command == "pass") 1.0 else 0.0,
            tokens * income,
            risk * income,
        )
    }

    fun choose(observation: BotObservation, ordinary: String, weights: List<Double>): String {
        var best = ordinary
        var bestGain = .02
        for (alternative in alternatives(observation, ordinary)) {
            val gain = weights.indices.sumOf { weights[it] * alternative.inputs[it] }
            if (gain > bestGain) {
                best = alternative.command
                bestGain = gain
            }
        }
        return best
    }
}
