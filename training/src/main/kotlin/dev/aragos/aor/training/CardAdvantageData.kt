package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.ai.CardObservation

/** Named development roles; descendants never leave their original world's split. */
internal enum class AdvantageSplit(val wire: String) {
    TRAIN("train"),
    DEVELOPMENT("development"),
    VALIDATION("validation"),
}

/** Eligibility is evaluated only from the ordinary acting player's present observation. */
internal enum class AdvantageCohort(val wire: String) {
    GENERIC("generic"),
    LEADER("leader"),
    PRINTED_WORD_LEADER("printed-word-leader"),
}

/**
 * A prospective exact-epoch request; ordinal counts all own normal card decisions in that epoch.
 */
internal data class CardAdvantageRequest(
    val index: Int,
    val split: String,
    val seed: Int,
    val seat: Int,
    val epoch: Int,
    val cohort: String,
    val ordinal: Int,
    val opponentUsesCandidate: List<Boolean>,
) {
    init {
        require(index >= 0 && seat in 0..3 && epoch in 1..3 && ordinal >= 1) {
            "An advantage request needs a nonnegative index, valid seat/epoch and positive ordinal."
        }
        require(
            AdvantageSplit.entries.any { it.wire == split } &&
                AdvantageCohort.entries.any { it.wire == cohort }
        ) {
            "Advantage request split or cohort is unknown."
        }
        require(opponentUsesCandidate.size == 4 && !opponentUsesCandidate[seat]) {
            "Rival continuation flags require four seats with the controlled seat false."
        }
    }
}

/** Each outcome is evaluated once. Root returns reference the chosen child's identical outcome. */
internal data class CardAdvantageOutcome(
    val reward: Double,
    val winShare: Double,
    val rankReward: Double,
    val scores: List<PilotReplayScore>,
    val gameDecisions: Int,
    val chanceSeed: Long,
    val canonicalSha256: String,
)

internal data class CardAdvantageReturns(val actionIndex: Int, val outcomeIds: List<String>)

/**
 * Separate exact decision states; observation hashes identify JVM JSON bytes, not a Python codec.
 * Schema 1 omits acquisition fields and observes all proposals. Schema 2 keeps the complete
 * proposal set but labels only [observedActionIndices]; omitted actions have unknown returns.
 */
internal data class CardAdvantageGroup(
    val id: String,
    val kind: String,
    val observationSha256: String,
    val number: Int,
    val botSeed: Int,
    val turn: Int,
    val epoch: Int,
    val proposalIndices: List<Int>,
    val referenceIndex: Int,
    val returns: List<CardAdvantageReturns>,
    val parentActionIndex: Int? = null,
    val replicate: Int? = null,
    val continuationIndex: Int? = null,
    val prefixCanonicalSha256: String? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL) val observedActionIndices: List<Int>? = null,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val acquisition: CardAdvantageAcquisition? = null,
) {
    init {
        require((observedActionIndices == null) == (acquisition == null)) {
            "Observed actions and acquisition metadata must be present together."
        }
        if (observedActionIndices != null && acquisition != null) {
            require(
                kind == "root" &&
                    parentActionIndex == null &&
                    replicate == null &&
                    prefixCanonicalSha256 == null
            ) {
                "Explicit acquisition belongs only to an independent root."
            }
            require(
                observedActionIndices == observedActionIndices.distinct().sorted() &&
                    referenceIndex in observedActionIndices &&
                    observedActionIndices.all { it in proposalIndices } &&
                    observedActionIndices ==
                        acquisition.observedIndices(proposalIndices, referenceIndex)
            ) {
                "Observed root actions must preserve their acquisition and full proposal indices."
            }
            require(
                acquisition.seed == botSeed &&
                    returns.map { it.actionIndex } == observedActionIndices
            ) {
                "Observed returns must preserve the root's absolute seed and declared action mask."
            }
        }
    }
}

/** Only complete origins are persisted; hidden authority state and canonical entries are absent. */
internal data class CardAdvantageOrigin(
    val schemaVersion: Int = 1,
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
    val observations: Map<String, CardObservation>,
    val groups: List<CardAdvantageGroup>,
    val outcomes: Map<String, CardAdvantageOutcome>,
)
