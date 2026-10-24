package dev.aragos.aor

/** Repeatable phase progression. Automatic steps stop at a player choice or recorded outcome. */
internal object TurnEngine {
    fun prompt(state: GameState): Prompt? {
        val decision = state.flow.decisions.lastOrNull()
        return when (decision) {
            is DrawDecision ->
                Prompt.System(SystemNeed.Draw(decision.owner, state.nextHandReference()))
            is TurnDecision.Roll ->
                Prompt.System(SystemNeed.GameRoll(decision.kind, decision.diceCount))
            is TurnDecision.RandomCard ->
                Prompt.System(SystemNeed.Trade(decision.owner, decision.recipient))
            null -> phasePrompt(state)
            else -> decision.player?.let { actor -> Prompt.SinglePlayer(actor, decision.choice) }
        }
    }

    private fun phasePrompt(state: GameState): Prompt? {
        if (!state.stage.isTurnPhase || state.stage == Stage.FINISHED) return null
        val actor = state.flow.actor ?: return null
        val choice =
            when (state.stage) {
                Stage.DRAW_CARD -> "cancel-adjustment"
                Stage.BUY_CARD -> "buy-card"
                Stage.PLAY_CARDS,
                Stage.FINAL_CARD_PLAY -> "play-cards"
                Stage.PURCHASE -> "purchase-${state.flow.purchaseStep.name.lowercase()}"
                Stage.EXPANSION -> "expand"
                else -> "waiting"
            }
        return Prompt.SinglePlayer(actor, choice)
    }

    fun choices(state: GameState, actor: PlayerId): LegalChoices {
        val decision = state.flow.decisions.lastOrNull()
        if (decision != null) {
            return LegalChoices(
                if (decision.player == actor) decision.choices(state) else emptyList()
            )
        }
        if (state.flow.actor != actor || state.stage == Stage.FINISHED) return LegalChoices()
        val commands =
            when (state.stage) {
                Stage.DRAW_CARD -> adjustmentChoices(state, actor)
                Stage.BUY_CARD -> buyCardChoices(state, actor)
                Stage.PLAY_CARDS -> listOf("pass") + CardGame.playChoices(state, actor)
                Stage.FINAL_CARD_PLAY -> finalCardChoices(state, actor)
                Stage.PURCHASE -> purchaseChoices(state, actor)
                Stage.EXPANSION -> BoardGame.choices(state, actor)
                else -> emptyList()
            }
        return LegalChoices(commands)
    }

    private fun adjustmentChoices(state: GameState, actor: PlayerId): List<String> = buildList {
        add("pass")
        if (actor != state.turnOrder.first()) return@buildList
        val player = state.playerState(actor)
        for ((commodity, markers) in state.adjustments) {
            val cost =
                CommodityData.definitions
                    .single { definition -> definition.id == commodity }
                    .baseValue
            if (cost > player.cash) continue
            markers.distinct().forEach { adjustment ->
                add(
                    "cancel-adjustment ${commodity.name.lowercase()} ${adjustment.name.lowercase()}"
                )
            }
        }
    }

    private fun buyCardChoices(state: GameState, actor: PlayerId): List<String> = buildList {
        add("pass")
        if (actor in state.flow.discardedCards) return@buildList
        val player = state.playerState(actor)
        if (
            player.advances.owns(AdvanceId.URBAN_ASCENDANCY) &&
                actor !in state.flow.boughtCards &&
                player.cash >= 10 &&
                state.deck.isNotEmpty()
        ) {
            add("buy-card")
        }
        if (player.advances.owns(AdvanceId.MASTER_ART)) {
            addAll(player.hand.map { card -> "discard-card ${card.reference}" })
        }
    }

    private fun finalCardChoices(state: GameState, actor: PlayerId): List<String> = buildList {
        addAll(CardGame.playChoices(state, actor))
        if (
            state.playerState(actor).hand.all { card ->
                CardRules.isBurden(state.cardContext(), card.definition.id)
            }
        ) {
            add("pass")
        }
    }

