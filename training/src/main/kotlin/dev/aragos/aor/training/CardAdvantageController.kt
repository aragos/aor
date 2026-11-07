package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotPolicy
import dev.aragos.aor.ai.CardAdvantageModel
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardFrequencyControl
import dev.aragos.aor.ai.CardFrequencyModel
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardObservationEncoder

/** Repeated experimental card selector; purchases retain the exact independently frozen actor. */
internal class CardAdvantageController(
    private val environment: CardAdvantageEnvironment,
    private val model: CardAdvantageModel?,
    private val frequency: CardFrequencyModel? = null,
) : SimulationController {
    private val purchase =
        PurchaseActorController(environment.baseline.model, environment.purchase.model)

    init {
        require(model == null || frequency == null) {
            "A card controller has one declared policy, not two."
        }
    }

    override fun controls(decision: String): Boolean =
        decision == "play-cards" || purchase.controls(decision)

    override fun choose(decision: SimulationDecision): String {
        if (purchase.controls(decision.observation.decision)) return purchase.choose(decision)
        val baseline = recommendation(decision, environment)
        if (
            model == null && frequency == null ||
                decision.view.stage != Stage.PLAY_CARDS ||
                decision.observation.decision != "play-cards"
        )
            return baseline
        val observation = observation(decision, baseline)
        return if (model != null) {
            CardAdvantagePolicy.choose(observation, model, decision.seed).command
        } else {
            CardFrequencyControl.choose(
                    observation,
                    requireNotNull(frequency) {
                        "A frequency controller requires its frozen model."
                    },
                    decision.seed,
                )
                .command
        }
    }

    companion object {
        fun recommendation(
            decision: SimulationDecision,
            environment: CardAdvantageEnvironment,
        ): String =
            BotPolicy.choose(
                decision.observation,
                BotDifficulty.HARD,
                decision.seed,
                environment.baseline.model,
            )

        fun observation(decision: SimulationDecision, baseline: String): CardObservation =
            CardObservationEncoder.from(
                decision.view,
                PlayerId(decision.observation.playerId),
                decision.observation,
                baseline,
            )
    }
}
