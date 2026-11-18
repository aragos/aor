package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonValue
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchasePolicy
import dev.aragos.aor.ai.PurchasePolicyModel
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyModel

/** Typed existing observations retain their original wire shape and intrinsic privacy boundary. */
internal sealed interface CpePairedObservation {
    val head: CpeHead
    val commands: List<String>
    val baselineIndex: Int

    @JsonValue fun wire(): Any

    fun probabilities(models: CpePolicyHeads): List<Double>

    data class Card(val observation: CardObservation) : CpePairedObservation {
        override val head = CpeHead.CARD
        override val commands = observation.actions.map { it.command }
        override val baselineIndex = observation.baselineIndex

        override fun wire(): Any = observation

        override fun probabilities(models: CpePolicyHeads): List<Double> =
            CardPolicy.evaluate(observation, models.card).probabilities
    }

    data class Purchase(val observation: PurchaseObservation) : CpePairedObservation {
        override val head = CpeHead.PURCHASE
        override val commands = observation.actions.map { it.command }
        override val baselineIndex = observation.baselineIndex

        override fun wire(): Any = observation

        override fun probabilities(models: CpePolicyHeads): List<Double> =
            PurchasePolicy.evaluate(observation, models.purchase).probabilities
    }

    data class Expansion(val observation: TerritoryObservation) : CpePairedObservation {
        override val head = CpeHead.EXPANSION
        override val commands = observation.actions.map { it.command }
        override val baselineIndex = observation.baselineIndex

        override fun wire(): Any = observation

        override fun probabilities(models: CpePolicyHeads): List<Double> =
            TerritoryPolicy.evaluate(observation, models.expansion).probabilities
    }
}

/** Complete current/original inference snapshots; no optimizer or partial-head publication. */
internal data class CpePolicyHeads(
    val card: CardPolicyModel,
    val purchase: PurchasePolicyModel,
    val expansion: TerritoryPolicyModel,
) {
    fun validateFrozenValues(original: CpePolicyHeads) {
        require(
            card.valueWeights == original.card.valueWeights &&
                card.valueBias == original.card.valueBias &&
                purchase.valueWeights == original.purchase.valueWeights &&
                purchase.valueBias == original.purchase.valueBias &&
                expansion.valueWeights == original.expansion.valueWeights &&
                expansion.valueBias == original.expansion.valueBias
        ) {
            "Actor-only C/P/E publication must preserve every original value parameter."
        }
    }
}
