package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy

/** One regenerated purchase decision; likelihood/value are absent for exact baseline imitation. */
internal data class PurchaseDecisionRecord(
    val number: Int,
    val actorSeed: Int,
    val decision: String,
    val turn: Int,
    val observation: PurchaseObservation,
    val selectedIndex: Int,
    val behaviorLogProbability: Double?,
    val behaviorValue: Double?,
)

/** Public terminal labels are separate from all actor inputs and assigned once per whole game. */
internal data class PurchaseTerminal(
    val reward: Double,
    val winShare: Double,
    val rankReward: Double,
    val scores: List<PilotReplayScore>,
    val gameDecisions: Int,
)

/** Atomic game boundary: interrupted games never produce a partially labeled trajectory. */
internal data class PurchaseGameTrajectory(
    val schemaVersion: Int = 1,
    val signature: String,
    val mode: String,
    val encoderVersion: String = PurchaseObservationEncoder.ENCODER_VERSION,
    val actorVersion: String = PurchasePolicy.ACTOR_VERSION,
    val modelSha256: String?,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val decisions: List<PurchaseDecisionRecord>,
    val terminal: PurchaseTerminal,
)

/** Collects whole games using the same shared inference/sampler that evaluates the actor. */
internal class PurchaseCollector(
    private val input: PurchaseCollectionInput,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): PurchaseGameTrajectory {
        require(index in input.seeds.indices) {
            "A purchase trajectory index must belong to its frozen seed bank."
        }
        val seat = input.seat(index)
        val records = mutableListOf<PurchaseDecisionRecord>()
        val controller =
            object : SimulationController {
                override fun controls(decision: String): Boolean =
                    decision in setOf("purchase-shipping", "purchase-advances")

                override fun choose(decision: SimulationDecision): String {
                    val baseline =
                        BotPolicy.choose(
                            decision.observation,
                            BotDifficulty.HARD,
                            decision.seed,
                            input.baseline.model,
                        )
                    val observation =
                        PurchaseObservationEncoder.from(
                            decision.view,
                            PlayerId(decision.observation.playerId),
                            decision.observation,
                            baseline,
                        )
                    val actorSeed = samplingSeed(decision.seed)
                    val chosen =
                        input.policy?.let { PurchasePolicy.choose(observation, it, actorSeed) }
                    val selected = chosen?.index ?: observation.baselineIndex
                    records.add(
                        PurchaseDecisionRecord(
                            decision.number,
                            actorSeed,
                            decision.observation.decision,
                            requireNotNull(decision.view.turnNumber) {
                                "A purchase decision requires its actual game turn."
                            },
                            observation,
                            selected,
                            chosen?.logProbability,
                            chosen?.value,
                        )
                    )
                    return observation.actions[selected].command
                }
            }
        val opponents = input.opponentIndices(index)
        val game =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = input.seeds[index],
                    competitors =
                        opponents.mapIndexed { player, opponent ->
                            Competitor(
                                if (player == seat) input.baseline.model
                                else input.opponents[opponent].model,
                                BotDifficulty.HARD,
                            )
                        },
                    deadlineNanos = deadlineNanos,
                    controllers = mapOf(seat to controller),
                )
        return PurchaseGameTrajectory(
            signature = signature,
            mode = input.mode,
            modelSha256 = input.modelSha256,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = opponents,
            decisions = records.toList(),
            terminal =
                PurchaseTerminal(
                    game.reward(seat),
                    game.winShare(seat),
                    game.rankReward(seat),
                    game.scores.map(PilotReplayScore::from),
                    game.steps,
                ),
        )
    }

    companion object {
        /**
         * A separate deterministic actor stream; neither this seed nor chance RNG enters features.
         */
        internal fun samplingSeed(baselineSeed: Int): Int = baselineSeed xor 0x6a09e667
    }
}
