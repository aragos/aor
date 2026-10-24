package dev.aragos.aor

/** Commands for phase progression, optional draws and the ordered purchasing steps. */
sealed interface TurnCommand : Command {
    data object Pass : TurnCommand {
        override fun toText(): String = "pass"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            BoardGame.passDecision(state, actor)?.let { resumed ->
                return resumed
            }
            CardGame.passDecision(state, actor)?.let { resumed ->
                return resumed
            }
            val decision = state.flow.decisions.lastOrNull()
            if (decision is RenaissanceDecision) {
                checkRule(decision.player == actor, ViolationCode.WRONG_PLAYER)
                return state.popDecision()
            }
            checkRule(state.flow.decisions.isEmpty(), ViolationCode.WRONG_PHASE)
            checkRule(state.flow.actor == actor, ViolationCode.WRONG_PLAYER)
            return when (state.stage) {
                Stage.DRAW_CARD -> state.copy(flow = state.flow.copy(cancelComplete = true))
                Stage.BUY_CARD,
                Stage.PLAY_CARDS -> state.finishActor()
                Stage.PURCHASE -> passPurchase(state)
                Stage.EXPANSION -> BoardGame.endExpansion(state, actor)
                Stage.FINAL_CARD_PLAY -> {
                    checkRule(
                        state.playerState(actor).hand.all { card ->
                            CardRules.isBurden(state.cardContext(), card.definition.id)
                        },
                        ViolationCode.UNPLAYED_FINAL_CARDS,
                    )
                    state.finishActor()
                }
                else -> throw RuleViolation(ViolationCode.WRONG_PHASE)
            }
        }

