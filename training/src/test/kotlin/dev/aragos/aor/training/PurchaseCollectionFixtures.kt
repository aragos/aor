package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.PurchaseAction
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Real immutable source files, reused only as setup for independent boundary tests. */
internal class PurchaseCollectionFixture(onPolicy: Boolean = false, games: Int = 1) :
    AutoCloseable {
    val directory: Path = Files.createTempDirectory("aor-purchase-collection-")
    private val inputs = TrainingArtifacts(directory.resolve("inputs"))
    val output: Path = directory.resolve("output")
    val source: Path = inputs.directory.resolve("collection-source.json")
    val configuration = TrainingMatrix.stratified.first()

    init {
        inputs.model(BotModels.default, "baseline.json")
        if (onPolicy) {
            val dictionary =
                PurchaseObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
                )
            inputs.write(
                "policy.json",
                mapOf(
                    "schemaVersion" to 1,
                    "encoderVersion" to PurchaseObservationEncoder.ENCODER_VERSION,
                    "hiddenSize" to 64,
                    "baselineProbability" to .97,
                    "stateFeatureNames" to dictionary.stateFeatureNames,
                    "actionFeatureNames" to dictionary.actionFeatureNames,
                    "stateWeights" to List(64 * dictionary.stateFeatureNames.size) { 0.0 },
                    "stateBias" to List(64) { 0.0 },
                    "actionWeights" to List(64 * dictionary.actionFeatureNames.size) { 0.0 },
                    "actionBias" to List(64) { 0.0 },
                    "actorWeights" to List(64) { 0.0 },
                    "valueWeights" to List(64) { 0.0 },
                    "valueBias" to 0.0,
                ),
            )
        }
        inputs.write(
            "collection-source.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "games" to games,
                "seed" to 71,
                "baseline" to "baseline.json",
                "opponents" to listOf("baseline.json"),
                "policy" to if (onPolicy) "policy.json" else null,
            ),
        )
    }

    val input: PurchaseCollectionInput
        get() = PurchaseCollectionInput.read(source)

    fun arguments(): List<String> =
        listOf(
            "--mode",
            "purchase-collect",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
            "--workers",
            "1",
            "--hours",
            ".01",
        )

    fun store(
        input: PurchaseCollectionInput = this.input,
        signature: String = "fixture",
    ): PurchaseCollectionStore = PurchaseCollectionStore(input, output, signature)

    fun syntheticGame(input: PurchaseCollectionInput = this.input): PurchaseGameTrajectory {
        val observation =
            PurchaseObservation(
                stateFeatures = emptyMap(),
                actions = listOf(PurchaseAction("pass", emptyMap())),
                baselineIndex = 0,
            )
        return PurchaseGameTrajectory(
            signature = "fixture",
            mode = input.mode,
            modelSha256 = input.modelSha256,
            index = 0,
            configuration = input.configuration.id,
            gameSeed = input.seeds[0],
            controlledSeat = 0,
            opponentIndices = input.opponentIndices(0),
            decisions =
                listOf(
                    PurchaseDecisionRecord(
                        number = 0,
                        actorSeed = PurchaseCollector.samplingSeed(input.seeds[0]),
                        decision = "purchase-advances",
                        turn = 1,
                        observation = observation,
                        selectedIndex = 0,
                        behaviorLogProbability = if (input.policy == null) null else 0.0,
                        behaviorValue = if (input.policy == null) null else .5,
                    )
                ),
            terminal =
                PurchaseTerminal(
                    reward = 1.0,
                    winShare = 1.0,
                    rankReward = 1.0,
                    gameDecisions = 1,
                    scores =
                        List(configuration.playerCount) { seat ->
                            PilotReplayScore(
                                "p${seat + 1}",
                                0,
                                if (seat == 0) 10 else 0,
                                0,
                                if (seat == 0) 10 else 0,
                                seat == 0,
                            )
                        },
                ),
        )
    }

    override fun close() {
        directory.toFile().deleteRecursively()
    }
}
