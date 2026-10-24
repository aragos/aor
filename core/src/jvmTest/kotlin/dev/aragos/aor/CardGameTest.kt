package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class CardGameTest {
    private val p1 = cardPlayerOne
    private val p2 = cardPlayerTwo
    private val p3 = cardPlayerThree

    @Test
    fun playingACommodityPaysItsOwnersAndConsumesOnlyOneAdjustment() {
        val initial =
            running("silk")
                .copy(
                    adjustments =
                        mapOf(
                            Commodity.SILK to
                                listOf(
                                    CommodityAdjustment.SHORTAGE,
                                    CommodityAdjustment.SHORTAGE,
                                )
                        )
                )
                .withMarker("salonika", p2)

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p2).cash).isEqualTo(72)
        assertThat(played.adjustments[Commodity.SILK]).containsExactly(CommodityAdjustment.SHORTAGE)
        assertThat(played.playerState(p1).hand.map { card -> card.definition.id })
            .doesNotContain("silk")
    }

    @Test
    fun anotherPlayerCannotPlayTheActivePlayersCard() {
        val initial = running("silk")

        val failure = assertFailsWith<RuleViolation> { initial.play("p2: play hand-1") }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun civilWarSuspendsTheOuterActorForTheVictimsChoice() {
        val initial = running("civil-war")

        val played = initial.play("p1: play hand-1 player p2")

        assertThat(played.flow.actor).isEqualTo(p1)
        assertThat(played.flow.decisions.last()).isEqualTo(CardDecision.CivilWarLoss(p2))
        assertThat(played.playerState(p2).misery).isEqualTo(MiseryStep.TEN)
    }

    @Test
    fun aCivilWarReplyReturnsToTheSameCardRound() {
        val awaiting = running("civil-war").play("p1: play hand-1 player p2")

        val replied = awaiting.play("p2: civil-war-loss tokens")

        assertThat(replied.flow.actor).isEqualTo(p1)
        assertThat(replied.flow.decisions).isEmpty()
        assertThat(replied.playerState(p2).poolTokens).isEqualTo(5)
    }

    @Test
    fun onlyTheVictimCanAnswerCivilWar() {
        val awaiting = running("civil-war").play("p1: play hand-1 player p2")

        val failure = assertFailsWith<RuleViolation> { awaiting.play("p3: civil-war-loss cash") }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun civilWarRecordsTheDelayedExpansionPenalty() {
        val played = running("civil-war").play("p1: play hand-1 player p2")

        assertThat(played.effects.civilWarVictims).containsExactly(p2)
    }

    @Test
    fun poolReplacementPrecedesTheCivilWarTokenHalf() {
        val initial =
            running("civil-war").withMarker("genoa", p2).withPlayer(p2) { player ->
                player.copy(stockTokens = 0, poolTokens = 5)
            }
        val awaiting = initial.play("p1: play hand-1 player p2", "p2: replace-event-token pool")

        val replied = awaiting.play("p2: civil-war-loss tokens")

        assertThat(replied.playerState(p2).poolTokens).isEqualTo(2)
        assertThat(replied.playerState(p2).stockTokens).isEqualTo(2)
        assertThat(replied.board.pieces[ProvinceId("genoa")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
    }

    @Test
    fun anEventVictimMayDeclineSpendingAPoolToken() {
        val initial =
            running("pirates-vikings").withMarker("tunis", p2).withPlayer(p2) { player ->
                player.copy(stockTokens = 0)
            }
        val awaiting = initial.play("p1: play hand-1 province tunis")

        val replied = awaiting.play("p2: replace-event-token none")

        assertThat(replied.board.pieces[ProvinceId("tunis")]).isNull()
        assertThat(replied.playerState(p2).poolTokens).isEqualTo(10)
    }

    @Test
    fun crusadesCreatesAColouredExpansionBase() {
        val played = running("the-crusades").play("p1: play hand-1 province acre")

        assertThat(played.board.pieces[ProvinceId("acre")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(played.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
    }

    @Test
    fun warRequestsAnExplicitRecordedPairOfDice() {
        val played = running("war").play("p1: play hand-1 player p2")

        assertThat(played.prompts().single())
            .isEqualTo(Prompt.System(SystemNeed.GameRoll(GameRollKind.WAR, 2)))
    }

    @Test
    fun aWarVictorySuspendsForTheLosersProvinceChoice() {
        val initial = running("war").withMarker("tunis", p2).withMarker("suez", p2)

        val rolled = initial.play("p1: play hand-1 player p2", "!roll war 4 3")

        val decision = rolled.flow.decisions.last() as CardDecision.WarSurrender
        assertThat(decision.player).isEqualTo(p2)
        assertThat(decision.victory.requiredTransfers).isEqualTo(1)
    }

    @Test
    fun aWarSurrenderTransfersTheChosenColouredMarkerAndAppliesMisery() {
        val initial = running("war").withMarker("tunis", p2).withMarker("suez", p2)
        val awaiting = initial.play("p1: play hand-1 player p2", "!roll war 4 3")

        val ceded = awaiting.play("p2: cede-war tunis")

        assertThat(ceded.board.pieces[ProvinceId("tunis")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(ceded.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
        assertThat(ceded.playerState(p2).misery).isEqualTo(MiseryStep.TWENTY)
        assertThat(ceded.flow.actor).isEqualTo(p1)
    }

    @Test
    fun warWithoutEnoughProvincesAddsTheShortfallToMisery() {
        val initial = running("war").withMarker("tunis", p2)
        val awaiting = initial.play("p1: play hand-1 player p2", "!roll war 6 1")

        val ceded = awaiting.play("p2: cede-war tunis")

        assertThat(ceded.playerState(p2).misery).isEqualTo(MiseryStep.SIXTY)
    }

    @Test
    fun aTiedWarIsNotRolledTwiceInTheSamePlayersRound() {
        val tied = running("war").play("p1: play hand-1 player p2", "!roll war 3 3")

        val prepared = CardGame.prepareRound(tied)

        assertThat(prepared.flow.decisions).isEmpty()
        assertThat(prepared.activeWar?.ties).isEqualTo(1)
    }

    @Test
    fun anEmptyHandStillHostsTheNextWarReroll() {
        val tied = running("war").play("p1: play hand-1 player p2", "!roll war 3 3")
        val nextRound =
            tied.copy(flow = tied.flow.copy(actor = p2, cursor = 1)).withPlayer(p2) { player ->
                player.copy(hand = emptyList())
            }

        val prepared = CardGame.prepareRound(nextRound)

        assertThat(prepared.flow.decisions.last()).isInstanceOf(CardDecision.WarRoll::class.java)
    }

    @Test
    fun aWarParticipantMayPlayMilitaryCardsBeforeTheReroll() {
        val tied = running("war").play("p1: play hand-1 player p2", "!roll war 3 3")
        val nextRound =
            tied.copy(flow = tied.flow.copy(actor = p2, cursor = 1)).withPlayer(p2) { player ->
                player.copy(hand = listOf(hand("hand-2", "armor"), hand("hand-5", "stone-2")))
            }
        val preparing = CardGame.prepareRound(nextRound)

        val armed = preparing.play("p2: play hand-2", "p2: pass")

        assertThat(armed.effects.military).containsExactly(CardMilitaryPlay(p2, EventId.ARMOR))
        assertThat(armed.flow.decisions.last()).isInstanceOf(CardDecision.WarRoll::class.java)
    }

    @Test
    fun aWarRerollMustPrecedeNonMilitaryCards() {
        val state = running("armor").copy(activeWar = WarState(p2, p1, startedTurn = 1))
        val preparing = CardGame.prepareRound(state)

        val failure = assertFailsWith<CardRuleViolation> { preparing.play("p1: play hand-4") }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_CHOICE)
    }

    @Test
    fun aWarFromAnEarlierTurnUsesTheNewTurnsMilitaryState() {
        val state =
            running("stone-1")
                .copy(
                    turnNumber = 2,
                    activeWar = WarState(p1, p2, startedTurn = 1, ties = 2),
                )
        val preparing = CardGame.prepareRound(state)

        val rolled = preparing.play("!roll war 3 3")

        assertThat(rolled.activeWar?.ties).isEqualTo(3)
    }

    @Test
    fun finalCivilWarDoesNotAskForAZeroLossChoice() {
        val initial = running("civil-war").copy(stage = Stage.FINAL_CARD_PLAY)

        val played = initial.play("p1: play hand-1 player p2")

        assertThat(played.flow.decisions).isEmpty()
        assertThat(played.playerState(p2).cash).isEqualTo(40)
        assertThat(played.playerState(p2).poolTokens).isEqualTo(10)
    }

    @Test
    fun aLeaderPaysItsImmediateRebateAndDebitsProtectionEscrow() {
        val initial =
            running("rashid-ad-din")
                .copy(
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        p2,
                                        CardLeaderRole.DISPLAY,
                                    )
                                )
                        )
                )
                .withPlayer(p1) { player ->
                    player.copy(
                        advances =
                            PlayerAdvances(
                                listOf(
                                    AdvanceAcquisition(AdvanceId.WRITTEN_RECORD, 1),
                                    AdvanceAcquisition(AdvanceId.PRINTED_WORD, 1),
                                )
                            )
                    )
                }

        val played = initial.play("p1: play hand-1 protect 7")

        assertThat(played.playerState(p1).cash).isEqualTo(43)
        assertThat(played.effects.leaders.last().protectionCash).isEqualTo(7)
    }

    @Test
    fun patronageTransfersProtectionMoneyWithoutChargingTheBankEscrowAgain() {
        val initial =
            running("stone-1")
                .copy(
                    stage = Stage.PURCHASE,
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            purchaseStep = PurchaseStep.ADVANCES,
                        ),
                    effects =
                        CardTurnEffects(
                            leaders =
                                listOf(
                                    CardLeaderInPlay(
                                        LeaderId.CHARLEMAGNE,
                                        p3,
                                        CardLeaderRole.DISPLAY,
                                    ),
                                    CardLeaderInPlay(
                                        LeaderId.RASHID_AD_DIN,
                                        p2,
                                        CardLeaderRole.EXPOSED,
                                        protectionCash = 7,
                                    ),
                                )
                        ),
                )
                .withPlayer(p1) { player ->
                    player.copy(
                        advances =
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.PATRONAGE, 1)))
                    )
                }

        val claimed = initial.play("p1: patronize rashid-ad-din")

        assertThat(claimed.playerState(p1).cash).isEqualTo(33)
        assertThat(claimed.playerState(p2).cash).isEqualTo(47)
        assertThat(claimed.purchaseContext(p1).leaderDiscounts).hasSize(2)
    }

    @Test
    fun recordedCardActionsReplayToIdenticalDecisionsAndResources() {
        val initial = running("war").withMarker("tunis", p2)
        val lines = arrayOf("p1: play hand-1 player p2", "!roll war 4 3", "p2: cede-war tunis")

        val first = initial.play(*lines)
        val replayed = initial.play(*lines)

        assertThat(replayed).isEqualTo(first)
    }

    @Test
    fun finalAlchemyWithOnlyProtectedTargetsCanBePlayedWithoutEffect() {
        val initial =
            running("alchemists-gold")
                .copy(
                    stage = Stage.FINAL_CARD_PLAY,
                    effects = CardTurnEffects(enlightenedRulers = setOf(p1)),
                )
                .withPlayer(p2) { player ->
                    player.copy(
                        advances =
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.LAWS_OF_MATTER, 1)))
                    )
                }
                .withPlayer(p3) { player ->
                    player.copy(
                        advances =
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.LAWS_OF_MATTER, 1)))
                    )
                }

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p1).hand.map { card -> card.definition.id })
            .doesNotContain("alchemists-gold")
        assertThat(played.players.values.map(PlayerState::cash)).containsExactly(40, 40, 40)
    }

    @Test
    fun finalPiratesWithNoMarkersCanBePlayedWithoutEffect() {
        val initial = running("pirates-vikings").copy(stage = Stage.FINAL_CARD_PLAY)

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p1).hand.map { card -> card.definition.id })
            .doesNotContain("pirates-vikings")
        assertThat(played.board.pieces).isEmpty()
    }

    @Test
    fun finalRebellionWithOnlyForeignCapitalMarkersCanBePlayedWithoutEffect() {
        val initial =
            running("rebellion")
                .copy(stage = Stage.FINAL_CARD_PLAY)
                .withMarker("genoa", p2)
                .withMarker("barcelona", p3)

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p1).hand.map { card -> card.definition.id })
            .doesNotContain("rebellion")
        assertThat(played.board).isEqualTo(initial.board)
    }

    @Test
    fun noEffectPlayIsRejectedWhenALegalTargetExists() {
        val initial = running("pirates-vikings").withMarker("tunis", p2)

        val failure = assertFailsWith<CardRuleViolation> { initial.play("p1: play hand-1") }

        assertThat(failure.code).isEqualTo(CardRuleCode.INVALID_CHOICE)
    }

    @Test
    fun aMilitaryWarTieWinAppliesNormalMiseryWithoutTakingMarkers() {
        val initial =
            running("war")
                .copy(
                    effects =
                        CardTurnEffects(military = listOf(CardMilitaryPlay(p1, EventId.ARMOR)))
                )

        val rolled = initial.play("p1: play hand-1 player p2", "!roll war 2 3")

        assertThat(rolled.activeWar).isNull()
        assertThat(rolled.flow.decisions).isEmpty()
        assertThat(rolled.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
        assertThat(rolled.playerState(p2).misery).isEqualTo(MiseryStep.TWENTY)
    }

    private fun running(card: String): GameState {
        val header =
            GameHeader(listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three")), p1)
        return GameState(
            header = header,
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(order = listOf(p1, p2, p3)),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            players =
                header.players.associate { player ->
                    val capital =
                        when (player.id) {
                            p1 -> "venice"
                            p2 -> "genoa"
                            else -> "barcelona"
                        }
                    player.id to
                        PlayerState(
                            player = player,
                            capital = capital,
                            stockTokens = 26,
                            poolTokens = 10,
                            hand =
                                when (player.id) {
                                    p1 -> listOf(hand("hand-1", card), hand("hand-4", "fur"))
                                    p2 -> listOf(hand("hand-2", "stone-2"))
                                    else -> listOf(hand("hand-3", "wool-2"))
                                },
                        )
                },
        )
    }

    private fun hand(reference: String, id: String): HandCard =
        HandCard(
            reference,
            CardData.epochOne.single { definition -> definition.id == id },
        )

    private fun GameState.withMarker(province: String, owner: PlayerId): GameState =
        withBoardChange(board.placeMarker(ProvinceId(province), owner))

    private fun GameState.withPlayer(
        player: PlayerId,
        update: (PlayerState) -> PlayerState,
    ): GameState = replacePlayer(update(playerState(player)))

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line -> state.step(TextCodec.parse(line)) }
}
