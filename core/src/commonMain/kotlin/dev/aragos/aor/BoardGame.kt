package dev.aragos.aor

/** Competition and trade decisions preserve the current expansion activity. */
sealed interface BoardDecision : TurnDecision {
    data class CathedralChoice(
        override val player: PlayerId,
        val plan: PlacementPlan.Competition,
        val remaining: List<PlayerId>,
    ) : BoardDecision {
        override val choice: String
            get() = "cathedral"

        override fun choices(state: GameState): List<String> = listOf("cathedral", "pass")
    }

    /** Dice are recorded in white, black, green order. */
    data class CompetitionDice(val plan: PlacementPlan.Competition) :
        BoardDecision, TurnDecision.Roll {
        override val choice: String
            get() = "competition"

        override val kind: GameRollKind
            get() = GameRollKind.COMPETITION

        override val diceCount: Int
            get() = 3

        override fun resolve(state: GameState, values: List<DieRoll>): GameState =
            BoardGame.resolveCompetition(state, this, values)
    }

    data class WindmillOpportunity(
        override val player: PlayerId,
        val victim: PlayerId,
        val province: ProvinceId,
    ) : BoardDecision {
        override val choice: String
            get() = "windmill"

        override fun choices(state: GameState): List<String> = listOf("windmill", "pass")
    }

    data class WrittenRecordOpportunity(
        override val player: PlayerId,
        val victims: List<PlayerId>,
    ) : BoardDecision {
        override val choice: String = "written-record"

        override fun choices(state: GameState): List<String> =
            listOf("pass") +
                if (victims.size == 1) listOf("written-record")
                else victims.map { victim -> "written-record $victim" }
    }

    data class WindmillDice(val winner: PlayerId, val victim: PlayerId, val marketNumber: Int) :
        BoardDecision, TurnDecision.Roll {
        override val choice: String
            get() = "windmill"

        override val kind: GameRollKind
            get() = GameRollKind.WINDMILL

        override val diceCount: Int
            get() = 1

        override fun resolve(state: GameState, values: List<DieRoll>): GameState =
            BoardGame.resolveWindmill(state, this, values)
    }

    data class TradeOffer(override val player: PlayerId, val victim: PlayerId) : BoardDecision {
        override val choice: String
            get() = "offer-card"

        override fun choices(state: GameState): List<String> =
            state.playerState(player).hand.map { card -> "offer-card ${card.reference}" }
    }

    data class RandomTrade(
        override val owner: PlayerId,
        override val recipient: PlayerId,
        val offeredReference: String,
    ) : BoardDecision, TurnDecision.RandomCard {
        override val choice: String
            get() = "trade-card"

        override fun resolve(state: GameState, reference: String): GameState =
            BoardGame.resolveTrade(state, this, reference)
    }
}

/**
 * Runtime adapter around the pure board rules; the surrounding phase driver owns turn progression.
 */
object BoardGame {
    fun context(state: GameState): BoardRulesContext =
        BoardRulesContext(
            configuration = state.catalog,
            board = state.board,
            players =
                state.players.mapValues { entry ->
                    val player = entry.value
                    BoardPlayerProfile(
                        player = entry.key,
                        capital =
                            ProvinceId(
                                requireNotNull(player.capital) {
                                    "Board play requires chosen capitals."
                                }
                            ),
                        advances = player.advances.owned,
                        ships = player.ships,
                        chaos = player.misery == MiseryStep.CHAOS,
                    )
                },
            shipped = state.flow.shipped,
            militaryPlays =
                state.effects.military.map { play ->
                    MilitaryPlay(
                        play.player,
                        when (play.event) {
                            EventId.ARMOR -> MilitaryCard.ARMOR
                            EventId.STIRRUPS -> MilitaryCard.STIRRUPS
                            EventId.GUNPOWDER -> MilitaryCard.GUNPOWDER
                            EventId.LONG_BOW -> MilitaryCard.LONG_BOW
                            else -> error("Only military advantage cards can enter board play.")
                        },
                    )
                },
        )

