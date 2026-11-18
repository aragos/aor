package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Only completed worlds are compressed; workers return indices and never retain a population. */
internal class ExpansionOnPolicyStore(
    private val input: ExpansionOnPolicyInput,
    val directory: Path,
) {
    private val artifacts = TrainingArtifacts(directory)
    private val validation = ExpansionOnPolicyValidation(input)

    fun prepare() {
        input.snapshot(artifacts)
        artifacts.write("collection.json", manifest(input))
    }

    fun save(game: ExpansionOnPolicyGame): Int {
        val node = wire(game)
        validation.validate(node, game.index)
        val target = directory.resolve("game-${game.index}.json.gz")
        require(!Files.exists(target)) {
            "A complete original game cannot be overwritten or retried."
        }
        require((game.index in input.replayIndices) == (game.canonicalReplay != null)) {
            "Only the prospective replay worlds retain complete canonical histories."
        }
        CardTrajectoryFiles.save(target, node)
        check(CardTrajectoryFiles.readIfPresent(target) == node) {
            "Compressed trajectory bytes changed."
        }
        game.canonicalReplay?.let {
            artifacts.write(
                "replay-${game.index}.json",
                replayDocument(input, game.index, PurchaseJson.hash(target), it),
            )
        }
        return game.index
    }

    fun finish(indices: List<Int>) {
        require(indices.sorted() == input.seeds.indices.toList()) {
            "Every original world must complete exactly once."
        }
        val games =
            input.seeds.indices.associate { index ->
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
                "kind" to ExpansionOnPolicyInput.VERSION,
                "signature" to input.signature,
                "complete" to true,
                "games" to input.seeds.size,
                "gameSha256" to games,
                "canonicalSha256" to replays,
            ),
        )
    }

    fun validateReplay(node: JsonNode, index: Int, trajectorySha: String) {
        require(
            node["kind"]?.asText() == "expansion-on-policy-canonical-1" &&
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

        fun manifest(input: ExpansionOnPolicyInput): Map<String, Any> =
            mapOf(
                "kind" to ExpansionOnPolicyInput.VERSION,
                "schemaVersion" to 1,
                "signature" to input.signature,
                "trajectoryEncoding" to CardTrajectoryFiles.ENCODING,
                "configuration" to input.configuration.id,
                "rulesVersion" to RULES_VERSION,
                "dataVersion" to DATA_VERSION,
                "games" to input.seeds.size,
                "seed" to input.seed,
                "seeds" to input.seeds,
                "replayIndices" to input.replayIndices,
                "source" to
                    mapOf(
                        "path" to input.source.toString(),
                        "sha256" to input.sourceSha256,
                        "snapshot" to "source.json",
                    ),
                "arena" to
                    mapOf(
                        "path" to input.arenaSource.toString(),
                        "sha256" to input.arenaSourceSha256,
                        "snapshot" to "arena.json",
                    ),
                "actor" to
                    mapOf(
                        "path" to input.actor.path.toString(),
                        "sha256" to input.actor.sha256,
                        "snapshot" to "actor.json",
                        "modelSchemaVersion" to 2,
                        "actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION,
                        "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
                    ),
                "actorVersion" to ExpansionOnPolicyInput.ACTOR_VERSION,
                "dictionaries" to
                    mapOf(
                        "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
                        "stateFeatureNames" to input.dictionary.stateFeatureNames,
                        "actionFeatureNames" to input.dictionary.actionFeatureNames,
                    ),
                "frozenCardPurchaseBundleSha256" to input.frozenCardPurchaseBundleSha256,
                "referenceExpansionModelSha256" to input.referenceExpansionModelSha256,
                "ecologySnapshotDirectory" to "ecology",
                "policyBindings" to input.policyBindings,
                "controlledSeat" to "index % playerCount",
                "opponentIndices" to List(4) { 0 },
                "reward" to ".8 * winShare + .2 * rankReward",
            )

        fun replayDocument(
            input: ExpansionOnPolicyInput,
            index: Int,
            hash: String,
            replay: PurchaseCanonicalReplay,
        ): Map<String, Any> =
            mapOf(
                "kind" to "expansion-on-policy-canonical-1",
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
