package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path

/** Re-run only predeclared historical origins through the same complete joint controller owner. */
internal object JointReproduction {
    internal data class Input(
        val collection: JointCollectionInput,
        val signature: String,
        val indices: List<Int>,
        val files: Map<Path, String>,
    )

    fun run(arguments: List<String>) {
        require(arguments.size == 8) {
            "Use --mode joint-replay --source COLLECTION --output NEW_DIRECTORY --hours HOURS."
        }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.keys == setOf("--mode", "--source", "--output", "--hours") &&
                fields["--mode"] == "joint-replay"
        ) {
            "Joint reproduction requires exactly mode, source, output and hours."
        }
        val source =
            Path.of(requireNotNull(fields["--source"]) { "Replay source required." }).toRealPath()
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Replay output required." })
                .toAbsolutePath()
                .normalize()
        val hours = requireNotNull(fields["--hours"]) { "Replay allowance required." }.toDouble()
        require(
            hours.isFinite() &&
                hours > 0.0 &&
                hours <= 2.0 &&
                !Files.exists(output) &&
                !output.startsWith(source) &&
                !source.startsWith(output)
        ) {
            "Joint reproduction requires a new separate output and a positive allowance of at most two hours."
        }
        val deadline = System.nanoTime() + (hours * 3_600_000_000_000).toLong()
        val input = read(source)
        val artifacts = TrainingArtifacts(output)
        val collector = JointCollector(input.collection, input.signature, deadline)
        val receipts =
            input.indices.map { index ->
                checkTokenValueDeadline(deadline)
                val game = collector.collect(index)
                val name = "game-$index.json.gz"
                val original =
                    requireNotNull(CardTrajectoryFiles.readIfPresent(source.resolve(name))) {
                        "Original joint game required."
                    }
                val reproduced = PurchaseJson.mapper.valueToTree<JsonNode>(game)
                require(original == reproduced) {
                    "Joint reproduction changed typed states, actions, probabilities, quotes or terminal labels at origin $index."
                }
                CardTrajectoryFiles.save(output.resolve(name), reproduced)
                val hash = PurchaseJson.hash(output.resolve(name))
                require(hash == input.files[source.resolve(name)]) {
                    "Joint trajectory bytes changed during reproduction."
                }
                val history =
                    JointCollectionStore.replayDocument(
                        input.signature,
                        index,
                        hash,
                        requireNotNull(game.canonicalReplay) {
                            "A declared joint reproduction requires canonical history."
                        },
                    )
                val replayName = "replay-$index.json"
                require(
                    PurchaseJson.read(source.resolve(replayName)) ==
                        PurchaseJson.mapper.valueToTree<JsonNode>(history)
                ) {
                    "Joint reproduction changed its complete canonical history at origin $index."
                }
                artifacts.write(replayName, history)
                require(
                    PurchaseJson.hash(output.resolve(replayName)) ==
                        input.files[source.resolve(replayName)]
                ) {
                    "Joint canonical history bytes changed during reproduction."
                }
                mapOf(
                    "index" to index,
                    "trajectorySha256" to hash,
                    "replaySha256" to PurchaseJson.hash(output.resolve(replayName)),
                )
            }
        require(
            input.files.all { (path, hash) -> PurchaseJson.hash(path) == hash } &&
                JointCollectionInput.read(input.collection.source).signature() == input.signature
        ) {
            "Joint original source or completed evidence changed during reproduction."
        }
        checkTokenValueDeadline(deadline)
        artifacts.write(
            "reproduction.json",
            mapOf(
                "kind" to "joint-reproduction-1",
                "signature" to input.signature,
                "collection" to source.toString(),
                "replayIndices" to input.indices,
                "gameReceipts" to receipts,
                "files" to input.files.mapKeys { it.key.toString() },
                "allGamesExact" to true,
                "allCanonicalHistoriesExact" to true,
                "newGameWorlds" to 0,
                "historicalTerminalExecutions" to receipts.size,
                "qualificationPassed" to false,
                "productionActivation" to false,
            ),
        )
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to "joint-reproduction-1",
                "signature" to input.signature,
                "reportSha256" to PurchaseJson.hash(output.resolve("reproduction.json")),
            ),
        )
    }

    /**
     * Read-only admission: complete collection, exact original identity and every declared sidecar.
     */
    internal fun read(directory: Path): Input {
        val manifest = PurchaseJson.read(directory.resolve("collection.json"))
        val source = Path.of(PurchaseJson.text(manifest.required("source"), "joint source"))
        val input = JointCollectionInput.read(source)
        val signature = input.signature()
        val indices =
            requireNotNull(input.ecology) { "Joint reproduction requires its opted-in ecology." }
                .replayIndices
        require(
            indices.isNotEmpty() &&
                manifest ==
                    PurchaseJson.mapper.valueToTree<JsonNode>(
                        JointCollectionStore.manifest(input, signature)
                    )
        ) {
            "Joint reproduction requires exact original metadata and prospective replay indices."
        }
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        val state = PurchaseJson.read(directory.resolve("state.json"))
        require(
            finished["signature"]?.asText() == signature &&
                finished["games"]?.intValue() == input.seeds.size &&
                state["signature"]?.asText() == signature &&
                state["phase"]?.asText() == "complete"
        ) {
            "Joint reproduction requires a completed original collection."
        }
        val bound =
            mapOf(
                "gameSha256" to "game-%d.json.gz",
                "jointStateSha256" to "joint-state-%d.json",
                "jointLabelSha256" to "joint-label-%d.json",
                "jointCoverageSha256" to "joint-coverage-%d.json",
            )
        val files = buildMap {
            input.files().forEach { put(it, PurchaseJson.hash(it)) }
            listOf(
                    "collection.json",
                    "finished.json",
                    "state.json",
                    "joint-state-dictionary.json",
                    "joint-coverage-summary.json",
                )
                .forEach {
                    put(directory.resolve(it), PurchaseJson.hash(directory.resolve(it)))
                }
            bound.forEach { (key, pattern) ->
                val hashes = PurchaseJson.strings(finished.required(key), key)
                require(hashes.size == input.seeds.size) {
                    "Completed joint evidence must retain every original world."
                }
                hashes.forEachIndexed { index, hash ->
                    put(directory.resolve(pattern.format(index)), hash)
                }
            }
            val replays = finished.required("canonicalReplaySha256")
            require(
                replays.isObject &&
                    replays.fieldNames().asSequence().toSet() ==
                        indices.map { "replay-$it.json" }.toSet()
            ) {
                "Completed joint histories must match exactly the prospective indices."
            }
            replays.properties().forEach {
                put(
                    directory.resolve(it.key),
                    PurchaseJson.text(it.value, "canonical history hash"),
                )
            }
        }
        require(
            files.all { (path, hash) -> PurchaseJson.hash(path) == hash } &&
                finished["jointCoverageSummarySha256"]?.asText() ==
                    PurchaseJson.hash(directory.resolve("joint-coverage-summary.json"))
        ) {
            "Completed joint evidence bytes changed after their completion receipt."
        }
        return Input(input, signature, indices, files)
    }
}
