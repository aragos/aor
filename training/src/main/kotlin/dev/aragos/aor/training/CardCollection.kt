package dev.aragos.aor.training

import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy

/** One card decision; exact baseline imitation has no invented behavior likelihood or value. */
internal data class CardDecisionRecord(
    val number: Int,
    val actorSeed: Int,
    val decision: String,
    val turn: Int,
    val observation: CardObservation,
    val selectedIndex: Int,
    val behaviorLogProbability: Double?,
    val behaviorValue: Double?,
)

/** Whole-game boundary: interrupted games never persist a partially labeled card trajectory. */
internal data class CardGameTrajectory(
    val schemaVersion: Int = 1,
    val signature: String,
    val mode: String,
    val encoderVersion: String = CardObservationEncoder.ENCODER_VERSION,
    val actorVersion: String = CardPolicy.ACTOR_VERSION,
    val modelSha256: String?,
    val frozenPurchasePolicySha256: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val decisions: List<CardDecisionRecord>,
    val terminal: PurchaseTerminal,
    val purchaseAudit: List<CardPurchaseAudit>,
)

/** Full repeated-policy continuations, with card rows and public terminal labels kept separate. */
internal class CardCollector(
    private val input: CardCollectionInput,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): CardGameTrajectory {
        require(index in input.seeds.indices) {
            "A card game index must belong to its frozen seed bank."
        }
        val seat = input.seat(index)
        val records = mutableListOf<CardDecisionRecord>()
        val purchaseAudit = mutableListOf<CardPurchaseAudit>()
        val opponents = input.opponentIndices(index)
        val competitors = opponents.mapIndexed { player, opponent ->
            Competitor(
                if (player == seat) input.baseline.model else input.opponents[opponent].model,
                BotDifficulty.HARD,
            )
        }
        val controllers =
            competitors.indices.associateWith { player ->
                CardActorController(
                    competitors[player].model,
                    if (player == seat) input.policy else input.opponentCardPolicy?.model,
                    input.frozenPurchasePolicy.model,
                    audit = if (player == seat) ({ purchaseAudit.add(it) }) else null,
                    record = if (player == seat) ({ records.add(it) }) else null,
                )
            }
        val game =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = input.seeds[index],
                    competitors = competitors,
                    deadlineNanos = deadlineNanos,
                    controllers = controllers,
                )
        return CardGameTrajectory(
            signature = signature,
            mode = input.mode,
            modelSha256 = input.modelSha256,
            frozenPurchasePolicySha256 = input.frozenPurchasePolicy.sha256,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = opponents,
            decisions = records.toList(),
            purchaseAudit = purchaseAudit.toList(),
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
