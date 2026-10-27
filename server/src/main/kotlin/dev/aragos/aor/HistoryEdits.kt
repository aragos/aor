package dev.aragos.aor

/** History authority is independent of optional game mechanics. */
enum class UndoPolicy(val commandValue: String) {
    COMPETITIVE("competitive"),
    REGULAR_TURN("regular-turn"),
    FRIENDLY_TURN("friendly-turn"),
    FRIENDLY_GAME("friendly-game");

    companion object {
        fun parse(value: String): UndoPolicy =
            entries.singleOrNull { it.commandValue == value }
                ?: throw ServiceFailure(400, "INVALID_UNDO_POLICY")
    }
}

/** Zero-based positions address the effective log and are bound to expectedRevision. */
data class EditableActionView(val entry: Int, val actor: PlayerId, val text: String)

data class UndoView(
    val policy: UndoPolicy,
    val available: Boolean,
    val fromEntry: Int?,
    val entryCount: Int,
    val editRevision: Int,
    val actions: List<EditableActionView>,
)

/** Reject the whole proposed revision on any scope, dependency or randomness mismatch. */
internal object HistoryEdits {
    fun view(stored: StoredGame, player: PlayerId): UndoView {
        val entries = stored.effectiveEntries()
        val lower = editableFrom(stored, player)
        var state = GameState(stored.header)
        val actions = buildList {
            entries.forEachIndexed { index, recorded ->
                val entry = TextCodec.parse(recorded.text)
                if (lower != null && index >= lower && entry is Entry.Action) {
                    add(
                        EditableActionView(
                            index,
                            entry.actor,
                            HistoryProjection.line(state, entry, player),
                        )
                    )
                }
                state = state.step(entry)
            }
        }
        val available =
            actions.lastOrNull()?.let { action ->
                runCatching { replacement(stored, player, action.entry, emptyList()) }.isSuccess
            } ?: false
        return UndoView(
            stored.undoPolicy,
            available,
            lower,
            entries.size,
            stored.revisions
                .lastOrNull {
                    it.replaceFrom != null ||
                        it.administrativeAction == AdministrativeAction.UPGRADE
                }
                ?.id ?: 0,
            actions,
        )
    }

    fun undo(stored: StoredGame, player: PlayerId, target: Int? = null): StoredGame {
        val from =
            target
                ?: stored.effectiveEntries().indexOfLast {
                    TextCodec.parse(it.text) is Entry.Action
                }
        return commit(stored, player, from, emptyList(), "undo $from")
    }

    /** @N retains original action N, including private actions without revealing their contents. */
    fun edit(stored: StoredGame, player: PlayerId, from: Int, lines: List<String>): StoredGame =
        commit(stored, player, from, lines, "edit-history $from")

    fun configure(stored: StoredGame, player: PlayerId, policy: UndoPolicy): StoredGame {
        if (player != stored.header.creatorId) throw ServiceFailure(403, "HISTORY_EDIT_FORBIDDEN")
        if (stored.state().stage != Stage.CONFIGURATION) {
            throw ServiceFailure(422, "UNDO_POLICY_FROZEN")
        }
        return stored.copy(
            revisions =
                stored.revisions +
                    Revision(
                        id = stored.revision + 1,
                        parent = stored.revision,
                        author = player,
                        command = "set-undo-policy ${policy.commandValue}",
                        entries = emptyList(),
                        undoPolicy = policy,
                    )
        )
    }

