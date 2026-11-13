package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** One globally indexed head cohort, with complete current/original three-head continuations. */
internal class CpePairedInput
private constructor(
    val source: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val arena: ArenaSpecification,
    val current: CpePairedBundle,
    val original: CpePairedBundle,
    val purchaseBaseline: PurchaseBaselineInput,
    val head: CpeHead,
    val iteration: Int,
    val range: TokenPairedRange,
    val origins: List<CpePairedSeeds>,
    val replayIndices: List<Int>,
) {
    val configuration = arena.configurations.single()
    val dictionaries = original.dictionaries
    val policyBindings: JsonNode = PurchaseJson.mapper.valueToTree(arena.overlayMetadata())
    val ecologyIdentity =
        TokenPairedEcology.identity(arena, TokenPairedEcology.ordinaryHash(arenaSource.path))
    val ecologySha256 = TokenPairedEcology.hash(ecologyIdentity)
    val bundleScopeIdentity =
        mapOf(
            "kind" to VERSION,
            "ecology" to ecologyIdentity,
            "originalBundle" to original.identity(),
            "requestProfile" to "head-640-seat-epoch-branching-reservoir-1",
            "proposalProfile" to "current-sample-uniform-eight-two-futures-1",
        )
    val bundleScopeSha256 = digest(bundleScopeIdentity)
    val scopeSha256 = digest(mapOf("bundleScopeSha256" to bundleScopeSha256, "head" to head))
    val scienceIdentity =
        mapOf(
            "scopeSha256" to scopeSha256,
            "iteration" to iteration,
            "currentBundle" to current.identity(),
            "origins" to origins,
            "replayIndices" to replayIndices,
        )
    val scienceSha256 = digest(scienceIdentity)
    val signature =
        trainingSignature("$VERSION:${source.sha256}:${arenaSource.sha256}:$scienceSha256")
    val ownedReplayIndices = replayIndices.filter { it in range.indices }

    init {
        require(
            iteration in 1..2 && (iteration != 1 || current.source.sha256 == original.source.sha256)
        ) {
            "C/P/E collection requires two fixed iterations starting from the exact original bundle."
        }
        val joint = policyBindings.required("jointPolicy")
        require(purchaseBaseline.sha256 == joint["baseline"]["sha256"]?.asText()) {
            "The current purchase controller must retain the arena's exact frozen F0 prior."
        }
        for (role in listOf("candidateBundle", "incumbentBundle", "sharedBundle")) {
            require(
                joint[role]["card"]["sha256"]?.asText() == original.subject(CpeHead.CARD).sha256 &&
                    joint[role]["purchase"]["sha256"]?.asText() ==
                        original.subject(CpeHead.PURCHASE).sha256
            ) {
                "Every original C/P head must be the exact frozen qualified G3 component."
            }
        }
        require(original.models.expansion.actorWeights.all { it == 0.0 }) {
            "The original expansion actor must preserve its declared exact qualified-E prior."
        }
    }

    fun request(index: Int) = CpePairedRequest(head, index)

    fun controllers(
        index: Int,
        record: ((CpePairedDecision) -> Unit)? = null,
    ): Map<Int, SimulationController> {
        val seat = request(index).seat
        val normal = arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
        val reference = requireNotNull(normal[seat]) { "Complete G3/E/T reference is required." }
        return normal +
            (seat to
                CpePairedController(
                    reference,
                    arena.candidate,
                    purchaseBaseline.model,
                    current.models,
                    original.models,
                    head,
                    record,
                ))
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        listOf(source, arenaSource).forEach(TokenPairedSubject::verify)
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena.json")
        current.snapshot(artifacts, "current")
        original.snapshot(artifacts, "original")
        val ecology = TrainingArtifacts(artifacts.directory.resolve("ecology"))
        arena.snapshotModels(ecology)
        arena.snapshotOverlays(ecology)
    }

    companion object {
        const val VERSION = "cpe-paired-collect-1"

        fun read(path: Path): CpePairedInput {
            val declared = path.toAbsolutePath().normalize()
            val actual = declared.toRealPath()
            val source = TokenPairedSubject(actual, PurchaseJson.hash(actual), declared.toString())
            val node = PurchaseJson.read(actual)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "iteration",
                    "head",
                    "globalStart",
                    "count",
                    "arena",
                    "currentBundle",
                    "originalBundle",
                    "origins",
                    "replayIndices",
                ),
            )
            require(node["kind"]?.asText() == VERSION) {
                "Use an explicit C/P/E collection source."
            }
            val arenaSubject = TokenPairedSubject.read(node.required("arena"), actual.parent)
            val arenaNode = PurchaseJson.read(arenaSubject.path)
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
                    "jointPolicy",
                    "tokenOrder",
                    "expansionImitation",
                ),
            )
            val arena = ArenaSpecification.read(arenaSubject.path)
            ExpansionPairedInput.validateEcology(arena)
            val dictionaries = CpePairedDictionary.forConfiguration(arena.configurations.single())
            val originalSubject =
                TokenPairedSubject.read(node.required("originalBundle"), actual.parent)
            val currentSubject =
                TokenPairedSubject.read(node.required("currentBundle"), actual.parent)
            val original = CpePairedBundle.read(originalSubject, dictionaries)
            val current =
                if (currentSubject == originalSubject) original
                else CpePairedBundle.read(currentSubject, dictionaries)
            val baselinePath =
                arenaSubject.path.parent
                    .resolve(
                        PurchaseJson.text(arenaNode["jointPolicy"]["baseline"], "purchase baseline")
                    )
                    .toRealPath()
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    PurchaseJson.hash(baselinePath),
                    TrainingArtifacts.readModel(baselinePath),
                )
            val origins =
                PurchaseJson.array(node["origins"], "origins").map { row ->
                    PurchaseJson.fields(
                        row,
                        setOf("gameSeed", "reservoirSeed", "proposalSeed", "future"),
                    )
                    CpePairedSeeds(
                        PurchaseJson.integer(row["gameSeed"], "gameSeed"),
                        exactLong(row["reservoirSeed"]),
                        exactLong(row["proposalSeed"]),
                        PurchaseJson.array(row["future"], "future").map(::exactLong),
                    )
                }
            require(origins.size == 640 && origins.flatMap { it.all }.distinct().size == 3200) {
                "Every head shard binds its complete 640-world table with all streams distinct."
            }
            val replays =
                PurchaseJson.array(node["replayIndices"], "replayIndices").map {
                    PurchaseJson.integer(it, "replay index")
                }
            require(replays == listOf(0, 213, 426, 639)) {
                "C/P/E cohorts require the four fixed global reproductions."
            }
            return CpePairedInput(
                    source,
                    arenaSubject,
                    arena,
                    current,
                    original,
                    baseline,
                    CpeHead.valueOf(PurchaseJson.text(node["head"], "head")),
                    PurchaseJson.integer(node["iteration"], "iteration"),
                    TokenPairedRange(
                        PurchaseJson.integer(node["globalStart"], "globalStart"),
                        PurchaseJson.integer(node["count"], "count"),
                    ),
                    origins,
                    replays,
                )
                .also {
                    listOf(source, arenaSubject, currentSubject, originalSubject)
                        .forEach(TokenPairedSubject::verify)
                    require(PurchaseJson.hash(baseline.path) == baseline.sha256) {
                        "The frozen F0 purchase baseline changed during input admission."
                    }
                }
        }

        private fun exactLong(value: JsonNode): Long {
            require(value.isIntegralNumber && value.canConvertToLong()) {
                "Streams must be exact signed 64-bit integers."
            }
            return value.longValue()
        }

        private fun digest(value: Any): String =
            trainingSignature(PurchaseJson.mapper.writeValueAsString(value))
    }
}
