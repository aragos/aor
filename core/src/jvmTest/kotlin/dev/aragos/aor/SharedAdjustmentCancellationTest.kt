package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SharedAdjustmentCancellationTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun goldAndIvorySurplusOfferOneCanonicalCancellation() {
        val state =
            game(
                mapOf(
                    Commodity.IVORY to listOf(CommodityAdjustment.SURPLUS),
                    Commodity.GOLD to listOf(CommodityAdjustment.SURPLUS),
                )
            )

        val choices = state.legalChoices(p1).commands

        assertThat(choices).containsExactly("pass", "cancel-adjustment gold surplus")
    }

    @Test
    fun goldAndIvoryShortageOfferOneCanonicalCancellation() {
        val state =
            game(
                mapOf(
                    Commodity.GOLD to listOf(CommodityAdjustment.SHORTAGE),
                    Commodity.IVORY to listOf(CommodityAdjustment.SHORTAGE),
                )
            )

        val choices = state.legalChoices(p1).commands

        assertThat(choices).containsExactly("pass", "cancel-adjustment gold shortage")
    }

    @Test
    fun sharedCancellationRemovesOneMatchingMarkerFromEachForOnePayment() {
        val before =
            game(
                mapOf(
                    Commodity.GOLD to
                        listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS),
                    Commodity.IVORY to
                        listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS),
                )
            )

        val after = before.step(TextCodec.parse("p1: cancel-adjustment gold surplus"))

        assertThat(after.playerState(p1).cash).isEqualTo(30)
        assertThat(after.adjustments[Commodity.GOLD]).containsExactly(CommodityAdjustment.SURPLUS)
        assertThat(after.adjustments[Commodity.IVORY]).containsExactly(CommodityAdjustment.SURPLUS)
        assertThat(after.legalChoices(p1).commands)
            .containsExactly("pass", "cancel-adjustment gold surplus")
    }

    @Test
    fun extraUnpairedGoldMarkerRemainsCancelableAfterTheSharedMarker() {
        val before =
            game(
                mapOf(
                    Commodity.GOLD to
                        listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS),
                    Commodity.IVORY to listOf(CommodityAdjustment.SURPLUS),
                )
            )
        val shared = before.step(TextCodec.parse("p1: cancel-adjustment gold surplus"))

        val after = shared.step(TextCodec.parse("p1: cancel-adjustment gold surplus"))

        assertThat(after.playerState(p1).cash).isEqualTo(20)
        assertThat(after.adjustments[Commodity.GOLD]).isEmpty()
        assertThat(after.adjustments[Commodity.IVORY]).isEmpty()
    }

    @Test
    fun playingGoldLeavesAnIvorySingletonCancellation() {
        val initial =
            game(
                    mapOf(
                        Commodity.GOLD to listOf(CommodityAdjustment.SURPLUS),
                        Commodity.IVORY to listOf(CommodityAdjustment.SURPLUS),
                    )
                )
                .copy(stage = Stage.PLAY_CARDS)
        val played = CardGame.play(initial, p1, "dual", CardChoice.CommodityChoice(Commodity.GOLD))
        val before =
            played.copy(stage = Stage.DRAW_CARD, flow = TurnFlow(actor = p1, phaseStarted = true))

        val choices = before.legalChoices(p1).commands
        val after = before.step(TextCodec.parse("p1: cancel-adjustment ivory surplus"))

        assertThat(choices).containsExactly("pass", "cancel-adjustment ivory surplus")
        assertThat(after.playerState(p1).cash).isEqualTo(30)
        assertThat(after.adjustments[Commodity.IVORY]).isEmpty()
    }

    @Test
    fun playingIvoryLeavesAGoldSingletonCancellation() {
        val initial =
            game(
                    mapOf(
                        Commodity.GOLD to listOf(CommodityAdjustment.SHORTAGE),
                        Commodity.IVORY to listOf(CommodityAdjustment.SHORTAGE),
                    )
                )
                .copy(stage = Stage.PLAY_CARDS)
        val played = CardGame.play(initial, p1, "dual", CardChoice.CommodityChoice(Commodity.IVORY))
        val before =
            played.copy(stage = Stage.DRAW_CARD, flow = TurnFlow(actor = p1, phaseStarted = true))

        val choices = before.legalChoices(p1).commands

        assertThat(choices).containsExactly("pass", "cancel-adjustment gold shortage")
    }

    @Test
    fun differentAdjustmentTypesKeepTheirIndependentChoices() {
        val state =
            game(
                mapOf(
                    Commodity.GOLD to listOf(CommodityAdjustment.SHORTAGE),
                    Commodity.IVORY to listOf(CommodityAdjustment.SURPLUS),
                    Commodity.STONE to listOf(CommodityAdjustment.SHORTAGE),
                )
            )

        val choices = state.legalChoices(p1).commands

        assertThat(choices)
            .containsExactly(
                "pass",
                "cancel-adjustment gold shortage",
                "cancel-adjustment ivory surplus",
                "cancel-adjustment stone shortage",
            )
    }

    @Test
    fun sharedCancellationRetainsCashValidation() {
        val state =
            game(
                mapOf(
                    Commodity.GOLD to listOf(CommodityAdjustment.SURPLUS),
                    Commodity.IVORY to listOf(CommodityAdjustment.SURPLUS),
                ),
                cash = 9,
            )

        val choices = state.legalChoices(p1).commands
        val failure =
            assertFailsWith<RuleViolation> {
                state.step(TextCodec.parse("p1: cancel-adjustment gold surplus"))
            }

        assertThat(choices).containsExactly("pass")
        assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_CASH)
    }

    @Test
    fun ivoryTextCommandStillCancelsTheExistingSharedMeaning() {
        val state =
            game(
                mapOf(
                    Commodity.GOLD to
                        listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS),
                    Commodity.IVORY to
                        listOf(CommodityAdjustment.SURPLUS, CommodityAdjustment.SURPLUS),
                )
            )

        val after = state.step(TextCodec.parse("p1: cancel-adjustment ivory surplus"))

        assertThat(after.playerState(p1).cash).isEqualTo(30)
        assertThat(after.adjustments[Commodity.GOLD]).containsExactly(CommodityAdjustment.SURPLUS)
        assertThat(after.adjustments[Commodity.IVORY]).containsExactly(CommodityAdjustment.SURPLUS)
    }

    private fun game(
        adjustments: Map<Commodity, List<CommodityAdjustment>>,
        cash: Int = 40,
    ): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.DRAW_CARD,
            turnNumber = 2,
            adjustments = adjustments,
            deck = listOf("stone-1", "stone-2", "wool-1"),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = cash,
                                hand =
                                    if (player.id == p1)
                                        listOf(HandCard("dual", CardData.definition("ivory-gold")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
        )
    }
}
