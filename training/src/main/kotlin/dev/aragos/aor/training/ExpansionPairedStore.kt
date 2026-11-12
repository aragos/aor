package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionPairedEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Small worker result; complete origins are compressed and verified before this receipt returns.
 */
internal data class ExpansionPairedReceipt(
    val index: Int,
    val file: String,
    val sha256: String,
    val captured: Boolean,
    val prefixTerminal: Boolean,
    val prefixPlayerDecisions: Int,
    val tailGames: Int,
)

/**
 * One new output directory, exact input snapshots and independent compressed origin documents.
 * Reuses the existing bounded atomic gzip-JSON owner. Completed futures retain only small receipts,
 * never the growing population of canonical traces or parsed JSON trees.
 */
internal class ExpansionPairedStore(private val input: ExpansionPairedInput, val directory: Path) {
    private val artifacts = TrainingArtifacts(directory)

    fun prepare() {
        input.snapshot(artifacts)
        val dictionary =
            ExpansionPairedEncoder.dictionary(
                PublicCatalog.forPlayers(4, input.configuration.rules)
            )
        artifacts.write(
            "dictionary.json",
            mapOf("encoderVersion" to ExpansionPairedEncoder.VERSION, "dictionary" to dictionary),
        )
        artifacts.write(
            "prepared.json",
            mapOf(
                "kind" to "expansion-paired-data-1",
                "signature" to input.signature,
                "sourceSha256" to input.sourceSha256,
                "arenaSha256" to input.arenaSha256,
                "configuration" to input.configuration.id,
                "policyBindings" to input.arena.overlayMetadata(),
                "requests" to
                    (0 until ExpansionPairedRequest.ORIGINS).map(::ExpansionPairedRequest),
                "origins" to input.origins,
                "dictionarySha256" to PurchaseJson.hash(directory.resolve("dictionary.json")),
                "originEncoding" to CardTrajectoryFiles.ENCODING,
                "maximumOriginPrefixes" to 1024,
                "maximumTailGames" to 16384,
                "maximumTerminalGames" to 17408,
                "proposalVersion" to "public-destination-sample-1",
                "statisticalUnit" to "independent origin; both streams and all arms remain grouped",
                "targetContract" to
                    "all first-action terminal returns under exact normal continuation; no winner labels",
                "prefixContract" to
                    "normal play; exact prospective ordinal in first own expansion turn of assigned epoch",
                "authorityContract" to
                    "canonical evidence outside actor inputs; forks keep existing hands and deck",
                "fits" to 0,
                "runtimeExport" to false,
            ),
        )
    }

    fun save(origin: ExpansionPairedOrigin): ExpansionPairedReceipt {
        require(
            origin.signature == input.signature &&
                origin.streams == input.origins[origin.request.index]
        ) {
            "Every completed origin must bind its original source and streams."
        }
        val name = "origin-${origin.request.index}.json.gz"
        val path = directory.resolve(name)
        require(!Files.exists(path)) {
            "Completed expansion origins cannot be overwritten or retried."
        }
        // Normalize the persisted JSON boundary once, including signed Long streams.
        val wire = PurchaseJson.mapper.readTree(PurchaseJson.mapper.writeValueAsBytes(origin))
        validate(wire, origin.request.index)
        CardTrajectoryFiles.save(path, wire)
        check(CardTrajectoryFiles.readIfPresent(path) == wire) {
            "Compressed expansion evidence must reproduce every original JSON field."
        }
        return ExpansionPairedReceipt(
            origin.request.index,
            name,
            PurchaseJson.hash(path),
            origin.status == ExpansionOriginStatus.COLLECTED,
            origin.prefix.terminal,
            origin.prefix.playerDecisions,
            origin.arms.sumOf { it.outcomes.size },
        )
    }

    fun finish(receipts: List<ExpansionPairedReceipt>) {
        require(receipts.map { it.index }.sorted() == (0 until 1024).toList()) {
            "All 1,024 original requests, including missing windows, must complete once."
        }
        for (receipt in receipts) {
            val path = directory.resolve(receipt.file)
            require(PurchaseJson.hash(path) == receipt.sha256) { "Completed origin bytes changed." }
            validate(
                requireNotNull(CardTrajectoryFiles.readIfPresent(path)) {
                    "Origin evidence is missing."
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
                "prefixPlayerDecisions" to receipts.sumOf { it.prefixPlayerDecisions },
                "tailGames" to receipts.sumOf { it.tailGames },
                "scientificTerminalGames" to
                    receipts.sumOf { (if (it.prefixTerminal) 1 else 0) + it.tailGames },
                "at" to Instant.now().toString(),
            ),
        )
    }

    private fun validate(origin: JsonNode, index: Int) {
        require(
            origin["signature"].asText() == input.signature &&
                origin["request"]["index"].intValue() == index
        ) {
            "Persisted origin provenance changed."
        }
        val request =
            PurchaseJson.mapper.readTree(
                PurchaseJson.mapper.writeValueAsBytes(ExpansionPairedRequest(index))
            )
        val streams =
            PurchaseJson.mapper.readTree(
                PurchaseJson.mapper.writeValueAsBytes(input.origins[index])
            )
        require(
            origin["request"] == request &&
                origin["streams"] == streams &&
                origin["prefix"]["replayVerified"].booleanValue()
        ) {
            "The original schedule, streams and verified canonical prefix are required."
        }
        require(
            origin["prefix"]["canonicalSha256"].asText() ==
                CardAdvantageExecutor.hash(origin["prefix"]["entries"].map(JsonNode::asText))
        ) {
            "The retained canonical prefix must match its exact recorded hash."
        }
        val arms = origin["arms"]
        if (origin["status"].asText() == ExpansionOriginStatus.MISSING.name) {
            require(
                arms.isEmpty &&
                    origin["actorInput"].isNull &&
                    origin["prefix"]["terminal"].booleanValue()
            ) {
                "Missing windows retain their full normal terminal backbone without invented labels."
            }
            return
        }
        require(
            origin["status"].asText() == ExpansionOriginStatus.COLLECTED.name &&
                arms.size() in 2..8 &&
                origin["exactIncumbentVerified"].booleanValue()
        ) {
            "A collected root requires its exact incumbent and bounded branching arms."
        }
        val encoded = origin["actorInput"]["encoded"]
        val proposals = arms.map { it["proposal"] }
        require(
            proposals.map { it["actionIndex"].intValue() }.distinct().size == proposals.size &&
                proposals.first()["actionIndex"].intValue() ==
                    encoded["baselineIndex"].intValue() &&
                proposals.first()["command"].asText() == origin["incumbentCommand"].asText()
        ) {
            "Arms must be distinct original offered indices with exact E first."
        }
        for (arm in arms) {
            val proposal = arm["proposal"]
            require(
                encoded["actions"][proposal["actionIndex"].intValue()]["command"] ==
                    proposal["command"] && arm["outcomes"].size() == 2
            ) {
                "Each arm requires its original offered command and both paired streams."
            }
            arm["outcomes"].forEachIndexed { replicate, tail ->
                require(
                    tail["canonicalSha256"].asText() ==
                        CardAdvantageExecutor.hash(tail["canonicalEntries"].map(JsonNode::asText))
                ) {
                    "The retained canonical suffix must match its exact recorded hash."
                }
                require(
                    tail["replicate"].intValue() == replicate &&
                        tail["terminal"]["continuationSeed"].longValue() ==
                            input.origins[index].future[replicate] &&
                        tail["replayVerified"].booleanValue()
                ) {
                    "Every retained terminal tail must bind its paired stream and canonical replay."
                }
            }
        }
    }
}
