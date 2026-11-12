package dev.aragos.aor.training

import java.nio.file.Path

/**
 * Explicit input/source identities; neither origin worlds nor future streams are generated here.
 */
internal class PurchaseSequenceInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSha256: String,
    val study: PurchaseSequenceStudy,
    private val ordinarySources: Map<String, ModelInitializationSource?>,
) {
    fun signature(): String =
        trainingSignature(
            listOf(
                    PurchaseSequenceProtocol.VERSION,
                    source.toString(),
                    sourceSha256,
                    arenaSource.toString(),
                    arenaSha256,
                    study.arena.identity(),
                    ordinarySources
                        .toSortedMap()
                        .map { (role, input) -> "$role:${input?.path}:${input?.sha256}" }
                        .joinToString("\n"),
                )
                .joinToString("\n")
        )

    fun manifest(): Map<String, Any?> =
        mapOf(
            "kind" to PurchaseSequenceProtocol.VERSION,
            "signature" to signature(),
            "source" to source.toString(),
            "sourceSha256" to sourceSha256,
            "arenaSource" to arenaSource.toString(),
            "arenaSha256" to arenaSha256,
            "environmentSha256" to CardAdvantageExecutor.hash(listOf(study.arena.identity())),
            "configuration" to study.configuration.id,
            "evaluatedSide" to ArenaSide.INCUMBENT,
            "origins" to study.origins,
            "ordinaryInputs" to
                ordinarySources.mapValues { (role, input) ->
                    input?.metadata("ordinary-$role-input.json")
                },
            "overlays" to study.arena.overlayMetadata(),
            "maximumSimulatedTerminals" to PurchaseSequenceProtocol.MAXIMUM_TERMINALS,
            "maximumSimulatedTails" to PurchaseSequenceProtocol.MAXIMUM_TAILS,
            "maximumFullCanonicalTraversals" to PurchaseSequenceProtocol.MAXIMUM_TERMINALS,
            "maximumPartialPrefixReconstructions" to PurchaseSequenceProtocol.ORIGINS,
            "additionalFullPolicyReproductions" to 0,
            "qualification" to false,
            "activation" to false,
        )

    fun snapshot(directory: Path) {
        val artifacts = TrainingArtifacts(directory)
        artifacts.ensureFileSnapshot(source, sourceSha256, "teacher-source.json")
        artifacts.ensureFileSnapshot(arenaSource, arenaSha256, "arena-source.json")
        ordinarySources.forEach { (role, input) ->
            input?.let {
                artifacts.ensureFileSnapshot(it.path, it.sha256, "ordinary-$role-input.json")
            }
        }
        study.arena.snapshotModels(artifacts)
        study.arena.snapshotOverlays(artifacts)
    }

    fun verifySources() {
        require(
            PurchaseJson.hash(source) == sourceSha256 &&
                PurchaseJson.hash(arenaSource) == arenaSha256
        ) {
            "Teacher or arena source changed after input validation."
        }
        ordinarySources.values.filterNotNull().forEach { input ->
            require(PurchaseJson.hash(input.path) == input.sha256) {
                "An ordinary teacher input changed after parsing."
            }
        }
    }

    companion object {
        fun read(path: Path): PurchaseSequenceInput {
            val source = path.toRealPath()
            val sourceSha = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("schemaVersion", "protocol", "arena", "origins"))
            require(
                PurchaseJson.integer(node.required("schemaVersion"), "schemaVersion") == 1 &&
                    PurchaseJson.text(node.required("protocol"), "protocol") ==
                        PurchaseSequenceProtocol.VERSION
            ) {
                "Purchase sequence source requires schema 1 and the fixed teacher protocol."
            }
            val arenaSource =
                source.parent
                    .resolve(PurchaseJson.text(node.required("arena"), "arena"))
                    .normalize()
                    .toRealPath()
            val arenaHash = PurchaseJson.hash(arenaSource)
            val arenaNode = PurchaseJson.read(arenaSource)
            PurchaseJson.fields(
                arenaNode,
                setOf(
                    "schemaVersion",
                    "purpose",
                    "configurations",
                    "candidate",
                    "incumbent",
                    "opponents",
                    "pairsPerConfiguration",
                    "seed",
                ),
                setOf(
                    "frozenPurchase",
                    "jointPolicy",
                    "tokenOrder",
                    "expansionImitation",
                    "experimentalRulesTransfer",
                ),
            )
            val references =
                listOf(
                    "candidate" to arenaNode.required("candidate"),
                    "incumbent" to arenaNode.required("incumbent"),
                ) +
                    PurchaseJson.array(arenaNode.required("opponents"), "opponents").mapIndexed {
                        index,
                        value ->
                        "opponent-$index" to value
                    }
            val ordinary = references.associate { (role, value) ->
                role to
                    if (value.isNull) {
                        null
                    } else {
                        val resolved =
                            arenaSource.parent
                                .resolve(PurchaseJson.text(value, role))
                                .normalize()
                                .toRealPath()
                        ModelInitializationSource(resolved, PurchaseJson.hash(resolved))
                    }
            }
            val arena = ArenaSpecification.read(arenaSource)
            require(arena.purpose == ArenaPurpose.SCREENING) {
                "A teacher source is diagnostic, never a qualification arena."
            }
            val origins =
                PurchaseJson.array(node.required("origins"), "origins").map { origin ->
                    PurchaseJson.fields(
                        origin,
                        setOf("gameSeed", "seat", "opponentIndices", "continuationSeeds"),
                    )
                    PurchaseSequenceOrigin(
                        PurchaseJson.integer(origin.required("gameSeed"), "gameSeed"),
                        PurchaseJson.integer(origin.required("seat"), "seat"),
                        PurchaseJson.array(origin.required("opponentIndices"), "opponentIndices")
                            .map { PurchaseJson.integer(it, "opponent index") },
                        PurchaseJson.array(
                                origin.required("continuationSeeds"),
                                "continuationSeeds",
                            )
                            .map { seed ->
                                require(seed.isIntegralNumber && seed.canConvertToLong()) {
                                    "A continuation stream must be a signed 64-bit integer."
                                }
                                seed.longValue()
                            },
                    )
                }
            return PurchaseSequenceInput(
                    source,
                    sourceSha,
                    arenaSource,
                    arenaHash,
                    PurchaseSequenceStudy(arena, origins),
                    ordinary,
                )
                .also(PurchaseSequenceInput::verifySources)
        }
    }
}
