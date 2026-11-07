package dev.aragos.aor.training

import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardProposals
import java.nio.file.Path

/**
 * Immutable explicit origin requests and continuation population; no outcome-dependent fallback.
 */
internal data class CardAdvantageCollectionInput(
    val source: Path,
    val sourceSha256: String,
    val environment: CardAdvantageEnvironment,
    val continuation: CardAdvantageSnapshot?,
    val origins: List<CardAdvantageRequest>,
    val collection: CardRootPairCollection? = null,
) {
    init {
        require(origins.size in 1..4096 && origins.map { it.index } == origins.indices.toList()) {
            "Advantage origins must have contiguous indices and a bounded nonempty population."
        }
        require(origins.map { it.seed }.distinct().size == origins.size) {
            "Each advantage origin must use an independent explicit world seed."
        }
        require(
            continuation != null ||
                origins.none { it.opponentUsesCandidate.any { enabled -> enabled } }
        ) {
            "Candidate rivals require a frozen continuation model."
        }
    }

    val schemaVersion: Int
        get() = if (collection == null) 1 else 2

    fun signature(): String =
        trainingSignature(
            listOf(
                    "card-advantage-collect-$schemaVersion",
                    source.toString(),
                    sourceSha256,
                    environment.baseline.sha256,
                    environment.purchase.sha256,
                    continuation?.sha256,
                    CardAdvantagePolicy.SELECTOR_VERSION,
                    CardProposals.VERSION,
                    environment.dictionary.stateFeatureNames,
                    environment.dictionary.actionFeatureNames,
                )
                .joinToString("\n")
        )

    fun manifest(): Map<String, Any?> =
        mapOf(
            "kind" to "card-advantage-collect",
            "schemaVersion" to schemaVersion,
            "signature" to signature(),
            "rulesVersion" to RULES_VERSION,
            "dataVersion" to DATA_VERSION,
            "encoderVersion" to CardObservationEncoder.ENCODER_VERSION,
            "selectorVersion" to CardAdvantagePolicy.SELECTOR_VERSION,
            "proposalVersion" to CardProposals.VERSION,
            "trajectoryEncoding" to CardTrajectoryFiles.ENCODING,
            "source" to source.toString(),
            "sourceSha256" to sourceSha256,
            "configuration" to environment.configuration.id,
            "baseline" to
                mapOf(
                    "path" to environment.baseline.path.toString(),
                    "sha256" to environment.baseline.sha256,
                ),
            "frozenPurchasePolicy" to
                mapOf(
                    "path" to environment.purchase.path.toString(),
                    "sha256" to environment.purchase.sha256,
                ),
            "continuation" to
                continuation?.let { mapOf("path" to it.path.toString(), "sha256" to it.sha256) },
            "stateFeatureNames" to environment.dictionary.stateFeatureNames,
            "actionFeatureNames" to environment.dictionary.actionFeatureNames,
            "origins" to origins,
            "replicates" to (collection?.replicates ?: 4),
            "maxTailsPerOrigin" to (collection?.let { 4 * it.replicates } ?: 52),
            "observationHashCodec" to
                "SHA-256 of JVM Jackson observation bytes; opaque outside JVM",
        ) + (collection?.let { mapOf("collection" to it) } ?: emptyMap())

    companion object {
        fun read(path: Path): CardAdvantageCollectionInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            val version = PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion")
            require(version in 1..2) { "Advantage collection requires schemaVersion 1 or 2." }
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "configuration",
                    "baseline",
                    "frozenPurchasePolicy",
                    "continuation",
                    "origins",
                ) + if (version == 2) setOf("collection") else emptySet(),
            )
            val environment = CardAdvantageEnvironment.read(source, node)
            val continuation =
                node
                    .required("continuation")
                    .takeUnless { it.isNull }
                    ?.let {
                        environment.readAdvantage(source, PurchaseJson.text(it, "continuation"))
                    }
            val requests =
                PurchaseJson.array(node.required("origins"), "origins").map { row ->
                    PurchaseJson.fields(
                        row,
                        setOf(
                            "index",
                            "split",
                            "seed",
                            "seat",
                            "epoch",
                            "cohort",
                            "ordinal",
                            "opponentUsesCandidate",
                        ),
                    )
                    CardAdvantageRequest(
                        index = PurchaseJson.integer(row.required("index"), "index"),
                        split = PurchaseJson.text(row.required("split"), "split"),
                        seed = PurchaseJson.integer(row.required("seed"), "seed"),
                        seat = PurchaseJson.integer(row.required("seat"), "seat"),
                        epoch = PurchaseJson.integer(row.required("epoch"), "epoch"),
                        cohort = PurchaseJson.text(row.required("cohort"), "cohort"),
                        ordinal = PurchaseJson.integer(row.required("ordinal"), "ordinal"),
                        opponentUsesCandidate =
                            PurchaseJson.array(
                                    row.required("opponentUsesCandidate"),
                                    "opponentUsesCandidate",
                                )
                                .map {
                                    require(it.isBoolean) {
                                        "Rival continuation flags must be JSON booleans."
                                    }
                                    it.booleanValue()
                                },
                    )
                }
            require(PurchaseJson.hash(source) == hash) {
                "Advantage source changed while being read."
            }
            val collection =
                if (version == 2) CardRootPairCollection.read(node.required("collection")) else null
            return CardAdvantageCollectionInput(
                source,
                hash,
                environment,
                continuation,
                requests,
                collection,
            )
        }
    }
}
