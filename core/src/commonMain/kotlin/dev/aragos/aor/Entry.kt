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

        override fun applyTo(state: GameState): GameState {
            checkRule(state.stage == Stage.SHUFFLE, ViolationCode.WRONG_PHASE)
            val expectedCards = CardData.epochOne.map(CardDefinition::id)
            checkRule(cards.sorted() == expectedCards.sorted(), ViolationCode.INVALID_OUTCOME)
            return state.copy(stage = Stage.DEAL, deck = cards.toList())
        }

        companion object {
            fun parse(arguments: List<String>): Shuffle {
                checkRule(arguments.size == CardData.epochOne.size, ViolationCode.INVALID_COMMAND)
                return Shuffle(cards = arguments)
            }
        }
    }

    data class Deal(val player: PlayerId, val reference: String, val card: String) : Entry {
        override fun toText(): String = "!deal $player $reference $card"

        override fun applyTo(state: GameState): GameState {
            val need = (state.stage.prompt(state) as? Prompt.System)?.need as? SystemNeed.Deal
            checkRule(
                need != null &&
                    player == need.player &&
                    reference == need.reference &&
                    card == state.deck.firstOrNull(),
                ViolationCode.INVALID_OUTCOME,
            )
            val handCard =
                HandCard(
                    reference = reference,
                    definition = CardData.epochOne.first { definition -> definition.id == card },
                )
            val dealt =
                state.updatePlayer(player) { playerState ->
                    playerState.copy(hand = playerState.hand + handCard)
                }
            return dealt.copy(
                deck = state.deck.drop(1),
                stage =
                    if (dealt.players.values.all { playerState -> playerState.hand.isNotEmpty() }) {
                        Stage.BIDDING
                    } else {
                        Stage.DEAL
                    },
            )
        }

        companion object {
            fun parse(arguments: List<String>): Deal {
                checkRule(arguments.size == 3, ViolationCode.INVALID_COMMAND)
                return Deal(
                    player = parsePlayerId(arguments[0]),
                    reference = arguments[1],
                    card = arguments[2],
                )
            }
        }
    }

    /** Results for exactly the players in the highest unresolved capital-choice tie. */
    data class CapitalRoll(val rolls: Map<PlayerId, DieRoll>) : Entry {
        override fun toText(): String {
            val results =
                rolls.entries
                    .sortedBy { entry -> entry.key.value }
                    .joinToString(" ") { (player, roll) -> "$player=$roll" }
            return "!roll capital-tie $results"
        }

        override fun applyTo(state: GameState): GameState = state.resolveCapitalTie(rolls)

        companion object {
            fun parse(arguments: List<String>): CapitalRoll {
                checkRule(
                    arguments.size >= 3 && arguments[0] == "capital-tie",
                    ViolationCode.INVALID_COMMAND,
                )
                val results =
                    arguments.drop(1).map { result ->
                        val pair = result.split("=")
                        checkRule(pair.size == 2, ViolationCode.INVALID_COMMAND)
                        parsePlayerId(pair[0]) to DieRoll.parse(pair[1])
                    }
                checkRule(
                    results.map(Pair<PlayerId, DieRoll>::first).distinct().size == results.size,
                    ViolationCode.INVALID_COMMAND,
                )
                return CapitalRoll(rolls = results.toMap())
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
