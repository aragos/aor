package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class OpeningSelectionTest {
    private val p1 = PlayerId("p1")

    @Test
    fun everySupportedOpeningFilterDealsThreeAndKeepsTwoBeforeBidding() {
        for (count in 3..6) {
            for (rule in InitialCardRule.entries) {
                if (count == 6 && rule == InitialCardRule.COMMODITY_ONLY) continue
                for (modified in listOf(false, true)) {
                    val opening = opening(count, rule, modified)
                    val bidding = keepTwo(opening)
                    assertThat(bidding.state.stage).isEqualTo(Stage.BIDDING)
                    assertThat(bidding.state.players.values.map { it.hand.size })
                        .containsExactlyElementsIn(List(count) { 2 })
                    assertThat(
                            bidding.state.players.values.flatMap(PlayerState::hand).all {
                                rule.accepts(it.definition)
                            }
                        )
                        .isTrue()
                    assertThat(
                            bidding.state.deck +
                                bidding.state.players.values.flatMap(PlayerState::hand).map {
                                    it.definition.id
                                }
                        )
                        .containsExactlyElementsIn(bidding.state.rules.cardsForEpoch(Epoch.ONE))
                    assertThat(replay(bidding.state.header, bidding.lines)).isEqualTo(bidding.state)
                }
            }
        }
    }

    @Test
    fun returnedCardRequiresAnExactRecordedReshuffleWithoutBeingDiscarded() {
        val opening = opening()
        val card = opening.state.playerState(p1).hand.first()
        val returned = opening.state.step(TextCodec.parse("p1: return-card ${card.reference}"))
        val request = requireNotNull(returned.pendingShuffle()) { "A return requires a shuffle." }

        assertThat(request.purpose).isEqualTo(ShufflePurpose.SETUP_RETURN)
        assertThat(request.cards).contains(card.definition.id)
        assertThat(returned.playerState(p1).hand).hasSize(2)
        assertThat(returned.deckHistory.discarded).isEmpty()
        assertThat(returned.deckHistory.removed).isEmpty()
        assertThat(returned.playedCards).isEmpty()
        val failure =
            assertFailsWith<RuleViolation> {
                returned.step(Entry.Shuffle(request.cards.drop(1)))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_OUTCOME)
        val shuffled = returned.step(Entry.Shuffle(request.cards.reversed()))
        assertThat(shuffled.deck).containsExactlyElementsIn(request.cards.reversed()).inOrder()
        assertThat(shuffled.pendingDraw()?.player).isEqualTo(PlayerId("p2"))
    }

    @Test
    fun onlyTheCurrentOwnerMayReturnAnOpeningCard() {
        val opening = opening()
        val card = opening.state.playerState(p1).hand.first()
        val failure =
            assertFailsWith<RuleViolation> {
                opening.state.step(TextCodec.parse("p2: return-card ${card.reference}"))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
        assertThat(opening.state.legalChoices(PlayerId("p2")).commands).isEmpty()
    }

    @Test
    fun cardReturnRejectsUnknownReferences() {
        val failure =
            assertFailsWith<RuleViolation> {
                opening().state.step(TextCodec.parse("p1: return-card unknown"))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_CARD_RETURN)
    }

    @Test
    fun capitalBidsCannotPrecedeOpeningChoices() {
        val failure =
            assertFailsWith<RuleViolation> {
                opening().state.step(TextCodec.parse("p1: bid-capital 0"))
            }
        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PHASE)
    }

    @Test
    fun sixPlayerCommodityOnlyOpeningIsRejectedBeforeDrawing() {
        for (modified in listOf(false, true)) {
            val options =
                RuleOptions(
                    initialCard = InitialCardRule.COMMODITY_ONLY,
                    drawThreeKeepTwo = true,
                    deckModification = modified,
                )
            val configured = Scenario.create(6).state.copy(rules = options)
            val failure =
                assertFailsWith<RuleViolation> {
                    configured.step(TextCodec.parse("p1: start"))
                }
            assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_INITIAL_CARDS)
        }
    }

    @Test
    fun firstTurnDoesNotDrawAThirdCard() {
        val ordered = firstTurn()
        assertThat(ordered.state.stage).isEqualTo(Stage.PLAY_CARDS)
        assertThat(ordered.state.players.values.map { it.hand.size }).containsExactly(2, 2, 2)
        assertThat(ordered.lines.count { it.startsWith("!draw ") }).isEqualTo(9)
    }

    @Test
    fun secondTurnResumesOrdinaryDraws() {
        val completed = opening().complete()
        val turnTwoDraws =
            completed.lines
                .fold(GameState(completed.state.header) to 0) { (state, draws), line ->
                    val entry = TextCodec.parse(line)
                    val ordinaryDraw =
                        state.turnNumber == 2 &&
                            state.stage == Stage.DRAW_CARD &&
                            entry is GameOutcome.Draw
                    state.step(entry) to (draws + if (ordinaryDraw) 1 else 0)
                }
                .second
        assertThat(turnTwoDraws).isEqualTo(3)
        assertThat(replay(completed.state.header, completed.lines)).isEqualTo(completed.state)
        assertThat(completed.state.stage).isEqualTo(Stage.FINISHED)
    }

    private fun firstTurn(): Scenario =
        keepTwo(opening())
            .play(
                "p1: bid-capital 3",
                "p2: bid-capital 2",
                "p3: bid-capital 1",
                "p1: choose-capital venice",
                "p2: choose-capital genoa",
                "p3: choose-capital barcelona",
                "p1: buy-tokens 3",
                "p2: buy-tokens 2",
                "p3: buy-tokens 1",
            )

    private fun opening(
        count: Int = 3,
        rule: InitialCardRule = InitialCardRule.ANY,
        modified: Boolean = false,
    ): Scenario =
        Scenario.create(count)
            .play(
                "p1: set-option draw-three-keep-two enabled",
                "p1: set-option initial-card ${rule.commandValue}",
                "p1: set-option deck-modification ${if (modified) "enabled" else "disabled"}",
                "p1: start",
            )

    private fun keepTwo(opening: Scenario): Scenario =
        opening.state.playerIds.fold(opening) { current, id ->
            assertThat(current.state.stage).isEqualTo(Stage.SETUP_RETURN)
            assertThat(current.state.playerState(id).hand).hasSize(3)
            current.play("$id: return-card ${current.state.playerState(id).hand.first().reference}")
        }
}
