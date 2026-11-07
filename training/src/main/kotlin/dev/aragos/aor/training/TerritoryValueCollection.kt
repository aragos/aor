package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.TerritoryPhase

/**
 * Both typed views share one complete origin and terminal result. Numbers are absolute simulator
 * command indices, not phase-local offsets. Empty phase arrays preserve the common origin set.
 * Readers may merge these arrays to link actual next-own decisions; the collector invents no
 * successor offers and does not publish terminal labels for interrupted games.
 */
internal data class TerritoryValueGameTrajectory(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val bundleSha256: String,
    val frozenCardPurchaseBundleSha256: String,
    val tokenModelSha256: String,
    val expansionModelSha256: String,
    val tokenDecisions: List<TerritoryValueDecisionRecord>,
    val expansionDecisions: List<TerritoryValueDecisionRecord>,
    val terminal: PurchaseTerminal,
)

/** No callback can expose opponent-private observations or persist an interrupted game's labels. */
internal class TerritoryValueCollector(
    private val input: TerritoryValueCollectionInput,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): TerritoryValueGameTrajectory {
        require(index in input.seeds.indices) {
            "A territory game index must belong to the frozen bank."
        }
        val seat = input.seat(index)
        val tokenRows = mutableListOf<TerritoryValueDecisionRecord>()
        val expansionRows = mutableListOf<TerritoryValueDecisionRecord>()
        val opponents = input.opponentIndices(index)
        val controllers =
            opponents.indices.associateWith { player ->
                val bundle =
                    if (player == seat) input.bundle else input.opponents[opponents[player]]
                bundle.controller(
                    input.baseline,
                    input.epsilon,
                    record =
                        if (player == seat)
                            ({ row ->
                                if (row.decision == TerritoryPhase.TOKEN.decision)
                                    tokenRows.add(row)
                                else expansionRows.add(row)
                            })
                        else null,
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
        return TerritoryValueGameTrajectory(
            signature = signature,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = opponents,
            bundleSha256 = input.bundle.sha256,
            frozenCardPurchaseBundleSha256 = input.frozen.sha256,
            tokenModelSha256 =
                requireNotNull(input.bundle.token) {
                        "The territory learner requires its token model."
                    }
                    .sha256,
            expansionModelSha256 =
                requireNotNull(input.bundle.expansion) {
                        "The territory learner requires its expansion actor."
                    }
                    .sha256,
            tokenDecisions = tokenRows.toList(),
            expansionDecisions = expansionRows.toList(),
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
