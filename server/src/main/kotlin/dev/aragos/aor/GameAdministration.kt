package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path
import java.time.Clock
import java.util.UUID

/** Public list status is derived from engine readiness and the operational lifecycle. */
enum class GameListStatus {
    ONGOING,
    MY_MOVE,
    FINISHED,
    CANCELLED,
    ABANDONED,
    UNAVAILABLE,
}

data class GameSummary(
    val id: String,
    val createdAt: Long,
    val revision: Int,
    val status: GameListStatus,
    val stage: Stage?,
    val players: List<Player>,
    val myMove: Boolean,
)

data class GamePage(val games: List<GameSummary>, val nextCursor: String?)

data class GameLog(
    val id: String,
    val fileVersion: Int,
    val revision: Int,
    val header: JsonNode,
    val entries: List<String>,
    val archives: List<String>,
    val revisions: List<JsonNode>,
)

data class LogPage(val games: List<GameLog>, val nextCursor: String?)

data class LogValidation(
    val valid: Boolean,
    val revision: Int,
    val entryCount: Int,
    val stage: Stage,
)

/** Destructive requests retain intent receipts even if the following storage operation fails. */
class AdministrationAudit(
    private val directory: Path,
    private val writer: GameWriter = AtomicGameWriter,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun record(actor: AccountId, action: AdministrativeAction, target: String, revision: Int) {
        val record =
            mapOf(
                "at" to clock.millis(),
                "actor" to actor.value,
                "action" to action.name,
                "target" to target,
                "revision" to revision,
                "outcome" to "REQUESTED",
            )
        writer.write(
            directory.resolve("${clock.millis()}-${UUID.randomUUID()}.json"),
            json.writeValueAsString(record),
        )
    }
}

/**
 * Authorized operational changes replay proposed logs before replaceable storage publishes them.
 */
