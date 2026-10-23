package dev.aragos.aor

/** Canonical locale-independent command and authority-outcome text, one entry per line. */
object TextCodec {
    fun parseCommand(text: String): Command {
        val parts = text.trim().split(Regex("\\s+"))
        val arguments = parts.drop(1)
        return when (parts.first()) {
            "set-option" ->
                when (arguments.firstOrNull()) {
                    "epochs" -> Command.SetEpochs.parse(arguments)
                    "rebuilt-marker-bonus" -> Command.SetRebuiltMarkerBonus.parse(arguments)
                    null -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
                    else -> throw RuleViolation(ViolationCode.UNSUPPORTED_OPTION)
                }
            "start" -> Command.Start.parse(arguments)
            "bid-capital" -> Command.BidCapital.parse(arguments)
            "choose-capital" -> Command.ChooseCapital.parse(arguments)
            "buy-tokens" -> Command.BuyTokens.parse(arguments)
            "choose-turn-position" -> Command.ChooseTurnPosition.parse(arguments)
            else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
        }
    }

    fun print(entry: Entry): String = entry.toText()

    fun parse(text: String): Entry {
        if (!text.startsWith("!")) {
            // TODO: Optionally allow players to enter commands without actor prefix where context
            // is clear
            // TODO: Once capitals are selected, allow actor prefix to be capital name
            return Entry.Action.parse(text)
        }
        val parts = text.split(" ")
        val arguments = parts.drop(1)
        return when (parts.first()) {
            "!shuffle" -> Entry.Shuffle.parse(arguments)
            "!deal" -> Entry.Deal.parse(arguments)
            "!roll" -> Entry.CapitalRoll.parse(arguments)
            else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
        }
    }
}

fun replay(header: GameHeader, entries: List<String>): GameState =
    entries.fold(GameState(header)) { state, line -> state.step(TextCodec.parse(line)) }
