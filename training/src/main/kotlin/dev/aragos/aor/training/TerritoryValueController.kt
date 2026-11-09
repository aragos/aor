package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryValuePolicy
import dev.aragos.aor.ai.TokenObservationEncoder

/**
 * Provenance remains outside observations; sealed actors share batchStartNumber, not number.
 * selectedQ is the raw online estimate, not a critic label. behaviorProbability includes explicit
 * epsilon exploration. No lagged target network or hidden successor enters native choice.
 */
internal data class TerritoryValueDecisionRecord(
    val number: Int,
    val batchStartNumber: Int,
    val botSeed: Int,
    val selectorSeed: Int,
    val decision: String,
    val turn: Int,
    val observation: TerritoryObservation,
    val selectedIndex: Int,
    val selectedQ: Double,
    val behaviorProbability: Double,
)

/**
 * Every seat retains its explicit frozen C/P bundle; only offered token/expansion actions learn.
 */
internal class TerritoryValueController(
    private val baseline: PurchaseBaselineInput,
    private val bundle: TerritoryValueBundle,
    private val epsilon: Double,
    private val record: ((TerritoryValueDecisionRecord) -> Unit)? = null,
) : SimulationController {
    private val continuation = bundle.frozen.controller(baseline)

    init {
        require(epsilon.isFinite() && epsilon in 0.0..1.0) {
            "Native territory value exploration epsilon must be finite and within [0,1]."
        }
    }

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
                        TerritoryEncoder.fromVersion(policy.encoderVersion),
                    )
            }
        val seed = TerritoryValuePolicy.selectorSeed(phase, decision.seed)
        val choice = TerritoryValuePolicy.choose(observation, policy, epsilon, seed)
        record?.invoke(
            TerritoryValueDecisionRecord(
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
                choice.selectedQ,
                choice.probability,
            )
        )
        return choice.command
    }
}
