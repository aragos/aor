package dev.aragos.aor

internal data class PlayerHistory(
    val state: GameState,
    val lines: List<String>,
    val events: List<HistoryEventView>,
)

/** History follows the same private audiences as live hands, including both sides of a trade. */
internal object HistoryProjection {
    /** Projects private history and the final state in the same replay of every revision delta. */
    fun forPlayer(stored: StoredGame, viewer: PlayerId?): PlayerHistory {
        var state = GameState(stored.header)
        var tokenBatch: TokenPurchaseBatch? = null
        val events = mutableListOf<HistoryEventView>()
        val lines = buildList {
            for (recorded in stored.effectiveEntries()) {
                val entry = TextCodec.parse(recorded.text)
                add(line(state, entry, viewer))
                val transition = state.transition(entry)
                val after = transition.state
                val purchase = (entry as? Entry.Action)?.command as? Command.BuyTokens
                if (entry is Entry.Action && purchase != null) {
                    val previous =
                        tokenBatch?.takeIf { batch ->
                            batch.before.turnNumber == state.turnNumber
                        } ?: TokenPurchaseBatch(recorded.id, state)
                    val batch =
                        previous.copy(amounts = previous.amounts + (entry.actor to purchase.amount))
                    if (batch.revealed(after)) {
                        val paid = transition.applied
                        events.add(
                            HistoryEvents.tokenPurchases(
                                batch.firstEntryId,
                                batch.before,
                                paid,
                                batch.amounts,
                            )
                        )
                        tokenBatch = null
                    } else {
                        tokenBatch = batch
                    }
                    events.addAll(
                        HistoryEvents.projectAll(
                                recorded.id,
                                state,
                                after,
                                entry,
                                viewer,
                                transition,
                            )
                            .filterNot { event -> event.kind == HistoryEventKind.BUY_TOKENS }
                    )
                } else {
                    tokenBatch = null
                    events.addAll(
                        HistoryEvents.projectAll(
                            recorded.id,
                            state,
                            after,
                            entry,
                            viewer,
                            transition,
                        )
                    )
                }
                state = after
            }
            stored.revisions
                .filter {
                    it.replaceFrom != null ||
                        it.storageArchive != null ||
                        it.adminAuthor != null ||
                        (it.id > 1 && it.undoPolicy != null)
                }
                .forEach { revision ->
                    add(
                        "# revision ${revision.id} by ${revision.adminAuthor?.let { if (viewer == null) "administrator" else "admin:$it" } ?: revision.author}: ${revision.command}"
                    )
                }
        }
        return PlayerHistory(state, lines, events.toList())
    }

    /**
     * Hold the batch until reveal, so an appended log never rewrites an already published event.
     */
    private data class TokenPurchaseBatch(
        val firstEntryId: String,
        val before: GameState,
        val amounts: Map<PlayerId, Int> = emptyMap(),
    ) {
        fun revealed(after: GameState): Boolean {
            val active =
                before.playerIds.filter { player ->
                    before.playerState(player).misery != MiseryStep.CHAOS
                }
            return amounts.keys == active.toSet() &&
                active.all { player ->
                    after.playerState(player).tokenPurchase != null
                }
        }
    }

    fun line(before: GameState, entry: Entry, viewer: PlayerId?): String =
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

    private fun action(before: GameState, entry: Entry.Action, viewer: PlayerId?): String {
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
