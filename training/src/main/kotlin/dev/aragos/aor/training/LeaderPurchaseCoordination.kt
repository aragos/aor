package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.AdvanceOfferView
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId

/**
 * One public target, frozen before tails; dollars are actual marginal credit after the price floor.
 */
internal data class LeaderPurchaseTarget(
    val advance: AdvanceId,
    val leaderCardId: String,
    val priceAtRoot: Int,
    val savingAtRoot: Int,
) {
    init {
        require(leaderCardId.isNotBlank() && priceAtRoot >= 0 && savingAtRoot > 0) {
            "A coordinated target needs a named leader, nonnegative price and positive actual saving."
        }
    }

    val command: String
        get() = "buy-advance ${advance.letter}"
}

/**
 * Reuses authoritative actor-view quotes; nominal card discounts never establish an opportunity.
 */
internal object LeaderPurchaseQuotes {
    fun saving(offer: AdvanceOfferView, card: String): Int {
        val quote = offer.priceBreakdown ?: return 0
        val without = quote.copy(leaders = quote.leaders.filter { it.leaderCardId != card })
        return without.price - quote.price
    }

    fun target(view: GameView, card: String): LeaderPurchaseTarget? =
        view.advanceOffers
            .filter { it.unavailableReason == null && it.price != null && saving(it, card) > 0 }
            .map {
                LeaderPurchaseTarget(
                    it.advance,
                    card,
                    requireNotNull(it.price) {
                        "Eligible advance requires its authoritative price."
                    },
                    saving(it, card),
                )
            }
            .sortedWith(
                compareByDescending<LeaderPurchaseTarget> { it.savingAtRoot }
                    .thenBy { it.priceAtRoot }
                    .thenBy { it.advance.letter }
            )
            .firstOrNull()
}

/** One current-turn commitment; all other choices delegate to the exact existing controller. */
internal class LeaderPurchaseCoordination(
    private val actor: PlayerId,
    private val turn: Int,
    private val target: LeaderPurchaseTarget,
    private val previous: SimulationController,
) : SimulationController {
    private var ended: String? = null
    private val decisions = mutableListOf<Map<String, Any?>>()

    override fun controls(decision: String): Boolean = previous.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        val ordinary = previous.choose(decision)
        if (ended != null || decision.observation.playerId != actor.value) return ordinary
        if (decision.view.turnNumber != turn) {
            ended = "turn-expired"
            return ordinary
        }
        if (decision.observation.decision !in setOf("purchase-shipping", "purchase-advances")) {
            return ordinary
        }
        val offer = decision.view.advanceOffers.firstOrNull { it.advance == target.advance }
        val saving = offer?.let { LeaderPurchaseQuotes.saving(it, target.leaderCardId) } ?: 0
        val reason =
            when {
                offer == null -> "target-no-longer-unowned"
                offer.unavailableReason != null -> offer.unavailableReason
                saving <= 0 -> "leader-credit-expired"
                else -> null
            }
        var command = ordinary
        if (reason != null) {
            ended = reason
        } else {
            val price = requireNotNull(offer?.price) { "An eligible target requires its price." }
            if (decision.observation.decision == "purchase-shipping") {
                if (ordinary == "upgrade-ships") {
                    val cashAfter =
                        requireNotNull(decision.view.shippingOffer?.cashAfter) {
                            "A selected shipping upgrade requires its authoritative cash endpoint."
                        }
                    if (cashAfter < price) {
                        require(decision.observation.candidates.any { it.command == "pass" }) {
                            "Shipping budget reservation requires an offered pass."
                        }
                        command = "pass"
                    }
                }
            } else if (decision.observation.candidates.any { it.command == target.command }) {
                command = target.command
                ended = "target-purchased"
            } else {
                ended = "target-command-not-offered"
            }
        }
        decisions.add(
            mapOf(
                "decision" to decision.number,
                "phase" to decision.observation.decision,
                "cash" to decision.observation.availableCash,
                "targetPrice" to offer?.price,
                "actualLeaderSaving" to saving,
                "ordinaryCommand" to ordinary,
                "command" to command,
                "shippingCashAfter" to decision.view.shippingOffer?.cashAfter,
                "ended" to ended,
            )
        )
        return command
    }

    fun receipt(): Map<String, Any?> =
        mapOf(
            "actor" to actor.value,
            "turn" to turn,
            "target" to target,
            "ended" to (ended ?: "purchase-not-reached"),
            "decisions" to decisions.toList(),
        )
}
