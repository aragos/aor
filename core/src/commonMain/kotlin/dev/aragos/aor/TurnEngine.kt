package dev.aragos.aor

/** Repeatable phase progression. Automatic steps stop at a player choice or recorded outcome. */
internal object TurnEngine {
    fun prompt(state: GameState): Prompt? {
        val decision = state.flow.decisions.lastOrNull()
        if (decision != null) return decisionPrompt(decision)
        if (!state.stage.isTurnPhase || state.stage == Stage.FINISHED) return null
        val actor = state.flow.actor ?: return null
        return Prompt.SinglePlayer(actor, phaseChoice(state))
    }

    private fun decisionPrompt(decision: TurnDecision): Prompt? =
        when (decision) {
            is TurnDecision.RandomCard ->
                Prompt.System(SystemNeed.Trade(decision.owner, decision.recipient))
            else -> decision.player?.let { actor -> Prompt.SinglePlayer(actor, decision.choice) }
        }

    private fun phaseChoice(state: GameState): String =
        when (state.stage) {
            Stage.DRAW_CARD -> "cancel-adjustment"
            Stage.BUY_CARD -> "buy-card"
            Stage.PLAY_CARDS,
            Stage.FINAL_CARD_PLAY -> "play-cards"
            Stage.PURCHASE -> "purchase-${state.flow.purchaseStep.name.lowercase()}"
            Stage.EXPANSION -> "expand"
            else -> "waiting"
        }

    fun choices(state: GameState, actor: PlayerId): LegalChoices {
        val decision = state.flow.decisions.lastOrNull()
        if (decision != null) {
            val commands = if (decision.player == actor) decision.choices(state) else emptyList()
            return LegalChoices(commands)
        }
        if (state.flow.actor != actor || state.stage == Stage.FINISHED) return LegalChoices()
        return LegalChoices(phaseCommands(state, actor))
    }

    private fun phaseCommands(state: GameState, actor: PlayerId): List<String> =
        when (state.stage) {
            Stage.DRAW_CARD -> adjustmentCommands(state)
            Stage.BUY_CARD -> buyCardCommands(state, state.playerState(actor))
            Stage.PLAY_CARDS,
            Stage.FINAL_CARD_PLAY -> listOf("pass", "play-card")
            Stage.PURCHASE -> purchaseCommands(state, state.playerState(actor))
            Stage.EXPANSION ->
                listOf("pass", "place", "attack", "rebuild-marker", "buy-expansion-card")
            else -> emptyList()
        }

    private fun adjustmentCommands(state: GameState): List<String> =
        listOf("pass") +
            state.adjustments.flatMap { (commodity, markers) ->
                markers.distinct().map { adjustment ->
                    "cancel-adjustment ${commodity.name.lowercase()} ${adjustment.name.lowercase()}"
                }
            }

    private fun buyCardCommands(state: GameState, player: PlayerState): List<String> =
        listOfNotNull("pass", "buy-card".takeIf { canBuyCard(state, player) }) +
            discardCommands(state, player)

    private fun discardCommands(state: GameState, player: PlayerState): List<String> {
        if (!canDiscardCard(state, player)) return emptyList()
        return player.hand.map { card -> "discard-card ${card.reference}" }
    }

    private fun canBuyCard(state: GameState, player: PlayerState): Boolean =
        player.advances.owns(AdvanceId.URBAN_ASCENDANCY) &&
            player.player.id !in state.flow.boughtCards &&
            state.deck.isNotEmpty()

    private fun canDiscardCard(state: GameState, player: PlayerState): Boolean =
        player.advances.owns(AdvanceId.MASTER_ART) &&
            player.player.id !in state.flow.discardedCards &&
            player.hand.isNotEmpty()

