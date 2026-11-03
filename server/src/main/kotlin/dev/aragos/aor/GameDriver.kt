package dev.aragos.aor

import java.security.SecureRandom

/** A committed transaction's entries are a delta, not a complete history snapshot. */
data class Revision(
    val id: Int,
    val parent: Int?,
    val author: PlayerId,
    val command: String,
    val entries: List<String>,
    /** Edits retain this many effective entries, then append [entries] as a replacement suffix. */
    val replaceFrom: Int? = null,
    val abandonment: AbandonmentEvent? = null,
    /** Pending request closed by this archived administrative checkpoint. */
    val abandonmentCheckpoint: java.util.UUID? = null,
    /** Present on creation and explicit setup policy commands; later revisions inherit it. */
    val undoPolicy: UndoPolicy? = null,
    /** A new engine checkpoint is authorized by this administrator and archived separately. */
    val adminAuthor: String? = null,
    val administrativeAction: AdministrativeAction? = null,
    /** Administrative audit time, in Unix milliseconds; ordinary engine deltas use zero. */
    val at: Long = 0,
    /**
     * Historical format-upgrade archive; retained for replay and audit, never added to new games.
     */
    val storageArchive: String? = null,
)

data class StoredGame(
    val fileVersion: Int = 7,
    val id: String,
    val header: GameHeader,
    val credentials: Map<PlayerId, String>,
    val revisions: List<Revision>,
    val name: GameName = GameName.DEFAULT,
    /** Unrevealed amounts for the current capital-bid or token-purchase batch. */
    val pendingBids: Map<PlayerId, Int> = emptyMap(),
    /** Opaque per-seat receipts; changed by each successful private write, including withdrawal. */
    val submissionVersions: Map<PlayerId, String> = emptyMap(),
    /** Recent acknowledged private-write IDs per owner; no amounts or other seats are exposed. */
    val submissionReceipts: Map<PlayerId, List<String>> = emptyMap(),
    val plans: Map<PlayerId, SavedActivityPlan> = emptyMap(),
    val planVersions: Map<PlayerId, String> = emptyMap(),
    val planReceipts: Map<PlayerId, List<String>> = emptyMap(),
    val planExecutions: Map<PlayerId, List<PlanExecution>> = emptyMap(),
    /** Managed account UID per seat. Registered games have no transferable seat credentials. */
    val memberships: Map<PlayerId, String> = emptyMap(),
    /** Creation time in milliseconds since the Unix epoch. */
    val createdAt: Long = System.currentTimeMillis(),
    val initialStatus: GameStatus = GameStatus.ACTIVE,
    /** Revisions before this checkpoint are retained in [previousArchive]. */
    val baseRevision: Int = 0,
    val previousArchive: String? = null,
    /** Automated controllers keep ordinary engine seats; absent fields preserve existing saves. */
    val bots: Map<PlayerId, AutomatedPlayer> = emptyMap(),
    /** Durable browser ownership, fencing and reconciliation; never part of engine replay. */
    val botRunner: BotRunnerState? = null,
) {
    val status: GameStatus
        get() =
            when (
                revisions
                    .lastOrNull {
                        it.abandonment?.closure == AbandonmentClosure.UNANIMOUS ||
                            it.administrativeAction in
                                setOf(AdministrativeAction.CANCEL, AdministrativeAction.ABANDON)
                    }
                    ?.let {
                        if (it.abandonment?.closure == AbandonmentClosure.UNANIMOUS)
                            AdministrativeAction.ABANDON
                        else it.administrativeAction
                    }
            ) {
                AdministrativeAction.CANCEL -> GameStatus.CANCELLED
                AdministrativeAction.ABANDON -> GameStatus.ABANDONED
                else -> initialStatus
            }

    val revision: Int
        get() = revisions.last().id

    val undoPolicy: UndoPolicy
        get() =
            checkNotNull(revisions.lastOrNull { it.undoPolicy != null }?.undoPolicy) {
                "Every game must record its undo policy at creation."
            }

    /** Ordinary revisions append deltas; edits replace only their declared effective suffix. */
    fun effectiveEntries(): List<RecordedEntry> =
        revisions.fold(emptyList()) { entries, revision ->
            val retained = revision.replaceFrom?.let(entries::take) ?: entries
            retained +
                revision.entries.mapIndexed { index, text ->
                    RecordedEntry("${revision.id}:$index", text)
                }
        }

    private val cachedState: GameState by lazy {
        replay(header, effectiveEntries().map(RecordedEntry::text))
    }

    fun state(): GameState = cachedState
}

