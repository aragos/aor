package dev.aragos.aor

/** Public functional configuration; values use the same canonical encoding as setup commands. */
data class RuleOptionView(val key: String, val value: String, val choices: List<String>) {
    companion object {
        internal fun from(rules: RuleOptions): List<RuleOptionView> =
            rules.configurationCommands().map { command ->
                val choices =
                    when (command.key) {
                        "initial-card" -> InitialCardRule.entries.map(InitialCardRule::commandValue)
                        "deck-modification",
                        "immediate-master-art-discard" -> listOf("disabled", "enabled")
                        "map" -> listOf("standard")
                        else -> error("A configured rule needs public choices: ${command.key}.")
                    }
                RuleOptionView(command.key, command.value, choices)
            }
    }
}
