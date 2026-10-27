package dev.aragos.aor

/** Canonical locale-independent command and authority-outcome text, one entry per line. */
object TextCodec {
    private val whitespace = Regex("\\s+")

    fun parseCommand(text: String): Command {
        val parts = tokens(text)
        val arguments = parts.drop(1)
        return when (parts.first()) {
            "set-option" -> RuleOptionCommand.parse(arguments)
            "start" -> Command.Start.parse(arguments)
            "bid-capital" -> Command.BidCapital.parse(arguments)
            "choose-capital" -> Command.ChooseCapital.parse(arguments)
            "buy-tokens" -> Command.BuyTokens.parse(arguments)
            "choose-turn-position" -> Command.ChooseTurnPosition.parse(arguments)
            else ->
                SetupCommand.parse(parts.first(), arguments)
                    ?: BoardCommand.parse(parts.first(), arguments)
                    ?: CardCommand.parse(parts.first(), arguments)
                    ?: TurnCommand.parse(parts.first(), arguments)
        }
    }

    fun print(entry: Entry): String = entry.toText()

    /**
     * Resolves live input to a canonical action using only [actor]'s own hand and public state.
     * [actor] is the authenticated player when supplied; an explicit prefix must identify them.
     * Without either, exactly one prompted player is required. Capital prefixes refer to their
     * registered owners. Saved replay entries continue to use [parse] without contextual aliases.
     */
    fun parseAction(text: String, state: GameState, actor: PlayerId? = null): Entry.Action {
        val parts = tokens(text).joinToString(" ").split(':', limit = 2)
        val prefixedActor =
            if (parts.size == 2) {
                val name = parts[0].trim()
                state.players.values
                    .singleOrNull { player ->
                        player.player.id.value == name || player.capital == name
                    }
                    ?.player
                    ?.id ?: throw RuleViolation(ViolationCode.UNKNOWN_PLAYER)
            } else {
                null
            }
        checkRule(
            actor == null || prefixedActor == null || actor == prefixedActor,
            ViolationCode.WRONG_PLAYER,
        )
        val resolvedActor = actor ?: prefixedActor ?: promptedActor(state)
        val player = state.playerState(resolvedActor)
        val commandParts = tokens(parts.last())
        val command =
            if (commandParts.first() == "play") {
                CardCommand.PlayCard.parseInput(commandParts.drop(1), player.hand)
            } else {
                parseCommand(parts.last())
            }
        return Entry.Action(resolvedActor, command)
    }

    fun parse(text: String): Entry {
        if (!text.startsWith("!")) {
            return Entry.Action.parse(text)
        }
        val parts = tokens(text)
        val arguments = parts.drop(1)
        return when (parts.first()) {
            "!shuffle" -> Entry.Shuffle.parse(arguments)
            "!roll",
            "!draw",
            "!trade" -> GameOutcome.parse(parts.first(), arguments)
            else -> throw RuleViolation(ViolationCode.INVALID_COMMAND)
        }
    }

    private fun promptedActor(state: GameState): PlayerId =
        when (val prompt = state.prompts().singleOrNull()) {
            is Prompt.SinglePlayer -> prompt.player
            is Prompt.MultiPlayer -> prompt.players.singleOrNull()
            else -> null
        } ?: throw RuleViolation(ViolationCode.INVALID_COMMAND)

    private fun tokens(text: String): List<String> {
        checkRule('\n' !in text && '\r' !in text, ViolationCode.INVALID_COMMAND)
        return text.trim().split(whitespace)
    }
}

fun replay(header: GameHeader, entries: List<String>): GameState =
    entries.fold(GameState(header)) { state, line -> state.step(TextCodec.parse(line)) }
