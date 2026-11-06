package dev.aragos.aor

/** A complete current-prefix destination, with no hidden action text or card identities. */
data class RewindTarget(
    val entryCount: Int,
    val turnNumber: Int?,
    val stage: Stage,
    val removesRandomness: Boolean,
    val actor: PlayerId,
    val actionText: String,
)

data class RewindTargets(
    val expectedRevision: Int,
    val targets: List<RewindTarget>,
    val nextBefore: Int?,
)

enum class HistoryCapability {
    NONE,
    SUFFIX_EDIT,
    PREFIX_REWIND,
}

/** Prefix boundaries come from replay, including complete simultaneous batches and outcomes. */
internal object LinearHistory {
    fun targets(stored: StoredGame, player: PlayerId): List<RewindTarget> {
        if (stored.status != GameStatus.ACTIVE) return emptyList()
        val lower = HistoryEdits.editableFrom(stored, player) ?: return emptyList()
        val entries = stored.effectiveEntries().map { TextCodec.parse(it.text) }
        val lastRandom = entries.indexOfLast { it !is Entry.Action }
        var state = GameState(stored.header)
        return buildList {
            entries.forEachIndexed { index, entry ->
                if (
                    index >= lower &&
                        entry is Entry.Action &&
                        runCatching { requireCompletedTransaction(state) }.isSuccess
                ) {
                    add(
                        RewindTarget(
                            index,
                            state.turnNumber,
                            state.stage,
                            lastRandom >= index,
                            entry.actor,
                            HistoryProjection.line(state, entry, player),
                        )
                    )
                }
                state = state.step(entry)
            }
        }
            .asReversed()
    }

    /**
     * Returns the validated destination state without enumerating every other rewind target.
     * [state], when supplied, is the caller's already replayed effective history of [stored].
     * [prefixes] belongs to the same ordered replay and is accessed only after scope validation.
     */
    fun validate(
        stored: StoredGame,
        player: PlayerId,
        from: Int,
        state: GameState? = null,
        prefixes: ReplayPrefixes? = null,
    ): GameState {
        if (stored.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
        val lower =
            HistoryEdits.editableFrom(stored, player, state)
                ?: throw ServiceFailure(403, "HISTORY_EDIT_FORBIDDEN")
        val entries = stored.effectiveEntries()
        if (from == entries.size) throw ServiceFailure(422, "HISTORY_NO_CHANGE")
        if (
            from !in lower until entries.size ||
                TextCodec.parse(entries[from].text) !is Entry.Action
        ) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
        val destination =
            prefixes?.restore(entries.map(RecordedEntry::text), from)
                ?: replay(stored.header, entries.take(from).map(RecordedEntry::text))
        if (runCatching { requireCompletedTransaction(destination) }.isFailure) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
        return destination
    }
}
