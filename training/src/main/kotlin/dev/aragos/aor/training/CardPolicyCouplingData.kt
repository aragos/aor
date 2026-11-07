package dev.aragos.aor.training

import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardProposals

/** Initial F/A intervention; trivial A arms alias only the matching schedule's F outcomes. */
internal data class CardPolicyCouplingArm(
    val id: String,
    val schedule: String,
    val actionIndex: Int,
    val outcomeIds: List<String>,
) {
    init {
        require(id in IDS && schedule == Companion.schedule(id).name && actionIndex >= 0) {
            "A coupling arm requires its declared schedule, ID and initial action."
        }
        require(outcomeIds.size == 8 && outcomeIds.distinct().size == 8) {
            "Each coupling arm requires eight paired-stream outcomes."
        }
    }

    companion object {
        val IDS = listOf("l-f", "l-a", "g-f", "g-a", "s-f", "s-a")

        fun schedule(id: String): CardPolicySchedule {
            require(id in IDS) { "Unknown coupling arm ID." }
            return CardPolicySchedule.valueOf(id.take(1).uppercase())
        }

        fun action(id: String, reference: Int, alternative: Int?): Int {
            require(id in IDS) { "Unknown coupling arm ID." }
            return if (id.endsWith("-a")) alternative ?: reference else reference
        }

        fun outcomeIds(id: String, alternative: Int?): List<String> {
            require(id in IDS) { "Unknown coupling arm ID." }
            val owner = if (alternative == null && id.endsWith("-a")) id.take(1) + "-f" else id
            return (0..7).map { "arm-$owner-$it" }
        }
    }
}

/** Safe root input stays identical to the completed F capture and acquisition. */
internal data class CardPolicyCouplingRoot(
    val number: Int,
    val botSeed: Int,
    val turn: Int,
    val epoch: Int,
    val observation: CardObservation,
    val proposalIndices: List<Int>,
    val referenceIndex: Int,
    val acquisition: CardAdvantageAcquisition,
    val alternativeIndex: Int?,
    val arms: List<CardPolicyCouplingArm>,
) {
    init {
        require(number in 0..11999 && turn >= 1 && epoch in 1..3) {
            "A coupling root requires bounded absolute decision, turn and epoch facts."
        }
        require(
            CardAdvantagePolicy.supports(observation) &&
                proposalIndices == CardProposals.indices(observation, botSeed) &&
                referenceIndex == observation.baselineIndex
        ) {
            "A coupling root must retain its normal-window observation, complete S and F reference."
        }
        require(
            acquisition ==
                CardAdvantageAcquisition.select(proposalIndices, referenceIndex, botSeed, true) &&
                alternativeIndex == acquisition.sampledActionIndex
        ) {
            "The alternative must retain its outcome-independent uniform acquisition."
        }
        require(
            arms.map { it.id } == CardPolicyCouplingArm.IDS &&
                arms.all {
                    it.actionIndex ==
                        CardPolicyCouplingArm.action(it.id, referenceIndex, alternativeIndex) &&
                        it.outcomeIds == CardPolicyCouplingArm.outcomeIds(it.id, alternativeIndex)
                }
        ) {
            "The root requires all six declared arms and exact trivial aliases."
        }
    }
}

/** Terminal records contain no authority snapshot, hidden cards or seed-dependent policy input. */
internal data class CardPolicyCouplingOrigin(
    val schemaVersion: Int = 1,
    val kind: String = CardPolicyCouplingInput.KIND,
    val signature: String,
    val index: Int,
    val split: String,
    val seed: Int,
    val seat: Int,
    val status: String,
    val skipReason: String?,
    val request: CardAdvantageRequest,
    val backboneDecisions: Int,
    val backboneCanonicalSha256: String,
    val root: CardPolicyCouplingRoot?,
    val outcomes: Map<String, CardAdvantageOutcome>,
    val policyTraces: Map<String, List<CardPolicySeedTrace>>,
) {
    init {
        require(schemaVersion == 1 && kind == CardPolicyCouplingInput.KIND) {
            "Coupling records require their separate version and kind."
        }
        require(
            status == "skipped" &&
                skipReason == "requested-root-not-reached" &&
                root == null &&
                outcomes.isEmpty() &&
                policyTraces.isEmpty() ||
                status == "collected" &&
                    skipReason == null &&
                    root != null &&
                    outcomes.isNotEmpty() &&
                    policyTraces.keys == outcomes.keys
        ) {
            "A coupling origin is either fully collected with all traces or explicitly skipped."
        }
    }
}
