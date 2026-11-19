package dev.aragos.aor.ai

/** Use the incumbent opening policy after a publicly revealed capital bid at or above the limit. */
data class OpeningAuctionFallback(
    val minimumPaidBid: Int,
    val policy: InitiativePolicyConfig,
) {
    init {
        require(minimumPaidBid in 1..39) {
            "An auction fallback requires a paid capital bid between 1 and 39 dollars."
        }
    }
}
