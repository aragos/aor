package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryFeatureDictionary
import dev.aragos.aor.ai.TerritoryPhase
import dev.aragos.aor.ai.TerritoryValueModel
import dev.aragos.aor.ai.TerritoryValuePolicy
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Path

internal data class TerritoryValueInput(
    val path: Path,
    val sha256: String,
    val model: TerritoryValueModel,
)

/** Two learning heads bound to one immutable card/purchase continuation and its exact F prior. */
internal data class TerritoryValueBundle(
    val path: Path,
    val sha256: String,
    val configuration: TrainingConfiguration,
    val baselineSha256: String,
    val frozen: JointActorBundle,
    val token: TerritoryValueInput?,
    val expansion: TerritoryValueInput?,
) {
    val tokenDictionary =
        TokenObservationEncoder.dictionary(
            PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
        )
    val expansionDictionary =
        ExpansionObservationEncoder.dictionary(
            PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
            expansion?.model?.encoderVersion?.let(TerritoryEncoder::fromVersion)
                ?: TerritoryEncoder.EXPANSION_1,
        )

    init {
        require(
            path.isAbsolute &&
                sha256.matches(HASH) &&
                baselineSha256 == frozen.baselineSha256 &&
                configuration == frozen.configuration
        ) {
            "A territory bundle must preserve its absolute identity, setup and frozen continuation's F prior."
        }
        for ((phase, actor, dictionary) in
            listOf(
                Triple(TerritoryPhase.TOKEN, token, tokenDictionary),
                Triple(TerritoryPhase.EXPANSION, expansion, expansionDictionary),
            )) {
            require(
                actor == null ||
                    actor.path.isAbsolute &&
                        actor.sha256.matches(HASH) &&
                        TerritoryPhase.fromEncoder(actor.model.encoderVersion) == phase &&
                        actor.model.stateFeatureNames == dictionary.stateFeatureNames &&
                        actor.model.actionFeatureNames == dictionary.actionFeatureNames
            ) {
                "Every territory actor requires the exact typed version and complete public-catalog vocabulary."
            }
        }
    }

    fun metadata(): Map<String, Any?> =
        mapOf(
            "path" to path.toString(),
            "sha256" to sha256,
            "kind" to VERSION,
            "configuration" to configuration.id,
            "baselineSha256" to baselineSha256,
            "frozenCardPurchaseBundleSha256" to frozen.sha256,
            "token" to token?.let { phase(it, TerritoryPhase.TOKEN, tokenDictionary) },
            "expansion" to
                expansion?.let { phase(it, TerritoryPhase.EXPANSION, expansionDictionary) },
        )

    fun files(): List<Path> = listOfNotNull(path, token?.path, expansion?.path) + frozen.files()

    fun controller(
        baseline: PurchaseBaselineInput,
        epsilon: Double,
        record: ((TerritoryValueDecisionRecord) -> Unit)? = null,
    ): TerritoryValueController {
        require(baseline.sha256 == baselineSha256) {
            "Territory control requires its exact original F prior."
        }
        return TerritoryValueController(baseline, this, epsilon, record)
    }

    companion object {
        const val VERSION = "territory-token-expansion-values-1"
        private val HASH = Regex("[0-9a-f]{64}")

        fun read(
            path: Path,
            configuration: TrainingConfiguration,
            baseline: PurchaseBaselineInput,
            frozen: JointActorBundle,
        ): TerritoryValueBundle {
            val file = path.toRealPath()
            val hash = PurchaseJson.hash(file)
            val node = PurchaseJson.read(file)
            PurchaseJson.fields(
                node,
                setOf(
                    "schemaVersion",
                    "kind",
                    "configuration",
                    "baselineSha256",
                    "frozenCardPurchaseBundleSha256",
                    "token",
                    "expansion",
                ),
            )
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    node.required("kind").asText() == VERSION &&
                    node.required("configuration").asText() == configuration.id &&
                    node.required("baselineSha256").asText() == baseline.sha256 &&
                    node.required("frozenCardPurchaseBundleSha256").asText() == frozen.sha256
            ) {
                "Territory bundle version, setup, F prior and frozen card/purchase bytes must match."
            }
            fun actor(value: JsonNode): TerritoryValueInput? {
                if (value.isNull) return null
                PurchaseJson.fields(value, setOf("path", "sha256"))
                val source =
                    file.parent
                        .resolve(PurchaseJson.text(value.required("path"), "path"))
                        .normalize()
                        .toRealPath()
                val expected = PurchaseJson.text(value.required("sha256"), "sha256")
                require(expected.matches(HASH) && PurchaseJson.hash(source) == expected) {
                    "A territory component differs from its declared hash."
                }
                val model = TerritoryValuePolicyInput.read(source)
                require(PurchaseJson.hash(source) == expected) {
                    "A territory component changed while decoding."
                }
                return TerritoryValueInput(source, expected, model)
            }
            val token = actor(node.required("token"))
            val expansion = actor(node.required("expansion"))
            require(PurchaseJson.hash(file) == hash) { "Territory bundle changed while decoding." }
            return TerritoryValueBundle(
                file,
                hash,
                configuration,
                baseline.sha256,
                frozen,
                token,
                expansion,
            )
        }

        private fun phase(
            actor: TerritoryValueInput,
            phase: TerritoryPhase,
            dictionary: TerritoryFeatureDictionary,
        ): Map<String, Any> =
            mapOf(
                "path" to actor.path.toString(),
                "sha256" to actor.sha256,
                "encoderVersion" to actor.model.encoderVersion,
                "selectorVersion" to TerritoryValuePolicy.selectorVersion(phase),
                "architecture" to actor.model.architecture,
                "stateFeatureNames" to dictionary.stateFeatureNames,
                "actionFeatureNames" to dictionary.actionFeatureNames,
            )
    }
}
