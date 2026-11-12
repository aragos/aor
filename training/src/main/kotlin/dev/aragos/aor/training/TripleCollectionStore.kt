package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Only completed worlds are compressed; workers return indices and never retain a population. */
internal class TripleCollectionStore(
    private val input: TripleCollectionInput,
    val directory: Path,
) {
    private val artifacts = TrainingArtifacts(directory)
    private val validation = TripleTrajectoryValidation(input)

    fun prepare() {
        require(!Files.exists(directory.resolve("collection.json"))) {
            "Triple preparation cannot overwrite an existing manifest."
        }
        input.snapshot(artifacts)
        artifacts.write("collection.json", manifest(input))
    }

    fun save(game: TripleGameTrajectory): Int {
        val node = wire(game)
        validation.validate(node, game.joint.index)
        val target = directory.resolve("game-${game.joint.index}.json.gz")
        require(!Files.exists(target)) {
            "A complete original game cannot be overwritten or retried."
        }
        require((game.joint.index in input.replayIndices) == (game.joint.canonicalReplay != null)) {
            "Only the prospective replay worlds retain complete canonical histories."
        }
        CardTrajectoryFiles.save(target, node)
        check(CardTrajectoryFiles.readIfPresent(target) == node) {
            "Compressed trajectory bytes changed."
        }
        game.joint.canonicalReplay?.let {
            artifacts.write(
                "replay-${game.joint.index}.json",
                replayDocument(input, game.joint.index, PurchaseJson.hash(target), it),
            )
        }
        return game.joint.index
    }

    fun finish(indices: List<Int>) {
        require(!Files.exists(directory.resolve("finished.json"))) {
            "Triple completion is published exactly once."
        }
        require(indices.sorted() == input.joint.seeds.indices.toList()) {
            "Every original world must complete exactly once."
        }
        val games =
            input.joint.seeds.indices.associate { index ->
                val name = "game-$index.json.gz"
                validation.validate(
                    requireNotNull(CardTrajectoryFiles.readIfPresent(directory.resolve(name))) {
                        "Every original complete game is required."
                    },
                    index,
                )
                name to PurchaseJson.hash(directory.resolve(name))
            }
        val replays =
            input.replayIndices.associate { index ->
                val name = "replay-$index.json"
                val node = PurchaseJson.read(directory.resolve(name))
                validateReplay(
                    node,
                    index,
                    requireNotNull(games["game-$index.json.gz"]) { "Game hash is required." },
                )
                name to PurchaseJson.hash(directory.resolve(name))
            }
        input.snapshot(artifacts)
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to TripleCollectionInput.VERSION,
                "signature" to input.signature,
                "complete" to true,
                "games" to input.joint.seeds.size,
                "gameSha256" to games,
                "canonicalSha256" to replays,
            ),
        )
    }

    fun validateReplay(node: JsonNode, index: Int, trajectorySha: String) {
        require(
            node["kind"]?.asText() == "triple-on-policy-canonical-1" &&
                node["signature"]?.asText() == input.signature &&
                node["index"]?.intValue() == index &&
                node["trajectorySha256"]?.asText() == trajectorySha &&
                node["replayVerified"]?.booleanValue() == true &&
                node["header"]?.isObject == true &&
                node["entries"]?.isArray == true &&
                node["entriesSha256"]?.asText() ==
                    CardAdvantageExecutor.hash(node["entries"].map(JsonNode::asText))
        ) {
            "A canonical history must retain its original trajectory, entries and native replay proof."
        }
    }

    companion object {
        fun wire(value: Any): JsonNode =
            PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))

        fun manifest(input: TripleCollectionInput): Map<String, Any> =
            mapOf(
                "kind" to TripleCollectionInput.VERSION,
                "schemaVersion" to 1,
                "signature" to input.signature,
                "trajectoryEncoding" to CardTrajectoryFiles.ENCODING,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "source" to
                    mapOf(
                        "path" to input.source.toString(),
                        "sha256" to input.sourceSha256,
                        "snapshot" to "source.json",
                    ),
                "jointSource" to
                    mapOf(
                        "path" to input.joint.source.toString(),
                        "sha256" to input.joint.sourceSha256,
                        "snapshot" to "joint/source.json",
                    ),
                "joint" to JointCollectionStore.manifest(input.joint, input.signature),
                "expansion" to
                    mapOf(
                        "path" to input.expansion.path.toString(),
                        "sha256" to input.expansion.sha256,
                        "snapshot" to "expansion.json",
                        "modelSchemaVersion" to 2,
                        "actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION,
                        "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
                        "stateFeatureNames" to input.dictionary.stateFeatureNames,
                        "actionFeatureNames" to input.dictionary.actionFeatureNames,
                    ),
                "controlledBehavior" to input.controlledBehavior,
                "snapshots" to input.snapshots(),
                "replayIndices" to input.replayIndices,
            )

        fun replayDocument(
            input: TripleCollectionInput,
            index: Int,
            hash: String,
            replay: PurchaseCanonicalReplay,
        ): Map<String, Any> =
            mapOf(
                "kind" to "triple-on-policy-canonical-1",
                "signature" to input.signature,
                "index" to index,
                "trajectorySha256" to hash,
                "replayVerified" to true,
                "header" to replay.header,
                "entries" to replay.entries,
                "entriesSha256" to CardAdvantageExecutor.hash(replay.entries),
            )
    }
}
