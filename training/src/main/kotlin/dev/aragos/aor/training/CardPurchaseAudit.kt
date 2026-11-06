package dev.aragos.aor.training

import dev.aragos.aor.CardCommand
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/**
 * Quoted dollar credits; marginal saving preserves other credits and the authoritative price floor.
 */
internal data class CardPurchaseCredit(
    val leaderCardId: String,
    val baseAmount: Int,
    val writtenRecordBonus: Int,
    val marginalPriceSaving: Int,
)

/**
 * Public continuation evidence only; never part of a card observation or numerical training row.
 */
internal data class CardPurchaseAudit(
    val number: Int,
    val turn: Int,
    val decision: String,
    val command: String,
    val quotedPrice: Int,
    val leaderCredits: List<CardPurchaseCredit>,
) {
    companion object {
        fun from(input: SimulationDecision, text: String): CardPurchaseAudit {
            val command = TextCodec.parseCommand(text)
            val view = input.view
            val advance =
                (command as? TurnCommand.BuyAdvance)?.let { purchase ->
                    val definition =
                        requireNotNull(view.catalog) {
                                "Purchase audit requires the public catalog."
                            }
                            .advances
                            .single { it.letter == purchase.letter }
                    view.advanceOffers.single { it.advance == definition.id }
                }
            val price =
                when (command) {
                    TurnCommand.Pass -> 0
                    TurnCommand.UpgradeShips ->
                        requireNotNull(view.shippingOffer) { "Shipping audit requires its quote." }
                            .cost
                    is TurnCommand.BuyAdvance ->
                        requireNotNull(advance?.price) { "Advance audit requires its quote." }
                    is CardCommand.PatronizeLeader ->
                        view.patronageOffers.single { it.command == text }.cost
                    else -> error("Unsupported purchase audit command: $text.")
                }
            val quote = advance?.priceBreakdown
            val credits =
                quote
                    ?.let { priceQuote ->
                        priceQuote.leaders.map { credit ->
                            CardPurchaseCredit(
                                credit.leaderCardId,
                                credit.baseAmount,
                                credit.writtenRecordBonus,
                                priceQuote
                                    .copy(
                                        leaders =
                                            priceQuote.leaders.filterNot {
                                                it.leaderCardId == credit.leaderCardId
                                            }
                                    )
                                    .price - priceQuote.price,
                            )
                        }
                    }
                    .orEmpty()
            return CardPurchaseAudit(
                input.number,
                requireNotNull(view.turnNumber) { "Purchase audit requires its game turn." },
                input.observation.decision,
                text,
                price,
                credits,
            )
        }
    }
}
