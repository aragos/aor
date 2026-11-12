package dev.aragos.aor.ai

import kotlin.math.ln

/** Separate behavior identity; it is not a full-support training-policy evaluation. */
class KnownOwnChaosChoice
internal constructor(
    val index: Int,
    val command: String,
    val logProbability: Double,
    val value: Double,
    val probabilities: List<Double>,
    val excludedIndices: List<Int>,
)

/**
 * Conditions the original learned distribution only on known immediate own survival. An unknown
 * consequence remains eligible. If every action guarantees own Chaos, preserve the original
 * distribution. This does not estimate eventual survival or change the learned value prediction.
 *
 * Inputs must be the original encoder observations and evaluations. Original action indices and
 * order are retained, including zero probability at excluded indices. Legacy trajectory readers
 * cannot interpret this version as the original full-support actor.
 */
object KnownOwnChaosSupport {
    const val VERSION = "known-own-chaos-support-1"

    fun card(
        observation: CardObservation,
        evaluation: CardPolicyEvaluation,
        uniform: Double,
    ): KnownOwnChaosChoice {
        val excluded =
            exclusions(
                observation.actions.map { action ->
                    action.features["effect.role.0.chaos"] == 1.0 &&
                        (action.features["effect.role.0.miseryUncertain"] ?: 0.0) == 0.0
                }
            )
        if (excluded.isEmpty()) {
            val old = CardPolicy.select(observation, evaluation, uniform)
            return KnownOwnChaosChoice(
                old.index,
                old.command,
                old.logProbability,
                old.value,
                evaluation.probabilities,
                emptyList(),
            )
        }
        return condition(
            observation.actions.map(CardAction::command),
            evaluation.probabilities,
            evaluation.value,
            excluded,
            uniform,
        )
    }

    fun purchase(
        observation: PurchaseObservation,
        evaluation: PurchasePolicyEvaluation,
        uniform: Double,
    ): KnownOwnChaosChoice {
        // Only the advance owner emits this feature, from its exact own-misery quote.
        val excluded = exclusions(observation.actions.map { it.features["chaos"] == 1.0 })
        if (excluded.isEmpty()) {
            val old = PurchasePolicy.select(observation, evaluation, uniform)
            return KnownOwnChaosChoice(
                old.index,
                old.command,
                old.logProbability,
                old.value,
                evaluation.probabilities,
                emptyList(),
            )
        }
        return condition(
            observation.actions.map(PurchaseAction::command),
            evaluation.probabilities,
            evaluation.value,
            excluded,
            uniform,
        )
    }

    private fun exclusions(known: List<Boolean>): List<Int> =
        if (known.all { it }) emptyList() else known.indices.filter { known[it] }

    private fun condition(
        commands: List<String>,
        original: List<Double>,
        value: Double,
        excluded: List<Int>,
        uniform: Double,
    ): KnownOwnChaosChoice {
        require(uniform.isFinite() && uniform >= 0.0 && uniform < 1.0) {
            "Conditional sampling requires one finite uniform draw in [0,1)."
        }
        require(commands.size == original.size) {
            "Conditional sampling requires one original probability per offered action."
        }
        val eligible = original.indices.filter { it !in excluded }
        val mass = eligible.sumOf { original[it] }
        check(mass.isFinite() && mass > 0.0) {
            "A nonempty subset of a full-support distribution must have positive mass."
        }
        val probabilities = original.mapIndexed { i, p -> if (i in excluded) 0.0 else p / mass }
        var cumulative = 0.0
        var selected = eligible.last()
        for (index in eligible) {
            cumulative += probabilities[index]
            if (uniform < cumulative) {
                selected = index
                break
            }
        }
        return KnownOwnChaosChoice(
            selected,
            commands[selected],
            ln(probabilities[selected]),
            value,
            probabilities,
            excluded,
        )
    }
}