    private fun purchaseCommands(state: GameState, player: PlayerState): List<String> =
        when (state.flow.purchaseStep) {
            PurchaseStep.SHIPPING -> listOf("pass", "upgrade-ships")
            PurchaseStep.ADVANCES ->
                listOf("pass") +
                    AdvanceId.entries
                        .filter { advance -> !player.advances.owns(advance) }
                        .map { advance -> "buy-advance ${advance.letter}" }
            PurchaseStep.STABILIZATION -> listOf("stabilize cash", "stabilize misery")
            PurchaseStep.RELIEF -> listOf("pass", "relieve-misery")
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
            )
                return state
            if (!state.flow.phaseStarted) {
                state = beginPhase(state)
                if (state.flow.decisions.isNotEmpty()) return state
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
                state = finishActor(state)
                return@repeat
            }
            when (state.stage) {
                Stage.DRAW_CARD -> {
                    if (!state.flow.cancelComplete) {
                        if (state.adjustments.values.any(List<CommodityAdjustment>::isNotEmpty))
                            return state
                        state = state.copy(flow = state.flow.copy(cancelComplete = true))
                    }
                    if (actor !in state.flow.drawn) {
                        state =
                            state
                                .copy(flow = state.flow.copy(drawn = state.flow.drawn + actor))
                                .queueDraw(actor)
                        if (state.flow.decisions.isNotEmpty()) return state
                    } else state = finishActor(state)
                }
                Stage.BUY_CARD -> {
                    val player = state.playerState(actor)
                    if (canBuyCard(state, player) || canDiscardCard(state, player)) return state
                    state = finishActor(state)
                }
                Stage.PLAY_CARDS,
                Stage.FINAL_CARD_PLAY -> {
                    if (state.playerState(actor).hand.isEmpty()) state = finishActor(state)
                    else return state
                }
                else -> return state
            }
        }
        error("Automatic phase progression must reach a decision in fewer than 1000 transitions.")
    }

    private fun beginPhase(initial: GameState): GameState {
        val state =
            initial.copy(
                flow =
                    initial.flow.copy(
                        phaseStarted = true,
                        actor = initial.turnOrder.firstOrNull(),
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
        val opened =
            state.copy(
                flow = state.flow.copy(decisions = candidates.reversed().map(::RenaissanceDecision))
            )
        return if (state.stage == Stage.INCOME) {
            opened.copy(
                board = opened.board.flipWhitePieces(),
                players =
                    opened.players.mapValues { (id, player) ->
                        if (player.misery == MiseryStep.CHAOS) player
                        else
                            PlayerEconomy.collectIncome(
                                    player,
                                    opened.playerIds.size,
                                    opened.board.markerCount(id),
                                )
                                .player
                    },
                adjustments = emptyMap(),
            )
        } else if (state.stage == Stage.EXPANSION) opened.applyHolyIndulgence() else opened
    }

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
            Stage.PURCHASE -> open(state, Stage.EXPANSION)
            Stage.EXPANSION -> {
                val winner =
                    state.turnOrder
                        .filter { id -> state.playerState(id).misery != MiseryStep.CHAOS }
                        .maxByOrNull { id ->
                            state.board.pieces.count { (province, pieces) ->
                                pieces is ProvincePieces.Marker &&
                                    pieces.owner == id &&
                                    pieces.side == PieceSide.WHITE &&
                                    state.rebuiltMarkerBonus.counts(
                                        state.flow.markerOrigins[province]
                                            ?: MarkerPlacementOrigin.EXPANSION
                                    )
                            }
                        }
                val eligible = winner?.takeIf { id ->
                    state.board.pieces.any { (province, pieces) ->
                        pieces is ProvincePieces.Marker &&
                            pieces.owner == id &&
                            pieces.side == PieceSide.WHITE &&
                            state.rebuiltMarkerBonus.counts(
                                state.flow.markerOrigins[province]
                                    ?: MarkerPlacementOrigin.EXPANSION
                            )
                    }
                }
                val next = open(state, Stage.INCOME)
                if (eligible == null) next else next.queueDraw(eligible)
            }
            Stage.FINAL_CARD_PLAY ->
                state.copy(
                    stage = Stage.FINISHED,
                    activeWar = null,
                    flow = state.flow.copy(actor = null),
                )
            else -> state
        }

    private fun open(state: GameState, stage: Stage): GameState =
        state.copy(
            stage = stage,
            flow = state.flow.copy(actor = null, cursor = 0, phaseStarted = false),
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
                    if (previous.any { marker -> marker != adjustment }) previous.drop(1)
                    else previous + adjustment
                current + (definition.id to next)
            }
        val rolled =
            state
                .popDecision()
                .copy(
                    adjustments = markers,
                    flow =
                        state.flow.copy(
                            decisions = state.flow.decisions.dropLast(1),
                            shortageRolls = state.flow.shortageRolls + 1,
                        ),
                )
        if (rolled.flow.shortageRolls < 2) return rolled
        return markers.entries.fold(rolled) { current, (commodity, adjustments) ->
            val counts =
                current.playerIds.associateWith { id ->
                    current.board.commodityCount(id, commodity)
                }
            val maximum = counts.values.maxOrNull() ?: 0
            val majority =
                counts.filterValues { count -> count == maximum && count > 0 }.keys.singleOrNull()
            if (majority == null || current.playerState(majority).misery == MiseryStep.CHAOS)
                current
            else
                adjustments.fold(current) { paying, marker ->
                    if (marker == CommodityAdjustment.SHORTAGE) paying.queueDraw(majority)
                    else
                        paying.replacePlayer(
                            paying.playerState(majority).let { player ->
                                val cost =
                                    requireNotNull(counts[majority]) {
                                        "A commodity majority must have an ownership count: $majority."
                                    }
                                if (player.cash >= cost) player.copy(cash = player.cash - cost)
                                else
                                    player.copy(
                                        cash = 0,
                                        misery =
                                            MiseryRules.payDebt(
                                                player.misery,
                                                (cost - player.cash).toLong(),
                                            ),
                                    )
                            }
                        )
                }
        }
    }

    private fun finishIncome(state: GameState): GameState =
        if (state.endingTriggered) open(state, Stage.FINAL_CARD_PLAY)
        else
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