        private fun passPurchase(state: GameState): GameState =
            when (state.flow.purchaseStep) {
                PurchaseStep.SHIPPING ->
                    state.copy(flow = state.flow.copy(purchaseStep = PurchaseStep.ADVANCES))
                PurchaseStep.ADVANCES ->
                    state.copy(flow = state.flow.copy(purchaseStep = PurchaseStep.STABILIZATION))
                PurchaseStep.STABILIZATION ->
                    throw RuleViolation(ViolationCode.STABILIZATION_REQUIRED)
                PurchaseStep.RELIEF -> state.finishActor()
            }
    }

    data class SwapOrder(val target: PlayerId) : TurnCommand {
        override fun toText(): String = "swap-order $target"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            val frame = state.flow.decisions.lastOrNull() as? RenaissanceDecision
            checkRule(frame?.player == actor, ViolationCode.WRONG_PLAYER)
            val index = state.turnOrder.indexOf(actor)
            val other = state.turnOrder.indexOf(target)
            checkRule(
                kotlin.math.abs(index - other) == 1 &&
                    !state.playerState(target).advances.owns(AdvanceId.RENAISSANCE),
                ViolationCode.INVALID_TURN_POSITION,
            )
            val order =
                state.turnOrder.map { id ->
                    if (id == actor) target else if (id == target) actor else id
                }
            val next = state.popDecision()
            return next.copy(
                turnOrderSelection = state.turnOrderSelection.copy(order = order),
                flow =
                    next.flow.copy(
                        actor = order.firstOrNull(),
                        renaissanceUsed = next.flow.renaissanceUsed + actor,
                    ),
            )
        }
    }

    data object BuyCard : TurnCommand {
        override fun toText(): String = "buy-card"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requireActor(actor, Stage.BUY_CARD)
            val player = state.playerState(actor)
            checkRule(
                player.advances.owns(AdvanceId.URBAN_ASCENDANCY) &&
                    actor !in state.flow.boughtCards &&
                    state.deck.isNotEmpty(),
                ViolationCode.INVALID_CARD_PURCHASE,
            )
            // TODO: Extract raw rule values, including this card cost, into named constants.
            checkRule(player.cash >= 10, ViolationCode.INSUFFICIENT_CASH)
            return state
                .replacePlayer(player.copy(cash = player.cash - 10))
                .copy(flow = state.flow.copy(boughtCards = state.flow.boughtCards + actor))
                .queueDraw(actor)
        }
    }

    data class DiscardCard(val reference: String) : TurnCommand {
        override fun toText(): String = "discard-card $reference"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requireActor(actor, Stage.BUY_CARD)
            val player = state.playerState(actor)
            checkRule(
                player.advances.owns(AdvanceId.MASTER_ART) &&
                    actor !in state.flow.discardedCards &&
                    player.hand.any { card -> card.reference == reference },
                ViolationCode.INVALID_CARD_DISCARD,
            )
            return state
                .replacePlayer(
                    player.copy(
                        hand = player.hand.filterNot { card -> card.reference == reference }
                    )
                )
                .copy(flow = state.flow.copy(discardedCards = state.flow.discardedCards + actor))
        }
    }

    data class CancelAdjustment(val commodity: Commodity, val adjustment: CommodityAdjustment) :
        TurnCommand {
        override fun toText(): String =
            "cancel-adjustment ${commodity.name.lowercase()} ${adjustment.name.lowercase()}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requireActor(actor, Stage.DRAW_CARD)
            checkRule(
                !state.flow.cancelComplete && actor == state.turnOrder.first(),
                ViolationCode.WRONG_PLAYER,
            )
            val markers = state.adjustments[commodity].orEmpty()
            checkRule(
                adjustment in markers,
                ViolationCode.INVALID_ADJUSTMENT,
            )
            val cost =
                CommodityData.definitions
                    .single { definition -> definition.id == commodity }
                    .baseValue
            val player = state.playerState(actor)
            checkRule(player.cash >= cost, ViolationCode.INSUFFICIENT_CASH)
            val affected =
                if (commodity == Commodity.GOLD || commodity == Commodity.IVORY)
                    setOf(Commodity.GOLD, Commodity.IVORY)
                else setOf(commodity)
            val updated =
                state.adjustments.mapValues { (id, previous) ->
                    if (id in affected)
                        previous.toMutableList().apply { remove(adjustment) }.toList()
                    else previous
                }
            return state
                .replacePlayer(player.copy(cash = player.cash - cost))
                .copy(adjustments = updated)
        }
    }

    data object UpgradeShips : TurnCommand {
        override fun toText(): String = "upgrade-ships"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requirePurchaseStep(actor, PurchaseStep.SHIPPING)
            val result = ShippingPurchases.upgrade(state.playerState(actor), state.currentTurn())
            return state
                .replacePlayer(result.player)
                .copy(flow = state.flow.copy(purchaseStep = PurchaseStep.ADVANCES))
        }
    }

    data class BuyAdvance(val advance: AdvanceId) : TurnCommand {
        override fun toText(): String = "buy-advance ${advance.letter}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requirePurchaseStep(actor, PurchaseStep.ADVANCES)
            val context = state.purchaseContext(actor)
            val result = AdvancePurchases.purchase(state.playerState(actor), advance, context)
            return state
                .replacePlayer(result.player)
                .copy(
                    endingTriggered =
                        state.endingTriggered || result.player.advances.owned.size == 26
                )
        }
    }

    data class Stabilize(val payment: StabilizationPayment) : TurnCommand {
        override fun toText(): String = "stabilize ${payment.name.lowercase()}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requirePurchaseStep(actor, PurchaseStep.STABILIZATION)
            return state
                .replacePlayer(PlayerEconomy.stabilize(state.playerState(actor), payment).player)
                .let { paid ->
                    paid.copy(flow = paid.flow.copy(purchaseStep = PurchaseStep.RELIEF))
                }
        }
    }

    data class RelieveMisery(val spaces: Int) : TurnCommand {
        override fun toText(): String = "relieve-misery $spaces"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            state.requirePurchaseStep(actor, PurchaseStep.RELIEF)
            return state.replacePlayer(
                PlayerEconomy.buyRelief(state.playerState(actor), spaces, state.currentTurn())
                    .player
            )
        }
    }

    companion object {
        fun parse(name: String, arguments: List<String>): Command =
            try {
                when (name) {
                    "pass" -> {
                        arguments.sizeIs(0)
                        Pass
                    }
                    "buy-card" -> {
                        arguments.sizeIs(0)
                        BuyCard
                    }
                    "upgrade-ships" -> {
                        arguments.sizeIs(0)
                        UpgradeShips
                    }
                    "swap-order" -> {
                        arguments.sizeIs(1)
                        SwapOrder(PlayerId(arguments.single()))
                    }
                    "discard-card" -> {
                        arguments.sizeIs(1)
                        DiscardCard(arguments.single())
                    }
                    "buy-advance" -> {
                        arguments.sizeIs(1)
                        BuyAdvance(
                            AdvanceId.entries.singleOrNull { advance ->
                                advance.letter.toString() == arguments.single().uppercase()
                            } ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)
                        )
                    }
                    "stabilize" -> {
                        arguments.sizeIs(1)
                        Stabilize(StabilizationPayment.valueOf(arguments.single().uppercase()))
                    }
                    "relieve-misery" -> {
                        arguments.sizeIs(1)
                        RelieveMisery(arguments.single().toInt())
                    }
                    "cancel-adjustment" -> {
                        arguments.sizeIs(2)
                        CancelAdjustment(
                            Commodity.valueOf(arguments[0].uppercase()),
                            CommodityAdjustment.valueOf(arguments[1].uppercase()),
                        )
                    }
                    else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                }
            } catch (failure: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }
    }
}

private fun List<String>.sizeIs(expected: Int) {
    checkRule(size == expected, ViolationCode.INVALID_COMMAND)
}

internal fun GameState.currentTurn(): Int =
    requireNotNull(turnNumber) { "This action requires a running turn." }

internal fun GameState.requirePurchaseStep(actor: PlayerId, step: PurchaseStep) {
    requireActor(actor, Stage.PURCHASE)
    checkRule(flow.purchaseStep == step, ViolationCode.WRONG_PHASE)
}
