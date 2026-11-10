package dev.aragos.aor.training

import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModels
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Files

/** Frozen ordinary, prior and actor inputs shared by arena boundary and routing tests. */
internal class ArenaPurchaseFixture : AutoCloseable {
    val directory = Files.createTempDirectory("aor-arena-purchase-")
    val artifacts = TrainingArtifacts(directory)
    val source = directory.resolve("arena.json")
    val configuration =
        TrainingMatrix.configurations.single {
            it.id == "players-4/standard/no-crusades/deck-true/art-true"
        }

    init {
        artifacts.model(BotModels.default, "baseline.json")
        val dictionary =
            PurchaseObservationEncoder.dictionary(
                PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            )
        artifacts.write(
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
        artifacts.write(
            "arena.json",
            mapOf(
                "schemaVersion" to 1,
                "purpose" to "SCREENING",
                "configurations" to listOf(configuration.id),
                "candidate" to null,
                "incumbent" to null,
                "opponents" to listOf(null),
                "pairsPerConfiguration" to 1,
                "seed" to 71,
                "frozenPurchase" to mapOf("baseline" to "baseline.json", "policy" to "policy.json"),
            ),
        )
    }

    val specification: ArenaSpecification
        get() = ArenaSpecification.read(source)

    val frozen: ArenaPurchasePolicy
        get() = requireNotNull(specification.frozenPurchase) { "The fixture requires its actor." }

    fun search(arena: ArenaSpecification): StrategySearchSpecification =
        StrategySearchSpecification(
            arena,
            listOf(StrategySearchParameter("planning.navigationReplacement", -1.0, 1.0)),
        )

    /** Large opposite residuals make purchase routing observable without changing the F0 prior. */
    fun withOverrides(): ArenaSpecification {
        actor("candidate", passWeight = 30.0)
        actor("incumbent", passWeight = -30.0)
        val document = PurchaseJson.read(source) as ObjectNode
        val frozen = document["frozenPurchase"] as ObjectNode
        frozen.put("candidatePolicy", "candidate-policy.json")
        frozen.put("incumbentPolicy", "incumbent-policy.json")
        document.put("pairsPerConfiguration", 4)
        artifacts.write("arena.json", document)
        return specification
    }

    private fun actor(name: String, passWeight: Double) {
        val document = PurchaseJson.read(directory.resolve("policy.json")) as ObjectNode
        val names = document["actionFeatureNames"].map { it.asText() }
        (document["actionWeights"] as ArrayNode).set(
            names.indexOf("kind.pass"),
            PurchaseJson.mapper.nodeFactory.numberNode(8.0),
        )
        (document["actionBias"] as ArrayNode).set(
            0,
            PurchaseJson.mapper.nodeFactory.numberNode(-4.0),
        )
        (document["actorWeights"] as ArrayNode).set(
            0,
            PurchaseJson.mapper.nodeFactory.numberNode(passWeight),
        )
        artifacts.write("$name-policy.json", document)
    }

    override fun close() {
        directory.toFile().deleteRecursively()
    }
}
