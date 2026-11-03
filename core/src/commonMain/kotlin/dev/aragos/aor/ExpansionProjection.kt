package dev.aragos.aor

enum class ExpansionAction {
    PLACE,
    ATTACK,
    INSPECT,
}

/** Per-destination shipping. Null capacity means unlimited ocean token capacity. */
data class ShippingUsageView(val used: Int, val capacity: Int?, val required: Int)

data class PartialPlacementView(val tokens: Int, val resultingPool: Int)

/**
 * The primary intention is a marker for markets, a token for satellites, or inspection for a
 * controlled province. A competition's incomeAfter is null because its outcome is uncertain. White
 * markers affect income and current commodity control immediately.
 */
data class ExpansionOfferView(
    val destination: ProvinceId,
    val primaryAction: ExpansionAction,
    val requiredTokens: Int,
    val availablePool: Int,
    val resultingPool: Int?,
    val establishesMarker: Boolean,
    val uncertain: Boolean,
    val blockers: List<ViolationCode>,
    val partialPlacements: List<PartialPlacementView>,
    val shipping: ShippingUsageView?,
    val incomeBefore: Int?,
    val incomeAfter: Int?,
    /** An accessible route exists, independently of pool, marker stock, and commitment size. */
    val reachable: Boolean,
    /** Printed access conditions the viewer has not satisfied; independent of route existence. */
    val missingAccess: List<AccessRequirement> = emptyList(),
    /** Null for peaceful placements; otherwise the faces that win on green alone, with advances. */
    val greenDieWins: List<Int>? = null,
    /** Exact dice-only attack probability; optional Cathedral declarations remain decisions. */
    val competitionWinProbability: Double? = null,
)

/** Consequences from the same mutation used by the voluntary marker-reduction command. */
data class MarkerReductionOfferView(
    val command: String,
    val province: ProvinceId,
    val poolBefore: Int,
    val poolAfter: Int,
    val stockBefore: Int,
    val stockAfter: Int,
    val incomeBefore: Int?,
    val incomeAfter: Int?,
)

internal object ExpansionProjection {
    fun reductions(state: GameState, viewer: PlayerState): List<MarkerReductionOfferView> =
        state
            .legalChoices(viewer.player.id)
            .commands
            .filter { command -> command.startsWith("reduce-marker ") }
            .map { command ->
                val province = ProvinceId(command.substringAfter("reduce-marker "))
                val after = BoardGame.reduceMarker(state, viewer.player.id, province)
                val reduced = after.playerState(viewer.player.id)
                MarkerReductionOfferView(
                    command,
                    province,
                    viewer.poolTokens,
                    reduced.poolTokens,
                    viewer.stockTokens,
                    reduced.stockTokens,
                    IncomeView.from(state, viewer)?.ordinary,
                    IncomeView.from(after, reduced)?.ordinary,
                )
            }

    /** All current destination evaluations, including failures excluded from candidate offers. */
    fun inspections(state: GameState, viewer: PlayerState): List<ExpansionOfferView> {
        if (
            state.stage != Stage.EXPANSION ||
                state.flow.actor != viewer.player.id ||
                state.flow.decisions.isNotEmpty() ||
                viewer.misery == MiseryStep.CHAOS
        ) {
            return emptyList()
        }
        val context = BoardGame.context(state)
        return context.configuration.provinces.map { province ->
            offer(state, viewer, context, province)
        }
    }

    /**
     * Keep impossible pool/shipping commitments inspectable without offering them as candidates.
     */
    fun offers(inspections: List<ExpansionOfferView>): List<ExpansionOfferView> =
        inspections.filter { offer ->
            offer.blockers.none { blocker ->
                blocker == ViolationCode.INSUFFICIENT_EXPANSION_TOKENS ||
                    blocker == ViolationCode.SHIPPING_CAPACITY_EXCEEDED
            } || offer.partialPlacements.isNotEmpty()
        }

