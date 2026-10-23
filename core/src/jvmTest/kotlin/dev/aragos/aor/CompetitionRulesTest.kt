package dev.aragos.aor

import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap

class CompetitionRulesTest {
    private val venice = PlayerId("p1")
    private val genoa = PlayerId("p2")
    private val barcelona = PlayerId("p3")
    private val success =
        CompetitionRoll(white = DieRoll(1), black = DieRoll(6), green = DieRoll(1))
    private val failure =
        CompetitionRoll(white = DieRoll(6), black = DieRoll(1), green = DieRoll(1))

    @Test
    fun aPeacefulPartialPlacementPreservesExistingColouredTokens() {
        val context = context(board = tokens("florence", venice to TokenHolding(coloured = 1)))
        val plan =
            BoardPlacementRules.evaluate(
                context,
                venice,
                id("florence"),
                addedTokens = 1,
                route = ExpansionRoute.Land(id("venice")),
                poolTokens = 1,
            ) as PlacementPlan.Peaceful

        val change = BoardPlacementRules.applyPeaceful(context, plan)

        assertThat(change.board.pieces[id("florence")])
            .isEqualTo(
                ProvincePieces.Tokens(
                    persistentMapOf(venice to TokenHolding(coloured = 1, white = 1))
                )
            )
        assertThat(change.tokensRequired).containsExactly(venice, 1)
        assertThat(change.tokensReturned).isEmpty()
    }

    @Test
    fun aFullUncontestedMarketConvertsToAWhiteMarkerAndReturnsAllTokens() {
        val context = context(board = tokens("florence", venice to TokenHolding(coloured = 1)))
        val plan =
            BoardPlacementRules.evaluate(
                context,
                venice,
                id("florence"),
                addedTokens = 3,
                route = ExpansionRoute.Land(id("venice")),
                poolTokens = 3,
            ) as PlacementPlan.Peaceful

        val change = BoardPlacementRules.applyPeaceful(context, plan)

        assertThat(change.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(venice, PieceSide.WHITE))
        assertThat(change.tokensRequired).containsExactly(venice, 3)
        assertThat(change.tokensReturned).containsExactly(venice, 4)
    }

    @Test
    fun playersCanCoexistWithoutRollingAtOrBelowTheMarketNumber() {
        val context = context(board = tokens("florence", genoa to TokenHolding(coloured = 2)))

        val plan =
            BoardPlacementRules.evaluate(
                context,
                venice,
                id("florence"),
                addedTokens = 2,
                route = ExpansionRoute.Land(id("venice")),
                poolTokens = 2,
            )

        assertThat(plan).isInstanceOf(PlacementPlan.Peaceful::class.java)
    }

