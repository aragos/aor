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
import dev.aragos.aor.ScoreView
import dev.aragos.aor.Stage
import dev.aragos.aor.SystemNeed
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy
import java.util.concurrent.CancellationException
import kotlin.random.Random

/** A simulated policy has the same observation-only access as a browser-hosted opponent. */
data class Competitor(val model: BotModel, val difficulty: BotDifficulty = BotDifficulty.NORMAL)

/** Public coverage facts; no actual hidden hand or deck order is retained in training reports. */
data class GameCoverage(
    val decisions: Map<String, Int>,
    val families: Map<String, Int>,
    val playedCards: Set<String>,
    val capitals: Set<String>,
    val epochs: Set<Int>,
    val features: Set<String>,
)

data class SimulatedGame(
    val configuration: String,
    val seed: Int,
    val steps: Int,
    val scores: List<ScoreView>,
    val coverage: GameCoverage,
    val elapsedMillis: Long,
) {
    /** Win share assigns tied winners equal credit and gives an all-Chaos game no winner. */
    fun winShare(seat: Int): Double =
        if (!scores[seat].winner) 0.0 else 1.0 / scores.count(ScoreView::winner)

    /** Normalized rank is secondary to wins and handles eliminated players explicitly. */
    fun rankReward(seat: Int): Double {
        val score = scores[seat].total ?: return 0.0
        val others = scores.filterIndexed { index, _ -> index != seat }
        return others.sumOf { other ->
            val otherScore = other.total
            when {
                otherScore == null || score > otherScore -> 1.0
                score == otherScore -> 0.5
                else -> 0.0
            }
        } / others.size
    }

    fun reward(seat: Int): Double = 0.8 * winShare(seat) + 0.2 * rankReward(seat)
}

/**
 * In-memory authority for offline experiments. It uses the production rules and records sampled
 * outcomes through ordinary engine entries. No policy receives this authority's state or RNG.
 */
class Simulation(private val maxDecisions: Int = 12000) {
    fun play(
        configuration: TrainingConfiguration,
        seed: Int,
        competitors: List<Competitor>,
        deadlineNanos: Long = Long.MAX_VALUE,
        verifyReplay: Boolean = false,
    ): SimulatedGame {
        require(competitors.size == configuration.playerCount) {
            "Every simulated seat requires one competitor."
        }
        val started = System.nanoTime()
        val random = Random(seed)
        val entries = if (verifyReplay) mutableListOf<Entry>() else null
        val players =
            (1..configuration.playerCount).map { number ->
                Player(PlayerId("p$number"), "Training $number")
            }
        var state = GameState(GameHeader(players, players.first().id))
        for (command in configuration.rules.configurationCommands()) {
            val entry = Entry.Action(players.first().id, command)
            entries?.add(entry)
            state = state.step(entry)
        }
        val start = TextCodec.parseAction("start", state, actor = players.first().id)
        entries?.add(start)
        state = state.step(start)
        state = resolve(state, random, entries)
        val decisions = mutableMapOf<String, Int>()
        val families = mutableMapOf<String, Int>()
        val cards = mutableSetOf<String>()
        val capitals = mutableSetOf<String>()
        val epochs = mutableSetOf<Int>()
        val features = mutableSetOf<String>()
        var step = 0
        while (state.stage != Stage.FINISHED) {
            if (Thread.currentThread().isInterrupted || System.nanoTime() >= deadlineNanos) {
                throw CancellationException(
                    "The training run reached its deadline or was canceled."
                )
            }
            check(step < maxDecisions) {
                "Game $seed (${configuration.id}) exceeded $maxDecisions decisions at " +
                    "${state.stage}: ${state.prompts()}."
            }
            val prompt =
                checkNotNull(state.prompts().singleOrNull()) {
                    "A running game requires one decision: ${configuration.id}/$seed."
                }
            val actors =
                when (prompt) {
                    is Prompt.SinglePlayer -> listOf(prompt.player)
                    is Prompt.MultiPlayer -> prompt.players
                    is Prompt.System ->
                        error("Training authority must resolve system requests first.")
                }
            // All sealed choices use the same pre-batch state, before any other amount is applied.
            val actions = actors.map { actor ->
                val seat = players.indexOfFirst { it.id == actor }
                val competitor = competitors[seat]
                val observation = BotObservations.from(state, actor)
                observation.candidates.flatMapTo(features) { it.features.keys }
                val candidate =
                    BotPolicy.chooseCandidate(
                        observation = observation,
                        difficulty = competitor.difficulty,
                        seed = seed xor (step * 65537 + seat * 8191),
                        model = competitor.model,
                    )
                decisions[observation.decision] = (decisions[observation.decision] ?: 0) + 1
                families[candidate.family] = (families[candidate.family] ?: 0) + 1
                val action = TextCodec.parseAction(candidate.command, state, actor = actor)
                if (candidate.command.startsWith("play ")) {
                    val reference = candidate.command.split(' ').getOrNull(1)
                    state
                        .playerState(actor)
                        .hand
                        .firstOrNull { it.reference == reference }
                        ?.let { cards.add(it.definition.id) }
                }
                action
            }
            for (action in actions) {
                try {
                    entries?.add(action)
                    state = state.step(action)
                } catch (failure: Exception) {
                    throw IllegalStateException(
                        "Rejected ${action.toText()} in ${configuration.id}/$seed at " +
                            "${state.stage}: ${state.prompts()}.",
                        failure,
                    )
                }
                step++
            }
            state = resolve(state, random, entries)
            state.players.values.mapNotNullTo(capitals) { it.capital }
            epochs.add(state.currentEpoch.number)
        }
        if (entries != null) {
            val replayed =
                entries.fold(GameState(state.header)) { current, entry ->
                    current.step(TextCodec.parse(entry.toText()))
                }
            check(replayed == state) { "A generated game must replay to the identical state." }
        }
        return SimulatedGame(
            configuration = configuration.id,
            seed = seed,
            steps = step,
            scores = state.publicView().scores,
            coverage =
                GameCoverage(
                    decisions = decisions.toSortedMap(),
                    families = families.toSortedMap(),
                    playedCards = cards.toSortedSet(),
                    capitals = capitals.toSortedSet(),
                    epochs = epochs.toSortedSet(),
                    features = features.toSortedSet(),
                ),
            elapsedMillis = (System.nanoTime() - started) / 1_000_000,
        )
    }

    private fun resolve(
        initial: GameState,
        random: Random,
        entries: MutableList<Entry>?,
    ): GameState {
        var state = initial
        repeat(10000) {
            val need = (state.prompts().singleOrNull() as? Prompt.System)?.need ?: return state
            val outcome =
                when (need) {
                    is SystemNeed.Shuffle -> Entry.Shuffle(need.cards.shuffled(random))
                    is SystemNeed.Draw -> GameOutcome.Draw(need.player, need.reference, need.card)
                    is SystemNeed.GameRoll ->
                        GameOutcome.Roll(
                            need.kind,
                            List(need.diceCount) { DieRoll(random.nextInt(1, 7)) },
                            need.players,
                        )
                    is SystemNeed.Trade ->
                        GameOutcome.Trade(
                            need.owner,
                            need.recipient,
                            state
                                .playerState(need.owner)
                                .hand
                                .map(HandCard::reference)
                                .random(random),
                        )
                }
            entries?.add(outcome)
            state = state.step(outcome)
        }
        error("A training authority sequence must reach a player decision.")
    }
}
