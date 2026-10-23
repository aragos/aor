package dev.aragos.aor

import kotlinx.collections.immutable.toPersistentMap

/**
 * A validated action. Its board snapshot prevents resolving it after an unrelated board mutation.
 */
sealed interface PlacementPlan {
    val player: PlayerId
    val destination: ProvinceId
    val addedTokens: Int
    val route: ExpansionRoute
    val boardBefore: BoardState

    @ConsistentCopyVisibility
    data class Peaceful
    internal constructor(
        override val player: PlayerId,
        override val destination: ProvinceId,
        override val addedTokens: Int,
        override val route: ExpansionRoute,
        override val boardBefore: BoardState,
    ) : PlacementPlan

    /**
     * The driver reserves [addedTokens] and shipping usage before requesting a recorded outcome.
     */
    @ConsistentCopyVisibility
    data class Competition
    internal constructor(
        override val player: PlayerId,
        override val destination: ProvinceId,
        override val addedTokens: Int,
        override val route: ExpansionRoute,
        override val boardBefore: BoardState,
        val existingTokens: Int,
        val defenders: Set<PlayerId>,
        val breakdown: AttackBreakdown,
    ) : PlacementPlan
}

object BoardPlacementRules {
    /** All rejection checks run before the driver spends tokens, shipping capacity or any dice. */
    fun evaluate(
        context: BoardRulesContext,
        player: PlayerId,
        destination: ProvinceId,
        addedTokens: Int,
        route: ExpansionRoute,
        poolTokens: Int,
    ): PlacementPlan {
        checkRule(addedTokens >= 0, ViolationCode.INVALID_PLACEMENT)
        checkRule(
            poolTokens >= 0 && addedTokens <= poolTokens,
            ViolationCode.INSUFFICIENT_EXPANSION_TOKENS,
        )
        ExpansionReach.validate(context, player, destination, route)
        if (route is ExpansionRoute.Shipping) {
            ExpansionReach.validateShippingCapacity(context, player, destination, addedTokens)
        }
        val definition = context.province(destination)
        val occupants = context.board.pieces[destination]
        val holdings = (occupants as? ProvincePieces.Tokens)?.holdings.orEmpty()
        val existing = holdings[player]?.total ?: 0
        val defenders =
            when (occupants) {
                is ProvincePieces.Marker -> setOf(occupants.owner) - player
                else -> holdings.keys - player
            }
        checkRule(
            occupants !is ProvincePieces.Marker || occupants.owner != player,
            ViolationCode.INVALID_PLACEMENT,
        )
        val competition =
            defenders.isNotEmpty() &&
                (occupants is ProvincePieces.Marker ||
                    holdings.values.sumOf(TokenHolding::total).toLong() + addedTokens >
                        definition.marketNumber)
        if (competition) {
            val breakdown = CompetitionRules.requirement(context, player, destination)
            breakdown.requirement.validateCommitment(existing, addedTokens)
            requireMarkerAvailable(context, player, definition)
            return PlacementPlan.Competition(
                player,
                destination,
                addedTokens,
                route,
                context.board,
                existing,
                defenders,
                breakdown,
            )
        }
        checkRule(
            addedTokens > 0 &&
                holdings.values.sumOf(TokenHolding::total).toLong() + addedTokens <=
                    definition.marketNumber,
            ViolationCode.INVALID_PLACEMENT,
        )
        if (defenders.isEmpty() && existing + addedTokens == definition.marketNumber) {
            requireMarkerAvailable(context, player, definition)
        }
        return PlacementPlan.Peaceful(player, destination, addedTokens, route, context.board)
    }

    /** Peaceful placement spends [BoardChange.tokensRequired] from the expansion pool. */
    fun applyPeaceful(context: BoardRulesContext, plan: PlacementPlan.Peaceful): BoardChange {
        checkRule(context.board == plan.boardBefore, ViolationCode.INVALID_BOARD_MUTATION)
        val definition = context.province(plan.destination)
        val previous =
            (context.board.pieces[plan.destination] as? ProvincePieces.Tokens)?.holdings.orEmpty()
        val holding =
            previous[plan.player]?.add(plan.addedTokens, PieceSide.WHITE)
                ?: TokenHolding(white = plan.addedTokens)
        val holdings = (previous + (plan.player to holding)).toPersistentMap()
        if (
            holdings.size == 1 &&
                holding.total == definition.marketNumber &&
                !definition.isSatellite
        ) {
            val replacement =
                context.board.placeMarker(plan.destination, plan.player, PieceSide.WHITE)
            return replacement.copy(
                tokensReturned = mapOf(plan.player to holding.total),
                tokensRequired = mapOf(plan.player to plan.addedTokens),
            )
        }
        return BoardChange(
            board =
                context.board.copy(
                    pieces =
                        context.board.pieces.putting(
                            plan.destination,
                            ProvincePieces.Tokens(holdings),
                        )
                ),
            tokensRequired = mapOf(plan.player to plan.addedTokens),
        )
    }

    /** Derive usage from the accepted plan; competition attempts remain charged after failure. */
    fun recordShipping(context: BoardRulesContext, plan: PlacementPlan): BoardRulesContext =
        if (plan.route is ExpansionRoute.Shipping) {
            context.recordShipment(plan.player, plan.destination, plan.addedTokens)
        } else context

    private fun requireMarkerAvailable(
        context: BoardRulesContext,
        player: PlayerId,
        definition: ProvinceDefinition,
    ) {
        checkRule(
            definition.isSatellite || context.board.markerCount(player) < 25,
            ViolationCode.NO_DOMINATION_MARKERS,
        )
    }
}
