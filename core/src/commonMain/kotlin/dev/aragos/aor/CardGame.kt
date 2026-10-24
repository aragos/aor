package dev.aragos.aor

/**
 * Card-owned interruption frames; the outer phase actor stays on [TurnFlow] while victims reply.
 */
sealed interface CardDecision : TurnDecision {
    data class CivilWarLoss(override val player: PlayerId) : CardDecision {
        override val choice: String
            get() = "civil-war-loss"

        override fun choices(state: GameState): List<String> =
            listOf("civil-war-loss cash", "civil-war-loss tokens")
    }

    data class ReplacementToken(override val player: PlayerId, val province: ProvinceId) :
        CardDecision {
        override val choice: String
            get() = "replace-event-token"

        override fun choices(state: GameState): List<String> =
            listOf("replace-event-token pool", "replace-event-token none")
    }

    data class WarPreparation(override val player: PlayerId) : CardDecision {
        override val choice: String
            get() = "prepare-war"

        override fun choices(state: GameState): List<String> =
            listOf("pass") + CardGame.playChoices(state, player, militaryOnly = true)
    }

    data class WarRoll(val war: WarState) : CardDecision, TurnDecision.Roll {
        override val choice: String
            get() = "war-roll"

        override val kind: GameRollKind
            get() = GameRollKind.WAR

        override val diceCount: Int
            get() = 2

        override val player: PlayerId?
            get() = null

        override fun resolve(state: GameState, values: List<DieRoll>): GameState =
            CardGame.roll(state, values)
    }

    /** Physical marker shortage suspends the outer card round for the receiving player. */
    data class MarkerPlacement(
        val destinations: List<ProvinceId>,
        val aftermath: MarkerPlacementAftermath,
    ) : CardDecision {
        init {
            require(
                destinations.isNotEmpty() && destinations.distinct().size == destinations.size
            ) {
                "A marker placement requires distinct pending destinations."
            }
        }

        override val player: PlayerId
            get() = aftermath.player

        val destination: ProvinceId
            get() = destinations.first()

        override val choice: String
            get() = "place-event-marker"

        override fun choices(state: GameState): List<String> =
            listOf(CardCommand.ForgoMarker.toText()) +
                CardMarkerPlacements.sources(state, player, destination).map { province ->
                    CardCommand.MoveMarker(province).toText()
                }
    }

    data class WarSurrender(override val player: PlayerId, val victory: WarResult.Victory) :
        CardDecision {
        override val choice: String
            get() = "war-surrender"

        override fun choices(state: GameState): List<String> {
            val context = state.cardContext()
            val eligible = WarRules.eligibleProvinces(context, victory.winner, victory.loser)
            val mainland = eligible.filter { province ->
                context.province(province).overseas == null
            }
            val overseas =
                eligible
                    .filter { province -> context.province(province).overseas != null }
                    .take(WarRules.overseasCapacity(context, victory.winner))
            val candidates = mainland + overseas
            val count = WarRules.requiredCessions(context, victory)
            return listOf(CardCommand.CedeWar(candidates.take(count).toSet()).toText())
        }
    }
}

/**
 * Applies finite pure card outcomes to the authoritative game, without choosing dice or victims.
 */
object CardGame {
    fun isBurden(state: GameState, cardId: String): Boolean =
        CardRules.isBurden(state.cardContext(), cardId)

    /**
     * Called before the normal driver skips an empty hand: an empty-handed player still hosts
     * rerolls.
     */
    fun prepareRound(state: GameState): GameState {
        if (state.stage != Stage.PLAY_CARDS && state.stage != Stage.FINAL_CARD_PLAY) return state
        if (state.flow.decisions.isNotEmpty()) return state
        val actor = state.flow.actor ?: return state
        val war = state.activeWar ?: return state
        val context = state.cardContext()
        if (!WarRules.canContinue(context, war)) return state.copy(activeWar = null)
        if (war.lastRound == currentRound(state)) return state
        val hasMilitaryChoice =
            actor in setOf(war.initiator, war.opponent) &&
                playChoices(state, actor, militaryOnly = true).isNotEmpty()
        return state.pushDecision(
            if (hasMilitaryChoice) CardDecision.WarPreparation(actor) else CardDecision.WarRoll(war)
        )
    }

    /** The ordinary pass command doubles as declining the optional pre-reroll military window. */
    fun passDecision(state: GameState, actor: PlayerId): GameState? {
        val decision =
            state.flow.decisions.lastOrNull() as? CardDecision.WarPreparation ?: return null
        checkRule(decision.player == actor, ViolationCode.WRONG_PLAYER)
        return prepareWarRoll(state)
    }

