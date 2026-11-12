package dev.aragos.aor.training

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** New-output-only native owner; an external campaign owns attempt count and original clocks. */
internal object TripleCollectionMain {
    val modes =
        setOf(
            "triple-on-policy-prepare",
            "triple-on-policy-collect",
            "triple-on-policy-reproduce",
        )

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Triple collection options must be key/value pairs." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(fields.size * 2 == arguments.size && fields["--mode"] in modes) {
            "Declare one explicit expansion on-policy mode without duplicate options."
        }
        val mode = fields["--mode"]
        val collect = mode == "triple-on-policy-collect"
        val replaying = mode == "triple-on-policy-reproduce"
        val expected =
            setOf("--mode", "--source", "--output") +
                (if (collect || replaying) setOf("--seconds") else emptySet()) +
                (if (collect) setOf("--workers") else emptySet()) +
                (if (replaying) setOf("--collection", "--index") else emptySet())
        require(fields.keys == expected) {
            "Only the mode's exact source, limits and output options are allowed."
        }
        fun path(name: String) =
            Path.of(requireNotNull(fields[name]) { "$name is required." }).toAbsolutePath()
        val input = TripleCollectionInput.read(path("--source"))
        val output = path("--output").normalize()
        require(!Files.exists(output)) {
            "Use one new output directory; collection never resumes or overwrites."
        }
        val seconds =
            if (collect || replaying) {
                requireNotNull(fields["--seconds"]?.toIntOrNull()) { "Seconds must be integral." }
                    .also {
                        require(it in 1..7200) {
                            "A native stage has one bounded allowance up to 7200 seconds."
                        }
                    }
            } else 0
        val workers =
            if (collect) {
                requireNotNull(fields["--workers"]?.toIntOrNull()) { "Workers must be integral." }
                    .also {
                        require(it in 1..10) {
                            "Declare at most 10 workers; Tournaments retains the actual CPU guard."
                        }
                    }
            } else 0
        if (replaying) {
            val index =
                requireNotNull(fields["--index"]?.toIntOrNull()) {
                    "Replay index must be integral."
                }
            reproduce(input, path("--collection"), output, index, seconds)
            return
        }
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val store = TripleCollectionStore(input, output)
        store.prepare()
        if (!collect) return
        val started = Instant.now()
        val artifacts = TrainingArtifacts(output)
        artifacts.write(
            "started.json",
            mapOf(
                "signature" to input.signature,
                "workers" to workers,
                "startedAt" to started.toString(),
                "deadlineAt" to started.plusSeconds(seconds.toLong()).toString(),
            ),
        )
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        try {
            val collector = TripleCollector(input, deadline)
            val completed =
                Tournaments(workers, deadline).use { pool ->
                    pool.run(
                        input.joint.seeds.indices.map { index ->
                            { store.save(collector.collect(index)) }
                        }
                    )
                }
            store.finish(completed)
        } catch (failure: Exception) {
            artifacts.write(
                "failed.json",
                mapOf(
                    "signature" to input.signature,
                    "failure" to failure.toString(),
                    "at" to Instant.now().toString(),
                ),
            )
            throw failure
        }
    }

    private fun reproduce(
        input: TripleCollectionInput,
        collection: Path,
        output: Path,
        index: Int,
        seconds: Int,
    ) {
        require(index in input.replayIndices) {
            "Only originally declared canonical worlds may be reproduced."
        }
        require(
            PurchaseJson.read(collection.resolve("collection.json")) ==
                TripleCollectionStore.wire(TripleCollectionStore.manifest(input))
        ) {
            "Reproduction requires the exact original source, behavior and complete role manifest."
        }
        val finished = PurchaseJson.read(collection.resolve("finished.json"))
        require(
            finished["kind"]?.asText() == TripleCollectionInput.VERSION &&
                finished["signature"]?.asText() == input.signature &&
                finished["complete"]?.booleanValue() == true &&
                finished["games"]?.intValue() == input.joint.seeds.size
        ) {
            "Only a completed original collection may be reproduced."
        }
        val gamePath = collection.resolve("game-$index.json.gz")
        val replayPath = collection.resolve("replay-$index.json")
        val gameSha = PurchaseJson.hash(gamePath)
        val replaySha = PurchaseJson.hash(replayPath)
        require(
            finished["gameSha256"][gamePath.fileName.toString()]?.asText() == gameSha &&
                finished["canonicalSha256"][replayPath.fileName.toString()]?.asText() == replaySha
        ) {
            "Original trajectory and canonical hashes must match completion before reproduction."
        }
        val expected =
            requireNotNull(CardTrajectoryFiles.readIfPresent(gamePath)) {
                "Original game is required."
            }
        TripleTrajectoryValidation(input).validate(expected, index)
        val canonical = PurchaseJson.read(replayPath)
        TripleCollectionStore(input, collection).validateReplay(canonical, index, gameSha)
        Files.createDirectories(output.parent)
        Files.createDirectory(output)
        val deadline = System.nanoTime() + seconds * 1_000_000_000L
        val actual = TripleCollector(input, deadline).collect(index)
        require(TripleCollectionStore.wire(actual) == expected) {
            "Reproduction must match every original row, command, likelihood, value and terminal."
        }
        val replay =
            requireNotNull(actual.joint.canonicalReplay) {
                "Prospective canonical history is required."
            }
        require(
            TripleCollectionStore.wire(
                TripleCollectionStore.replayDocument(input, index, gameSha, replay)
            ) == canonical
        ) {
            "Reproduction must match every original canonical entry and header."
        }
        require(
            PurchaseJson.hash(gamePath) == gameSha && PurchaseJson.hash(replayPath) == replaySha
        ) {
            "Original evidence changed during reproduction."
        }
        TrainingArtifacts(output)
            .write(
                "replay.json",
                mapOf(
                    "kind" to "triple-on-policy-reproduction-1",
                    "signature" to input.signature,
                    "index" to index,
                    "passed" to true,
                    "trajectorySha256" to gameSha,
                    "canonicalSha256" to replaySha,
                    "completeTypedRecordsEqual" to true,
                    "completeCanonicalEqual" to true,
                ),
            )
    }
}
