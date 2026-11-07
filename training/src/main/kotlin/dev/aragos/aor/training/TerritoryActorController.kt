package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TokenObservationEncoder

/** Provenance remains outside observations; sealed actors share batchStartNumber, not number. */
internal data class TerritoryDecisionRecord(
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val actorSeed: Int,
    val decision: String,
    val turn: Int,
    val observation: TerritoryObservation,
    val selectedIndex: Int,
    val behaviorLogProbability: Double,
    val behaviorValue: Double,
)

/**
 * Every seat retains its explicit frozen C/P bundle; only offered token/expansion actions learn.
 */
internal class TerritoryActorController(
    private val baseline: PurchaseBaselineInput,
    private val bundle: TerritoryActorBundle,
    private val record: ((TerritoryDecisionRecord) -> Unit)? = null,
) : SimulationController {
    private val continuation = bundle.frozen.controller(baseline)

    override fun controls(decision: String): Boolean =
        continuation.controls(decision) ||
            decision == TerritoryPhase.TOKEN.decision && bundle.token != null ||
            decision == TerritoryPhase.EXPANSION.decision && bundle.expansion != null

    override fun choose(decision: SimulationDecision): String? {
        if (continuation.controls(decision.observation.decision))
            return continuation.choose(decision)
        val phase =
            TerritoryPhase.entries.singleOrNull { it.decision == decision.observation.decision }
                ?: return null
        val policy =
            (if (phase == TerritoryPhase.TOKEN) bundle.token else bundle.expansion)?.model
                ?: return null
        val recommendation =
            BotPolicy.choose(
                decision.observation,
                BotDifficulty.HARD,
                decision.seed,
                baseline.model,
            )
        val seat = PlayerId(decision.observation.playerId)
        val observation =
            when (phase) {
                TerritoryPhase.TOKEN ->
                    TokenObservationEncoder.from(
                        decision.view,
                        seat,
                        decision.observation,
                        recommendation,
                    )
                TerritoryPhase.EXPANSION ->
                    ExpansionObservationEncoder.from(
                        decision.view,
                        seat,
                        decision.observation,
                        recommendation,
                    )
            }
        val seed = TerritoryPolicy.actorSeed(phase, decision.seed)
        val choice = TerritoryPolicy.choose(observation, policy, seed)
        record?.invoke(
            TerritoryDecisionRecord(
                decision.number,
                decision.batchStartNumber,
                decision.seed,
                seed,
                decision.observation.decision,
                requireNotNull(decision.view.turnNumber) {
                    "Territory decisions require the actual turn."
                },
                observation,
                choice.index,
                choice.logProbability,
                choice.value,
            )
        )
        return choice.command
    }
}
