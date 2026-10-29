package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class LaterCardFlowTest {
    private val p1 = cardPlayerOne
    private val p2 = cardPlayerTwo
    private val p3 = cardPlayerThree

    @Test
    fun blackDeathReturnsAllAreaTokensBeforeSpendingReplacementStock() {
        val initial =
            running("black-death").marker("genoa", p2).tokens("florence", p2, 2).player(p2) { owner
                ->
                owner.copy(stockTokens = 0, poolTokens = 34)
            }

        val played = initial.play("p1: play hand-1 area vii")

        assertThat(played.board.pieces[ProvinceId("florence")]).isNull()
        assertThat(played.board.pieces[ProvinceId("genoa")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(played.playerState(p2).stockTokens).isEqualTo(1)
        assertThat(played.playerState(p2).poolTokens).isEqualTo(34)
        assertThat(played.flow.decisions).isEmpty()
        assertThat(played.deckHistory.discarded).containsExactly("black-death")
    }

    @Test
    fun blackDeathRecalculatesStockForEachMarkerAndResumesAfterPoolReplacement() {
        val initial =
            running("black-death").marker("genoa", p2).marker("venice", p2).player(p2) { owner ->
                owner.copy(stockTokens = 1, poolTokens = 1)
            }
        val awaiting = initial.play("p1: play hand-1 area vii")

        val replied = awaiting.play("p2: replace-event-tokens venice")

        assertThat(replied.board.pieces.values.filterIsInstance<ProvincePieces.Marker>()).isEmpty()
        assertThat(replied.board.tokenCount(p2)).isEqualTo(2)
        assertThat(replied.playerState(p2).stockTokens).isEqualTo(0)
        assertThat(replied.playerState(p2).poolTokens).isEqualTo(0)
        assertThat(replied.flow.actor).isEqualTo(p1)
        assertThat(replied.flow.decisions).isEmpty()
    }

    @Test
    fun piratesKeepsTheCardRoundSuspendedUntilBothVictimsAnswer() {
        val initial =
            running("pirates-vikings")
                .marker("genoa", p2)
                .marker("tunis", p3)
                .player(p2) { owner -> owner.copy(stockTokens = 0, poolTokens = 1) }
                .player(p3) { owner -> owner.copy(stockTokens = 0, poolTokens = 1) }
        val awaiting = initial.play("p1: play hand-1 provinces genoa tunis")
        val firstReply = awaiting.play("p2: replace-event-tokens genoa")

        val replied = firstReply.play("p3: replace-event-tokens none")

        assertThat(replied.board.pieces[ProvinceId("tunis")]).isNull()
        assertThat(replied.board.pieces[ProvinceId("genoa")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1))))
        assertThat(replied.flow.actor).isEqualTo(p1)
        assertThat(replied.flow.decisions).isEmpty()
        assertThat(replied.playerState(p2).poolTokens).isEqualTo(0)
        assertThat(replied.playerState(p3).poolTokens).isEqualTo(1)
    }

    @Test
    fun invalidCompoundPiratesPlayPreservesTheHandAndBothMarkers() {
        val initial = running("pirates-vikings").marker("genoa", p2).marker("tunis", p3)

        assertFailsWith<CardRuleViolation> {
            initial.play("p1: play hand-1 provinces genoa genoa")
        }

        assertThat(initial.playerState(p1).hand.map { held -> held.definition.id })
            .contains("pirates-vikings")
        assertThat(initial.board.markerCount(p2)).isEqualTo(1)
        assertThat(initial.board.markerCount(p3)).isEqualTo(1)
        assertThat(initial.deckHistory.discarded).isEmpty()
    }

    @Test
    fun epochThreeStrifeCancelsPapalDecreeAndLeavesItsHeldCardAsABurden() {
        val initial =
            running("religious-strife", "papal-decree")
                .copy(
                    currentEpoch = Epoch.THREE,
                    effects = CardTurnEffects(papalCategory = AdvanceCategory.SCIENCE),
                )

        val played = initial.play("p1: play hand-1")

        assertThat(played.effects.papalCategory).isNull()
        assertThat(played.effects.religiousStrifePlayed).isTrue()
        assertThat(played.voids.papalDecree).isTrue()
        assertThat(CardRules.isBurden(played.cardContext(), "papal-decree")).isTrue()
        assertThat(played.playerState(p1).hand.map { held -> held.definition.id })
            .contains("papal-decree")
    }

    @Test
    fun epochTwoStrifeCancelsOnlyThisTurnsDecree() {
        val initial =
            running("religious-strife", "papal-decree")
                .copy(effects = CardTurnEffects(papalCategory = AdvanceCategory.SCIENCE))
        val strife = initial.play("p1: play hand-1")

        val decree = strife.play("p1: play hand-2 category exploration")

        assertThat(decree.effects.papalCategory).isNull()
        assertThat(decree.voids.papalDecree).isFalse()
        assertThat(
                CardRules.isBurden(
                    decree.copy(effects = CardTurnEffects()).cardContext(),
                    "papal-decree",
                )
            )
            .isFalse()
    }

    @Test
    fun enlightenedRulerPreventsReligiousStrifeMiseryInCommandFlow() {
        val initial =
            running("religious-strife")
                .player(p2) { owner ->
                    owner.copy(advances = advances(AdvanceId.PATRONAGE, AdvanceId.CATHEDRAL))
                }
                .copy(effects = CardTurnEffects(enlightenedRulers = setOf(p2)))

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p2).misery).isEqualTo(MiseryStep.ZERO)
    }

    @Test
    fun religiousStrifeChaosDiscardsTheVictimsHandAndReturnsItsPool() {
        val initial =
            running("religious-strife")
                .player(p2) { owner ->
                    owner.copy(
                        misery = MiseryStep.THOUSAND,
                        stockTokens = 30,
                        poolTokens = 6,
                        advances = advances(AdvanceId.PATRONAGE),
                    )
                }
                .copy(activeWar = WarState(p1, p2, 1, lastRound = WarRound(1, false, p1)))

        val played = initial.play("p1: play hand-1")

        assertThat(played.playerState(p2).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(played.playerState(p2).hand).isEmpty()
        assertThat(played.playerState(p2).stockTokens).isEqualTo(36)
        assertThat(played.playerState(p2).poolTokens).isEqualTo(0)
        assertThat(played.deckHistory.discarded).contains("stone-2")
        assertThat(played.activeWar).isNull()
    }

    @Test
    fun gunpowderVoidsHeldOldMilitaryAndReachesTheBoardAdapter() {
        val initial =
            running("gunpowder", "armor")
                .copy(
                    effects =
                        CardTurnEffects(military = listOf(CardMilitaryPlay(p2, EventId.STIRRUPS)))
                )

        val played = initial.play("p1: play hand-1")

        assertThat(played.voids.armorAndStirrups).isTrue()
        assertThat(CardRules.isBurden(played.cardContext(), "armor")).isTrue()
        assertThat(BoardGame.context(played).militaryPlays)
            .containsExactly(
                MilitaryPlay(p2, MilitaryCard.STIRRUPS),
                MilitaryPlay(p1, MilitaryCard.GUNPOWDER),
            )
        assertThat(played.deckHistory.removed).containsExactly("gunpowder")
    }

    @Test
    fun gunpowderWinsARecordedModifiedWarTieAgainstLongBow() {
        val initial =
            running("gunpowder", "war")
                .copy(
                    effects =
                        CardTurnEffects(military = listOf(CardMilitaryPlay(p2, EventId.LONG_BOW)))
                )
        val awaiting = initial.play("p1: play hand-1", "p1: play hand-2 player p2")

        val rolled = awaiting.play("!roll war 3 4")

        assertThat(rolled.activeWar).isNull()
        assertThat(rolled.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
        assertThat(rolled.playerState(p2).misery).isEqualTo(MiseryStep.TWENTY)
        assertThat(rolled.flow.decisions).isEmpty()
    }

    @Test
    fun marcoPlayedBeforeMongolsKeepsTheEarlierRebateAndPurchaseCredit() {
        val initial =
            running("marco-polo", "mongol-armies").player(p1) { owner ->
                owner.copy(
                    advances =
                        advances(
                            AdvanceId.WRITTEN_RECORD,
                            AdvanceId.PRINTED_WORD,
                            AdvanceId.OVERLAND_EAST,
                        )
                )
            }
        val played = initial.play("p1: play hand-1", "p1: play hand-2")

        val quote =
            AdvancePurchases.quote(
                played.playerState(p1),
                AdvanceId.COSMOPOLITAN,
                played.purchaseContext(p1),
            )

        assertThat(played.playerState(p1).cash).isEqualTo(70)
        assertThat(quote.price).isEqualTo(120)
        assertThat(played.voids.crusades).isTrue()
    }

    @Test
    fun marcoPlayedAfterMongolsGivesEnhancedRebateAndPurchaseCredit() {
        val initial =
            running("mongol-armies", "marco-polo").player(p1) { owner ->
                owner.copy(
                    advances =
                        advances(
                            AdvanceId.WRITTEN_RECORD,
                            AdvanceId.PRINTED_WORD,
                            AdvanceId.OVERLAND_EAST,
                        )
                )
            }
        val played = initial.play("p1: play hand-1", "p1: play hand-2")

        val quote =
            AdvancePurchases.quote(
                played.playerState(p1),
                AdvanceId.COSMOPOLITAN,
                played.purchaseContext(p1),
            )

        assertThat(played.playerState(p1).cash).isEqualTo(90)
        assertThat(quote.price).isEqualTo(100)
    }

    @Test
    fun finalCardPlayRetainsMongolHistoryAndTheExistingLeaderDisplay() {
        val initial =
            running("mongol-armies", "marco-polo")
                .player(p1) { owner ->
                    owner.copy(
                        advances =
                            advances(
                                AdvanceId.WRITTEN_RECORD,
                                AdvanceId.PRINTED_WORD,
                                AdvanceId.OVERLAND_EAST,
                            )
                    )
                }
                .copy(
                    stage = Stage.FINAL_CARD_PLAY,
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
                        ),
                )

        val played = initial.play("p1: play hand-1", "p1: play hand-2")

        assertThat(played.playerState(p1).cash).isEqualTo(90)
        assertThat(played.effects.leaders.last().role).isEqualTo(CardLeaderRole.EXPOSED)
        assertThat(played.effects.leaders.last().mongolEnhanced).isTrue()
    }

    private fun running(vararg cards: String): GameState {
        val header =
            GameHeader(listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three")), p1)
        return GameState(
            header = header,
            currentEpoch = Epoch.TWO,
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(order = listOf(p1, p2, p3)),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
            players =
                header.players.associate { player ->
                    player.id to
                        PlayerState(
                            player = player,
                            capital =
                                when (player.id) {
                                    p1 -> "venice"
                                    p2 -> "genoa"
                                    else -> "barcelona"
                                },
                            stockTokens = 26,
                            poolTokens = 10,
                            hand =
                                when (player.id) {
                                    p1 ->
                                        cards.mapIndexed { index, id ->
                                            HandCard("hand-${index + 1}", CardData.definition(id))
                                        } + HandCard("spare", CardData.definition("fur"))
                                    p2 ->
                                        listOf(
                                            HandCard("victim-two", CardData.definition("stone-2"))
                                        )
                                    else ->
                                        listOf(
                                            HandCard("victim-three", CardData.definition("wool-2"))
                                        )
                                },
                        )
                },
        )
    }

    private fun advances(vararg ids: AdvanceId): PlayerAdvances =
        PlayerAdvances(ids.map { id -> AdvanceAcquisition(id, 1) })

    private fun GameState.marker(province: String, owner: PlayerId): GameState =
        withBoardChange(board.placeMarker(ProvinceId(province), owner))

    private fun GameState.tokens(province: String, owner: PlayerId, count: Int): GameState =
        withBoardChange(
            board.replace(
                ProvinceId(province),
                ProvincePieces.Tokens(persistentMapOf(owner to TokenHolding(coloured = count))),
            )
        )

    private fun GameState.player(id: PlayerId, update: (PlayerState) -> PlayerState): GameState =
        replacePlayer(update(playerState(id)))

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line -> state.step(TextCodec.parse(line)) }
}
