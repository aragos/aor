package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class PublicGameViewTest {
    private val header =
        GameHeader(
            listOf(
                Player(PlayerId("p1"), "Alice"),
                Player(PlayerId("p2"), "Bob"),
                Player(PlayerId("p3"), "Carol"),
            ),
            PlayerId("p1"),
        )

    @Test
    fun finalScoresExcludeChaosAndPreserveTiedWinners() {
        val initial = GameState(header)
        val players =
            initial.players.mapValues { (id, player) ->
                if (id == PlayerId("p3")) player.copy(cash = 10_000, misery = MiseryStep.CHAOS)
                else
                    player.copy(
                        cash = 100,
                        advances =
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1))),
                    )
            }

        val view =
            initial.copy(stage = Stage.FINISHED, players = players).viewForPlayer(PlayerId("p1"))

        assertThat(view.scores.filter(ScoreView::winner).map(ScoreView::player))
            .containsExactly(PlayerId("p1"), PlayerId("p2"))
        assertThat(view.scores.last().total).isNull()
        assertThat(view.scores.first().total).isEqualTo(130)
    }

    @Test
    fun intermediateViewsDoNotPublishFinalScores() {
        assertThat(GameState(header).viewForPlayer(PlayerId("p1")).scores).isEmpty()
    }

    @Test
    fun publicCatalogUsesTheStartingPlayerCount() {
        val catalog = requireNotNull(GameState(header).viewForPlayer(PlayerId("p1")).catalog)

        assertThat(catalog.provinces.map(ProvinceDefinition::area))
            .containsNoneOf(GeographicalArea.I, GeographicalArea.II, GeographicalArea.III)
        assertThat(catalog.advances).hasSize(26)
    }

    @Test
    fun privateHandsAreAbsentFromOtherPlayerRows() {
        val initial = GameState(header)
        val card = HandCard("secret-reference", CardData.epochOne.first())
        val state =
            initial.copy(
                players =
                    initial.players +
                        (PlayerId("p2") to
                            initial.playerState(PlayerId("p2")).copy(hand = listOf(card)))
            )

        val serialized = json.writeValueAsString(state.viewForPlayer(PlayerId("p1")))

        assertThat(serialized).doesNotContain("secret-reference")
        assertThat(serialized).doesNotContain("stone-1")
    }

    @Test
    fun advanceOffersUseTheViewersCashAndCurrentPhaseCredits() {
        val initial = turnState()
        val viewer =
            initial
                .playerState(PlayerId("p1"))
                .copy(
                    cash = 35,
                    advances = PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1))),
                )
        val state = initial.copy(players = initial.players + (viewer.player.id to viewer))

        val offers =
            state
                .viewForPlayer(viewer.player.id)
                .advanceOffers
                .associateBy(AdvanceOfferView::advance)

        assertThat(offers).doesNotContainKey(AdvanceId.THE_HEAVENS)
        assertThat(offers.getValue(AdvanceId.HUMAN_BODY).price).isEqualTo(60)
        assertThat(offers.getValue(AdvanceId.HUMAN_BODY).unavailableReason)
            .isEqualTo("INSUFFICIENT_CASH")
        assertThat(offers.getValue(AdvanceId.CARAVAN).price).isEqualTo(20)
        assertThat(offers.getValue(AdvanceId.CARAVAN).unavailableReason).isNull()
    }

    @Test
    fun burdenReferencesBelongOnlyToTheViewersHand() {
        val initial = turnState()
        val player =
            initial
                .playerState(PlayerId("p1"))
                .copy(
                    hand =
                        listOf(
                            HandCard(
                                "burden-reference",
                                CardData.epochOne.single { card -> card.id == "the-crusades" },
                            )
                        ),
                    advances =
                        PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.ENLIGHTENMENT, 1))),
                )
        val state =
            initial.copy(
                players = initial.players + (player.player.id to player),
                voids = CardVoids(crusades = true),
            )

        assertThat(state.viewForPlayer(player.player.id).burdenReferences)
            .containsExactly("burden-reference")
        assertThat(state.viewForPlayer(PlayerId("p2")).burdenReferences).isEmpty()
    }

    private fun turnState(): GameState {
        val initial = GameState(header)
        val capitals = listOf("venice", "genoa", "barcelona")
        return initial.copy(
            stage = Stage.PURCHASE,
            turnNumber = 1,
            players =
                initial.playerIds
                    .mapIndexed { index, id ->
                        id to initial.playerState(id).copy(capital = capitals[index])
                    }
                    .toMap(),
        )
    }
}
