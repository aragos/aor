package dev.aragos.aor

/**
 * A peaceful market reachable with current public capabilities before the forthcoming Expansion.
 * [requiredTokens] is the additional commitment needed to establish its marker, independent of the
 * current pool. This is a planning forecast, not a legal action offer: other players may change the
 * board, purchases may change capabilities, and future phases must authorize actual commands.
 */
data class PeacefulExpansionProspectView(
    val destination: ProvinceId,
    val requiredTokens: Int,
    val shipping: Boolean,
    val usesOverseasSlot: Boolean,
) {
    init {
        require(requiredTokens > 0) {
            "A peaceful expansion prospect requires a positive commitment."
        }
    }
}

/**
 * An uncertain market competition currently reachable at [requiredTokens] additional tokens. The
 * authoritative commitment includes existing own holdings and current public strength modifiers; it
 * does not depend on the future turn order. No success probability or outcome is predicted.
 * [shipping] and [usesOverseasSlot] describe the first feasible current route, not a future offer.
 */
data class ContestedExpansionProspectView(
    val destination: ProvinceId,
    val requiredTokens: Int,
    val shipping: Boolean,
    val usesOverseasSlot: Boolean,
) {
    init {
        require(requiredTokens > 0) {
            "A contested expansion prospect requires a positive commitment."
        }
    }
}

/**
 * Current-capability planning facts, independent of the expansion pool and sealed token purchases.
 * Shared [overseasSlots] and the owner's marker stock constrain combinations of individually
 * feasible markets. Token purchasing includes contested prospects; card-play retention keeps its
 * peaceful-only forecast. Future purchases, card effects and expansion chains are not projected.
 */
data class ExpansionForecastView(
    val peacefulMarkets: List<PeacefulExpansionProspectView>,
    val overseasSlots: Int,
    val contestedMarkets: List<ContestedExpansionProspectView> = emptyList(),
) {
    init {
        require(overseasSlots >= 0) {
            "An expansion forecast cannot have negative capacity."
        }
    }

    companion object {
        internal fun from(state: GameState, viewer: PlayerState): ExpansionForecastView? {
            if (
                state.stage != Stage.TOKEN_PURCHASE && state.stage != Stage.PLAY_CARDS ||
                    viewer.misery == MiseryStep.CHAOS
            ) {
                return null
            }
            val player = viewer.player.id
            // The forthcoming Expansion phase starts without shipments from an earlier turn.
            val context = state.boardContext.copy(shipped = emptyMap())
            val markets =
                context.configuration.provinces.mapNotNull { province ->
                    if (province.isSatellite) return@mapNotNull null
                    val occupants = context.board.pieces[province.id]
                    if (occupants is ProvincePieces.Marker) return@mapNotNull null
                    val holdings = (occupants as? ProvincePieces.Tokens)?.holdings.orEmpty()
                    if (holdings.keys.any { it != player }) return@mapNotNull null
                    val required = province.marketNumber - (holdings[player]?.total ?: 0)
                    if (required <= 0) return@mapNotNull null
                    val plan =
                        plan(context, player, province.id, required) as? PlacementPlan.Peaceful
                            ?: return@mapNotNull null
                    PeacefulExpansionProspectView(
                        destination = province.id,
                        requiredTokens = required,
                        shipping = plan.route is ExpansionRoute.Shipping,
                        usesOverseasSlot =
                            province.overseas != null &&
                                !context.board.hasPresence(player, province.id),
                    )
                }
            return ExpansionForecastView(
                peacefulMarkets = markets,
                overseasSlots = ExpansionReach.remainingOverseasSlots(context, player),
                contestedMarkets =
                    if (state.stage == Stage.TOKEN_PURCHASE) {
                        contestedMarkets(context, player)
                    } else emptyList(),
            )
        }

        private fun contestedMarkets(
            context: BoardRulesContext,
            player: PlayerId,
        ): List<ContestedExpansionProspectView> =
            context.configuration.provinces.mapNotNull { province ->
                if (province.isSatellite) return@mapNotNull null
                val contested =
                    when (val occupants = context.board.pieces[province.id]) {
                        is ProvincePieces.Marker -> occupants.owner != player
                        is ProvincePieces.Tokens -> occupants.holdings.keys.any { it != player }
                        null -> false
                    }
                if (!contested) return@mapNotNull null
                val required = BoardGame.attackTokens(context, player, province.id)
                val plan =
                    plan(context, player, province.id, required) as? PlacementPlan.Competition
                        ?: return@mapNotNull null
                ContestedExpansionProspectView(
                    destination = province.id,
                    requiredTokens = required,
                    shipping = plan.route is ExpansionRoute.Shipping,
                    usesOverseasSlot =
                        province.overseas != null &&
                            !context.board.hasPresence(player, province.id),
                )
            }

        private fun plan(
            context: BoardRulesContext,
            player: PlayerId,
            destination: ProvinceId,
            required: Int,
        ): PlacementPlan? =
            ExpansionReach.routesTo(context, player, destination).firstNotNullOfOrNull { route ->
                try {
                    BoardPlacementRules.evaluate(
                        context = context,
                        player = player,
                        destination = destination,
                        addedTokens = required,
                        route = route,
                        poolTokens = required,
                    )
                } catch (_: RuleViolation) {
                    null
                }
            }
    }
}
