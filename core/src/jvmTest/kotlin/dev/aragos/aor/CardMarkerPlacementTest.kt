package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CardMarkerPlacementTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val acre = ProvinceId("acre")
    private val tunis = ProvinceId("tunis")
    private val suez = ProvinceId("suez")

    @Test
    fun crusadesAtCapacityOpensAChoiceForItsPlayer() {
        val played = crusades().play("p1: play-card hand-1 province acre")
        val decision = played.flow.decisions.single() as CardDecision.MarkerPlacement
        assertThat(decision.player).isEqualTo(p1)
        assertThat(decision.destination).isEqualTo(acre)
        assertThat(played.board.markerCount(p1)).isEqualTo(25)
        assertThat(played.flow.actor).isEqualTo(p1)
        assertThat(played.board.pieces).doesNotContainKey(acre)
    }

    @Test
    fun decliningCrusadesStillRemovesTheVictimAndResolvesTheEvent() {
        val played = crusades().play("p1: play-card hand-1 province acre", "p1: forgo-marker")
        assertThat(played.board.pieces).doesNotContainKey(acre)
        assertThat(played.board.markerCount(p1)).isEqualTo(25)
        assertThat(played.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
        assertThat(played.effects.crusadesPlayed).isTrue()
        assertThat(played.flow.decisions).isEmpty()
    }

    @Test
    fun reusingACrusadesMarkerEmptiesItsSourceWithoutSpendingTokens() {
        val initial = crusades()
        val source = source(initial, p1)
        val played = initial.play("p1: play-card hand-1 province acre", "p1: move-marker $source")
        assertThat(played.board.pieces).doesNotContainKey(source)
        assertThat(played.board.pieces[acre])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(played.board.markerCount(p1)).isEqualTo(25)
        assertThat(played.playerState(p1).stockTokens)
            .isEqualTo(initial.playerState(p1).stockTokens)
        assertThat(played.playerState(p1).poolTokens).isEqualTo(initial.playerState(p1).poolTokens)
        assertThat(played.flow.voluntaryReduced).contains(source)
    }

    @Test
    fun declinedCrusadesReturnsAllRemovedSquareTokensToTheirStocks() {
        val initial =
            crusades().copy(board = crusades().board.remove(acre).board).let { state ->
                state.withBoardChange(state.board.replaceWithToken(acre, p2))
            }
        val played = initial.play("p1: play-card hand-1 province acre", "p1: forgo-marker")
        assertThat(played.playerState(p2).stockTokens).isEqualTo(36)
        assertThat(played.board.tokenCount(p2)).isEqualTo(0)
    }

    @Test
    fun aCrusadesMarkerFromStockNeedsNoExtraChoice() {
        val original = crusades()
        val initial = original.withBoardChange(original.board.remove(source(original, p1)))
        val played = initial.play("p1: play-card hand-1 province acre")
        assertThat(played.board.markerCount(p1)).isEqualTo(25)
        assertThat(played.board.pieces[acre])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(played.flow.decisions).isEmpty()
    }

    @Test
    fun crusadesInAnAlreadyOwnedMarketReusesThatMarker() {
        val original = crusades()
        val freed = original.withBoardChange(original.board.remove(source(original, p1)))
        val initial = freed.withBoardChange(freed.board.placeMarker(acre, p1))
        val played = initial.play("p1: play-card hand-1 province acre")
        assertThat(played.board.markerCount(p1)).isEqualTo(25)
        assertThat(played.flow.decisions).isEmpty()
    }

    @Test
    fun anotherPlayerCannotChooseTheMarkerSource() {
        val awaiting = crusades().play("p1: play-card hand-1 province acre")
        val failure = assertFailsWith<RuleViolation> { awaiting.play("p2: forgo-marker") }
        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    @Test
    fun aForeignMarkerCannotBeReused() {
        val original = crusades()
        val initial = original.withBoardChange(original.board.placeMarker(tunis, p2))
        val awaiting = initial.play("p1: play-card hand-1 province acre")
        val failure = assertFailsWith<CardRuleViolation> { awaiting.play("p1: move-marker tunis") }
        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun anEmptyDestinationCannotBeUsedAsItsOwnSource() {
        val awaiting = crusades().play("p1: play-card hand-1 province acre")
        val failure = assertFailsWith<CardRuleViolation> { awaiting.play("p1: move-marker acre") }
        assertThat(failure.code).isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun aWhiteMarkerIsNotAControlledSource() {
        val original = crusades()
        val source = source(original, p1)
        val initial =
            original.copy(board = original.board.placeMarker(source, p1, PieceSide.WHITE).board)
        val awaiting = initial.play("p1: play-card hand-1 province acre")
        assertThat(awaiting.legalChoices(p1).commands).doesNotContain("move-marker $source")
        assertThat(
                assertFailsWith<CardRuleViolation> { awaiting.play("p1: move-marker $source") }.code
            )
            .isEqualTo(CardRuleCode.ILLEGAL_TARGET)
    }

    @Test
    fun warWinnerChoosesSeparatelyForEveryAwardWithoutExtraLoserMisery() {
        val awaiting =
            war().play("p1: play-card hand-1 player p2", "!roll war 5 3", "p2: cede-war suez tunis")
        val settled = awaiting.play("p1: forgo-marker", "p1: forgo-marker")
        assertThat(settled.board.pieces).doesNotContainKey(tunis)
        assertThat(settled.board.pieces).doesNotContainKey(suez)
        assertThat(settled.board.markerCount(p1)).isEqualTo(25)
        assertThat(settled.playerState(p1).misery).isEqualTo(MiseryStep.TEN)
        assertThat(settled.playerState(p2).misery).isEqualTo(MiseryStep.TWENTY)
        assertThat(settled.flow.decisions).isEmpty()
    }

    @Test
    fun warUsesItsLastStockMarkerBeforeAskingForAnother() {
        val original = war()
        val initial = original.withBoardChange(original.board.remove(source(original, p1)))
        val awaiting =
            initial.play(
                "p1: play-card hand-1 player p2",
                "!roll war 5 3",
                "p2: cede-war suez tunis",
            )
        val decision = awaiting.flow.decisions.single() as CardDecision.MarkerPlacement
        assertThat(awaiting.board.pieces[suez])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(decision.destination).isEqualTo(tunis)
        assertThat(awaiting.board.markerCount(p1)).isEqualTo(25)
    }

    @Test
    fun warCanReuseTheMarkerFromAnEarlierAwardInTheSameSettlement() {
        val original = war()
        val initial = original.withBoardChange(original.board.remove(source(original, p1)))
        val settled =
            initial.play(
                "p1: play-card hand-1 player p2",
                "!roll war 5 3",
                "p2: cede-war suez tunis",
                "p1: move-marker suez",
            )
        assertThat(settled.board.pieces).doesNotContainKey(suez)
        assertThat(settled.board.pieces[tunis])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.COLOURED))
        assertThat(settled.board.markerCount(p1)).isEqualTo(25)
        assertThat(settled.flow.decisions).isEmpty()
    }

    @Test
    fun aDifferentWarWinnerAnswersWithoutTakingOverTheOuterCardRound() {
        val initial =
            game("war", owner = p2)
                .withBoardChange(game("war", owner = p2).board.placeMarker(tunis, p1))
        val awaiting =
            initial.play("p1: play-card hand-1 player p2", "!roll war 3 4", "p1: cede-war tunis")
        val decision = awaiting.flow.decisions.single() as CardDecision.MarkerPlacement
        assertThat(decision.player).isEqualTo(p2)
        assertThat(awaiting.flow.actor).isEqualTo(p1)
        val settled = awaiting.play("p2: forgo-marker")
        assertThat(settled.flow.actor).isEqualTo(p1)
    }

    @Test
    fun finalCardPlayFinishesAfterARequiredMarkerChoice() {
        val original = crusades()
        val initial =
            original.copy(
                stage = Stage.FINAL_CARD_PLAY,
                endingTriggered = true,
                players =
                    original.players.mapValues { (id, player) ->
                        player.copy(hand = if (id == p1) player.hand.take(1) else emptyList())
                    },
            )
        val finished = initial.play("p1: play-card hand-1 province acre", "p1: forgo-marker")
        assertThat(finished.stage).isEqualTo(Stage.FINISHED)
        assertThat(finished.flow.decisions).isEmpty()
        assertThat(finished.viewForPlayer(p1).scores).hasSize(3)
    }

    @Test
    fun markerChoiceCommandsReplayToTheSameBoardAndActivity() {
        val initial = war()
        val source = source(initial, p1)
        val commands =
            listOf(
                "p1: play-card hand-1 player p2",
                "!roll war 5 3",
                "p2: cede-war suez tunis",
                "p1: forgo-marker",
                "p1: move-marker $source",
            )
        val played = initial.play(*commands.toTypedArray())
        val canonical = commands.map { text -> TextCodec.parse(text).toText() }
        assertThat(initial.play(*canonical.toTypedArray())).isEqualTo(played)
    }

    @Test
    fun warAppliesChaosAndShortfallMiseryAfterEveryMarkerChoice() {
        val original = war()
        val initial =
            original.updatePlayer(p1) { player -> player.copy(misery = MiseryStep.THOUSAND) }
        val settled =
            initial.play(
                "p1: play-card hand-1 player p2",
                "!roll war 6 3",
                "p2: cede-war suez tunis",
                "p1: forgo-marker",
                "p1: forgo-marker",
            )
        assertThat(settled.playerState(p1).misery).isEqualTo(MiseryStep.CHAOS)
        assertThat(settled.playerState(p2).misery).isEqualTo(MiseryStep.THIRTY)
        assertThat(settled.flow.decisions).isEmpty()
        assertThat(settled.flow.actor).isEqualTo(p2)
    }

    @Test
    fun finalWarFinishesAfterTheEmptyHandedWinnerAnswers() {
        val original = game("war", owner = p2)
        val marked = original.withBoardChange(original.board.placeMarker(tunis, p1))
        val initial =
            marked.copy(
                stage = Stage.FINAL_CARD_PLAY,
                endingTriggered = true,
                players =
                    marked.players.mapValues { (id, player) ->
                        player.copy(hand = if (id == p1) player.hand.take(1) else emptyList())
                    },
            )
        val finished =
            initial.play(
                "p1: play-card hand-1 player p2",
                "!roll war 3 4",
                "p1: cede-war tunis",
                "p2: forgo-marker",
            )
        assertThat(finished.stage).isEqualTo(Stage.FINISHED)
        assertThat(finished.playerState(p2).misery).isEqualTo(MiseryStep.TEN)
        assertThat(finished.playerState(p1).misery).isEqualTo(MiseryStep.TWENTY)
        assertThat(finished.flow.decisions).isEmpty()
    }

    private fun crusades(): GameState =
        game("the-crusades").let { state ->
            state.withBoardChange(state.board.placeMarker(acre, p2))
        }

    private fun war(): GameState =
        game("war")
            .let { state ->
                state.withBoardChange(state.board.placeMarker(tunis, p2))
            }
            .let { state -> state.withBoardChange(state.board.placeMarker(suez, p2)) }

    private fun source(state: GameState, owner: PlayerId): ProvinceId =
        state.board.pieces.entries
            .first { entry ->
                (entry.value as? ProvincePieces.Marker)?.owner == owner
            }
            .key

    private fun game(card: String, owner: PlayerId = p1): GameState {
        val players = (1..3).map { number -> Player(PlayerId("p$number"), "Player $number") }
        val capitals = listOf("venice", "genoa", "barcelona")
        val markets =
            CatalogConfiguration(3)
                .provinces
                .filter { province ->
                    !province.isSatellite &&
                        province.area != null &&
                        province.area != GeographicalArea.V &&
                        province.id !in setOf(acre, tunis, suez)
                }
                .take(25)
        check(markets.size == 25) { "The fixture requires twenty-five available markets." }
        val board =
            markets.fold(BoardState()) { board, province ->
                board.placeMarker(province.id, owner).board
            }
        return GameState(
            GameHeader(players, p1),
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            board = board,
            deck = CardData.epochOne.map(CardDefinition::id),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player,
                                capital = capitals[index],
                                hand =
                                    if (player.id == p1)
                                        listOf(hand("hand-1", card), hand("hand-4", "fur"))
                                    else
                                        listOf(
                                            hand(
                                                "hand-${index + 1}",
                                                if (index == 1) "stone-2" else "wool-2",
                                            )
                                        ),
                            )
                    }
                    .toMap(),
        )
    }

    private fun hand(reference: String, id: String): HandCard =
        HandCard(reference, CardData.epochOne.single { card -> card.id == id })

    private fun GameState.play(vararg lines: String): GameState =
        lines.fold(this) { state, line -> state.step(TextCodec.parse(line)) }
}
