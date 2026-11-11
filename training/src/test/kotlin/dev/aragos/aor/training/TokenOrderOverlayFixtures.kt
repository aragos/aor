package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.aragos.aor.ai.BotModels
import java.nio.file.Path

/** Small declared banks and synthetic skipped receipts; these helpers create no training games. */
internal class TokenOrderOverlayFixture : AutoCloseable {
    val purchase = ArenaPurchaseFixture()
    val directory: Path = purchase.directory
    val artifacts = purchase.artifacts
    val source: Path = purchase.source

    init {
        artifacts.model(BotModels.default, "expansion.json")
        edit {
            put("trainingRoots", 2)
            put("validationRoots", 2)
            put("continuationProtocol", "ARENA_OVERLAYS_1")
            set<JsonNode>("replayOrigins", PurchaseJson.mapper.valueToTree(listOf(0, 3)))
            set<JsonNode>(
                "opponents",
                PurchaseJson.mapper.valueToTree(listOf(null, null, null, null)),
            )
            set<JsonNode>(
                "expansionImitation",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "candidateModel" to "expansion.json",
                        "opponentModels" to listOf(null, "expansion.json", null, "expansion.json"),
                    )
                ),
            )
            set<JsonNode>(
                "tokenOrder",
                PurchaseJson.mapper.valueToTree(
                    mapOf(
                        "opponentSelectors" to
                            listOf("ORDINARY", "ORDINARY", "FIXED_16_TO_15", "FIXED_16_TO_15")
                    )
                ),
            )
        }
    }

    val specification: TokenOrderSpecification
        get() = TokenOrderSpecification.read(source)

    fun edit(change: ObjectNode.() -> Unit) {
        val document = PurchaseJson.read(source) as ObjectNode
        document.change()
        artifacts.write("arena.json", document)
    }

    fun snapshots(): TokenOrderSpecification = specification.also {
        it.arena.snapshotModels(artifacts, "initial.json")
        it.arena.snapshotOverlays(artifacts)
    }

    fun completed(): TokenOrderSpecification {
        val specification = snapshots()
        val hash = PurchaseJson.hash(source)
        val signature = TokenOrderCollectionMain.signature(specification, hash)
        artifacts.ensureFileSnapshot(source, hash, "source.json")
        val metadata =
            TokenOrderCollectionMain.inputMetadata(
                specification,
                source,
                hash,
                signature,
                directory,
            )
        val hashes =
            (0 until specification.origins).map { index ->
                artifacts.write("origin-$index.json", skipped(specification, index, signature))
                PurchaseJson.hash(directory.resolve("origin-$index.json"))
            }
        val identity = mapOf("kind" to TokenOrderCollectionMain.KIND, "signature" to signature)
        artifacts.write("run.json", metadata)
        artifacts.write(
            "dataset.json",
            metadata +
                mapOf("complete" to true, "counts" to mapOf("SKIPPED" to specification.origins)),
        )
        artifacts.write("finished.json", identity + mapOf("originSha256" to hashes))
        artifacts.write("state.json", identity + mapOf("phase" to "complete"))
        return specification
    }

    fun skipped(
        specification: TokenOrderSpecification,
        index: Int,
        signature: String,
    ): TokenOrderOrigin {
        val lineup = specification.lineup(index)
        val request = specification.request(index)
        return TokenOrderOrigin(
            signature = signature,
            index = index,
            split = specification.split(index),
            configuration = lineup.configuration.id,
            gameSeed = lineup.seed,
            seat = lineup.seat,
            opponentIndices = lineup.opponentIndices,
            requestedEpoch = request.epoch,
            requestedOrdinal = request.ordinal,
            originDecisions = 10,
            status = TokenValueOriginStatus.SKIPPED,
            skipReason = TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED,
        )
    }

    override fun close() = purchase.close()
}
