package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty

/**
 * Both typed views share one complete origin and terminal result. Numbers are absolute simulator
 * command indices, not phase-local offsets. Empty phase arrays preserve the common origin set.
 */
internal data class JointGameTrajectory(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val bundleSha256: String,
    val cardModelSha256: String,
    val purchaseModelSha256: String,
    val cardDecisions: List<CardDecisionRecord>,
    val purchaseDecisions: List<PurchaseDecisionRecord>,
    val terminal: PurchaseTerminal,
)

/** No callback can expose opponent-private observations or persist an interrupted game's labels. */
internal class JointCollector(
    private val input: JointCollectionInput,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): JointGameTrajectory {
        require(index in input.seeds.indices) {
            "A joint game index must belong to the frozen bank."
        }
        val seat = input.seat(index)
        val cards = mutableListOf<CardDecisionRecord>()
        val purchases = mutableListOf<PurchaseDecisionRecord>()
        val opponents = input.opponentIndices(index)
        val controllers =
            opponents.indices.associateWith { player ->
                val bundle =
                    if (player == seat) input.bundle else input.opponents[opponents[player]]
                bundle.controller(
                    input.baseline,
                    cardRecord = if (player == seat) ({ cards.add(it) }) else null,
                    purchaseRecord = if (player == seat) ({ purchases.add(it) }) else null,
                )
            }
        val game =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = input.seeds[index],
                    competitors =
                        List(input.configuration.playerCount) {
                            Competitor(input.baseline.model, BotDifficulty.HARD)
                        },
                    deadlineNanos = deadlineNanos,
                    controllers = controllers,
                )
        return JointGameTrajectory(
            signature = signature,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = opponents,
            bundleSha256 = input.bundle.sha256,
            cardModelSha256 =
                requireNotNull(input.bundle.card) { "The joint learner requires its card model." }
                    .sha256,
            purchaseModelSha256 = input.bundle.purchase.sha256,
            cardDecisions = cards.toList(),
            purchaseDecisions = purchases.toList(),
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
}