    fun place(
        state: GameState,
        actor: PlayerId,
        province: ProvinceId,
        tokens: Int,
    ): GameState {
        state.requireActor(actor, Stage.EXPANSION)
        val context = context(state)
        val plan =
            BoardPlacementRules.evaluate(
                context,
                actor,
                province,
                tokens,
                state.playerState(actor).poolTokens,
            )
        val reserved =
            spendPool(state, actor, tokens, returnToStock = false)
                .copy(
                    flow =
                        state.flow.copy(
                            shipped = BoardPlacementRules.recordShipping(context, plan).shipped
                        )
                )
        return when (plan) {
            is PlacementPlan.Peaceful -> {
                val change = BoardPlacementRules.applyPeaceful(context, plan)
                recordMarkerOrigin(
                    reserved.withBoardChange(change.copy(tokensRequired = emptyMap())),
                    plan,
                    newAcquisition = false,
                )
            }
            is PlacementPlan.Competition ->
                openCathedral(
                    reserved,
                    plan,
                    cathedralOrder(reserved, plan),
                )
        }
    }

    fun attack(
        state: GameState,
        actor: PlayerId,
        province: ProvinceId,
    ): GameState {
        state.requireActor(actor, Stage.EXPANSION)
        val tokens = attackTokens(context(state), actor, province)
        return place(state, actor, province, tokens)
    }

    fun endExpansion(state: GameState, actor: PlayerId): GameState {
        state.requireActor(actor, Stage.EXPANSION)
        return spendPool(state, actor, state.playerState(actor).poolTokens, returnToStock = true)
            .finishActor()
    }

    fun buyExpansionCard(state: GameState, actor: PlayerId): GameState {
        state.requireActor(actor, Stage.EXPANSION)
        checkRule(canBuyExpansionCard(state, actor), ViolationCode.INVALID_CARD_PURCHASE)
        val price = EconomyTables.expansionCardCost(state.flow.expansionCardCount)
        val paid = spendPool(state, actor, price, returnToStock = true)
        return paid
            .copy(
                flow =
                    paid.flow.copy(
                        expansionCards = paid.flow.expansionCards + actor,
                        expansionCardCount = paid.flow.expansionCardCount + 1,
                    )
            )
            .queueDraw(actor)
    }

    /**
     * Only old coloured markers can be voluntarily reduced, preventing white expansion chaining.
     */
    fun reduceMarker(state: GameState, actor: PlayerId, province: ProvinceId): GameState {
        state.requireActor(actor, Stage.EXPANSION)
        checkRule(
            state.board.pieces[province] == ProvincePieces.Marker(actor, PieceSide.COLOURED),
            ViolationCode.INVALID_BOARD_MUTATION,
        )
        val owner = state.playerState(actor)
        checkRule(
            owner.stockTokens > 0 || owner.poolTokens > 0,
            ViolationCode.INVALID_BOARD_MUTATION,
        )
        val change = state.board.replaceWithToken(province, actor)
        val changed =
            if (owner.stockTokens > 0) {
                state.withBoardChange(change)
            } else {
                spendPool(state, actor, 1, returnToStock = false)
                    .withBoardChange(change.copy(tokensRequired = emptyMap()))
            }
        return changed.copy(
            flow =
                changed.flow.copy(
                    voluntaryReduced = changed.flow.voluntaryReduced + province,
                    markerOrigins = changed.flow.markerOrigins - province,
                )
        )
    }

    fun passDecision(state: GameState, actor: PlayerId): GameState? =
        when (val decision = state.flow.decisions.lastOrNull()) {
            is BoardDecision.CathedralChoice -> {
                checkRule(decision.player == actor, ViolationCode.WRONG_PLAYER)
                openCathedral(state.popDecision(), decision.plan, decision.remaining)
            }
            is BoardDecision.WrittenRecordOpportunity -> {
                checkRule(decision.player == actor, ViolationCode.WRONG_PLAYER)
                state.popDecision()
            }
            is BoardDecision.WindmillOpportunity -> {
                checkRule(decision.player == actor, ViolationCode.WRONG_PLAYER)
                state.popDecision()
            }
            else -> null
        }

