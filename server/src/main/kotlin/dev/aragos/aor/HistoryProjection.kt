package dev.aragos.aor

internal data class PlayerHistory(val state: GameState, val lines: List<String>)

/** History follows the same private audiences as live hands, including both sides of a trade. */
internal object HistoryProjection {
    /** Projects private history and the final state in the same replay of every revision delta. */
    fun forPlayer(stored: StoredGame, viewer: PlayerId): PlayerHistory {
        var state = GameState(stored.header)
        val lines = buildList {
            for (revision in stored.revisions) {
                for (text in revision.entries) {
                    val entry = TextCodec.parse(text)
                    add(line(state, entry, viewer))
                    state = state.step(entry)
                }
            }
        }
        return PlayerHistory(state, lines)
    }

    fun line(before: GameState, entry: Entry, viewer: PlayerId): String =
        when (entry) {
            is Entry.Shuffle -> "!shuffle <hidden>"
            is GameOutcome.Draw ->
                if (entry.player == viewer) {
                    entry.toText()
                } else {
                    "!draw ${entry.player} ${entry.reference} <hidden>"
                }
            is GameOutcome.Trade ->
                if (viewer == entry.owner || viewer == entry.recipient) {
                    entry.toText()
                } else {
                    "!trade ${entry.owner} ${entry.recipient} <hidden>"
                }
            is Entry.Action -> action(before, entry, viewer)
            else -> entry.toText()
        }

    private fun action(before: GameState, entry: Entry.Action, viewer: PlayerId): String {
        val command = entry.command
        return when {
            entry.actor != viewer && command is TurnCommand.DiscardCard ->
                "${entry.actor}: discard-card <hidden>"
            command is BoardCommand.OfferCard && viewer !in BoardGame.tradeParticipants(before) ->
                "${entry.actor}: offer-card <hidden>"
            command is CardCommand.PlayCard -> {
                val reference = command.reference
                val card =
                    before.playerState(entry.actor).hand.singleOrNull { card ->
                        card.reference == reference
                    }
                if (card == null) entry.toText() else "${entry.toText()} [${card.definition.id}]"
            }
            else -> entry.toText()
        }
    }
}
