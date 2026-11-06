package dev.aragos.aor

import java.util.UUID

/** History authority is independent of optional game mechanics. */
enum class UndoPolicy(val commandValue: String) {
    COMPETITIVE("competitive"),
    REGULAR_TURN("regular-turn"),
    FRIENDLY_TURN("friendly-turn"),
    FRIENDLY_GAME("friendly-game"),
    LINEAR_GAME("linear-game");

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
    val capability: HistoryCapability = HistoryCapability.NONE,
    val latestTarget: RewindTarget? = null,
    val converted: Boolean = false,
)

/** Reject the whole proposed revision on any scope, dependency or randomness mismatch. */
internal object HistoryEdits {
    fun view(stored: StoredGame, player: PlayerId?): UndoView {
        val linear = stored.undoPolicy == UndoPolicy.LINEAR_GAME
        val entries = stored.effectiveEntries()
        val lower = player?.let { editableFrom(stored, it) }
        val actions = if (linear) emptyList() else editableActions(stored, player, lower)
        val target =
            if (linear && player != null) {
                LinearHistory.targets(stored, player).firstOrNull()
            } else {
                null
            }
        val available =
            !linear &&
                player != null &&
                (actions.lastOrNull()?.let { action ->
                    runCatching { replacement(stored, player, action.entry, emptyList()) }.isSuccess
                } ?: false)
        return UndoView(
            stored.undoPolicy,
            if (linear) target != null else available,
            lower,
            entries.size,
            stored.revisions
                .lastOrNull {
                    it.replaceFrom != null ||
                        it.administrativeAction == AdministrativeAction.UPGRADE
                }
                ?.id ?: 0,
            actions,
            when {
                lower == null -> HistoryCapability.NONE
                linear -> HistoryCapability.PREFIX_REWIND
                else -> HistoryCapability.SUFFIX_EDIT
            },
            target,
            stored.revisions.any { it.command == "upgrade-linear-game" },
        )
    }

    private fun editableActions(
        stored: StoredGame,
        player: PlayerId?,
        lower: Int?,
    ): List<EditableActionView> {
        if (lower == null) return emptyList()
        var state = GameState(stored.header)
        return buildList {
            stored.effectiveEntries().forEachIndexed { index, recorded ->
                val entry = TextCodec.parse(recorded.text)
                if (index >= lower && entry is Entry.Action) {
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
    }

    fun undo(stored: StoredGame, player: PlayerId, target: Int? = null): StoredGame {
        val from =
            if (stored.undoPolicy == UndoPolicy.LINEAR_GAME)
                target
                    ?: LinearHistory.targets(stored, player).firstOrNull()?.entryCount
                    ?: throw ServiceFailure(403, "HISTORY_EDIT_FORBIDDEN")
            else
                target
                    ?: stored.effectiveEntries().indexOfLast {
                        TextCodec.parse(it.text) is Entry.Action
                    }
        return commit(stored, player, from, emptyList(), "undo $from")
    }

    /** @N retains original action N, including private actions without revealing their contents. */
    fun edit(stored: StoredGame, player: PlayerId, from: Int, lines: List<String>): StoredGame {
        if (stored.undoPolicy == UndoPolicy.LINEAR_GAME) {
            throw ServiceFailure(422, "HISTORY_SUFFIX_UNSUPPORTED")
        }
        return commit(stored, player, from, lines, "edit-history $from")
    }

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
        val replacement =
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
            )
        // The new revision rejects stale bot work without revoking the browser's lease.
        val edited =
            if (stored.undoPolicy == UndoPolicy.REGULAR_TURN) replacement
            else BotAutomation.pause(replacement)
        if (stored.undoPolicy == UndoPolicy.LINEAR_GAME) {
            return edited.copy(
                submissionVersions =
                    stored.header.players.associate {
                        it.id to UUID.randomUUID().toString()
                    }
            )
        }
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
        if (stored.undoPolicy == UndoPolicy.LINEAR_GAME) {
            if (lines.isNotEmpty()) throw ServiceFailure(422, "HISTORY_SUFFIX_UNSUPPORTED")
            LinearHistory.validate(stored, player, from)
            return emptyList()
        }
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
                    val key = OutcomeIdentity.requestKey(state, occurrences)
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
                } catch (failure: Exception) {
                    throw ServiceFailure(
                        422,
                        "HISTORY_INVALID_ENTRY",
                        entryIndex = position,
                        diagnostic = (failure as? DomainRejection)?.diagnostic,
                    )
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

    /** [state], when supplied by revision validation, is the already replayed effective history. */
    internal fun editableFrom(
        stored: StoredGame,
        player: PlayerId,
        state: GameState? = null,
    ): Int? {
        if (stored.undoPolicy == UndoPolicy.COMPETITIVE) return null
        val recorded = stored.effectiveEntries().map { TextCodec.parse(it.text) }
        var setupState = GameState(stored.header)
        val setupEnd = recorded.indexOfFirst { entry ->
            setupState = setupState.step(entry)
            setupState.turnNumber != null
        }
        if (setupEnd < 0) return null
        val setup = setupEnd + 1
        if (
            stored.undoPolicy in setOf(UndoPolicy.FRIENDLY_GAME, UndoPolicy.LINEAR_GAME) &&
                player == stored.header.creatorId
        ) {
            return setup
        }
        val current = state ?: stored.state()
        val prompt = current.prompts().singleOrNull() as? Prompt.SinglePlayer
        val owner = current.flow.actor ?: prompt?.player
        if (owner != player || current.stage == Stage.FINISHED) return null
        val activity =
            if (current.flow.actor == null) {
                current.decisionStart
            } else {
                current.activityStart
            }
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
            if (entry !is Entry.Action)
                outcomes[OutcomeIdentity.requestKey(state, occurrences)] = entry
            state = state.step(entry)
        }
        return outcomes
    }

    private fun requireEditBoundary(state: GameState) {
        try {
            requireCompletedTransaction(state)
        } catch (_: IllegalArgumentException) {
            throw ServiceFailure(422, "HISTORY_EDIT_BOUNDARY")
        }
    }
}
