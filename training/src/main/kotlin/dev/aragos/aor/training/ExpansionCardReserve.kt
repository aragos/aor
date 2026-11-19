package dev.aragos.aor.training

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameView
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotEconomicFeature
import dev.aragos.aor.ai.BotObservation

/** A legal cheaper full market that leaves enough expansion tokens for the offered card. */
internal data class ExpansionCardReserve(
    val alternative: String,
    val facts: Map<String, Any>,
) {
    companion object {
        fun from(
            view: GameView,
            observation: BotObservation,
            command: String,
        ): ExpansionCardReserve? {
            if (
                view.endingTriggered ||
                    observation.candidates.none {
                        it.command == BoardCommand.BuyExpansionCard.toText()
                    }
            ) {
                return null
            }
            val price =
                checkNotNull(view.expansionCardCost) {
                    "An offered expansion card must have its authoritative token price."
                }
            val pool = observation.availableTokens
            val offers = view.expansionOffers.associateBy { it.destination }
            val ordinary = observation.candidates.single { it.command == command }
            if (ordinary.tokenCost + price <= pool) return null
            val destination =
                when (val action = TextCodec.parseCommand(command)) {
                    is BoardCommand.Place -> action.province
                    is BoardCommand.Attack -> action.province
                    else -> return null
                }
            val expensive = offers[destination] ?: return null
            if (!expensive.establishesMarker || expensive.requiredTokens != ordinary.tokenCost) {
                return null
            }

            val candidates = observation.candidates.associateBy { it.command }
            val inexpensive =
                view.expansionOffers
                    .mapNotNull { offer ->
                        if (
                            offer.primaryAction != ExpansionAction.PLACE ||
                                !offer.establishesMarker ||
                                offer.uncertain ||
                                offer.blockers.isNotEmpty() ||
                                offer.requiredTokens !in 1..(pool - price)
                        )
                            return@mapNotNull null
                        val place =
                            BoardCommand.Place(offer.requiredTokens, offer.destination).toText()
                        val candidate = candidates[place] ?: return@mapNotNull null
                        Market(
                            command = place,
                            cost = offer.requiredTokens,
                            income = (offer.incomeAfter ?: 0) - (offer.incomeBefore ?: 0),
                            heldGain =
                                candidate.features[BotEconomicFeature.HELD_COMMODITY.key] ?: 0.0,
                        )
                    }
                    .maxWithOrNull(
                        compareBy<Market> { it.income }
                            .thenBy { it.heldGain }
                            .thenByDescending { it.cost }
                            .thenByDescending { it.command }
                    ) ?: return null
            return ExpansionCardReserve(
                inexpensive.command,
                mapOf(
                    "price" to price,
                    "pool" to pool,
                    "ordinaryCommitment" to ordinary.tokenCost,
                    "alternativeCommitment" to inexpensive.cost,
                    "alternativeIncome" to inexpensive.income,
                    "ordinaryAttack" to expensive.uncertain,
                    "ownHandSize" to view.hand.size,
                ),
            )
        }
    }

    private data class Market(
        val command: String,
        val cost: Int,
        val income: Int,
        val heldGain: Double,
    )
}