    fun cathedral(state: GameState, actor: PlayerId): GameState {
        val decision = state.flow.decisions.lastOrNull() as? BoardDecision.CathedralChoice
        checkRule(decision?.player == actor, ViolationCode.WRONG_PLAYER)
        val frame =
            requireNotNull(decision) { "A validated Cathedral declaration requires a frame." }
        val resolution =
            CompetitionRules.resolveCathedral(
                context(state),
                frame.plan,
                actor,
                cathedralUsage(state),
            )
        return completeCompetition(state.popDecision(), frame.plan, resolution)
    }

    fun windmill(state: GameState, actor: PlayerId): GameState {
        val decision = state.flow.decisions.lastOrNull() as? BoardDecision.WindmillOpportunity
        checkRule(decision?.player == actor, ViolationCode.WRONG_PLAYER)
        val frame = requireNotNull(decision) { "A validated Wind/Watermill use requires a frame." }
        val number = state.catalog.map.province(frame.province).marketNumber
        val paid = spendPool(state.popDecision(), actor, number, returnToStock = true)
        return paid
            .copy(flow = paid.flow.copy(windmillUsed = paid.flow.windmillUsed + actor))
            .pushDecision(BoardDecision.WindmillDice(actor, frame.victim, number))
    }

    fun writtenRecord(state: GameState, actor: PlayerId, target: PlayerId?): GameState {
        val decision = state.flow.decisions.lastOrNull() as? BoardDecision.WrittenRecordOpportunity
        checkRule(decision?.player == actor, ViolationCode.WRONG_PLAYER)
        val frame =
            requireNotNull(decision) { "Written Record requires a pending trade opportunity." }
        val victim = target ?: frame.victims.singleOrNull()
        checkRule(victim in frame.victims, ViolationCode.INVALID_CARD_DISCARD)
        val selected =
            requireNotNull(victim) { "A Written Record exchange requires a defeated player." }
        checkRule(
            VariantInteractions.canWrittenRecordTrade(state, actor, selected),
            ViolationCode.INVALID_CARD_DISCARD,
        )
        val next = state.popDecision()
        return next
            .copy(flow = next.flow.copy(writtenRecordUsed = next.flow.writtenRecordUsed + actor))
            .pushDecision(BoardDecision.TradeOffer(actor, selected))
    }

    fun offerCard(state: GameState, actor: PlayerId, reference: String): GameState {
        val decision = state.flow.decisions.lastOrNull() as? BoardDecision.TradeOffer
        checkRule(decision?.player == actor, ViolationCode.WRONG_PLAYER)
        checkRule(
            state.playerState(actor).hand.any { card -> card.reference == reference },
            ViolationCode.INVALID_CARD_DISCARD,
        )
        val frame = requireNotNull(decision) { "A validated trade offer requires a frame." }
        return state
            .popDecision()
            .pushDecision(BoardDecision.RandomTrade(frame.victim, actor, reference))
    }

    /**
     * Participants entitled to the offered/random hand reference, for transport-history redaction.
     */
    fun tradeParticipants(state: GameState): Set<PlayerId> =
        when (val decision = state.flow.decisions.lastOrNull()) {
            is BoardDecision.TradeOffer -> setOf(decision.player, decision.victim)
            is BoardDecision.RandomTrade -> setOf(decision.owner, decision.recipient)
            else -> emptySet()
        }

    fun choices(state: GameState, actor: PlayerId): List<String> {
        if (
            state.stage != Stage.EXPANSION ||
                state.flow.actor != actor ||
                state.flow.decisions.isNotEmpty() ||
                state.playerState(actor).misery == MiseryStep.CHAOS
        ) {
            return emptyList()
        }
        val context = context(state)
        val player = state.playerState(actor)
        return buildList {
            add("pass")
            for (province in context.configuration.provinces) {
                addAll(placementChoices(context, actor, province.id, player.poolTokens))
                if (
                    state.board.pieces[province.id] ==
                        ProvincePieces.Marker(actor, PieceSide.COLOURED) &&
                        (player.stockTokens > 0 || player.poolTokens > 0)
                ) {
                    add("reduce-marker ${province.id}")
                }
            }
            if (canBuyExpansionCard(state, actor)) {
                add("buy-expansion-card")
            }
        }
    }