    fun play(state: GameState, actor: PlayerId, reference: String, choice: CardChoice): GameState {
        checkRule(
            state.stage == Stage.PLAY_CARDS || state.stage == Stage.FINAL_CARD_PLAY,
            ViolationCode.WRONG_PHASE,
        )
        val preparing = state.flow.decisions.lastOrNull() as? CardDecision.WarPreparation
        if (preparing == null) {
            state.requireActor(actor, state.stage)
        } else {
            checkRule(
                preparing.player == actor && state.flow.actor == actor,
                ViolationCode.WRONG_PLAYER,
            )
        }
        val player = state.playerState(actor)
        val handCard = player.hand.singleOrNull { card -> card.reference == reference }
        cardRule(
            handCard != null,
            CardRuleCode.CARD_NOT_HELD,
            "Choose a reference in your own hand.",
        )
        val card = requireNotNull(handCard) { "A validated card play has a hand reference." }
        if (preparing != null) {
            val spec = CardEffectData.forCard(card.definition.id)
            cardRule(
                spec.isMilitary(),
                CardRuleCode.INVALID_CHOICE,
                "A pending War reroll must resolve before any non-military card is played.",
            )
        }
        cardRule(
            state.activeWar == null || card.definition.id != EventId.WAR.cardId,
            CardRuleCode.INVALID_WAR,
            "A second War cannot begin while the first is unresolved.",
        )
        val resolution =
            CardRules.resolve(state.cardContext(), actor, CardPlay(card.definition.id, choice))
        val committed =
            state.replacePlayer(
                player.copy(hand = player.hand.filterNot { held -> held.reference == reference })
            )
        return apply(committed, resolution)
    }

    fun chooseCivilWarLoss(
        state: GameState,
        actor: PlayerId,
        choice: CivilWarLossChoice,
    ): GameState {
        val frame = state.flow.decisions.lastOrNull() as? CardDecision.CivilWarLoss
        checkRule(frame?.player == actor, ViolationCode.WRONG_PLAYER)
        val player = state.playerState(actor)
        val updated =
            when (choice) {
                CivilWarLossChoice.CASH ->
                    player.copy(cash = (player.cash - halfUp(player.writtenCash)).coerceAtLeast(0))
                // Capital replacement is resolved first, so this half uses the remaining expansion
                // pool.
                CivilWarLossChoice.EXPANSION_TOKENS -> {
                    val loss = halfUp(player.poolTokens)
                    player.copy(
                        poolTokens = player.poolTokens - loss,
                        stockTokens = player.stockTokens + loss,
                    )
                }
            }
        return state.popDecision().replacePlayer(updated)
    }

    fun replaceEventToken(state: GameState, actor: PlayerId, usePool: Boolean): GameState {
        val frame = state.flow.decisions.lastOrNull() as? CardDecision.ReplacementToken
        checkRule(frame?.player == actor, ViolationCode.WRONG_PLAYER)
        val replacement =
            requireNotNull(frame) { "A validated event replacement requires a frame." }
        val next = state.popDecision()
        if (!usePool) return next
        val owner = next.playerState(actor)
        cardRule(
            owner.poolTokens > 0,
            CardRuleCode.ILLEGAL_TARGET,
            "A replacement requires one expansion token.",
        )
        val change = next.board.replaceWithToken(replacement.province, actor)
        return next
            .withBoardChange(change.copy(tokensRequired = emptyMap()))
            .replacePlayer(owner.copy(poolTokens = owner.poolTokens - 1))
    }

    private fun prepareWarRoll(state: GameState): GameState {
        val war =
            requireNotNull(state.activeWar) { "A War preparation frame requires an active War." }
        return state.popDecision().pushDecision(CardDecision.WarRoll(war))
    }

    fun roll(state: GameState, values: List<DieRoll>): GameState {
        val frame = state.flow.decisions.lastOrNull() as? CardDecision.WarRoll
        checkRule(
            frame != null && frame.war == state.activeWar && values.size == 2,
            ViolationCode.INVALID_OUTCOME,
        )
        val pending = requireNotNull(frame) { "A validated War roll requires a pending frame." }
        val next = state.popDecision()
        return when (
            val result =
                WarRules.roll(next.cardContext(), pending.war, WarDice(values[0], values[1]))
        ) {
            is WarResult.Tie -> {
                val tied =
                    next
                        .copy(activeWar = result.war.copy(lastRound = currentRound(state)))
                        .gainMisery(result.war.initiator, 1)
                        .gainMisery(result.war.opponent, 1)
                if (WarRules.canContinue(tied.cardContext(), result.war)) {
                    tied
                } else {
                    tied.copy(activeWar = null)
                }
            }
            is WarResult.Victory -> {
                val won = next.copy(activeWar = null)
                val eligible =
                    WarRules.eligibleProvinces(won.cardContext(), result.winner, result.loser)
                if (result.requiredTransfers == 0 || eligible.isEmpty()) {
                    settleWar(won, result, emptySet())
                } else {
                    won.pushDecision(CardDecision.WarSurrender(result.loser, result))
                }
            }
        }
    }

