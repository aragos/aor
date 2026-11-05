package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class CardAvailabilityTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")

    @Test
    fun initialCirculationContainsEveryIntroducedPhysicalCard() {
        assertThat(state().publicView().unplayedCards)
            .containsExactlyElementsIn(CardData.epochOne)
            .inOrder()
    }

    @Test
    fun cardsAreNotIntroducedBeforeTheInitialShuffle() {
        assertThat(Scenario.create().state.publicView().unplayedCards).isEmpty()
    }

    @Test
    fun configuredDeckModificationDeterminesWhichCardsAreIntroduced() {
        val modified = state().copy(rules = RuleOptions(deckModification = true))

        assertThat(modified.publicView().unplayedCards.map(CardDefinition::id))
            .containsExactlyElementsIn(modified.rules.cardsForEpoch(Epoch.ONE))
            .inOrder()
    }

    @Test
    fun publicPlaysRemoveCommoditiesEventsAndLeaders() {
        val initial = state("stone-1", "enlightened-ruler", "armor", "charlemagne")

        val played =
            initial.playerState(p1).hand.fold(initial) { current, card ->
                CardGame.play(current, p1, card.reference, CardChoice.None)
            }

        assertThat(played.publicView().unplayedCards.map(CardDefinition::id))
            .containsExactlyElementsIn(
                CardData.epochOne.map(CardDefinition::id) -
                    initial.playerState(p1).hand.map { it.definition.id }
            )
            .inOrder()
    }

    @Test
    fun hiddenLocationsAndPrivateDiscardsDoNotChangeAnyViewersList() {
        val held = state("enlightened-ruler", "armor", "charlemagne")
        val owner = held.playerState(p1)
        val inDeck =
            held
                .replacePlayer(owner.copy(hand = emptyList()))
                .copy(deck = held.deck + owner.hand.map { it.definition.id })
        val discarded = held.discardCards(owner.hand).replacePlayer(owner.copy(hand = emptyList()))

        assertThat(inDeck.viewForPlayer(p2).unplayedCards)
            .isEqualTo(held.viewForPlayer(p2).unplayedCards)
        assertThat(discarded.publicView().unplayedCards).isEqualTo(held.publicView().unplayedCards)
        assertThat(discarded.viewForPlayer(p1).unplayedCards)
            .isEqualTo(held.publicView().unplayedCards)
    }

    @Test
    fun nextEpochRestoresRecurringCardsAndKeepsPlayedSquaresExcluded() {
        val initial = state("stone-1", "enlightened-ruler", "armor", "charlemagne")
        val played =
            initial.playerState(p1).hand.fold(initial) { current, card ->
                CardGame.play(current, p1, card.reference, CardChoice.None)
            }
        val exhausted = played.copy(deck = emptyList()).queueDraw(p1)
        val request =
            requireNotNull(exhausted.pendingShuffle()) { "An exhausted deck must shuffle." }

        val shuffled = exhausted.step(Entry.Shuffle(request.cards))

        assertThat(shuffled.publicView().unplayedCards.map(CardDefinition::id))
            .containsExactlyElementsIn(
                (CardData.epochOne + CardData.epochTwo).map(CardDefinition::id) -
                    setOf("armor", "charlemagne")
            )
            .inOrder()
    }

    @Test
    fun existingCatalogHistoriesReplayAndContinueWithTheSameAvailability() {
        for (version in CatalogVersion.entries) {
            val initial = Scenario.create().state
            val source = initial.copy(header = initial.header.copy(dataVersion = version.wireValue))
            val completed = Scenario(source).play("p1: start").complete()
            val loaded =
                completed.lines.dropLast(1).fold(source) { current, line ->
                    current.step(TextCodec.parse(line))
                }

            val continued = loaded.step(TextCodec.parse(completed.lines.last()))

            assertThat(continued.publicView().unplayedCards)
                .isEqualTo(completed.state.publicView().unplayedCards)
            assertThat(continued).isEqualTo(completed.state)
        }
    }

    private fun state(vararg cards: String): GameState {
        val initial = Scenario.create().state
        val capitals = listOf("venice", "genoa", "barcelona")
        return initial.copy(
            stage = Stage.PLAY_CARDS,
            turnNumber = 1,
            turnOrderSelection = TurnOrderSelection(initial.playerIds),
            flow = TurnFlow(actor = p1, phaseStarted = true),
            deck = listOf("wool-1"),
            players =
                initial.players.mapValues { (id, player) ->
                    player.copy(
                        capital = capitals[initial.playerIds.indexOf(id)],
                        hand =
                            if (id == p1) cards.map { HandCard(it, CardData.definition(it)) }
                            else emptyList(),
                    )
                },
        )
    }
}
