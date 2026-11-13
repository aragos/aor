package dev.aragos.aor.training

import dev.aragos.aor.ai.BotModel
import kotlin.math.abs
import kotlin.math.exp

/** Public record of the exact existing controller choice, before applying any command. */
internal data class CpePairedDecision(
    val head: CpeHead,
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val actorSeed: Int,
    val observation: CpePairedObservation,
    val sampledCommandIndex: Int,
    val behaviorLogProbability: Double,
    val behaviorValue: Double,
    val currentProbabilities: List<Double>,
    val originalProbabilities: List<Double>,
) {
    init {
        require(head == observation.head && sampledCommandIndex in observation.commands.indices) {
            "C/P/E decision head and sampled command must match the original typed observation."
        }
        require(
            listOf(currentProbabilities, originalProbabilities).all { probabilities ->
                probabilities.size == observation.commands.size &&
                    probabilities.all { it.isFinite() && it > 0.0 && it <= 1.0 } &&
                    abs(probabilities.sum() - 1.0) < 1e-9
            } &&
                behaviorLogProbability.isFinite() &&
                abs(exp(behaviorLogProbability) - currentProbabilities[sampledCommandIndex]) <
                    1e-12 &&
                behaviorValue.isFinite() &&
                behaviorValue in 0.0..1.0
        ) {
            "C/P/E records require full original/current support and the actual sampled likelihood."
        }
    }
}

/**
 * Existing C/P owners retain M8/F0 priors and seeds. Existing E wraps that complete continuation
 * last; the raw E/T/G3 reference handles every other decision. Only the assigned head is recorded.
 */
internal class CpePairedController(
    reference: SimulationController,
    ordinary: BotModel,
    purchaseBaseline: BotModel,
    private val current: CpePolicyHeads,
    private val original: CpePolicyHeads,
    private val recordedHead: CpeHead,
    private val record: ((CpePairedDecision) -> Unit)? = null,
) : SimulationController {
    private var activeDecision: SimulationDecision? = null
    private val cardPurchase =
        CardActorController(
            ordinary,
            current.card,
            current.purchase,
            purchaseBaseline = purchaseBaseline,
            purchaseRecord =
                if (record != null && recordedHead == CpeHead.PURCHASE) ::purchase else null,
            record = if (record != null && recordedHead == CpeHead.CARD) ::card else null,
        )
    private val combined =
        ExpansionOnPolicyController(
            object : SimulationController {
                override fun controls(decision: String): Boolean =
                    cardPurchase.controls(decision) || reference.controls(decision)

                override fun choose(decision: SimulationDecision): String? =
                    if (cardPurchase.controls(decision.observation.decision)) {
                        cardPurchase.choose(decision)
                    } else reference.choose(decision)
            },
            current.expansion,
            if (record != null && recordedHead == CpeHead.EXPANSION) ::expansion else null,
        )

    override fun controls(decision: String): Boolean = combined.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        check(activeDecision == null) { "A C/P/E controller cannot choose recursively." }
        activeDecision = decision
        return try {
            combined.choose(decision)
        } finally {
            activeDecision = null
        }
    }

    private fun card(row: CardDecisionRecord) =
        emit(
            row.number,
            row.actorSeed,
            CpePairedObservation.Card(row.observation),
            row.selectedIndex,
            row.behaviorLogProbability,
            row.behaviorValue,
        )

    private fun purchase(row: PurchaseDecisionRecord) =
        emit(
            row.number,
            row.actorSeed,
            CpePairedObservation.Purchase(row.observation),
            row.selectedIndex,
            row.behaviorLogProbability,
            row.behaviorValue,
        )

    private fun expansion(row: TerritoryDecisionRecord) =
        emit(
            row.number,
            row.actorSeed,
            CpePairedObservation.Expansion(row.observation),
            row.selectedIndex,
            row.behaviorLogProbability,
            row.behaviorValue,
        )

    private fun emit(
        number: Int,
        actorSeed: Int,
        observation: CpePairedObservation,
        index: Int,
        logProbability: Double?,
        value: Double?,
    ) {
        val decision = requireNotNull(activeDecision) { "A recorded choice needs its active call." }
        check(number == decision.number && observation.head == recordedHead) {
            "The recorded legacy choice must belong to this exact active head/command."
        }
        val currentProbabilities = observation.probabilities(current)
        record?.invoke(
            CpePairedDecision(
                recordedHead,
                number,
                decision.batchStartNumber,
                decision.seed,
                actorSeed,
                observation,
                index,
                requireNotNull(logProbability) { "A current C/P/E actor must record likelihood." },
                requireNotNull(value) { "A current C/P/E actor must record value." },
                currentProbabilities,
                if (current === original) currentProbabilities
                else observation.probabilities(original),
            )
        )
    }
}