    fun surrenderWar(state: GameState, actor: PlayerId, provinces: Set<ProvinceId>): GameState {
        val frame = state.flow.decisions.lastOrNull() as? CardDecision.WarSurrender
        checkRule(frame?.player == actor, ViolationCode.WRONG_PLAYER)
        val pending =
            requireNotNull(frame) { "A validated War surrender requires a pending frame." }
        return settleWar(state.popDecision(), pending.victory, provinces)
    }

    private fun settleWar(
        state: GameState,
        victory: WarResult.Victory,
        provinces: Set<ProvinceId>,
    ): GameState {
        val settlement = WarRules.surrender(state.cardContext(), victory, provinces)
        return CardMarkerPlacements.war(state, settlement)
    }

    internal fun completeMarkerPlacement(
        state: GameState,
        aftermath: MarkerPlacementAftermath,
    ): GameState =
        when (aftermath) {
            is MarkerPlacementAftermath.Crusades -> state.gainMisery(aftermath.player, 1)
            is MarkerPlacementAftermath.War ->
                state
                    .gainMisery(aftermath.settlement.winner, 1)
                    .gainMisery(
                        aftermath.settlement.loser,
                        2 + aftermath.settlement.additionalLoserMisery,
                    )
        }

    fun patronize(state: GameState, actor: PlayerId, leader: LeaderId): GameState {
        state.requirePurchaseStep(actor, PurchaseStep.ADVANCES)
        val claim = LeaderRules.claim(state.cardContext(), actor, leader)
        val buyer = state.playerState(actor)
        val recipient = state.playerState(claim.recipient)
        return state
            .copy(effects = claim.effects)
            .replacePlayer(buyer.copy(cash = buyer.cash - claim.cash))
            .replacePlayer(recipient.copy(cash = recipient.cash + claim.cash))
    }

    private fun apply(state: GameState, resolution: CardResolution): GameState =
        when (resolution) {
            is CardResolution.NoEffect -> state
            is CardResolution.CommodityPayment -> {
                val paid =
                    resolution.payments.entries.fold(state) { current, (player, amount) ->
                        current.replacePlayer(
                            current.playerState(player).let { owner ->
                                owner.copy(cash = owner.cash + amount)
                            }
                        )
                    }
                val remaining = paid.adjustments[resolution.commodity].orEmpty().drop(1)
                paid.copy(
                    adjustments =
                        if (remaining.isEmpty()) {
                            paid.adjustments - resolution.commodity
                        } else {
                            paid.adjustments + (resolution.commodity to remaining)
                        }
                )
            }
            is CardResolution.AlchemistLoss ->
                state.replacePlayer(
                    state.playerState(resolution.target).let { player ->
                        player.copy(cash = player.cash - resolution.cash)
                    }
                )
            is CardResolution.Military -> state.copy(effects = resolution.effects)
            is CardResolution.EnlightenedRuler -> state.copy(effects = resolution.effects)
            is CardResolution.PapalDecree -> state.copy(effects = resolution.effects)
            is CardResolution.MiseryEvent ->
                resolution.spaces.entries.fold(state) { current, (player, spaces) ->
                    current.gainMisery(player, spaces)
                }
            is CardResolution.CivilWar -> civilWar(state, resolution)
            is CardResolution.MarkerReduction -> reduceMarker(state, resolution)
            is CardResolution.CrusadesPlacement -> CardMarkerPlacements.crusades(state, resolution)
            is CardResolution.WarBattle ->
                state
                    .copy(activeWar = resolution.war)
                    .pushDecision(CardDecision.WarRoll(resolution.war))
            is CardResolution.LeaderPlay ->
                state
                    .copy(effects = resolution.effects)
                    .replacePlayer(
                        state.playerState(resolution.leader.owner).let { player ->
                            player.copy(
                                cash =
                                    player.cash + resolution.rebate -
                                        resolution.leader.protectionCash
                            )
                        }
                    )
        }

    private fun civilWar(state: GameState, resolution: CardResolution.CivilWar): GameState {
        val hurt = state.gainMisery(resolution.victim, 1)
        val ordered =
            if (resolution.moveLastAtExpansion) {
                hurt.copy(
                    effects =
                        hurt.effects.copy(
                            civilWarVictims = hurt.effects.civilWarVictims + resolution.victim
                        )
                )
            } else {
                hurt
            }
        val awaitingLoss =
            if (
                !state.cardContext().finalCardPlay &&
                    ordered.playerState(resolution.victim).misery != MiseryStep.CHAOS
            ) {
                ordered.pushDecision(CardDecision.CivilWarLoss(resolution.victim))
            } else {
                ordered
            }
        val reduction = resolution.capitalReduction ?: return awaitingLoss
        return reduceMarker(awaitingLoss, reduction)
    }

