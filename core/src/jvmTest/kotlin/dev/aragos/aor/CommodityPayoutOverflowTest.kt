package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CommodityPayoutOverflowTest {
    private val owner = PlayerId("p1")

    @Test
    fun nineWoolMarketsWithIndustryAndAShortagePayBeyondThePrintedBoxes() {
        val initial = woolPayoutState()

        val result = CardRules.commodityPayment(initial.cardContext(), Commodity.WOOL)

        assertThat(result.payments.getValue(owner)).isEqualTo(242)
    }

    @Test
    fun anOverflowPayoutConsumesOnlyOneOfTwoShortages() {
        val initial = woolPayoutState()

        val paid = initial.step(TextCodec.parse("p1: play-card hand-1"))

        assertThat(paid.playerState(owner).cash).isEqualTo(282)
        assertThat(paid.adjustments[Commodity.WOOL]).containsExactly(CommodityAdjustment.SHORTAGE)
        assertThat(paid.playerState(owner).hand).isEmpty()
    }

    private fun woolPayoutState(): GameState {
        val players =
            CapitalData.definitions.mapIndexed { index, capital ->
                val player = Player(PlayerId("p${index + 1}"), capital.id)
                PlayerState(
                    player = player,
                    capital = capital.id,
                    advances =
                        if (player.id == owner) {
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.INDUSTRY, 1)))
                        } else {
                            PlayerAdvances()
                        },
                    hand =
                        if (player.id == owner) {
                            listOf(
                                HandCard(
                                    "hand-1",
                                    CardData.epochOne.single { card -> card.id == "wool-1" },
                                )
                            )
                        } else {
                            emptyList()
                        },
                )
            }
        val initial =
            GameState(
                header = GameHeader(players.map(PlayerState::player), owner),
                stage = Stage.PLAY_CARDS,
                turnNumber = 1,
                turnOrderSelection = TurnOrderSelection(players.map { player -> player.player.id }),
                flow = TurnFlow(actor = owner, phaseStarted = true),
                players = players.associateBy { player -> player.player.id },
                adjustments =
                    mapOf(
                        Commodity.WOOL to
                            listOf(CommodityAdjustment.SHORTAGE, CommodityAdjustment.SHORTAGE)
                    ),
            )
        return ProvinceData.definitions
            .filter { province -> Commodity.WOOL in province.commodities }
            .fold(initial) { state, province ->
                state.withBoardChange(state.board.placeMarker(province.id, owner))
            }
    }
}
