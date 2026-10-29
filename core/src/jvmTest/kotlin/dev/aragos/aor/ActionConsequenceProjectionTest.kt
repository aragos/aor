package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class ActionConsequenceProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun expansionCardQuoteUsesTheSharedPhasePurchaseCount() {
        val before =
            state()
                .copy(stage = Stage.EXPANSION, flow = TurnFlow(actor = p1, expansionCardCount = 2))
        val cost = before.viewForPlayer(p1).expansionCardCost

        val after = BoardGame.buyExpansionCard(before, p1)

        assertThat(cost).isEqualTo(9)
        assertThat(before.playerState(p1).poolTokens - after.playerState(p1).poolTokens)
            .isEqualTo(cost)
        assertThat(before.viewForPlayer(p2).expansionCardCost).isNull()
    }

    @Test
    fun advanceBreakdownMatchesTheChargedQuoteAndMiseryTransition() {
        val before =
            state(
                    advances =
                        listOf(
                            AdvanceId.THE_HEAVENS,
                            AdvanceId.INSTITUTIONAL_RESEARCH,
                            AdvanceId.WRITTEN_RECORD,
                        )
                )
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.ANDREAS_VESALIUS,
                                        p1,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        )
                )
        val offer =
            before.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.HUMAN_BODY }
        val quote = offer.priceBreakdown

        val after = TurnCommand.BuyAdvance(AdvanceId.HUMAN_BODY.letter).applyTo(before, p1)

        assertThat(quote?.baseCost).isEqualTo(60)
        assertThat(quote?.categoryCredit).isEqualTo(20)
        assertThat(quote?.institutionalResearchCredit).isEqualTo(10)
        assertThat(quote?.leaders)
            .containsExactly(AppliedLeaderDiscount("andreas-vesalius", 20, 10))
        assertThat(quote?.price).isEqualTo(before.playerState(p1).cash - after.playerState(p1).cash)
        assertThat(offer.miseryChanges).containsExactly(MiseryChangeView(p1, 100, 90))
        assertThat(after.playerState(p1).misery).isEqualTo(quote?.miseryAfter)
        assertThat(after.playerState(p2).misery).isEqualTo(before.playerState(p2).misery)
    }

    @Test
    fun categoryCreditsExcludeAdvancesBoughtDuringTheCurrentTurn() {
        val before = state()
        val player =
            before
                .playerState(p1)
                .copy(
                    advances = PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.THE_HEAVENS, 2)))
                )
        val held = before.copy(players = before.players + (p1 to player))

        val offer =
            held.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.HUMAN_BODY }

        assertThat(offer.priceBreakdown?.categoryCredit).isEqualTo(0)
        assertThat(offer.price).isEqualTo(60)
    }

    @Test
    fun unaffordableAdvanceStillProjectsItsKnownMisery() {
        val initial = state()
        val before =
            initial.copy(players = initial.players + (p1 to initial.playerState(p1).copy(cash = 0)))

        val offer =
            before.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.HUMAN_BODY }

        assertThat(offer.unavailableReason).isEqualTo(ViolationCode.INSUFFICIENT_CASH.name)
        assertThat(offer.priceBreakdown?.price).isEqualTo(60)
        assertThat(offer.miseryChanges).containsExactly(MiseryChangeView(p1, 100, 90))
    }

    @Test
    fun religionPurchasePreviewsChaosAsATerminalBox() {
        val before = state(misery = MiseryStep.THOUSAND)
        val offer =
            before.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.PATRONAGE }

        val after = TurnCommand.BuyAdvance(AdvanceId.PATRONAGE.letter).applyTo(before, p1)

        assertThat(offer.miseryChanges).containsExactly(MiseryChangeView(p1, 1000, null))
        assertThat(after.playerState(p1).misery).isEqualTo(MiseryStep.CHAOS)
    }

    @Test
    fun anAdvanceWithoutMiseryDoesNotInventAMovement() {
        val before = state(advances = listOf(AdvanceId.WRITTEN_RECORD))
        val offer =
            before.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.PRINTED_WORD }

        val after = TurnCommand.BuyAdvance(AdvanceId.PRINTED_WORD.letter).applyTo(before, p1)

        assertThat(offer.miseryChanges).isEmpty()
        assertThat(after.playerState(p1).misery).isEqualTo(before.playerState(p1).misery)
    }

    @Test
    fun reliefClampedAtZeroDoesNotInventAMiseryMovement() {
        val before = state(misery = MiseryStep.ZERO)

        val offer =
            before.viewForPlayer(p1).advanceOffers.single { it.advance == AdvanceId.HUMAN_BODY }

        assertThat(offer.miseryChanges).isEmpty()
        assertThat(offer.priceBreakdown?.miseryAfter).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun faminePreviewsEveryAffectedPlayerUsingGrainAgricultureAndPrintedBoxes() {
        val initial = state(card = "famine")
        val grain = initial.catalog.provinces.first { Commodity.GRAIN in it.commodities }.id
        val before =
            initial.copy(
                board = initial.board.placeMarker(grain, p1).board,
                players =
                    initial.players +
                        (p2 to
                            initial
                                .playerState(p2)
                                .copy(
                                    advances =
                                        PlayerAdvances(
                                            listOf(
                                                AdvanceAcquisition(
                                                    AdvanceId.IMPROVED_AGRICULTURE,
                                                    1,
                                                )
                                            )
                                        )
                                )) +
                        (p3 to initial.playerState(p3).copy(misery = MiseryStep.CHAOS)),
            )
        val offer = before.viewForPlayer(p1).cardOffers.single()

        val after = CardGame.play(before, p1, "hand-1", CardChoice.None)

        assertThat(offer.miseryChanges)
            .containsExactly(MiseryChangeView(p1, 100, 175), MiseryChangeView(p2, 100, 175))
        assertThat(offer.choices.single().miseryChanges).isEqualTo(offer.miseryChanges)
        assertThat(after.playerState(p1).misery).isEqualTo(MiseryStep.HUNDRED_SEVENTY_FIVE)
        assertThat(after.playerState(p2).misery).isEqualTo(MiseryStep.HUNDRED_SEVENTY_FIVE)
        assertThat(before.playerState(p1).misery).isEqualTo(MiseryStep.HUNDRED)
    }

    @Test
    fun civilWarMiseryPreviewFollowsTheChosenVictim() {
        val initial = state(card = "civil-war")
        val before =
            initial.copy(
                players =
                    initial.players +
                        (p2 to initial.playerState(p2).copy(misery = MiseryStep.THOUSAND))
            )
        val offer = before.viewForPlayer(p1).cardOffers.single()
        val choice = offer.choices.single { it.value == p2.value }

        val after = CardGame.play(before, p1, "hand-1", CardChoice.PlayerChoice(p2))

        assertThat(offer.miseryChanges).isEmpty()
        assertThat(choice.miseryChanges).containsExactly(MiseryChangeView(p2, 1000, null))
        assertThat(after.playerState(p2).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(after.playerState(p1).misery).isEqualTo(before.playerState(p1).misery)
    }

    @Test
    fun crusadesPreviewsGuaranteedMiseryBeforePhysicalMarkerChoices() {
        val initial = state(card = "the-crusades")
        val destination = ProvinceId("acre")
        val board =
            initial.catalog.provinces
                .filter { !it.isSatellite && it.id != destination }
                .take(25)
                .fold(initial.board) { current, province ->
                    current.placeMarker(province.id, p1).board
                }
        val before = initial.copy(board = board)
        val offer = before.viewForPlayer(p1).cardOffers.single()
        val choice = offer.choices.single { it.value == destination.value }
        val awaitingMarker =
            CardGame.play(before, p1, "hand-1", CardChoice.ProvinceChoice(destination))

        val after = CardCommand.ForgoMarker.applyTo(awaitingMarker, p1)

        assertThat(offer.miseryChanges).containsExactly(MiseryChangeView(p1, 100, 125))
        assertThat(choice.miseryChanges).isEqualTo(offer.miseryChanges)
        assertThat(after.playerState(p1).misery).isEqualTo(MiseryStep.HUNDRED_TWENTY_FIVE)
    }

    @Test
    fun warNamesAffectedPlayersWithoutInventingADiceOutcome() {
        val before = state(card = "war")

        val choice =
            before.viewForPlayer(p1).cardOffers.single().choices.single { it.value == p2.value }

        assertThat(choice.miseryChanges).isEmpty()
        assertThat(choice.miseryUncertainPlayers).containsExactly(p1, p2).inOrder()
    }

    @Test
    fun opponentsPrivateHandsDoNotChangePublicMiseryPreviews() {
        val before = state(card = "famine")
        val hidden =
            before.copy(
                players =
                    before.players +
                        (p2 to
                            before
                                .playerState(p2)
                                .copy(
                                    hand =
                                        listOf(
                                            HandCard(
                                                "secret-reference",
                                                CardData.definition("charlemagne"),
                                            )
                                        )
                                ))
            )

        val original = before.viewForPlayer(p1).cardOffers
        val changed = hidden.viewForPlayer(p1).cardOffers

        assertThat(changed).isEqualTo(original)
        assertThat(hidden.viewForPlayer(p2).cardOffers.single().reference)
            .isEqualTo("secret-reference")
    }

    private fun state(
        card: String? = null,
        misery: MiseryStep = MiseryStep.HUNDRED,
        advances: List<AdvanceId> = emptyList(),
    ): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            turnNumber = 2,
            stage = if (card == null) Stage.PURCHASE else Stage.PLAY_CARDS,
            deck = listOf("stone-1"),
            flow = TurnFlow(actor = p1, purchaseStep = PurchaseStep.ADVANCES, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                cash = 1000,
                                poolTokens = 20,
                                stockTokens = 16,
                                misery = misery,
                                advances =
                                    PlayerAdvances(
                                        (if (player.id == p1) advances else emptyList()).map {
                                            AdvanceAcquisition(it, 1)
                                        }
                                    ),
                                hand =
                                    if (player.id == p1 && card != null)
                                        listOf(HandCard("hand-1", CardData.definition(card)))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
        )
    }
}
