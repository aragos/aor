package dev.aragos.aor

/** History follows the same private audiences as live hands, including both sides of a trade. */
internal object HistoryProjection {
    fun forPlayer(stored: StoredGame, viewer: PlayerId): List<String> = buildList {
        var before = GameState(stored.header)
        for (text in stored.revisions.flatMap(Revision::entries)) {
            val entry = TextCodec.parse(text)
            add(line(before, entry, viewer))
            before = before.step(entry)
        }
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
