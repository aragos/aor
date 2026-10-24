package dev.aragos.aor

/**
 * Typed card rejections; transport and command handlers translate these into presentation errors.
 */
enum class CardRuleCode {
    UNKNOWN_CARD,
    UNKNOWN_PLAYER,
    CARD_NOT_HELD,
    INVALID_CHOICE,
    ILLEGAL_TARGET,
    IMMUNE_TARGET,
    MISERY_BURDEN,
    INSUFFICIENT_CASH,
    INVALID_LEADER_CLAIM,
    INVALID_WAR,
    INVALID_SURRENDER,
    MARKER_RULING_REQUIRED,
    PAYOUT_RULING_REQUIRED,
}

class CardRuleViolation(val code: CardRuleCode, val explanation: String) : Exception(explanation)

internal fun cardRule(valid: Boolean, code: CardRuleCode, explanation: String) {
    if (!valid) {
        throw CardRuleViolation(code, explanation)
    }
}

/**
 * Read-only adapter for resolving a card; this is not a second persisted player model.
 * [writtenCash] is cash remaining after this turn's token purchase, and [handCardIds] contains
 * physical card ids.
 */
data class CardPlayerContext(
    val cash: Int,
    val writtenCash: Int,
    val poolTokens: Int,
    val stockTokens: Int,
    val advances: Set<AdvanceId>,
    val capital: ProvinceId,
    val misery: MiseryStep,
    val handCardIds: Set<String> = emptySet(),
    val ships: ShippingCapability = ShippingCapability.NONE,
    /** Overseas boxes containing any of this player's square tokens or markers. */
    val overseasPresence: Set<ProvinceId> = emptySet(),
) {
    val isActive: Boolean
        get() = misery != MiseryStep.CHAOS

    init {
        require(cash >= 0 && writtenCash >= 0) { "Current and written cash cannot be negative." }
        require(poolTokens in 0..36 && stockTokens in 0..36 && poolTokens + stockTokens <= 36) {
            "Stock and expansion pool must fit within the player's thirty-six tokens."
        }
    }
}

/** Domination-marker facts needed by card rules. History cards refer to coloured counters only. */
data class CardMarker(val owner: PlayerId, val coloured: Boolean = true)

enum class CommodityAdjustment(val payoutOffset: Int) {
    SHORTAGE(1),
    SURPLUS(-1),
}

/**
 * Ephemeral inputs assembled by the driver from authoritative state. Adjustment lists are ordered;
 * one marker is consumed per commodity play, so a double shortage shifts two payouts by one box.
 * [provinces] contains only spaces in this game. Card rules never mutate the supplied collections.
 */
data class CardContext(
    val players: Map<PlayerId, CardPlayerContext>,
    val markers: Map<ProvinceId, CardMarker> = emptyMap(),
    val effects: CardTurnEffects = CardTurnEffects(),
    val adjustments: Map<Commodity, List<CommodityAdjustment>> = emptyMap(),
    val voids: CardVoids = CardVoids(),
    val finalCardPlay: Boolean = false,
    val turn: Int = 1,
    val provinces: Map<ProvinceId, ProvinceDefinition> =
        ProvinceData.definitions
            .filter { province ->
                province.area == null || province.area.minimumPlayers <= players.size
            }
            .associateBy(ProvinceDefinition::id),
) {
    init {
        require(players.size in 3..6) {
            "Card resolution requires three through six registered players."
        }
        require(turn > 0) { "A card play requires a positive turn number." }
        require(
            markers.all { (province, marker) ->
                province in provinces &&
                    !provinces.getValue(province).isSatellite &&
                    marker.owner in players
            }
        ) {
            "Card markers must reference a known province and player."
        }
        require(
            markers.values.groupingBy(CardMarker::owner).eachCount().values.all { count ->
                count <= 25
            }
        ) {
            "A player's board cannot contain more than twenty-five domination markers."
        }
        require(
            effects.enlightenedRulers.all(players::containsKey) &&
                effects.military.all { play -> play.player in players } &&
                effects.leaders.all { leader ->
                    leader.owner in players && leader.patronageClaimants.all(players::containsKey)
                }
        ) {
            "Turn card effects must refer to registered players."
        }
        require(
            adjustments.values.all { markers -> markers.size <= 2 && markers.distinct().size <= 1 }
        ) {
            "Each commodity may have at most two equal, uncancelled adjustment markers."
        }
    }

    fun player(id: PlayerId): CardPlayerContext =
        players[id]
            ?: throw CardRuleViolation(
                CardRuleCode.UNKNOWN_PLAYER,
                "The card refers to an unknown player: $id.",
            )

    fun province(id: ProvinceId): ProvinceDefinition =
        provinces[id]
            ?: throw CardRuleViolation(
                CardRuleCode.ILLEGAL_TARGET,
                "The card refers to an unavailable province: $id.",
            )

    val activePlayers: Map<PlayerId, CardPlayerContext>
        get() = players.filterValues(CardPlayerContext::isActive)
}

