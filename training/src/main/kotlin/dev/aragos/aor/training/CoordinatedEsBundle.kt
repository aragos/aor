package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.CardPolicyModel
import dev.aragos.aor.ai.PurchasePolicyModel
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Path

internal enum class CoordinatedEsHead {
    CARD,
    PURCHASE,
    EXPANSION,
    TOKEN,
}

/** Complete actor parameters; values and phase-specific inference schemas retain their owners. */
internal data class CoordinatedEsModels(
    val card: CardPolicyModel,
    val purchase: PurchasePolicyModel,
    val expansion: TerritoryPolicyModel,
    val token: TerritoryPolicyModel,
) {
    init {
        require(expansion.schemaVersion == 2 && token.schemaVersion == 3) {
            "Coordinated actors require explicit E2 and TOKEN2 parameter containers."
        }
    }

    fun territory(head: CoordinatedEsHead): TerritoryPolicyModel =
        when (head) {
            CoordinatedEsHead.EXPANSION -> expansion
            CoordinatedEsHead.TOKEN -> token
            else -> error("Only expansion and token use the incumbent-relative selector.")
        }

    fun validateFrozenValues(original: CoordinatedEsModels) {
        CpePolicyHeads(card, purchase, expansion)
            .validateFrozenValues(
                CpePolicyHeads(original.card, original.purchase, original.expansion)
            )
        require(
            token.valueWeights == original.token.valueWeights &&
                token.valueBias == original.token.valueBias
        ) {
            "Coordinated actor search must preserve the original token value arrays."
        }
    }
}

