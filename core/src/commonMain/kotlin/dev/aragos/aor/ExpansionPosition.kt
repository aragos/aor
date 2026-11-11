package dev.aragos.aor

/**
 * Public board/resource inputs for one actor's remaining Expansion, without hands, deck or phase
 * progression. [poolTokens] is the actor's uncommitted pool; [stockTokens] includes every player so
 * defeated and converted pieces return to their actual owners. Marker inventories derive from
 * [context]'s board. [orderBox] is the actor's printed competition box, not a zero-based position.
 * [cathedralUsedAgainst] records declaring owner to opponents already affected this turn.
 *
 * The caller supplies an actual public snapshot. Predictions remain these typed inputs rather than
 * synthetic game states. Resolved victories stop before optional Wind/Watermill continuations;
 * marker bonus bookkeeping and automatic phase progression remain with the game driver.
 */
data class ExpansionPosition(
    val context: BoardRulesContext,
    val actor: PlayerId,
    val poolTokens: Int,
    val stockTokens: Map<PlayerId, Int>,
    val orderBox: Int,
    val turnOrder: List<PlayerId>,
    val cathedralUsedAgainst: Map<PlayerId, Set<PlayerId>> = emptyMap(),
) {
    init {
        require(actor in context.players) { "The expansion actor must be a board player." }
        require(poolTokens in 0..36) { "An expansion pool contains zero through 36 tokens." }
        require(
            stockTokens.keys == context.players.keys && stockTokens.values.all { it in 0..36 }
        ) {
            "Public expansion stock requires zero through 36 tokens for every board player."
        }
        require(orderBox in 1..6) {
            "An expansion actor requires a printed order box from one to six."
        }
        require(turnOrder.toSet() == context.players.keys && turnOrder.distinct() == turnOrder) {
            "Public expansion turn order must contain every board player exactly once."
        }
        require(
            cathedralUsedAgainst.all { (owner, opponents) ->
                owner in context.players &&
                    owner !in opponents &&
                    opponents.all { it in context.players }
            }
        ) {
            "Cathedral usage must name distinct registered owners and opponents."
        }
    }

    /**
     * Freshly evaluates reach, strength, marker supply, pool and shipping against this position.
     */
    fun evaluate(destination: ProvinceId, addedTokens: Int): PlacementPlan =
        BoardPlacementRules.evaluate(context, actor, destination, addedTokens, poolTokens)

    /** Reserves new tokens and shipping once, before any Cathedral response or competition dice. */
    fun reserve(destination: ProvinceId, addedTokens: Int): ExpansionTransition {
        val plan = evaluate(destination, addedTokens)
        val reserved =
            copy(
                context = BoardPlacementRules.recordShipping(context, plan),
                poolTokens = poolTokens - plan.addedTokens,
            )
        return when (plan) {
            is PlacementPlan.Peaceful -> {
                val change = BoardPlacementRules.applyPeaceful(context, plan)
                ExpansionTransition.Resolved(
                    position = reserved.apply(change),
                    change = change,
                    competition = null,
                    boundary = ExpansionBoundary.BOARD_RESOLVED,
                )
            }
            is PlacementPlan.Competition -> {
                val eligible =
                    CompetitionRules.cathedralUsers(reserved.context, plan, cathedralUsedAgainst)
                ExpansionTransition.PendingCompetition(
                    position = reserved,
                    plan = plan,
                    cathedralChoices =
                        (listOf(actor) + turnOrder.filter { it != actor }).filter(
                            eligible::contains
                        ),
                )
            }
        }
    }

    /** New tokens were already reserved from the pool; returned tokens go only to stock. */
    internal fun apply(change: BoardChange): ExpansionPosition =
        copy(
            context = context.copy(board = change.board),
            stockTokens =
                stockTokens.mapValues { (player, stock) ->
                    stock + (change.tokensReturned[player] ?: 0)
                },
        )
}

/** Board resolution does not simulate the surrounding activity or declare a live action legal. */
enum class ExpansionBoundary {
    BOARD_RESOLVED,
    /** The driver must check victory interruptions before treating the position as action-ready. */
    VICTORY_CONTINUATIONS_UNRESOLVED,
}

/** Explicit reservation, optional Cathedral responses, and supplied dice at the board boundary. */
sealed interface ExpansionTransition {
    val position: ExpansionPosition

    @ConsistentCopyVisibility
    data class Resolved
    internal constructor(
        override val position: ExpansionPosition,
        val change: BoardChange,
        val competition: CompetitionResolution?,
        val boundary: ExpansionBoundary,
    ) : ExpansionTransition

    @ConsistentCopyVisibility
    data class PendingCompetition
    internal constructor(
        override val position: ExpansionPosition,
        val plan: PlacementPlan.Competition,
        /** Actual response order; empty means all responses are settled and dice may resolve. */
        val cathedralChoices: List<PlayerId>,
    ) : ExpansionTransition {
        fun declineCathedral(player: PlayerId): PendingCompetition {
            checkRule(cathedralChoices.firstOrNull() == player, ViolationCode.WRONG_PLAYER)
            return copy(cathedralChoices = cathedralChoices.drop(1))
        }

        /**
         * A declaration resolves through the shared rule, including all affected opponent pairs.
         */
        fun useCathedral(player: PlayerId): Resolved {
            checkRule(cathedralChoices.firstOrNull() == player, ViolationCode.WRONG_PLAYER)
            return complete(
                CompetitionRules.resolveCathedral(
                    position.context,
                    plan,
                    player,
                    position.cathedralUsedAgainst,
                )
            )
        }

        /** Only supplied dice are consumed; unanswered Cathedral responses cannot be skipped. */
        fun resolve(roll: CompetitionRoll): Resolved {
            checkRule(cathedralChoices.isEmpty(), ViolationCode.INVALID_OUTCOME)
            return complete(
                CompetitionRules.resolve(position.context, plan, roll, position.orderBox)
            )
        }

        private fun complete(resolution: CompetitionResolution): Resolved {
            val applied = position.apply(resolution.change)
            val owner = resolution.cathedralUser
            val completed =
                if (owner == null) applied
                else {
                    applied.copy(
                        cathedralUsedAgainst =
                            applied.cathedralUsedAgainst +
                                (owner to
                                    (applied.cathedralUsedAgainst[owner].orEmpty() +
                                        resolution.cathedralOpponents))
                    )
                }
            return Resolved(
                position = completed,
                change = resolution.change,
                competition = resolution,
                boundary =
                    if (resolution.attackerWon) ExpansionBoundary.VICTORY_CONTINUATIONS_UNRESOLVED
                    else ExpansionBoundary.BOARD_RESOLVED,
            )
        }
    }
}
