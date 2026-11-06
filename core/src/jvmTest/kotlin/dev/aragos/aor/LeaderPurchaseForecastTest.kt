package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class LeaderPurchaseForecastTest {
    private val owner = PlayerId("p1")
    private val ids = listOf(owner, PlayerId("p2"), PlayerId("p3"))

    @Test
    fun aLeaderExposesTheSameShippingQuoteAsTheFuturePurchaseStage() {
        val state = fixture("charlemagne")
        val purchase = state.copy(stage = Stage.PURCHASE)

        val forecast = state.viewForDecision(owner).purchaseShippingForecast
        val actual = purchase.viewForDecision(owner).shippingOffer

        assertThat(forecast).isEqualTo(actual)
        assertThat(state.viewForDecision(owner).shippingOffer).isNull()
        assertThat(state.legalChoices(owner).commands).doesNotContain("upgrade-ships")
    }

    @Test
    fun commodityOnlyCardDecisionsDoNotBuildUnusedPurchaseForecasts() {
        val state = fixture("wool-1")

        val decision = state.viewForDecision(owner)
        val ordinary = state.viewForPlayer(owner)

        assertThat(decision.advanceOffers).isEmpty()
        assertThat(decision.purchaseShippingForecast).isNull()
        assertThat(ordinary.advanceOffers).isNotEmpty()
    }

    @Test
    fun aPreviouslyPurchasedUpgradeStaysBlockedInTheInformationalQuote() {
        val initial = fixture("charlemagne")
        val state =
            initial.copy(
                players =
                    initial.players +
                        (owner to initial.playerState(owner).copy(lastShipUpgradeTurn = 2))
            )

        val forecast = state.viewForDecision(owner).purchaseShippingForecast

        assertThat(forecast?.blockers).containsExactly(ViolationCode.SHIP_UPGRADE_ALREADY_BOUGHT)
    }

    private fun fixture(card: String): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        val board =
            ids.zip(capitals).fold(BoardState()) { current, (id, capital) ->
                current.placeMarker(ProvinceId(capital), id).board
            }
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, owner),
            stage = Stage.PLAY_CARDS,
            turnNumber = 2,
            board = board,
            turnOrderSelection = TurnOrderSelection(ids),
            players =
                ids.mapIndexed { index, id ->
                        id to
                            PlayerState(
                                player = Player(id, id.value),
                                capital = capitals[index],
                                cash = 40,
                                hand =
                                    if (id == owner)
                                        listOf(HandCard("held", CardData.definition(card)))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = owner, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
