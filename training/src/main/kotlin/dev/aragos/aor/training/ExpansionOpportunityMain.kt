package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * One bounded diagnostic bank. Completion records observations and paired returns, never fits or
 * promotes.
 */
object ExpansionOpportunityMain {
    const val KIND = "expansion-opportunity-collection"

    fun run(arguments: List<String>) {
        require(arguments.size % 2 == 0) {
            "Expansion diagnostic options require --option value pairs."
        }
        val fields = arguments.chunked(2).associate { it[0] to it[1] }
        require(
            fields.size * 2 == arguments.size &&
                fields.keys.all {
                    it in setOf("--mode", "--source", "--output", "--workers", "--hours")
                } &&
                fields["--mode"] == "expansion-opportunity-collect"
        ) {
            "Expansion diagnostic options must be distinct mode, source, output, workers or hours."
        }
        val source =
            Path.of(
                    requireNotNull(fields["--source"]) {
                        "Expansion diagnostics require --source PATH."
                    }
                )
                .toRealPath()
        val output =
            Path.of(
                    requireNotNull(fields["--output"]) {
                        "Expansion diagnostics require --output PATH."
                    }
                )
                .toAbsolutePath()
                .normalize()
        require(!source.startsWith(output)) {
            "Expansion diagnostic output must not contain its source."
        }
        val cpus =
            System.getenv("AOR_TRAINING_MACHINE_CPUS")?.toIntOrNull()
                ?: Runtime.getRuntime().availableProcessors()
        val workers = fields["--workers"]?.toInt() ?: ((cpus * .8).toInt() - 2).coerceAtLeast(1)
        val hours = fields["--hours"]?.toDouble() ?: 2.0
        require(hours.isFinite() && hours > 0 && hours <= 2.0) {
            "Expansion diagnostics require a positive time allowance of at most two hours."
        }
        val sourceHash = PurchaseJson.hash(source)
        val specification = ExpansionOpportunitySpecification.read(source)
        require(PurchaseJson.hash(source) == sourceHash) {
            "Expansion source changed while reading."
        }
        val signature = trainingSignature("$KIND-1:$sourceHash:${specification.identity()}")
        val artifacts = TrainingArtifacts(output)
        Files.list(output).use { paths ->
            require(
                paths.allMatch {
                    it.fileName.toString() in
                        setOf("supervisor.json", ".supervisor.lock", "trainer.log")
                }
            ) {
                "Expansion diagnostics require a new output directory; existing attempts cannot be renewed."
            }
        }
        val started = Instant.now()
        val deadlineAt = TokenOrderCollectionMain.originalDeadline(output, started, hours)
        val deadline = System.nanoTime() + (hours * 3_600_000_000_000).toLong()
        Files.writeString(output.resolve("started-at.txt"), started.toString())
        val identity = mapOf("kind" to KIND, "signature" to signature)
        artifacts.write("state.json", identity + mapOf("phase" to "initializing"))
        artifacts.ensureFileSnapshot(source, sourceHash, "source.json")
        specification.arena.snapshotModels(artifacts, "initial.json")
        specification.arena.snapshotOverlays(artifacts)
        val configuration = specification.arena.configurations.single()
        val dictionary =
            ExpansionObservationEncoder.dictionary(
                PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                TerritoryEncoder.EXPANSION_2,
            )
        val originFiles = (0 until specification.diagnosticRoots).map { "origin-$it.json" }
        val modelFiles =
            listOf("initial.json", "incumbent.json") +
                specification.arena.opponents.indices.map { "opponent-$it.json" }
        val requests =
            (0 until specification.diagnosticRoots).map { index ->
                val lineup = specification.lineup(index)
                mapOf(
                    "index" to index,
                    "gameSeed" to lineup.seed,
                    "seat" to lineup.seat,
                    "opponentIndices" to lineup.opponentIndices,
                    "requestedEpoch" to specification.epoch(index),
                    "proposalSeed" to ExpansionOpportunityProposals.seed(lineup.seed),
                    "continuationSeeds" to
                        List(2) { TokenValueCollector.continuationSeed(lineup.seed, it) },
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
                    "encoderVersion" to TerritoryEncoder.EXPANSION_2.version,
                    "proposalVersion" to ExpansionOpportunityProposals.VERSION,
                    "selectionProtocol" to "first-eligible-pass-in-predeclared-epoch-1",
                    "stateFeatureNames" to dictionary.stateFeatureNames,
                    "actionFeatureNames" to dictionary.actionFeatureNames,
                    "diagnosticRoots" to specification.diagnosticRoots,
                    "continuationReplicates" to 2,
                    "maximumAlternatives" to 3,
                    "maximumTerminalContinuations" to specification.diagnosticRoots * 8,
                    "seed" to specification.arena.seed,
                    "originFiles" to originFiles,
                    "rootRequests" to requests,
                    "ordinaryModelSnapshots" to
                        modelFiles.associateWith { PurchaseJson.hash(output.resolve(it)) },
                    "startedAt" to started.toString(),
                    "deadlineAt" to deadlineAt.toString(),
                    "workers" to workers,
                    "target" to
                        "Paired terminal return differences; equal-weight origin is the statistical unit; no best-arm labels.",
                    "fitting" to false,
                    "qualificationPassed" to false,
                    "productionActivation" to false,
                    "authorityScope" to
                        "Synthetic canonical histories are replay evidence only; proposals and encoded observations use actor-visible facts.",
                )
        artifacts.write("run.json", metadata)
        artifacts.write("state.json", identity + mapOf("phase" to "collecting"))
        val collector = ExpansionOpportunityCollector(specification, signature, deadline)
        var completed = 0
        val counts =
            Tournaments(workers, deadline).use { pool ->
                pool
                    .run(
                        (0 until specification.diagnosticRoots).map { index ->
                            {
                                val origin = collector.collect(index)
                                validateOrigin(
                                    PurchaseJson.mapper.valueToTree(origin),
                                    specification,
                                    index,
                                    signature,
                                )
                                artifacts.write(originFiles[index], origin)
                                origin.status
                            }
                        }
                    ) {
                        completed++
                        if (completed % 8 == 0 || completed == specification.diagnosticRoots) {
                            println(
                                "Expansion diagnostic origins $completed/${specification.diagnosticRoots}"
                            )
                        }
                    }
                    .groupingBy { it }
                    .eachCount()
            }
        artifacts.write("dataset.json", metadata + mapOf("complete" to true, "counts" to counts))
        artifacts.write(
            "finished.json",
            identity +
                mapOf(
                    "originSha256" to
                        originFiles.map {
                            PurchaseJson.hash(output.resolve(it))
                        },
                    "at" to Instant.now().toString(),
                ),
        )
        artifacts.write("state.json", identity + mapOf("phase" to "complete"))
        println(
            "Expansion diagnostic complete; no fitting, policy selection or qualification performed."
        )
    }

    internal fun validateOrigin(
        node: JsonNode,
        specification: ExpansionOpportunitySpecification,
        index: Int,
        signature: String,
    ) {
        val lineup = specification.lineup(index)
        require(
            node["schemaVersion"]?.asInt() == 1 &&
                node["signature"]?.asText() == signature &&
                node["index"]?.asInt() == index &&
                node["configuration"]?.asText() == lineup.configuration.id &&
                node["gameSeed"]?.asInt() == lineup.seed &&
                node["seat"]?.asInt() == lineup.seat &&
                node["opponentIndices"]?.map(JsonNode::asInt) == lineup.opponentIndices &&
                node["requestedEpoch"]?.asInt() == specification.epoch(index) &&
                node["proposalSeed"]?.asInt() == ExpansionOpportunityProposals.seed(lineup.seed)
        ) {
            "Expansion origin must match its immutable source, lineup and prospective epoch."
        }
        if (node["status"]?.asText() == ExpansionOpportunityStatus.SKIPPED.name) {
            require(
                node["skipReason"]?.asText() in ExpansionOpportunitySkip.entries.map { it.name } &&
                    listOf(
                            "observation",
                            "alternatives",
                            "proposedIndices",
                            "header",
                            "prefixEntries",
                            "meanAlternativeRewardDifference",
                            "meanAlternativeWinShareDifference",
                        )
                        .none(node::has)
            ) {
                "Skipped expansion origins cannot publish roots, observations, histories or targets."
            }
            return
        }
        require(
            node["status"]?.asText() == ExpansionOpportunityStatus.COLLECTED.name &&
                node["actualEpoch"]?.asInt() == specification.epoch(index) &&
                node["poolTokens"]?.asInt()?.let { it > 0 } == true &&
                node["rootDecision"] == node["originDecisions"] &&
                node["baselineCommand"]?.asText() == "pass"
        ) {
            "Collected expansion roots require the requested epoch, remaining public tokens and an actual pass baseline."
        }
        val observation = node.required("observation")
        val actions = observation.required("actions")
        val proposed =
            node.required("proposedIndices").map { PurchaseJson.integer(it, "proposedIndex") }
        val baseline = observation.required("baselineIndex").asInt()
        require(
            observation["encoderVersion"]?.asText() == TerritoryEncoder.EXPANSION_2.version &&
                proposed.size in 2..4 &&
                proposed.distinct() == proposed &&
                proposed.first() == baseline &&
                proposed.all { it in 0 until actions.size() } &&
                actions[baseline]["command"]?.asText() == "pass"
        ) {
            "Expansion proposals require the complete version 2 observation with baseline first and at most three alternatives."
        }
        val prefix = node.required("prefixEntries").map(JsonNode::asText)
        require(
            CardAdvantageExecutor.hash(prefix) == node["prefixSha256"]?.asText() &&
                node.has("header")
        ) {
            "Expansion root requires its canonical header, prefix and content hash."
        }
        val alternatives = node.required("alternatives")
        require(
            alternatives.map { it.required("index").asInt() } == proposed &&
                alternatives.first().required("family").asText() == "pass" &&
                alternatives.drop(1).map { it.required("family").asText() }.distinct().size ==
                    alternatives.size() - 1
        ) {
            "Expansion alternatives must retain proposal order and distinct non-pass action families."
        }
        val reference = alternatives.first().required("outcomes")
        alternatives.forEach { alternative ->
            val action = alternative.required("index").asInt()
            val outcomes = alternative.required("outcomes")
            require(alternative["command"] == actions[action]["command"] && outcomes.size() == 2) {
                "Expansion arms require exactly two full-tail outcomes for their offered command."
            }
            outcomes.forEachIndexed { replicate, outcome ->
                val terminal = outcome.required("terminal")
                val canonical = outcome.required("replayEntries").map(JsonNode::asText)
                require(
                    terminal.required("continuationSeed").asLong() ==
                        TokenValueCollector.continuationSeed(lineup.seed, replicate) &&
                        listOf("reward", "winShare", "rankReward").all {
                            PurchaseJson.number(terminal.required(it), it) in 0.0..1.0
                        } &&
                        terminal.required("scores").size() == lineup.configuration.playerCount &&
                        terminal.required("decisions").asInt() >
                            node.required("rootDecision").asInt() &&
                        outcome["replayVerified"]?.asBoolean() == true &&
                        canonical.take(prefix.size) == prefix &&
                        CardAdvantageExecutor.hash(canonical) == outcome["replaySha256"]?.asText()
                ) {
                    "Expansion outcomes require common future streams, complete scores and verified full canonical histories."
                }
            }
            fun differences(field: String) = outcomes.mapIndexed { replicate, outcome ->
                outcome.required("terminal").required(field).asDouble() -
                    reference[replicate].required("terminal").required(field).asDouble()
            }
            val rewards = differences("reward")
            val wins = differences("winShare")
            require(
                alternative.required("pairedRewardDifferences").map(JsonNode::asDouble) ==
                    rewards &&
                    alternative.required("pairedWinShareDifferences").map(JsonNode::asDouble) ==
                        wins &&
                    alternative.required("meanRewardDifference").asDouble() == rewards.average() &&
                    alternative.required("meanWinShareDifference").asDouble() == wins.average()
            ) {
                "Expansion labels must remain paired arm-minus-baseline means, never best-arm targets."
            }
        }
        require(
            node.required("meanAlternativeRewardDifference").asDouble() ==
                alternatives
                    .drop(1)
                    .map { it.required("meanRewardDifference").asDouble() }
                    .average() &&
                node.required("meanAlternativeWinShareDifference").asDouble() ==
                    alternatives
                        .drop(1)
                        .map { it.required("meanWinShareDifference").asDouble() }
                        .average()
        ) {
            "Origin summaries must average the precommitted alternatives without selection."
        }
    }
}