    private fun commit(
        stored: StoredGame,
        player: PlayerId,
        from: Int,
        lines: List<String>,
        command: String,
    ): StoredGame {
        val entries = replacement(stored, player, from, lines)
        val edited =
            stored.copy(
                revisions =
                    stored.revisions +
                        Revision(
                            id = stored.revision + 1,
                            parent = stored.revision,
                            author = player,
                            command = command,
                            entries = entries,
                            replaceFrom = from,
                        ),
                pendingBids = emptyMap(),
                pendingReturns = emptyMap(),
            )
        val before = stored.state()
        val after = edited.state()
        if (before.stage != after.stage || before.turnNumber != after.turnNumber) return edited
        val participants =
            (after.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players.orEmpty()
        val pending =
            stored.pendingBids.filter { (actor, amount) ->
                actor in participants &&
                    runCatching {
                        val action =
                            if (after.stage == Stage.BIDDING) Command.BidCapital(amount)
                            else Command.BuyTokens(amount)
                        after.step(Entry.Action(actor, action))
                    }
                        .isSuccess
            }
        return edited.copy(pendingBids = pending.takeIf { it.size < participants.size }.orEmpty())
    }

    /** Reconstruct retention references when validating a stored replacement suffix. */
    fun retainedLines(
        stored: StoredGame,
        author: PlayerId,
        from: Int,
        entries: List<String>,
    ): List<String> {
        val original = stored.effectiveEntries()
        val used = mutableSetOf<Int>()
        return entries.map(TextCodec::parse).filterIsInstance<Entry.Action>().map { action ->
            if (action.actor == author) action.toText()
            else {
                val index =
                    original.indices.firstOrNull { index ->
                        index >= from && index !in used && original[index].text == action.toText()
                    } ?: throw ServiceFailure(403, "HISTORY_OTHER_PLAYER_ANSWER")
                used.add(index)
                "@$index"
            }
        }
    }

    /** Used for both publication and validating an edit loaded from untrusted storage. */
    fun replacement(
        stored: StoredGame,
        player: PlayerId,
        from: Int,
        lines: List<String>,
    ): List<String> {
        val recorded = stored.effectiveEntries().map(RecordedEntry::text)
        val lower =
            editableFrom(stored, player) ?: throw ServiceFailure(403, "HISTORY_EDIT_FORBIDDEN")
        if (from !in lower until recorded.size || lines.size > 10000) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
        val prefix = replay(stored.header, recorded.take(from))
        requireEditBoundary(prefix)
        if (TextCodec.parse(recorded[from]) !is Entry.Action) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
        val originals = recorded.drop(from).map(TextCodec::parse)
        val retainedOthers =
            originals
                .filterIsInstance<Entry.Action>()
                .filter { it.actor != player }
                .groupingBy(Entry.Action::toText)
                .eachCount()
                .toMutableMap()
        val outcomes = recordedOutcomes(prefix, originals)
        val occurrences = mutableMapOf<List<Any?>, Int>()
        var state = prefix
        val replacement = buildList {
            fun resolveRecorded() {
                while (state.prompts().singleOrNull() is Prompt.System) {
                    val key = requestKey(state, occurrences)
                    val outcome =
                        outcomes.remove(key)
                            ?: throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
                    try {
                        state = state.step(outcome)
                    } catch (_: Exception) {
                        throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
                    }
                    add(outcome.toText())
                }
            }
            for ((position, line) in lines.withIndex()) {
                resolveRecorded()
                val action =
                    try {
                        if (line.startsWith('@')) {
                            val index =
                                line.drop(1).toIntOrNull()
                                    ?: throw ServiceFailure(400, "INVALID_HISTORY_EDIT")
                            if (index !in from until recorded.size) {
                                throw ServiceFailure(400, "INVALID_HISTORY_EDIT")
                            }
                            TextCodec.parse(recorded[index]) as? Entry.Action
                                ?: throw ServiceFailure(400, "INVALID_HISTORY_EDIT")
                        } else {
                            TextCodec.parseAction(line, state, actor = player)
                        }
                    } catch (failure: RuleViolation) {
                        if (failure.code == ViolationCode.WRONG_PLAYER) {
                            throw ServiceFailure(403, "HISTORY_OTHER_PLAYER_ANSWER")
                        }
                        throw ServiceFailure(422, "HISTORY_INVALID_ENTRY", entryIndex = position)
                    }
                if (action.actor != player) {
                    val count = retainedOthers[action.toText()] ?: 0
                    if (count == 0) throw ServiceFailure(403, "HISTORY_OTHER_PLAYER_ANSWER")
                    retainedOthers[action.toText()] = count - 1
                }
                try {
                    state = state.step(action)
                } catch (_: Exception) {
                    throw ServiceFailure(422, "HISTORY_INVALID_ENTRY", entryIndex = position)
                }
                add(action.toText())
            }
            resolveRecorded()
        }
        if (outcomes.isNotEmpty()) throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
        requireEditBoundary(state)
        if (replacement == recorded.drop(from)) throw ServiceFailure(422, "HISTORY_NO_CHANGE")
        return replacement
    }

    private fun editableFrom(stored: StoredGame, player: PlayerId): Int? {
        if (stored.undoPolicy == UndoPolicy.COMPETITIVE) return null
        val recorded = stored.effectiveEntries().map { TextCodec.parse(it.text) }
        var state = GameState(stored.header)
        var setupEnd: Int? = null
        recorded.forEachIndexed { index, entry ->
            state = state.step(entry)
            if (setupEnd == null && state.turnNumber != null) setupEnd = index + 1
        }
        val setup = setupEnd ?: return null
        if (stored.undoPolicy == UndoPolicy.FRIENDLY_GAME && player == stored.header.creatorId)
            return setup
        val prompt = state.prompts().singleOrNull() as? Prompt.SinglePlayer
        val owner = state.flow.actor ?: prompt?.player
        if (owner != player || state.stage == Stage.FINISHED) return null
        val activity = if (state.flow.actor == null) state.decisionStart else state.activityStart
        val lower = maxOf(setup, activity)
        if (stored.undoPolicy != UndoPolicy.REGULAR_TURN) return lower
        val boundary =
            recorded.indexOfLast { entry -> entry !is Entry.Action || entry.actor != player } + 1
        return maxOf(lower, boundary)
    }

    private fun recordedOutcomes(
        initial: GameState,
        entries: List<Entry>,
    ): MutableMap<List<Any?>, Entry> {
        var state = initial
        val occurrences = mutableMapOf<List<Any?>, Int>()
        val outcomes = mutableMapOf<List<Any?>, Entry>()
        for (entry in entries) {
            if (entry !is Entry.Action) outcomes[requestKey(state, occurrences)] = entry
            state = state.step(entry)
        }
        return outcomes
    }

    /** Logical events include their participants/target; unrelated equal dice cannot substitute. */
    internal fun requestKey(
        state: GameState,
        occurrences: MutableMap<List<Any?>, Int>,
    ): List<Any?> {
        val need =
            (state.prompts().singleOrNull() as? Prompt.System)?.need
                ?: throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
        val normalized =
            if (need is SystemNeed.Shuffle) need.copy(cards = need.cards.sorted()) else need
        val frame =
            when (val decision = state.flow.decisions.lastOrNull()) {
                is BoardDecision.CompetitionDice ->
                    listOf(decision.plan.player, decision.plan.destination, decision.plan.defenders)
                is BoardDecision.WindmillDice ->
                    listOf(decision.winner, decision.victim, decision.marketNumber)
                is BoardDecision.RandomTrade ->
                    listOf(decision.owner, decision.recipient, decision.offeredReference)
                is CardDecision.WarRoll ->
                    listOf(
                        decision.war.initiator,
                        decision.war.opponent,
                        decision.war.startedTurn,
                        decision.war.ties,
                        decision.war.lastRound,
                    )
                else -> null
            }
        val base =
            listOf(
                state.turnNumber,
                state.currentEpoch,
                state.stage,
                state.flow.actor,
                state.flow.shortageRolls,
                normalized,
                frame,
            )
        val ordinal = occurrences[base] ?: 0
        occurrences[base] = ordinal + 1
        return base + ordinal
    }

    private fun requireEditBoundary(state: GameState) {
        try {
            requireCompletedTransaction(state)
        } catch (_: IllegalArgumentException) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
    }
}
