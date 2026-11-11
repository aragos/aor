package dev.aragos.aor

/** Bounded attack enumeration, with the size of the complete legal frontier retained explicitly. */
data class ExpansionAttackFrontier(
    val totalLegalOptions: Int,
    val options: List<PlacementPlan.Competition>,
)

/** Prospective actor-relative search roles; this diagnostic assigns no utility or policy choice. */
enum class ExpansionResponseRole {
    ACTOR_MAX,
    DEFENDER_MIN,
    CHANCE,
}

/** A declaration, or one distinct dice result after the listed players have declined Cathedral. */
data class ExpansionResponseBranch(
    val declined: List<PlayerId>,
    val declaredBy: PlayerId?,
    /** Null for a declaration; otherwise one supplied representative of [diceOutcomes] triples. */
    val representativeRoll: CompetitionRoll?,
    /** Exact count among 216 equally likely triples, conditional on every Cathedral decline. */
    val diceOutcomes: Int,
    val result: ExpansionTransition.Resolved,
)

/** Declarations remain decisions; dice counts are never probabilities of an opponent declining. */
data class ExpansionResponseFrontier(
    val decisionRole: ExpansionResponseRole,
    val responseOrder: List<PlayerId>,
    val branches: List<ExpansionResponseBranch>,
)

/**
 * Factual diagnostic frontiers over public positions. This is neither an economic evaluator nor a
 * playing policy. Sorting cheap commitments before province identities only makes truncation
 * deterministic. Every option is reevaluated by the shared placement rules in its own position.
 */
object ExpansionTacticalFrontier {
    /**
     * Optionally restrict to full markets defended by [defender]. [retain] keeps one legal target
     * in a capped fixture even when it falls outside the first [limit] options; it cannot authorize
     * an otherwise illegal attack. Satellites remain available when [fullMarketsOnly] is false.
     */
    fun attacks(
        position: ExpansionPosition,
        limit: Int = 16,
        defender: PlayerId? = null,
        fullMarketsOnly: Boolean = false,
        retain: ProvinceId? = null,
    ): ExpansionAttackFrontier {
        require(limit in 1..16) { "A diagnostic attack frontier permits one through 16 options." }
        val legal =
            position.context.provinceIds
                .filter { !fullMarketsOnly || !position.context.province(it).isSatellite }
                .mapNotNull { destination ->
                    try {
                        val required =
                            BoardGame.attackTokens(position.context, position.actor, destination)
                        position.evaluate(destination, required) as? PlacementPlan.Competition
                    } catch (_: RuleViolation) {
                        null
                    }
                }
                .filter { defender == null || defender in it.defenders }
                .sortedWith(compareBy({ it.addedTokens }, { it.destination.value }))
        val retained = legal.firstOrNull { it.destination == retain }
        val options =
            if (retained == null || retained in legal.take(limit)) legal.take(limit)
            else listOf(retained) + legal.filterNot { it == retained }.take(limit - 1)
        return ExpansionAttackFrontier(legal.size, options)
    }

    /**
     * Flatten the ordered optional responses without combining attacker and defender powers. The
     * engine's Cathedral eligibility makes those roles mutually exclusive. All 216 supplied triples
     * are resolved by [CompetitionRules], then grouped by their exact public resolution. No random
     * source, authority state or assumed reply is consulted.
     */
    fun responses(pending: ExpansionTransition.PendingCompetition): ExpansionResponseFrontier {
        val players = pending.cathedralChoices
        val role =
            when {
                players.isEmpty() -> ExpansionResponseRole.CHANCE
                players.first() == pending.position.actor -> ExpansionResponseRole.ACTOR_MAX
                else -> ExpansionResponseRole.DEFENDER_MIN
            }
        var remaining = pending
        val declarations = players.mapIndexed { index, player ->
            val branch =
                ExpansionResponseBranch(
                    declined = players.take(index),
                    declaredBy = player,
                    representativeRoll = null,
                    diceOutcomes = 0,
                    result = remaining.useCathedral(player),
                )
            remaining = remaining.declineCathedral(player)
            branch
        }
        val dice =
            (1..6).flatMap { white ->
                (1..6).flatMap { black ->
                    (1..6).map { green ->
                        val roll = CompetitionRoll(DieRoll(white), DieRoll(black), DieRoll(green))
                        roll to
                            CompetitionRules.resolve(
                                remaining.position.context,
                                remaining.plan,
                                roll,
                                remaining.position.orderBox,
                            )
                    }
                }
            }
        val outcomes =
            dice
                .groupBy { it.second }
                .values
                .map { group ->
                    val roll = group.first().first
                    ExpansionResponseBranch(
                        declined = players,
                        declaredBy = null,
                        representativeRoll = roll,
                        diceOutcomes = group.size,
                        result = remaining.resolve(roll),
                    )
                }
        return ExpansionResponseFrontier(role, players, declarations + outcomes)
    }
}
