package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

internal enum class CpePairedScreenRole {
    FINAL,
    RAW_G3,
    INITIAL,
}

/**
 * One fixed 128-world shard; the full bank and all actor/role identities are shared across hosts.
 */
internal class CpePairedScreenInput
private constructor(
    override val source: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    override val raw: ArenaSpecification,
    val arena: ArenaSpecification,
    val admission: CpePairedAdmission,
    val historical: TokenPairedSubject,
    override val globalStart: Int,
) : ThreeRoleScreenInput {
    override val kind = VERSION
    override val roles = CpePairedScreenRole.entries.map { it.name }
    override val replayIndices = REPLAYS
    override val finalAdmissionSha256
        get() = admission.source.sha256

    override val historicalAdmissionSha256
        get() = historical.sha256

    override fun specification(role: String) = specification(CpePairedScreenRole.valueOf(role))

    override fun side(role: String) = side(CpePairedScreenRole.valueOf(role))

    override val indices = globalStart until globalStart + 128
    override val ownedReplays = REPLAYS.filter { it in indices }
    override val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "bundleScopeSha256" to admission.bundleScopeSha256,
            "ecologySha256" to admission.ecologySha256,
            "finalBundleSha256" to admission.finalBundle.sha256,
            "originalBundleSha256" to admission.originalBundle.sha256,
            "seeds" to List(1024) { StrategyArena.seed(arena.seed, it) },
            "replayIndices" to REPLAYS,
            "roles" to CpePairedScreenRole.entries.map { it.name },
        )
    override val scienceSha256 =
        trainingSignature(PurchaseJson.mapper.writeValueAsString(scienceIdentity))
    override val signature =
        trainingSignature("$VERSION:${source.sha256}:${admission.source.sha256}:$scienceSha256")

    fun specification(role: CpePairedScreenRole): ArenaSpecification =
        if (role == CpePairedScreenRole.RAW_G3) raw else arena

    fun side(role: CpePairedScreenRole): ArenaSide =
        if (role == CpePairedScreenRole.FINAL) ArenaSide.CANDIDATE else ArenaSide.INCUMBENT

    override fun verify() {
        source.verify()
        arenaSource.verify()
        admission.verify()
        historical.verify()
    }

    override fun manifest(): Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "signature" to signature,
            "scienceSha256" to scienceSha256,
            "scienceIdentity" to scienceIdentity,
            "globalStart" to globalStart,
            "count" to 128,
            "source" to source.metadata("study-source.json"),
            "arena" to arenaSource.metadata("arena-source.json"),
            "admission" to admission.source.metadata("admission.json"),
            "historicalAdmission" to historical.metadata("historical-admission.json"),
            "policyBindings" to arena.overlayMetadata(),
            "ownedReplayIndices" to ownedReplays,
            "maximumTerminalExecutions" to 384 + ownedReplays.size * 3,
        )

    override fun snapshot(artifacts: TrainingArtifacts) {
        verify()
        artifacts.ensureFileSnapshot(source.path, source.sha256, "study-source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena-source.json")
        artifacts.ensureFileSnapshot(
            admission.source.path,
            admission.source.sha256,
            "admission.json",
        )
        artifacts.ensureFileSnapshot(
            historical.path,
            historical.sha256,
            "historical-admission.json",
        )
        val roles = TrainingArtifacts(artifacts.directory.resolve("roles"))
        arena.snapshotModels(roles)
        arena.snapshotOverlays(roles)
    }

    companion object {
        const val VERSION = "cpe-paired-screen-1"
        val REPLAYS = listOf(0, 341, 682, 1023)
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

        fun read(path: Path): CpePairedScreenInput {
            val file = path.toRealPath()
            val source =
                TokenPairedSubject(
                    file,
                    PurchaseJson.hash(file),
                    path.toAbsolutePath().normalize().toString(),
                )
            val node = PurchaseJson.read(file)
            PurchaseJson.fields(
                node,
                setOf("kind", "arena", "admission", "historicalAdmission", "globalStart", "count"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "Declare the separate final complete C/P/E screen source."
            }
            val start = PurchaseJson.integer(node["globalStart"], "globalStart")
            require(
                start in 0..896 &&
                    start % 128 == 0 &&
                    PurchaseJson.integer(node["count"], "count") == 128
            ) {
                "A screen shard is one of eight fixed 128-world global ranges."
            }
            // No actor tensor is decoded until the complete portable two-update chain is verified.
            val admission =
                CpePairedAdmission.read(
                    TokenPairedSubject.read(node.required("admission"), file.parent)
                )
            val arenaSource = TokenPairedSubject.read(node.required("arena"), file.parent)
            val raw = readRawArena(arenaSource, admission, screen = true)
            val historical =
                TokenPairedSubject.read(node.required("historicalAdmission"), file.parent)
            validateHistorical(PurchaseJson.read(historical.path), admission)
            val dictionaries = CpePairedDictionary.forConfiguration(raw.configurations.single())
            val final = CpePairedBundle.read(admission.finalBundle, dictionaries)
            val original = CpePairedBundle.read(admission.originalBundle, dictionaries)
            final.models.validateFrozenValues(original.models)
            val arenaNode = PurchaseJson.read(arenaSource.path)
            val baselinePath =
                arenaSource.path.parent
                    .resolve(PurchaseJson.text(arenaNode["jointPolicy"]["baseline"], "F0 baseline"))
                    .toRealPath()
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    PurchaseJson.hash(baselinePath),
                    TrainingArtifacts.readModel(baselinePath),
                )
            original.validateOriginal(raw, baseline)
            val arena = raw.copy(cpeBundle = ArenaCpeBundle.from(final, original, baseline))
            return CpePairedScreenInput(
                    source,
                    arenaSource,
                    raw,
                    arena,
                    admission,
                    historical,
                    start,
                )
                .also(CpePairedScreenInput::verify)
        }

        internal fun readRawArena(
            source: TokenPairedSubject,
            admission: CpePairedAdmission,
            screen: Boolean,
        ): ArenaSpecification {
            PurchaseJson.fields(PurchaseJson.read(source.path), ARENA_FIELDS)
            val arena = ArenaSpecification.read(source.path)
            ExpansionPairedInput.validateEcology(arena)
            val ecology =
                TokenPairedEcology.hash(
                    TokenPairedEcology.identity(arena, TokenPairedEcology.ordinaryHash(source.path))
                )
            require(ecology == admission.ecologySha256) {
                "The screen must retain every original G3/M8/F0/E/T model role."
            }
            if (screen) {
                val bank = admission.protocolRecord.required("screenBank")
                require(
                    arena.pairsPerConfiguration == 1024 &&
                        bank["count"]?.intValue() == 1024 &&
                        bank["nativeOffset"]?.intValue() == arena.seed &&
                        bank["seeds"]?.map(JsonNode::intValue) ==
                            List(1024) { StrategyArena.seed(arena.seed, it) }
                ) {
                    "The final study requires its exact original 1,024-world native bank."
                }
            }
            source.verify()
            return arena
        }

        internal fun validateHistorical(node: JsonNode, admission: CpePairedAdmission) {
            require(
                node["kind"]?.asText() == "cpe-paired-historical-admission-1" &&
                    node["complete"]?.booleanValue() == true &&
                    node["controls"]?.intValue() == 2 &&
                    node["zeroFailures"]?.booleanValue() == true &&
                    node["protocolSha256"]?.asText() == admission.protocol.sha256 &&
                    node["bundleScopeSha256"]?.asText() == admission.bundleScopeSha256 &&
                    node["ecologySha256"]?.asText() == admission.ecologySha256 &&
                    node["scientificTerminalExecutions"]?.intValue() == 2 &&
                    node["records"]?.size() == 2
            ) {
                "Both historical controls must complete on Desktop before either host starts the fresh screen."
            }
        }
    }
}
