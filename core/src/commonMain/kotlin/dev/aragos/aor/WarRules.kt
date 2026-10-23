package dev.aragos.aor

/**
 * An unresolved War persists across ordinary turn boundaries. Roll again at the start of the next
 * player's Card Play round; a participating round owner may first play military cards. The driver
 * clears a remaining War only after the last Final Card Play round, or if a participant leaves
 * play.
 */
data class WarState(
    val initiator: PlayerId,
    val opponent: PlayerId,
    val startedTurn: Int,
    val ties: Int = 0,
    val lastRound: WarRound? = null,
) {
    init {
        require(initiator != opponent) { "A War requires two different players." }
        require(startedTurn > 0) { "A War must start in a positive turn." }
        require(ties >= 0) { "The number of tied War rolls cannot be negative." }
    }
}

/** Distinguishes ordinary and final card rounds in the same turn for reroll scheduling. */
data class WarRound(val turn: Int, val finalCardPlay: Boolean, val actor: PlayerId) {
    init {
        require(turn > 0) { "A War round requires a positive turn." }
    }
}

/** Recorded, unmodified dice: current advances and military effects supply modifiers on replay. */
data class WarDice(val initiator: DieRoll, val opponent: DieRoll)

sealed interface WarResult {
    /** Both sides gain one misery space; [war] contains the updated tie count. */
    data class Tie(val war: WarState, val totals: Map<PlayerId, Int>) : WarResult

    /** Winner gains one misery, loser two, before any additional unceded-province penalty. */
    data class Victory(
        val winner: PlayerId,
        val loser: PlayerId,
        val requiredTransfers: Int,
        val totals: Map<PlayerId, Int>,
    ) : WarResult
}

/** Selected coloured markers become the winner's coloured markers; unceded amounts add misery. */
data class WarSettlement(
    val winner: PlayerId,
    val loser: PlayerId,
    val provinces: Set<ProvinceId>,
    val additionalLoserMisery: Int,
)

object WarRules {
    fun canContinue(context: CardContext, war: WarState): Boolean =
        context.player(war.initiator).isActive && context.player(war.opponent).isActive

    /** Military modifiers are recalculated for each roll, including rolls in subsequent turns. */
    fun roll(context: CardContext, war: WarState, dice: WarDice): WarResult {
        cardRule(
            canContinue(context, war),
            CardRuleCode.INVALID_WAR,
            "A War cannot continue after either participant leaves play.",
        )
        val firstMilitary = context.effects.militaryBonus(war.initiator, war.opponent)
        val secondMilitary = context.effects.militaryBonus(war.opponent, war.initiator)
        fun nationalism(player: PlayerId): Int =
            if (AdvanceId.NATIONALISM in context.player(player).advances) 1 else 0
        val first = dice.initiator.value + firstMilitary + nationalism(war.initiator)
        val second = dice.opponent.value + secondMilitary + nationalism(war.opponent)
        val totals = mapOf(war.initiator to first, war.opponent to second)
        // Printed military cards win War ties, in addition to their die modifier. A zero modified
        // difference transfers zero markers, while still applying winner/loser misery normally.
        val winner =
            when {
                first > second -> war.initiator
                second > first -> war.opponent
                firstMilitary > 0 && secondMilitary == 0 -> war.initiator
                secondMilitary > 0 && firstMilitary == 0 -> war.opponent
                else -> null
            }
        return if (winner == null) WarResult.Tie(war.copy(ties = war.ties + 1), totals)
        else {
            WarResult.Victory(
                winner,
                if (winner == war.initiator) war.opponent else war.initiator,
                requiredTransfers = kotlin.math.abs(first - second),
                totals,
            )
        }
    }

    /**
     * Shipping range is irrelevant; access advances still restrict supportable War acquisitions.
     */
    fun eligibleProvinces(
        context: CardContext,
        winner: PlayerId,
        loser: PlayerId,
    ): Set<ProvinceId> {
        cardRule(
            winner != loser,
            CardRuleCode.INVALID_WAR,
            "War participants must be different players.",
        )
        val advances = context.player(winner).advances
        context.player(loser)
        val overseasCapacity = overseasCapacity(context, winner)
        return context.markers
            .filter { (province, marker) ->
                val definition = context.province(province)
                marker.coloured &&
                    marker.owner == loser &&
                    (definition.area != GeographicalArea.V ||
                        AdvanceId.OVERLAND_EAST in advances) &&
                    (definition.overseas == null || AdvanceId.OCEAN_NAVIGATION in advances) &&
                    (definition.overseas == null || overseasCapacity > 0) &&
                    (definition.overseas != OverseasGroup.NEW_WORLD ||
                        AdvanceId.NEW_WORLD in advances)
            }
            .keys
    }

    /**
     * The loser chooses all required supportable markers, or all available ones if short. AH1996
     * section 11.5 adds one misery space for every marker that cannot be ceded. Physical counter
     * exhaustion is validated separately once the game's counter-capacity policy is established.
     */
    fun surrender(
        context: CardContext,
        result: WarResult.Victory,
        provinces: Set<ProvinceId>,
    ): WarSettlement {
        cardRule(
            result.requiredTransfers >= 0,
            CardRuleCode.INVALID_SURRENDER,
            "A War cannot require a negative number of transfers.",
        )
        val eligible = eligibleProvinces(context, result.winner, result.loser)
        val overseas = eligible.count { province -> context.province(province).overseas != null }
        val supportable =
            eligible.size - overseas + minOf(overseas, overseasCapacity(context, result.winner))
        val required = minOf(result.requiredTransfers, supportable)
        cardRule(
            provinces.size == required &&
                eligible.containsAll(provinces) &&
                provinces.count { province -> context.province(province).overseas != null } <=
                    overseasCapacity(context, result.winner),
            CardRuleCode.INVALID_SURRENDER,
            "The loser must cede exactly the required available supportable provinces.",
        )
        val availableMarkers =
            25 - context.markers.values.count { marker -> marker.owner == result.winner }
        cardRule(
            required <= availableMarkers,
            CardRuleCode.MARKER_RULING_REQUIRED,
            "War gains exceeding available domination markers require the counter-capacity ruling.",
        )
        return WarSettlement(
            result.winner,
            result.loser,
            provinces,
            additionalLoserMisery = result.requiredTransfers - required,
        )
    }

    /** Overseas maintenance limits survive the explicit exemption from shipping range. */
    fun overseasCapacity(context: CardContext, winner: PlayerId): Int {
        val player = context.player(winner)
        if (player.ships.fleet != ShippingFleet.OCEAN) return 0
        val occupied =
            player.overseasPresence +
                context.markers
                    .filter { (province, marker) ->
                        marker.owner == winner && context.province(province).overseas != null
                    }
                    .keys
        return (player.ships.level - occupied.size).coerceAtLeast(0)
    }
}