/** Permanent card obsolescence; temporary Enlightened Ruler protection does not set these flags. */
data class CardVoids(
    val armorAndStirrups: Boolean = false,
    val crusades: Boolean = false,
    val papalDecree: Boolean = false,
)

/** A play's choice is finite; each card validates which choice type it accepts. */
sealed interface CardChoice {
    data object None : CardChoice

    data class CommodityChoice(val commodity: Commodity) : CardChoice

    data class PlayerChoice(val player: PlayerId) : CardChoice

    data class ProvinceChoice(val province: ProvinceId) : CardChoice

    /** Null declines to forbid a category when playing Papal Decree. */
    data class CategoryChoice(val category: AdvanceCategory?) : CardChoice

    /** Dollars placed on an exposed leader at play time. */
    data class LeaderProtection(val cash: Int) : CardChoice {
        init {
            require(cash >= 0) { "Leader protection money cannot be negative." }
        }
    }
}

data class CardPlay(val cardId: String, val choice: CardChoice = CardChoice.None)

enum class EventTokenReplacement {
    STOCK,
    /** The counter's owner chooses to spend one pool token or leave the province empty. */
    POOL_OR_EMPTY,
    EMPTY,
}

enum class CivilWarLossChoice {
    CASH,
    EXPANSION_TOKENS,
}

/**
 * Dollar/token loss amounts, with fractions rounded up. In Final Card Play both choices cost zero.
 */
data class CivilWarLosses(val cash: Int, val expansionTokens: Int) {
    init {
        require(cash >= 0 && expansionTokens >= 0) { "Civil War losses cannot be negative." }
    }
}

/**
 * Finite card-specific outcomes. The driver applies them and records any ensuing victim decisions.
 */
sealed interface CardResolution {
    /** A non-burden targeted event with no eligible target may be played without a target. */
    data class NoEffect(val event: EventId) : CardResolution

    data class CommodityPayment(
        val commodity: Commodity,
        val payments: Map<PlayerId, Int>,
        val consumedAdjustment: CommodityAdjustment?,
    ) : CardResolution

    data class AlchemistLoss(val target: PlayerId, val cash: Int) : CardResolution

    data class Military(val play: CardMilitaryPlay, val effects: CardTurnEffects) : CardResolution

    data class CivilWar(
        val victim: PlayerId,
        val capitalReduction: MarkerReduction?,
        val losses: CivilWarLosses,
        val moveLastAtExpansion: Boolean,
    ) : CardResolution

    data class EnlightenedRuler(val player: PlayerId, val effects: CardTurnEffects) : CardResolution

    data class MiseryEvent(val event: EventId, val spaces: Map<PlayerId, Int>) : CardResolution