    @Test
    fun anAttackWithExcessCommitmentFailsBeforeAnyPiecesChange() {
        val context = context(board = BoardState().placeMarker(id("florence"), genoa).board)

        val failure =
            assertFailsWith<RuleViolation> {
                BoardPlacementRules.evaluate(
                    context,
                    venice,
                    id("florence"),
                    addedTokens = 9,
                    route = ExpansionRoute.Land(id("venice")),
                    poolTokens = 9,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_ATTACK_TOKEN_COUNT)
        assertThat(context.board.markerCount(genoa)).isEqualTo(1)
    }

    @Test
    fun anAttackCannotCommitMoreTokensThanItsExpansionPool() {
        val context = context(board = BoardState().placeMarker(id("florence"), genoa).board)

        val failure =
            assertFailsWith<RuleViolation> {
                BoardPlacementRules.evaluate(
                    context,
                    venice,
                    id("florence"),
                    addedTokens = 8,
                    route = ExpansionRoute.Land(id("venice")),
                    poolTokens = 7,
                )
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INSUFFICIENT_EXPANSION_TOKENS)
    }

    @Test
    fun defendingInOnesOriginalCapitalDoublesItsPieces() {
        val context = context(board = BoardState().placeMarker(id("genoa"), genoa).board)

        assertThat(CompetitionRules.requirement(context, venice, id("genoa")).requirement.tokens)
            .isEqualTo(15)
    }

    @Test
    fun retakingOnesOriginalCapitalDoublesTheAttackersPieces() {
        val context = context(board = BoardState().placeMarker(id("genoa"), venice).board)

        assertThat(CompetitionRules.requirement(context, genoa, id("genoa")).requirement.tokens)
            .isEqualTo(5)
    }

    @Test
    fun nationalismIsAddedBeforeDefendingCapitalStrengthIsDoubled() {
        val context =
            context(
                board = BoardState().placeMarker(id("genoa"), genoa).board,
                advances = mapOf(genoa to setOf(AdvanceId.NATIONALISM)),
            )

        val breakdown = CompetitionRules.requirement(context, venice, id("genoa"))

        assertThat(breakdown.defenderNationalism).isEqualTo(2)
        assertThat(breakdown.requirement.tokens).isEqualTo(17)
    }

    @Test
    fun nationalismIsAddedBeforeAttackingCapitalStrengthIsDoubled() {
        val context =
            context(
                board = BoardState().placeMarker(id("genoa"), venice).board,
                advances = mapOf(genoa to setOf(AdvanceId.NATIONALISM)),
            )

        val breakdown = CompetitionRules.requirement(context, genoa, id("genoa"))

        assertThat(breakdown.attackerNationalism).isEqualTo(2)
        assertThat(breakdown.requirement.tokens).isEqualTo(5)
    }

    @Test
    fun physicalAttackerTokensNeverFallBelowThePrintedMarketNumber() {
        val context = context(board = tokens("genoa", venice to TokenHolding(coloured = 1)))

        assertThat(CompetitionRules.requirement(context, genoa, id("genoa")).requirement.tokens)
            .isEqualTo(5)
    }

    @Test
    fun colouredSupportingSatellitesDefendAWhiteMarker() {
        val board =
            BoardState()
                .placeMarker(id("barcelona"), genoa, PieceSide.WHITE)
                .board
                .replaceWithToken(id("palma"), genoa)
                .board
        val context = context(board = board)

        val breakdown = CompetitionRules.requirement(context, venice, id("barcelona"))

        assertThat(breakdown.defensiveSatellites).isEqualTo(1)
        assertThat(breakdown.requirement.tokens).isEqualTo(7)
    }

    @Test
    fun whiteSatelliteTokensGiveNoDefensiveSupport() {
        val board =
            BoardState()
                .placeMarker(id("barcelona"), genoa)
                .board
                .replaceWithToken(id("palma"), genoa, PieceSide.WHITE)
                .board

        assertThat(
                CompetitionRules.requirement(context(board), venice, id("barcelona"))
                    .defensiveSatellites
            )
            .isEqualTo(0)
    }

    @Test
    fun satelliteArrowsDoNotSupportOrdinaryDefendingTokens() {
        val board =
            tokens("barcelona", genoa to TokenHolding(coloured = 1))
                .replaceWithToken(id("palma"), genoa)
                .board

        assertThat(
                CompetitionRules.requirement(context(board), venice, id("barcelona"))
                    .defensiveSatellites
            )
            .isEqualTo(0)
    }

    @Test
    fun cosmopolitanUsesTheAttackersColouredSupportingSatellites() {
        val board =
            BoardState()
                .placeMarker(id("barcelona"), genoa)
                .board
                .replaceWithToken(id("palma"), venice)
                .board
        val context = context(board, advances = mapOf(venice to setOf(AdvanceId.COSMOPOLITAN)))

        assertThat(
                CompetitionRules.requirement(context, venice, id("barcelona")).offensiveSatellites
            )
            .isEqualTo(1)
    }

    @Test
    fun armourAndStirrupsBothContributeAgainstAnUnprotectedOpponent() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                military =
                    listOf(
                        MilitaryPlay(venice, MilitaryCard.ARMOR),
                        MilitaryPlay(venice, MilitaryCard.STIRRUPS),
                    ),
            )

