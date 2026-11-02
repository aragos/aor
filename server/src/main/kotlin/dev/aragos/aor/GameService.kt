package dev.aragos.aor

import com.fasterxml.jackson.databind.node.ObjectNode
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class ServiceFailure(
    val status: Int,
    val code: String,
    val entryIndex: Int? = null,
    val diagnostic: RuleDiagnostic? = null,
) : Exception(code)

data class PlayerLink(val id: PlayerId, val name: String, val path: String)

data class CreatedGame(
    val gameId: String,
    val links: List<PlayerLink>,
    val registered: Boolean = false,
    val name: GameName = GameName.DEFAULT,
)

data class PromptView(val messageId: String, val players: List<PlayerId>)

/** A read-only quote bound to the committed revision used for its calculation. */
data class TokenPurchaseQuoteResponse(val revision: Int, val quote: TokenPurchaseOfferView)

data class GameSnapshot(
    val gameId: String,
    val name: GameName,
    val revision: Int,
    /** Null for an authenticated spectator, who has no private seat or action permissions. */
    val viewerId: PlayerId?,
    val creatorId: PlayerId,
    val game: GameView,
    val prompt: PromptView?,
    val legalChoices: LegalChoices,
    val submitted: List<PlayerId>,
    /** The viewer's unrevealed capital bid or signed token-purchase amount, when submitted. */
    val pendingBid: Int?,
    /** Only the viewer's private-write receipt; unrelated seats never change this precondition. */
    val submissionVersion: String?,
    /**
     * Bounded reconciliation receipts for this owner only; an absent ID leaves the outcome unknown.
     */
    val submissionReceipts: List<String>,
    val history: List<String>,
    val events: List<HistoryEventView>,
    val undo: UndoView,
    val status: GameStatus,
    val accountId: String?,
    val planning: ActivityPlanningView? = null,
    /** The acknowledged write's version can differ from a newer state in this response. */
    val planWriteReceipt: PlanWriteReceipt? = null,
)

data class PlanWriteReceipt(val operationId: String, val version: String)

data class PlanningQuote(
    val revision: Int,
    val preview: ActivityPlanPreview,
    val previewToken: String,
)

data class PlanningUpgradeQuote(val revision: Int, val sourceHash: String, val fileVersion: Int = 7)