    data class PapalDecree(val category: AdvanceCategory?, val effects: CardTurnEffects) :
        CardResolution

    /** The replacement token, if available, is coloured rather than newly placed white. */
    data class MarkerReduction(
        val event: EventId,
        val province: ProvinceId,
        val owner: PlayerId,
        val replacement: EventTokenReplacement,
    ) : CardResolution

    /**
     * Remove every existing piece in [province] and place the player's coloured marker; +1 misery.
     */
    data class CrusadesPlacement(
        val province: ProvinceId,
        val player: PlayerId,
        val effects: CardTurnEffects,
    ) : CardResolution

    data class WarBattle(val war: WarState) : CardResolution

    /** [rebate] is immediate; [leader]'s protection cash is separately paid into escrow. */
    data class LeaderPlay(
        val leader: CardLeaderInPlay,
        val rebate: Int,
        val effects: CardTurnEffects,
    ) : CardResolution
}

object CardRules {
    private val sciences =
        AdvanceData.definitions
            .filter { advance ->
                advance.category == AdvanceCategory.SCIENCE
            }
            .map(AdvanceDefinition::id)
            .toSet()

    /** Checks permanent conditions only. A temporarily immune player is filtered as a target. */
    fun isBurden(context: CardContext, cardId: String): Boolean {
        val spec =
            CardEffectData.epochOne[cardId]
                ?: throw CardRuleViolation(
                    CardRuleCode.UNKNOWN_CARD,
                    "Unknown epoch-one card: $cardId.",
                )
        if (spec !is CardEffectSpec.Event) return false
        val players = context.activePlayers.values
        return when (spec.event) {
            EventId.ALCHEMISTS_GOLD ->
                players.isNotEmpty() &&
                    players.all { player ->
                        AdvanceId.LAWS_OF_MATTER in player.advances
                    }
            EventId.MYSTICISM_ABOUNDS ->
                players.isNotEmpty() &&
                    players.all { player ->
                        player.advances.containsAll(sciences)
                    }
            EventId.ARMOR,
            EventId.STIRRUPS -> context.voids.armorAndStirrups
            EventId.THE_CRUSADES -> context.voids.crusades
            EventId.PAPAL_DECREE -> context.voids.papalDecree
            else -> false
        }
    }

    fun immune(context: CardContext, player: PlayerId, event: EventId): Boolean =
        (event == EventId.ALCHEMISTS_GOLD &&
            AdvanceId.LAWS_OF_MATTER in context.player(player).advances) ||
            (player in context.effects.enlightenedRulers &&
                event in
                    setOf(
                        EventId.ALCHEMISTS_GOLD,
                        EventId.CIVIL_WAR,
                        EventId.MYSTICISM_ABOUNDS,
                        EventId.REBELLION,
                        EventId.REVOLUTIONARY_UPRISINGS,
                    ))

