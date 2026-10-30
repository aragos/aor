package dev.aragos.aor

/**
 * Optional purchase quote also supports waiting preauthorization; actor eligibility stays separate.
 */
data class CardPurchaseOfferView(
    val cost: Int,
    val cashAfter: Int,
    val blockers: List<ViolationCode>,
)

internal object CardPurchases {
    fun offer(state: GameState, player: PlayerState): CardPurchaseOfferView =
        CardPurchaseOfferView(
            EconomyTables.cardPurchaseCost,
            player.cash - EconomyTables.cardPurchaseCost,
            listOfNotNull(
                ViolationCode.INVALID_CARD_PURCHASE.takeIf {
                    !player.advances.owns(AdvanceId.URBAN_ASCENDANCY) ||
                        player.player.id in state.flow.boughtCards ||
                        !state.canDrawCards()
                },
                ViolationCode.INSUFFICIENT_CASH.takeIf {
                    player.cash < EconomyTables.cardPurchaseCost
                },
                ViolationCode.PLAYER_IN_CHAOS.takeIf { player.misery == MiseryStep.CHAOS },
            ),
        )
}