    private fun reduceMarker(
        state: GameState,
        reduction: CardResolution.MarkerReduction,
    ): GameState {
        val marked =
            state.copy(
                flow = state.flow.copy(eventReduced = state.flow.eventReduced + reduction.province)
            )
        val owner = marked.playerState(reduction.owner)
        // Civil War may eliminate the owner before reducing its capital. Chaos returns its pool
        // to stock, and an eliminated player cannot answer an optional pool replacement frame.
        val replacement =
            when {
                owner.misery != MiseryStep.CHAOS -> reduction.replacement
                owner.stockTokens > 0 -> EventTokenReplacement.STOCK
                else -> EventTokenReplacement.EMPTY
            }
        return when (replacement) {
            EventTokenReplacement.STOCK ->
                marked.withBoardChange(
                    marked.board.replaceWithToken(reduction.province, reduction.owner)
                )
            EventTokenReplacement.EMPTY ->
                marked.withBoardChange(marked.board.remove(reduction.province))
            EventTokenReplacement.POOL_OR_EMPTY ->
                marked
                    .withBoardChange(marked.board.remove(reduction.province))
                    .pushDecision(
                        CardDecision.ReplacementToken(reduction.owner, reduction.province)
                    )
        }
    }

    internal fun canPassFinalCards(state: GameState, actor: PlayerId): Boolean {
        val context = state.cardContext()
        return state.playerState(actor).hand.all { card ->
            CardRules.isBurden(context, card.definition.id)
        }
    }

    /** Concrete legal card commands, including targets, also used in the military reroll window. */
    fun playChoices(
        state: GameState,
        actor: PlayerId,
        militaryOnly: Boolean = false,
    ): List<String> {
        val context = state.cardContext()
        return state.playerState(actor).hand.flatMap { card ->
            val spec = CardEffectData.forCard(card.definition.id)
            if (militaryOnly && !spec.isMilitary()) return@flatMap emptyList()
            choices(context, spec).mapNotNull { choice ->
                try {
                    CardRules.resolve(context, actor, CardPlay(card.definition.id, choice))
                    CardCommand.PlayCard(card.reference, choice).toText()
                } catch (_: CardRuleViolation) {
                    null
                }
            }
        }
    }

    private fun CardEffectSpec.isMilitary(): Boolean =
        this is CardEffectSpec.Event && event in setOf(EventId.ARMOR, EventId.STIRRUPS)

    private fun choices(context: CardContext, spec: CardEffectSpec): List<CardChoice> =
        when (spec) {
            is CardEffectSpec.CommodityChoices -> spec.choices.map(CardChoice::CommodityChoice)
            is CardEffectSpec.Leader -> listOf(CardChoice.None)
            is CardEffectSpec.Event ->
                when (spec.event) {
                    EventId.ALCHEMISTS_GOLD,
                    EventId.CIVIL_WAR,
                    EventId.WAR ->
                        listOf(CardChoice.None) +
                            context.activePlayers.keys.map(CardChoice::PlayerChoice)
                    EventId.PIRATES_VIKINGS,
                    EventId.REBELLION ->
                        listOf(CardChoice.None) +
                            context.markers.keys.map(CardChoice::ProvinceChoice)
                    EventId.THE_CRUSADES ->
                        context.provinces.values
                            .filter { province ->
                                province.area == GeographicalArea.VI && !province.isSatellite
                            }
                            .map { province -> CardChoice.ProvinceChoice(province.id) }
                    EventId.PAPAL_DECREE ->
                        listOf(
                            CardChoice.CategoryChoice(null),
                            CardChoice.CategoryChoice(AdvanceCategory.SCIENCE),
                            CardChoice.CategoryChoice(AdvanceCategory.RELIGION),
                            CardChoice.CategoryChoice(AdvanceCategory.EXPLORATION),
                        )
                    else -> listOf(CardChoice.None)
                }
        }

    private fun currentRound(state: GameState): WarRound =
        WarRound(
            state.currentTurn(),
            state.stage == Stage.FINAL_CARD_PLAY,
            requireNotNull(state.flow.actor) { "A War roll requires a card-round owner." },
        )

    private fun GameState.gainMisery(player: PlayerId, spaces: Int): GameState =
        replacePlayer(
            playerState(player).let { owner ->
                owner.copy(misery = MiseryRules.shift(owner.misery, spaces))
            }
        )

    private fun halfUp(value: Int): Int = value / 2 + value % 2
}