    /**
     * Validates ownership, finite choice shape, target and immunities before returning any effect.
     */
    fun resolve(context: CardContext, actor: PlayerId, play: CardPlay): CardResolution {
        val player = context.player(actor)
        cardRule(
            player.isActive,
            CardRuleCode.ILLEGAL_TARGET,
            "A player in Chaos cannot play cards.",
        )
        val spec =
            CardEffectData.epochOne[play.cardId]
                ?: throw CardRuleViolation(
                    CardRuleCode.UNKNOWN_CARD,
                    "Unknown epoch-one card: ${play.cardId}.",
                )
        cardRule(
            play.cardId in player.handCardIds,
            CardRuleCode.CARD_NOT_HELD,
            "The player does not hold this physical card: ${play.cardId}.",
        )
        cardRule(
            !isBurden(context, play.cardId),
            CardRuleCode.MISERY_BURDEN,
            "A misery burden cannot be played: ${play.cardId}.",
        )
        if (
            spec is CardEffectSpec.Event &&
                play.choice == CardChoice.None &&
                spec.event in targetedEvents &&
                !hasLegalTarget(context, actor, spec.event)
        ) {
            return CardResolution.NoEffect(spec.event)
        }
        return when (spec) {
            is CardEffectSpec.CommodityChoices -> {
                val choice = play.choice as? CardChoice.CommodityChoice
                val commodity = choice?.commodity ?: spec.choices.singleOrNull()
                cardRule(
                    (choice != null || play.choice == CardChoice.None) && commodity in spec.choices,
                    CardRuleCode.INVALID_CHOICE,
                    "Choose a commodity printed on this card.",
                )
                commodityPayment(
                    context,
                    requireNotNull(commodity) { "A validated commodity must be selected." },
                )
            }
            is CardEffectSpec.Leader -> {
                val protection =
                    when (val choice = play.choice) {
                        CardChoice.None -> 0
                        is CardChoice.LeaderProtection -> choice.cash
                        else ->
                            throw CardRuleViolation(
                                CardRuleCode.INVALID_CHOICE,
                                "A leader accepts only protection money as its play choice.",
                            )
                    }
                LeaderRules.play(context, actor, spec.leader, protection)
            }
            is CardEffectSpec.Event -> event(context, actor, spec.event, play.choice)
        }
    }

    private val targetedEvents =
        setOf(
            EventId.ALCHEMISTS_GOLD,
            EventId.CIVIL_WAR,
            EventId.WAR,
            EventId.PIRATES_VIKINGS,
            EventId.REBELLION,
            EventId.THE_CRUSADES,
        )

    /** Target existence is distinct from permanent burden status and temporary effect immunity. */
    fun hasLegalTarget(context: CardContext, actor: PlayerId, event: EventId): Boolean =
        when (event) {
            EventId.ALCHEMISTS_GOLD,
            EventId.CIVIL_WAR,
            EventId.WAR ->
                context.activePlayers.keys.any { target ->
                    (event != EventId.WAR || target != actor) && !immune(context, target, event)
                }
            EventId.PIRATES_VIKINGS,
            EventId.REBELLION ->
                context.markers.any { (province, marker) ->
                    val definition = context.province(province)
                    marker.coloured &&
                        (!context.player(marker.owner).isActive ||
                            !immune(context, marker.owner, event)) &&
                        if (event == EventId.PIRATES_VIKINGS) definition.coastal
                        else {
                            definition.overseas != OverseasGroup.NEW_WORLD &&
                                (definition.capital == null ||
                                    province == context.player(actor).capital)
                        }
                }
            EventId.THE_CRUSADES ->
                context.provinces.values.any { province ->
                    province.area == GeographicalArea.VI && !province.isSatellite
                }
            else -> true
        }

