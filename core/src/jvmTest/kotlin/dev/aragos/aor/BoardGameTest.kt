package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf

class BoardGameTest {
    private val p1 = PlayerId("p1")
    private val p2 = PlayerId("p2")
    private val p3 = PlayerId("p3")

    @Test
    fun canonicalBoardCommandsRoundTrip() {
        val commands =
            listOf(
                "place 4 florence",
                "attack rome",
                "reduce-marker venice",
                "pass",
                "buy-expansion-card",
                "cathedral",
                "windmill",
                "offer-card hand-17",
            )

        val roundTripped = commands.map { text -> TextCodec.parseCommand(text).toText() }

        assertThat(roundTripped).containsExactlyElementsIn(commands).inOrder()
    }

    @Test
    fun unsupportedRouteArgumentsAreRejectedAsTypedSyntaxErrors() {
        val failure =
            assertFailsWith<RuleViolation> {
                TextCodec.parseCommand("place 2 rome ship caravan florence")
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMMAND)
    }

    @Test
    fun peacefulDominationSpendsThePoolAndReturnsAllConvertedTokensToStock() {
        val state = expansion()

        val placed = state.act("place 4 florence")

        assertThat(placed.playerState(p1).poolTokens).isEqualTo(16)
        assertThat(placed.playerState(p1).stockTokens).isEqualTo(20)
        assertThat(placed.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(placed.flow.markerOrigins[id("florence")])
            .isEqualTo(MarkerPlacementOrigin.EXPANSION)
    }

    @Test
    fun toppingUpOldTokensKeepsTheirReachWithAnImmediateWhiteMarker() {
        val board = BoardState().replaceWithToken(id("rome"), p1).board
        val initial = expansion(board)
        val required = initial.catalog.map.province(id("rome")).marketNumber - 1

        val toppedUp = initial.act("place $required rome")
        val continued = toppedUp.act("place 1 naples")

        assertThat(toppedUp.viewForPlayer(p1).board.single { it.id == id("rome") }.marker)
            .isEqualTo(MarkerView(p1, PieceSide.WHITE))
        assertThat(toppedUp.flow.markerOrigins[id("rome")])
            .isEqualTo(MarkerPlacementOrigin.EXPANSION)
        assertThat(continued.board.hasPresence(p1, id("naples"))).isTrue()
    }

    @Test
    fun dominatingWithOnlyNewTokensDoesNotCreateAnotherBase() {
        val toppedUp = expansion().act("place 4 florence")

        val failure = assertFailsWith<RuleViolation> { toppedUp.act("place 1 rome") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun winningWithOldTokensPreservesTheirExpansionBase() {
        val board =
            BoardState(
                persistentMapOf(
                    id("rome") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )

        val won = expansion(board).act("attack rome").win()
        val continued = won.act("place 1 naples")

        assertThat(won.viewForPlayer(p1).board.single { it.id == id("rome") }.marker)
            .isEqualTo(MarkerView(p1, PieceSide.WHITE))
        assertThat(won.flow.markerOrigins[id("rome")]).isEqualTo(MarkerPlacementOrigin.EXPANSION)
        assertThat(continued.board.hasPresence(p1, id("naples"))).isTrue()
    }

    @Test
    fun losingOldTokensInCompetitionRemovesTheirExpansionBase() {
        val board =
            BoardState(
                persistentMapOf(
                    id("rome") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )
        val pending = expansion(board).act("attack rome")

        val lost =
            pending.step(
                GameOutcome.Roll(
                    GameRollKind.COMPETITION,
                    listOf(DieRoll(6), DieRoll(1), DieRoll(1)),
                )
            )
        val failure = assertFailsWith<RuleViolation> { lost.act("place 1 naples") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
        assertThat(BoardGame.context(lost).bases(p1)).doesNotContain(id("rome"))
    }

    @Test
    fun losingAConvertedMarkerRemovesItsBaseForBothCombatants() {
        val board =
            BoardState()
                .replaceWithToken(id("rome"), p1)
                .board
                .replaceWithToken(id("naples"), p2)
                .board
        val initial = expansion(board)
        val required = initial.catalog.map.province(id("rome")).marketNumber - 1
        val defending = initial.act("place $required rome").act("pass")

        val conquered = defending.act("attack rome", p2).win()
        val context = BoardGame.context(conquered)

        assertThat(context.bases(p1)).doesNotContain(id("rome"))
        assertThat(context.bases(p2)).doesNotContain(id("rome"))
        assertThat(conquered.viewForPlayer(p2).board.single { it.id == id("rome") }.marker)
            .isEqualTo(MarkerView(p2, PieceSide.WHITE))
    }

    @Test
    fun attackingReservesOnlyTheAdditionalRequiredTokensBeforeRequestingDice() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )

        val pending = expansion(board).act("attack florence")

        assertThat(pending.playerState(p1).poolTokens).isEqualTo(16)
        assertThat(pending.flow.decisions.last())
            .isInstanceOf(BoardDecision.CompetitionDice::class.java)
        assertThat(pending.board).isEqualTo(board)
    }

    @Test
    fun losingACompetitionReturnsExistingAndCommittedTokensWithoutChangingTheOuterActor() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p1 to TokenHolding(coloured = 1),
                                p2 to TokenHolding(white = 1),
                            )
                        )
                )
            )
        val pending = expansion(board).act("attack florence")

