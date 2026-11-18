package dev.aragos.aor.training

import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.AfterstateContext
import dev.aragos.aor.ai.AfterstateValueEncoder
import java.nio.file.Path

/** A separate value-only source wraps exact, unchanged joint sampled-controller roles. */
internal data class AfterstateInput(
    val source: Path,
    val sourceSha256: String,
    val collection: JointCollectionInput,
    val legacyDictionary: Path,
    val legacyDictionarySha256: String,
) {
    private val catalog =
        PublicCatalog.forPlayers(
            collection.configuration.playerCount,
            collection.configuration.rules,
        )
    val names = AfterstateValueEncoder.dictionary(catalog)
    val legacyNames = AfterstateValueEncoder.legacyDictionary(catalog)
    val files = (listOf(source, legacyDictionary) + collection.files()).distinct()
    val pins = files.associateWith(PurchaseJson::hash)
    val signature =
        trainingSignature(
            "${AfterstateValueEncoder.VERSION}\n${collection.signature()}\n$pins\n$names"
        )

    init {
        require(pins[source] == sourceSha256 && pins[legacyDictionary] == legacyDictionarySha256) {
            "The declared source and original dictionary bytes changed while reading."
        }
        require(collection.ecology != null && collection.ecology.replayIndices.size == 1) {
            "Afterstate data requires explicit current ecology and one prospective history per chunk."
        }
        require(collection.seeds.size in 1..256) { "Afterstate chunks contain at most 256 worlds." }
        require(
            collection.opponents.all {
                it.card?.sha256 == collection.bundle.card?.sha256 &&
                    it.purchase.sha256 == collection.bundle.purchase.sha256
            }
        ) {
            "Afterstate collection uses identical fixed sampled card/purchase actors in every seat."
        }
        require(
            PurchaseJson.strings(
                PurchaseJson.read(legacyDictionary).required("stateFeatureNames"),
                "legacy state names",
            ) == legacyNames
        ) {
            "The entire original state dictionary must match before appending contexts."
        }
    }

    fun unchanged() {
        require(pins.all { (path, hash) -> PurchaseJson.hash(path) == hash }) {
            "Afterstate source or immutable model/dictionary bytes changed."
        }
    }

    fun dictionary(): Map<String, Any> =
        mapOf(
            "version" to AfterstateValueEncoder.VERSION,
            "stateFeatureNames" to names,
            "legacyStateFeatureNames" to legacyNames,
            "appendedStateFeatureNames" to names.drop(legacyNames.size),
            "contexts" to
                AfterstateContext.entries.map {
                    mapOf("name" to it.name, "index" to it.ordinal, "decision" to it.decision)
                },
            "units" to "unchanged-existing-phase-encoder-units",
            "missingValue" to 0.0,
        )

    fun metadata(): Map<String, Any?> =
        mapOf(
            "kind" to AfterstateValueEncoder.VERSION,
            "signature" to signature,
            "source" to source.toString(),
            "sourceSha256" to sourceSha256,
            "legacyDictionarySha256" to legacyDictionarySha256,
            "collection" to JointCollectionStore.manifest(collection, collection.signature()),
            "inputs" to
                pins.entries.mapIndexed { index, (path, hash) ->
                    mapOf(
                        "path" to path.toString(),
                        "sha256" to hash,
                        "snapshot" to "input-$index.json",
                    )
                },
        )

    companion object {
        fun read(path: Path): AfterstateInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "collection", "legacyDictionary"))
            require(node.required("kind").asText() == AfterstateValueEncoder.VERSION) {
                "Afterstate collection requires its explicit versioned source."
            }
            fun resolve(name: String): Pair<Path, String> {
                val value = node.required(name)
                PurchaseJson.fields(value, setOf("path", "sha256"))
                val file =
                    source.parent
                        .resolve(PurchaseJson.text(value.required("path"), name))
                        .normalize()
                        .toRealPath()
                val expected = PurchaseJson.text(value.required("sha256"), "$name SHA-256")
                require(PurchaseJson.hash(file) == expected) {
                    "The $name bytes must match their declared hash."
                }
                return file to expected
            }
            val (collection, collectionHash) = resolve("collection")
            val (dictionary, dictionaryHash) = resolve("legacyDictionary")
            val parsed = JointCollectionInput.read(collection)
            require(parsed.sourceSha256 == collectionHash) {
                "The exact joint role source changed while reading."
            }
            return AfterstateInput(
                    source,
                    hash,
                    parsed,
                    dictionary,
                    dictionaryHash,
                )
                .also { it.unchanged() }
        }
    }
}

internal data class AfterstateGame(
    val index: Int,
    val rows: List<AfterstateRow>,
    val exclusions: List<AfterstateExclusion>,
    val original: JointGameTrajectory,
)

internal class AfterstateCollector(private val input: AfterstateInput, private val deadline: Long) {
    fun collect(index: Int): AfterstateGame {
        val observation = AfterstateObservation(input.collection.seat(index))
        val original =
            JointCollector(input.collection, input.collection.signature(), deadline)
                .collect(index, observation)
        observation.verifyOriginalStates(original)
        return AfterstateGame(
            index,
            observation.rows.toList(),
            observation.exclusions.toList(),
            original,
        )
    }
}
