package dev.aragos.aor

/** Modified totals for a public recorded War roll. A null winner denotes a continuing tie. */
data class WarOutcomeView(
    val initiator: PlayerId,
    val opponent: PlayerId,
    val totals: Map<PlayerId, Int>,
    val winner: PlayerId?,
    val requiredTransfers: Int,
)

object WarProjection {
    /** Uses the same rule calculation as execution, before misery or cession changes occur. */
    fun outcome(state: GameState, dice: WarDice): WarOutcomeView? {
        val frame = state.flow.decisions.lastOrNull() as? CardDecision.WarRoll ?: return null
        val result = WarRules.roll(state.cardContext(), frame.war, dice)
        return when (result) {
            is WarResult.Tie ->
                WarOutcomeView(
                    frame.war.initiator,
                    frame.war.opponent,
                    result.totals,
                    null,
                    0,
                )
            is WarResult.Victory ->
                WarOutcomeView(
                    frame.war.initiator,
                    frame.war.opponent,
                    result.totals,
                    result.winner,
                    result.requiredTransfers,
                )
        }
    }
}
