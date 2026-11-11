package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel

/** Exact native expansion-overlay dictionary and ordinary-weight preservation contract. */
internal object ExpansionImitationProtocol {
    const val VERSION = "expansion-imitation-1"
    val features =
        listOf(
            "tokens",
            "progress",
            "reach",
            "commodity",
            "income",
            "risk",
            "action.expansion",
            "action.pass",
        )

    fun preservesOtherWeights(baseline: BotModel, model: BotModel): Boolean =
        (baseline.weights.keys + model.weights.keys).all { name ->
            name in features || (baseline.weights[name] ?: 0.0) == (model.weights[name] ?: 0.0)
        }
}
