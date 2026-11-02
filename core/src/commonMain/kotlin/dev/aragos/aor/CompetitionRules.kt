package dev.aragos.aor

import kotlinx.collections.immutable.toPersistentMap

enum class MilitaryCard {
    ARMOR,
    STIRRUPS,
    LONG_BOW,
    GUNPOWDER,
}

/** Cards currently in effect; their phase timing and discard/voiding belong to the card driver. */
data class MilitaryPlay(val player: PlayerId, val card: MilitaryCard)

/** Nationalism entries are strength bonuses after the appropriate capital multiplier. */
data class AttackBreakdown(
    val marketNumber: Int,
    val defenderStrength: Int,
    val defenderCapitalFactors: Map<PlayerId, Int>,
    val defensiveSatellites: Int,
    val offensiveSatellites: Int,
    val defenderNationalism: Int,
    val attackerNationalism: Int,
    val defensiveMilitary: Set<MilitaryCard>,
    val offensiveMilitary: Set<MilitaryCard>,
    val attackerCapitalFactor: Int,
) {
    init {
        require(marketNumber in 1..6 && defenderStrength > 0) {
            "A competition requires a printed market number and positive defending strength."
        }
        require(
            listOf(
                    defensiveSatellites,
                    offensiveSatellites,
                    defenderNationalism,
                    attackerNationalism,
                )
                .all { value -> value >= 0 }
        ) {
            "Competition bonuses cannot be negative."
        }
        require(
            attackerCapitalFactor in 1..2 &&
                defenderCapitalFactors.values.all { factor -> factor in 1..2 }
        ) {
            "Capital strength multipliers must be one or two."
        }
    }

    val requiredStrength: Int
        get() =
            marketNumber +
                defenderStrength +
                defensiveSatellites +
                defenderNationalism +
                defensiveMilitary.size -
                offensiveSatellites -
                attackerNationalism -
                offensiveMilitary.size

    /** The physical token minimum remains the printed market number, even in one's own capital. */
    val requirement: AttackRequirement
        get() =
            AttackRequirement(
                maxOf(
                    marketNumber,
                    (requiredStrength + attackerCapitalFactor - 1) / attackerCapitalFactor,
                )
            )
}

data class CompetitionRoll(val white: DieRoll, val black: DieRoll, val green: DieRoll)

/**
 * The attack's new tokens have already left the pool; resolution never requires them a second time.
 */
data class CompetitionResolution(
    val change: BoardChange,
    val attackerWon: Boolean,
    val defeatedPlayers: Set<PlayerId>,
    val previousController: PlayerId?,
    val cathedralUser: PlayerId? = null,
    val cathedralOpponents: Set<PlayerId> = emptySet(),
)

object CompetitionRules {
    /** Green die faces that independently win a competition from this printed box (1 through 6). */
    fun greenWinningRolls(orderBox: Int, proselytism: Boolean = false): List<Int> =
        (1..6).filter { roll -> roll > orderBox || (proselytism && roll == orderBox) }

    /** Chaos defenders disable the attacker's advance benefit, including green-die ties. */
    fun proselytismApplies(
        context: BoardRulesContext,
        attacker: PlayerId,
        defenders: Set<PlayerId>,
    ): Boolean =
        AdvanceId.PROSELYTISM in context.player(attacker).advances &&
            defenders.none { defender -> context.player(defender).chaos }

