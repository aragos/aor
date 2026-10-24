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

    /** Presets replace the functional snapshot; later option commands may override any value. */
    fun resolve(rules: RuleOptions): RuleOptions =
        when (key) {
            "setup" -> rules.copy(setup = option(SetupRule.entries, SetupRule::commandValue))
            "initial-card" ->
                rules.copy(
                    initialCard = option(InitialCardRule.entries, InitialCardRule::commandValue)
                )
            "commerce" ->
                rules.copy(commerce = option(CommerceRule.entries, CommerceRule::commandValue))
            "wind-watermill" ->
                rules.copy(
                    windWatermill =
                        option(WindWatermillRule.entries, WindWatermillRule::commandValue)
                )
            "written-record" ->
                rules.copy(
                    writtenRecord =
                        option(WrittenRecordRule.entries, WrittenRecordRule::commandValue)
                )
            "income-relief" ->
                rules.copy(
                    incomeRelief = option(IncomeReliefRule.entries, IncomeReliefRule::commandValue)
                )
            "credits" -> rules.copy(credits = option(CreditRule.entries, CreditRule::commandValue))
            "misery-relief" ->
                rules.copy(
                    miseryRelief = option(MiseryReliefRule.entries, MiseryReliefRule::commandValue)
                )
            "patronage" ->
                rules.copy(patronage = option(PatronageRule.entries, PatronageRule::commandValue))
            "immediate-master-art-discard" -> rules.copy(immediateMasterArtDiscard = enabled())
            "st-gall-support" -> rules.copy(stGallSupport = enabled())
            "preset" ->
                when (value) {
                    "ah1996" -> RuleOptions.AH1996
                    "second-edition" -> RuleOptions.SECOND_EDITION
                    "tournament" -> RuleOptions.TOURNAMENT
                    else ->
                        throw RuleViolation(ViolationCode.UNSUPPORTED_OPTION, listOf(key, value))
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
