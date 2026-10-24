package dev.aragos.aor

/** Public functional configuration; values use the same canonical encoding as setup commands. */
data class RuleOptionView(val key: String, val value: String, val choices: List<String>) {
    companion object {
        internal fun from(rules: RuleOptions): List<RuleOptionView> =
            rules.configurationCommands().map { command ->
                val choices =
                    when (command.key) {
                        "setup" -> SetupRule.entries.map(SetupRule::commandValue)
                        "initial-card" -> InitialCardRule.entries.map(InitialCardRule::commandValue)
                        "commerce" -> CommerceRule.entries.map(CommerceRule::commandValue)
                        "wind-watermill" ->
                            WindWatermillRule.entries.map(WindWatermillRule::commandValue)
                        "written-record" ->
                            WrittenRecordRule.entries.map(WrittenRecordRule::commandValue)
                        "income-relief" ->
                            IncomeReliefRule.entries.map(IncomeReliefRule::commandValue)
                        "credits" -> CreditRule.entries.map(CreditRule::commandValue)
                        "misery-relief" ->
                            MiseryReliefRule.entries.map(MiseryReliefRule::commandValue)
                        "patronage" -> PatronageRule.entries.map(PatronageRule::commandValue)
                        "immediate-master-art-discard",
                        "st-gall-support" -> listOf("disabled", "enabled")
                        else -> error("A configured rule needs public choices: ${command.key}.")
                    }
                RuleOptionView(command.key, command.value, choices)
            }
    }
}