/** Relative, immutable four-head publication; not a legacy CPE bundle or sampled territory role. */
internal class CoordinatedEsBundle
private constructor(
    val source: TokenPairedSubject,
    val subjects: Map<CoordinatedEsHead, TokenPairedSubject>,
    val dictionaries: Map<CoordinatedEsHead, CpePairedDictionary>,
    val models: CoordinatedEsModels,
) {
    fun subject(head: CoordinatedEsHead): TokenPairedSubject =
        requireNotNull(subjects[head]) { "Every coordinated bundle requires all four heads." }

    fun identity(): Map<String, Any> =
        CoordinatedEsHead.entries.associate { head ->
            head.name to
                mapOf(
                    "sha256" to subject(head).sha256,
                    "dictionarySha256" to
                        requireNotNull(dictionaries[head]) { "Missing head dictionary." }.sha256(),
                )
        }

    fun validateOriginal(arena: ArenaSpecification, baseline: PurchaseBaselineInput) {
        val joint =
            PurchaseJson.mapper
                .valueToTree<JsonNode>(arena.overlayMetadata())
                .required("jointPolicy")
        require(joint["baseline"]["sha256"]?.asText() == baseline.sha256) {
            "The complete coordinated reference requires its exact frozen F0 purchase prior."
        }
        for (role in listOf("candidateBundle", "incumbentBundle", "sharedBundle")) {
            require(
                joint[role]["card"]["sha256"]?.asText() == subject(CoordinatedEsHead.CARD).sha256 &&
                    joint[role]["purchase"]["sha256"]?.asText() ==
                        subject(CoordinatedEsHead.PURCHASE).sha256
            ) {
                "Every raw reference role must retain the exact original qualified G3 C/P heads."
            }
        }
        require(
            models.expansion.actorWeights.all { it == 0.0 } &&
                models.token.actorWeights.all { it == 0.0 }
        ) {
            "Original E/T containers require the declared untrained zero readouts."
        }
    }

    fun requireFrozenContainers() {
        require(ORIGINAL_HASHES.all { (head, hash) -> subject(head).sha256 == hash }) {
            "Use only the fixed original qualified G3 C/P and untrained75 E/untrained79 TOKEN2 containers."
        }
    }

    fun verify() {
        source.verify()
        subjects.values.forEach(TokenPairedSubject::verify)
    }

    fun snapshot(artifacts: TrainingArtifacts, prefix: String) {
        verify()
        val target = TrainingArtifacts(artifacts.directory.resolve(prefix))
        target.ensureFileSnapshot(source.path, source.sha256, "bundle.json")
        CoordinatedEsHead.entries.forEach { head ->
            val subject = subject(head)
            target.ensureFileSnapshot(subject.path, subject.sha256, "${head.name.lowercase()}.json")
        }
    }

    companion object {
        const val VERSION = "coordinated-es-bundle-1"
        const val ORIGINAL_EXPANSION =
            "8268ba94536c5286ca7e7e9aedf148bffab2b8f5244e7a631594c5b4af8303fc"
        const val ORIGINAL_TOKEN =
            "812a16f1b8d26911752db0720607d260f410b821922ba2f10f1e3254aa8c9ae6"
        val ORIGINAL_HASHES =
            mapOf(
                CoordinatedEsHead.CARD to
                    "916de6e6ec41b3cc708f51155044956b1d3ebdb68e3a171f660a57123a8b00bf",
                CoordinatedEsHead.PURCHASE to
                    "6613908b272cee6fb5789dc0ac8d8e6a14853d743ff6b9e08ba322a7d7a23f79",
                CoordinatedEsHead.EXPANSION to ORIGINAL_EXPANSION,
                CoordinatedEsHead.TOKEN to ORIGINAL_TOKEN,
            )

        /** Reject substituted original subjects before opening any actor parameter file. */
        fun readOriginal(
            subject: TokenPairedSubject,
            dictionaries: Map<CoordinatedEsHead, CpePairedDictionary>,
        ): CoordinatedEsBundle {
            subject.verify()
            val descriptor = PurchaseJson.read(subject.path)
            require(
                ORIGINAL_HASHES.all { (head, hash) ->
                    descriptor["heads"]?.get(head.name)?.get("sha256")?.asText() == hash
                }
            ) {
                "Original four-head subject pins must match the approved fixed containers before decoding."
            }
            return read(subject, dictionaries).also(CoordinatedEsBundle::requireFrozenContainers)
        }

        fun dictionaries(
            configuration: TrainingConfiguration
        ): Map<CoordinatedEsHead, CpePairedDictionary> {
            val cpe = CpePairedDictionary.forConfiguration(configuration)
            val token =
                TokenObservationEncoder.dictionary(
                    PublicCatalog.forPlayers(configuration.playerCount, configuration.rules),
                    TerritoryEncoder.TOKEN_2,
                )
            return CoordinatedEsHead.entries.associateWith { head ->
                if (head == CoordinatedEsHead.TOKEN)
                    CpePairedDictionary(
                        TerritoryEncoder.TOKEN_2.version,
                        token.stateFeatureNames,
                        token.actionFeatureNames,
                    )
                else requireNotNull(cpe[CpeHead.valueOf(head.name)]) { "Missing C/P/E dictionary." }
            }
        }

        fun read(
            subject: TokenPairedSubject,
            dictionaries: Map<CoordinatedEsHead, CpePairedDictionary>,
        ): CoordinatedEsBundle {
            subject.verify()
            val node = PurchaseJson.read(subject.path)
            PurchaseJson.fields(node, setOf("kind", "heads"))
            require(node["kind"]?.asText() == VERSION) {
                "Use an explicit complete coordinated ES bundle."
            }
            PurchaseJson.fields(
                node.required("heads"),
                CoordinatedEsHead.entries.map { it.name }.toSet(),
            )
            val subjects =
                CoordinatedEsHead.entries.associateWith { head ->
                    val component = node["heads"].required(head.name)
                    PurchaseJson.fields(component, setOf("path", "sha256", "dictionarySha256"))
                    val path = Path.of(PurchaseJson.text(component["path"], "head path"))
                    require(!path.isAbsolute && path.normalize() == path) {
                        "Four-head descriptor paths must be normalized relative portable references."
                    }
                    val dictionary =
                        requireNotNull(dictionaries[head]) { "Missing complete head dictionary." }
                    require(component["dictionarySha256"]?.asText() == dictionary.sha256()) {
                        "Each coordinated component must bind its complete canonical dictionary."
                    }
                    val raw = PurchaseJson.mapper.createObjectNode()
                    raw.set<JsonNode>("path", component.required("path"))
                    raw.set<JsonNode>("sha256", component.required("sha256"))
                    TokenPairedSubject.read(raw, subject.path.parent)
                }
            fun path(head: CoordinatedEsHead) =
                requireNotNull(subjects[head]) { "Missing head subject." }.path
            val models =
                CoordinatedEsModels(
                    CardPolicyInput.read(path(CoordinatedEsHead.CARD)),
                    PurchasePolicyInput.read(path(CoordinatedEsHead.PURCHASE)),
                    TerritoryPolicyInput.read(path(CoordinatedEsHead.EXPANSION)),
                    TerritoryPolicyInput.read(path(CoordinatedEsHead.TOKEN)),
                )
            val actual =
                mapOf(
                    CoordinatedEsHead.CARD to
                        CpePairedDictionary(
                            models.card.encoderVersion,
                            models.card.stateFeatureNames,
                            models.card.actionFeatureNames,
                        ),
                    CoordinatedEsHead.PURCHASE to
                        CpePairedDictionary(
                            models.purchase.encoderVersion,
                            models.purchase.stateFeatureNames,
                            models.purchase.actionFeatureNames,
                        ),
                    CoordinatedEsHead.EXPANSION to
                        CpePairedDictionary(
                            models.expansion.encoderVersion,
                            models.expansion.stateFeatureNames,
                            models.expansion.actionFeatureNames,
                        ),
                    CoordinatedEsHead.TOKEN to
                        CpePairedDictionary(
                            models.token.encoderVersion,
                            models.token.stateFeatureNames,
                            models.token.actionFeatureNames,
                        ),
                )
            require(actual == dictionaries) {
                "Coordinated heads require the complete unchanged vocabularies."
            }
            return CoordinatedEsBundle(subject, subjects, dictionaries, models).also { it.verify() }
        }
    }
}
