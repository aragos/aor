package dev.aragos.aor.ai

/** Token fallback ownership; an unavailable adjustment retains the exact ordinary offered bid. */
enum class TokenOrderReference(val version: String) {
    ORDINARY("ordinary-token-reference-1"),
    FIXED_16_TO_15("fixed-token-reference-1");

    fun command(ordinary: String, offeredCommands: List<String>): String {
        require(ordinary in offeredCommands) {
            "The actual ordinary token choice must belong to its authoritative offers."
        }
        return if (
            this == FIXED_16_TO_15 &&
                ordinary == "buy-tokens 16" &&
                "buy-tokens 15" in offeredCommands
        ) {
            "buy-tokens 15"
        } else ordinary
    }

    /**
     * The observation retains its ordinary baseline; this index never changes proposal generation.
     */
    fun index(observation: TerritoryObservation): Int {
        val commands = observation.actions.map(TerritoryAction::command)
        return commands.indexOf(command(commands[observation.baselineIndex], commands))
    }
}