    private fun purchaseChoices(state: GameState, actor: PlayerId): List<String> {
        val player = state.playerState(actor)
        return when (state.flow.purchaseStep) {
            PurchaseStep.SHIPPING ->
                buildList {
                    add("done")
                    if (
                        player.cash >= 10 &&
                            player.lastShipUpgradeTurn != state.currentTurn() &&
                            ShippingPurchases.nextUpgrade(player.ships) != null
                    ) {
                        add("upgrade-ships")
                    }
                }
            PurchaseStep.ADVANCES -> advanceChoices(state, actor)
            PurchaseStep.STABILIZATION ->
                buildList {
                    add("stabilize misery")
                    if (PlayerEconomy.stabilizationCost(player) <= player.cash.toLong()) {
                        add("stabilize cash")
                    }
                }
            PurchaseStep.RELIEF -> reliefChoices(state, player)
        }
    }

    private fun advanceChoices(state: GameState, actor: PlayerId): List<String> {
        val player = state.playerState(actor)
        val context = state.purchaseContext(actor)
        val affordable =
            AdvanceId.entries.filter { advance ->
                try {
                    AdvancePurchases.quote(player, advance, context).price <= player.cash
                } catch (_: RuleViolation) {
                    false
                }
            }
        return listOf("done") +
            state.patronageChoices(actor) +
            affordable.map { advance -> "buy-advance ${advance.letter}" }
    }

    private fun reliefChoices(state: GameState, player: PlayerState): List<String> {
        val affordable =
            (1..player.advances.tierCount).filter { spaces ->
                try {
                    PlayerEconomy.reliefQuote(player, spaces, state.currentTurn()).cashCost <=
                        player.cash
                } catch (_: RuleViolation) {
                    false
                }
            }
        return listOf("done") + affordable.map { spaces -> "relieve-misery $spaces" }
    }

    fun normalise(initial: GameState): GameState {
        var state = initial
        if (
            state.turnNumber != null &&
                state.players.values.all { player -> player.misery == MiseryStep.CHAOS }
        ) {
            return state.copy(
                stage = Stage.FINISHED,
                activeWar = null,
                flow = state.flow.copy(actor = null, decisions = emptyList()),
            )
        }
        if (
            state.stage == Stage.TOKEN_PURCHASE &&
                !state.flow.phaseStarted &&
                state.turnOrder.isNotEmpty()
        ) {
            state = beginPhase(state)
        }
        repeat(1000) {
            if (
                !state.stage.isTurnPhase ||
                    state.stage == Stage.FINISHED ||
                    state.flow.decisions.isNotEmpty()
            ) {
                return state
            }
            if (!state.flow.phaseStarted) {
                state = beginPhase(state)
                if (state.flow.decisions.isNotEmpty()) return state
            }
            if (!state.flow.phaseBookkeepingApplied) {
                state = applyPhaseBookkeeping(state)
            }
            val actor = state.flow.actor
            if (state.stage == Stage.INCOME) {
                if (state.flow.shortageRolls < 2) return state.pushDecision(ShortageDecision)
                state = finishIncome(state)
                return@repeat
            }
            if (actor == null) {
                state = nextPhase(state)
                return@repeat
            }
            if (state.playerState(actor).misery == MiseryStep.CHAOS) {
                if (state.stage == Stage.DRAW_CARD && state.flow.cursor == 0) {
                    state = state.copy(flow = state.flow.copy(cancelComplete = true))
                }
                state = finishActor(state)
                return@repeat
            }
            when (state.stage) {
                Stage.DRAW_CARD -> {
                    if (!state.flow.cancelComplete) {
                        if (state.adjustments.values.any(List<CommodityAdjustment>::isNotEmpty)) {
                            return state
                        }
                        state = state.copy(flow = state.flow.copy(cancelComplete = true))
                    }
                    if (actor !in state.flow.drawn) {
                        state =
                            state
                                .copy(flow = state.flow.copy(drawn = state.flow.drawn + actor))
                                .queueDraw(actor)
                        if (state.flow.decisions.isNotEmpty()) return state
                    } else {
                        state = finishActor(state)
                    }
                }
                Stage.BUY_CARD -> {
                    val player = state.playerState(actor)
                    val canBuy =
                        player.advances.owns(AdvanceId.URBAN_ASCENDANCY) &&
                            actor !in state.flow.boughtCards &&
                            player.cash >= 10 &&
                            state.deck.isNotEmpty()
                    val canDiscard =
                        player.advances.owns(AdvanceId.MASTER_ART) &&
                            actor !in state.flow.discardedCards &&
                            player.hand.isNotEmpty()
                    if (canBuy || canDiscard) return state
                    state = finishActor(state)
                }
                Stage.PLAY_CARDS,
                Stage.FINAL_CARD_PLAY -> {
                    state = CardGame.prepareRound(state)
                    if (state.flow.decisions.isNotEmpty()) return state
                    if (state.playerState(actor).hand.isNotEmpty()) return state
                    state = finishActor(state)
                }
                else -> return state
            }
        }
        error("Automatic phase progression must reach a decision in fewer than 1000 transitions.")
    }

