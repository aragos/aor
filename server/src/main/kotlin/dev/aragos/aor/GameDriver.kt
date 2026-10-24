package dev.aragos.aor

import java.security.SecureRandom

/** A committed transaction's entries are a delta, not a complete history snapshot. */
data class Revision(
    val id: Int,
    val parent: Int?,
    val author: PlayerId,
    val command: String,
    val entries: List<String>,
)

data class StoredGame(
    val fileVersion: Int = 2,
    val id: String,
    val header: GameHeader,
    val credentials: Map<PlayerId, String>,
    val revisions: List<Revision>,
    /** Unrevealed amounts for the current capital-bid or token-purchase batch. */
    val pendingBids: Map<PlayerId, Int> = emptyMap(),
) {
    val revision: Int
        get() = revisions.last().id

    /** Every revision contributes entries; replaying only the latest would lose earlier actions. */
    fun state(): GameState = replay(header, revisions.flatMap(Revision::entries))
}

interface SetupRandom {
    fun shuffle(cards: List<String>): List<String>

    fun dice(players: List<PlayerId>): Map<PlayerId, DieRoll>
}

class AuthorityRandom : SetupRandom {
    private val random = SecureRandom()

    override fun shuffle(cards: List<String>): List<String> =
        cards.toMutableList().also { shuffled -> shuffled.shuffle(random) }.toList()

    override fun dice(players: List<PlayerId>): Map<PlayerId, DieRoll> = players.associateWith {
        DieRoll(random.nextInt(6) + 1)
    }
}

/** Advances the engine and batches sealed decisions independently of transport and storage. */
class GameDriver(private val random: SetupRandom = AuthorityRandom()) {
    fun create(
        id: String,
        header: GameHeader,
        credentials: Map<PlayerId, String>,
        epochs: Int,
    ): StoredGame {
        val entry = Entry.Action(actor = header.creatorId, command = Command.SetEpochs(epochs))
        val entries =
            listOf(
                entry,
                Entry.Action(
                    actor = header.creatorId,
                    command = Command.SetRebuiltMarkerBonus(RebuiltMarkerBonus.EXCLUDE),
                ),
            )
        entries.fold(GameState(header)) { state, action -> state.step(action) }
        return StoredGame(
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
                    )
                ),
        )
    }

    fun submit(stored: StoredGame, player: PlayerId, command: Command): StoredGame {
        val state = stored.state()
        state.step(Entry.Action(actor = player, command = command))
        return when (command) {
            is Command.BidCapital ->
                submitSealedDecision(
                    stored = stored,
                    state = state,
                    player = player,
                    amount = command.amount,
                    revealCommand = "reveal-capital-bids",
                    createCommand = { amount -> Command.BidCapital(amount) },
                )
            is Command.BuyTokens ->
                submitSealedDecision(
                    stored = stored,
                    state = state,
                    player = player,
                    amount = command.amount,
                    revealCommand = "reveal-token-purchases",
                    createCommand = { amount -> Command.BuyTokens(amount) },
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

    private fun submitSealedDecision(
        stored: StoredGame,
        state: GameState,
        player: PlayerId,
        amount: Int,
        revealCommand: String,
        createCommand: (Int) -> Command,
    ): StoredGame {
        val pending = stored.pendingBids + (player to amount)
        if (pending.size < stored.header.players.size) return stored.copy(pendingBids = pending)
        return commit(
            stored = stored.copy(pendingBids = emptyMap()),
            state = state,
            player = player,
            command = revealCommand,
            actions =
                stored.header.players.map { registered ->
                    Entry.Action(
                        actor = registered.id,
                        command =
                            createCommand(
                                requireNotNull(pending[registered.id]) {
                                    "Every participant must have submitted a sealed decision: ${registered.id}."
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
        return stored.copy(revisions = stored.revisions + revision)
    }

    private fun resolveOutcomes(state: GameState): List<Entry> = buildList {
        var current = state
        while (true) {
            val need = (current.prompts().singleOrNull() as? Prompt.System)?.need ?: break
            val outcome =
                when (need) {
                    SystemNeed.Shuffle ->
                        Entry.Shuffle(random.shuffle(CardData.epochOne.map(CardDefinition::id)))
                    is SystemNeed.Deal ->
                        Entry.Deal(
                            player = need.player,
                            reference = need.reference,
                            card = current.deck.first(),
                        )
                    is SystemNeed.CapitalTie -> Entry.CapitalRoll(random.dice(need.players))
                }
            current = current.step(outcome)
            add(outcome)
        }
    }
}