    fun requirement(
        context: BoardRulesContext,
        attacker: PlayerId,
        province: ProvinceId,
    ): AttackBreakdown {
        val profile = context.player(attacker)
        val definition = context.province(province)
        val occupants = context.board.pieces[province]
        val strengthByDefender =
            when (occupants) {
                is ProvincePieces.Marker ->
                    if (occupants.owner != attacker) {
                        mapOf(occupants.owner to definition.marketNumber)
                    } else {
                        emptyMap()
                    }
                is ProvincePieces.Tokens ->
                    occupants.holdings
                        .filterKeys { owner -> owner != attacker }
                        .mapValues { holding -> holding.value.total }
                null -> emptyMap()
            }
        checkRule(strengthByDefender.isNotEmpty(), ViolationCode.INVALID_COMPETITION)
        val defenders = strengthByDefender.keys
        val factors = defenders.associateWith { defender ->
            if (context.player(defender).capital == province) 2 else 1
        }
        val attackerFactor = if (profile.capital == province) 2 else 1
        val advancesUsable = defenders.none { defender -> context.player(defender).chaos }
        val attackingNationalism = advancesUsable && hasNationalism(context, profile, definition)
        val defendingNationalism = defenders.filter { defender ->
            hasNationalism(context, context.player(defender), definition)
        }
        val nationalistDefence =
            if (!attackingNationalism && defendingNationalism.isNotEmpty()) {
                defendingNationalism.maxOf { defender ->
                    requireNotNull(factors[defender]) {
                        "Every defending player requires a strength factor."
                    }
                }
            } else {
                0
            }
        val nationalistAttack =
            if (attackingNationalism && defendingNationalism.isEmpty()) {
                attackerFactor
            } else {
                0
            }
        val offensiveMilitary =
            MilitaryCard.entries
                .filter { card ->
                    defenders.all { defender ->
                        card in effectiveMilitary(context, attacker, defender)
                    }
                }
                .toSet()
        val defensiveMilitary =
            defenders
                .flatMap { defender ->
                    effectiveMilitary(context, defender, attacker)
                }
                .toSet()
        return AttackBreakdown(
            marketNumber = definition.marketNumber,
            defenderStrength =
                strengthByDefender.entries.sumOf { defender ->
                    defender.value *
                        requireNotNull(factors[defender.key]) {
                            "Every defender must have a strength factor: ${defender.key}."
                        }
                },
            defenderCapitalFactors = factors,
            defensiveSatellites =
                if (occupants is ProvincePieces.Marker) {
                    satelliteSupport(context, occupants.owner, province)
                } else {
                    0
                },
            offensiveSatellites =
                if (advancesUsable && AdvanceId.COSMOPOLITAN in profile.advances) {
                    satelliteSupport(context, attacker, province)
                } else {
                    0
                },
            defenderNationalism = nationalistDefence,
            attackerNationalism = nationalistAttack,
            defensiveMilitary = defensiveMilitary,
            offensiveMilitary = offensiveMilitary,
            attackerCapitalFactor = attackerFactor,
        )
    }

    fun resolve(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
        roll: CompetitionRoll,
        orderBox: Int,
    ): CompetitionResolution {
        validatePending(context, plan)
        checkRule(orderBox in 1..6, ViolationCode.INVALID_OUTCOME)
        val proselytism = proselytismApplies(context, plan.player, plan.defenders)
        val attackerWon =
            roll.black.value > roll.white.value ||
                (roll.black.value == roll.white.value &&
                    plan.breakdown.offensiveMilitary.isNotEmpty()) ||
                roll.green.value in greenWinningRolls(orderBox, proselytism)
        return outcome(context, plan, attackerWon)
    }

    /**
     * Eligible callers are returned for the driver's optional attack/defence interruption windows.
     */
    fun cathedralUsers(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
        usedAgainst: Map<PlayerId, Set<PlayerId>>,
    ): Set<PlayerId> {
        validatePending(context, plan)
        val attacker = context.player(plan.player)
        val attackerOwns = AdvanceId.CATHEDRAL in attacker.advances
        if (!attackerOwns) {
            return plan.defenders.filterTo(mutableSetOf()) { defender ->
                val profile = context.player(defender)
                !profile.chaos &&
                    AdvanceId.CATHEDRAL in profile.advances &&
                    plan.player !in usedAgainst[defender].orEmpty()
            }
        }
        val canDeclare =
            plan.defenders.all { defender ->
                val profile = context.player(defender)
                !profile.chaos &&
                    AdvanceId.CATHEDRAL !in profile.advances &&
                    defender !in usedAgainst[plan.player].orEmpty()
            }
        return if (canDeclare) setOf(plan.player) else emptySet()
    }

    fun resolveCathedral(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
        declaringPlayer: PlayerId,
        usedAgainst: Map<PlayerId, Set<PlayerId>>,
    ): CompetitionResolution {
        checkRule(
            declaringPlayer in cathedralUsers(context, plan, usedAgainst),
            ViolationCode.INVALID_CATHEDRAL_USE,
        )
        return outcome(context, plan, attackerWon = declaringPlayer == plan.player)
            .copy(
                cathedralUser = declaringPlayer,
                cathedralOpponents =
                    if (declaringPlayer == plan.player) {
                        plan.defenders
                    } else {
                        setOf(plan.player)
                    },
            )
    }

