package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.IncumbentResidualSelector
import java.nio.file.Path

/** Cross-host role/dictionary identity independent of final protocol, projection and host paths. */
internal class CoordinatedEsScope(
    val arenaSource: TokenPairedSubject,
    val arena: ArenaSpecification,
    val original: CoordinatedEsBundle,
    val baseline: PurchaseBaselineInput,
) {
    val configuration = arena.configurations.single()
    val ecologyIdentity =
        TokenPairedEcology.identity(arena, TokenPairedEcology.ordinaryHash(arenaSource.path))
    val ecologySha256 = TokenPairedEcology.hash(ecologyIdentity)
    val identity: Map<String, Any> =
        mapOf(
            "kind" to "coordinated-es-scope-1",
            "ecology" to ecologyIdentity,
            "originalBundle" to original.identity(),
            "selector" to
                mapOf(
                    "kind" to IncumbentResidualSelector.VERSION,
                    "margin" to 1,
                    "rawResiduals" to true,
                    "ties" to "REFERENCE_THEN_LOWEST_ORIGINAL_INDEX",
                ),
        )
    val sha256 = CpePairedDigest.json(identity)
    val dictionaries = original.dictionaries
    val policyBindings: JsonNode = PurchaseJson.mapper.valueToTree(arena.overlayMetadata())

    init {
        ExpansionPairedInput.validateEcology(arena)
        require(
            arena.expansionActor == null &&
                arena.tokenActor == null &&
                arena.cpeBundle == null &&
                arena.coordinatedEs == null
        ) {
            "Coordinated search starts from the exact raw-G3 reference, without another learned overlay."
        }
        original.validateOriginal(arena, baseline)
    }

    fun inputPins(): Map<String, String> = buildMap {
        fun pin(path: Path, hash: String) {
            val key = path.toRealPath().toString()
            require(PurchaseJson.hash(path) == hash) {
                "A reference input changed before preparation: $path."
            }
            val prior = put(key, hash)
            require(prior == null || prior == hash) {
                "One readable input cannot have conflicting identities."
            }
        }
        fun metadata(node: JsonNode) {
            if (node.isObject) {
                if (node["path"]?.isTextual == true && node["sha256"]?.isTextual == true) {
                    pin(Path.of(node["path"].asText()), node["sha256"].asText())
                }
                node.elements().forEachRemaining(::metadata)
            } else if (node.isArray) node.forEach(::metadata)
        }
        pin(arenaSource.path, arenaSource.sha256)
        pin(original.source.path, original.source.sha256)
        original.subjects.values.forEach { pin(it.path, it.sha256) }
        metadata(policyBindings)
        val arenaNode = PurchaseJson.read(arenaSource.path)
        (listOf(arenaNode.required("candidate"), arenaNode.required("incumbent")) +
                arenaNode.required("opponents").toList())
            .forEach { value ->
                val path =
                    arenaSource.path.parent
                        .resolve(PurchaseJson.text(value, "ordinary model"))
                        .toRealPath()
                pin(path, PurchaseJson.hash(path))
            }
    }

    fun metadata(): Map<String, Any> =
        mapOf(
            "kind" to "coordinated-es-scope-prepared-1",
            "scopeIdentity" to identity,
            "scopeSha256" to sha256,
            "ecologySha256" to ecologySha256,
            "dictionaries" to
                dictionaries.entries.associate { (head, dictionary) ->
                    head.name to dictionary.wire()
                },
            "originalBundle" to
                (mapOf(
                    "path" to original.source.path.toString(),
                    "sha256" to original.source.sha256,
                    "originalPath" to original.source.originalPath,
                ) + mapOf("heads" to original.identity())),
            "policyBindings" to policyBindings,
            "inputPins" to inputPins(),
            "policyCalls" to 0,
            "scientificTerminalExecutions" to 0,
        )

    companion object {
        private val ARENA_FIELDS =
            setOf(
                "schemaVersion",
                "purpose",
                "configurations",
                "candidate",
                "incumbent",
                "opponents",
                "pairsPerConfiguration",
                "seed",
                "jointPolicy",
                "tokenOrder",
                "expansionImitation",
            )

        fun read(
            arenaSource: TokenPairedSubject,
            originalSubject: TokenPairedSubject,
        ): CoordinatedEsScope {
            arenaSource.verify()
            val node = PurchaseJson.read(arenaSource.path)
            PurchaseJson.fields(node, ARENA_FIELDS)
            val arena = ArenaSpecification.read(arenaSource.path)
            ExpansionPairedInput.validateEcology(arena)
            val original =
                CoordinatedEsBundle.readOriginal(
                    originalSubject,
                    CoordinatedEsBundle.dictionaries(arena.configurations.single()),
                )
            val path =
                arenaSource.path.parent
                    .resolve(PurchaseJson.text(node["jointPolicy"]["baseline"], "F0 baseline"))
                    .toRealPath()
            val baseline =
                PurchaseBaselineInput(
                    path,
                    PurchaseJson.hash(path),
                    TrainingArtifacts.readModel(path),
                )
            arenaSource.verify()
            return CoordinatedEsScope(arenaSource, arena, original, baseline)
        }
    }
}