/** Authenticates players and projects the driver's state for the HTTP API. */
class GameService(
    internal val store: GameStore,
    private val driver: GameDriver = GameDriver(),
    private val accounts: AccountStore? = null,
) {
    private val credentialsRandom = SecureRandom()

    internal fun forAccounts(accounts: AccountStore): GameService =
        GameService(store, driver, accounts)

    fun create(
        names: List<String>,
        rules: RuleOptions = RuleOptions(),
        undoPolicy: UndoPolicy = UndoPolicy.REGULAR_TURN,
        name: GameName = GameName.DEFAULT,
    ): CreatedGame {
        val normalized = names.map(String::trim)
        if (normalized.map(String::lowercase).distinct().size != normalized.size) {
            throw ServiceFailure(400, "INVALID_GAME")
        }
        val header =
            try {
                GameHeader(
                    creatorId = PlayerId("p1"),
                    players =
                        normalized.mapIndexed { index, name ->
                            Player(id = PlayerId("p${index + 1}"), name = name)
                        },
                )
            } catch (_: IllegalArgumentException) {
                throw ServiceFailure(400, "INVALID_GAME")
            }
        val id = UUID.randomUUID().toString()
        val players = header.players
        val tokens = players.associate { player ->
            player.id to
                Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(ByteArray(32).also(credentialsRandom::nextBytes))
        }
        store.create(
            driver
                .create(
                    id = id,
                    header = header,
                    credentials = tokens.mapValues { (_, token) -> digest(token) },
                    rules = rules,
                    undoPolicy = undoPolicy,
                )
                .copy(name = name)
        )
        return CreatedGame(
            gameId = id,
            name = name,
            links =
                players.map { player ->
                    val token =
                        requireNotNull(tokens[player.id]) {
                            "Every registered player requires an access token."
                        }
                    PlayerLink(
                        id = player.id,
                        name = player.name,
                        path = "/games/$id#token=$token",
                    )
                },
        )
    }

    /** Stable account membership binds each authenticated UID to an engine seat. */
    fun createRegistered(
        players: List<RegisteredAccount>,
        creator: AccountId,
        rules: RuleOptions,
        policy: UndoPolicy,
        name: GameName = GameName.DEFAULT,
    ): CreatedGame {
        if (
            players.size !in 3..6 ||
                players.map(RegisteredAccount::id).distinct().size != players.size ||
                players.none { it.id == creator }
        ) {
            throw ServiceFailure(400, "INVALID_GAME")
        }
        val seats = players.mapIndexed { index, account -> PlayerId("p${index + 1}") to account }
        val creatorId =
            checkNotNull(seats.singleOrNull { it.second.id == creator }?.first) {
                "The creator must have a registered seat."
            }
        val header =
            GameHeader(
                seats.map { (id, account) ->
                    Player(id, "@${account.handle.value}")
                },
                creatorId,
            )
        val id = UUID.randomUUID().toString()
        store.create(
            driver
                .create(id, header, emptyMap(), rules, policy)
                .copy(
                    name = name,
                    memberships = seats.associate { (seat, account) -> seat to account.id.value },
                )
        )
        val creatorName = header.players.single { it.id == creatorId }.name
        return CreatedGame(
            id,
            listOf(PlayerLink(creatorId, creatorName, "/games/$id")),
            registered = true,
            name = name,
        )
    }

    fun get(id: String, token: String): GameSnapshot = get(id, GameAccess.SeatToken(token))

    fun submit(
        id: String,
        token: String,
        expectedRevision: Int,
        text: String,
        expectedSubmissionVersion: String? = null,
    ): GameSnapshot =
        submit(id, GameAccess.SeatToken(token), expectedRevision, text, expectedSubmissionVersion)

    fun quoteTokenPurchase(
        id: String,
        token: String,
        expectedRevision: Int,
        amount: Int,
    ): TokenPurchaseQuoteResponse =
        quoteTokenPurchase(id, GameAccess.SeatToken(token), expectedRevision, amount)

    fun editHistory(
        id: String,
        token: String,
        expectedRevision: Int,
        fromEntry: Int,
        lines: List<String>,
    ): GameSnapshot =
        editHistory(id, GameAccess.SeatToken(token), expectedRevision, fromEntry, lines)

    fun getRevision(id: String, token: String, revision: Int): GameSnapshot =
        getRevision(id, GameAccess.SeatToken(token), revision)

    internal fun get(id: String, access: GameAccess): GameSnapshot {
        val stored = store.read(id)
        return snapshot(stored = stored, player = viewer(stored, access))
    }

    internal fun quoteTokenPurchase(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        amount: Int,
    ): TokenPurchaseQuoteResponse {
        val stored = store.read(id)
        val player = authenticate(stored, access)
        if (stored.revision != expectedRevision) {
            throw ServiceFailure(409, "STALE_REVISION")
        }
        if (stored.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
        return TokenPurchaseQuoteResponse(
            stored.revision,
            TokenPurchaseProjection.quote(stored.state(), player, amount),
        )
    }

    internal fun submit(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        text: String,
        expectedSubmissionVersion: String? = null,
        operationId: String? = null,
    ): GameSnapshot {
        val stored =
            store.update(id) { current ->
                val player = authenticate(current, access)
                if (current.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
                if (current.revision != expectedRevision) {
                    throw ServiceFailure(409, "STALE_REVISION")
                }
                val administrative =
                    text.trim().let { command ->
                        command == "withdraw-bid" ||
                            command == "undo" ||
                            command.startsWith("undo ") ||
                            command.startsWith("set-undo-policy ")
                    }
                val command =
                    if (administrative) null
                    else TextCodec.parseAction(text, current.state(), actor = player).command
                val privateWrite =
                    text.trim() == "withdraw-bid" ||
                        command is Command.BidCapital ||
                        command is Command.BuyTokens
                if (
                    privateWrite && expectedSubmissionVersion != current.submissionVersions[player]
                ) {
                    throw ServiceFailure(409, "STALE_SUBMISSION")
                }
                if (operationId != null && !operationId.matches(GameDocuments.GAME_ID_PATTERN)) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                if (
                    privateWrite &&
                        operationId != null &&
                        operationId in current.submissionReceipts[player].orEmpty()
                ) {
                    throw ServiceFailure(409, "OPERATION_ALREADY_COMPLETED")
                }
                try {
                    val next =
                        when {
                            text.trim() == "withdraw-bid" -> driver.withdrawBid(current, player)
                            text.trim() == "undo" -> HistoryEdits.undo(current, player)
                            text.trim().startsWith("undo ") ->
                                HistoryEdits.undo(
                                    current,
                                    player,
                                    text.trim().substringAfter(' ').toIntOrNull()
                                        ?: throw ServiceFailure(400, "INVALID_HISTORY_EDIT"),
                                )
                            text.trim().startsWith("set-undo-policy ") ->
                                HistoryEdits.configure(
                                    current,
                                    player,
                                    UndoPolicy.parse(text.trim().substringAfter(' ')),
                                )
                            else ->
                                driver.submit(
                                    current,
                                    player,
                                    requireNotNull(command) {
                                        "An engine submission requires its parsed command."
                                    },
                                )
                        }
                    val saved =
                        if (privateWrite) {
                            next.copy(
                                submissionVersions =
                                    if (next.revision == current.revision)
                                        next.submissionVersions +
                                            (player to UUID.randomUUID().toString())
                                    else next.submissionVersions,
                                submissionReceipts =
                                    if (operationId != null)
                                        next.submissionReceipts +
                                            (player to
                                                (current.submissionReceipts[player].orEmpty() +
                                                        operationId)
                                                    .takeLast(16))
                                    else next.submissionReceipts,
                            )
                        } else next
                    if (text.trim() == "undo" || text.trim().startsWith("undo ")) {
                        ActivityPlans.disarm(saved, PlanPause.EDITED)
                    } else ActivityPlans.afterManual(saved, player)
                } catch (failure: CardRuleViolation) {
                    throw ServiceFailure(
                        422,
                        failure.diagnostic.code,
                        diagnostic = failure.diagnostic,
                    )
                }
            }
        val result = advancePlans(id) ?: stored
        return snapshot(result, authenticate(result, access))
    }

    internal fun editHistory(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        fromEntry: Int,
        lines: List<String>,
    ): GameSnapshot {
        val stored =
            store.update(id) { current ->
                val player = authenticate(current, access)
                if (current.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
                if (current.revision != expectedRevision) {
                    throw ServiceFailure(409, "STALE_REVISION")
                }
                ActivityPlans.disarm(
                    HistoryEdits.edit(current, player, fromEntry, lines),
                    PlanPause.EDITED,
                )
            }
        return snapshot(stored, authenticate(stored, access))
    }

    internal fun getRevision(id: String, access: GameAccess, revision: Int): GameSnapshot {
        val stored = store.read(id)
        val player = viewer(stored, access)
        if (revision !in (stored.baseRevision + 1)..stored.revision) {
            throw ServiceFailure(404, "REVISION_NOT_FOUND")
        }
        return snapshot(
            stored.copy(
                revisions = stored.revisions.take(revision - stored.baseRevision),
                pendingBids = emptyMap(),
                submissionVersions = emptyMap(),
                submissionReceipts = emptyMap(),
                plans = emptyMap(),
                planVersions = emptyMap(),
                planReceipts = emptyMap(),
                planExecutions = emptyMap(),
            ),
            player,
        )
    }

    private fun snapshot(stored: StoredGame, player: PlayerId?): GameSnapshot {
        val history = HistoryProjection.forPlayer(stored, player)
        val state = history.state
        val prompt =
            when (val current = state.prompts().singleOrNull()) {
                is Prompt.SinglePlayer ->
                    PromptView(
                        messageId = "prompt.${current.choice}",
                        players = listOf(current.player),
                    )
                is Prompt.MultiPlayer ->
                    PromptView(
                        messageId = "prompt.${current.choice}",
                        players =
                            current.players.filterNot { participant ->
                                participant in stored.pendingBids
                            },
                    )
                else -> null
            }
        return GameSnapshot(
            gameId = stored.id,
            name = stored.name,
            revision = stored.revision,
            viewerId = player,
            creatorId = stored.header.creatorId,
            game =
                (player?.let(state::viewForPlayer) ?: state.publicView()).let { original ->
                    val view =
                        original.copy(
                            players =
                                original.players.map { participant ->
                                    participant.copy(
                                        name =
                                            accounts?.playerName(
                                                stored.memberships[participant.id],
                                                participant.name,
                                            ) ?: participant.name
                                    )
                                }
                        )
                    if (stored.status == GameStatus.ACTIVE) {
                        view
                    } else {
                        view.copy(
                            expansionOffers = emptyList(),
                            expansionInspections = emptyList(),
                            cardOffers = emptyList(),
                            advanceOffers = emptyList(),
                            tokenPurchaseOffers = emptyList(),
                            shippingOffer = null,
                            reliefOffers = emptyList(),
                            patronageOffers = emptyList(),
                            adjustmentOffers = emptyList(),
                            cardPurchaseOffer = null,
                        )
                    }
                },
            prompt = prompt.takeIf { stored.status == GameStatus.ACTIVE },
            legalChoices =
                if (player != null && stored.status == GameStatus.ACTIVE) {
                    state.legalChoices(player).let { choices ->
                        if (player in stored.pendingBids) {
                            choices.copy(commands = choices.commands + "withdraw-bid")
                        } else choices
                    }
                } else {
                    LegalChoices()
                },
            submitted =
                state.playerIds.filter { participant ->
                    participant in stored.pendingBids
                },
            pendingBid = stored.pendingBids[player],
            submissionVersion = stored.submissionVersions[player],
            submissionReceipts = stored.submissionReceipts[player].orEmpty(),
            history = history.lines,
            events = history.events,
            undo =
                HistoryEdits.view(stored, player).let {
                    if (stored.status == GameStatus.ACTIVE) it
                    else it.copy(available = false, fromEntry = null, actions = emptyList())
                },
            status = stored.status,
            accountId = stored.memberships[player],
            planning = player?.let { ActivityPlans.view(stored, it) },
        )
    }

    internal fun read(id: String, access: GameAccess, ifNoneMatch: String?): GameRead {
        val stored = store.read(id)
        val player = viewer(stored, access)
        val visibleVersion =
            listOf(
                    stored.id,
                    stored.revision,
                    stored.status,
                    player,
                    stored.pendingBids[player],
                    stored.submissionVersions[player],
                    stored.submissionReceipts[player].orEmpty(),
                    stored.pendingBids.keys.sortedBy(PlayerId::value),
                    stored.planVersions[player],
                    stored.planReceipts[player].orEmpty(),
                    stored.planExecutions[player].orEmpty(),
                )
                .joinToString("|")
        val etag = '"' + digest(visibleVersion) + '"'
        return GameRead(etag, if (ifNoneMatch == etag) null else snapshot(stored, player))
    }

    internal fun previewPlan(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        actions: List<PlannedAction>,
    ): PlanningQuote {
        val stored = store.read(id)
        val owner = authenticate(stored, access)
        if (stored.revision != expectedRevision) throw ServiceFailure(409, "STALE_REVISION")
        if (stored.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
        val preview = ActivityPlanning.preview(stored.state(), owner, actions)
        return PlanningQuote(stored.revision, preview, ActivityPlans.signature(preview))
    }

    internal fun writePlan(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        expectedVersion: String?,
        operationId: String,
        operation: PlanOperation,
        actions: List<PlannedAction> = emptyList(),
        previewToken: String? = null,
    ): GameSnapshot {
        if (!operationId.matches(GameDocuments.GAME_ID_PATTERN)) {
            throw ServiceFailure(400, "INVALID_REQUEST")
        }
        var receipt: PlanWriteReceipt? = null
        val stored =
            store.update(id) { current ->
                val owner = authenticate(current, access)
                if (current.status != GameStatus.ACTIVE) throw ServiceFailure(422, "GAME_INACTIVE")
                if (operationId in current.planReceipts[owner].orEmpty()) return@update current
                val reducingAuthorization =
                    operation in setOf(PlanOperation.DISARM, PlanOperation.DELETE)
                if (
                    expectedRevision < 1 ||
                        expectedRevision > current.revision ||
                        (!reducingAuthorization && current.revision != expectedRevision)
                ) {
                    throw ServiceFailure(409, "STALE_REVISION")
                }
                if (current.planVersions[owner] != expectedVersion) {
                    throw ServiceFailure(409, "STALE_PLAN")
                }
                ActivityPlans.write(
                        current,
                        owner,
                        operation,
                        actions,
                        previewToken,
                        driver,
                        operationId,
                    )
                    .let { next ->
                        receipt =
                            PlanWriteReceipt(
                                operationId,
                                requireNotNull(next.planVersions[owner]) {
                                    "A completed private plan write requires its owner version."
                                },
                            )
                        next.copy(
                            planReceipts =
                                next.planReceipts +
                                    (owner to
                                        (current.planReceipts[owner].orEmpty() + operationId)
                                            .takeLast(16))
                        )
                    }
            }
        val result = advancePlans(id) ?: stored
        return snapshot(result, authenticate(result, access)).copy(planWriteReceipt = receipt)
    }

    /** Bounded per-actor transactions; authorization itself is the durable work signal. */
    internal fun drainPlans(id: String): StoredGame? {
        var result: StoredGame? = null
        repeat(6) {
            val before = store.read(id)
            if (before.status != GameStatus.ACTIVE) return result
            val prompt =
                before.state().prompts().singleOrNull() as? Prompt.SinglePlayer ?: return result
            val owner = prompt.player
            if (prompt.choice == "renaissance" || before.plans[owner]?.armed != true) return result
            result =
                store.update(id) { current ->
                    if (current.planVersions[owner] != before.planVersions[owner]) current
                    else ActivityPlans.execute(current, owner, driver)
                }
        }
        return result
    }

    private fun advancePlans(id: String): StoredGame? =
        try {
            drainPlans(id)
        } catch (failure: Exception) {
            // The triggering mutation is already durable. Recovery retries the unconsumed plan;
            // do not report its parent's acknowledged operation as though it failed publication.
            org.slf4j.LoggerFactory.getLogger(GameService::class.java)
                .warn(
                    "Deferred plan execution for {} ({})",
                    id,
                    failure.javaClass.simpleName,
                )
            null
        }

    /**
     * Called by the application worker, including after startup; GET requests never execute plans.
     */
    internal fun recoverPlans(onFailure: (String, Exception) -> Unit) {
        val managed = store as? ManagedGameStore ?: return
        var cursor: String? = null
        do {
            val ids = managed.ids(cursor, 100)
            for (id in ids) {
                try {
                    val game = store.read(id)
                    if (game.plans.values.any(SavedActivityPlan::armed)) drainPlans(id)
                } catch (failure: Exception) {
                    if (failure !is ServiceFailure || failure.code != "PLANNING_UPGRADE_REQUIRED") {
                        onFailure(id, failure)
                    }
                }
            }
            cursor = ids.lastOrNull()
        } while (cursor != null)
    }

    internal fun planningUpgrade(
        id: String,
        access: GameAccess,
        expectedRevision: Int? = null,
        sourceHash: String? = null,
    ): PlanningUpgradeQuote {
        val managed = store as? ManagedGameStore ?: throw ServiceFailure(422, "UPGRADE_UNAVAILABLE")
        fun converted(contents: String): StoredGame {
            val root =
                json.readTree(contents) as? ObjectNode
                    ?: throw ServiceFailure(422, "INVALID_UPGRADE")
            if (root["fileVersion"]?.asInt() != 6) throw ServiceFailure(422, "INVALID_UPGRADE")
            root.put("fileVersion", 7)
            root.remove(listOf("plans", "planVersions", "planReceipts", "planExecutions"))
            val game = GameDocuments.read(id, root)
            if (authenticate(game, access) != game.header.creatorId) {
                throw ServiceFailure(403, "UPGRADE_CREATOR_REQUIRED")
            }
            return game
        }
        val contents = managed.document(id)
        val source = converted(contents)
        val hash = planDigest(contents)
        if (expectedRevision == null) return PlanningUpgradeQuote(source.revision, hash)
        if (sourceHash != hash) throw ServiceFailure(409, "STALE_UPGRADE")
        managed.checkpoint(id, expectedRevision) { locked, archive ->
            if (planDigest(locked) != sourceHash) throw ServiceFailure(409, "STALE_UPGRADE")
            val original = converted(locked)
            original.copy(
                revisions =
                    original.revisions +
                        Revision(
                            id = original.revision + 1,
                            parent = original.revision,
                            author = original.header.creatorId,
                            command = "upgrade-planning",
                            entries = emptyList(),
                            at = System.currentTimeMillis(),
                            storageArchive = archive,
                        )
            )
        }
        return PlanningUpgradeQuote(expectedRevision + 1, hash)
    }

    /**
     * Account reads may observe public state; seat tokens continue to require their owning seat.
     */
    private fun viewer(stored: StoredGame, access: GameAccess): PlayerId? =
        when (access) {
            is GameAccess.Account ->
                stored.memberships.entries.singleOrNull { it.value == access.id.value }?.key
            is GameAccess.SeatToken -> authenticateSeat(stored, access.token)
        }

    private fun authenticate(stored: StoredGame, access: GameAccess): PlayerId =
        viewer(stored, access) ?: throw ServiceFailure(403, "GAME_MEMBERSHIP_REQUIRED")

    private fun authenticateSeat(stored: StoredGame, token: String): PlayerId {
        if (token.length !in 20..100) throw ServiceFailure(401, "UNAUTHORIZED")
        val hash = digest(token).toByteArray()
        return stored.credentials.entries
            .firstOrNull { (_, credential) ->
                MessageDigest.isEqual(credential.toByteArray(), hash)
            }
            ?.key ?: throw ServiceFailure(401, "UNAUTHORIZED")
    }
}

private fun digest(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { byte ->
        "%02x".format(byte)
    }
