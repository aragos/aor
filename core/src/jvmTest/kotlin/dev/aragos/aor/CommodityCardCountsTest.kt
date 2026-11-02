package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CommodityCardCountsTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")

    @Test
    fun epochCountsIncludeSharedChoicesAndFutureCards() {
        val counts = PublicCatalog.forPlayers(3).commodityCards.associateBy { it.commodity }

        assertThat(counts[Commodity.STONE]?.epochs).containsExactly(2, 0, 0).inOrder()
        assertThat(counts[Commodity.TIMBER]?.epochs).containsExactly(1, 2, 0).inOrder()
        assertThat(counts[Commodity.GRAIN]?.epochs).containsExactly(0, 2, 0).inOrder()
        assertThat(counts[Commodity.CLOTH]?.epochs).containsExactly(1, 1, 1).inOrder()
        assertThat(counts[Commodity.IVORY]?.epochs).containsExactly(1, 0, 0).inOrder()
        assertThat(counts[Commodity.GOLD]?.epochs).containsExactly(1, 0, 1).inOrder()
    }

    @Test
    fun deckModificationMovesTheAdditionalTimberCountToEpochOne() {
        val state = state().copy(rules = RuleOptions(deckModification = true))
        val timber = state.publicView().commodityCards.single { it.commodity == Commodity.TIMBER }

        assertThat(timber.epochs).containsExactly(2, 1, 0).inOrder()
        assertThat(timber.unplayed).isEqualTo(2)
    }

    @Test
    fun cardsAreNotInCirculationBeforeTheInitialShuffle() {
        val state = Scenario.create().state

        assertThat(state.publicView().commodityCards.map { it.unplayed })
            .containsExactlyElementsIn(List(12) { 0 })
    }

    @Test
    fun playingASharedCardReducesBothCommodityCounts() {
        val after = CardGame.play(state(), p1, "held", CardChoice.CommodityChoice(Commodity.CLOTH))
        val counts = after.publicView().commodityCards.associateBy { it.commodity }

        assertThat(counts[Commodity.CLOTH]?.unplayed).isEqualTo(0)
        assertThat(counts[Commodity.WINE]?.unplayed).isEqualTo(0)
    }

    @Test
    fun hiddenCardLocationsAndPrivateDiscardsDoNotChangePublicCounts() {
        val held = state()
        val owner = held.playerState(p1)
        val inDeck =
            held.copy(
                deck = listOf("cloth-wine"),
                players = held.players + (p1 to owner.copy(hand = emptyList())),
            )
        val discarded = held.discardCards(owner.hand).copy(players = inDeck.players)

        assertThat(inDeck.viewForPlayer(p2).commodityCards)
            .isEqualTo(held.viewForPlayer(p2).commodityCards)
        assertThat(discarded.publicView().commodityCards)
            .isEqualTo(held.publicView().commodityCards)
    }

    @Test
    fun nextEpochRestoresRecycledCardsAndIntroducesOnlyItsNewCards() {
        val played = CardGame.play(state(), p1, "held", CardChoice.CommodityChoice(Commodity.CLOTH))
        val exhausted = played.copy(deck = emptyList()).queueDraw(p1)
        val request =
            requireNotNull(exhausted.pendingShuffle()) { "An exhausted deck must shuffle." }

        val shuffled = exhausted.step(Entry.Shuffle(request.cards))
        val counts = shuffled.publicView().commodityCards.associateBy { it.commodity }

        assertThat(counts[Commodity.CLOTH]?.unplayed).isEqualTo(2)
        assertThat(counts[Commodity.GOLD]?.unplayed).isEqualTo(1)
        assertThat(counts[Commodity.TIMBER]?.unplayed).isEqualTo(3)
    }

    @Test
    fun supportedSavedHistoriesReplayAndContinueWithTheSamePublicCounts() {
        for (version in CatalogVersion.entries) {
            val initial = Scenario.create().state
            val source = initial.copy(header = initial.header.copy(dataVersion = version.wireValue))
            val completed = Scenario(source).play("p1: start").complete()
            val loaded =
                completed.lines.dropLast(1).fold(source) { state, line ->
                    state.step(TextCodec.parse(line))
                }

            val continued = loaded.step(TextCodec.parse(completed.lines.last()))

            assertThat(continued.publicView().commodityCards)
                .isEqualTo(completed.state.publicView().commodityCards)
            assertThat(continued).isEqualTo(completed.state)
        }
    }

    private fun state(): GameState {
        val initial = Scenario.create().state
        return initial.copy(
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(initial.playerIds),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            deck = listOf("stone-1"),
            players =
                initial.players.mapValues { (id, player) ->
                    val capital =
                        when (id) {
                            p1 -> "venice"
                            p2 -> "genoa"
                            else -> "barcelona"
                        }
                    player.copy(
                        capital = capital,
                        hand =
                            if (id == p1) {
                                listOf(HandCard("held", CardData.definition("cloth-wine")))
                            } else {
                                emptyList()
                            },
                    )
                },
        )
    }
}
