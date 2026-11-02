package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ExpansionInspectionProjectionTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")
    private val florence = ProvinceId("florence")
    private val rome = ProvinceId("rome")

    @Test
    fun impossiblePoolCommitmentIsInspectableWithoutBecomingACandidate() {
        val before = state(pool = 1, board = BoardState().placeMarker(florence, p2).board)

        val view = before.viewForPlayer(p1)
        val inspection = view.expansionInspections.single { it.destination == florence }

        assertThat(view.expansionOffers.map(ExpansionOfferView::destination))
            .doesNotContain(florence)
        assertThat(inspection.blockers).containsExactly(ViolationCode.INSUFFICIENT_EXPANSION_TOKENS)
        assertThat(inspection.availablePool).isEqualTo(1)
        assertThat(inspection.requiredTokens).isGreaterThan(1)
        assertThat(inspection.partialPlacements).isEmpty()
    }

    @Test
    fun exhaustedShippingRemainsInspectableAndAgreesWithExecution() {
        val initial =
            state(
                ships = ShippingCapability.GALLEY_4,
                board = BoardState().placeMarker(rome, p2).board,
            )
        val before = initial.copy(flow = initial.flow.copy(shipped = mapOf(p1 to mapOf(rome to 4))))

        val view = before.viewForPlayer(p1)
        val inspection = view.expansionInspections.single { it.destination == rome }
        val failure =
            assertFailsWith<RuleViolation> {
                before.step(TextCodec.parse("p1: attack rome"))
            }

        assertThat(view.expansionOffers.map(ExpansionOfferView::destination)).doesNotContain(rome)
        assertThat(inspection.blockers).containsExactly(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(inspection.shipping?.used).isEqualTo(4)
        assertThat(inspection.shipping?.capacity).isEqualTo(4)
        assertThat(failure.code).isEqualTo(inspection.blockers.single())
    }

    @Test
    fun accessDiagnosticsNameOnlyTheUnsatisfiedPrintedRequirement() {
        val before =
            state(
                advances =
                    listOf(
                        AdvanceId.THE_HEAVENS,
                        AdvanceId.SEAWORTHY_VESSELS,
                        AdvanceId.OCEAN_NAVIGATION,
                    ),
                ships = ShippingCapability.OCEAN_1,
            )

        val inspection =
            before.viewForPlayer(p1).expansionInspections.single {
                it.destination == ProvinceId("north-america")
            }

        assertThat(inspection.blockers).containsExactly(ViolationCode.PROVINCE_ACCESS_DENIED)
        assertThat(inspection.missingAccess).containsExactly(AccessRequirement.NEW_WORLD)
        assertThat(inspection.reachable).isFalse()
    }

    @Test
    fun shipsDoNotMakeAnUnreachableInlandProvinceAvailable() {
        val before =
            state(
                advances = listOf(AdvanceId.SEAWORTHY_VESSELS),
                ships = ShippingCapability.SEAWORTHY_10,
            )

        val inspection =
            before.viewForPlayer(p1).expansionInspections.single {
                it.destination == ProvinceId("lyons")
            }

        assertThat(before.catalog.map.province(inspection.destination).coastal).isFalse()
        assertThat(inspection.blockers).containsExactly(ViolationCode.UNREACHABLE_PROVINCE)
        assertThat(inspection.missingAccess).isEmpty()
        assertThat(inspection.partialPlacements).isEmpty()
    }

    @Test
    fun fullyOwnedProvinceIsExplicitlyInspectionOnly() {
        val before = state(board = BoardState().placeMarker(florence, p1).board)

        val inspection =
            before.viewForPlayer(p1).expansionInspections.single { it.destination == florence }

        assertThat(inspection.primaryAction).isEqualTo(ExpansionAction.INSPECT)
        assertThat(inspection.requiredTokens).isEqualTo(0)
        assertThat(inspection.partialPlacements).isEmpty()
        assertThat(inspection.blockers).isEmpty()
    }

    @Test
    fun aLegalPartialPlacementSurvivesTheFullCommitmentBlocker() {
        val view = state(pool = 2).viewForPlayer(p1)
        val inspection = view.expansionInspections.single { it.destination == florence }
        val candidate = view.expansionOffers.single { it.destination == florence }

        assertThat(inspection.blockers).containsExactly(ViolationCode.INSUFFICIENT_EXPANSION_TOKENS)
        assertThat(inspection.partialPlacements.map(PartialPlacementView::tokens))
            .containsExactly(1, 2)
        assertThat(candidate).isEqualTo(inspection)
    }

    @Test
    fun exhaustedMarkerSupplyExplainsTheBlockerAndRetainsSmallerPlacements() {
        val board =
            CatalogConfiguration(3)
                .provinces
                .filter { province -> !province.isSatellite && province.id != florence }
                .take(25)
                .fold(BoardState()) { current, province ->
                    current.placeMarker(province.id, p1).board
                }

        val inspection =
            state(board = board).viewForPlayer(p1).expansionInspections.single {
                it.destination == florence
            }

        assertThat(inspection.blockers).containsExactly(ViolationCode.NO_DOMINATION_MARKERS)
        assertThat(inspection.partialPlacements.map(PartialPlacementView::tokens))
            .containsExactly(1, 2, 3)
    }

    @Test
    fun overseasPresenceLimitIsDistinctFromShippingCapacity() {
        val before =
            state(
                advances =
                    listOf(
                        AdvanceId.THE_HEAVENS,
                        AdvanceId.SEAWORTHY_VESSELS,
                        AdvanceId.OCEAN_NAVIGATION,
                    ),
                ships = ShippingCapability.OCEAN_1,
                board = BoardState().replaceWithToken(ProvinceId("china"), p1).board,
            )

        val inspection =
            before.viewForPlayer(p1).expansionInspections.single {
                it.destination == ProvinceId("india")
            }

        assertThat(inspection.blockers).containsExactly(ViolationCode.OVERSEAS_LIMIT_EXCEEDED)
        assertThat(inspection.shipping?.capacity).isNull()
        assertThat(inspection.partialPlacements).isEmpty()
    }

    @Test
    fun waitingPlayersDoNotReceiveExpansionActionDiagnostics() {
        assertThat(state().viewForPlayer(p2).expansionInspections).isEmpty()
    }

    @Test
    fun otherPhasesDoNotReceiveExpansionActionDiagnostics() {
        assertThat(state().copy(stage = Stage.PURCHASE).viewForPlayer(p1).expansionInspections)
            .isEmpty()
    }

    @Test
    fun competitionProjectsPrintedBoxRatherThanTurnPosition() {
        val before = state(board = BoardState().placeMarker(florence, p2).board)

        val offer = before.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

        assertThat(offer.greenDieWins).containsExactly(3, 4, 5, 6).inOrder()
    }

    @Test
    fun proselytismAddsGreenTiesToTheCompetitionPreview() {
        val before =
            state(
                advances = listOf(AdvanceId.PROSELYTISM),
                board = BoardState().placeMarker(florence, p2).board,
            )

        val offer = before.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

        assertThat(offer.greenDieWins).containsExactly(2, 3, 4, 5, 6).inOrder()
    }

    @Test
    fun aChaosDefenderRemovesProselytismFromTheCompetitionPreview() {
        val initial =
            state(
                advances = listOf(AdvanceId.PROSELYTISM),
                board = BoardState().placeMarker(florence, p2).board,
            )
        val before = initial.updatePlayer(p2) { player -> player.copy(misery = MiseryStep.CHAOS) }

        val offer = before.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

        assertThat(offer.greenDieWins).containsExactly(3, 4, 5, 6).inOrder()
    }

    @Test
    fun movingLastRemovesAllStandardGreenWinningFaces() {
        val initial = state(board = BoardState().placeMarker(florence, p2).board)
        val before = initial.copy(turnOrderSelection = TurnOrderSelection(listOf(p2, p3, p1)))

        val offer = before.viewForPlayer(p1).expansionOffers.single { it.destination == florence }

        assertThat(offer.greenDieWins).isEmpty()
    }

    @Test
    fun peacefulPlacementsDoNotProjectCompetitionDice() {
        val view = state().viewForPlayer(p1)

        assertThat(view.expansionInspections.single { it.destination == florence }.greenDieWins)
            .isNull()
    }

    @Test
    fun projectedGreenFacesAgreeWithRecordedCompetitionOutcomes() {
        for (proselytism in listOf(false, true)) {
            for (chaos in listOf(false, true)) {
                for (order in listOf(listOf(p1, p2, p3), listOf(p2, p1, p3), listOf(p2, p3, p1))) {
                    val initial =
                        state(
                            advances =
                                if (proselytism) listOf(AdvanceId.PROSELYTISM) else emptyList(),
                            board = BoardState().placeMarker(florence, p2).board,
                        )
                    val before =
                        initial.copy(turnOrderSelection = TurnOrderSelection(order)).updatePlayer(
                            p2
                        ) { player ->
                            player.copy(
                                misery = if (chaos) MiseryStep.CHAOS else MiseryStep.entries.first()
                            )
                        }
                    val winningFaces =
                        before
                            .viewForPlayer(p1)
                            .expansionOffers
                            .single { it.destination == florence }
                            .greenDieWins
                            .orEmpty()
                    for (green in 1..6) {
                        val after =
                            before
                                .step(TextCodec.parse("p1: attack florence"))
                                .step(TextCodec.parse("!roll competition 6 1 $green"))

                        assertThat(
                                after.board.pieces[florence] ==
                                    ProvincePieces.Marker(p1, PieceSide.WHITE)
                            )
                            .isEqualTo(green in winningFaces)
                    }
                }
            }
        }
    }

    private fun state(
        pool: Int = 20,
        ships: ShippingCapability = ShippingCapability.NONE,
        advances: List<AdvanceId> = emptyList(),
        board: BoardState = BoardState(),
    ): GameState {
        val players = listOf(Player(p1, "First"), Player(p2, "Second"), Player(p3, "Third"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            stage = Stage.EXPANSION,
            turnNumber = 2,
            flow =
                TurnFlow(
                    actor = p1,
                    phaseStarted = true,
                    phaseBookkeepingApplied = true,
                ),
            turnOrderSelection = TurnOrderSelection(players.map(Player::id)),
            board = board,
            players =
                players
                    .mapIndexed { index, player ->
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                poolTokens = if (player.id == p1) pool else 0,
                                stockTokens =
                                    36 -
                                        (if (player.id == p1) pool else 0) -
                                        board.tokenCount(player.id),
                                ships = if (player.id == p1) ships else ShippingCapability.NONE,
                                advances =
                                    PlayerAdvances(
                                        (if (player.id == p1) advances else emptyList()).map {
                                            AdvanceAcquisition(it, 1)
                                        }
                                    ),
                            )
                    }
                    .toMap(),
        )
    }
}
