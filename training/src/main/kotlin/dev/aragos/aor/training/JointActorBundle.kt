package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.CardFeatureDictionary
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.CardPolicy
import dev.aragos.aor.ai.PurchaseFeatureDictionary
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.PurchasePolicy
import java.nio.file.Path

/**
 * Immutable two-phase behavior identity. Paths resolve beside the bundle; every component declares
 * its exact raw SHA-256. A null card component explicitly means F card play for a frozen rival. A
 * learning seat requires a nonnull card actor. Optimizer state belongs to the producer, not this
 * inference boundary; publish a bundle only after both actor checkpoints are complete.
 */
internal data class JointActorBundle(
    val path: Path,
    val sha256: String,
    val configuration: TrainingConfiguration,
    val baselineSha256: String,
    val card: CardActorInput?,
    val purchase: PurchaseActorInput,
) {
    val cardDictionary: CardFeatureDictionary =
        CardObservationEncoder.dictionary(
            PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
        )
    val purchaseDictionary: PurchaseFeatureDictionary =
        PurchaseObservationEncoder.dictionary(
            PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
        )

    init {
        require(path.isAbsolute && listOf(sha256, baselineSha256).all { HASH.matches(it) }) {
            "A joint bundle requires an absolute path and exact bundle/baseline fingerprints."
        }
        require(
            card == null ||
                card.model.stateFeatureNames == cardDictionary.stateFeatureNames &&
                    card.model.actionFeatureNames == cardDictionary.actionFeatureNames
        ) {
            "The joint card actor must use the setup's complete card dictionary."
        }
        require(
            purchase.model.stateFeatureNames == purchaseDictionary.stateFeatureNames &&
                purchase.model.actionFeatureNames == purchaseDictionary.actionFeatureNames
        ) {
            "The joint purchase actor must use the setup's complete purchase dictionary."
        }
    }

    fun metadata(): Map<String, Any?> =
        mapOf(
            "path" to path.toString(),
            "sha256" to sha256,
            "kind" to VERSION,
            "configuration" to configuration.id,
            "baselineSha256" to baselineSha256,
            "card" to
                card?.let {
                    phase(
                        it.path,
                        it.sha256,
                        CardObservationEncoder.ENCODER_VERSION,
                        CardPolicy.ACTOR_VERSION,
                        cardDictionary.stateFeatureNames,
                        cardDictionary.actionFeatureNames,
                    )
                },
            "purchase" to
                phase(
                    purchase.path,
                    purchase.sha256,
                    PurchaseObservationEncoder.ENCODER_VERSION,
                    PurchasePolicy.ACTOR_VERSION,
                    purchaseDictionary.stateFeatureNames,
                    purchaseDictionary.actionFeatureNames,
                ),
        )

    fun controller(
        baseline: PurchaseBaselineInput,
        cardRecord: ((CardDecisionRecord) -> Unit)? = null,
        purchaseRecord: ((PurchaseDecisionRecord) -> Unit)? = null,
        ordinary: BotModel = baseline.model,
        support: ArenaCandidateSupport? = null,
    ): CardActorController {
        require(baseline.sha256 == baselineSha256) { "A joint actor requires its exact F prior." }
        return CardActorController(
            ordinary,
            card?.model,
            purchase.model,
            record = cardRecord,
            purchaseRecord = purchaseRecord,
            purchaseBaseline = baseline.model,
            support = support,
        )
    }

    fun files(): List<Path> = listOfNotNull(path, card?.path, purchase.path)

    companion object {
        const val VERSION = "joint-card-purchase-1"
        private val HASH = Regex("[0-9a-f]{64}")

        fun read(
            path: Path,
            configuration: TrainingConfiguration,
            baseline: PurchaseBaselineInput,
        ): JointActorBundle {
            val file = path.toRealPath()
            val fingerprint = PurchaseJson.hash(file)
            val node = PurchaseJson.read(file)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "kind",
                    "configuration",
                    "baselineSha256",
                    "card",
                    "purchase",
                ),
            )
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    PurchaseJson.text(node.required("kind"), "kind") == VERSION &&
                    PurchaseJson.text(node.required("configuration"), "configuration") ==
                        configuration.id &&
                    PurchaseJson.text(node.required("baselineSha256"), "baselineSha256") ==
                        baseline.sha256
            ) {
                "A joint bundle must bind its supported version, setup and exact F prior."
            }
            fun component(value: JsonNode): Pair<Path, String> {
                PurchaseJson.fields(value, setOf("path", "sha256"))
                val componentPath =
                    file.parent
                        .resolve(PurchaseJson.text(value.required("path"), "path"))
                        .normalize()
                        .toRealPath()
                val expected = PurchaseJson.text(value.required("sha256"), "sha256")
                require(HASH.matches(expected) && PurchaseJson.hash(componentPath) == expected) {
                    "A joint component must match its declared raw model fingerprint."
                }
                return componentPath to expected
            }
            val card =
                node.required("card").takeUnless(JsonNode::isNull)?.let { value ->
                    val (componentPath, expected) = component(value)
                    CardActorInput(componentPath, expected, CardPolicyInput.read(componentPath))
                }
            val (purchasePath, purchaseHash) = component(node.required("purchase"))
            val purchase =
                PurchaseActorInput(
                    purchasePath,
                    purchaseHash,
                    PurchasePolicyInput.read(purchasePath),
                )
            require(
                PurchaseJson.hash(file) == fingerprint &&
                    PurchaseJson.hash(purchasePath) == purchaseHash &&
                    (card == null || PurchaseJson.hash(card.path) == card.sha256)
            ) {
                "A joint bundle or component changed while being read."
            }
            return JointActorBundle(
                file,
                fingerprint,
                configuration,
                baseline.sha256,
                card,
                purchase,
            )
        }

        private fun phase(
            path: Path,
            hash: String,
            encoder: String,
            actor: String,
            states: List<String>,
            actions: List<String>,
        ): Map<String, Any> =
            mapOf(
                "path" to path.toString(),
                "sha256" to hash,
                "encoderVersion" to encoder,
                "actorVersion" to actor,
                "stateFeatureNames" to states,
                "actionFeatureNames" to actions,
            )
    }
}
