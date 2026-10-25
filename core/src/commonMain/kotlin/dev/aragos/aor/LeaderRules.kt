package dev.aragos.aor

enum class CardLeaderRole {
    DISPLAY,
    EXPOSED,
}

/**
 * A played leader's explicit display role and protection escrow. Each claimant pays
 * [protectionCash] once to [owner], then may use every printed credit. The owner's escrow goes to
 * the bank after Purchase. Retain the turn's play record through Final Card Play; it is not an
 * active hand card.
 */
data class CardLeaderInPlay(
    val leader: LeaderId,
    val owner: PlayerId,
    val role: CardLeaderRole,
    val protectionCash: Int = 0,
    val patronageClaimants: Set<PlayerId> = emptySet(),
    /** Snapshot at play: Mongol Armies must precede Marco Polo to enhance either credit. */
    val mongolEnhanced: Boolean = false,
    /** Printed alternate display box, or null for unprotected leaders. */
    val displayBox: Int? = null,
) {
    init {
        require(displayBox == null || displayBox in 1..3) {
            "An alternate leader display box must be one, two or three."
        }
        require(displayBox == null || protectionCash == 0) {
            "Alternate leader display boxes cannot hold protection cash."
        }
        require(displayBox == null || patronageClaimants.size < displayBox) {
            "A leader cannot exceed its display box's Patronage claim capacity."
        }
        require(protectionCash >= 0) { "Leader protection cash cannot be negative." }
        require(owner !in patronageClaimants) {
            "A leader's owner does not make a Patronage claim."
        }
        require(
            role == CardLeaderRole.EXPOSED || (protectionCash == 0 && patronageClaimants.isEmpty())
        ) {
            "The first leader's display role does not permit protection or Patronage claims."
        }
    }
}

/** One military card; storing the player explicitly preserves its opponent exclusions. */
data class CardMilitaryPlay(val player: PlayerId, val event: EventId) {
    init {
        require(
            event in setOf(EventId.ARMOR, EventId.STIRRUPS, EventId.GUNPOWDER, EventId.LONG_BOW)
        ) {
            "Only military advantage events can enter military play."
        }
    }
}

/**
 * Card effects owned by the current turn. Preserve these through Income and Final Card Play. An
 * unresolved [WarState] is stored separately because it may survive the turn. Leader records remain
 * after their Purchase cleanup so a later final-round leader cannot become this turn's first
 * leader.
 */
data class CardTurnEffects(
    val leaders: List<CardLeaderInPlay> = emptyList(),
    val enlightenedRulers: Set<PlayerId> = emptySet(),
    val military: List<CardMilitaryPlay> = emptyList(),
    val papalCategory: AdvanceCategory? = null,
    val crusadesPlayed: Boolean = false,
    val religiousStrifePlayed: Boolean = false,
    /** Card-play order, applied as move-to-last penalties when Expansion begins. */
    val civilWarVictims: List<PlayerId> = emptyList(),
) {
    init {
        require(leaders.map(CardLeaderInPlay::leader).distinct().size == leaders.size) {
            "Each physical leader can be played only once per turn."
        }
        require(
            leaders.count { leader -> leader.role == CardLeaderRole.DISPLAY } <= 1 &&
                (leaders.isEmpty() || leaders.first().role == CardLeaderRole.DISPLAY)
        ) {
            "The first leader played is the sole leader in the display."
        }
        require(military.map(CardMilitaryPlay::event).distinct().size == military.size) {
            "Each physical military card can be played only once per turn."
        }
        require(
            papalCategory == null ||
                papalCategory in
                    setOf(
                        AdvanceCategory.SCIENCE,
                        AdvanceCategory.RELIGION,
                        AdvanceCategory.EXPLORATION,
                    )
        ) {
            "Papal Decree may forbid only Science, Religion or Exploration."
        }
    }

    fun withMilitary(play: CardMilitaryPlay): CardTurnEffects = copy(military = military + play)

    /** Opponent-specific precedence; obsolete cards already played still affect other opponents. */
    fun militaryBonus(player: PlayerId, opponent: PlayerId): Int {
        val enemy = military.filter { play -> play.player == opponent }.map(CardMilitaryPlay::event)
        return military.count { play ->
            play.player == player &&
                when (play.event) {
                    EventId.ARMOR -> EventId.GUNPOWDER !in enemy && EventId.LONG_BOW !in enemy
                    EventId.STIRRUPS ->
                        EventId.ARMOR !in enemy &&
                            EventId.GUNPOWDER !in enemy &&
                            EventId.LONG_BOW !in enemy
                    EventId.LONG_BOW -> EventId.GUNPOWDER !in enemy
                    EventId.GUNPOWDER -> true
                    else -> false
                }
        }
    }
}

/** Money paid by a claimant to the leader's owner, independently of the owner's banked escrow. */
data class PatronageClaim(
    val leader: LeaderId,
    val claimant: PlayerId,
    val recipient: PlayerId,
    val cash: Int,
    val effects: CardTurnEffects,
)