    private fun event(
        context: CardContext,
        actor: PlayerId,
        event: EventId,
        choice: CardChoice,
    ): CardResolution =
        when (event) {
            EventId.ALCHEMISTS_GOLD -> {
                val target = target(context, event, choice)
                val player = context.player(target)
                CardResolution.AlchemistLoss(target, minOf(halfUp(player.writtenCash), player.cash))
            }
            EventId.CIVIL_WAR -> {
                val target = target(context, event, choice)
                val player = context.player(target)
                val losses =
                    if (context.finalCardPlay) {
                        CivilWarLosses(0, 0)
                    } else {
                        CivilWarLosses(
                            minOf(halfUp(player.writtenCash), player.cash),
                            halfUp(player.poolTokens),
                        )
                    }
                val marker = context.markers[player.capital]?.takeIf(CardMarker::coloured)
                CardResolution.CivilWar(
                    target,
                    marker?.let { capitalMarker ->
                        reduction(context, event, player.capital, capitalMarker)
                    },
                    losses,
                    moveLastAtExpansion = !context.finalCardPlay,
                )
            }
            EventId.ARMOR,
            EventId.STIRRUPS -> {
                noChoice(choice)
                val military = CardMilitaryPlay(actor, event)
                CardResolution.Military(military, context.effects.withMilitary(military))
            }
            EventId.ENLIGHTENED_RULER -> {
                noChoice(choice)
                CardResolution.EnlightenedRuler(
                    actor,
                    context.effects.copy(
                        enlightenedRulers = context.effects.enlightenedRulers + actor
                    ),
                )
            }
            EventId.FAMINE,
            EventId.MYSTICISM_ABOUNDS,
            EventId.REVOLUTIONARY_UPRISINGS -> {
                noChoice(choice)
                CardResolution.MiseryEvent(
                    event,
                    context.activePlayers.mapValues { (player, _) ->
                        miserySpaces(context, player, event)
                    },
                )
            }
            EventId.PAPAL_DECREE -> {
                val category =
                    when (choice) {
                        CardChoice.None -> null
                        is CardChoice.CategoryChoice -> choice.category
                        else ->
                            throw CardRuleViolation(
                                CardRuleCode.INVALID_CHOICE,
                                "Papal Decree accepts a category choice.",
                            )
                    }
                cardRule(
                    category == null ||
                        category in
                            setOf(
                                AdvanceCategory.SCIENCE,
                                AdvanceCategory.RELIGION,
                                AdvanceCategory.EXPLORATION,
                            ),
                    CardRuleCode.INVALID_CHOICE,
                    "Papal Decree may forbid Science, Religion or Exploration.",
                )
                CardResolution.PapalDecree(category, context.effects.copy(papalCategory = category))
            }
            EventId.PIRATES_VIKINGS,
            EventId.REBELLION -> {
                val province = provinceChoice(choice)
                val definition = context.province(province)
                val marker = context.markers[province]
                cardRule(
                    marker != null && marker.coloured,
                    CardRuleCode.ILLEGAL_TARGET,
                    "The event requires a coloured domination marker.",
                )
                val targetMarker =
                    requireNotNull(marker) { "A validated event target has a marker." }
                cardRule(
                    !context.player(targetMarker.owner).isActive ||
                        !immune(context, targetMarker.owner, event),
                    CardRuleCode.IMMUNE_TARGET,
                    "The marker's owner is protected against this event.",
                )
                if (event == EventId.PIRATES_VIKINGS) {
                    cardRule(
                        definition.coastal,
                        CardRuleCode.ILLEGAL_TARGET,
                        "Pirates/Vikings requires a coastal province, including overseas boxes.",
                    )
                } else {
                    cardRule(
                        definition.overseas != OverseasGroup.NEW_WORLD &&
                            (definition.capital == null ||
                                province == context.player(actor).capital),
                        CardRuleCode.ILLEGAL_TARGET,
                        "Rebellion cannot target the New World or a foreign capital.",
                    )
                }
                reduction(context, event, province, targetMarker)
            }
            EventId.THE_CRUSADES -> {
                val province = provinceChoice(choice)
                val definition = context.province(province)
                cardRule(
                    definition.area == GeographicalArea.VI && !definition.isSatellite,
                    CardRuleCode.ILLEGAL_TARGET,
                    "The Crusades requires a market in Area VI.",
                )
                val owned = context.markers.values.count { marker -> marker.owner == actor }
                cardRule(
                    owned < 25 || context.markers[province]?.owner == actor,
                    CardRuleCode.MARKER_RULING_REQUIRED,
                    "Crusades with no available domination marker requires the counter-capacity ruling.",
                )
                CardResolution.CrusadesPlacement(
                    province,
                    actor,
                    context.effects.copy(crusadesPlayed = true),
                )
            }
            EventId.WAR -> {
                val target = target(context, event, choice)
                cardRule(
                    target != actor,
                    CardRuleCode.ILLEGAL_TARGET,
                    "War requires a different player as its opponent.",
                )
                CardResolution.WarBattle(WarState(actor, target, startedTurn = context.turn))
            }
        }