    internal fun resolveCompetition(
        state: GameState,
        decision: BoardDecision.CompetitionDice,
        values: List<DieRoll>,
    ): GameState {
        checkRule(
            state.flow.decisions.lastOrNull() == decision && values.size == 3,
            ViolationCode.INVALID_OUTCOME,
        )
        val resolution =
            CompetitionRules.resolve(
                context(state),
                decision.plan,
                CompetitionRoll(white = values[0], black = values[1], green = values[2]),
                requireNotNull(state.orderBoxes[decision.plan.player]) {
                    "A competing attacker must have an order box: ${decision.plan.player}."
                },
            )
        return completeCompetition(state.popDecision(), decision.plan, resolution)
    }

    internal fun resolveWindmill(
        state: GameState,
        decision: BoardDecision.WindmillDice,
        values: List<DieRoll>,
    ): GameState {
        checkRule(
            state.flow.decisions.lastOrNull() == decision && values.size == 1,
            ViolationCode.INVALID_OUTCOME,
        )
        val next = state.popDecision()
        return if (values.single().value <= decision.marketNumber) {
            next.pushDecision(BoardDecision.TradeOffer(decision.winner, decision.victim))
        } else {
            next
        }
    }

    internal fun resolveTrade(
        state: GameState,
        decision: BoardDecision.RandomTrade,
        reference: String,
    ): GameState {
        checkRule(state.flow.decisions.lastOrNull() == decision, ViolationCode.INVALID_OUTCOME)
        val winner = state.playerState(decision.recipient)
        val victim = state.playerState(decision.owner)
        val offered =
            winner.hand.singleOrNull { card -> card.reference == decision.offeredReference }
        val received = victim.hand.singleOrNull { card -> card.reference == reference }
        checkRule(offered != null && received != null, ViolationCode.INVALID_OUTCOME)
        val offer = requireNotNull(offered) { "A validated trade requires the offered card." }
        val receipt = requireNotNull(received) { "A validated trade requires the received card." }
        return state
            .popDecision()
            .updatePlayer(decision.recipient) { owner ->
                owner.copy(hand = owner.hand - offer + receipt)
            }
            .updatePlayer(decision.owner) { owner ->
                owner.copy(hand = owner.hand - receipt + offer)
            }
    }

    private fun completeCompetition(
        state: GameState,
        plan: PlacementPlan.Competition,
        resolution: CompetitionResolution,
    ): GameState {
        val changed = recordCathedralUse(state.withBoardChange(resolution.change), resolution)
        if (!resolution.attackerWon) return changed
        val acquired = recordMarkerOrigin(changed, plan, newAcquisition = true)
        val eligible =
            resolution.defeatedPlayers
                .filter { defeated ->
                    VariantInteractions.canWrittenRecordTrade(acquired, plan.player, defeated)
                }
                .sortedBy(PlayerId::value)
        val conquered =
            if (eligible.isNotEmpty()) {
                acquired.pushDecision(BoardDecision.WrittenRecordOpportunity(plan.player, eligible))
            } else acquired
        val victim = resolution.previousController ?: return conquered
        val winner = conquered.playerState(plan.player)
        val defeated = conquered.playerState(victim)
        if (
            conquered.rules.windWatermill == WindWatermillRule.TAKEOVER_TRADE &&
                winner.advances.owns(AdvanceId.WIND_WATERMILL) &&
                plan.player !in conquered.flow.windmillUsed &&
                winner.poolTokens >= state.catalog.map.province(plan.destination).marketNumber &&
                winner.hand.isNotEmpty() &&
                defeated.hand.isNotEmpty() &&
                defeated.misery != MiseryStep.CHAOS
        ) {
            return conquered.pushDecision(
                BoardDecision.WindmillOpportunity(plan.player, victim, plan.destination)
            )
        }
        return conquered
    }

    private fun recordCathedralUse(
        state: GameState,
        resolution: CompetitionResolution,
    ): GameState {
        val cathedralUser = resolution.cathedralUser ?: return state
        val uses = resolution.cathedralOpponents.map { opponent -> cathedralUser to opponent }
        return state.copy(flow = state.flow.copy(cathedralUses = state.flow.cathedralUses + uses))
    }