    private fun validatePending(context: BoardRulesContext, plan: PlacementPlan.Competition) {
        checkRule(
            context.board == plan.boardBefore &&
                requirement(context, plan.player, plan.destination) == plan.breakdown,
            ViolationCode.INVALID_COMPETITION,
        )
        plan.breakdown.requirement.validateCommitment(plan.existingTokens, plan.addedTokens)
    }

    private fun outcome(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
        attackerWon: Boolean,
    ): CompetitionResolution {
        return CompetitionResolution(
            change =
                if (attackerWon) {
                    attackerVictory(context, plan)
                } else {
                    attackerDefeat(context, plan)
                },
            attackerWon = attackerWon,
            defeatedPlayers = if (attackerWon) plan.defenders else setOf(plan.player),
            previousController = context.board.controller(plan.destination),
        )
    }

    private fun attackerVictory(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
    ): BoardChange {
        val isSatellite = context.province(plan.destination).isSatellite
        val change =
            if (isSatellite) {
                context.board.replaceWithToken(plan.destination, plan.player, PieceSide.WHITE)
            } else {
                context.board.placeMarker(plan.destination, plan.player, PieceSide.WHITE)
            }
        val defendingTokens =
            (context.board.pieces[plan.destination] as? ProvincePieces.Tokens)
                ?.holdings
                .orEmpty()
                .filterKeys { owner -> owner != plan.player }
                .mapValues { holding -> holding.value.total }
        val returnedAttackers = plan.existingTokens + plan.addedTokens - if (isSatellite) 1 else 0
        return change.copy(
            tokensReturned =
                defendingTokens +
                    mapOf(plan.player to returnedAttackers).filterValues { count -> count > 0 },
            tokensRequired = emptyMap(),
        )
    }

    private fun attackerDefeat(
        context: BoardRulesContext,
        plan: PlacementPlan.Competition,
    ): BoardChange {
        val previous =
            requireNotNull(context.board.pieces[plan.destination]) {
                "A competition requires an occupied province: ${plan.destination}."
            }
        val replacement =
            when (previous) {
                is ProvincePieces.Marker -> previous
                is ProvincePieces.Tokens ->
                    ProvincePieces.Tokens(
                        previous.holdings
                            .filterKeys { owner -> owner != plan.player }
                            .toPersistentMap()
                    )
            }
        return BoardChange(
            board =
                context.board.copy(
                    pieces = context.board.pieces.putting(plan.destination, replacement)
                ),
            tokensReturned = mapOf(plan.player to plan.existingTokens + plan.addedTokens),
        )
    }

    private fun satelliteSupport(
        context: BoardRulesContext,
        player: PlayerId,
        province: ProvinceId,
    ): Int =
        context.configuration.satelliteSupports.count { support ->
            support.market == province &&
                ((context.board.pieces[support.satellite] as? ProvincePieces.Tokens)
                    ?.holdings
                    .orEmpty()[player]
                    ?.coloured ?: 0) > 0
        }

    private fun hasNationalism(
        context: BoardRulesContext,
        profile: BoardPlayerProfile,
        province: ProvinceDefinition,
    ): Boolean =
        AdvanceId.NATIONALISM in profile.advances &&
            province.area == context.province(profile.capital).area

    private fun effectiveMilitary(
        context: BoardRulesContext,
        owner: PlayerId,
        opponent: PlayerId,
    ): Set<MilitaryCard> {
        val owned =
            context.militaryPlays.filter { play -> play.player == owner }.map(MilitaryPlay::card)
        val opposing =
            context.militaryPlays.filter { play -> play.player == opponent }.map(MilitaryPlay::card)
        return owned
            .filter { card ->
                when (card) {
                    MilitaryCard.ARMOR ->
                        MilitaryCard.LONG_BOW !in opposing && MilitaryCard.GUNPOWDER !in opposing
                    MilitaryCard.STIRRUPS ->
                        MilitaryCard.ARMOR !in opposing &&
                            MilitaryCard.LONG_BOW !in opposing &&
                            MilitaryCard.GUNPOWDER !in opposing
                    MilitaryCard.LONG_BOW -> MilitaryCard.GUNPOWDER !in opposing
                    MilitaryCard.GUNPOWDER -> true
                }
            }
            .toSet()
    }
}
