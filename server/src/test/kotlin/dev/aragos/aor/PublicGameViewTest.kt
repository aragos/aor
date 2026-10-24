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
                if (id == PlayerId("p3")) {
                    player.copy(cash = 10_000, misery = MiseryStep.CHAOS)
                } else {
                    player.copy(
                        cash = 100,
                        advances =
                            PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.THE_HEAVENS, 1))),
                    )
                }
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

        val humanBody =
            requireNotNull(offers[AdvanceId.HUMAN_BODY]) {
                "The view must include the Human Body offer."
            }
        val caravan =
            requireNotNull(offers[AdvanceId.CARAVAN]) {
                "The view must include the Caravan offer."
            }

        assertThat(offers).doesNotContainKey(AdvanceId.THE_HEAVENS)
        assertThat(humanBody.price).isEqualTo(60)
        assertThat(humanBody.unavailableReason).isEqualTo("INSUFFICIENT_CASH")
        assertThat(caravan.price).isEqualTo(20)
        assertThat(caravan.unavailableReason).isNull()
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

    @Test
    fun completedWarPublishesTheWinnerDuringSurrender() {
        val view = warSurrenderState().viewForPlayer(PlayerId("p2"))

        assertThat(view.activeWar).isNull()
        assertThat(view.warCession?.winner).isEqualTo(PlayerId("p1"))
    }

    @Test
    fun warCessionPublishesSupportableChoicesAndOverseasLimit() {
        val cession = warSurrenderState().viewForPlayer(PlayerId("p2")).warCession

        assertThat(cession?.eligibleProvinces)
            .containsExactly(ProvinceId("tunis"), ProvinceId("china"), ProvinceId("india"))
        assertThat(cession?.requiredCount).isEqualTo(2)
        assertThat(cession?.overseasCapacity).isEqualTo(1)
    }

    @Test
    fun warCessionIsAbsentOutsideTheSurrenderDecision() {
        assertThat(GameState(header).viewForPlayer(PlayerId("p1")).warCession).isNull()
    }

    @Test
    fun markerPlacementPublishesTheDestinationOfTheNextChoice() {
        val initial = turnState()
        val decision =
            CardDecision.MarkerPlacement(
                listOf(ProvinceId("tunis"), ProvinceId("suez")),
                MarkerPlacementAftermath.War(
                    WarSettlement(
                        winner = PlayerId("p1"),
                        loser = PlayerId("p2"),
                        provinces = setOf(ProvinceId("tunis"), ProvinceId("suez")),
                        additionalLoserMisery = 0,
                    )
                ),
            )
        val state = initial.copy(flow = initial.flow.copy(decisions = listOf(decision)))

        assertThat(state.viewForPlayer(PlayerId("p1")).markerPlacement)
            .isEqualTo(MarkerPlacementView(PlayerId("p1"), ProvinceId("tunis")))
    }

    @Test
    fun markerPlacementIsAbsentOutsideTheMarkerChoice() {
        assertThat(GameState(header).viewForPlayer(PlayerId("p1")).markerPlacement).isNull()
    }

    private fun warSurrenderState(): GameState {
        val initial = turnState()
        val winner =
            initial
                .playerState(PlayerId("p1"))
                .copy(
                    advances =
                        PlayerAdvances(listOf(AdvanceAcquisition(AdvanceId.OCEAN_NAVIGATION, 1))),
                    ships = ShippingCapability.OCEAN_1,
                )
        val victory =
            WarResult.Victory(
                winner.player.id,
                PlayerId("p2"),
                requiredTransfers = 3,
                totals = mapOf(winner.player.id to 4, PlayerId("p2") to 1),
            )
        val board =
            listOf("tunis", "china", "india", "kiev").fold(BoardState()) { current, province ->
                current.placeMarker(ProvinceId(province), PlayerId("p2")).board
            }
        return initial.copy(
            players = initial.players + (winner.player.id to winner),
            board = board,
            flow =
                initial.flow.copy(
                    decisions = listOf(CardDecision.WarSurrender(PlayerId("p2"), victory))
                ),
        )
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
