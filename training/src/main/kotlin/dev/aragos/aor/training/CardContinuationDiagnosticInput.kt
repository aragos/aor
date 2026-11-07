package dev.aragos.aor.training

import java.nio.file.Path

/**
 * A diagnostic-only source: F captures every root, and only the controlled seat changes its tail.
 */
internal data class CardContinuationDiagnosticInput(
    val source: Path,
    val sourceSha256: String,
    val environment: CardAdvantageEnvironment,
    val priority: CardAdvantageSnapshot,
    val origins: List<CardAdvantageRequest>,
) {
    init {
        require(origins.size in 1..256) {
            "A continuation diagnostic supports at most 256 requests."
        }
        require(
            origins.all {
                it.split in setOf("train", "validation") &&
                    it.cohort == "generic" &&
                    it.opponentUsesCandidate.none { enabled -> enabled }
            }
        ) {
            "Diagnostic roots require generic train/validation requests and F opponents."
        }
    }

    /** Internal collector settings; this derived schema-two wrapper is never an exported source. */
    val backboneInput =
        CardAdvantageCollectionInput(
            source,
            sourceSha256,
            environment,
            null,
            origins,
            CardRootPairCollection("root-pairs-1", listOf("train", "validation"), 1),
        )

    fun signature(): String =
        trainingSignature(
            listOf(
                    "card-continuation-diagnostic-1",
                    source.toString(),
                    sourceSha256,
                    environment.baseline.sha256,
                    environment.purchase.sha256,
                    priority.sha256,
                    backboneInput.signature(),
                )
                .joinToString("\n")
        )

    fun manifest(): Map<String, Any?> =
        backboneInput.manifest() +
            mapOf(
                "kind" to KIND,
                "schemaVersion" to 3,
                "signature" to signature(),
                "collection" to mapOf("kind" to COLLECTION_KIND, "replicates" to 4),
                "continuation" to
                    mapOf("path" to priority.path.toString(), "sha256" to priority.sha256),
                "backbonePolicy" to "F",
                "opponentPolicy" to "F",
                "replicates" to 4,
                "maxTailsPerOrigin" to 16,
                "arms" to CardContinuationArm.IDS,
            )

    companion object {
        const val KIND = "card-continuation-diagnostic"
        const val COLLECTION_KIND = "own-continuation-crossover-1"

        fun read(path: Path): CardContinuationDiagnosticInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "configuration",
                    "baseline",
                    "frozenPurchasePolicy",
                    "continuation",
                    "collection",
                    "origins",
                ),
            )
            require(PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 3) {
                "A continuation diagnostic requires schemaVersion 3."
            }
            val plan = node.required("collection")
            PurchaseJson.fields(plan, setOf("kind", "replicates"))
            require(
                PurchaseJson.text(plan.required("kind"), "kind") == COLLECTION_KIND &&
                    PurchaseJson.integer(plan.required("replicates"), "replicates") == 4
            ) {
                "A diagnostic requires the fixed four-stream own-continuation crossover."
            }
            val environment = CardAdvantageEnvironment.read(source, node)
            val priority =
                environment.readAdvantage(
                    source,
                    PurchaseJson.text(node.required("continuation"), "continuation"),
                )
            val origins =
                PurchaseJson.array(node.required("origins"), "origins")
                    .map(CardAdvantageRequest::read)
            require(PurchaseJson.hash(source) == hash) {
                "The diagnostic source changed during loading."
            }
            return CardContinuationDiagnosticInput(source, hash, environment, priority, origins)
        }
    }
}
