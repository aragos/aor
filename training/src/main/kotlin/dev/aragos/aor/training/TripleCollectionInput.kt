package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.ExpansionObservationEncoder
import dev.aragos.aor.ai.TerritoryEncoder
import java.nio.file.Path

/** A separate three-head source wraps, but never relaxes, the strict CURRENT two-head source. */
internal class TripleCollectionInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val joint: JointCollectionInput,
    val expansion: TerritoryActorInput,
) {
    val ecology = requireNotNull(joint.ecology) { "Triple collection requires CURRENT ecology." }
    val dictionary =
        ExpansionObservationEncoder.dictionary(
            PublicCatalog.forPlayers(joint.configuration.playerCount, joint.configuration.rules),
            TerritoryEncoder.EXPANSION_2,
        )
    val replayIndices: List<Int> = ecology.replayIndices
    val controlledBehavior: Map<String, String> =
        mapOf(
            "expansion" to expansion.sha256,
            "card" to
                requireNotNull(joint.bundle.card) { "The controlled card actor is required." }
                    .sha256,
            "purchase" to joint.bundle.purchase.sha256,
        )
    val signature =
        trainingSignature("$VERSION:$sourceSha256:${joint.signature()}:${expansion.sha256}")

    init {
        require(joint.configuration.id == "players-4/standard/no-crusades/deck-true/art-true") {
            "The triple prerequisite requires its four-seat standard configuration."
        }
        require(joint.opponents.size == 1 && joint.opponents.single().card != null) {
            "Triple collection requires one explicit frozen rival with both learned heads."
        }
        require(
            expansion.model.schemaVersion == 2 &&
                expansion.model.encoderVersion == TerritoryEncoder.EXPANSION_2.version &&
                expansion.model.stateFeatureNames == dictionary.stateFeatureNames &&
                expansion.model.actionFeatureNames == dictionary.actionFeatureNames
        ) {
            "The expansion actor requires schema2 and the complete EXPANSION_2 dictionary."
        }
        val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(ecology.metadata())
        val token = metadata.required("tokenOrder")
        require(
            token.required("candidateSelector").asText() == "FIXED_16_TO_15" &&
                token.required("opponentSelectors").map(JsonNode::asText) ==
                    listOf("FIXED_16_TO_15")
        ) {
            "The controlled and rival token roles must retain exact fixed T."
        }
        val references = metadata.required("expansionImitation")
        require(
            references.required("opponentModels").size() == 1 &&
                references.required("candidateModel").required("sha256") ==
                    references.required("opponentModels")[0].required("sha256")
        ) {
            "The learner prior and frozen rival must share the same actual E reference."
        }
    }

    /**
     * Original paths are provenance; snapshot names are confined relative to the new collection.
     */
    fun snapshots(): Map<String, Map<String, String>> = buildMap {
        fun add(name: String, path: Path, hash: String) {
            check(put(name, mapOf("path" to path.toString(), "sha256" to hash)) == null) {
                "A triple snapshot name must have exactly one owner."
            }
        }
        add("source.json", source, sourceSha256)
        add("expansion.json", expansion.path, expansion.sha256)
        add("joint/source.json", joint.source, joint.sourceSha256)
        add("joint/baseline.json", joint.baseline.path, joint.baseline.sha256)
        fun bundle(prefix: String, value: JointActorBundle) {
            add("joint/${prefix}bundle.json", value.path, value.sha256)
            value.card?.let { add("joint/${prefix}card-policy.json", it.path, it.sha256) }
            add("joint/${prefix}purchase-policy.json", value.purchase.path, value.purchase.sha256)
        }
        bundle("", joint.bundle)
        joint.opponents.forEachIndexed { index, value -> bundle("opponent-$index-", value) }
        val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(ecology.metadata())
        val references = metadata.required("expansionImitation")
        val subjects =
            listOf(
                metadata.required("sharedPurchaseBaseline"),
                references.required("candidateModel"),
            ) + references.required("opponentModels").toList()
        subjects.forEach { subject ->
            val name = PurchaseJson.text(subject.required("snapshot"), "ecology snapshot")
            require(name.matches(Regex("[a-zA-Z0-9._-]+"))) {
                "An ecology snapshot must be a simple file name."
            }
            add(
                "joint/$name",
                Path.of(PurchaseJson.text(subject.required("path"), "ecology path")),
                PurchaseJson.text(subject.required("sha256"), "ecology SHA"),
            )
        }
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        snapshots().forEach { (name, subject) ->
            val relative = Path.of(name)
            val owner =
                relative.parent?.let { TrainingArtifacts(artifacts.directory.resolve(it)) }
                    ?: artifacts
            owner.ensureFileSnapshot(
                Path.of(requireNotNull(subject["path"]) { "Snapshot source is required." }),
                requireNotNull(subject["sha256"]) { "Snapshot SHA is required." },
                relative.fileName.toString(),
            )
        }
    }

    companion object {
        const val VERSION = "triple-on-policy-collect-1"

        fun read(path: Path): TripleCollectionInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "jointSource", "expansion"))
            require(node.required("kind").asText() == VERSION) {
                "An explicit triple source is required."
            }
            fun subject(name: String): Pair<Path, String> {
                val value = node.required(name)
                PurchaseJson.fields(value, setOf("path", "sha256"))
                val file =
                    source.parent
                        .resolve(PurchaseJson.text(value.required("path"), name))
                        .toRealPath()
                val expected = PurchaseJson.text(value.required("sha256"), "$name SHA")
                require(
                    expected.matches(Regex("[0-9a-f]{64}")) && PurchaseJson.hash(file) == expected
                ) {
                    "The declared $name subject changed."
                }
                return file to expected
            }
            val (jointPath, jointHash) = subject("jointSource")
            val (actorPath, actorHash) = subject("expansion")
            val joint = JointCollectionInput.read(jointPath)
            val actor =
                TerritoryActorInput(actorPath, actorHash, TerritoryPolicyInput.read(actorPath))
            require(
                PurchaseJson.hash(source) == hash &&
                    joint.sourceSha256 == jointHash &&
                    PurchaseJson.hash(actorPath) == actorHash
            ) {
                "The triple source or behavior changed while being read."
            }
            return TripleCollectionInput(source, hash, joint, actor)
        }
    }
}
