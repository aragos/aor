package dev.aragos.aor.training

import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.TerritoryEncoder
import kotlinx.collections.immutable.toPersistentList

/** Invented weights/positions only; the scientific frozen-container hash guard rejects this. */
internal class CoordinatedEsFixture : AutoCloseable {
    val prior = CpePairedFixture(nonzeroCardPurchase = true)
    val input = prior.input
    val files = prior.files
    val original: CoordinatedEsBundle

    init {
        val dictionaries = CoordinatedEsBundle.dictionaries(input.configuration)
        val dictionary =
            requireNotNull(dictionaries[CoordinatedEsHead.TOKEN]) { "Fixture token dictionary." }
        files.write(
            "token-actor.json",
            mapOf(
                "schemaVersion" to 3,
                "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
                "hiddenSize" to 64,
                "baselineProbability" to .97,
                "stateFeatureNames" to dictionary.stateFeatureNames,
                "actionFeatureNames" to dictionary.actionFeatureNames,
                "stateWeights" to List(64 * dictionary.stateFeatureNames.size) { 0.0 },
                "actionWeights" to List(64 * dictionary.actionFeatureNames.size) { 0.0 },
                "stateBias" to List(64) { .25 },
                "actionBias" to List(64) { -.125 },
                "actorWeights" to List(64) { 0.0 },
                "valueWeights" to List(64) { .01 },
                "valueBias" to .1,
            ),
        )
        files.write(
            "coordinated-bundle.json",
            mapOf(
                "kind" to CoordinatedEsBundle.VERSION,
                "heads" to
                    CoordinatedEsHead.entries.associate { head ->
                        val name =
                            when (head) {
                                CoordinatedEsHead.CARD -> "card-policy.json"
                                CoordinatedEsHead.PURCHASE -> "purchase-policy.json"
                                CoordinatedEsHead.EXPANSION -> "expansion-actor.json"
                                CoordinatedEsHead.TOKEN -> "token-actor.json"
                            }
                        head.name to
                            mapOf(
                                "path" to name,
                                "sha256" to PurchaseJson.hash(files.directory.resolve(name)),
                                "dictionarySha256" to
                                    requireNotNull(dictionaries[head]) { "Fixture dictionary." }
                                        .sha256(),
                            )
                    },
            ),
        )
        val path = files.directory.resolve("coordinated-bundle.json").toRealPath()
        original =
            CoordinatedEsBundle.read(
                TokenPairedSubject(path, PurchaseJson.hash(path), path.toString()),
                dictionaries,
            )
    }

    fun decision(head: CoordinatedEsHead, seat: Int = 0, batchStart: Int = 19): SimulationDecision {
        val state =
            if (head == CoordinatedEsHead.TOKEN)
                expansionPairedState().let { game ->
                    game.copy(
                        stage = Stage.TOKEN_PURCHASE,
                        capitalSelection = CapitalSelection(game.playerIds.toPersistentList()),
                        flow = TurnFlow(),
                        turnOrderSelection = TurnOrderSelection(),
                        players =
                            game.players.mapValues { (_, player) ->
                                player.copy(stockTokens = 36, poolTokens = 0, capitalBid = 0)
                            },
                    )
                }
            else prior.state(CpeHead.valueOf(head.name))
        val actor = state.playerIds[seat]
        return SimulationDecision(
            batchStart + if (head == CoordinatedEsHead.TOKEN) seat else 0,
            seat,
            simulationPolicySeed(123, batchStart, seat),
            state.viewForPlayer(actor),
            BotObservations.from(state, actor),
            batchStart,
        )
    }

    override fun close() = prior.close()
}
