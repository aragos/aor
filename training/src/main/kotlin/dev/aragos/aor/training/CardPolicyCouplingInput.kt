package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** A separate diagnostic source, never accepted as a training collection or policy artifact. */
internal data class CardPolicyCouplingInput(
    val source: Path,
    val sourceSha256: String,
    val environment: CardAdvantageEnvironment,
    val origins: List<CardAdvantageRequest>,
) {
    init {
        require(origins.size in 1..64) {
            "A policy coupling diagnostic supports one to 64 requests."
        }
        require(
            origins.all {
                it.cohort == "generic" && it.opponentUsesCandidate.none { enabled -> enabled }
            }
        ) {
            "Coupling roots require generic prospective requests and F card policies for every seat."
        }
    }

    val backboneInput =
        CardAdvantageCollectionInput(
            source,
            sourceSha256,
            environment,
            null,
            origins,
            CardRootPairCollection("root-pairs-1", AdvantageSplit.entries.map { it.wire }, 1),
        )

    fun signature(): String =
        trainingSignature(
            listOf(
                    KIND,
                    source.toString(),
                    sourceSha256,
                    backboneInput.signature(),
                    CardPolicySeeds.VERSION,
                    CardPolicySeeds.MASTER_DOMAIN,
                    CardPolicySeeds.SEED_DOMAIN,
                )
                .joinToString("\n")
        )

    fun manifest(): Map<String, Any?> =
        backboneInput.manifest() +
            mapOf(
                "kind" to KIND,
                "schemaVersion" to 1,
                "signature" to signature(),
                "collection" to plan(),
                "backbonePolicy" to "F",
                "tailPolicy" to "F",
                "opponentPolicy" to "F",
                "replicates" to 8,
                "maxTailsPerOrigin" to 48,
                "schedules" to CardPolicySchedule.entries.map { it.name },
                "arms" to CardPolicyCouplingArm.IDS,
                "policySeedVersion" to CardPolicySeeds.VERSION,
            )

    companion object {
        const val KIND = "card-policy-coupling"

        fun plan(): Map<String, Any> =
            mapOf(
                "kind" to CardPolicySeeds.VERSION,
                "replicates" to 8,
                "schedules" to CardPolicySchedule.entries.map { it.name },
            )

        fun read(path: Path): CardPolicyCouplingInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "kind",
                    "configuration",
                    "baseline",
                    "frozenPurchasePolicy",
                    "collection",
                    "origins",
                ),
            )
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    PurchaseJson.text(node.required("kind"), "kind") == KIND
            ) {
                "A coupling source requires its separate kind and schema version one."
            }
            require(
                node.required("collection") == PurchaseJson.mapper.valueToTree<JsonNode>(plan())
            ) {
                "A coupling source requires exactly L/G/S and eight streams."
            }
            val environment = CardAdvantageEnvironment.read(source, node)
            val origins =
                PurchaseJson.array(node.required("origins"), "origins")
                    .map(CardAdvantageRequest::read)
            require(PurchaseJson.hash(source) == hash) {
                "The coupling source changed during loading."
            }
            return CardPolicyCouplingInput(source, hash, environment, origins)
        }
    }
}