    private fun beginPhase(initial: GameState): GameState {
        val order =
            if (initial.stage == Stage.EXPANSION) {
                initial.effects.civilWarVictims.fold(initial.turnOrder) { current, victim ->
                    current.filterNot { player -> player == victim } + victim
                }
            } else {
                initial.turnOrder
            }
        val ordered =
            initial.copy(turnOrderSelection = initial.turnOrderSelection.copy(order = order))
        val state =
            ordered.copy(
                flow =
                    ordered.flow.copy(
                        phaseStarted = true,
                        phaseBookkeepingApplied = false,
                        actor = order.firstOrNull(),
                        cursor = 0,
                        purchaseStep = PurchaseStep.SHIPPING,
                    )
            )
        val candidates =
            state.turnOrder.filter { id ->
                state.playerState(id).misery != MiseryStep.CHAOS &&
                    state.playerState(id).advances.owns(AdvanceId.RENAISSANCE) &&
                    id !in state.flow.renaissanceUsed
            }
        // Reversed stack gives the earliest player first opportunity.
        return state.copy(
            flow = state.flow.copy(decisions = candidates.reversed().map(::RenaissanceDecision))
        )
    }

    private fun applyPhaseBookkeeping(initial: GameState): GameState {
        val state = initial.copy(flow = initial.flow.copy(phaseBookkeepingApplied = true))
        return when (state.stage) {
            Stage.INCOME -> collectIncome(state)
            Stage.EXPANSION -> state.applyHolyIndulgence()
            else -> state
        }
    }

    private fun collectIncome(state: GameState): GameState =
        state.copy(
            board = state.board.flipWhitePieces(),
            players =
                state.players.mapValues { (id, player) ->
                    if (player.misery == MiseryStep.CHAOS) {
                        player
                    } else {
                        PlayerEconomy.collectIncome(
                                player,
                                state.playerIds.size,
                                state.board.markerCount(id),
                            )
                            .player
                    }
                },
            adjustments = emptyMap(),
        )

    fun finishActor(state: GameState): GameState {
        checkRule(state.flow.decisions.isEmpty(), ViolationCode.WRONG_PHASE)
        val cursor = state.flow.cursor + 1
        return state.copy(
            flow =
                state.flow.copy(
                    cursor = cursor,
                    actor = state.turnOrder.getOrNull(cursor),
                    purchaseStep = PurchaseStep.SHIPPING,
                )
        )
    }

    private fun nextPhase(state: GameState): GameState =
        when (state.stage) {
            Stage.DRAW_CARD -> open(state, Stage.BUY_CARD)
            Stage.BUY_CARD -> open(state, Stage.PLAY_CARDS)
            Stage.PLAY_CARDS -> open(state, Stage.PURCHASE)
            Stage.PURCHASE -> open(CardGame.endPurchase(state), Stage.EXPANSION)
            Stage.EXPANSION -> {
                val winner = expansionBonusWinner(state)
                val next = open(state, Stage.INCOME)
                if (winner == null) next else next.queueDraw(winner)
            }
            Stage.FINAL_CARD_PLAY ->
                state.copy(
                    stage = Stage.FINISHED,
                    activeWar = null,
                    flow = state.flow.copy(actor = null),
                )
            else -> state
        }