/** Stable identity `revision:index` and canonical engine text in the effective log. */
data class RecordedEntry(val id: String, val text: String)

interface SetupRandom {
    fun shuffle(cards: List<String>): List<String>

    /** Independent unmodified dice, in the request's documented order. */
    fun roll(count: Int): List<DieRoll>
}

class AuthorityRandom : SetupRandom {
    private val random = SecureRandom()

    override fun shuffle(cards: List<String>): List<String> =
        cards.toMutableList().also { shuffled -> shuffled.shuffle(random) }.toList()

    override fun roll(count: Int): List<DieRoll> {
        require(count in 1..6) { "An authority roll requires one through six dice." }
        return List(count) { DieRoll(random.nextInt(6) + 1) }
    }
}

/** Advances the engine and batches private decisions independently of transport and storage. */
class GameDriver(private val random: SetupRandom = AuthorityRandom()) {
    /** Resolve selected variants and opening setup outcomes before publishing the new game. */
    fun create(
        id: String,
        header: GameHeader,
        credentials: Map<PlayerId, String>,
        rules: RuleOptions = RuleOptions(),
        undoPolicy: UndoPolicy = UndoPolicy.REGULAR_TURN,
    ): StoredGame {
        val entries = initialConfiguration(header.creatorId, rules)
        val entry = entries.first()
        entries.fold(GameState(header)) { state, action -> state.step(action) }
        val configured =
            StoredGame(
                id = id,
                header = header,
                credentials = credentials,
                revisions =
                    listOf(
                        Revision(
                            id = 1,
                            parent = null,
                            author = entry.actor,
                            command = entry.command.toText(),
                            entries = entries.map(TextCodec::print),
                            undoPolicy = undoPolicy,
                        )
                    ),
            )
        return submit(configured, header.creatorId, Command.Start)
    }

    fun submit(stored: StoredGame, player: PlayerId, command: Command): StoredGame {
        val state = stored.state()
        return GameAbandonment.reconcile(stored, submit(stored, state, player, command))
    }

    /** Resolve live shorthand after authentication, then record only canonical commands. */
    fun submit(stored: StoredGame, player: PlayerId, text: String): StoredGame {
        val state = stored.state()
        val action = TextCodec.parseAction(text, state, actor = player)
        return GameAbandonment.reconcile(stored, submit(stored, state, player, action.command))
    }

    /**
     * Withdraw only the caller's unrevealed amount. Private changes do not advance the committed
     * revision or enter replay history; the service serializes this with reveal and checks
     * revision.
     */
    fun withdrawBid(stored: StoredGame, player: PlayerId): StoredGame {
        if (player !in stored.pendingBids) throw ServiceFailure(422, "NO_PENDING_BID")
        return stored.copy(pendingBids = stored.pendingBids - player)
    }

    private fun submit(
        stored: StoredGame,
        state: GameState,
        player: PlayerId,
        command: Command,
    ): StoredGame {
        return when (command) {
            is Command.BidCapital ->
                submitSealedDecision(
                    state = state,
                    player = player,
                    value = command.amount,
                    pendingValues = stored.pendingBids,
                    revealCommand = "reveal-capital-bids",
                    createCommand = Command::BidCapital,
                    withPending = { pending -> stored.copy(pendingBids = pending) },
                )
            is Command.BuyTokens ->
                submitSealedDecision(
                    state = state,
                    player = player,
                    value = command.amount,
                    pendingValues = stored.pendingBids,
                    revealCommand = "reveal-token-purchases",
                    createCommand = Command::BuyTokens,
                    withPending = { pending -> stored.copy(pendingBids = pending) },
                )
            else ->
                commit(
                    stored = stored,
                    state = state,
                    player = player,
                    command = command.toText(),
                    actions = listOf(Entry.Action(actor = player, command = command)),
                )
        }
    }

