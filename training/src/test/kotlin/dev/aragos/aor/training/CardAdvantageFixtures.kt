package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardProposals
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Four-player, full-dictionary inputs with separate immutable model files. */
internal class CardAdvantageFixture(continuation: Boolean = false, preferPass: Boolean = false) :
    AutoCloseable {
    val directory: Path = Files.createTempDirectory("aor-card-advantage-")
    val artifacts = TrainingArtifacts(directory)
    val source: Path = directory.resolve("source.json")
    val configuration = TrainingMatrix.stratified.first { it.playerCount == 4 }
    private val catalog = PublicCatalog.forPlayers(4, configuration.rules)
    val dictionary = CardObservationEncoder.dictionary(catalog)
    val request = CardAdvantageRequest(0, "train", 71, 0, 1, "generic", 1, List(4) { false })

    init {
        artifacts.model(BotModels.default, "baseline.json")
        val purchase = PurchaseObservationEncoder.dictionary(catalog)
        artifacts.write(
            "purchase.json",
            mapOf(
                "schemaVersion" to 1,
                "encoderVersion" to PurchaseObservationEncoder.ENCODER_VERSION,
                "hiddenSize" to 64,
                "baselineProbability" to .97,
                "stateFeatureNames" to purchase.stateFeatureNames,
                "actionFeatureNames" to purchase.actionFeatureNames,
                "stateWeights" to List(64 * purchase.stateFeatureNames.size) { 0.0 },
                "stateBias" to List(64) { 0.0 },
                "actionWeights" to List(64 * purchase.actionFeatureNames.size) { 0.0 },
                "actionBias" to List(64) { 0.0 },
                "actorWeights" to List(64) { 0.0 },
                "valueWeights" to List(64) { 0.0 },
                "valueBias" to 0.0,
            ),
        )
        artifacts.write(
            "advantage.json",
            mapOf(
                "schemaVersion" to 1,
                "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
                "selectorVersion" to CardAdvantagePolicy.SELECTOR_VERSION,
                "proposalVersion" to CardProposals.VERSION,
                "hiddenSize" to 64,
                "margin" to .02,
                "stateFeatureNames" to dictionary.stateFeatureNames,
                "actionFeatureNames" to dictionary.actionFeatureNames,
                "members" to
                    List(3) {
                        mapOf(
                            "stateWeights" to List(64 * dictionary.stateFeatureNames.size) { 0.0 },
                            "stateBias" to
                                List(64) { row -> if (preferPass && row == 0) 1.0 else 0.0 },
                            "actionWeights" to
                                List(64 * dictionary.actionFeatureNames.size) { column ->
                                    if (
                                        preferPass &&
                                            column ==
                                                dictionary.actionFeatureNames.indexOf("kind.pass")
                                    )
                                        2.0
                                    else 0.0
                                },
                            "actionBias" to List(64) { 0.0 },
                            "outputWeights" to
                                List(64) { row -> if (preferPass && row == 0) 2.0 else 0.0 },
                            "outputBias" to 0.0,
                        )
                    },
            ),
        )
        artifacts.write("source.json", source(listOf(request), continuation))
    }

    fun source(
        origins: List<CardAdvantageRequest>,
        continuation: Boolean = false,
    ): Map<String, Any?> =
        mapOf(
            "schemaVersion" to 1,
            "configuration" to configuration.id,
            "baseline" to "baseline.json",
            "frozenPurchasePolicy" to "purchase.json",
            "continuation" to if (continuation) "advantage.json" else null,
            "origins" to origins,
        )

    val input: CardAdvantageCollectionInput
        get() = CardAdvantageCollectionInput.read(source)

    fun evaluation(jobs: List<CardAdvantageEvaluationJob>): CardAdvantageEvaluationInput {
        artifacts.write(
            "evaluation.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to configuration.id,
                "baseline" to "baseline.json",
                "frozenPurchasePolicy" to "purchase.json",
                "policies" to
                    mapOf(
                        "baseline" to mapOf("kind" to "baseline"),
                        "candidate" to mapOf("kind" to "advantage", "path" to "advantage.json"),
                    ),
                "jobs" to jobs,
            ),
        )
        return CardAdvantageEvaluationInput.read(directory.resolve("evaluation.json"))
    }

    override fun close() {
        directory.toFile().deleteRecursively()
    }
}