    private fun expansionBonusWinner(state: GameState): PlayerId? =
        state.turnOrder
            .filter { id -> state.playerState(id).misery != MiseryStep.CHAOS }
            .map { id ->
                val count =
                    state.board.pieces.count { (province, pieces) ->
                        pieces is ProvincePieces.Marker &&
                            pieces.owner == id &&
                            pieces.side == PieceSide.WHITE &&
                            state.rebuiltMarkerBonus.counts(
                                state.flow.markerOrigins[province]
                                    ?: MarkerPlacementOrigin.EXPANSION
                            )
                    }
                id to count
            }
            .maxByOrNull { (_, count) -> count }
            ?.takeIf { (_, count) -> count > 0 }
            ?.first

    private fun open(state: GameState, stage: Stage): GameState =
        state.copy(
            stage = stage,
            flow =
                state.flow.copy(
                    actor = null,
                    cursor = 0,
                    phaseStarted = false,
                    phaseBookkeepingApplied = false,
                ),
        )

    fun shortage(state: GameState, values: List<DieRoll>): GameState {
        checkRule(
            state.stage == Stage.INCOME &&
                state.flow.decisions.lastOrNull() == ShortageDecision &&
                values.size == 3,
            ViolationCode.INVALID_OUTCOME,
        )
        val commodities =
            CommodityData.definitions.filter { definition ->
                definition.diceTotal == values[0].value + values[1].value
            }
        val adjustment =
            if (values[2].value <= 3) CommodityAdjustment.SHORTAGE else CommodityAdjustment.SURPLUS
        val markers =
            commodities.fold(state.adjustments) { current, definition ->
                val previous = current[definition.id].orEmpty()
                val next =
                    if (previous.any { marker -> marker != adjustment }) {
                        previous.drop(1)
                    } else {
                        previous + adjustment
                    }
                current + (definition.id to next)
            }
        val resumed = state.popDecision()
        val rolled =
            resumed.copy(
                adjustments = markers,
                flow = resumed.flow.copy(shortageRolls = state.flow.shortageRolls + 1),
            )
        if (rolled.flow.shortageRolls < 2) return rolled
        return markers.entries.fold(rolled) { current, (commodity, adjustments) ->
            settleAdjustments(current, commodity, adjustments)
        }
    }

    private fun settleAdjustments(
        state: GameState,
        commodity: Commodity,
        adjustments: List<CommodityAdjustment>,
    ): GameState {
        val counts =
            state.playerIds.associateWith { id -> state.board.commodityCount(id, commodity) }
        val maximum = counts.values.maxOrNull() ?: 0
        val majority =
            counts.filterValues { count -> count == maximum && count > 0 }.keys.singleOrNull()
                ?: return state
        if (state.playerState(majority).misery == MiseryStep.CHAOS) return state
        return adjustments.fold(state) { current, adjustment ->
            when (adjustment) {
                CommodityAdjustment.SHORTAGE -> current.queueDraw(majority)
                CommodityAdjustment.SURPLUS ->
                    current.replacePlayer(
                        paySurplus(current.playerState(majority), counts.getValue(majority))
                    )
            }
        }
    }

    private fun paySurplus(player: PlayerState, cost: Int): PlayerState {
        if (player.cash >= cost) return player.copy(cash = player.cash - cost)
        return player.copy(
            cash = 0,
            misery = MiseryRules.payDebt(player.misery, (cost - player.cash).toLong()),
        )
    }

    private fun finishIncome(state: GameState): GameState =
        if (state.endingTriggered) {
            open(state, Stage.FINAL_CARD_PLAY)
        } else {
            state.copy(
                stage = Stage.TOKEN_PURCHASE,
                turnNumber = requireNotNull(state.turnNumber) { "Income requires a turn." } + 1,
                players =
                    state.players.mapValues { (_, player) ->
                        player.copy(
                            tokenPurchase = if (player.misery == MiseryStep.CHAOS) 0 else null
                        )
                    },
                effects = CardTurnEffects(),
                flow = TurnFlow(),
            )
        }
}
