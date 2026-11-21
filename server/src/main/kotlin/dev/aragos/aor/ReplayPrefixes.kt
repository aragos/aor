package dev.aragos.aor

/**
 * Bounded checkpoints for one ordered revision replay. Only requested rewind destinations are
 * retained. Restoring a prefix discards later checkpoints; suffix edits reset the replay entirely.
 * These derived states never enter storage or survive a change to the revision history.
 */
internal class ReplayPrefixes(private val header: GameHeader, revisions: List<Revision>) {
    private val destinations = revisions.mapNotNull(Revision::replaceFrom).toSet()
    private val states = LinkedHashMap<Int, GameState>()

    fun step(state: GameState, entry: Entry): GameState {
        if (state.entryCount in destinations) {
            states[state.entryCount] = state
            if (states.size > 32) states.remove(states.keys.first())
        }
        return state.step(entry)
    }

    /** [count] is the number of entries retained from the caller's current effective [entries]. */
    fun restore(entries: List<String>, count: Int): GameState {
        val state = states[count] ?: replay(header, entries.take(count))
        states.keys.removeAll { position -> position > count }
        return state
    }

    fun reset(entries: List<String>): GameState {
        states.clear()
        return entries.fold(GameState(header)) { state, text -> step(state, TextCodec.parse(text)) }
    }
}

/** Include superseded play so rewinding an upgraded game retains each historical turn's rules. */
internal fun StoredGame.latestRecordedTurn(turn: (GameState, GameState) -> Int?): Int {
    val prefixes = ReplayPrefixes(header, revisions)
    var state = GameState(header)
    var entries = emptyList<String>()
    var latestTurn = 0
    for (revision in revisions) {
        val retained = revision.replaceFrom?.let(entries::take) ?: entries
        if (revision.replaceFrom != null) state = prefixes.restore(entries, revision.replaceFrom)
        for (line in revision.entries) {
            val next = prefixes.step(state, TextCodec.parse(line))
            latestTurn = maxOf(latestTurn, turn(state, next) ?: 0)
            state = next
        }
        entries = retained + revision.entries
    }
    return latestTurn
}
