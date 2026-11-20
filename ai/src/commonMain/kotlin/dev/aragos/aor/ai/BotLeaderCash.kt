package dev.aragos.aor.ai

import dev.aragos.aor.CardOfferView
import dev.aragos.aor.PlayerId

/**
 * Immediate owner cash endpoints, excluding the separately valued reduction in hand costs.
 * [protection] is the gross escrow payment, which the simultaneous rebate may fund. The core card
 * preview owns all Printed Word, conditional-credit and Written Record rebate rules.
 */
internal data class BotLeaderCash(
    val before: Int,
    val rebate: Int,
    val protection: Int,
) {
    init {
        require(before >= 0 && rebate >= 0 && protection >= 0) {
            "Leader cash and its rebate and protection amounts must be nonnegative."
        }
        require(before.toLong() + rebate - protection in 0L..Int.MAX_VALUE.toLong()) {
            "A leader's projected rebate and protection require a valid remaining cash balance."
        }
    }

    val after: Int
        get() = (before.toLong() + rebate - protection).toInt()

    val change: Int
        get() = after - before

    companion object {
        /** Parameterized protection choices share the ordinary play's authoritative receipt. */
        fun from(offer: CardOfferView, seat: PlayerId, cash: Int, protection: Int): BotLeaderCash {
            val rebate =
                offer.choices
                    .firstOrNull { it.command == "play ${offer.reference}" }
                    ?.cashChanges
                    ?.filter { it.player == seat }
                    ?.sumOf { it.after - it.before } ?: 0
            return BotLeaderCash(cash, rebate, protection)
        }
    }
}
