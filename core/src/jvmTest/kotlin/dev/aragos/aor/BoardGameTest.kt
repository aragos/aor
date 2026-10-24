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
                "place 2 rome ship",
                "place 2 milan land",
                "place 3 rome caravan florence",
                "attack rome",
                "attack florence land",
                "attack sicily ship",
                "attack rome caravan florence",
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

        val placed = state.act("place 4 rome caravan florence")

        assertThat(placed.board.pieces[id("rome")])
            .isEqualTo(ProvincePieces.Marker(p1, PieceSide.WHITE))
        assertThat(placed.playerState(p1).poolTokens).isEqualTo(8)
    }

    @Test
    fun caravanCannotPassThroughAFullEnemyProvinceBeforeWinningIt() {
        val state =
            expansion(
                BoardState().placeMarker(id("florence"), p2).board,
                advances = mapOf(p1 to setOf(AdvanceId.CARAVAN)),
            )

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 rome caravan florence") }

        assertThat(failure.code).isEqualTo(ViolationCode.CARAVAN_INTERMEDIATE_OCCUPIED)
    }

    @Test
    fun failedShippingAttacksRemainChargedAgainstTheDestinationQuota() {
        val state =
            expansion(
                    BoardState().placeMarker(id("florence"), p2).board,
                    advances = mapOf(p1 to setOf(AdvanceId.SEAWORTHY_VESSELS)),
                    ships = mapOf(p1 to ShippingCapability.SEAWORTHY_10),
                )
                .act("attack florence ship")
                .step(
                    GameOutcome.Roll(
                        GameRollKind.COMPETITION,
                        listOf(DieRoll(6), DieRoll(1), DieRoll(1)),
                    )
                )

        val failure = assertFailsWith<RuleViolation> { state.act("attack florence ship") }

        assertThat(failure.code).isEqualTo(ViolationCode.SHIPPING_CAPACITY_EXCEEDED)
        assertThat(state.flow.shipped[p1]).containsExactly(id("florence"), 8)
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
                .act("place 1 china ship")

        val failure = assertFailsWith<RuleViolation> { state.act("place 1 india ship") }

        assertThat(failure.code).isEqualTo(ViolationCode.OVERSEAS_LIMIT_EXCEEDED)
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
        assertThat(bought.prompts()).containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4")))
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

        assertThat(ended.prompts()).containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4")))
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

        assertThat(ended.prompts()).containsExactly(Prompt.System(SystemNeed.Draw(p1, "hand-4")))
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