    private fun miserySpaces(context: CardContext, playerId: PlayerId, event: EventId): Int {
        if (immune(context, playerId, event)) return 0
        val player = context.player(playerId)
        return when (event) {
            EventId.FAMINE -> {
                val agriculture = if (AdvanceId.IMPROVED_AGRICULTURE in player.advances) 1 else 0
                (4 - dominated(context, playerId, Commodity.GRAIN) - agriculture).coerceAtLeast(0)
            }
            EventId.MYSTICISM_ABOUNDS -> 4 - player.advances.count(sciences::contains)
            EventId.REVOLUTIONARY_UPRISINGS ->
                player.advances.count { advance ->
                    AdvanceData.definition(advance).category == AdvanceCategory.COMMERCE
                }
            else -> error("Only a misery event can calculate misery spaces.")
        }
    }

    private fun target(context: CardContext, event: EventId, choice: CardChoice): PlayerId {
        val target =
            (choice as? CardChoice.PlayerChoice)?.player
                ?: throw CardRuleViolation(
                    CardRuleCode.INVALID_CHOICE,
                    "This event requires a player choice.",
                )
        cardRule(
            context.player(target).isActive,
            CardRuleCode.ILLEGAL_TARGET,
            "An event cannot target a player in Chaos.",
        )
        cardRule(
            !immune(context, target, event),
            CardRuleCode.IMMUNE_TARGET,
            "The selected player is protected against this event.",
        )
        return target
    }

    private fun provinceChoice(choice: CardChoice): ProvinceId =
        (choice as? CardChoice.ProvinceChoice)?.province
            ?: throw CardRuleViolation(
                CardRuleCode.INVALID_CHOICE,
                "This event requires a province choice.",
            )

    private fun noChoice(choice: CardChoice) {
        cardRule(
            choice == CardChoice.None,
            CardRuleCode.INVALID_CHOICE,
            "This card does not accept a target or additional choice.",
        )
    }

    private fun reduction(
        context: CardContext,
        event: EventId,
        province: ProvinceId,
        marker: CardMarker,
    ): CardResolution.MarkerReduction {
        val player = context.player(marker.owner)
        val replacement =
            when {
                player.stockTokens > 0 -> EventTokenReplacement.STOCK
                player.poolTokens > 0 -> EventTokenReplacement.POOL_OR_EMPTY
                else -> EventTokenReplacement.EMPTY
            }
        return CardResolution.MarkerReduction(event, province, marker.owner, replacement)
    }

    /** Payout progression is quadratic; the board's ownership-track length is not a payout cap. */
    fun commodityPayment(
        context: CardContext,
        commodity: Commodity,
    ): CardResolution.CommodityPayment {
        val definition = CommodityData.definitions.single { entry -> entry.id == commodity }
        val adjustment = context.adjustments[commodity]?.firstOrNull()
        val payments =
            context.activePlayers.mapValues { (id, player) ->
                val count = dominated(context, id, commodity)
                if (count == 0) {
                    0
                } else {
                    val box =
                        (count +
                                (adjustment?.payoutOffset ?: 0) +
                                if (AdvanceId.INDUSTRY in player.advances) 1 else 0)
                            .coerceAtLeast(0)
                    cardRule(
                        box <= 10,
                        CardRuleCode.PAYOUT_RULING_REQUIRED,
                        "A payout beyond the ten printed card boxes requires the overflow ruling.",
                    )
                    definition.baseValue * box * box
                }
            }
        return CardResolution.CommodityPayment(commodity, payments, adjustment)
    }

    private fun dominated(context: CardContext, player: PlayerId, commodity: Commodity): Int =
        context.markers.count { (province, marker) ->
            marker.owner == player &&
                marker.coloured &&
                commodity in context.province(province).commodities
        }

    private fun halfUp(amount: Int): Int = amount / 2 + amount % 2
}
