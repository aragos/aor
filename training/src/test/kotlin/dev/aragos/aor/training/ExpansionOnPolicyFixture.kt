package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder

/** All models and positions are constructed locally; no retained actor or trajectory is read. */
internal class ExpansionOnPolicyFixture(val games: Int = 2, val replays: List<Int> = emptyList()) :
    AutoCloseable {
    val prior = ExpansionPairedFixture()
    val files = prior.files
    val source = files.directory.resolve("on-policy-source.json")
    val actorPath = files.directory.resolve("expansion-actor.json")
    val output = prior.base.output

    init {
        val names =
            ExpansionObservationEncoder.dictionary(
                PublicCatalog.forPlayers(4, prior.base.configuration.rules),
                TerritoryEncoder.EXPANSION_2,
            )
        files.write(
            "expansion-actor.json",
            mapOf(
                "schemaVersion" to 2,
                "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
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

    fun source() {
        files.write(
            "on-policy-source.json",
            mapOf(
                "kind" to ExpansionOnPolicyInput.VERSION,
                "arena" to "arena.json",
                "actor" to
                    mapOf(
                        "path" to "expansion-actor.json",
                        "sha256" to PurchaseJson.hash(actorPath),
                    ),
                "games" to games,
                "seed" to 71,
                "replayIndices" to replays,
            ),
        )
    }

    val input: ExpansionOnPolicyInput
        get() = ExpansionOnPolicyInput.read(source)

    fun decision(input: ExpansionOnPolicyInput = this.input, number: Int = 7): SimulationDecision {
        val state = expansionPairedState()
        val actor = state.playerIds.first()
        return SimulationDecision(
            number,
            0,
            simulationPolicySeed(input.seeds[0], number, 0),
            state.viewForPlayer(actor),
            BotObservations.from(state, actor),
        )
    }

    fun game(input: ExpansionOnPolicyInput, rows: List<TerritoryDecisionRecord>, index: Int = 0) =
        ExpansionOnPolicyGame(
            signature = input.signature,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = input.seat(index),
            opponentIndices = List(4) { 0 },
            actorModelSha256 = input.actor.sha256,
            frozenCardPurchaseBundleSha256 = input.frozenCardPurchaseBundleSha256,
            referenceExpansionModelSha256 = input.referenceExpansionModelSha256,
            arenaSourceSha256 = input.arenaSourceSha256,
            expansionDecisions = rows,
            terminal =
                PurchaseTerminal(
                    1.0,
                    1.0,
                    1.0,
                    List(4) { player ->
                        val winner = player == input.seat(index)
                        PilotReplayScore(
                            "p${player + 1}",
                            0,
                            if (winner) 10 else 0,
                            0,
                            if (winner) 10 else 0,
                            winner,
                        )
                    },
                    99,
                ),
        )

    override fun close() = prior.close()
}
