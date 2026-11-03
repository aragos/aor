package dev.aragos.aor

import java.util.UUID

enum class AbandonmentOperation {
    PROPOSE,
    AGREE,
    DECLINE,
    WITHDRAW,
    CLOSE,
}

enum class AbandonmentWriteResult {
    COMMITTED,
    ALREADY_AGREED,
}

enum class AbandonmentClosure {
    DECLINED,
    WITHDRAWN,
    UNANIMOUS,
    HISTORY_CHANGED,
    POLICY_CHANGED,
    UPGRADED,
    FINISHED,
    ADMINISTRATIVE,
}

/** Immutable public consent audit; baseRevision binds retries to their original preconditions. */
data class AbandonmentEvent(
    val requestId: UUID,
    val operation: AbandonmentOperation,
    val operationId: UUID? = null,
    val baseRevision: Int? = null,
    val closure: AbandonmentClosure? = null,
)

data class AbandonmentView(
    val requestId: UUID,
    val proposer: PlayerId,
    val agreed: List<PlayerId>,
    val outstanding: List<PlayerId>,
    val operations: List<AbandonmentOperation>,
)

/** Application lifecycle consensus does not write engine commands or consume random outcomes. */
internal object GameAbandonment {
    fun open(game: StoredGame, viewer: PlayerId? = null): AbandonmentView? {
        var proposer: PlayerId? = null
        var request: UUID? = null
        val agreed = mutableSetOf<PlayerId>()
        game.revisions.forEach { revision ->
            val event = revision.abandonment ?: return@forEach
            if (event.operation == AbandonmentOperation.PROPOSE) {
                proposer = revision.author
                request = event.requestId
                agreed.clear()
            }
            if (event.operation in setOf(AbandonmentOperation.PROPOSE, AbandonmentOperation.AGREE))
                agreed.add(revision.author)
            if (event.closure != null) {
                request = null
                proposer = null
                agreed.clear()
            }
        }
        val id = request ?: return null
        val electorate = game.header.players.map(Player::id)
        return AbandonmentView(
            id,
            checkNotNull(proposer) { "An open request requires its proposer." },
            electorate.filter { it in agreed },
            electorate.filter { it !in agreed },
            when (viewer) {
                null -> emptyList()
                in agreed -> listOf(AbandonmentOperation.WITHDRAW)
                else -> listOf(AbandonmentOperation.AGREE, AbandonmentOperation.DECLINE)
            },
        )
    }

    fun apply(
        game: StoredGame,
        player: PlayerId,
        event: AbandonmentEvent,
        at: Long = System.currentTimeMillis(),
    ): StoredGame {
        val committed = transition(game, player, event)
        val next =
            game.copy(
                revisions =
                    game.revisions +
                        Revision(
                            id = game.revision + 1,
                            parent = game.revision,
                            author = player,
                            command = "abandonment ${event.operation.name.lowercase()}",
                            entries = emptyList(),
                            at = at,
                            abandonment = committed,
                        )
            )
        return if (committed.closure == AbandonmentClosure.UNANIMOUS) terminal(next) else next
    }

    fun transition(game: StoredGame, player: PlayerId, event: AbandonmentEvent): AbandonmentEvent {
        if (game.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
        if (game.state().stage == Stage.FINISHED) throw ServiceFailure(422, "GAME_FINISHED")
        val open = open(game, player)
        val closure =
            when (event.operation) {
                AbandonmentOperation.PROPOSE -> {
                    if (open != null) throw ServiceFailure(409, "ABANDONMENT_ALREADY_PENDING")
                    if (game.revisions.any { it.abandonment?.requestId == event.requestId })
                        throw ServiceFailure(409, "ABANDONMENT_REQUEST_CLOSED")
                    null
                }
                AbandonmentOperation.CLOSE ->
                    throw ServiceFailure(403, "ABANDONMENT_RESPONSE_NOT_ALLOWED")
                else -> {
                    if (open == null || open.requestId != event.requestId)
                        throw ServiceFailure(409, "ABANDONMENT_REQUEST_CLOSED")
                    if (event.operation !in open.operations)
                        throw ServiceFailure(
                            409,
                            if (
                                event.operation == AbandonmentOperation.AGREE &&
                                    player in open.agreed
                            )
                                "ALREADY_AGREED"
                            else "ABANDONMENT_RESPONSE_NOT_ALLOWED",
                        )
                    when (event.operation) {
                        AbandonmentOperation.AGREE ->
                            AbandonmentClosure.UNANIMOUS.takeIf {
                                open.outstanding == listOf(player)
                            }
                        AbandonmentOperation.DECLINE -> AbandonmentClosure.DECLINED
                        else -> AbandonmentClosure.WITHDRAWN
                    }
                }
            }
        return event.copy(closure = closure)
    }

    fun closure(before: StoredGame, after: StoredGame): AbandonmentClosure? {
        if (open(before) == null) return null
        val revision = after.revisions.last()
        return when {
            after.status != GameStatus.ACTIVE -> AbandonmentClosure.ADMINISTRATIVE
            after.state().stage == Stage.FINISHED -> AbandonmentClosure.FINISHED
            revision.replaceFrom != null -> AbandonmentClosure.HISTORY_CHANGED
            revision.storageArchive != null -> AbandonmentClosure.UPGRADED
            revision.undoPolicy != null -> AbandonmentClosure.POLICY_CHANGED
            else -> null
        }
    }

    /** Attach automatic closure to its cause, never invent a separate player vote. */
    fun reconcile(before: StoredGame, after: StoredGame): StoredGame {
        if (before.revision == after.revision) return after
        val reason = closure(before, after) ?: return after
        val open = checkNotNull(open(before)) { "Automatic closure requires an open request." }
        val next =
            after.copy(
                revisions =
                    after.revisions.dropLast(1) +
                        after.revisions
                            .last()
                            .copy(
                                at =
                                    after.revisions.last().at.takeIf { it > 0 }
                                        ?: System.currentTimeMillis(),
                                abandonment =
                                    AbandonmentEvent(
                                        open.requestId,
                                        AbandonmentOperation.CLOSE,
                                        closure = reason,
                                    ),
                            )
            )
        return if (next.status != GameStatus.ACTIVE) terminal(next) else next
    }

    fun terminal(game: StoredGame): StoredGame =
        ActivityPlans.disarm(
            game.copy(
                pendingBids = emptyMap(),
                submissionVersions =
                    game.header.players.associate { it.id to UUID.randomUUID().toString() },
            ),
            PlanPause.EDITED,
        )
}
