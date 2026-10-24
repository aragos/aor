package dev.aragos.aor

/** An accepted action or authority outcome, with its canonical replay text and state transition. */
sealed interface Entry {
    fun toText(): String

    fun applyTo(state: GameState): GameState

    data class Action(val actor: PlayerId, val command: Command) : Entry {
        override fun toText(): String = "$actor: ${command.toText()}"

        override fun applyTo(state: GameState): GameState {
            checkRule(actor in state.players, ViolationCode.UNKNOWN_PLAYER)
            return command.applyTo(state = state, actor = actor)
        }

        companion object {
            fun parse(text: String): Action {
                val parts = text.split(": ", limit = 2)
                checkRule(parts.size == 2, ViolationCode.INVALID_COMMAND)
                return Action(
                    actor = parsePlayerId(parts[0]),
                    command = TextCodec.parseCommand(parts[1]),
                )
            }
        }
    }

    data class Shuffle(val cards: List<String>) : Entry {
        override fun toText(): String = "!shuffle ${cards.joinToString(" ")}"

        override fun applyTo(state: GameState): GameState = state.applyShuffle(cards)

        companion object {
            fun parse(arguments: List<String>): Shuffle {
                checkRule(arguments.isNotEmpty(), ViolationCode.INVALID_COMMAND)
                return Shuffle(cards = arguments)
            }
        }
    }
}

private fun parsePlayerId(text: String): PlayerId =
    try {
        PlayerId(text)
    } catch (failure: IllegalArgumentException) {
        throw RuleViolation(ViolationCode.INVALID_COMMAND)
    }
