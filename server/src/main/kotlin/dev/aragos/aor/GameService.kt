package dev.aragos.aor

import dev.aragos.aor.ai.BOT_POLICY_VERSION
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
    val abandonment: AbandonmentView? = null,
    val abandonmentReceipt: String? = null,
    val abandonmentReceipts: List<String> = emptyList(),
    val abandonmentResult: AbandonmentWriteResult? = null,
    /** The acknowledged write's version can differ from a newer state in this response. */
    val planWriteReceipt: PlanWriteReceipt? = null,
    val automation: AutomationView? = null,
)

data class PlanWriteReceipt(val operationId: String, val version: String)

data class PlanningQuote(
    val revision: Int,
    val preview: ActivityPlanPreview,
    val previewToken: String,
)

/** Authenticates players and projects the driver's state for the HTTP API. */
class GameService(
    internal val store: GameStore,
    internal val driver: GameDriver = GameDriver(),
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
        bots: List<AutomatedPlayerRequest> = emptyList(),
    ): CreatedGame {
        val normalized = names.map(String::trim) + bots.map(AutomatedPlayerRequest::name)
        if (names.isEmpty() || names.size + bots.size !in 3..6) {
            throw ServiceFailure(400, "INVALID_GAME")
        }
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
        val tokens =
            players.take(names.size).associate { player ->
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
                .copy(
                    name = name,
                    bots = botSeats(players.drop(names.size), bots),
                    botRunner = BotRunnerState().takeIf { bots.isNotEmpty() },
                )
        )
        return CreatedGame(
            gameId = id,
            name = name,
            links =
                players.take(names.size).map { player ->
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
        bots: List<AutomatedPlayerRequest> = emptyList(),
    ): CreatedGame {
        if (
            players.size + bots.size !in 3..6 ||
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
                } +
                    bots.mapIndexed { index, bot ->
                        Player(PlayerId("p${players.size + index + 1}"), bot.name)
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
                    bots = botSeats(header.players.drop(players.size), bots),
                    botRunner = BotRunnerState().takeIf { bots.isNotEmpty() },
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
                    val reconciled = GameAbandonment.reconcile(current, next)
                    val saved =
                        if (privateWrite) {
                            reconciled.copy(
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
                        } else reconciled
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

    private fun consentReceipt(current: StoredGame, operationId: UUID): Revision? {
        current.revisions
            .firstOrNull { it.abandonment?.operationId == operationId }
            ?.let {
                return it
            }
        val managed = store as? ManagedGameStore ?: return null
        return managed.archives(current.id).asReversed().firstNotNullOfOrNull { name ->
            val root = json.readTree(managed.archivedDocument(current.id, name))
            if (
                root["fileVersion"]?.asInt() != 7 ||
                    root["revisions"]?.any { revision ->
                        revision["abandonment"]?.get("operationId")?.asText() ==
                            operationId.toString()
                    } != true
            )
                null
            else
                GameDocuments.read(current.id, root).revisions.firstOrNull {
                    it.abandonment?.operationId == operationId
                }
        }
    }

    internal fun abandonment(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        operationId: String,
        operation: AbandonmentOperation,
        requestId: String?,
    ): GameSnapshot {
        val stored =
            store.update(id) { current ->
                val player = authenticate(current, access)
                if (
                    !operationId.matches(GameDocuments.GAME_ID_PATTERN) ||
                        (requestId != null && !requestId.matches(GameDocuments.GAME_ID_PATTERN))
                ) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                val operationUuid = UUID.fromString(operationId)
                val prior = consentReceipt(current, operationUuid)
                if (prior != null) {
                    val event =
                        checkNotNull(prior.abandonment) {
                            "A consent receipt requires its audit payload."
                        }
                    if (
                        prior.author != player ||
                            event.operation != operation ||
                            event.baseRevision != expectedRevision ||
                            (if (operation == AbandonmentOperation.PROPOSE) requestId != null
                            else requestId != event.requestId.toString())
                    ) {
                        throw ServiceFailure(409, "OPERATION_ID_REUSED")
                    }
                    return@update current
                }
                if (current.revision != expectedRevision)
                    throw ServiceFailure(409, "STALE_REVISION")
                if (
                    (operation == AbandonmentOperation.PROPOSE) != (requestId == null) ||
                        operation == AbandonmentOperation.CLOSE
                ) {
                    throw ServiceFailure(400, "INVALID_REQUEST")
                }
                val event =
                    AbandonmentEvent(
                        requestId = requestId?.let(UUID::fromString) ?: UUID.randomUUID(),
                        operation = operation,
                        operationId = operationUuid,
                        baseRevision = expectedRevision,
                    )
                if (
                    operation == AbandonmentOperation.AGREE &&
                        current.status == GameStatus.ACTIVE &&
                        current.state().stage != Stage.FINISHED &&
                        GameAbandonment.open(current)?.let {
                            it.requestId == event.requestId && player in it.agreed
                        } == true
                )
                    return@update current
                val next = GameAbandonment.apply(current, player, event)
                if (
                    player == current.header.creatorId &&
                        operation in
                            setOf(
                                AbandonmentOperation.PROPOSE,
                                AbandonmentOperation.AGREE,
                            )
                ) {
                    BotAutomation.agreeToAbandonment(next, event.requestId)
                } else next
            }
        val committed = consentReceipt(stored, UUID.fromString(operationId)) != null
        return snapshot(stored, authenticate(stored, access))
            .copy(
                abandonmentReceipt = operationId.takeIf { committed },
                abandonmentResult =
                    if (committed) AbandonmentWriteResult.COMMITTED
                    else AbandonmentWriteResult.ALREADY_AGREED,
            )
    }

    internal fun rewindTargets(
        id: String,
        access: GameAccess,
        expectedRevision: Int,
        before: Int? = null,
    ): RewindTargets {
        val stored = store.read(id)
        val player = authenticate(stored, access)
        if (stored.revision != expectedRevision) throw ServiceFailure(409, "STALE_REVISION")
        if (stored.undoPolicy != UndoPolicy.LINEAR_GAME)
            throw ServiceFailure(422, "HISTORY_SUFFIX_UNSUPPORTED")
        val targets =
            LinearHistory.targets(stored, player).filter {
                before == null || it.entryCount < before
            }
        val page = targets.take(30)
        return RewindTargets(
            stored.revision,
            page,
            page.lastOrNull()?.entryCount?.takeIf { targets.size > page.size },
        )
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
                    GameAbandonment.reconcile(
                        current,
                        HistoryEdits.edit(current, player, fromEntry, lines),
                    ),
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
            automation = BotAutomation.view(stored, player),
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
                    else
                        it.copy(
                            available = false,
                            fromEntry = null,
                            actions = emptyList(),
                            capability = HistoryCapability.NONE,
                            latestTarget = null,
                        )
                },
            status = stored.status,
            accountId = stored.memberships[player],
            planning = player?.let { ActivityPlans.view(stored, it) },
            abandonment = GameAbandonment.open(stored, player),
            abandonmentReceipts =
                stored.revisions
                    .filter { it.author == player }
                    .mapNotNull { it.abandonment?.operationId?.toString() }
                    .takeLast(16),
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
                    BotAutomation.view(stored, player),
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
            val ids = managed.armedPlanIds(cursor, 100)
            for (id in ids) {
                try {
                    val game = store.read(id)
                    if (game.plans.values.any(SavedActivityPlan::armed)) drainPlans(id)
                } catch (failure: Exception) {
                    onFailure(id, failure)
                }
            }
            cursor = ids.lastOrNull()
        } while (cursor != null)
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

    internal fun authenticate(stored: StoredGame, access: GameAccess): PlayerId =
        viewer(stored, access) ?: throw ServiceFailure(403, "GAME_MEMBERSHIP_REQUIRED")

    private fun botSeats(
        players: List<Player>,
        requests: List<AutomatedPlayerRequest>,
    ): Map<PlayerId, AutomatedPlayer> =
        players.zip(requests).associate { (player, request) ->
            player.id to
                AutomatedPlayer(request.difficulty, BOT_POLICY_VERSION, credentialsRandom.nextInt())
        }

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
