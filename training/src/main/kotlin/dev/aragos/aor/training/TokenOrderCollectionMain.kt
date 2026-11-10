package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TokenObservationEncoder
import dev.aragos.aor.ai.TokenOrderPolicy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** Resumable, atomic origin collection. Only a completed manifest authorizes offline fitting. */
object TokenOrderCollectionMain {
    const val KIND = "token-order-collection"

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) { "Use --option value pairs for token-order collection." }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                } &&
                fields["--mode"] == "token-order-collect"
        ) {
            "Token-order options must be distinct mode, source, output, workers or hours."
        }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) {
                        "Token-order collection requires --source PATH."
                    }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) {
                        "Token-order collection requires --output PATH."
                    }
                )
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) { "Token-order output must not contain its source." }
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val workers = fields["--workers"]?.toInt() ?: ((cpus * 0.8).toInt() - 2).coerceAtLeast(1)
        val hours = fields["--hours"]?.toDouble() ?: 3.0
        require(hours.isFinite() && hours > 0 && hours <= 72) {
            "Token-order collection hours must be positive and at most 72."
        }
        val sourceHash = PurchaseJson.hash(source)
        val specification = TokenOrderSpecification.read(source)
        require(PurchaseJson.hash(source) == sourceHash) {
            "Token-order source changed while reading."
        }
        val signature = trainingSignature("$KIND-1:$sourceHash:${specification.identity()}")
        val artifacts = TrainingArtifacts(output)
        val previous = artifacts.read("state.json")
        require(
            previous == null ||
                previous["kind"]?.asText() == KIND && previous["signature"]?.asText() == signature
        ) {
            "Token-order source, code, models or settings changed; use a new output directory."
        }
        if (previous == null) {
            Files.list(output).use { paths ->
                require(
                    paths.allMatch {
                        it.fileName.toString() in
                            setOf(
                                "supervisor.json",
                                ".supervisor.lock",
                                "trainer.log",
                            )
                    }
                ) {
                    "Token-order output already contains another run."
                }
            }
        }
        val identity = mapOf("kind" to KIND, "signature" to signature)
        artifacts.write("state.json", identity + mapOf("phase" to "initializing"))
        artifacts.ensureFileSnapshot(source, sourceHash, "source.json")
        specification.arena.snapshotModels(artifacts, "initial.json")
        specification.arena.snapshotOverlays(artifacts)
        val startPath = output.resolve("started-at.txt")
        val started =
            if (Files.exists(startPath)) Instant.parse(Files.readString(startPath).trim())
            else Instant.now().also { Files.writeString(startPath, it.toString()) }
        val deadlineAt = originalDeadline(output, started, hours)
        val remaining =
            minOf(
                Duration.between(Instant.now(), deadlineAt).toNanos(),
                (hours * 3_600_000_000_000).toLong(),
            )
        require(remaining > 0) { "Token-order collection exhausted its original time allowance." }
        val deadline = System.nanoTime() + remaining
        val configuration = specification.arena.configurations.single()
        val dictionary =
            TokenObservationEncoder.dictionary(
                PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                TerritoryEncoder.TOKEN_2,
            )
        val originFiles = (0 until specification.origins).map { "origin-$it.json" }
        val modelFiles =
            listOf("initial.json", "incumbent.json") +
                specification.arena.opponents.indices.map { "opponent-$it.json" }
        val rootRequests =
            (0 until specification.origins).map { index ->
                val lineup = specification.lineup(index)
                val request = specification.request(index)
                mapOf(
                    "index" to index,
                    "split" to specification.split(index).name,
                    "gameSeed" to lineup.seed,
                    "seat" to lineup.seat,
                    "opponentIndices" to lineup.opponentIndices,
                    "requestedEpoch" to request.epoch,
                    "requestedOrdinal" to request.ordinal,
                )
            }
        val metadata =
            identity +
                specification.arena.overlayMetadata("initial.json") +
                mapOf(
                    "originSchemaVersion" to 1,
                    "source" to source.toString(),
                    "sourceSha256" to sourceHash,
                    "configuration" to configuration.id,
                    "encoderVersion" to TerritoryEncoder.TOKEN_2.version,
                    "proposalVersion" to TokenOrderPolicy.PROPOSAL_VERSION,
                    "selectionProtocol" to TokenValueSelectionProtocol.PRECOMMITTED_ORDINAL_V1,
                    "stateFeatureNames" to dictionary.stateFeatureNames,
                    "actionFeatureNames" to dictionary.actionFeatureNames,
                    "trainingRoots" to specification.trainingRoots,
                    "validationRoots" to specification.validationRoots,
                    "continuationReplicates" to specification.continuationReplicates,
                    "seed" to specification.arena.seed,
                    "originFiles" to originFiles,
                    "rootRequests" to rootRequests,
                    "ordinaryModelSnapshots" to
                        modelFiles.associateWith { PurchaseJson.hash(output.resolve(it)) },
                    "startedAt" to started.toString(),
                    "deadlineAt" to deadlineAt.toString(),
                    "workers" to workers,
                    "target" to
                        "Mean paired full-tail terminal return difference; origin is the statistical unit.",
                )
        artifacts.write("run.json", metadata)
        artifacts.write("state.json", identity + mapOf("phase" to "collecting"))
        val collector = TokenOrderCollector(specification, signature, deadline)
        var completed = 0
        val origins =
            Tournaments(workers, deadline).use { pool ->
                pool.run(
                    (0 until specification.origins).map { index ->
                        {
                            val name = originFiles[index]
                            val node =
                                artifacts.read(name)
                                    ?: run {
                                        val origin = collector.collect(index)
                                        artifacts.write(name, origin)
                                        checkNotNull(artifacts.read(name)) {
                                            "A written token-order origin must be readable."
                                        }
                                    }
                            validateOrigin(node, specification, index, signature)
                            node
                        }
                    }
                ) {
                    completed++
                    if (completed % 8 == 0 || completed == specification.origins) {
                        println("Token-order origins $completed/${specification.origins}")
                    }
                }
            }
        val hashes = originFiles.map { PurchaseJson.hash(output.resolve(it)) }
        artifacts.read("finished.json")?.let { finished ->
            require(
                finished["signature"]?.asText() == signature &&
                    finished["originSha256"]?.map(JsonNode::asText) == hashes
            ) {
                "Completed token-order origins differ from their original receipt."
            }
        }
        artifacts.write(
            "dataset.json",
            metadata +
                mapOf(
                    "complete" to true,
                    "counts" to origins.groupingBy { it.required("status").asText() }.eachCount(),
                ),
        )
        artifacts.write(
            "finished.json",
            identity + mapOf("originSha256" to hashes, "at" to Instant.now().toString()),
        )
        artifacts.write("state.json", identity + mapOf("phase" to "complete"))
        println(
            "Token-order collection complete; fitting and independent confirmation remain separate."
        )
    }

    /** A direct resume may shorten its segment but cannot renew the original native allowance. */
    internal fun originalDeadline(output: Path, started: Instant, hours: Double): Instant {
        val path = output.resolve("deadline-at.txt")
        if (Files.exists(path)) return Instant.parse(Files.readString(path).trim())
        val deadline = started.plusNanos((hours * 3_600_000_000_000).toLong())
        Files.writeString(path, deadline.toString(), java.nio.file.StandardOpenOption.CREATE_NEW)
        return deadline
    }

    internal fun validateOrigin(
        node: JsonNode,
        specification: TokenOrderSpecification,
        index: Int,
        signature: String,
    ) {
        val lineup = specification.lineup(index)
        val request = specification.request(index)
        require(
            node["schemaVersion"]?.asInt() == 1 &&
                node["signature"]?.asText() == signature &&
                node["index"]?.asInt() == index &&
                node["split"]?.asText() == specification.split(index).name &&
                node["configuration"]?.asText() == lineup.configuration.id &&
                node["gameSeed"]?.asInt() == lineup.seed &&
                node["seat"]?.asInt() == lineup.seat &&
                node["opponentIndices"]?.map(JsonNode::asInt) == lineup.opponentIndices &&
                node["requestedEpoch"]?.asInt() == request.epoch &&
                node["requestedOrdinal"]?.asInt() == request.ordinal
        ) {
            "Token-order origin identity must match its immutable source, bank and precommitted root."
        }
        if (node["status"]?.asText() == TokenValueOriginStatus.SKIPPED.name) {
            require(
                !node.has("observation") &&
                    !node.has("alternatives") &&
                    !node.has("proposedIndices") &&
                    node["skipReason"]?.asText() in
                        listOf(
                            TokenValueSkipReason.REQUESTED_ROOT_NOT_REACHED.name,
                            TokenValueSkipReason.PRECOMMITTED_ROOT_INELIGIBLE.name,
                        )
            ) {
                "A skipped token-order root cannot publish observations or terminal targets."
            }
            return
        }
        require(
            node["status"]?.asText() == TokenValueOriginStatus.COLLECTED.name &&
                node["actualEpoch"]?.asInt() == request.epoch &&
                node["rootDecision"]?.asInt() == node["originDecisions"]?.asInt()
        ) {
            "A collected token-order root must retain its prospective position."
        }
        val observation = node.required("observation")
        val actions = observation.required("actions")
        val baseline = PurchaseJson.integer(observation.required("baselineIndex"), "baselineIndex")
        val proposed =
            node.required("proposedIndices").map { PurchaseJson.integer(it, "proposedIndex") }
        require(
            observation["encoderVersion"]?.asText() == TerritoryEncoder.TOKEN_2.version &&
                proposed.size in 2..8 &&
                proposed.distinct() == proposed &&
                proposed.first() == baseline &&
                proposed.all { it in 0 until actions.size() } &&
                actions[baseline]["command"]?.asText() == node["baselineCommand"]?.asText()
        ) {
            "Token-order proposals require complete version 2 quotes with the actual baseline first."
        }
        val alternatives = node.required("alternatives")
        require(alternatives.map { it.required("index").asInt() } == proposed) {
            "Token-order outcomes must preserve the declared proposal order."
        }
        alternatives.forEach { alternative ->
            val action = alternative.required("index").asInt()
            val outcomes = alternative.required("outcomes")
            require(
                alternative["command"] == actions[action]["command"] &&
                    outcomes.size() == 2 &&
                    outcomes.withIndex().all { (replicate, outcome) ->
                        outcome.required("continuationSeed").asLong() ==
                            TokenValueCollector.continuationSeed(lineup.seed, replicate) &&
                            listOf("reward", "winShare", "rankReward").all { key ->
                                PurchaseJson.number(outcome.required(key), key) in 0.0..1.0
                            } &&
                            outcome.required("scores").size() == lineup.configuration.playerCount
                    }
            ) {
                "Token-order outcomes require complete, ordered paired terminal samples."
            }
        }
    }
}
