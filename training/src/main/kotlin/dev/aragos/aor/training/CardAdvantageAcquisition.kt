package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/** Root-only collection contract; sampled splits observe one uniformly chosen non-F action. */
internal data class CardRootPairCollection(
    val kind: String,
    val sampledSplits: List<String>,
    val replicates: Int,
) {
    init {
        require(kind == "root-pairs-1") { "Unknown root-pair collection contract." }
        require(
            sampledSplits.distinct() == sampledSplits &&
                sampledSplits.all { split -> AdvantageSplit.entries.any { it.wire == split } }
        ) {
            "Sampled splits must be distinct declared development roles."
        }
        require(replicates in 1..2 && (sampledSplits.isEmpty() || replicates == 1)) {
            "Root pairs allow one or two streams; sampled splits require one stream."
        }
    }

    companion object {
        fun read(node: JsonNode): CardRootPairCollection {
            PurchaseJson.fields(node, setOf("kind", "sampledSplits", "replicates"))
            return CardRootPairCollection(
                PurchaseJson.text(node.required("kind"), "kind"),
                PurchaseJson.strings(node.required("sampledSplits"), "sampledSplits"),
                PurchaseJson.integer(node.required("replicates"), "replicates"),
            )
        }
    }
}

/**
 * Acquisition randomness is separate from proposals, policy choices and hidden future outcomes.
 * [seed] is the original absolute decision seed before the acquisition salt. [probability] is the
 * conditional probability of selecting the recorded non-F member of the already frozen full S. A
 * trivial S has no sampled action and probability one; it remains an explicit collected root.
 */
internal data class CardAdvantageAcquisition(
    val version: String,
    val kind: String,
    val seed: Int,
    val sampledActionIndex: Int?,
    val probability: Double,
) {
    init {
        require(version == VERSION) { "Unsupported card acquisition version." }
        require(kind == "uniform-non-f" || kind == "exhaustive") {
            "Unknown card acquisition kind."
        }
        require(sampledActionIndex == null || sampledActionIndex >= 0) {
            "An acquired action must have a nonnegative offered index."
        }
        require(probability.isFinite() && probability > 0.0 && probability <= 1.0) {
            "Acquisition probability must be finite and in (0, 1]."
        }
        require(kind != "exhaustive" || sampledActionIndex == null && probability == 1.0) {
            "Exhaustive acquisition has no sampled action and probability one."
        }
    }

    fun observedIndices(proposals: List<Int>, reference: Int): List<Int> =
        if (kind == "exhaustive") proposals
        else (listOf(reference) + listOfNotNull(sampledActionIndex)).distinct().sorted()

    companion object {
        const val VERSION = "aor-card-acquisition-1"

        fun select(
            proposals: List<Int>,
            reference: Int,
            botSeed: Int,
            sampled: Boolean,
        ): CardAdvantageAcquisition {
            require(
                proposals.size in 1..4 &&
                    proposals == proposals.distinct().sorted() &&
                    proposals.all { it >= 0 } &&
                    reference in proposals
            ) {
                "Acquisition requires canonical full proposals and their explicit F reference."
            }
            val alternatives = proposals.filter { it != reference }
            val action =
                if (sampled && alternatives.isNotEmpty()) {
                    alternatives[index(botSeed xor 0x5be0cd19, alternatives.size)]
                } else null
            return CardAdvantageAcquisition(
                VERSION,
                if (sampled) "uniform-non-f" else "exhaustive",
                botSeed,
                action,
                if (action == null) 1.0 else 1.0 / alternatives.size,
            )
        }

        /** Counter/Murmur unsigned rejection stream, independently salted and versioned above. */
        private fun index(seed: Int, bound: Int): Int {
            var state = seed
            val limit = 4294967296L - 4294967296L % bound
            while (true) {
                state += -1640531527
                var mixed = state
                mixed = (mixed xor (mixed ushr 16)) * -2048144789
                mixed = (mixed xor (mixed ushr 13)) * -1028477387
                val word = (mixed xor (mixed ushr 16)).toLong() and 0xffffffffL
                if (word < limit) return (word % bound).toInt()
            }
        }
    }
}
