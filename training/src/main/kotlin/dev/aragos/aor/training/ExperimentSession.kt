package dev.aragos.aor.training

import dev.aragos.aor.DieRoll
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameOutcome
import dev.aragos.aor.GameState
import dev.aragos.aor.HandCard
import dev.aragos.aor.Player
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import java.util.concurrent.CancellationException

/**
 * In-memory experiment authority using ordinary production transitions. Sessions stop at resolved
 * player decisions and may retain an entry stream for differential replay checks. Authority state
 * and chance streams belong to the executor; policies receive [observation] instead of [state].
 *
 * A fork copies the actual simulated world. Information-safe planning must construct or sample a
 * permitted world before using this executor; changing its chance seed does not hide existing hands
 * or resample the deck. This RNG is experiment-specific and does not change production randomness.
 */
class ExperimentSession
private constructor(
    initial: GameState,
    private val random: ExperimentChance,
    private val recordedEntries: MutableList<Entry>?,
) {
    constructor(
        initial: GameState,
        seed: Long,
        recordEntries: Boolean = false,
    ) : this(initial, ExperimentChance(seed), if (recordEntries) mutableListOf() else null)

    internal constructor(
        initial: GameState,
        seed: Long,
        recordEntries: Boolean,
        chanceMode: ExperimentChanceMode,
    ) : this(
        initial,
        ExperimentChance(seed, chanceMode),
        if (recordEntries) mutableListOf() else null,
    )

    var state: GameState = initial
        private set

    /** Number of player commands applied by this session, excluding authority outcomes. */
    var decisions: Int = 0
        private set

    /** Executor-owned transitions, including initial configuration/start and chance outcomes. */
    var systemTransitions: Int = 0
        private set

    /** Nanoseconds spent in engine transitions, excluding observation and policy computation. */
    var transitionNanos: Long = 0L
        private set

    /** Recorded entries since construction; forks retain their prefix when recording is enabled. */
    val entries: List<Entry>
        get() = recordedEntries?.toList().orEmpty()

    /**
     * Copies state and counters without serializing them. Omit [seed] to copy the exact current RNG
     * position; provide one to start an independent continuation stream. Branch mutations are
     * local.
     */
    fun fork(seed: Long? = null): ExperimentSession =
        ExperimentSession(
                state,
                if (seed == null) random.copy() else ExperimentChance(seed, random.mode),
                recordedEntries?.toMutableList(),
            )
            .also { branch ->
                branch.decisions = decisions
                branch.systemTransitions = systemTransitions
                branch.transitionNanos = transitionNanos
            }

    /** Returns the whole sealed batch when the current decision has multiple actors. */
    fun actors(): List<PlayerId> =
        when (val prompt = state.prompts().singleOrNull()) {
            is Prompt.SinglePlayer -> listOf(prompt.player)
            is Prompt.MultiPlayer -> prompt.players
            is Prompt.System -> error("Resolve authority outcomes before requesting actors.")
            null -> {
                check(state.stage == Stage.FINISHED) {
                    "An unfinished experiment requires a decision or authority outcome."
                }
                emptyList()
            }
        }

    fun observation(actor: PlayerId): BotObservation = BotObservations.from(state, actor)

    /**
     * Applies exactly one command per pending actor. Parse every command against the same pre-batch
     * state before applying any action, preserving sealed-choice simultaneity. Rejected batches do
     * not partially update the session. Resolve subsequent authority outcomes before returning.
     */
    fun apply(commands: Map<PlayerId, String>, deadlineNanos: Long = Long.MAX_VALUE) {
        checkBudget(deadlineNanos)
        val actors = actors()
        require(actors.isNotEmpty() && commands.keys == actors.toSet()) {
            "An experiment batch must contain exactly the current deciding players."
        }
        val actions = actors.map { actor ->
            val command =
                checkNotNull(commands[actor]) { "Every deciding player requires a command." }
            TextCodec.parseAction(command, state, actor = actor)
        }
        var next = state
        var elapsed = 0L
        for (action in actions) {
            checkBudget(deadlineNanos)
            val started = System.nanoTime()
            next = next.step(action)
            elapsed += System.nanoTime() - started
        }
        state = next
        decisions += actions.size
        transitionNanos += elapsed
        recordedEntries?.addAll(actions)
        resolve(deadlineNanos)
    }

    /** Resolve shuffles, draws, rolls and random trades with this session's owned chance stream. */
    fun resolve(deadlineNanos: Long = Long.MAX_VALUE) {
        repeat(10000) {
            checkBudget(deadlineNanos)
            val need = (state.prompts().singleOrNull() as? Prompt.System)?.need ?: return
            fun stream() = random.forNeed(need, state.turnNumber, state.currentEpoch.number)
            val outcome =
                when (need) {
                    is SystemNeed.Shuffle -> Entry.Shuffle(need.cards.shuffled(stream()))
                    is SystemNeed.Draw -> GameOutcome.Draw(need.player, need.reference, need.card)
                    is SystemNeed.GameRoll -> {
                        val rolls = stream()
                        GameOutcome.Roll(
                            need.kind,
                            List(need.diceCount) { DieRoll(rolls.nextInt(1, 7)) },
                            need.players,
                        )
                    }
                    is SystemNeed.Trade ->
                        GameOutcome.Trade(
                            need.owner,
                            need.recipient,
                            state
                                .playerState(need.owner)
                                .hand
                                .map(HandCard::reference)
                                .random(stream()),
                        )
                }
            stepAuthority(outcome)
        }
        error("Experiment authority must reach a player decision within 10000 transitions.")
    }

    private fun stepAuthority(entry: Entry) {
        val started = System.nanoTime()
        state = state.step(entry)
        transitionNanos += System.nanoTime() - started
        systemTransitions++
        recordedEntries?.add(entry)
    }

    companion object {
        /** Start a complete experiment game and stop at its first player decision. */
        fun start(
            configuration: TrainingConfiguration,
            seed: Long,
            recordEntries: Boolean = false,
        ): ExperimentSession {
            val players =
                (1..configuration.playerCount).map { number ->
                    Player(PlayerId("p$number"), "Experiment $number")
                }
            val owner = players.first().id
            val session =
                ExperimentSession(GameState(GameHeader(players, owner)), seed, recordEntries)
            for (command in configuration.rules.configurationCommands()) {
                session.stepAuthority(Entry.Action(owner, command))
            }
            session.stepAuthority(TextCodec.parseAction("start", session.state, actor = owner))
            session.resolve()
            return session
        }

        private fun checkBudget(deadlineNanos: Long) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
                throw CancellationException("The experiment deadline elapsed or it was canceled.")
            }
        }
    }
}
