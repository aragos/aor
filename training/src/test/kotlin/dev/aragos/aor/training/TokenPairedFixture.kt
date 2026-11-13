package dev.aragos.aor.training

import dev.aragos.aor.CapitalSelection
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage
import dev.aragos.aor.TurnFlow
import dev.aragos.aor.TurnOrderSelection
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenObservationEncoder
import kotlinx.collections.immutable.toPersistentList

/** Constructed public states and zero-output actors only; no scientific model or game is read. */
internal class TokenPairedFixture : AutoCloseable {
    val prior = ExpansionPairedFixture()
    val files = prior.files
    val source = files.directory.resolve("token-source.json")
    val actor = files.directory.resolve("token-actor.json")

    init {
        val names =
            TokenObservationEncoder.dictionary(
                PublicCatalog.forPlayers(4, prior.base.configuration.rules),
                TerritoryEncoder.TOKEN_2,
            )
        files.write(
            "token-actor.json",
            mapOf(
                "schemaVersion" to 3,
                "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
                "hiddenSize" to 64,
                "baselineProbability" to .97,
                "stateFeatureNames" to names.stateFeatureNames,
                "actionFeatureNames" to names.actionFeatureNames,
                "stateWeights" to List(64 * names.stateFeatureNames.size) { 0.0 },
                "actionWeights" to List(64 * names.actionFeatureNames.size) { 0.0 },
                "stateBias" to List(64) { 0.0 },
                "actionBias" to List(64) { 0.0 },
                "actorWeights" to List(64) { 0.0 },
                "valueWeights" to List(64) { 0.0 },
                "valueBias" to 0.0,
            ),
        )
        source()
    }

    fun source(start: Int = 0) {
        val actorSubject = mapOf("path" to "token-actor.json", "sha256" to PurchaseJson.hash(actor))
        files.write(
            "token-source.json",
            mapOf(
                "kind" to TokenPairedInput.VERSION,
                "iteration" to 1,
                "globalStart" to start,
                "count" to 64,
                "arena" to
                    mapOf("path" to "arena.json", "sha256" to PurchaseJson.hash(prior.arenaSource)),
                "currentActor" to actorSubject,
                "originalActor" to actorSubject,
                "origins" to
                    List(640) {
                        TokenPairedSeeds(it, 10000L + it, listOf(20000L + it, 30000L + it))
                    },
                "replayIndices" to listOf(0, 213, 426, 639),
            ),
        )
    }

    val input: TokenPairedInput
        get() = TokenPairedInput.read(source)

    fun state() =
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

    fun decision(seat: Int = 0, number: Int = 19): SimulationDecision {
        val state = state()
        val actor = state.playerIds[seat]
        return SimulationDecision(
            number + seat,
            seat,
            simulationPolicySeed(123, number, seat),
            state.viewForPlayer(actor),
            BotObservations.from(state, actor),
            number,
        )
    }

    override fun close() = prior.close()
}
