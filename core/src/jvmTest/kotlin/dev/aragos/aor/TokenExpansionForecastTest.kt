package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlinx.collections.immutable.persistentMapOf

class TokenExpansionForecastTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val ids = listOf(p1, p2, PlayerId("p3"))
    private val alexandria = ProvinceId("alexandria")

    @Test
    fun tokenPurchasingProjectsPeacefulCommitmentsWithoutHeldCardsOrPoolTokens() {
        val state = fixture()

        val view = state.viewForDecision(p1)
        val prospect = forecast(state).peacefulMarkets.single { it.destination == alexandria }

        assertThat(view.hand).isEmpty()
        assertThat(state.playerState(p1).poolTokens).isEqualTo(0)
        assertThat(prospect.requiredTokens).isEqualTo(5)
        assertThat(prospect.shipping).isTrue()
        assertThat(view.expansionOffers).isEmpty()
        assertThat(view.orderBoxes).isEmpty()
        assertThat(state.legalChoices(p1).commands.none { it.startsWith("place ") }).isTrue()
    }

    @Test
    fun contestedCommitmentMatchesTheAuthoritativeAttemptWithoutAssumingAnOrder() {
        val initial = fixture()
        val state = initial.copy(board = initial.board.placeMarker(alexandria, p2).board)

        val prospect = forecast(state).contestedMarkets.single { it.destination == alexandria }
        val plan =
            BoardPlacementRules.evaluate(
                BoardGame.context(state),
                p1,
                alexandria,
                addedTokens = prospect.requiredTokens,
                poolTokens = prospect.requiredTokens,
            )

        assertThat(prospect.requiredTokens).isEqualTo(10)
        assertThat(prospect.shipping).isTrue()
        assertThat(plan).isInstanceOf(PlacementPlan.Competition::class.java)
        assertThat(forecast(state).peacefulMarkets.map { it.destination })
            .doesNotContain(alexandria)
        assertThat(state.orderBoxes).isEmpty()
    }

    @Test
    fun contestedCommitmentSubtractsExistingOwnTokens() {
        val initial = fixture()
        val state =
            initial.copy(
                board =
                    initial.board.copy(
                        pieces =
                            initial.board.pieces.put(
                                alexandria,
                                ProvincePieces.Tokens(
                                    persistentMapOf(
                                        p1 to TokenHolding(coloured = 2),
                                        p2 to TokenHolding(coloured = 1),
                                    )
                                ),
                            )
                    )
            )

        val prospect = forecast(state).contestedMarkets.single { it.destination == alexandria }

        assertThat(prospect.requiredTokens).isEqualTo(4)
    }

    @Test
    fun aContestedRouteStillNeedsEnoughShippingCapacity() {
        val initial = fixture(ships = ShippingCapability.GALLEY_4)
        val rome = ProvinceId("rome")
        val state = initial.copy(board = initial.board.placeMarker(rome, p2).board)

        val routes = ExpansionReach.routesTo(BoardGame.context(state), p1, rome)
        val prospects = forecast(state).contestedMarkets

        assertThat(routes).isNotEmpty()
        assertThat(prospects.map { it.destination }).doesNotContain(rome)
    }

    @Test
    fun noPeacefulMarketsDoesNotMeanNoContestedAccess() {
        val initial = fixture(ships = ShippingCapability.NONE)
        val surrounding = setOf("milan", "vienna", "budapest", "dubrovnik", "florence")
        val contested = initial.catalog.provinces.filter { it.id.value in surrounding }
        val state =
            initial.copy(
                board =
                    contested.fold(initial.board) { board, province ->
                        board.placeMarker(province.id, p2).board
                    }
            )

        val projected = forecast(state)

        assertThat(projected.peacefulMarkets).isEmpty()
        assertThat(projected.contestedMarkets.map { it.destination.value }).contains("florence")
    }

    @Test
    fun fullMarkerStockUsagePreventsForecastingAnotherMarket() {
        val initial = fixture()
        val occupied =
            initial.catalog.provinces
                .filter {
                    !it.isSatellite && it.id.value !in setOf("genoa", "barcelona", "alexandria")
                }
                .take(25)
        val board =
            occupied.fold(initial.board.remove(ProvinceId("venice")).board) { current, province ->
                current.placeMarker(province.id, p1).board
            }
        val state = initial.copy(board = board.placeMarker(alexandria, p2).board)

        val projected = forecast(state)

        assertThat(state.board.markerCount(p1)).isEqualTo(25)
        assertThat(projected.peacefulMarkets).isEmpty()
        assertThat(projected.contestedMarkets).isEmpty()
    }

    @Test
    fun overseasMarketsShareTheCurrentFleetSlotLimit() {
        val initial = fixture(ships = ShippingCapability.OCEAN_1)
        val owner = initial.playerState(p1)
        val state =
            initial.copy(
                players =
                    initial.players +
                        (p1 to
                            owner.copy(
                                advances =
                                    PlayerAdvances(
                                        listOf(AdvanceAcquisition(AdvanceId.OCEAN_NAVIGATION, 1))
                                    )
                            ))
            )

        val projected = forecast(state)
        val overseas = projected.peacefulMarkets.filter { it.usesOverseasSlot }

        assertThat(projected.overseasSlots).isEqualTo(1)
        assertThat(overseas.map { it.destination.value }).containsAtLeast("china", "east-indies")
        assertThat(overseas.all { it.shipping }).isTrue()
    }

    @Test
    fun affordableFutureShippingDoesNotCreateCurrentAccess() {
        val state = fixture(ships = ShippingCapability.NONE)

        val projected = forecast(state)

        assertThat(state.playerState(p1).cash).isGreaterThan(EconomyTables.shipUpgradeCost)
        assertThat(projected.peacefulMarkets.map { it.destination }).doesNotContain(alexandria)
    }

    @Test
    fun hiddenCardsDeckOrderAndSealedPurchaseAmountsLeaveDecisionInputsUnchanged() {
        val initial = fixture()
        val opponent = initial.playerState(p2)
        val before =
            initial.copy(
                deck = listOf("gold-3", "wine-3"),
                players =
                    initial.players +
                        (p2 to
                            opponent.copy(
                                hand = listOf(HandCard("private", CardData.definition("stone-1"))),
                                tokenPurchase = -17,
                            )),
            )
        val after =
            before.copy(
                deck = listOf("wine-3", "stone-1"),
                players =
                    before.players +
                        (p2 to
                            opponent.copy(
                                hand = listOf(HandCard("private", CardData.definition("gold-3"))),
                                tokenPurchase = 24,
                            )),
            )

        val original = before.viewForDecision(p1)
        val changed = after.viewForDecision(p1)

        assertThat(changed).isEqualTo(original)
        assertThat(changed.revealedTokenPurchases).isEmpty()
        assertThat(changed.expansionForecast).isNotNull()
    }

    @Test
    fun cardPlayKeepsItsPeacefulOnlyForecast() {
        val initial = fixture()
        val state =
            initial.copy(
                stage = Stage.PLAY_CARDS,
                turnOrderSelection = TurnOrderSelection(ids),
                board = initial.board.placeMarker(alexandria, p2).board,
                flow = TurnFlow(actor = p1, phaseStarted = true),
            )

        val projected = ExpansionForecastView.from(state, state.playerState(p1))

        assertThat(projected?.contestedMarkets).isEmpty()
        assertThat(state.viewForDecision(p1).expansionForecast).isNull()
    }

    private fun forecast(state: GameState): ExpansionForecastView =
        checkNotNull(state.viewForDecision(p1).expansionForecast) {
            "A token-purchase decision requires its current-capability expansion forecast."
        }

    private fun fixture(ships: ShippingCapability = ShippingCapability.SEAWORTHY_10): GameState {
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(ids.map { Player(it, it.value) }, p1),
            stage = Stage.TOKEN_PURCHASE,
            currentEpoch = Epoch.THREE,
            turnNumber = 2,
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
                            )
                    }
                    .toMap(),
        )
    }
}
