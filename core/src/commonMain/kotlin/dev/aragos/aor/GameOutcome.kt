package dev.aragos.aor

/**
 * Authority outcomes contain observed values, never a random seed; replay requests no randomness.
 */
sealed interface GameOutcome : Entry {
    data class Draw(val player: PlayerId, val reference: String, val card: String) : GameOutcome {
        override fun toText(): String = "!draw $player $reference $card"

        override fun applyTo(state: GameState): GameState {
            val need = state.pendingDraw()
            checkRule(
                need != null &&
                    player == need.player &&
                    reference == need.reference &&
                    card == state.deck.firstOrNull(),
                ViolationCode.INVALID_OUTCOME,
            )
            val definition =
                CardData.epochOne.singleOrNull { definition -> definition.id == card }
                    ?: throw RuleViolation(ViolationCode.INVALID_OUTCOME)
            val delivered =
                state
                    .updatePlayer(player) { owner ->
                        owner.copy(hand = owner.hand + HandCard(reference, definition))
                    }
                    .copy(deck = state.deck.drop(1))
            if (state.stage == Stage.INITIAL_DRAW) {
                val allStartingCardsDrawn =
                    delivered.players.values.all { owner -> owner.hand.isNotEmpty() }
                return delivered.copy(
                    stage = if (allStartingCardsDrawn) Stage.BIDDING else Stage.INITIAL_DRAW
                )
            }
            val drawn = delivered.popDecision()
            val exhausted = delivered.deck.isEmpty()
            val decisions =
                if (exhausted) {
                    drawn.flow.decisions.filterNot { frame -> frame is DrawDecision }
                } else {
                    drawn.flow.decisions
                }
            return drawn.copy(
                endingTriggered = state.endingTriggered || exhausted,
                flow = drawn.flow.copy(decisions = decisions),
            )
        }
    }

    /** [players] attributes the corresponding [values]; empty for unassigned dice. */
    data class Roll(
        val kind: GameRollKind,
        val values: List<DieRoll>,
        val players: List<PlayerId> = emptyList(),
    ) : GameOutcome {
        override fun toText(): String {
            val results =
                if (players.isEmpty()) {
                    values.joinToString(" ")
                } else {
                    players
                        .zip(values)
                        .sortedBy { (player, _) -> player.value }
                        .joinToString(" ") { (player, roll) -> "$player=$roll" }
                }
            return "!roll ${kind.name.lowercase().replace('_', '-')} $results"
        }

        override fun applyTo(state: GameState): GameState {
            val decision = state.pendingRoll()
            checkRule(
                decision != null &&
                    decision.kind == kind &&
                    values.size == decision.diceCount &&
                    players.distinct().size == players.size &&
                    players.toSet() == decision.players.toSet(),
                ViolationCode.INVALID_OUTCOME,
            )
            val request = requireNotNull(decision) { "A validated roll requires its decision." }
            val ordered =
                if (players.isEmpty()) {
                    values
                } else {
                    request.players.map { player -> values[players.indexOf(player)] }
                }
            return request.resolve(state, ordered)
        }

        companion object {
            internal fun parse(arguments: List<String>): Roll {
                checkRule(arguments.size >= 2, ViolationCode.INVALID_COMMAND)
                val kind = GameRollKind.valueOf(arguments[0].uppercase().replace('-', '_'))
                val results = arguments.drop(1)
                if (results.none { result -> '=' in result }) {
                    return Roll(kind, results.map(DieRoll::parse))
                }
                val attributed = results.map { result ->
                    val pair = result.split('=')
                    checkRule(pair.size == 2, ViolationCode.INVALID_COMMAND)
                    PlayerId(pair[0]) to DieRoll.parse(pair[1])
                }
                val players = attributed.map(Pair<PlayerId, DieRoll>::first)
                checkRule(players.distinct().size == players.size, ViolationCode.INVALID_COMMAND)
                return Roll(kind, attributed.map(Pair<PlayerId, DieRoll>::second), players)
            }
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
                    "!roll" -> Roll.parse(arguments)
                    else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                }
            } catch (failure: IllegalArgumentException) {
                throw RuleViolation(ViolationCode.INVALID_COMMAND)
            }
    }
}
