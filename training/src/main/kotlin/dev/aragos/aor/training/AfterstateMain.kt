package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path

/** Fixed-policy data only; no optimizer, search, resume or production integration. */
internal object AfterstateMain {
    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Afterstate options use --name value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in
                        setOf(
                            "--mode",
                            "--source",
                            "--output",
                            "--workers",
                            "--hours",
                            "--describe",
                        )
                }
        ) {
            "Afterstate options must be unique and supported."
        }
        val replay = fields["--mode"] == "afterstate-replay"
        require(replay || fields["--mode"] == "afterstate-collect") {
            "Afterstate mode must be explicit."
        }
        val describe =
            fields["--describe"]?.let {
                require(it == "true") { "Describe must be true when present." }
                true
            } ?: false
        val ordinary = fields.filterKeys { it != "--describe" } + ("--mode" to "joint-collect")
        val options = JointCollectionOptions.parse(ordinary.flatMap { listOf(it.key, it.value) })
        require(options.hours <= if (replay) 1.0 else 4.0) {
            "Afterstate data is capped at four hours; one reproduction at one hour."
        }
        if (replay) {
            require(!describe && options.workers == 1) {
                "Historical afterstate reproduction uses one worker and no describe."
            }
            reproduce(options)
            return
        }
        val input = AfterstateInput.read(options.source)
        if (describe) {
            val artifacts = TrainingArtifacts(freshOutput(options.output, input))
            artifacts.write("manifest.json", input.metadata())
            artifacts.write("dictionary.json", input.dictionary())
            println(
                PurchaseJson.mapper.writeValueAsString(
                    input.metadata() + mapOf("dictionary" to input.dictionary())
                )
            )
            return
        }
        val output = freshOutput(options.output, input)
        val store = AfterstateStore(input, output)
        val deadline = System.nanoTime() + (options.hours * 3_600_000_000_000).toLong()
        val collector = AfterstateCollector(input, deadline)
        var completed = 0
        Tournaments(options.workers, deadline).use { workers ->
            workers.run(
                input.collection.seeds.indices.map { index ->
                    {
                        store.save(collector.collect(index))
                        index
                    }
                }
            ) {
                completed++
                store.progress(completed)
                println("Afterstate origin $completed/${input.collection.seeds.size} complete.")
            }
        }
        store.finish()
    }

    private fun freshOutput(path: Path, input: AfterstateInput): Path {
        require(!Files.exists(path) && input.files.none { it.startsWith(path) }) {
            "Afterstate output must be new and separate from every immutable input."
        }
        Files.createDirectories(path.parent)
        return Files.createDirectory(path).toRealPath()
    }

    private fun reproduce(options: JointCollectionOptions) {
        val original = options.source.toRealPath()
        val manifest = PurchaseJson.read(original.resolve("manifest.json"))
        val input = AfterstateInput.read(Path.of(manifest.required("source").asText()))
        require(
            manifest ==
                PurchaseJson.mapper.readTree(
                    PurchaseJson.mapper.writeValueAsBytes(input.metadata())
                )
        ) {
            "Reproduction requires the unchanged original source and runtime metadata."
        }
        val finishedPath = original.resolve("finished.json")
        val finished = PurchaseJson.read(finishedPath)
        require(
            finished.required("signature").asText() == input.signature &&
                finished.required("games").intValue() == input.collection.seeds.size
        ) {
            "Only a completed original chunk can be reproduced."
        }
        val pins = finished.required("artifactSha256")
        require(
            pins.isObject &&
                pins.properties().all { (name, hash) ->
                    name.matches(Regex("[a-zA-Z0-9._-]+")) &&
                        PurchaseJson.hash(original.resolve(name)) == hash.asText()
                }
        ) {
            "All original chunk evidence must match before historical reproduction."
        }
        val output = freshOutput(options.output, input)
        val index =
            requireNotNull(input.collection.ecology) { "Reproduction requires exact ecology." }
                .replayIndices
                .single()
        val deadline = System.nanoTime() + (options.hours * 3_600_000_000_000).toLong()
        val actual =
            JointCollector(input.collection, input.collection.signature(), deadline).collect(index)
        CardTrajectoryFiles.save(
            output.resolve("control-$index.json.gz"),
            PurchaseJson.mapper.valueToTree(actual),
        )
        val replay =
            requireNotNull(actual.canonicalReplay) {
                "The prescribed disabled-observer execution requires its verified history."
            }
        val originalReplay = PurchaseJson.read(original.resolve("replay-$index.json"))
        require(
            originalReplay.required("header") ==
                PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                    replay.header
                ) &&
                originalReplay.required("entries") ==
                    PurchaseJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(
                        replay.entries
                    )
        ) {
            "Observer-disabled canonical history differs from its observed original."
        }
        TrainingArtifacts(output).write("replay-$index.json", originalReplay)
        for (name in listOf("control-$index.json.gz", "replay-$index.json")) {
            require(
                PurchaseJson.hash(original.resolve(name)) == PurchaseJson.hash(output.resolve(name))
            ) {
                "Historical afterstate reproduction differs: $name."
            }
        }
        TrainingArtifacts(output)
            .write(
                "reproduction.json",
                mapOf(
                    "kind" to "afterstate-reproduction-1",
                    "signature" to input.signature,
                    "index" to index,
                    "originalFinishedSha256" to PurchaseJson.hash(finishedPath),
                    "passed" to true,
                    "observerEnabled" to false,
                    "allControlsAndCanonicalExact" to true,
                    "newWorlds" to 0,
                    "historicalReproductions" to 1,
                ),
            )
    }
}
