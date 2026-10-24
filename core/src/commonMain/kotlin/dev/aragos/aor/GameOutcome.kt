package dev.aragos.aor

/**
 * Authority outcomes contain observed values, never a random seed; replay requests no randomness.
 */
sealed interface GameOutcome : Entry {
    data class Draw(val player: PlayerId, val reference: String, val card: String) : GameOutcome {
        override fun toText(): String = "!draw $player $reference $card"

        override fun applyTo(state: GameState): GameState {
            val decision = state.flow.decisions.lastOrNull() as? DrawDecision
            checkRule(
                decision?.owner == player &&
                    card == state.deck.firstOrNull() &&
                    reference == state.nextHandReference(),
                ViolationCode.INVALID_OUTCOME,
            )
            val definition =
                CardData.epochOne.singleOrNull { definition -> definition.id == card }
                    ?: throw RuleViolation(ViolationCode.INVALID_OUTCOME)
            val drawn =
                state.popDecision().updatePlayer(player) { owner ->
                    owner.copy(hand = owner.hand + HandCard(reference, definition))
                }
            val exhausted = state.deck.size == 1
            return drawn.copy(
                deck = state.deck.drop(1),
                endingTriggered = state.endingTriggered || exhausted,
                flow =
                    drawn.flow.copy(
                        decisions =
                            if (exhausted) {
                                drawn.flow.decisions.filterNot { frame -> frame is DrawDecision }
                            } else {
                                drawn.flow.decisions
                            }
                    ),
            )
        }
    }

    data class Roll(val kind: GameRollKind, val values: List<DieRoll>) : GameOutcome {
        override fun toText(): String = "!roll ${kind.name.lowercase()} ${values.joinToString(" ")}"

        override fun applyTo(state: GameState): GameState {
            val decision = state.flow.decisions.lastOrNull() as? TurnDecision.Roll
            checkRule(
                decision != null && decision.kind == kind && values.size == decision.diceCount,
                ViolationCode.INVALID_OUTCOME,
            )
            return requireNotNull(decision) { "A validated roll requires its decision." }
                .resolve(state, values)
        }
    }

    data class Trade(val owner: PlayerId, val recipient: PlayerId, val reference: String) :
        GameOutcome {
        override fun toText(): String = "!trade $owner $recipient $reference"

        override fun applyTo(state: GameState): GameState {
            val decision = state.flow.decisions.lastOrNull() as? TurnDecision.RandomCard
            checkRule(
                decision?.owner == owner &&
                    decision.recipient == recipient &&
                    state.playerState(owner).hand.any { card -> card.reference == reference },
                ViolationCode.INVALID_OUTCOME,
            )
            return requireNotNull(decision) { "A validated trade requires its decision." }
                .resolve(state, reference)
        }
    }

    companion object {
        fun parse(name: String, arguments: List<String>): GameOutcome =
            try {
                when (name) {
                    "!draw" -> {
                        checkRule(arguments.size == 3, ViolationCode.INVALID_COMMAND)
                        Draw(PlayerId(arguments[0]), arguments[1], arguments[2])
                    }
                    "!trade" -> {
                        checkRule(arguments.size == 3, ViolationCode.INVALID_COMMAND)
                        Trade(PlayerId(arguments[0]), PlayerId(arguments[1]), arguments[2])
                    }
                    "!roll" -> {
                        checkRule(arguments.size >= 2, ViolationCode.INVALID_COMMAND)
                        Roll(
                            GameRollKind.valueOf(arguments[0].uppercase()),
                            arguments.drop(1).map(DieRoll::parse),
                        )
                    }
                    else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                }
            } catch (failure: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }
    }
}
