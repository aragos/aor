package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionPairedEncoder
import java.nio.file.Files
import java.nio.file.Path

/** Incremental compressed evidence; workers return compact receipts, never whole populations. */
internal class CardStyleInteractionStore(
    private val input: CardStyleInteractionInput,
    val directory: Path,
) {
    private val artifacts = TrainingArtifacts(directory)

    fun prepare() {
        input.snapshot(artifacts)
        artifacts.write(
            "dictionary.json",
            mapOf(
                "encoderVersion" to ExpansionPairedEncoder.VERSION,
                "dictionary" to
                    ExpansionPairedEncoder.dictionary(
                        PublicCatalog.forPlayers(4, input.configuration.rules)
                    ),
            ),
        )
        artifacts.write(
            "prepared.json",
            mapOf(
                "kind" to "card-continuation-interaction-1",
                "signature" to input.signature,
                "sourceSha256" to input.sourceSha256,
                "calibrationSha256" to input.calibrationSha256,
                "configuration" to input.configuration.id,
                "policyBindings" to
                    input.arenas.mapValues { (_, arena) -> arena.overlayMetadata() },
                "origins" to input.origins,
                "requests" to (0 until 1024).map(::CardStyleInteractionRequest),
                "maximumOriginPrefixes" to 1024,
                "maximumTailGames" to 20480,
                "maximumTerminalGames" to 21504,
                "fits" to 0,
                "selectedVariant" to null,
                "primary" to
                    "four prespecified variant-minus-G3 acquisition interactions; two-sided98.75% each; Bonferroni family95%",
                "continuation" to
                    "persistent controlled-seat C/P only; original absolute PLAYER policy seeds; fixed G3 rivals",
                "missing" to "unavailable card or requested window remains missing; no refill",
                "runtimeActivation" to false,
            ),
        )
    }

    fun save(origin: CardStyleInteractionOrigin): ExpansionPairedReceipt {
        val index = origin.request.index
        val wire = wire(origin)
        validate(wire, index)
        val name = "origin-$index.json.gz"
        val path = directory.resolve(name)
        require(!Files.exists(path)) {
            "Original completed evidence cannot be overwritten or retried."
        }
        CardTrajectoryFiles.save(path, wire)
        check(CardTrajectoryFiles.readIfPresent(path) == wire) {
            "Compressed evidence must retain every exact field."
        }
        return ExpansionPairedReceipt(
            index,
            name,
            PurchaseJson.hash(path),
            origin.status == ExpansionOriginStatus.COLLECTED,
            origin.prefix.terminal,
            origin.prefix.playerDecisions,
            origin.continuations.sumOf { row -> row.arms.sumOf { it.outcomes.size } },
        )
    }

    fun finish(receipts: List<ExpansionPairedReceipt>) {
        require(receipts.map { it.index }.sorted() == (0 until 1024).toList()) {
            "All original1024 requests must finish once, including missing roots."
        }
        for (receipt in receipts) {
            val path = directory.resolve(receipt.file)
            require(PurchaseJson.hash(path) == receipt.sha256) { "Completed origin bytes changed." }
            validate(
                requireNotNull(CardTrajectoryFiles.readIfPresent(path)) {
                    "Original evidence is missing."
                },
                receipt.index,
            )
        }
        input.snapshot(artifacts)
        artifacts.write(
            "finished.json",
            mapOf(
                "signature" to input.signature,
                "complete" to true,
                "origins" to receipts.sortedBy { it.index },
                "originPrefixes" to receipts.size,
                "capturedPrefixes" to receipts.count { it.captured },
                "missingTerminalGames" to receipts.count { it.prefixTerminal },
                "tailGames" to receipts.sumOf { it.tailGames },
                "scientificTerminalGames" to
                    receipts.sumOf { it.tailGames + if (it.prefixTerminal) 1 else 0 },
            ),
        )
    }

    private fun validate(origin: JsonNode, index: Int) {
        require(
            origin["signature"].asText() == input.signature &&
                origin["request"] == wire(CardStyleInteractionRequest(index)) &&
                origin["streams"] == wire(input.origins[index])
        ) {
            "Original study, schedule and streams must remain exact."
        }
        val prefix = origin["prefix"]
        require(
            prefix["replayVerified"].booleanValue() &&
                prefix["canonicalSha256"].asText() ==
                    CardAdvantageExecutor.hash(prefix["entries"].map(JsonNode::asText))
        ) {
            "The canonical prefix must retain its native replay proof and exact bytes."
        }
        if (origin["status"].asText() == ExpansionOriginStatus.MISSING.name) {
            require(
                origin["continuations"].isEmpty &&
                    origin["actorInput"].isNull &&
                    prefix["terminal"].booleanValue() &&
                    origin["missingReason"].asText() in
                        CardStyleInteractionMissing.entries.map { it.name }
            ) {
                "Missing roots retain only normal terminal backbones, with an explicit exclusion."
            }
            return
        }
        require(
            origin["status"].asText() == ExpansionOriginStatus.COLLECTED.name &&
                origin["exactIncumbentVerified"].booleanValue() &&
                !prefix["terminal"].booleanValue()
        ) {
            "Collected roots require the exact unchanged E command before terminal continuation."
        }
        val rows = origin["continuations"]
        require(
            rows.map { it["style"].asText() } == CardStyleInteractionStyle.entries.map { it.name }
        ) {
            "All five prespecified continuation styles must be retained in fixed order."
        }
        val offered = origin["actorInput"]["encoded"]["actions"].map { it["command"].asText() }
        val commands =
            listOf(origin["incumbentCommand"].asText(), CardStyleInteractionCollector.ACQUIRE)
                .distinct()
        require(commands.all { it in offered }) {
            "Both logical arms must be in the original actor offer set."
        }
        for (row in rows) {
            require(
                row["incumbentIndex"].intValue() == 0 &&
                    row["acquireIndex"].intValue() ==
                        commands.indexOf(CardStyleInteractionCollector.ACQUIRE) &&
                    row["arms"].map { it["command"].asText() } == commands
            ) {
                "Logical aliases must resolve to the exact original commands."
            }
            for (arm in row["arms"]) {
                require(arm["outcomes"].size() == 2) {
                    "Every execution requires both paired futures."
                }
                arm["outcomes"].forEachIndexed { replicate, tail ->
                    require(
                        tail["replicate"].intValue() == replicate &&
                            tail["replayVerified"].booleanValue() &&
                            tail["terminal"]["continuationSeed"].longValue() ==
                                input.origins[index].future[replicate] &&
                            tail["canonicalSha256"].asText() ==
                                CardAdvantageExecutor.hash(
                                    tail["canonicalEntries"].map(JsonNode::asText)
                                )
                    ) {
                        "Every terminal execution requires its original future and exact canonical replay evidence."
                    }
                }
            }
        }
    }

    private fun wire(value: Any): JsonNode =
        PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(value))
}
