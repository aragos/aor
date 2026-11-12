package dev.aragos.aor.training

import dev.aragos.aor.ActivityPlanning
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlanBoundary
import dev.aragos.aor.PlannedAction
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import dev.aragos.aor.ai.BotObservation

/** Before/after ordering is provenance, not a model feature; exact states bypass inference. */
internal data class AfterstateRow(
    val number: Int,
    val ordinal: Int,
    val turn: Int,
    val context: AfterstateContext,
    val stateFeatures: Map<String, Double>,
    val exact: Boolean,
    val exactReason: String?,
)

internal data class AfterstateExclusion(val number: Int, val reason: String)

/**
 * Observes actual chosen purchase actions; never executes an alternate plan or chooses a policy.
 */
internal class AfterstateObservation(override val seat: Int) : SimulationStateObserver {
    val rows = mutableListOf<AfterstateRow>()
    val exclusions = mutableListOf<AfterstateExclusion>()

    override fun chosen(
        state: GameState,
        number: Int,
        observation: BotObservation,
        command: String,
    ) {
        val owner = state.header.players[seat].id
        require(observation.playerId == owner.value) {
            "Afterstate observations must belong to their actual controlled owner."
        }
        val context = AfterstateContext.entries.singleOrNull { it.decision == observation.decision }
        if (context == null || context == AfterstateContext.OWNER_EXIT) return
        add(state.viewForPlayer(owner), owner, number, 0, context, observation)
        if (context == AfterstateContext.CARD) return
        val preview =
            ActivityPlanning.preview(
                state,
                owner,
                listOf(PlannedAction(observation.decision, command)),
            )
        require(!preview.hypothetical && preview.boundary != PlanBoundary.INVALID) {
            "An actual purchase command must have a valid nonhypothetical preview."
        }
        when (preview.boundary) {
            PlanBoundary.FINISHED ->
                add(preview.game, owner, number, 1, AfterstateContext.OWNER_EXIT, null)
            PlanBoundary.INFORMATION -> exclusions.add(AfterstateExclusion(number, "INFORMATION"))
            PlanBoundary.INCOMPLETE -> {
                if (AfterstateContext.entries.none { it.decision == preview.decision }) {
                    exclusions.add(AfterstateExclusion(number, "INTERVENING_CHOICE"))
                }
            }
            PlanBoundary.INVALID -> error("Invalid actual purchase preview was already rejected.")
        }
    }

    private fun add(
        view: GameView,
        owner: PlayerId,
        number: Int,
        ordinal: Int,
        context: AfterstateContext,
        observation: BotObservation?,
    ) {
        val reason =
            when {
                view.players.single { it.id == owner }.misery == MiseryStep.CHAOS -> "OWN_CHAOS"
                view.stage == Stage.FINISHED -> "GAME_TERMINAL"
                else -> null
            }
        rows.add(
            AfterstateRow(
                number,
                ordinal,
                view.turnNumber ?: 0,
                context,
                AfterstateValueEncoder.from(view, owner, context, observation),
                reason != null,
                reason,
            )
        )
    }

    /** Old recorded CARD/shipping/advance state bytes are an independent per-origin control. */
    fun verifyOriginalStates(game: JointGameTrajectory) {
        val original =
            game.cardDecisions.associate { row ->
                row.number to row.observation.stateFeatures.mapKeys { "card.${it.key}" }
            } +
                game.purchaseDecisions.associate { row ->
                    row.number to row.observation.stateFeatures.mapKeys { "purchase.${it.key}" }
                }
        val observed =
            rows
                .filter {
                    it.context in
                        setOf(
                            AfterstateContext.CARD,
                            AfterstateContext.SHIPPING,
                            AfterstateContext.ADVANCES,
                        )
                }
                .associate { it.number to it.stateFeatures }
        require(original == observed) {
            "Existing actor-state features changed during observation."
        }
    }
}