    private fun recordMarkerOrigin(
        state: GameState,
        plan: PlacementPlan,
        newAcquisition: Boolean,
    ): GameState {
        val marker = state.board.pieces[plan.destination] as? ProvincePieces.Marker ?: return state
        if (marker.owner != plan.player || marker.side != PieceSide.WHITE) return state
        val origin =
            when {
                newAcquisition -> MarkerPlacementOrigin.EXPANSION
                plan.destination in state.flow.voluntaryReduced ->
                    MarkerPlacementOrigin.VOLUNTARY_REBUILD
                plan.destination in state.flow.eventReduced -> MarkerPlacementOrigin.EVENT_REBUILD
                else -> MarkerPlacementOrigin.EXPANSION
            }
        return state.copy(
            flow =
                state.flow.copy(
                    markerOrigins = state.flow.markerOrigins + (plan.destination to origin)
                )
        )
    }

    private fun openCathedral(
        state: GameState,
        plan: PlacementPlan.Competition,
        candidates: List<PlayerId>,
    ): GameState =
        if (candidates.isEmpty()) {
            state.pushDecision(BoardDecision.CompetitionDice(plan))
        } else {
            state.pushDecision(
                BoardDecision.CathedralChoice(candidates.first(), plan, candidates.drop(1))
            )
        }

    private fun cathedralOrder(state: GameState, plan: PlacementPlan.Competition): List<PlayerId> {
        val eligible = CompetitionRules.cathedralUsers(context(state), plan, cathedralUsage(state))
        return (listOf(plan.player) + state.turnOrder.filter { player -> player != plan.player })
            .filter(eligible::contains)
    }

    private fun cathedralUsage(state: GameState): Map<PlayerId, Set<PlayerId>> =
        state.flow.cathedralUses.groupBy(Pair<PlayerId, PlayerId>::first).mapValues { entry ->
            entry.value.map(Pair<PlayerId, PlayerId>::second).toSet()
        }

    private fun spendPool(
        state: GameState,
        player: PlayerId,
        count: Int,
        returnToStock: Boolean,
    ): GameState {
        val owner = state.playerState(player)
        checkRule(
            count >= 0 && count <= owner.poolTokens,
            ViolationCode.INSUFFICIENT_EXPANSION_TOKENS,
        )
        return state.updatePlayer(player) { current ->
            current.copy(
                poolTokens = current.poolTokens - count,
                stockTokens = current.stockTokens + if (returnToStock) count else 0,
            )
        }
    }

    private fun canBuyExpansionCard(state: GameState, actor: PlayerId): Boolean =
        actor !in state.flow.expansionCards &&
            (state.deck.isNotEmpty() || state.currentEpoch != Epoch.THREE) &&
            state.playerState(actor).poolTokens >=
                EconomyTables.expansionCardCost(state.flow.expansionCardCount)

    private fun placementChoices(
        context: BoardRulesContext,
        actor: PlayerId,
        province: ProvinceId,
        poolTokens: Int,
    ): List<String> {
        return buildList {
            if (canPlace(context, actor, province, 1, poolTokens)) {
                add("place 1 $province")
            }
            if (canAttack(context, actor, province, poolTokens)) {
                add("attack $province")
            }
        }
    }

    internal fun attackTokens(
        context: BoardRulesContext,
        actor: PlayerId,
        province: ProvinceId,
    ): Int {
        val existing =
            (context.board.pieces[province] as? ProvincePieces.Tokens)
                ?.holdings
                .orEmpty()[actor]
                ?.total ?: 0
        return CompetitionRules.requirement(context, actor, province).requirement.tokens - existing
    }

    private fun canAttack(
        context: BoardRulesContext,
        actor: PlayerId,
        province: ProvinceId,
        poolTokens: Int,
    ): Boolean =
        try {
            canPlace(
                context,
                actor,
                province,
                attackTokens(context, actor, province),
                poolTokens,
            )
        } catch (failure: RuleViolation) {
            // Empty and friendly provinces have no competition action.
            false
        }

    private fun canPlace(
        context: BoardRulesContext,
        actor: PlayerId,
        province: ProvinceId,
        tokens: Int,
        pool: Int,
    ): Boolean =
        try {
            BoardPlacementRules.evaluate(context, actor, province, tokens, pool)
            true
        } catch (failure: RuleViolation) {
            false
        }
}
