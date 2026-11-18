package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryPolicy
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenAnchoredPolicy
import dev.aragos.aor.ai.TokenAnchoredResidual
import dev.aragos.aor.ai.TokenObservationEncoder

/** Raw G3 reference encoding and frozen94 anchor evaluation are independent of actor sampling. */
internal class TokenAnchoredController(
    private val rawReference: SimulationController,
    private val qualified: SimulationController,
    private val anchor: TokenFrozenAnchor,
    private val current: TerritoryPolicyModel,
    private val record: ((TokenPairedDecision) -> Unit)? = null,
) : SimulationController {
    private val residual = TokenAnchoredResidual(current, anchor.distribution)

    override fun controls(decision: String): Boolean = qualified.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        if (decision.observation.decision != TerritoryPhase.TOKEN.decision) {
            return qualified.choose(decision)
        }
        val recommended =
            requireNotNull(rawReference.choose(decision)) {
                "Anchored TOKEN encoding requires the unchanged raw G3 reference command."
            }
        val seat = PlayerId(decision.observation.playerId)
        val raw =
            TokenObservationEncoder.from(
                decision.view,
                seat,
                decision.observation,
                recommended,
                TerritoryEncoder.TOKEN_2,
            )
        val observation = residual.observation(decision.view, seat, raw)
        val evaluation =
            TokenAnchoredPolicy.evaluate(
                raw,
                anchor.bundle.models.token,
                anchor.asset.original.models.token,
                requireNotNull(anchor.asset.calibration.selectors[CoordinatedEsHead.TOKEN]) {
                    "The frozen token calibration is required."
                },
                anchor.sampler,
                residual,
                residualObservation = observation,
            )
        val choice = TokenAnchoredPolicy.choose(observation, evaluation, decision.seed)
        record?.invoke(
            TokenPairedDecision(
                decision.number,
                decision.batchStartNumber,
                decision.seed,
                TerritoryPolicy.actorSeed(TerritoryPhase.TOKEN, decision.seed),
                observation,
                evaluation.probabilities,
                evaluation.initialProbabilities,
                choice,
                evaluation.anchorProbabilities,
                evaluation.anchorIndex,
            )
        )
        return choice.command
    }
}
