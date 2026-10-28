package dev.aragos.aor

/** One canonical configuration command. Invalid keys and values are rejected before recording. */
data class RuleOptionCommand(val key: String, val value: String) : Command {
    init {
        resolve(RuleOptions())
    }

    override fun toText(): String = "set-option $key $value"

    override fun applyTo(state: GameState, actor: PlayerId): GameState {
        checkRule(state.stage == Stage.CONFIGURATION, ViolationCode.WRONG_PHASE)
        checkRule(actor == state.header.creatorId, ViolationCode.WRONG_PLAYER)
        val resolved = resolve(state.rules)
        return state.copy(
            rules = resolved,
            board = state.board.copy(definition = resolved.map.definition),
        )
    }

    /** Resolve one supported option without changing the other choices. */
    fun resolve(rules: RuleOptions): RuleOptions =
        when (key) {
            "deck-modification" -> rules.copy(deckModification = enabled())
            "initial-card" ->
                rules.copy(
                    initialCard = option(InitialCardRule.entries, InitialCardRule::commandValue)
                )
            "immediate-master-art-discard" -> rules.copy(immediateMasterArtDiscard = enabled())
            "map" -> rules.copy(map = option(MapRule.entries, MapRule::commandValue))
            else -> throw RuleViolation(ViolationCode.UNSUPPORTED_OPTION, listOf(key, value))
        }

    private fun <T> option(choices: List<T>, commandValue: (T) -> String): T =
        choices.singleOrNull { choice -> commandValue(choice) == value }
            ?: throw RuleViolation(ViolationCode.UNSUPPORTED_OPTION, listOf(key, value))

    private fun enabled(): Boolean =
        when (value) {
            "enabled" -> true
            "disabled" -> false
            else -> throw RuleViolation(ViolationCode.UNSUPPORTED_OPTION, listOf(key, value))
        }

    companion object {
        fun parse(arguments: List<String>): RuleOptionCommand {
            checkRule(arguments.size == 2, ViolationCode.INVALID_COMMAND)
            return RuleOptionCommand(arguments[0], arguments[1])
        }
    }
}
