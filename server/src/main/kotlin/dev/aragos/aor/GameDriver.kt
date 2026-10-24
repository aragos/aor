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
    val fileVersion: Int = 3,
    val id: String,
    val header: GameHeader,
    val credentials: Map<PlayerId, String>,
    val revisions: List<Revision>,
    /** Unrevealed amounts for the current capital-bid or token-purchase batch. */
    val pendingBids: Map<PlayerId, Int> = emptyMap(),
    /** Unrevealed hand references selected during improved setup. */
    val pendingReturns: Map<PlayerId, String> = emptyMap(),
) {
    val revision: Int
        get() = revisions.last().id

    /** Every revision contributes entries; replaying only the latest would lose earlier actions. */
    fun state(): GameState = replay(header, revisions.flatMap(Revision::entries))
}

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

/** Advances the engine and batches sealed decisions independently of transport and storage. */
class GameDriver(private val random: SetupRandom = AuthorityRandom()) {
    fun create(
        id: String,
        header: GameHeader,
        credentials: Map<PlayerId, String>,
    ): StoredGame {
        val entries = initialConfiguration(header.creatorId)
        val entry = entries.first()
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
        return when (command) {
            is Command.BidCapital ->
                submitSealedDecision(
                    stored = stored,
                    state = state,
                    player = player,
                    amount = command.amount,
                    revealCommand = "reveal-capital-bids",
                    createCommand = Command::BidCapital,
                )
            is Command.BuyTokens ->
                submitSealedDecision(
                    stored = stored,
                    state = state,
                    player = player,
                    amount = command.amount,
                    revealCommand = "reveal-token-purchases",
                    createCommand = Command::BuyTokens,
                )
            is SetupCommand.ReturnCard -> submitSetupReturn(stored, state, player, command)
            else ->
                commit(
                    stored = stored,
                    state = state,
                    player = player,
                    command = command.toText(),
                    actions =
                        if (command is RuleOptionCommand && command.key == "preset") {
                            command.resolve(state.rules).configurationCommands().map { option ->
                                Entry.Action(actor = player, command = option)
                            }
                        } else {
                            listOf(Entry.Action(actor = player, command = command))
                        },
                )
        }
    }

    private fun submitSetupReturn(
        stored: StoredGame,
        state: GameState,
        player: PlayerId,
        command: SetupCommand.ReturnCard,
    ): StoredGame {
        state.step(Entry.Action(player, command))
        val pending = stored.pendingReturns + (player to command.reference)
        val required =
            (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players
                ?: error("Setup returns require a simultaneous player prompt.")
        if (required.any { participant -> participant !in pending }) {
            return stored.copy(pendingReturns = pending)
        }
        return commit(
            stored = stored.copy(pendingReturns = emptyMap()),
            state = state,
            player = player,
            command = "reveal-setup-returns",
            actions =
                state.playerIds.filter(required::contains).map { participant ->
                    Entry.Action(
                        participant,
                        SetupCommand.ReturnCard(
                            requireNotNull(pending[participant]) {
                                "Every setup participant requires a selected return."
                            }
                        ),
                    )
                },
        )
    }

    private fun submitSealedDecision(
        stored: StoredGame,
        state: GameState,
        player: PlayerId,
        amount: Int,
        revealCommand: String,
        createCommand: (Int) -> Command,
    ): StoredGame {
        state.step(Entry.Action(actor = player, command = createCommand(amount)))
        val pending = stored.pendingBids + (player to amount)
        val required =
            (state.prompts().singleOrNull() as? Prompt.MultiPlayer)?.players
                ?: error("Sealed submissions require a simultaneous player prompt.")
        if (required.any { participant -> participant !in pending }) {
            return stored.copy(pendingBids = pending)
        }
        return commit(
            stored = stored.copy(pendingBids = emptyMap()),
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
                                    "Every participant must have submitted a sealed decision: $participant."
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
internal fun initialConfiguration(creator: PlayerId): List<Entry.Action> =
    (listOf<Command>(Command.SetRebuiltMarkerBonus(RebuiltMarkerBonus.EXCLUDE)) +
            RuleOptions().configurationCommands())
        .map { command -> Entry.Action(creator, command) }
