package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardAdvantageModel
import dev.aragos.aor.ai.CardAdvantageNetwork
import dev.aragos.aor.ai.CardFeatureDictionary
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder
import java.nio.file.Path

/** Strict model transport; numerical shape and version invariants belong to shared AI. */
internal object CardAdvantageInput {
    fun read(path: Path): CardAdvantageModel {
        val node = PurchaseJson.read(path)
        PurchaseJson.fields(
            node,
            setOf(
                "schemaVersion",
                "encoderVersion",
                "selectorVersion",
                "proposalVersion",
                "hiddenSize",
                "margin",
                "stateFeatureNames",
                "actionFeatureNames",
                "members",
            ),
        )
        return CardAdvantageModel(
            schemaVersion = PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion"),
            encoderVersion = PurchaseJson.text(node.required("encoderVersion"), "encoderVersion"),
            selectorVersion =
                PurchaseJson.text(node.required("selectorVersion"), "selectorVersion"),
            proposalVersion =
                PurchaseJson.text(node.required("proposalVersion"), "proposalVersion"),
            hiddenSize = PurchaseJson.integer(node.required("hiddenSize"), "hiddenSize"),
            margin = PurchaseJson.number(node.required("margin"), "margin"),
            stateFeatureNames =
                PurchaseJson.strings(node.required("stateFeatureNames"), "stateFeatureNames"),
            actionFeatureNames =
                PurchaseJson.strings(node.required("actionFeatureNames"), "actionFeatureNames"),
            members =
                PurchaseJson.array(node.required("members"), "members").map { member ->
                    PurchaseJson.fields(
                        member,
                        setOf(
                            "stateWeights",
                            "stateBias",
                            "actionWeights",
                            "actionBias",
                            "outputWeights",
                            "outputBias",
                        ),
                    )
                    CardAdvantageNetwork(
                        stateWeights =
                            PurchaseJson.numbers(member.required("stateWeights"), "stateWeights"),
                        stateBias = PurchaseJson.numbers(member.required("stateBias"), "stateBias"),
                        actionWeights =
                            PurchaseJson.numbers(member.required("actionWeights"), "actionWeights"),
                        actionBias =
                            PurchaseJson.numbers(member.required("actionBias"), "actionBias"),
                        outputWeights =
                            PurchaseJson.numbers(member.required("outputWeights"), "outputWeights"),
                        outputBias =
                            PurchaseJson.number(member.required("outputBias"), "outputBias"),
                    )
                },
        )
    }
}

/** Raw bytes and their exact source identity remain separate from derived model caches. */
internal data class CardAdvantageSnapshot(
    val path: Path,
    val sha256: String,
    val model: CardAdvantageModel,
)

/** Shared public setup and frozen purchase policy for collection and repeated-game evaluation. */
internal data class CardAdvantageEnvironment(
    val configuration: TrainingConfiguration,
    val baseline: PurchaseBaselineInput,
    val purchase: PurchaseActorInput,
    val dictionary: CardFeatureDictionary,
) {
    fun readAdvantage(source: Path, text: String): CardAdvantageSnapshot {
        val path = source.parent.resolve(text).normalize().toRealPath()
        val hash = PurchaseJson.hash(path)
        val model = CardAdvantageInput.read(path)
        require(PurchaseJson.hash(path) == hash) { "An advantage input changed while being read." }
        require(
            model.stateFeatureNames == dictionary.stateFeatureNames &&
                model.actionFeatureNames == dictionary.actionFeatureNames
        ) {
            "An advantage model must retain this setup's complete feature dictionaries."
        }
        return CardAdvantageSnapshot(path, hash, model)
    }

    companion object {
        fun read(source: Path, node: JsonNode): CardAdvantageEnvironment {
            val id = PurchaseJson.text(node.required("configuration"), "configuration")
            val configuration =
                requireNotNull(TrainingMatrix.configurations.singleOrNull { it.id == id }) {
                    "Unknown advantage configuration: $id."
                }
            require(configuration.playerCount == 4) {
                "The experimental advantage executor requires four players."
            }
            fun path(name: String): Path =
                source.parent
                    .resolve(PurchaseJson.text(node.required(name), name))
                    .normalize()
                    .toRealPath()
            val baselinePath = path("baseline")
            val baselineHash = PurchaseJson.hash(baselinePath)
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    baselineHash,
                    TrainingArtifacts.readModel(baselinePath),
                )
            val purchasePath = path("frozenPurchasePolicy")
            val purchaseHash = PurchaseJson.hash(purchasePath)
            val purchase =
                PurchaseActorInput(
                    purchasePath,
                    purchaseHash,
                    PurchasePolicyInput.read(purchasePath),
                )
            require(
                PurchaseJson.hash(baselinePath) == baselineHash &&
                    PurchaseJson.hash(purchasePath) == purchaseHash
            ) {
                "A frozen baseline or purchase actor changed while being read."
            }
            val catalog = PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            val purchaseDictionary = PurchaseObservationEncoder.dictionary(catalog)
            require(
                purchase.model.stateFeatureNames == purchaseDictionary.stateFeatureNames &&
                    purchase.model.actionFeatureNames == purchaseDictionary.actionFeatureNames
            ) {
                "The frozen purchase actor must match this setup's complete dictionary."
            }
            return CardAdvantageEnvironment(
                configuration,
                baseline,
                purchase,
                CardObservationEncoder.dictionary(catalog),
            )
        }
    }
}