        val lost =
            pending.step(
                GameOutcome.Roll(
                    GameRollKind.COMPETITION,
                    listOf(DieRoll(6), DieRoll(1), DieRoll(1)),
                )
            )

        assertThat(lost.playerState(p1).stockTokens).isEqualTo(20)
        assertThat(lost.playerState(p1).poolTokens).isEqualTo(16)
        assertThat(lost.flow.actor).isEqualTo(p1)
        assertThat(lost.flow.decisions).isEmpty()
        assertThat(lost.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(white = 1))))
    }

    @Test
    fun winningACompetitionReturnsTheExactCommitmentToStockAndCreatesAWhiteMarker() {
        val board = BoardState().placeMarker(id("florence"), p2).board

        val won = expansion(board).act("attack florence").win()

        assertThat(won.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(won.playerState(p1).stockTokens).isEqualTo(24)
        assertThat(won.playerState(p1).poolTokens).isEqualTo(12)
    }

    @Test
    fun excessAttackTokensAreRejectedWithoutSpendingAnyResources() {
        val state = expansion(BoardState().placeMarker(id("florence"), p2).board)

        val failure = assertFailsWith<RuleViolation> { state.act("place 9 florence") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_ATTACK_TOKEN_COUNT)
        assertThat(state.playerState(p1).poolTokens).isEqualTo(20)
        assertThat(state.flow.decisions).isEmpty()
    }

    @Test
    fun caravanCanAttackBeyondAWhiteProvinceWonEarlierThisExpansion() {
        val state =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.CARAVAN)),
                )
                .act("attack florence")
                .win()

        val placed = state.act("place 4 rome")

        assertThat(placed.board.pieces[id("rome")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(placed.playerState(p1).poolTokens).isEqualTo(8)
    }

    @Test
    fun caravanCannotPassThroughAFullEnemyProvinceBeforeWinningIt() {
        val state =
            expansion(
                BoardState()
                    .placeMarker(id("florence"), p2)
                    .board
                    .placeMarker(id("genoa"), p2)
                    .board,
                advances = mapOf(p1 to setOf(AdvanceId.CARAVAN)),
            )

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 rome") }

        assertThat(failure.code).isEqualTo(ViolationCode.UNREACHABLE_PROVINCE)
    }

    @Test
    fun failedShippingAttacksRemainChargedAgainstTheDestinationQuota() {
        val state =
            expansion(
                    BoardState().placeMarker(id("rome"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.SEAWORTHY_VESSELS)),
                    ships = mapOf(p1 to ShippingCapability.SEAWORTHY_10),
                )
                .act("attack rome")
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(6), DieRoll(1), DieRoll(1)),
                    )
                )

        val failure = assertFailsWith<RuleViolation> { state.act("attack rome") }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(state.flow.shipped[p1]).containsExactly(id("rome"), 8)
        assertThat(state.playerState(p1).poolTokens).isEqualTo(12)
    }

    @Test
    fun automaticTransportPrefersLandFromAnyBaseOverShippingFromAnEarlierBase() {
        val state =
            expansion(
                BoardState().replaceWithToken(id("rome"), p1).board,
                advances = mapOf(p1 to setOf(AdvanceId.SEAWORTHY_VESSELS)),
                ships = mapOf(p1 to ShippingCapability.SEAWORTHY_10),
            )

        val placed = state.act("place 1 florence")

        assertThat(placed.flow.shipped).isEmpty()
    }

    @Test
    fun advertisedGalleyPlacementUsesTheSameAutomaticRouteDuringExecution() {
        val state = expansion(ships = mapOf(p1 to ShippingCapability.GALLEY_4))
        val choices = state.legalChoices(p1).commands

        val placed = state.act("place 1 rome")

        assertThat(choices).contains("place 1 rome")
        assertThat(placed.flow.shipped[p1]).containsExactly(id("rome"), 1)
        assertThat(placed.playerState(p1).poolTokens).isEqualTo(19)
    }

    @Test
    fun exhaustedShippingDoesNotHideOrRejectALegalLandPlacement() {
        val state =
            expansion(ships = mapOf(p1 to ShippingCapability.GALLEY_4))
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            shipped = mapOf(p1 to mapOf(id("florence") to 4)),
                        )
                )
        val choices = state.legalChoices(p1).commands

        val placed = state.act("place 1 florence")

        assertThat(choices).contains("place 1 florence")
        assertThat(placed.flow.shipped).isEqualTo(state.flow.shipped)
        assertThat(placed.playerState(p1).poolTokens).isEqualTo(19)
    }

    @Test
    fun exhaustedGalleyOnlyPlacementIsNeitherAdvertisedNorAccepted() {
        val state =
            expansion(ships = mapOf(p1 to ShippingCapability.GALLEY_4))
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            shipped = mapOf(p1 to mapOf(id("rome") to 4)),
                        )
                )
        val choices = state.legalChoices(p1).commands

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 rome") }

        assertThat(choices).doesNotContain("place 1 rome")
        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(state.playerState(p1).poolTokens).isEqualTo(20)
    }

    @Test
    fun whitePresenceConsumesAnOverseasOccupancySlot() {
        val state =
            expansion(
                    advances =
                        mapOf(
                            p1 to
                                setOf(
                                    AdvanceId.OCEAN_NAVIGATION,
                                    AdvanceId.SEAWORTHY_VESSELS,
                                )
                        ),
                    ships = mapOf(p1 to ShippingCapability.OCEAN_1),
                )
                .act("place 1 china")

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 india") }

        assertThat(failure.code).isEqualTo(ViolationCode.OVERSEAS_LIMIT_EXCEEDED)
    }

    @Test
    fun anAttackerWithCathedralCanDeclareOrPassBeforeCompetitionDice() {
        val state =
            expansion(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)),
            )

        val pending = state.act("attack florence")

        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p1, "cathedral"))
        assertThat(pending.legalChoices(p1).commands).containsExactly("cathedral", "pass")
        assertThat(pending.flow.actor).isEqualTo(p1)
    }

    @Test
    fun attackingCathedralWinsWithoutDiceAndRecordsEveryDefendingOpponent() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(
                            persistentMapOf(
                                p2 to TokenHolding(coloured = 1),
                                p3 to TokenHolding(coloured = 1),
                            )
                        )
                )
            )
        val pending =
            expansion(board, advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)))
                .act("attack florence")

        val conquered = pending.act("cathedral")

        assertThat(conquered.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(conquered.flow.cathedralUses).containsExactly(p1 to p2, p1 to p3)
        assertThat(conquered.flow.decisions).isEmpty()
        assertThat(conquered.flow.actor).isEqualTo(p1)
    }

    @Test
    fun passingAttackingCathedralRequestsDiceWithoutSpendingItsUseOrChangingTheActor() {
        val pending =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)),
                )
                .act("attack florence")

        val passed = pending.act("pass")

        assertThat(passed.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.COMPETITION, 3)))
        assertThat(passed.flow.cathedralUses).isEmpty()
        assertThat(passed.flow.actor).isEqualTo(p1)
    }

    @Test
    fun attackingCathedralCannotBeOfferedAgainAgainstTheSameOpponent() {
        val state =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)),
                )
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            cathedralUses = setOf(p1 to p2),
                        )
                )

        val pending = state.act("attack florence")

        assertThat(pending.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.COMPETITION, 3)))
    }

    @Test
    fun attackingCathedralRemainsAvailableAgainstADifferentOpponent() {
        val state =
            expansion(
                    BoardState().placeMarker(id("florence"), p3).board,
                    advances = mapOf(p1 to setOf(AdvanceId.CATHEDRAL)),
                )
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            cathedralUses = setOf(p1 to p2),
                        )
                )

        val pending = state.act("attack florence")

        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p1, "cathedral"))
    }

    @Test
    fun neitherSideCanDeclareCathedralWhenTheAttackerAndDefenderBothOwnIt() {
        val state =
            expansion(
                BoardState().placeMarker(id("florence"), p2).board,
                advances =
                    mapOf(
                        p1 to setOf(AdvanceId.CATHEDRAL),
                        p2 to setOf(AdvanceId.CATHEDRAL),
                    ),
            )

        val pending = state.act("attack florence")

        assertThat(pending.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.COMPETITION, 3)))
    }

    @Test
    fun aDefendersCathedralInterruptionPreservesTheAttackingActor() {
        val state =
            expansion(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
            )

        val pending = state.act("attack florence")

        assertThat(pending.flow.actor).isEqualTo(p1)
        assertThat(pending.prompts()).containsExactly(Prompt.SinglePlayer(p2, "cathedral"))
        assertThat(pending.legalChoices(p2).commands).containsExactly("cathedral", "pass")
    }

    @Test
    fun aDefendersCathedralConsumesThePerOpponentUseAndReturnsTheAttackersTokens() {
        val pending =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
                )
                .act("attack florence")

        val defended = pending.act("cathedral", p2)

        assertThat(defended.flow.cathedralUses).containsExactly(p2 to p1)
        assertThat(defended.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(p2, PieceSide.COLOURED))
        assertThat(defended.playerState(p1).poolTokens).isEqualTo(12)
        assertThat(defended.playerState(p1).stockTokens).isEqualTo(24)
    }

    @Test
    fun passingCathedralRequestsTheCompetitionDice() {
        val pending =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p2 to setOf(AdvanceId.CATHEDRAL)),
                )
                .act("attack florence")

        val passed = requireNotNull(BoardGame.passDecision(pending, p2))

        assertThat(passed.flow.decisions.last())
            .isInstanceOf(BoardDecision.CompetitionDice::class.java)
        assertThat(passed.flow.cathedralUses).isEmpty()
    }

    @Test
    fun theFirstExpansionCardCostsThreePoolTokensAndQueuesARecordedDraw() {
        val state = expansion()

        val bought = state.act("buy-expansion-card")

        assertThat(bought.playerState(p1).poolTokens).isEqualTo(17)
        assertThat(bought.playerState(p1).stockTokens).isEqualTo(19)
        assertThat(bought.flow.expansionCardCount).isEqualTo(1)
        assertThat(bought.prompts())
            .containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4", state.deck.first())))
    }

    @Test
    fun laterExpansionCardPricesUseTheSharedPhaseCounter() {
        val state =
            expansion()
                .copy(
                    flow =
                        TurnFlow(
                            actor = p2,
                            cursor = 1,
                            phaseStarted = true,
                            expansionCards = setOf(p1),
                            expansionCardCount = 1,
                        )
                )

        val bought = state.act("buy-expansion-card", p2)

        assertThat(bought.playerState(p2).poolTokens).isEqualTo(4)
        assertThat(bought.flow.expansionCardCount).isEqualTo(2)
    }

    @Test
    fun aPlayerCannotBuyTwoExpansionCardsInOnePhase() {
        val state =
            expansion()
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            expansionCards = setOf(p1),
                            expansionCardCount = 1,
                        )
                )

        val failure = assertFailsWith<RuleViolation> { state.act("buy-expansion-card") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_CARD_PURCHASE)
    }

    @Test
    fun endingExpansionReturnsTheUnusedPoolAndAdvancesTheActor() {
        val state = expansion()

        val ended = state.act("pass")

        assertThat(ended.playerState(p1).poolTokens).isEqualTo(0)
        assertThat(ended.playerState(p1).stockTokens).isEqualTo(36)
        assertThat(ended.flow.actor).isEqualTo(p2)
    }

    @Test
    fun voluntaryReductionAndRebuildingNeverEarnsExpansionBonusCredit() {
        val state =
            expansion(BoardState().placeMarker(id("venice"), p1).board).act("reduce-marker venice")

        val rebuilt = state.act("place 4 venice")

        assertThat(rebuilt.flow.markerOrigins[id("venice")])
            .isEqualTo(MarkerPlacementOrigin.VOLUNTARY_REBUILD)
    }

    @Test
    fun markerReductionPreviewMatchesStockFundedConsequences() {
        val state = expansion(BoardState().placeMarker(id("venice"), p1).board)
        val offer = state.viewForPlayer(p1).markerReductionOffers.single()

        val reduced = state.act(offer.command)

        assertThat(offer.province).isEqualTo(id("venice"))
        assertThat(offer.poolAfter).isEqualTo(reduced.playerState(p1).poolTokens)
        assertThat(offer.stockAfter).isEqualTo(reduced.playerState(p1).stockTokens)
        assertThat(offer.stockBefore - offer.stockAfter).isEqualTo(1)
        assertThat(offer.incomeBefore).isGreaterThan(offer.incomeAfter)
        assertThat(offer.incomeAfter)
            .isEqualTo(reduced.viewForPlayer(p1).players.first().income?.ordinary)
        assertThat(state.viewForPlayer(p2).markerReductionOffers).isEmpty()
    }

    @Test
    fun markerReductionPreviewUsesPoolWhenStockIsEmpty() {
        val state =
            expansion(BoardState().placeMarker(id("venice"), p1).board).updatePlayer(p1) { player ->
                player.copy(stockTokens = 0, poolTokens = 36)
            }
        val offer = state.viewForPlayer(p1).markerReductionOffers.single()

        val reduced = state.act(offer.command)

        assertThat(offer.poolBefore - offer.poolAfter).isEqualTo(1)
        assertThat(offer.poolAfter).isEqualTo(reduced.playerState(p1).poolTokens)
        assertThat(offer.stockAfter).isEqualTo(0)
    }

    @Test
    fun aWhiteMarkerCannotBeReducedToCreateANewColouredBase() {
        val state = expansion(BoardState().placeMarker(id("rome"), p1, PieceSide.WHITE).board)

        val failure = assertFailsWith<RuleViolation> { state.act("reduce-marker rome") }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_BOARD_MUTATION)
    }

    @Test
    fun rebuildingAnEventReducedMarkerRetainsItsConfiguredBonusOrigin() {
        val state =
            expansion(BoardState().replaceWithToken(id("venice"), p1).board)
                .copy(
                    flow =
                        TurnFlow(
                            actor = p1,
                            phaseStarted = true,
                            eventReduced = setOf(id("venice")),
                        )
                )

        val rebuilt = state.act("place 4 venice")

        assertThat(rebuilt.flow.markerOrigins[id("venice")])
            .isEqualTo(MarkerPlacementOrigin.EVENT_REBUILD)
    }

    @Test
    fun eventRebuiltMarkersEarnTheBonusWhenTheOptionIncludesThem() {
        val state =
            expansion(BoardState().placeMarker(id("venice"), p1, PieceSide.WHITE).board)
                .copy(
                    rebuiltMarkerBonus = RebuiltMarkerBonus.INCLUDE,
                    flow =
                        TurnFlow(
                            actor = p3,
                            cursor = 2,
                            phaseStarted = true,
                            markerOrigins =
                                mapOf(id("venice") to MarkerPlacementOrigin.EVENT_REBUILD),
                        ),
                )

        val ended = state.act("pass", p3)

        assertThat(ended.prompts())
            .containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4", state.deck.first())))
    }

    @Test
    fun eventRebuiltMarkersDoNotEarnTheBonusWithTheDefaultOption() {
        val state =
            expansion(BoardState().placeMarker(id("venice"), p1, PieceSide.WHITE).board)
                .copy(
                    flow =
                        TurnFlow(
                            actor = p3,
                            cursor = 2,
                            phaseStarted = true,
                            markerOrigins =
                                mapOf(id("venice") to MarkerPlacementOrigin.EVENT_REBUILD),
                        )
                )

        val ended = state.act("pass", p3)

        assertThat(ended.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.SHORTAGE, 3)))
    }

    @Test
    fun expansionBonusTiesGoToTheEarlierExpansionPlayer() {
        val state =
            expansion(
                    BoardState()
                        .placeMarker(id("florence"), p1, PieceSide.WHITE)
                        .board
                        .placeMarker(id("rome"), p2, PieceSide.WHITE)
                        .board
                )
                .copy(flow = TurnFlow(actor = p3, cursor = 2, phaseStarted = true))

        val ended = state.act("pass", p3)

        assertThat(ended.prompts())
            .containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4", state.deck.first())))
    }

    @Test
    fun capturingAControlledMarketOpensTheOptionalWindmillDecision() {
        val won =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)),
                )
                .act("attack florence")
                .win()

        assertThat(won.flow.decisions.last())
            .isEqualTo(BoardDecision.WindmillOpportunity(p1, p2, id("florence")))
    }

    @Test
    fun takingCoexistingTokensDoesNotOpenWindmill() {
        val board =
            BoardState(
                persistentMapOf(
                    id("florence") to
                        ProvincePieces.Tokens(persistentMapOf(p2 to TokenHolding(coloured = 1)))
                )
            )

        val won =
            expansion(board, advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)))
                .act("attack florence")
                .win()

        assertThat(won.flow.decisions).isEmpty()
    }

    @Test
    fun windmillPaymentUsesExpansionTokensAndIsChargedBeforeItsRoll() {
        val opportunity =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)),
                )
                .act("attack florence")
                .win()

        val paid = opportunity.act("windmill")

        assertThat(paid.playerState(p1).poolTokens).isEqualTo(8)
        assertThat(paid.playerState(p1).stockTokens).isEqualTo(28)
        assertThat(paid.flow.windmillUsed).containsExactly(p1)
        assertThat(paid.prompts())
            .containsExactly(Prompt.System(SystemNeed.GameRoll(GameRollKind.WINDMILL, 1)))
    }

    @Test
    fun successfulWindmillExchangesTheChosenCardForARecordedRandomVictimCard() {
        val offered = expansion().playerState(p1).hand.single()
        val received = expansion().playerState(p2).hand.single()
        val pending =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)),
                )
                .act("attack florence")
                .win()
                .act("windmill")
                .step(GameOutcome.Roll(GameRollKind.WINDMILL, listOf(DieRoll(4))))
                .act("offer-card ${offered.reference}")

        val traded = pending.step(GameOutcome.Trade(p2, p1, received.reference))

        assertThat(traded.playerState(p1).hand).containsExactly(received)
        assertThat(traded.playerState(p2).hand).containsExactly(offered)
        assertThat(traded.flow.decisions).isEmpty()
        assertThat(traded.flow.actor).isEqualTo(p1)
    }

    @Test
    fun failedWindmillConsumesTheAttemptWithoutRequestingAHandReference() {
        val pending =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.WIND_WATERMILL)),
                )
                .act("attack florence")
                .win()
                .act("windmill")

        val failed = pending.step(GameOutcome.Roll(GameRollKind.WINDMILL, listOf(DieRoll(6))))

        assertThat(failed.flow.decisions).isEmpty()
        assertThat(failed.flow.windmillUsed).containsExactly(p1)
    }

    @Test
    fun onlyTheTwoTradeParticipantsAreEntitledToTheOfferedHandReference() {
        val pending = expansion().pushDecision(BoardDecision.TradeOffer(p1, p2))

        assertThat(BoardGame.tradeParticipants(pending)).containsExactly(p1, p2)
    }

    @Test
    fun aPlayerCannotActWhileAnotherPlayerOwnsTheExpansionPhase() {
        val state = expansion()

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 florence", p2) }

        assertThat(failure.code).isEqualTo(ViolationCode.WRONG_PLAYER)
    }

    private fun expansion(
        board: BoardState = BoardState(),
        advances: Map<PlayerId, Set<AdvanceId>> = emptyMap(),
        ships: Map<PlayerId, ShippingCapability> = emptyMap(),
    ): GameState {
        val players = listOf(Player(p1, "One"), Player(p2, "Two"), Player(p3, "Three"))
        val capitals = listOf("venice", "genoa", "barcelona")
        return GameState(
            header = GameHeader(players, p1),
            turnNumber = 1,
            stage = Stage.EXPANSION,
            flow = TurnFlow(actor = p1, phaseStarted = true),
            turnOrderSelection = TurnOrderSelection(listOf(p1, p2, p3)),
            board = board,
            deck = CardData.epochOne.drop(3).map(CardDefinition::id),
            deckHistory = DeckHistory(nextDraw = 4),
            players =
                players
                    .mapIndexed { index, player ->
                        val pool = if (player.id == p1) 20 else 10
                        player.id to
                            PlayerState(
                                player = player,
                                capital = capitals[index],
                                hand =
                                    listOf(HandCard("hand-${index + 1}", CardData.epochOne[index])),
                                poolTokens = pool,
                                stockTokens = 36 - pool - board.tokenCount(player.id),
                                advances =
                                    PlayerAdvances(
                                        advances[player.id].orEmpty().map { advance ->
                                            AdvanceAcquisition(advance, turnNumber = 1)
                                        }
                                    ),
                                ships = ships[player.id] ?: ShippingCapability.NONE,
                            )
                    }
                    .toMap(),
        )
    }

    private fun GameState.act(text: String, actor: PlayerId = p1): GameState =
        step(Entry.Action(actor, TextCodec.parseCommand(text)))

    private fun GameState.win(): GameState =
        step(
            GameOutcome.Roll(
                GameRollKind.COMPETITION,
                listOf(DieRoll(1), DieRoll(6), DieRoll(6)),
            )
        )

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