        assertThat(CompetitionRules.requirement(context, venice, id("florence")).requirement.tokens)
            .isEqualTo(6)
    }

    @Test
    fun armourCancelsAnOpponentsStirrups() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                military =
                    listOf(
                        MilitaryPlay(venice, MilitaryCard.ARMOR),
                        MilitaryPlay(genoa, MilitaryCard.STIRRUPS),
                    ),
            )

        val breakdown = CompetitionRules.requirement(context, venice, id("florence"))

        assertThat(breakdown.defensiveMilitary).isEmpty()
        assertThat(breakdown.requirement.tokens).isEqualTo(7)
    }

    @Test
    fun aMilitaryPenaltyAppliesWhenAnyDefenderHasIt() {
        val context =
            context(
                board =
                    tokens(
                        "florence",
                        genoa to TokenHolding(coloured = 1),
                        barcelona to TokenHolding(white = 1),
                    ),
                military = listOf(MilitaryPlay(barcelona, MilitaryCard.ARMOR)),
            )

        assertThat(CompetitionRules.requirement(context, venice, id("florence")).requirement.tokens)
            .isEqualTo(7)
    }

    @Test
    fun anAttackingMilitaryBenefitMustApplyAgainstEveryDefender() {
        val context =
            context(
                board =
                    tokens(
                        "florence",
                        genoa to TokenHolding(coloured = 1),
                        barcelona to TokenHolding(white = 1),
                    ),
                military =
                    listOf(
                        MilitaryPlay(venice, MilitaryCard.STIRRUPS),
                        MilitaryPlay(barcelona, MilitaryCard.ARMOR),
                    ),
            )

        assertThat(CompetitionRules.requirement(context, venice, id("florence")).offensiveMilitary)
            .isEmpty()
    }

    @Test
    fun nationalismCancelsWhenBothSidesOwnItInTheirSharedHomeArea() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances =
                    mapOf(
                        venice to setOf(AdvanceId.NATIONALISM),
                        genoa to setOf(AdvanceId.NATIONALISM),
                    ),
            )

        val breakdown = CompetitionRules.requirement(context, venice, id("florence"))

        assertThat(breakdown.attackerNationalism).isEqualTo(0)
        assertThat(breakdown.defenderNationalism).isEqualTo(0)
    }

    @Test
    fun chaosDefendersRetainTheirNationalismAndBlockAttackerAdvances() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances =
                    mapOf(
                        venice to setOf(AdvanceId.NATIONALISM),
                        genoa to setOf(AdvanceId.NATIONALISM),
                    ),
                chaos = setOf(genoa),
            )

        val breakdown = CompetitionRules.requirement(context, venice, id("florence"))

        assertThat(breakdown.attackerNationalism).isEqualTo(0)
        assertThat(breakdown.defenderNationalism).isEqualTo(1)
    }

    @Test
    fun aFailedAttackReturnsAllAttackerTokensIncludingPreexistingOnes() {
        val context =
            context(
                board =
                    tokens(
                        "florence",
                        venice to TokenHolding(coloured = 1),
                        genoa to TokenHolding(white = 1),
                    )
            )
        val plan = attack(context, destination = "florence")

        val resolution = CompetitionRules.resolve(context, plan, failure, orderBox = 6)

        assertThat(resolution.change.tokensReturned).containsExactly(venice, 5)
        assertThat(resolution.change.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(genoa to TokenHolding(white = 1))))
        assertThat(resolution.change.tokensRequired).isEmpty()
    }

    @Test
    fun winningAnAttackReturnsAllCombatTokensAndCreatesAWhiteMarker() {
        val context =
            context(
                board =
                    tokens(
                        "florence",
                        venice to TokenHolding(coloured = 1),
                        genoa to TokenHolding(white = 1),
                    )
            )
        val plan = attack(context, destination = "florence")

        val resolution = CompetitionRules.resolve(context, plan, success, orderBox = 6)

        assertThat(resolution.change.tokensReturned).containsExactly(venice, 5, genoa, 1)
        assertThat(resolution.change.board.pieces[id("florence")])
            .isEqualTo(ProvincePieces.Marker(venice, PieceSide.WHITE))
    }

    @Test
    fun winningAgainstAMarkerReturnsTheDefendersMarkerToItsMat() {
        val context = context(board = BoardState().placeMarker(id("florence"), genoa).board)
        val plan = attack(context, destination = "florence")

        val resolution = CompetitionRules.resolve(context, plan, success, orderBox = 6)

        assertThat(resolution.change.markersReturned).containsExactly(genoa, 1)
        assertThat(resolution.change.markersRequired).containsExactly(venice, 1)
        assertThat(resolution.change.tokensReturned).containsExactly(venice, 8)
    }

    @Test
    fun aCapturedSatelliteRetainsOneWhiteTokenAndReturnsTheRest() {
        val context =
            context(
                board =
                    BoardState()
                        .replaceWithToken(id("bari"), genoa)
                        .board
                        .placeMarker(id("naples"), venice)
                        .board
            )
        val plan = attack(context, destination = "bari")

        val resolution = CompetitionRules.resolve(context, plan, success, orderBox = 6)

        assertThat(resolution.change.board.pieces[id("bari")])
            .isEqualTo(ProvincePieces.Tokens(persistentMapOf(venice to TokenHolding(white = 1))))
        assertThat(resolution.change.tokensReturned).containsExactly(genoa, 1, venice, 1)
        assertThat(resolution.change.markersRequired).isEmpty()
    }

    @Test
    fun militaryAdvantageWinsWhenBlackAndWhiteDiceAreEqual() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                military = listOf(MilitaryPlay(venice, MilitaryCard.ARMOR)),
            )
        val plan = attack(context, destination = "florence")

        val resolution =
            CompetitionRules.resolve(
                context,
                plan,
                CompetitionRoll(DieRoll(3), DieRoll(3), DieRoll(1)),
                orderBox = 6,
            )

        assertThat(resolution.attackerWon).isTrue()
    }

    @Test
    fun proselytismWinsWhenGreenEqualsThePrintedOrderBox() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances = mapOf(venice to setOf(AdvanceId.PROSELYTISM)),
            )
        val plan = attack(context, destination = "florence")

        val resolution =
            CompetitionRules.resolve(
                context,
                plan,
                CompetitionRoll(DieRoll(6), DieRoll(1), DieRoll(4)),
                orderBox = 4,
            )

        assertThat(resolution.attackerWon).isTrue()
    }

    @Test
    fun anAttackCannotResolveAfterTheBoardHasChanged() {
        val context = context(board = BoardState().placeMarker(id("florence"), genoa).board)
        val plan = attack(context, destination = "florence")
        val changed = context.copy(board = context.board.placeMarker(id("rome"), barcelona).board)

        val failure =
            assertFailsWith<RuleViolation> {
                CompetitionRules.resolve(changed, plan, success, orderBox = 6)
            }

        assertThat(failure.code).isEqualTo(ViolationCode.INVALID_COMPETITION)
    }

    @Test
    fun attackingCathedralCannotBypassADefendersCathedral() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances =
                    mapOf(
                        venice to setOf(AdvanceId.CATHEDRAL),
                        genoa to setOf(AdvanceId.CATHEDRAL),
                    ),
            )
        val plan = attack(context, destination = "florence")

        assertThat(CompetitionRules.cathedralUsers(context, plan, emptyMap())).isEmpty()
    }

    @Test
    fun aDefendersCathedralWinsTheCompetitionAndRecordsItsOpponent() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances = mapOf(genoa to setOf(AdvanceId.CATHEDRAL)),
            )
        val plan = attack(context, destination = "florence")

        val resolution = CompetitionRules.resolveCathedral(context, plan, genoa, emptyMap())

        assertThat(resolution.attackerWon).isFalse()
        assertThat(resolution.cathedralOpponents).containsExactly(venice)
        assertThat(resolution.change.tokensReturned).containsExactly(venice, 8)
    }

    @Test
    fun cathedralCannotBeReusedAgainstTheSameOpponentDuringTheTurn() {
        val context =
            context(
                board = BoardState().placeMarker(id("florence"), genoa).board,
                advances = mapOf(venice to setOf(AdvanceId.CATHEDRAL)),
            )
        val plan = attack(context, destination = "florence")

        assertThat(CompetitionRules.cathedralUsers(context, plan, mapOf(venice to setOf(genoa))))
            .isEmpty()
    }

    private fun attack(context: BoardRulesContext, destination: String): PlacementPlan.Competition {
        val target = id(destination)
        val existing =
            (context.board.pieces[target] as? ProvincePieces.Tokens)?.holdings?.get(venice)?.total
                ?: 0
        val added =
            CompetitionRules.requirement(context, venice, target).requirement.tokens - existing
        return BoardPlacementRules.evaluate(
            context,
            venice,
            target,
            added,
            ExpansionReach.routesTo(context, venice, target).first(),
            poolTokens = added,
        ) as PlacementPlan.Competition
    }

    private fun context(
        board: BoardState = BoardState(),
        advances: Map<PlayerId, Set<AdvanceId>> = emptyMap(),
        military: List<MilitaryPlay> = emptyList(),
        chaos: Set<PlayerId> = emptySet(),
    ): BoardRulesContext =
        BoardRulesContext(
            configuration = CatalogConfiguration(3),
            board = board,
            players =
                listOf(venice to "venice", genoa to "genoa", barcelona to "barcelona").associate {
                    (player, capital) ->
                    player to
                        BoardPlayerProfile(
                            player,
                            id(capital),
                            advances[player].orEmpty(),
                            chaos = player in chaos,
                        )
                },
            militaryPlays = military,
        )

    private fun tokens(
        province: String,
        vararg holdings: Pair<PlayerId, TokenHolding>,
    ): BoardState =
        BoardState(
            persistentMapOf(
                id(province) to ProvincePieces.Tokens(holdings.toMap().toPersistentMap())
            )
        )

    private fun id(value: String): ProvinceId = ProvinceId(value)
}