object LeaderRules {
    fun play(
        context: CardContext,
        actor: PlayerId,
        leader: LeaderId,
        protectionCash: Int = 0,
    ): CardResolution.LeaderPlay {
        val player = context.player(actor)
        val alternateDisplay = context.rules.patronage == PatronageRule.THREE_BOX_DISPLAY
        cardRule(
            !alternateDisplay || protectionCash == 0,
            CardRuleCode.INVALID_CHOICE,
            "The three-box display does not use protection money.",
        )
        cardRule(
            protectionCash >= 0,
            CardRuleCode.INVALID_CHOICE,
            "Leader protection money cannot be negative.",
        )
        cardRule(
            context.effects.leaders.none { played -> played.leader == leader },
            CardRuleCode.INVALID_CHOICE,
            "This physical leader was already played this turn.",
        )
        val role =
            if (context.effects.leaders.isEmpty()) {
                CardLeaderRole.DISPLAY
            } else {
                CardLeaderRole.EXPOSED
            }
        cardRule(
            role == CardLeaderRole.EXPOSED || protectionCash == 0,
            CardRuleCode.INVALID_CHOICE,
            "The first leader is already protected by the display and cannot hold protection cash.",
        )
        val rebate =
            if (AdvanceId.PRINTED_WORD in player.advances) {
                LeaderData.definition(leader)
                    .discounts
                    .filter { discount ->
                        discount.advance in player.advances
                    }
                    .sumOf { discount ->
                        discount.amountFor(context.effects.crusadesPlayed, context.voids.crusades)
                    }
            } else {
                0
            }
        cardRule(
            protectionCash.toLong() <= player.cash.toLong() + rebate,
            CardRuleCode.INSUFFICIENT_CASH,
            "Protection must be affordable when the leader and immediate rebate resolve.",
        )
        val played =
            CardLeaderInPlay(
                leader,
                actor,
                role,
                protectionCash,
                mongolEnhanced = leader == LeaderId.MARCO_POLO && context.voids.crusades,
                displayBox =
                    if (alternateDisplay)
                        (context.effects.leaders.size + 1).takeIf { box -> box <= 3 }
                    else null,
            )
        return CardResolution.LeaderPlay(
            played,
            rebate,
            context.effects.copy(leaders = context.effects.leaders + played),
        )
    }

    /** Claim during Purchase, using currently owned Patronage, including Patronage just bought. */
    fun claim(context: CardContext, actor: PlayerId, leader: LeaderId): PatronageClaim {
        val buyer = context.player(actor)
        val played = context.effects.leaders.singleOrNull { entry -> entry.leader == leader }
        cardRule(
            buyer.isActive && AdvanceId.PATRONAGE in buyer.advances && played != null,
            CardRuleCode.INVALID_LEADER_CLAIM,
            "Patronage requires an active owner and a leader played this turn.",
        )
        val eligible = requireNotNull(played) { "A validated Patronage claim has a played leader." }
        cardRule(
            eligible.role == CardLeaderRole.EXPOSED &&
                eligible.owner != actor &&
                actor !in eligible.patronageClaimants,
            CardRuleCode.INVALID_LEADER_CLAIM,
            "Only another player's exposed, not previously claimed leader is available to Patronage.",
        )
        val capacity =
            if (context.rules.patronage == PatronageRule.THREE_BOX_DISPLAY) {
                eligible.displayBox?.minus(1) ?: Int.MAX_VALUE
            } else Int.MAX_VALUE
        cardRule(
            eligible.patronageClaimants.size < capacity,
            CardRuleCode.INVALID_LEADER_CLAIM,
            "This leader's display box has no remaining Patronage claims.",
        )
        cardRule(
            buyer.cash >= eligible.protectionCash,
            CardRuleCode.INSUFFICIENT_CASH,
            "The claimant must pay the leader's protection amount to its owner.",
        )
        val claimed = eligible.copy(patronageClaimants = eligible.patronageClaimants + actor)
        return PatronageClaim(
            leader,
            actor,
            eligible.owner,
            eligible.protectionCash,
            context.effects.copy(
                leaders =
                    context.effects.leaders.map { entry ->
                        if (entry.leader == leader) claimed else entry
                    }
            ),
        )
    }

    /** Credits stack across owned and claimed leaders; Written Record adds ten per used credit. */
    fun discount(context: CardContext, buyer: PlayerId, advance: AdvanceId): Int {
        val writtenRecordBonus = writtenRecordBonus(context.player(buyer).advances, context.rules)
        return context.effects.leaders
            .filter { leader ->
                leader.owner == buyer || buyer in leader.patronageClaimants
            }
            .sumOf { leader ->
                val credit =
                    LeaderData.definition(leader.leader).discounts.singleOrNull { discount ->
                        discount.advance == advance
                    }
                if (credit == null) return@sumOf 0
                credit.amountFor(context.effects.crusadesPlayed, leader.mongolEnhanced) +
                    writtenRecordBonus
            }
    }

    /** Shared bonus for both authorized purchases and potential hand-card credit. */
    fun writtenRecordBonus(advances: Set<AdvanceId>, rules: RuleOptions): Int =
        if (
            rules.writtenRecord == WrittenRecordRule.LEADER_BONUS &&
                AdvanceId.WRITTEN_RECORD in advances
        )
            10
        else 0

    /** Escrow is forfeited, rather than refunded. Claim payments are already the owner's cash. */
    fun protectionToBank(effects: CardTurnEffects): Int =
        effects.leaders.sumOf(CardLeaderInPlay::protectionCash)
}
