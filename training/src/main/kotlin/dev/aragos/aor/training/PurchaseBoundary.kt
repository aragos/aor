package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.PurchaseObservationEncoder

/**
 * Actual actor input at a retained future decision; no recommendation, action or reward features.
 */
internal data class PurchaseBoundaryProjection(
    val turn: Int,
    val decision: String,
    val stateFeatures: Map<String, Double>,
)

/**
 * Counts exclude the boundary action, which has not yet been applied. Null projection is terminal.
 */
internal data class PurchaseBoundaryCapture(
    val projection: PurchaseBoundaryProjection?,
    val prefixTransitions: Int,
    val prefixPlayerActions: Int,
)

/** Replays only recorded entries until the first later-turn own purchase decision or terminal. */
internal object PurchaseBoundary {
    fun project(
        view: GameView,
        actor: PlayerId,
        observation: BotObservation,
        rootTurn: Int,
    ): PurchaseBoundaryProjection? {
        val turn = view.turnNumber ?: return null
        if (
            turn <= rootTurn ||
                view.stage != Stage.PURCHASE ||
                observation.playerId != actor.value ||
                observation.decision !in setOf("purchase-shipping", "purchase-advances")
        ) {
            return null
        }
        val projection = PurchaseObservationEncoder.project(view, actor, observation)
        return PurchaseBoundaryProjection(turn, observation.decision, projection.stateFeatures)
    }

    fun capture(root: GameState, actor: PlayerId, entries: List<String>): PurchaseBoundaryCapture {
        val turn = requireNotNull(root.turnNumber) { "A retained leader root requires its turn." }
        var state = root
        var actions = 0
        for ((index, text) in entries.withIndex()) {
            val prompt = state.prompts().singleOrNull()
            if (
                prompt is Prompt.SinglePlayer &&
                    prompt.player == actor &&
                    state.stage == Stage.PURCHASE &&
                    (state.turnNumber ?: 0) > turn
            ) {
                val boundary =
                    project(
                        state.viewForPlayer(actor),
                        actor,
                        BotObservations.from(state, actor),
                        turn,
                    )
                if (boundary != null) return PurchaseBoundaryCapture(boundary, index, actions)
            }
            val entry = TextCodec.parse(text)
            require(TextCodec.print(entry) == text) { "A retained suffix entry must be canonical." }
            state = state.step(entry)
            if (entry is Entry.Action) actions++
        }
        require(state.stage == Stage.FINISHED) {
            "A tail without a later purchase boundary must end at FINISHED."
        }
        return PurchaseBoundaryCapture(null, entries.size, actions)
    }
}