class GameAdministration(
    private val store: ManagedGameStore,
    private val audit: AdministrationAudit,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(
        viewer: AccountId,
        admin: Boolean,
        filter: String?,
        after: String?,
        limit: Int,
    ): GamePage {
        requireLimit(limit)
        val status = parseFilter(filter)
        val matches =
            store
                .ids()
                .asSequence()
                .filter { after == null || it > after }
                .mapNotNull { id ->
                    val root = json.readTree(store.document(id))
                    if (
                        !admin &&
                            root["memberships"]?.properties()?.none {
                                it.value.asText() == viewer.value
                            } != false
                    ) {
                        return@mapNotNull null
                    }
                    val summary = summary(id, root, viewer)
                    if (
                        status == null ||
                            summary.status == status ||
                            (status == GameListStatus.ONGOING &&
                                summary.status == GameListStatus.MY_MOVE)
                    ) {
                        summary
                    } else {
                        null
                    }
                }
                .take(limit + 1)
                .toList()
        return GamePage(matches.take(limit), matches.takeIf { it.size > limit }?.get(limit - 1)?.id)
    }

    fun log(id: String, archive: String? = null): GameLog {
        val root =
            json.readTree(
                if (archive == null) store.document(id) else store.archivedDocument(id, archive)
            )
        return GameLog(
            id,
            root.field("fileVersion").integer(),
            root.field("revision").integer(),
            root.field("header"),
            effectiveText(root),
            if (archive == null) store.archives(id) else emptyList(),
            root.field("revisions").array(),
        )
    }

    fun logs(after: String?, limit: Int): LogPage {
        requireLimit(limit)
        val ids = store.ids().filter { after == null || it > after }.take(limit + 1)
        return LogPage(ids.take(limit).map(::log), ids.takeIf { it.size > limit }?.get(limit - 1))
    }

    fun changeStatus(
        id: String,
        actor: AccountId,
        expectedRevision: Int,
        action: AdministrativeAction,
    ): GameSummary {
        if (action != AdministrativeAction.CANCEL && action != AdministrativeAction.ABANDON) {
            throw ServiceFailure(400, "INVALID_REQUEST")
        }
        val changed =
            store.update(id) { current ->
                if (current.revision != expectedRevision) {
                    throw ServiceFailure(409, "STALE_REVISION")
                }
                current.copy(
                    revisions =
                        current.revisions +
                            Revision(
                                id = current.revision + 1,
                                parent = current.revision,
                                author = current.header.creatorId,
                                command = "admin-${action.name.lowercase()}",
                                entries = emptyList(),
                                adminAuthor = actor.value,
                                administrativeAction = action,
                                at = clock.millis(),
                            ),
                    pendingBids = emptyMap(),
                    pendingReturns = emptyMap(),
                )
            }
        return summary(changed, actor)
    }

    /**
     * Explicit upgrades preserve every recorded outcome and archive the complete preceding file.
     */
    fun replaceLog(
        id: String,
        actor: AccountId,
        expectedRevision: Int,
        entries: List<String>,
    ): GameLog {
        store.checkpoint(id, expectedRevision) { contents, reference ->
            replacement(id, actor, expectedRevision, entries, contents, reference)
        }
        return log(id)
    }

    /** Validation runs the same replay as publication, without writing a checkpoint or archive. */
    fun validateLog(
        id: String,
        actor: AccountId,
        expectedRevision: Int,
        entries: List<String>,
    ): LogValidation {
        val contents = store.document(id)
        if (json.readTree(contents).field("revision").integer() != expectedRevision) {
            throw ServiceFailure(409, "STALE_REVISION")
        }
        val candidate =
            replacement(
                id,
                actor,
                expectedRevision,
                entries,
                contents,
                "$expectedRevision-${"0".repeat(64)}.json",
            )
        return LogValidation(true, expectedRevision, entries.size, candidate.state().stage)
    }

    private fun replacement(
        id: String,
        actor: AccountId,
        expectedRevision: Int,
        entries: List<String>,
        contents: String,
        reference: String,
    ): StoredGame {
        if (
            entries.isEmpty() ||
                entries.size > 100000 ||
                entries.any { it.length > 32768 || '\n' in it || '\r' in it }
        ) {
            throw ServiceFailure(400, "INVALID_HISTORY_EDIT")
        }
        val original = json.readTree(contents)
        val oldEntries = effectiveText(original)
        if (entries.filter { it.startsWith('!') } != oldEntries.filter { it.startsWith('!') }) {
            throw ServiceFailure(422, "HISTORY_RANDOMNESS_CHANGED")
        }
        val header = replayUpgradedLog(original.field("header"), entries).header
        val policy =
            original["undoPolicy"]?.takeIf { it.isTextual }?.let { UndoPolicy.valueOf(it.asText()) }
                ?: UndoPolicy.REGULAR_TURN
        val status =
            original["status"]?.takeIf { it.isTextual }?.let { GameStatus.valueOf(it.asText()) }
                ?: GameStatus.ACTIVE
        return StoredGame(
            id = id,
            header = header,
            credentials =
                original.field("credentials").properties().associate { (player, hash) ->
                    PlayerId(player) to hash.string()
                },
            memberships =
                original["memberships"]
                    ?.properties()
                    ?.associate { (player, uid) -> PlayerId(player) to uid.string() }
                    .orEmpty(),
            createdAt =
                original["createdAt"]?.takeIf(JsonNode::isIntegralNumber)?.longValue()
                    ?: clock.millis(),
            initialStatus = status,
            baseRevision = expectedRevision,
            previousArchive = reference,
            revisions =
                listOf(
                    Revision(
                        id = expectedRevision + 1,
                        parent = expectedRevision,
                        author = header.creatorId,
                        command = "admin-replace-log",
                        entries = entries,
                        undoPolicy = policy,
                        adminAuthor = actor.value,
                        administrativeAction = AdministrativeAction.UPGRADE,
                        at = clock.millis(),
                    )
                ),
        )
    }

    fun recordCreationPermission(actor: AccountId, target: AccountId, allowed: Boolean) {
        audit.record(
            actor,
            if (allowed) AdministrativeAction.ALLOW_CREATION
            else AdministrativeAction.DENY_CREATION,
            target.value,
            0,
        )
    }

    fun purge(id: String, actor: AccountId, expectedRevision: Int) {
        if (json.readTree(store.document(id)).field("revision").integer() != expectedRevision) {
            throw ServiceFailure(409, "STALE_REVISION")
        }
        audit.record(actor, AdministrativeAction.PURGE, id, expectedRevision)
        store.purge(id, expectedRevision)
    }

    private fun summary(id: String, root: JsonNode, viewer: AccountId): GameSummary =
        try {
            summary(store.read(id), viewer)
        } catch (_: ServiceFailure) {
            val players =
                root["header"]
                    ?.get("players")
                    ?.mapNotNull { player ->
                        runCatching {
                            Player(
                                PlayerId(player.field("id").string()),
                                player.field("name").string(),
                            )
                        }
                            .getOrNull()
                    }
                    .orEmpty()
            GameSummary(
                id,
                root["createdAt"]?.asLong() ?: 0,
                root["revision"]?.asInt() ?: 0,
                GameListStatus.UNAVAILABLE,
                null,
                players,
                false,
            )
        }

    private fun summary(game: StoredGame, viewer: AccountId): GameSummary {
        val state = game.state()
        val seat = game.memberships.entries.singleOrNull { it.value == viewer.value }?.key
        val prompt = state.prompts().singleOrNull()
        val myMove =
            game.status == GameStatus.ACTIVE &&
                seat != null &&
                when (prompt) {
                    is Prompt.SinglePlayer -> prompt.player == seat
                    is Prompt.MultiPlayer ->
                        seat in prompt.players &&
                            seat !in game.pendingBids &&
                            seat !in game.pendingReturns
                    else -> false
                }
        val status =
            when {
                game.status == GameStatus.CANCELLED -> GameListStatus.CANCELLED
                game.status == GameStatus.ABANDONED -> GameListStatus.ABANDONED
                state.stage == Stage.FINISHED -> GameListStatus.FINISHED
                myMove -> GameListStatus.MY_MOVE
                else -> GameListStatus.ONGOING
            }
        return GameSummary(
            game.id,
            game.createdAt,
            game.revision,
            status,
            state.stage,
            game.header.players,
            myMove,
        )
    }

    private fun effectiveText(root: JsonNode): List<String> =
        root.field("revisions").array().fold(emptyList()) { text, revision ->
            val from = revision["replaceFrom"]?.takeUnless(JsonNode::isNull)?.integer()
            if (from != null && from !in 0..text.size) {
                throw ServiceFailure(503, "STORAGE_UNAVAILABLE")
            }
            (from?.let(text::take) ?: text) +
                revision.field("entries").array().map(JsonNode::string)
        }

    private fun parseFilter(value: String?): GameListStatus? =
        if (value == null || value == "all") null
        else
            try {
                GameListStatus.valueOf(value.replace('-', '_').uppercase())
            } catch (_: IllegalArgumentException) {
                throw ServiceFailure(400, "INVALID_GAME_FILTER")
            }

    private fun requireLimit(limit: Int) {
        if (limit !in 1..100) throw ServiceFailure(400, "INVALID_REQUEST")
    }
}
