package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.Prompt
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.BotPolicy

/**
 * Rare-decision curriculum complements complete games. Its prior-based regret is a regression guard
 * during parameter selection, not a claim of optimal strategy or a substitute for win rates.
 */
class DecisionProbes(
    configurations: List<TrainingConfiguration>,
    ledger: CoverageLedger,
) {
    val observations: List<BotObservation> = configurations.flatMap { configuration ->
        BotDecisionCurriculum.positions(configuration.playerCount, configuration.rules).map { state
            ->
            val prompt =
                checkNotNull(state.prompts().singleOrNull() as? Prompt.SinglePlayer) {
                    "A directed probe requires one player decision."
                }
            val observation = BotObservations.from(state, prompt.player)
            for (difficulty in BotDifficulty.entries) {
                val command = BotPolicy.choose(observation, difficulty, 17)
                state.step(Entry.Action(prompt.player, TextCodec.parseCommand(command)))
            }
            ledger.probe(configuration.id, observation)
            observation
        }
    }

    private val probes = observations.map(::RegretProbe)

    fun regret(model: BotModel): Double = probes.map { probe -> probe.regret(model) }.average()
}

/**
 * Fixed prior losses belong to the observation; candidate models only change which move is chosen.
 */
internal class RegretProbe(private val observation: BotObservation) {
    private val regretByCommand =
        observation.candidates
            .associate { candidate ->
                candidate.command to BotPolicy.score(candidate, BotModels.default)
            }
            .let { scores ->
                val best = scores.values.max()
                scores.mapValues { (_, score) -> (best - score).coerceAtLeast(0.0) }
            }

    fun regret(model: BotModel): Double {
        val selected = BotPolicy.chooseCandidate(observation, BotDifficulty.HARD, 17, model)
        return checkNotNull(regretByCommand[selected.command]) {
            "A selected probe candidate requires its fixed prior regret."
        }
    }
}
