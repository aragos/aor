package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardAction
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import java.nio.file.Path

/**
 * Separate card inputs reuse a real frozen purchase actor, never a fake purchase recommendation.
 */
internal class CardCollectionFixture(onPolicy: Boolean = false, games: Int = 1) : AutoCloseable {
    val purchase = PurchaseCollectionFixture(onPolicy = true, games = games)
    val directory: Path = purchase.directory
    private val artifacts = TrainingArtifacts(directory.resolve("card-inputs"))
    val source: Path = artifacts.directory.resolve("source.json")
    val output: Path = directory.resolve("card-output")
    val configuration = purchase.configuration

    init {
        val frozen = purchase.input
        val dictionary =
            CardObservationEncoder.dictionary(
                PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            )
        artifacts.write(
            "card-policy.json",
            mapOf(
                "schemaVersion" to 1,
                "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
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
        artifacts.write(
            "source.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "games" to games,
                "seed" to 71,
                "baseline" to frozen.baseline.path.toString(),
                "opponents" to frozen.opponents.map { it.path.toString() },
                "frozenPurchasePolicy" to
                    requireNotNull(frozen.policyPath) { "The fixture requires its purchase actor." }
                        .toString(),
                "policy" to if (onPolicy) "card-policy.json" else null,
            ),
        )
    }

    val input: CardCollectionInput
        get() = CardCollectionInput.read(source)

    fun store(
        input: CardCollectionInput = this.input,
        signature: String = "fixture",
    ): CardCollectionStore = CardCollectionStore(input, output, signature)

    fun arguments(): List<String> =
        listOf(
            "--mode",
            "card-collect",
            "--source",
            source.toString(),
            "--output",
            output.toString(),
            "--workers",
            "1",
            "--hours",
            ".01",
        )

    fun syntheticGame(input: CardCollectionInput = this.input): CardGameTrajectory =
        CardGameTrajectory(
            signature = "fixture",
            mode = input.mode,
            modelSha256 = input.modelSha256,
            frozenPurchasePolicySha256 = input.frozenPurchasePolicy.sha256,
            index = 0,
            configuration = input.configuration.id,
            gameSeed = input.seeds[0],
            controlledSeat = 0,
            opponentIndices = input.opponentIndices(0),
            decisions =
                listOf(
                    CardDecisionRecord(
                        number = 0,
                        actorSeed = CardPolicy.actorSeed(input.seeds[0]),
                        decision = "play-cards",
                        turn = 1,
                        observation =
                            CardObservation(
                                actions = listOf(CardAction("pass", emptyMap())),
                                stateFeatures = emptyMap(),
                                baselineIndex = 0,
                            ),
                        selectedIndex = 0,
                        behaviorLogProbability = if (input.policy == null) null else 0.0,
                        behaviorValue = if (input.policy == null) null else .5,
                    )
                ),
            terminal = purchase.syntheticGame().terminal,
            purchaseAudit = emptyList(),
        )

    override fun close() = purchase.close()
}
