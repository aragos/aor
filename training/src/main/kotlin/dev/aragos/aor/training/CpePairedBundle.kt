package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardObservationEncoder
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.PurchaseObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Path

/** Complete dictionary identity uses canonical sorted JSON keys, separately from raw file bytes. */
internal data class CpePairedDictionary(
    val encoderVersion: String,
    val stateFeatureNames: List<String>,
    val actionFeatureNames: List<String>,
) {
    fun wire(): Map<String, Any> =
        sortedMapOf(
            "encoderVersion" to encoderVersion,
            "stateFeatureNames" to stateFeatureNames,
            "actionFeatureNames" to actionFeatureNames,
        )

    fun sha256(): String = trainingSignature(PurchaseJson.mapper.writeValueAsString(wire()))

    companion object {
        fun forConfiguration(
            configuration: TrainingConfiguration
        ): Map<CpeHead, CpePairedDictionary> {
            val catalog = PublicCatalog.forPlayers(configuration.playerCount, configuration.rules)
            val card = CardObservationEncoder.dictionary(catalog)
            val purchase = PurchaseObservationEncoder.dictionary(catalog)
            val expansion =
                ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2)
            return mapOf(
                CpeHead.CARD to
                    CpePairedDictionary(
                        CardObservationEncoder.ENCODER_VERSION,
                        card.stateFeatureNames,
                        card.actionFeatureNames,
                    ),
                CpeHead.PURCHASE to
                    CpePairedDictionary(
                        PurchaseObservationEncoder.ENCODER_VERSION,
                        purchase.stateFeatureNames,
                        purchase.actionFeatureNames,
                    ),
                CpeHead.EXPANSION to
                    CpePairedDictionary(
                        TerritoryEncoder.EXPANSION_2.version,
                        expansion.stateFeatureNames,
                        expansion.actionFeatureNames,
                    ),
            )
        }
    }
}

/** Explicit complete three-head inference publication; legacy two-head readers stay unchanged. */
internal class CpePairedBundle
private constructor(
    val source: TokenPairedSubject,
    val subjects: Map<CpeHead, TokenPairedSubject>,
    val dictionaries: Map<CpeHead, CpePairedDictionary>,
    val models: CpePolicyHeads,
) {
    fun subject(head: CpeHead): TokenPairedSubject =
        requireNotNull(subjects[head]) { "A C/P/E bundle requires every component subject." }

    fun identity(): Map<String, Any> =
        CpeHead.entries.associate { head ->
            head.name to
                mapOf(
                    "sha256" to subject(head).sha256,
                    "dictionarySha256" to
                        requireNotNull(dictionaries[head]) { "A head dictionary is required." }
                            .sha256(),
                )
        }

    fun snapshot(artifacts: TrainingArtifacts, prefix: String) {
        val bundleArtifacts = TrainingArtifacts(artifacts.directory.resolve(prefix))
        source.verify()
        bundleArtifacts.ensureFileSnapshot(source.path, source.sha256, "bundle.json")
        CpeHead.entries.forEach { head ->
            val subject = subject(head)
            subject.verify()
            bundleArtifacts.ensureFileSnapshot(
                subject.path,
                subject.sha256,
                "${head.name.lowercase()}.json",
            )
        }
    }

    companion object {
        const val VERSION = "cpe-paired-bundle-1"

        fun read(
            subject: TokenPairedSubject,
            dictionaries: Map<CpeHead, CpePairedDictionary>,
        ): CpePairedBundle {
            subject.verify()
            val node = PurchaseJson.read(subject.path)
            PurchaseJson.fields(node, setOf("kind", "heads"))
            require(node["kind"]?.asText() == VERSION) { "Use the explicit complete C/P/E bundle." }
            PurchaseJson.fields(node.required("heads"), CpeHead.entries.map { it.name }.toSet())
            val subjects =
                CpeHead.entries.associateWith { head ->
                    val component = node["heads"].required(head.name)
                    PurchaseJson.fields(component, setOf("path", "sha256", "dictionarySha256"))
                    val dictionary = requireNotNull(dictionaries[head]) { "Missing dictionary." }
                    require(component["dictionarySha256"]?.asText() == dictionary.sha256()) {
                        "The $head component must bind its complete canonical dictionary."
                    }
                    val raw = PurchaseJson.mapper.createObjectNode()
                    raw.set<JsonNode>("path", component.required("path"))
                    raw.set<JsonNode>("sha256", component.required("sha256"))
                    TokenPairedSubject.read(raw, subject.path.parent)
                }
            fun path(head: CpeHead): Path =
                requireNotNull(subjects[head]) { "Missing $head component." }.path
            val models =
                CpePolicyHeads(
                    CardPolicyInput.read(path(CpeHead.CARD)),
                    PurchasePolicyInput.read(path(CpeHead.PURCHASE)),
                    TerritoryPolicyInput.read(path(CpeHead.EXPANSION)),
                )
            val actual =
                mapOf(
                    CpeHead.CARD to
                        CpePairedDictionary(
                            models.card.encoderVersion,
                            models.card.stateFeatureNames,
                            models.card.actionFeatureNames,
                        ),
                    CpeHead.PURCHASE to
                        CpePairedDictionary(
                            models.purchase.encoderVersion,
                            models.purchase.stateFeatureNames,
                            models.purchase.actionFeatureNames,
                        ),
                    CpeHead.EXPANSION to
                        CpePairedDictionary(
                            models.expansion.encoderVersion,
                            models.expansion.stateFeatureNames,
                            models.expansion.actionFeatureNames,
                        ),
                )
            require(actual == dictionaries && models.expansion.schemaVersion == 2) {
                "C/P/E actors require the complete unchanged C/P1 and EXPANSION_2 vocabularies."
            }
            subject.verify()
            subjects.values.forEach(TokenPairedSubject::verify)
            return CpePairedBundle(subject, subjects, dictionaries, models)
        }
    }
}
