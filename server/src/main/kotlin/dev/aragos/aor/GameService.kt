package dev.aragos.aor

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
)

data class PromptView(val messageId: String, val players: List<PlayerId>)

/** A read-only quote bound to the committed revision used for its calculation. */
data class TokenPurchaseQuoteResponse(val revision: Int, val quote: TokenPurchaseOfferView)

data class GameSnapshot(
    val gameId: String,
    val revision: Int,
    val viewerId: PlayerId,
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
)

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
            driver.create(
                id = id,
                header = header,
                credentials = tokens.mapValues { (_, token) -> digest(token) },
                rules = rules,
                undoPolicy = undoPolicy,
            )
        )
        return CreatedGame(
            gameId = id,
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
                .copy(memberships = seats.associate { (seat, account) -> seat to account.id.value })
        )
        val name = header.players.single { it.id == creatorId }.name
        return CreatedGame(id, listOf(PlayerLink(creatorId, name, "/games/$id")), registered = true)
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
        return snapshot(stored = stored, player = authenticate(stored, access))
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
                } catch (failure: CardRuleViolation) {
                    throw ServiceFailure(
                        422,
                        failure.diagnostic.code,
                        diagnostic = failure.diagnostic,
                    )
                }
            }
        return snapshot(stored = stored, player = authenticate(stored, access))
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
                HistoryEdits.edit(current, player, fromEntry, lines)
            }
        return snapshot(stored, authenticate(stored, access))
    }

    internal fun getRevision(id: String, access: GameAccess, revision: Int): GameSnapshot {
        val stored = store.read(id)
        val player = authenticate(stored, access)
        if (revision !in (stored.baseRevision + 1)..stored.revision) {
            throw ServiceFailure(404, "REVISION_NOT_FOUND")
        }
        return snapshot(
            stored.copy(
                revisions = stored.revisions.take(revision - stored.baseRevision),
                pendingBids = emptyMap(),
                submissionVersions = emptyMap(),
                submissionReceipts = emptyMap(),
            ),
            player,
        )
    }

    private fun snapshot(stored: StoredGame, player: PlayerId): GameSnapshot {
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
            revision = stored.revision,
            viewerId = player,
            creatorId = stored.header.creatorId,
            game =
                state.viewForPlayer(player).let { original ->
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
                if (stored.status == GameStatus.ACTIVE) {
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
        )
    }

    internal fun read(id: String, access: GameAccess, ifNoneMatch: String?): GameRead {
        val stored = store.read(id)
        val player = authenticate(stored, access)
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
                )
                .joinToString("|")
        val etag = '"' + digest(visibleVersion) + '"'
        return GameRead(etag, if (ifNoneMatch == etag) null else snapshot(stored, player))
    }

    private fun authenticate(stored: StoredGame, access: GameAccess): PlayerId =
        when (access) {
            is GameAccess.Account ->
                stored.memberships.entries.singleOrNull { it.value == access.id.value }?.key
                    ?: throw ServiceFailure(403, "GAME_MEMBERSHIP_REQUIRED")
            is GameAccess.SeatToken -> authenticateSeat(stored, access.token)
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
