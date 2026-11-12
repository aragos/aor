package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.aragos.aor.ai.BotDifficulty

/** Complete origins only; original absolute decisions and empty expansion arrays are retained. */
internal data class ExpansionOnPolicyGame(
    val schemaVersion: Int = 1,
    val kind: String = "expansion-on-policy-trajectory-1",
    val signature: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val actorModelSha256: String,
    val frozenCardPurchaseBundleSha256: String,
    val referenceExpansionModelSha256: String,
    val arenaSourceSha256: String,
    val expansionDecisions: List<TerritoryDecisionRecord>,
    val terminal: PurchaseTerminal,
    @get:JsonIgnore val canonicalReplay: PurchaseCanonicalReplay? = null,
)

/** The same controller factory is used for original collection and historical reproduction. */
internal class ExpansionOnPolicyCollector(
    private val input: ExpansionOnPolicyInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): ExpansionOnPolicyGame {
        require(index in input.seeds.indices) { "Origin index must belong to its frozen bank." }
        val seat = input.seat(index)
        val records = mutableListOf<TerritoryDecisionRecord>()
        val trace = if (index in input.replayIndices) PurchaseCanonicalTrace(seat) else null
        val game =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = input.seeds[index],
                    competitors = List(4) { Competitor(input.arena.candidate, BotDifficulty.HARD) },
                    deadlineNanos = deadlineNanos,
                    controllers = input.controllers(index, records::add),
                    trace = trace,
                )
        return ExpansionOnPolicyGame(
            signature = input.signature,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = List(4) { 0 },
            actorModelSha256 = input.actor.sha256,
            frozenCardPurchaseBundleSha256 = input.frozenCardPurchaseBundleSha256,
            referenceExpansionModelSha256 = input.referenceExpansionModelSha256,
            arenaSourceSha256 = input.arenaSourceSha256,
            expansionDecisions = records.toList(),
            terminal =
                PurchaseTerminal(
                    game.reward(seat),
                    game.winShare(seat),
                    game.rankReward(seat),
                    game.scores.map(PilotReplayScore::from),
                    game.steps,
                ),
            canonicalReplay = trace?.result(),
        )
    }
}
