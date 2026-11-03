package dev.aragos.aor

import com.fasterxml.jackson.databind.JsonNode
import java.time.Clock

/** Public list status is derived from engine readiness and the operational lifecycle. */
enum class GameListStatus {
    ONGOING,
    MY_MOVE,
    FINISHED,
    CANCELLED,
    ABANDONED,
    UNAVAILABLE,
}

/** Listing membership is independent from administrative authority to inspect private logs. */
enum class GameListScope {
    OWN,
    OTHER,
    ALL,
}

data class GameSummary(
    val id: String,
    val createdAt: Long,
    val revision: Int,
    val status: GameListStatus,
    val stage: Stage?,
    val players: List<Player>,
    val myMove: Boolean,
    val name: GameName = GameName.DEFAULT,
    /** Seats still required by the public prompt, excluding unrevealed submissions. */
    val actionPlayers: List<PlayerId> = emptyList(),
    val consentResponders: List<PlayerId> = emptyList(),
    val consentResponse: Boolean = false,
    val turnRequired: Boolean = false,
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
    val name: GameName = GameName.DEFAULT,
)

data class LogPage(val games: List<GameLog>, val nextCursor: String?)

data class LogValidation(
    val valid: Boolean,
    val revision: Int,
    val entryCount: Int,
    val stage: Stage,
)

/**
 * Authorized operational changes replay proposed logs before replaceable storage publishes them.
 */
class GameAdministration(
    private val store: ManagedGameStore,
    private val audit: AdministrationAudit,
    private val clock: Clock = Clock.systemUTC(),
    private val transactions: StorageTransactions = FileTransactions,
) {
    fun list(
        viewer: AccountId,
        scope: GameListScope,
        filter: String?,
        after: String?,
        limit: Int,
        accounts: AccountStore? = null,
    ): GamePage {
        requireLimit(limit)
        requireCursor(after)
        return store.listGames(viewer, scope, parseFilter(filter), after, limit, accounts)
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
            GameDirectory.displayName(root),
        )
    }

    fun logs(after: String?, limit: Int): LogPage {
        requireLimit(limit)
        requireCursor(after)
        val ids = store.ids(after, limit + 1)
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
                GameAbandonment.terminal(
                    GameAbandonment.reconcile(
                        current,
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
                        ),
                    )
                )
            }
        return GameDirectory.summary(changed, actor)
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
        val sourceHeader = original.field("header")
        if (
            sourceHeader.field("rulesVersion").string() != header.rulesVersion ||
                sourceHeader.field("dataVersion").string() != header.dataVersion
        ) {
            throw ServiceFailure(422, "HISTORY_IDENTITY_UNVERIFIABLE")
        }
        OutcomeIdentity.validate(header, oldEntries, entries)
        val policy =
            original["undoPolicy"]?.takeIf { it.isTextual }?.let { UndoPolicy.valueOf(it.asText()) }
                ?: UndoPolicy.REGULAR_TURN
        val status =
            original["status"]?.takeIf { it.isTextual }?.let { GameStatus.valueOf(it.asText()) }
                ?: GameStatus.ACTIVE
        val privatePlans =
            if (original["fileVersion"]?.asInt() == 7) {
                GameDocuments.read(id, original)
            } else null
        return ActivityPlans.disarm(
            StoredGame(
                id = id,
                name = GameName(original.field("name").string()),
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
                plans = privatePlans?.plans.orEmpty(),
                planVersions = privatePlans?.planVersions.orEmpty(),
                planReceipts = privatePlans?.planReceipts.orEmpty(),
                planExecutions = privatePlans?.planExecutions.orEmpty(),
                revisions =
                    listOf(
                        Revision(
                            id = expectedRevision + 1,
                            parent = expectedRevision,
                            author = header.creatorId,
                            command = "admin-replace-log",
                            abandonmentCheckpoint =
                                privatePlans?.let { GameAbandonment.open(it)?.requestId },
                            entries = entries,
                            undoPolicy = policy,
                            adminAuthor = actor.value,
                            administrativeAction = AdministrativeAction.UPGRADE,
                            at = clock.millis(),
                        )
                    ),
            ),
            PlanPause.EDITED,
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

    fun purge(id: String, actor: AccountId, expectedRevision: Int) = transactions.transaction {
        if (json.readTree(store.document(id)).field("revision").integer() != expectedRevision) {
            throw ServiceFailure(409, "STALE_REVISION")
        }
        audit.record(actor, AdministrativeAction.PURGE, id, expectedRevision)
        store.purge(id, expectedRevision)
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

    private fun requireCursor(after: String?) {
        if (after != null && !GameDocuments.GAME_ID_PATTERN.matches(after)) {
            throw ServiceFailure(400, "INVALID_REQUEST")
        }
    }
}
