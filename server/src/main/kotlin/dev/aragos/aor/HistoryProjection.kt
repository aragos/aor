package dev.aragos.aor

internal data class PlayerHistory(
    val state: GameState,
    val lines: List<String>,
    val events: List<HistoryEventView>,
)

/** History follows the same private audiences as live hands, including both sides of a trade. */
internal object HistoryProjection {
    /** Projects private history and the final state in the same replay of every revision delta. */
    fun forPlayer(stored: StoredGame, viewer: PlayerId): PlayerHistory {
        var state = GameState(stored.header)
        val events = mutableListOf<HistoryEventView>()
        val lines = buildList {
            for (recorded in stored.effectiveEntries()) {
                val entry = TextCodec.parse(recorded.text)
                add(line(state, entry, viewer))
                val after = state.step(entry)
                HistoryEvents.project(recorded.id, state, after, entry, viewer)?.let(events::add)
                state = after
            }
            stored.revisions
                .filter { it.replaceFrom != null || (it.id > 1 && it.undoPolicy != null) }
                .forEach { revision ->
                    add("# revision ${revision.id} by ${revision.author}: ${revision.command}")
                }
        }
        return PlayerHistory(state, lines, events.toList())
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
            entry.actor != viewer && command is SetupCommand.ReturnCard ->
                "${entry.actor}: return-card <hidden>"
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