    private fun offer(
        state: GameState,
        viewer: PlayerState,
        context: BoardRulesContext,
        province: ProvinceDefinition,
    ): ExpansionOfferView {
        val player = viewer.player.id
        val occupants = state.board.pieces[province.id]
        val holdings = (occupants as? ProvincePieces.Tokens)?.holdings.orEmpty()
        val existing = holdings[player]?.total ?: 0
        val controlled =
            (occupants is ProvincePieces.Marker && occupants.owner == player) ||
                (province.isSatellite && existing >= province.marketNumber)
        val contested =
            when (occupants) {
                is ProvincePieces.Marker -> occupants.owner != player
                else -> holdings.keys.any { owner -> owner != player }
            }
        val action =
            when {
                controlled -> ExpansionAction.INSPECT
                contested -> ExpansionAction.ATTACK
                else -> ExpansionAction.PLACE
            }
        val required =
            when {
                controlled -> 0
                contested -> BoardGame.attackTokens(context, player, province.id)
                else -> province.marketNumber - existing
            }
        val income = IncomeView.from(state, viewer)?.ordinary
        val routes =
            if (controlled) emptyList() else ExpansionReach.routesTo(context, player, province.id)
        val evaluated =
            if (controlled) null else evaluate(context, viewer, province.id, required, routes)
        val plan = evaluated?.plan
        val blocker = evaluated?.blocker
        val partial =
            if (controlled) {
                emptyList()
            } else {
                // A province has at most six spaces. Never enumerate pool-sized commitments.
                (1 until province.marketNumber).mapNotNull { tokens ->
                    val candidate =
                        evaluate(context, viewer, province.id, tokens, routes).plan
                            as? PlacementPlan.Peaceful
                    if (candidate == null || tokens == required) {
                        null
                    } else {
                        PartialPlacementView(tokens, viewer.poolTokens - tokens)
                    }
                }
            }
        val route = plan?.route ?: routes.firstOrNull()
        val shipping =
            if (route is ExpansionRoute.Shipping) {
                ShippingUsageView(
                    context.shipped[player].orEmpty()[province.id] ?: 0,
                    viewer.ships.level.takeUnless { viewer.ships.fleet == ShippingFleet.OCEAN },
                    required,
                )
            } else null
        val after =
            (plan as? PlacementPlan.Peaceful)?.let { peaceful ->
                BoardPlacementRules.applyPeaceful(context, peaceful).board
            }
        return ExpansionOfferView(
            province.id,
            action,
            required,
            viewer.poolTokens,
            if (plan == null) null else viewer.poolTokens - required,
            !province.isSatellite && !controlled,
            contested,
            listOfNotNull(blocker),
            partial,
            shipping,
            income,
            after?.let { board -> IncomeView.from(state, viewer, board)?.ordinary },
            routes.isNotEmpty(),
            province.access.filterNot { requirement ->
                requirement.isSatisfiedBy(viewer.advances.owned)
            },
            if (contested) {
                val defenders =
                    when (occupants) {
                        is ProvincePieces.Marker -> setOf(occupants.owner)
                        else -> holdings.keys - player
                    }
                CompetitionRules.greenWinningRolls(
                    orderBox =
                        requireNotNull(state.orderBoxes[player]) {
                            "An expansion attacker requires a resolved printed order box."
                        },
                    proselytism = CompetitionRules.proselytismApplies(context, player, defenders),
                )
            } else null,
            (plan as? PlacementPlan.Competition)?.let { competition ->
                val greenWins =
                    CompetitionRules.greenWinningRolls(
                            requireNotNull(state.orderBoxes[player]) {
                                "A competition requires the attacker's printed order box."
                            },
                            CompetitionRules.proselytismApplies(
                                context,
                                player,
                                competition.defenders,
                            ),
                        )
                        .size
                val blackWins =
                    15 + if (competition.breakdown.offensiveMilitary.isNotEmpty()) 6 else 0
                (blackWins * 6 + (36 - blackWins) * greenWins) / 216.0
            },
        )
    }

    private data class Evaluation(
        val plan: PlacementPlan? = null,
        val blocker: ViolationCode? = null,
    )

    private fun evaluate(
        context: BoardRulesContext,
        viewer: PlayerState,
        province: ProvinceId,
        tokens: Int,
        routes: List<ExpansionRoute>,
    ): Evaluation {
        if (
            !ExpansionReach.canEnter(context.player(viewer.player.id), context.province(province))
        ) {
            return Evaluation(blocker = ViolationCode.PROVINCE_ACCESS_DENIED)
        }
        var firstFailure: ViolationCode? = null
        for (route in routes) {
            try {
                return Evaluation(
                    plan =
                        BoardPlacementRules.evaluate(
                            context,
                            viewer.player.id,
                            province,
                            tokens,
                            route,
                            viewer.poolTokens,
                        )
                )
            } catch (failure: RuleViolation) {
                if (firstFailure == null) firstFailure = failure.code
            }
        }
        return Evaluation(blocker = firstFailure ?: ViolationCode.UNREACHABLE_PROVINCE)
    }
}
