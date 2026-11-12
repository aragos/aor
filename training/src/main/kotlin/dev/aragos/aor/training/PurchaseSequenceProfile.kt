package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode

/**
 * Data volume and outer native allowance only. Proposal, eligibility, continuation and return
 * semantics are shared unchanged. An omitted source profile retains the original 32-world wire
 * contract; the larger corpus requires an explicit declaration.
 */
internal enum class PurchaseSequenceProfile(val origins: Int, val maximumHours: Double) {
    PILOT_32(PurchaseSequenceProtocol.ORIGINS, 3.0),
    CORPUS_512_1(512, 4.0);

    val maximumTails: Int
        get() = origins * PurchaseSequenceProtocol.PLANS * 2 * PurchaseSequenceProtocol.STREAMS

    val maximumTerminals: Int
        get() = maximumTails + origins

    fun metadata(): Map<String, String> =
        if (this == PILOT_32) emptyMap() else mapOf("profile" to name)

    companion object {
        fun read(value: JsonNode?): PurchaseSequenceProfile {
            if (value == null) return PILOT_32
            require(value.isTextual && value.asText() == CORPUS_512_1.name) {
                "An explicit teacher profile must be CORPUS_512_1; omit it for the original pilot."
            }
            return CORPUS_512_1
        }
    }
}
