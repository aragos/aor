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
        return state.copy(rules = resolve(state.rules))
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
            "map" -> {
                checkRule(
                    value == MapRule.STANDARD.commandValue,
                    ViolationCode.UNSUPPORTED_OPTION,
                    listOf(key, value),
                )
                rules.copy(map = MapRule.STANDARD)
            }
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