    private fun <T> submitSealedDecision(
        state: GameState,
        player: PlayerId,
        value: T,
        pendingValues: Map<PlayerId, T>,
        revealCommand: String,
        createCommand: (T) -> Command,
        withPending: (Map<PlayerId, T>) -> StoredGame,
    ): StoredGame {
        state.step(Entry.Action(actor = player, command = createCommand(value)))
        val pending = pendingValues + (player to value)
        val required =
            (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players
                ?: error("private submissions require a simultaneous player prompt.")
        if (required.any { participant -> participant !in pending }) {
            return withPending(pending)
        }
        return commit(
            stored = withPending(emptyMap()),
            state = state,
            player = player,
            command = revealCommand,
            actions =
                state.playerIds.filter(required::contains).map { participant ->
                    Entry.Action(
                        actor = participant,
                        command =
                            createCommand(
                                requireNotNull(pending[participant]) {
                                    "Every participant must have submitted a private decision: $participant."
                                }
                            ),
                    )
                },
        )
    }

    private fun commit(
        stored: StoredGame,
        state: GameState,
        player: PlayerId,
        command: String,
        actions: List<Entry.Action>,
    ): StoredGame {
        val stateAfterActions = actions.fold(state) { current, action -> current.step(action) }
        val entries = actions + resolveOutcomes(stateAfterActions)
        val revision =
            Revision(
                id = stored.revision + 1,
                parent = stored.revision,
                author = player,
                command = command,
                entries = entries.map(TextCodec::print),
            )
        return stored.copy(revisions = stored.revisions + revision, submissionVersions = emptyMap())
    }

    private fun resolveOutcomes(state: GameState): List<Entry> = buildList {
        var current = state
        while (true) {
            val need = (current.prompts().singleOrNull() as? Prompt.System)?.need ?: break
            val outcome =
                when (need) {
                    is SystemNeed.Shuffle -> Entry.Shuffle(random.shuffle(need.cards))
                    is SystemNeed.Draw -> GameOutcome.Draw(need.player, need.reference, need.card)
                    is SystemNeed.GameRoll ->
                        GameOutcome.Roll(need.kind, random.roll(need.diceCount), need.players)
                    is SystemNeed.Trade -> {
                        val references =
                            current.playerState(need.owner).hand.map(HandCard::reference)
                        GameOutcome.Trade(
                            need.owner,
                            need.recipient,
                            random.shuffle(references).first(),
                        )
                    }
                }
            current = current.step(outcome)
            add(outcome)
        }
    }
}

/**
 * The creation transaction records every resolved default, independent of future default changes.
 */
internal fun initialConfiguration(
    creator: PlayerId,
    rules: RuleOptions = RuleOptions(),
): List<Entry.Action> =
    rules.configurationCommands().map { command -> Entry.Action(creator, command) }

internal fun requireCompletedTransaction(state: GameState) {
    require(state.prompts().none { prompt -> prompt is Prompt.System }) {
        "A revision cannot stop before required system outcomes have completed."
    }
    require(
        state.stage != Stage.BIDDING ||
            state.players.values.all { player -> player.capitalBid == null }
    ) {
        "private bids may enter history only as a complete, revealed batch."
    }
    require(
        state.stage != Stage.TOKEN_PURCHASE ||
            state.players.values
                .filter { player -> player.misery != MiseryStep.CHAOS }
                .all { player -> player.tokenPurchase == null }
    ) {
        "Private token purchases may enter history only as a complete, revealed batch."
    }
}
