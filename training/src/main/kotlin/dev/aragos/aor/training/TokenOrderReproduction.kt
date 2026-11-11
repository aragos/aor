package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Read-only reproduction of exactly the origins declared before an opted-in collection began. */
object TokenOrderReproduction {
    private const val KIND = "token-order-reproduction"

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for token reproduction." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all { it in setOf("--mode", "--source", "--output", "--hours") } &&
                fields["--mode"] == "token-order-replay"
        ) {
            "Token reproduction accepts distinct mode, source, output and hours options only."
        }
        val source =
            Path.of(requireNotNull(fields["--source"]) { "Token reproduction requires --source." })
                .toRealPath()
        val output =
            Path.of(requireNotNull(fields["--output"]) { "Token reproduction requires --output." })
                .toAbsolutePath()
                .normalize()
        require(!Files.exists(output) && !output.startsWith(source) && !source.startsWith(output)) {
            "Token reproduction requires a new output directory separate from the completed bank."
        }
        val hours = fields["--hours"]?.toDouble() ?: 1.0
        require(hours.isFinite() && hours > 0.0 && hours <= 2.0) {
            "Token reproduction hours must be positive and at most two."
        }
        val started = Instant.now()
        val deadline = System.nanoTime() + (hours * 3_600_000_000_000).toLong()
        val input = read(source)
        val artifacts = TrainingArtifacts(output)
        val collector = TokenOrderCollector(input.specification, input.signature, deadline)
        val receipts =
            input.specification.replayOrigins.map { index ->
                checkTokenValueDeadline(deadline)
                val name = "origin-$index.json"
                val recorded = PurchaseJson.read(source.resolve(name))
                val reproduced = collector.collect(index)
                verifyOrigin(recorded, reproduced)
                artifacts.write(name, reproduced)
                val originalHash =
                    requireNotNull(input.files[name]) {
                        "A predeclared reproduction requires its completed origin hash."
                    }
                val reproducedHash = PurchaseJson.hash(output.resolve(name))
                require(reproducedHash == originalHash) {
                    "Reproduced origin $index differs from its exact completed serialized bytes."
                }
                mapOf(
                    "index" to index,
                    "status" to reproduced.status.name,
                    "originalSha256" to originalHash,
                    "reproducedSha256" to reproducedHash,
                    "alternativeCount" to reproduced.alternatives.orEmpty().size,
                    "continuationOutcomeCount" to
                        reproduced.alternatives.orEmpty().sumOf { it.outcomes.size },
                )
            }
        require(
            input.files.all { (name, digest) -> PurchaseJson.hash(source.resolve(name)) == digest }
        ) {
            "Completed collection inputs changed while reproducing their declared origins."
        }
        checkTokenValueDeadline(deadline)
        artifacts.write(
            "reproduction.json",
            mapOf(
                "schemaVersion" to 1,
                "kind" to KIND,
                "collection" to source.toString(),
                "signature" to input.signature,
                "continuationProtocol" to input.specification.continuationProtocol?.name,
                "replayOrigins" to input.specification.replayOrigins,
                "originReceipts" to receipts,
                "files" to input.files,
                "allOriginsExact" to true,
                "newOriginWorlds" to 0,
                "newContinuationWorlds" to 0,
                "qualificationPassed" to false,
                "productionActivation" to false,
                "startedAt" to started.toString(),
                "deadlineAt" to started.plusNanos((hours * 3_600_000_000_000).toLong()).toString(),
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
        println("Reproduced ${receipts.size} predeclared completed token origins exactly.")
    }

    internal data class Input(
        val specification: TokenOrderSpecification,
        val signature: String,
        val files: Map<String, String>,
    )

    internal fun read(directory: Path): Input =
        readWithSignature(directory, signatureFor = TokenOrderCollectionMain::signature)

    /** Explicit, separately pinned augmentation compatibility; the legacy reader remains strict. */
    internal fun readParent(
        directory: Path,
        runtime: TokenOrderParentRuntime,
        source: TokenOrderParentSource,
    ): Input =
        readWithSignature(directory, source) { specification, sourceHash ->
            runtime.signature(specification, sourceHash)
        }

    private fun readWithSignature(
        directory: Path,
        historical: TokenOrderParentSource? = null,
        signatureFor: (TokenOrderSpecification, String) -> String,
    ): Input {
        val manifest = PurchaseJson.read(directory.resolve("dataset.json"))
        val finished = PurchaseJson.read(directory.resolve("finished.json"))
        val run = PurchaseJson.read(directory.resolve("run.json"))
        val state = PurchaseJson.read(directory.resolve("state.json"))
        val source =
            historical?.originalPath
                ?: Path.of(PurchaseJson.text(manifest.required("source"), "collection source"))
        val sourceHash = historical?.originalSha256 ?: PurchaseJson.hash(source)
        require(
            sourceHash == manifest["sourceSha256"]?.asText() &&
                sourceHash == PurchaseJson.hash(directory.resolve("source.json"))
        ) {
            "Reproduction requires the unchanged original source and its completed snapshot."
        }
        val specification = historical?.specification ?: TokenOrderSpecification.read(source)
        require(specification.continuationProtocol != null) {
            "Token reproduction requires the explicit arena continuation protocol."
        }
        val signature = signatureFor(specification, sourceHash)
        val expectedRun =
            (manifest.deepCopy<JsonNode>() as ObjectNode).apply {
                remove(listOf("complete", "counts"))
            }
        require(
            manifest["complete"]?.asBoolean() == true &&
                manifest["originSchemaVersion"]?.asInt() == 1 &&
                run == expectedRun &&
                state["phase"]?.asText() == "complete" &&
                listOf(manifest, finished, state).all {
                    it["kind"]?.asText() == TokenOrderCollectionMain.KIND &&
                        it["signature"]?.asText() == signature
                }
        ) {
            "Reproduction requires a complete bank with its original runtime, signature and manifest."
        }
        val inputs =
            TokenOrderCollectionMain.inputMetadata(
                specification,
                source,
                sourceHash,
                signature,
                directory,
            )
        require(
            inputs.all { (key, value) ->
                val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(value)
                manifest[key] == (historical?.originalMetadata(metadata) ?: metadata)
            }
        ) {
            "Completed token continuation, reference, prospective requests or overlay bindings changed."
        }
        val files = buildMap {
            listOf("dataset.json", "finished.json", "run.json", "state.json", "source.json")
                .forEach { put(it, PurchaseJson.hash(directory.resolve(it))) }
            val ordinary =
                listOf(
                    "initial.json" to specification.arena.candidate,
                    "incumbent.json" to specification.arena.incumbent,
                ) +
                    specification.arena.opponents.mapIndexed { index, model ->
                        "opponent-$index.json" to model
                    }
            val hashes = manifest.required("ordinaryModelSnapshots")
            require(
                hashes.properties().map { it.key }.toSet() == ordinary.map { it.first }.toSet()
            ) {
                "Reproduction requires every declared ordinary model snapshot."
            }
            for ((name, model) in ordinary) {
                val path = directory.resolve(name)
                require(
                    TrainingArtifacts.readModel(
                        path,
                        specification.arena.experimentalRulesTransfer,
                    ) == model
                ) {
                    "Ordinary snapshot $name differs from the source's actual reference policy."
                }
                put(name, PurchaseJson.text(hashes.required(name), "ordinary snapshot hash"))
            }
            overlaySnapshots(PurchaseJson.mapper.valueToTree(inputs)).forEach { (name, hash) ->
                put(name, hash)
            }
            val names = (0 until specification.origins).map { "origin-$it.json" }
            val originHashes =
                PurchaseJson.strings(finished.required("originSha256"), "origin hashes")
            require(
                manifest["originFiles"]?.map(JsonNode::asText) == names &&
                    originHashes.size == names.size
            ) {
                "A completed token bank must bind every prospective origin, including skips."
            }
            val counts =
                names
                    .mapIndexed { index, name ->
                        val origin = PurchaseJson.read(directory.resolve(name))
                        TokenOrderCollectionMain.validateOrigin(
                            origin,
                            specification,
                            index,
                            signature,
                        )
                        put(name, originHashes[index])
                        origin.required("status").asText()
                    }
                    .groupingBy { it }
                    .eachCount()
            require(manifest["counts"] == PurchaseJson.mapper.valueToTree<JsonNode>(counts)) {
                "Completed token counts must preserve every collected and skipped origin."
            }
        }
        require(
            files.all { (name, digest) -> PurchaseJson.hash(directory.resolve(name)) == digest }
        ) {
            "A completed token snapshot or origin changed before reproduction."
        }
        return Input(specification, signature, files)
    }

    internal fun verifyOrigin(recorded: JsonNode, reproduced: TokenOrderOrigin) {
        require(recorded == PurchaseJson.mapper.valueToTree<JsonNode>(reproduced)) {
            "Reproduced token origin changed its reference, proposals, private-safe observation or paired outcomes."
        }
    }

    private fun overlaySnapshots(node: JsonNode): Map<String, String> = buildMap {
        if (node.isObject && node.has("snapshot")) {
            val name = PurchaseJson.text(node.required("snapshot"), "overlay snapshot")
            require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
                "Overlay snapshots must use simple file names."
            }
            put(name, PurchaseJson.text(node.required("sha256"), "overlay hash"))
        } else {
            for (child in node) putAll(overlaySnapshots(child))
        }
    }
}
