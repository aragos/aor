package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/** Synthetic complete dictionaries; calibration scalar receipts are boundary fixtures only. */
internal class CardStyleInteractionFixture : AutoCloseable {
    val old = ExpansionPairedFixture()
    val files = old.files
    val source = files.directory.resolve("continuation-source.json")

    init {
        for (phase in listOf("card", "purchase")) {
            val model =
                PurchaseJson.read(files.directory.resolve("$phase-policy.json")) as ObjectNode
            val names = model["actionFeatureNames"].map(JsonNode::asText)
            (model["actionWeights"] as ArrayNode).set(
                names.indexOf("kind.pass"),
                PurchaseJson.mapper.nodeFactory.numberNode(8.0),
            )
            (model["actionBias"] as ArrayNode).set(
                0,
                PurchaseJson.mapper.nodeFactory.numberNode(-4.0),
            )
            files.write("$phase-policy.json", model)
        }
        old.base.writeBundle("bundle.json")
        val calibration =
            CardStyleInteractionStyle.entries.drop(1).associate { style ->
                val phaseHashes =
                    listOf("card", "purchase").associateWith { phase ->
                        val model =
                            PurchaseJson.read(files.directory.resolve("$phase-policy.json"))
                                as ObjectNode
                        (model["actorWeights"] as ArrayNode).set(
                            0,
                            PurchaseJson.mapper.nodeFactory.numberNode(
                                when (style) {
                                    CardStyleInteractionStyle.D1_PLUS -> 3.0
                                    CardStyleInteractionStyle.D1_MINUS -> -3.0
                                    CardStyleInteractionStyle.D2_PLUS -> 1.5
                                    else -> -1.5
                                }
                            ),
                        )
                        files.write("${style.name}-$phase.json", model)
                        PurchaseJson.hash(files.directory.resolve("${style.name}-$phase.json"))
                    }
                old.base.writeBundle(
                    "${style.name}-bundle.json",
                    "${style.name}-card.json",
                    "${style.name}-purchase.json",
                )
                val arena = PurchaseJson.read(old.arenaSource) as ObjectNode
                (arena["jointPolicy"] as ObjectNode).put(
                    "candidateBundle",
                    "${style.name}-bundle.json",
                )
                files.write("${style.name}-arena.json", arena)
                style.name to
                    (mapOf(
                        "bundleSha256" to
                            PurchaseJson.hash(files.directory.resolve("${style.name}-bundle.json"))
                    ) +
                        phaseHashes.mapValues { (_, hash) ->
                            mapOf("modelSha256" to hash, "targetKl" to .02, "actualKl" to .02)
                        })
            }
        files.write(
            "calibration.json",
            mapOf(
                "kind" to "card-continuation-calibration-1",
                "passed" to true,
                "originalBundleSha256" to PurchaseJson.hash(files.directory.resolve("bundle.json")),
                "actualFits" to 0,
                "terminalLabelsDecoded" to false,
                "styles" to calibration,
            ),
        )
        files.write(
            "continuation-source.json",
            mapOf(
                "kind" to "card-continuation-interaction-1",
                "calibration" to
                    mapOf(
                        "path" to "calibration.json",
                        "sha256" to PurchaseJson.hash(files.directory.resolve("calibration.json")),
                    ),
                "arenas" to
                    CardStyleInteractionStyle.entries.associate {
                        it.name to
                            if (it == CardStyleInteractionStyle.G3) "arena.json"
                            else "${it.name}-arena.json"
                    },
                "origins" to
                    (0 until 1024).map {
                        CardStyleInteractionSeeds(it, listOf(-900000000L + it, -800000000L + it))
                    },
            ),
        )
    }

    val input: CardStyleInteractionInput
        get() = CardStyleInteractionInput.read(source)

    override fun close() = old.close()
}
