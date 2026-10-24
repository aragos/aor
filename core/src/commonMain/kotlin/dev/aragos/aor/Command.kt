package dev.aragos.aor

/** A player action with canonical text and command-specific syntax and rule validation. */
sealed interface Command {
    fun toText(): String

    fun applyTo(state: GameState, actor: PlayerId): GameState

    /** The minimum full-game scope supports one epoch; later decks are not implemented. */
    data class SetEpochs(val epochs: Int) : Command {
        override fun toText(): String = "set-option epochs $epochs"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
            checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
            checkRule(epochs == 1, ViolationCode.INVALID_EPOCHS)
            return state.copy(epochs = epochs)
        }

        companion object {
            fun parse(arguments: List<String>): SetEpochs {
                arguments.requireSize(2)
                checkRule(arguments[0] == "epochs", ViolationCode.UNSUPPORTED_OPTION)
                return SetEpochs(epochs = arguments[1].commandNumber())
            }
        }
    }

    data class SetRebuiltMarkerBonus(val policy: RebuiltMarkerBonus) : Command {
        override fun toText(): String = "set-option rebuilt-marker-bonus ${policy.commandValue}"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
            checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
            return state.copy(rebuiltMarkerBonus = policy)
        }

        companion object {
            fun parse(arguments: List<String>): SetRebuiltMarkerBonus {
                arguments.requireSize(2)
                checkRule(arguments[0] == "rebuilt-marker-bonus", ViolationCode.UNSUPPORTED_OPTION)
                return SetRebuiltMarkerBonus(policy = RebuiltMarkerBonus.parse(arguments[1]))
            }
        }
    }

    data object Start : Command {
        override fun toText(): String = "start"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
            checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
            checkRule(state.epochs == 1, ViolationCode.INVALID_EPOCHS)
            return state.copy(stage = Stage.SHUFFLE)
        }

        fun parse(arguments: List<String>): Start {
            arguments.requireSize(0)
            return this
        }
    }

    data class BidCapital(val amount: Int) : Command {
        override fun toText(): String = "bid-capital $amount"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.BIDDING, ViolationCode.WRONG_PHASE)
            checkRule(state.playerState(actor).capitalBid == null, ViolationCode.WRONG_PLAYER)
            checkRule(amount in 0..40, ViolationCode.INVALID_BID)
            val submitted = state.updatePlayer(actor) { player -> player.copy(capitalBid = amount) }
            if (submitted.players.values.any { player -> player.capitalBid == null }) {
                return submitted
            }
            val bids =
                submitted.playerIds.associateWith { playerId ->
                    requireNotNull(submitted.playerState(playerId).capitalBid) {
                        "Every player must have a capital bid before choosing an order."
                    }
                }
            val paid =
                submitted.copy(
                    players =
                        submitted.players.mapValues { (playerId, player) ->
                            player.copy(
                                cash =
                                    player.cash -
                                        requireNotNull(bids[playerId]) {
                                            "Every player must have a recorded bid at reveal."
                                        }
                            )
                        }
                )
            return paid.beginCapitalSelection(CapitalSelection.fromBids(bids))
        }

        companion object {
            fun parse(arguments: List<String>): BidCapital {
                arguments.requireSize(1)
                return BidCapital(amount = arguments.single().commandNumber())
            }
        }
    }

    data class ChooseCapital(val capital: String) : Command {
        override fun toText(): String = "choose-capital $capital"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CAPITAL_CHOICE, ViolationCode.WRONG_PHASE)
            val choosingPlayer =
                state.capitalOrder.first { playerId ->
                    state.playerState(playerId).capital == null
                }
            checkRule(actor == choosingPlayer, ViolationCode.WRONG_PLAYER)
            checkRule(
                capital in state.availableCapitals,
                ViolationCode.UNAVAILABLE_CAPITAL,
                entities = listOf(capital),
            )
            val chosen =
                state.updatePlayer(actor) { player ->
                    player.copy(capital = capital)
                }
            val home = ProvinceData.definitions.single { province -> province.capital == capital }
            val placed = chosen.copy(board = chosen.board.placeMarker(home.id, actor).board)
            return if (chosen.players.values.all { player -> player.capital != null }) {
                placed.copy(stage = Stage.TOKEN_PURCHASE, turnNumber = 1)
            } else {
                placed
            }
        }

        companion object {
            fun parse(arguments: List<String>): ChooseCapital {
                arguments.requireSize(1)
                return ChooseCapital(capital = arguments.single())
            }
        }
    }

    /** A signed purchase, affordable in full even when available token stock is lower. */
    data class BuyTokens(val amount: Int) : Command {
        override fun toText(): String = "buy-tokens $amount"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.TOKEN_PURCHASE, ViolationCode.WRONG_PHASE)
            val player = state.playerState(actor)
            checkRule(
                player.tokenPurchase == null && player.misery != MiseryStep.CHAOS,
                ViolationCode.WRONG_PLAYER,
            )
            TokenPurchase.affordable(amount = amount, cash = player.cash)
            val submitted =
                state.updatePlayer(actor) { buying -> buying.copy(tokenPurchase = amount) }
            if (
                submitted.players.values.any { buying ->
                    buying.tokenPurchase == null && buying.misery != MiseryStep.CHAOS
                }
            ) {
                return submitted
            }
            val purchases =
                submitted.playerIds.associateWith { playerId ->
                    requireNotNull(submitted.playerState(playerId).tokenPurchase) {
                        "Every player must have a token purchase at reveal."
                    }
                }
            val selection = TurnOrderSelection.fromPurchases(purchases, state.capitalOrder)
            return submitted.copy(
                players =
                    submitted.players.mapValues { (_, buying) ->
                        val purchase =
                            TokenPurchase.affordable(
                                amount =
                                    requireNotNull(buying.tokenPurchase) {
                                        "Every player must have a token purchase at reveal."
                                    },
                                cash = buying.cash,
                            )
                        purchase.applyTo(buying).let { paid -> paid.copy(writtenCash = paid.cash) }
                    },
                turnOrderSelection = selection,
                stage = if (selection.isResolved) Stage.DRAW_CARD else Stage.TURN_ORDER_TIE,
            )
        }

        companion object {
            fun parse(arguments: List<String>): BuyTokens {
                arguments.requireSize(1)
                return BuyTokens(amount = arguments.single().commandNumber())
            }
        }
    }

    /** One-based insertion position within the current unresolved equal-purchase group. */
    data class ChooseTurnPosition(val slot: Int) : Command {
        override fun toText(): String = "choose-turn-position $slot"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.TURN_ORDER_TIE, ViolationCode.WRONG_PHASE)
            val selection = state.turnOrderSelection.choosePosition(actor = actor, slot = slot)
            return state.copy(
                turnOrderSelection = selection,
                stage = if (selection.isResolved) Stage.DRAW_CARD else Stage.TURN_ORDER_TIE,
            )
        }

        companion object {
            fun parse(arguments: List<String>): ChooseTurnPosition {
                arguments.requireSize(1)
                return ChooseTurnPosition(slot = arguments.single().commandNumber())
            }
        }
    }
}

private fun List<String>.requireSize(expected: Int) {
    checkRule(size == expected, ViolationCode.INVALID_COMMAND)
}

private fun String.commandNumber(): Int =
    toIntOrNull() ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)
