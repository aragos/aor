package dev.aragos.aor

/** A player action with canonical text and command-specific syntax and rule validation. */
sealed interface Command {
    fun toText(): String

    fun applyTo(state: GameState, actor: PlayerId): GameState

    data class SetEpochs(val epochs: Int) : Command {
        override fun toText(): String = "set-option epochs $epochs"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
            checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
            checkRule(epochs in 1..3, ViolationCode.INVALID_EPOCHS)
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

    data object Start : Command {
        override fun toText(): String = "start"

        override fun applyTo(state: GameState, actor: PlayerId): GameState {
            checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
            checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
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
                    player.copy(capital = capital, boardMarkers = 1)
                }
            return if (chosen.players.values.all { player -> player.capital != null }) {
                chosen.copy(stage = Stage.SETUP_COMPLETE)
            } else {
                chosen
            }
        }

        companion object {
            fun parse(arguments: List<String>): ChooseCapital {
                arguments.requireSize(1)
                return ChooseCapital(capital = arguments.single())
            }
        }
    }
}

private fun List<String>.requireSize(expected: Int) {
    checkRule(size == expected, ViolationCode.INVALID_COMMAND)
}

private fun String.commandNumber(): Int =
    toIntOrNull() ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)
