package dev.aragos.aor.training

import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardProposals

/** An intervention arm. Trivial A=F roots alias A outcome IDs to the matching F arm explicitly. */
internal data class CardContinuationArm(
    val id: String,
    val actionIndex: Int,
    val continuation: String,
    val outcomeIds: List<String>,
) {
    init {
        require(id in IDS && actionIndex >= 0) {
            "A diagnostic arm requires its declared ID and action."
        }
        require(continuation == tail(id)) {
            "An arm must preserve its declared own-seat continuation."
        }
        require(outcomeIds.size == 4 && outcomeIds.distinct().size == 4) {
            "Each arm requires four distinct paired-stream outcomes."
        }
    }

    companion object {
        val IDS = listOf("f-f", "a-f", "f-priority", "a-priority")

        fun tail(id: String): String {
            require(id in IDS) { "Unknown diagnostic arm ID." }
            return if (id.endsWith("priority")) "priority64" else "F"
        }

        fun action(id: String, reference: Int, alternative: Int?): Int {
            require(id in IDS) { "Unknown diagnostic arm ID." }
            return if (id.startsWith("a-")) alternative ?: reference else reference
        }

        fun outcomeIds(id: String, alternative: Int?): List<String> {
            require(id in IDS) { "Unknown diagnostic arm ID." }
            val owner = if (alternative == null && id.startsWith("a-")) "f" + id.drop(1) else id
            return (0..3).map { replicate -> "arm-$owner-$replicate" }
        }
    }
}

/**
 * Only the ordinary acting-player observation is exported; absolute seeds are provenance, not
 * input.
 */
internal data class CardContinuationRoot(
    val number: Int,
    val botSeed: Int,
    val turn: Int,
    val epoch: Int,
    val observation: CardObservation,
    val proposalIndices: List<Int>,
    val referenceIndex: Int,
    val acquisition: CardAdvantageAcquisition,
    val alternativeIndex: Int?,
    val arms: List<CardContinuationArm>,
) {
    init {
        require(number in 0..11999 && turn >= 1 && epoch in 1..3) {
            "A diagnostic root requires bounded absolute decision and turn provenance."
        }
        require(
            CardAdvantagePolicy.supports(observation) &&
                proposalIndices == CardProposals.indices(observation, botSeed) &&
                referenceIndex == observation.baselineIndex
        ) {
            "A diagnostic root must preserve its normal-window observation, full S and F reference."
        }
        require(
            acquisition ==
                CardAdvantageAcquisition.select(
                    proposalIndices,
                    referenceIndex,
                    botSeed,
                    sampled = true,
                ) && alternativeIndex == acquisition.sampledActionIndex
        ) {
            "The diagnostic alternative must be sampled before outcomes from actor-safe full S."
        }
        require(
            arms.map { it.id } == CardContinuationArm.IDS &&
                arms.all {
                    it.actionIndex ==
                        CardContinuationArm.action(it.id, referenceIndex, alternativeIndex) &&
                        it.outcomeIds == CardContinuationArm.outcomeIds(it.id, alternativeIndex)
                }
        ) {
            "Every diagnostic root requires the exact four paired arms and explicit trivial aliases."
        }
    }
}

/** Completed diagnostic records never contain hidden authority state or partial rollout targets. */
internal data class CardContinuationOrigin(
    val schemaVersion: Int = 3,
    val kind: String = CardContinuationDiagnosticInput.KIND,
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
    val root: CardContinuationRoot?,
    val outcomes: Map<String, CardAdvantageOutcome>,
) {
    init {
        require(schemaVersion == 3 && kind == CardContinuationDiagnosticInput.KIND) {
            "Diagnostic origin versions cannot be interpreted as a training dataset."
        }
        require(
            status == "skipped" &&
                skipReason == "requested-root-not-reached" &&
                root == null &&
                outcomes.isEmpty() ||
                status == "collected" && skipReason == null && root != null && outcomes.isNotEmpty()
        ) {
            "A diagnostic origin is either fully collected or explicitly skipped."
        }
    }
}
