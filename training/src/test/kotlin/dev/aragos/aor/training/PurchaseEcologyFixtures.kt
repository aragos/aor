package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.ai.BotModels
import java.nio.file.Path

internal class PurchaseEcologyFixture : AutoCloseable {
    val purchase = ArenaPurchaseFixture()
    val directory: Path = purchase.directory
    val artifacts = purchase.artifacts
    val source: Path = directory.resolve("collection-source.json")
    val output: Path = directory.resolve("collection")

    init {
        purchase.withOverrides()
        artifacts.model(
            BotModels.default.copy(weights = BotModels.default.weights + ("action.pass" to 2.0)),
            "expansion.json",
        )
        val expansion =
            mapOf(
                "candidateModel" to "expansion.json",
                "opponentModels" to listOf(null, "expansion.json", null, "expansion.json"),
            )
        val tokens =
            mapOf(
                "candidateSelector" to "FIXED_16_TO_15",
                "opponentSelectors" to
                    listOf("ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15"),
            )
        val rivals = List(4) { "baseline.json" }
        artifacts.write(
            "collection-source.json",
            mapOf(
                "schemaVersion" to 1,
                "configuration" to purchase.configuration.id,
                "games" to 1,
                "seed" to 71,
                "baseline" to "baseline.json",
                "opponents" to rivals,
                "policy" to "candidate-policy.json",
                "opponentPurchasePolicy" to "policy.json",
                "sharedPurchaseBaseline" to "baseline.json",
                "continuationProtocol" to "ARENA_OVERLAYS_1",
                "expansionImitation" to expansion,
                "tokenOrder" to tokens,
                "replayIndices" to listOf(0),
            ),
        )
        val arena = PurchaseJson.read(purchase.source) as ObjectNode
        arena.put("candidate", "baseline.json")
        arena.put("incumbent", "baseline.json")
        arena.set<JsonNode>("opponents", PurchaseJson.mapper.valueToTree(rivals))
        arena.set<JsonNode>("expansionImitation", PurchaseJson.mapper.valueToTree(expansion))
        arena.set<JsonNode>("tokenOrder", PurchaseJson.mapper.valueToTree(tokens))
        artifacts.write("arena.json", arena)
    }

    val input: PurchaseCollectionInput
        get() = PurchaseCollectionInput.read(source)

    fun edit(change: ObjectNode.() -> Unit) {
        val node = PurchaseJson.read(source) as ObjectNode
        node.change()
        artifacts.write("collection-source.json", node)
    }

    override fun close() = purchase.close()
}
