package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Reproduce only prospectively selected complete purchase games; never collect new origins. */
object PurchaseReproduction {
    private const val KIND = "purchase-reproduction"

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for purchase reproduction." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all { it in setOf("--mode", "--source", "--output", "--hours") } &&
                fields["--mode"] == "purchase-replay"
        ) {
            "Purchase reproduction accepts distinct mode, source, output and hours options only."
        }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) {
                        "Purchase reproduction requires --source."
                    }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) {
                        "Purchase reproduction requires --output."
                    }
                )
                .toAbsolutePath()
                .normalize()
        require(!Files.exists(output) && !output.startsWith(source) && !source.startsWith(output)) {
            "Purchase reproduction requires a new output separate from its completed collection."
        }
        val hours = fields["--hours"]?.toDouble() ?: 1.0
        require(hours.isFinite() && hours > 0.0 && hours <= 2.0) {
            "Purchase reproduction hours must be positive and at most two."
        }
        val started = Instant.now()
        val allowance = (hours * 3_600_000_000_000).toLong()
        val deadline = System.nanoTime() + allowance
        val input = read(source)
        val artifacts = TrainingArtifacts(output)
        val collector = PurchaseCollector(input.collection, input.signature, deadline)
        val receipts =
            input.indices.map { index ->
                checkTokenValueDeadline(deadline)
                val game = collector.collect(index)
                val name = "game-$index.json"
                val recorded = PurchaseJson.read(source.resolve(name))
                val reproduced = PurchaseJson.mapper.valueToTree<JsonNode>(game)
                require(recorded == reproduced) {
                    "Reproduced purchase game $index changed observations, sampling or terminal outcomes."
                }
                artifacts.write(name, reproduced)
                val trajectoryHash = PurchaseJson.hash(output.resolve(name))
                require(trajectoryHash == input.files[name]) {
                    "Reproduced purchase game $index differs from the original serialized trajectory."
                }
                val replayName = "replay-$index.json"
                val history =
                    PurchaseCollectionStore.replayDocument(
                        input.signature,
                        index,
                        trajectoryHash,
                        requireNotNull(game.canonicalReplay) {
                            "A declared purchase replay needs its complete history."
                        },
                    )
                require(
                    PurchaseJson.read(source.resolve(replayName)) ==
                        PurchaseJson.mapper.valueToTree<JsonNode>(history)
                ) {
                    "Reproduced purchase game $index changed its full canonical history."
                }
                artifacts.write(replayName, history)
                val replayHash = PurchaseJson.hash(output.resolve(replayName))
                require(replayHash == input.files[replayName]) {
                    "A reproduced purchase history differs from its original serialized bytes."
                }
                mapOf(
                    "index" to index,
                    "trajectorySha256" to trajectoryHash,
                    "replaySha256" to replayHash,
                    "purchaseDecisions" to game.decisions.size,
                )
            }
        require(
            input.files.all { (name, digest) ->
                PurchaseJson.hash(source.resolve(name)) == digest
            } &&
                PurchaseCollectionInput.read(input.collection.source).signature() == input.signature
        ) {
            "Purchase collection or original input bytes changed during reproduction."
        }
        checkTokenValueDeadline(deadline)
        artifacts.write(
            "reproduction.json",
            mapOf(
                "schemaVersion" to 1,
                "kind" to KIND,
                "collection" to source.toString(),
                "signature" to input.signature,
                "continuationProtocol" to PurchaseEcology.PROTOCOL,
                "replayIndices" to input.indices,
                "gameReceipts" to receipts,
                "files" to input.files,
                "workers" to 1,
                "allGamesExact" to true,
                "allCanonicalHistoriesExact" to true,
                "newGameWorlds" to 0,
                "qualificationPassed" to false,
                "productionActivation" to false,
                "startedAt" to started.toString(),
                "deadlineAt" to started.plusNanos(allowance).toString(),
            ),
        )
        artifacts.write(
            "finished.json",
            mapOf(
                "kind" to KIND,
                "signature" to input.signature,
                "reportSha256" to PurchaseJson.hash(output.resolve("reproduction.json")),
                "at" to Instant.now().toString(),
            ),
        )
        println(
            "Reproduced ${receipts.size} predeclared purchase trajectories and canonical histories exactly."
        )
    }

    internal data class Input(
        val collection: PurchaseCollectionInput,
        val signature: String,
        val indices: List<Int>,
        val files: Map<String, String>,
    )

    internal fun read(directory: Path): Input {
        val manifest = PurchaseJson.read(directory.resolve("collection.json"))
        val source = Path.of(PurchaseJson.text(manifest.required("source"), "purchase source"))
        val collection = PurchaseCollectionInput.read(source)
        val signature = collection.signature()
        val ecology =
            requireNotNull(collection.ecology) {
                "Purchase reproduction requires an opted-in ecology and predeclared replay indices."
            }
        require(
            manifest ==
                PurchaseJson.mapper.valueToTree<JsonNode>(
                    PurchaseCollectionStore.manifest(collection, signature)
                )
        ) {
            "Purchase reproduction requires unchanged original source, role and model metadata."
        }
        val state = PurchaseJson.read(directory.resolve("state.json"))
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        require(
            state["phase"]?.asText() == "complete" &&
                state["signature"]?.asText() == signature &&
                finished["kind"]?.asText() == "purchase-collect" &&
                finished["signature"]?.asText() == signature &&
                finished["games"]?.intValue() == collection.seeds.size
        ) {
            "Purchase reproduction requires the original complete collection."
        }
        val snapshots = buildMap {
            put("source.json", collection.sourceSha256)
            put("baseline.json", collection.baseline.sha256)
            collection.opponents.forEachIndexed { index, model ->
                put("opponent-$index.json", model.sha256)
            }
            put(
                "policy.json",
                requireNotNull(collection.modelSha256) { "Ecology requires a sampled actor." },
            )
            put(
                "opponent-purchase-policy.json",
                requireNotNull(collection.opponentPurchasePolicy) {
                        "Ecology requires its rival actor."
                    }
                    .sha256,
            )
            put(
                "shared-purchase-baseline.json",
                requireNotNull(collection.sharedPurchaseBaseline) {
                        "Ecology requires its purchase prior."
                    }
                    .sha256,
            )
            fun overlay(node: JsonNode) {
                if (node.isObject && node.has("snapshot")) {
                    put(
                        PurchaseJson.text(node.required("snapshot"), "snapshot"),
                        PurchaseJson.text(node.required("sha256"), "snapshot hash"),
                    )
                } else if (node.isContainerNode) node.forEach(::overlay)
            }
            overlay(manifest.required("expansionImitation"))
        }
        require(
            snapshots.all { (name, digest) -> PurchaseJson.hash(directory.resolve(name)) == digest }
        ) {
            "A frozen purchase collection model/source snapshot changed."
        }
        val gameHashes =
            collection.seeds.indices.map { index ->
                val path = directory.resolve("game-$index.json")
                PurchaseTrajectoryValidation(collection, signature)
                    .validate(PurchaseJson.read(path), index)
                PurchaseJson.hash(path)
            }
        require(finished["gameSha256"] == PurchaseJson.mapper.valueToTree<JsonNode>(gameHashes)) {
            "Purchase reproduction requires all original completed trajectory bytes."
        }
        val replayHashes =
            ecology.replayIndices.associate { index ->
                "replay-$index.json" to PurchaseJson.hash(directory.resolve("replay-$index.json"))
            }
        require(
            finished["replaySha256"] == PurchaseJson.mapper.valueToTree<JsonNode>(replayHashes)
        ) {
            "Purchase reproduction requires every original canonical-history sidecar."
        }
        val files =
            snapshots +
                replayHashes +
                collection.seeds.indices.associate { index ->
                    "game-$index.json" to gameHashes[index]
                } +
                listOf("collection.json", "state.json", "finished.json", "summary.json")
                    .associateWith {
                        PurchaseJson.hash(directory.resolve(it))
                    }
        return Input(collection, signature, ecology.replayIndices, files)
    }
}
