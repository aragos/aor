package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test

class PeacefulExpansionProspectTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val ids = listOf(p1, p2, PlayerId("p3"))

    @Test
    fun shippingProspectsQuoteCommitmentsWithoutOfferingPrematureCommands() {
        val state = fixture()

        val view = state.viewForDecision(p1)
        val prospect =
            view.expansionForecast?.peacefulMarkets.orEmpty().single {
                it.destination.value == "alexandria"
            }

        assertThat(prospect.requiredTokens).isEqualTo(5)
        assertThat(prospect.shipping).isTrue()
        assertThat(view.expansionOffers).isEmpty()
        assertThat(state.legalChoices(p1).commands.none { it.startsWith("place ") }).isTrue()
    }

    @Test
    fun unavailableShippingDoesNotCreateDistantProspects() {
        val state = fixture(ships = ShippingCapability.NONE)

        val prospects = state.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(prospects.map { it.destination.value }).doesNotContain("alexandria")
    }

    @Test
    fun aReachableGalleyDestinationStillRequiresEnoughPerDestinationCapacity() {
        val state = fixture(ships = ShippingCapability.GALLEY_2)

        val routes = ExpansionReach.routesTo(BoardGame.context(state), p1, ProvinceId("rome"))
        val prospects = state.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(routes).isNotEmpty()
        assertThat(prospects.map { it.destination.value }).doesNotContain("rome")
    }

    @Test
    fun contestedMarketsAreExcludedFromPeacefulForecasts() {
        val initial = fixture()
        val state =
            initial.copy(board = initial.board.placeMarker(ProvinceId("alexandria"), p2).board)

        val prospects = state.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(prospects.map { it.destination.value }).doesNotContain("alexandria")
    }

    @Test
    fun originalCapitalRemainsAPlanningBaseAfterItsMarkerIsLost() {
        val initial = fixture(ships = ShippingCapability.NONE)
        val state = initial.copy(board = BoardState())

        val prospects = state.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(prospects.map { it.destination.value }).contains("venice")
    }

    @Test
    fun finalCardPlayHasNoForthcomingExpansionForecast() {
        val state = fixture().copy(stage = Stage.FINAL_CARD_PLAY, endingTriggered = true)

        val prospects = state.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(prospects).isEmpty()
    }

    @Test
    fun forecastsDoNotDependOnOpponentsPrivateCards() {
        val initial = fixture()
        val opponent = initial.playerState(p2)
        val changed =
            initial.copy(
                players =
                    initial.players +
                        (p2 to
                            opponent.copy(
                                hand =
                                    listOf(
                                        HandCard(
                                            "private-opponent-card",
                                            CardData.definition("spice-3"),
                                        )
                                    )
                            ))
            )

        val before = initial.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()
        val after = changed.viewForDecision(p1).expansionForecast?.peacefulMarkets.orEmpty()

        assertThat(after).isEqualTo(before)
    }

    private fun fixture(ships: ShippingCapability = ShippingCapability.SEAWORTHY_10): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.PLAY_CARDS,
            currentEpoch = Epoch.THREE,
            turnNumber = 2,
            turnOrderSelection = TurnOrderSelection(ids),
            board =
                ids.zip(capitals).fold(BoardState()) { board, (player, capital) ->
                    board.placeMarker(ProvinceId(capital), player).board
                },
            players =
                ids.mapIndexed { index, player ->
                        player to
                            PlayerState(
                                player = Player(player, player.value),
                                capital = capitals[index],
                                cash = 100,
                                ships = if (player == p1) ships else ShippingCapability.NONE,
                                hand =
                                    if (player == p1)
                                        listOf(HandCard("held", CardData.definition("spice-2")))
                                    else emptyList(),
                            )
                    }
                    .toMap(),
            flow = TurnFlow(actor = p1, phaseStarted = true, phaseBookkeepingApplied = true),
        )
    }
}
