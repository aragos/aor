package dev.aragos.aor.training

import java.nio.file.Path

/** Three predeclared roles; the raw control has no schema-2 expansion actor. */
internal enum class ExpansionBaselineRole {
    TREATMENT,
    CONTROL,
    RAW_G3,
}

/** One 1,024-world bank; no intermediate actor or change in rival behavior is representable. */
internal class ExpansionBaselineInput
private constructor(
    val source: ExpansionScreenSubject,
    val arenaSource: ExpansionScreenSubject,
    val arena: ArenaSpecification,
    val admission: ExpansionBaselineAdmission,
    val historical: List<ExpansionHistoricalControl>,
) {
    val signature =
        trainingSignature(
            "$VERSION:${source.sha256}:${arena.identity()}:${admission.source.sha256}"
        )
    val rawComparison = arena.copy(expansionActor = null)

    fun specification(role: ExpansionBaselineRole): ArenaSpecification =
        if (role == ExpansionBaselineRole.RAW_G3) rawComparison else arena

    fun side(role: ExpansionBaselineRole): ArenaSide =
        if (role == ExpansionBaselineRole.TREATMENT) ArenaSide.CANDIDATE else ArenaSide.INCUMBENT

    fun verify() {
        source.verify()
        arenaSource.verify()
        admission.verify()
        historical.forEach {
            it.source.verify()
            it.pair.verify()
            it.replay.verify()
        }
    }

    fun manifest(): Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "signature" to signature,
            "replica" to admission.replica.name,
            "worlds" to WORLDS,
            "source" to source.metadata(),
            "arena" to arenaSource.metadata(),
            "admission" to admission.source.metadata(),
            "policyBindings" to arena.overlayMetadata(),
            "roles" to ExpansionBaselineRole.entries.map { it.name },
            "seed" to arena.seed,
            "seeds" to List(WORLDS) { StrategyArena.seed(arena.seed, it) },
            "replayIndices" to REPLAYS,
            "maximumTerminalExecutions" to 3086,
            "historical" to
                historical.map {
                    mapOf(
                        "source" to it.source.metadata(),
                        "pair" to it.pair.metadata(),
                        "replay" to it.replay.metadata(),
                        "index" to it.index,
                        "side" to it.side.commandValue,
                    )
                },
        )

    fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        artifacts.ensureFileSnapshot(source.path, source.sha256, "study-source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena-source.json")
        artifacts.ensureFileSnapshot(
            admission.source.path,
            admission.source.sha256,
            "admission.json",
        )
        val roles = TrainingArtifacts(artifacts.directory.resolve("roles"))
        arena.snapshotModels(roles)
        arena.snapshotOverlays(roles)
    }

    companion object {
        const val VERSION = "expansion-baseline-screen-1"
        const val WORLDS = 1024
        val REPLAYS = listOf(0, 341, 682, 1023)

        fun read(path: Path): ExpansionBaselineInput {
            val file = path.toRealPath()
            val source = ExpansionScreenSubject(file, PurchaseJson.hash(file), file.toString())
            val node = source.read()
            PurchaseJson.fields(node, setOf("kind", "arena", "admission", "historical"))
            require(node["kind"]?.asText() == VERSION) {
                "An explicit completed baseline comparison screen is required."
            }
            val arenaSource = ExpansionScreenSubject.read(file, node.required("arena"))
            PurchaseJson.fields(
                arenaSource.read(),
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
                    "expansionActor",
                ),
            )
            val admission =
                ExpansionBaselineAdmission.read(
                    ExpansionScreenSubject.read(file, node.required("admission"))
                )
            val arena = ArenaSpecification.read(arenaSource.path)
            require(
                arena.pairsPerConfiguration == WORLDS &&
                    arena.experimentalRulesTransfer == null &&
                    arena.frozenPurchase == null
            ) {
                "The development screen requires exactly 1,024 worlds and the unchanged learning engine profile."
            }
            ExpansionPairedInput.validateEcology(arena)
            val actors =
                requireNotNull(arena.expansionActor) {
                    "Both expansion actor roles must be explicit."
                }
            val candidate =
                requireNotNull(actors.actor(ArenaSide.CANDIDATE)) {
                    "Baseline comparison actor is required."
                }
            val control =
                requireNotNull(actors.actor(ArenaSide.INCUMBENT)) {
                    "Completed constant-baseline control is required."
                }
            require(
                candidate.sha256 == admission.treatmentPolicy.sha256 &&
                    candidate.path == admission.treatmentPolicy.path &&
                    control.sha256 == admission.controlPolicy.sha256 &&
                    control.path == admission.controlPolicy.path
            ) {
                "Arena roles must be the exact admitted treatment-8 and control-8 files."
            }
            admission.validateEcology(arena)
            val histories =
                PurchaseJson.array(node["historical"], "historical controls").map { item ->
                    PurchaseJson.fields(item, setOf("source", "pair", "replay", "index", "side"))
                    ExpansionHistoricalControl(
                        ExpansionScreenSubject.read(file, item["source"]),
                        ExpansionScreenSubject.read(file, item["pair"]),
                        ExpansionScreenSubject.read(file, item["replay"]),
                        PurchaseJson.integer(item["index"], "historical index"),
                        StrategyReplaySide.parse(
                            PurchaseJson.text(item["side"], "historical side")
                        ),
                    )
                }
            require(
                histories.size == 2 && histories.map { it.replay.sha256 }.distinct().size == 2
            ) {
                "Freeze exactly two distinct historical raw-G3 controls before the fresh bank."
            }
            return ExpansionBaselineInput(source, arenaSource, arena, admission, histories).also {
                it.verify()
            }
        }
    }
}
