package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** One fixed32-world supported mixed shard, using the existing bounded game/store owner. */
internal class CoordinatedWinInput
private constructor(
    override val source: TokenPairedSubject,
    val subjects: Map<String, TokenPairedSubject?>,
    val host: QualificationHost,
    val protocol: CoordinatedWinProtocol,
    val origin: CoordinatedWinOrigin,
    val inherited: CoordinatedWinCalibration,
    val preparation: CoordinatedWinPreparation,
    override val stage: CoordinatedEsStage,
    override val generation: Int,
    override val globalStart: Int,
    override val count: Int,
    override val bank: CoordinatedEsBank,
    override val arena: ArenaSpecification,
    val population: CoordinatedWinPopulation?,
) : CoordinatedGenerationInput {
    override val prefix = "coordinated-win"
    override val arenaSource
        get() = required("arena")

    override val scopeSha256
        get() = protocol.scopeSha256

    override val originalBundleSha256
        get() = origin.scope.original.source.sha256

    override val panelFiles = false
    override val indices = globalStart until globalStart + count
    override val roles = population?.members?.keys?.toList() ?: listOf("CONTROL")
    override val additionalIdentity: Map<String, Any> =
        mapOf(
            "warmStartSha256" to protocol.warmStartSha256,
            "inheritedCalibrationSha256" to inherited.source.sha256,
            "ecologySha256" to protocol.ecologySha256,
        )
    val scienceIdentity =
        mapOf(
            "kind" to VERSION,
            "stage" to stage,
            "generation" to generation,
            "scopeSha256" to scopeSha256,
            "ecologySha256" to protocol.ecologySha256,
            "bank" to bank,
            "subjects" to
                subjects
                    .filterKeys { it !in setOf("arena", "originProof") }
                    .mapValues { it.value?.sha256 },
        )
    override val scienceSha256 = CpePairedDigest.json(scienceIdentity)
    override val signature = trainingSignature("$VERSION:${source.sha256}:$scienceSha256")
    override val specifications: Map<String, ArenaSpecification> =
        population?.members?.mapValues { (_, bundle) ->
            arena.copy(
                coordinatedEs =
                    ArenaCoordinatedPolicy.supported(
                        bundle,
                        origin.scope.original,
                        origin.scope.baseline,
                        inherited.calibration,
                    )
            )
        } ?: mapOf("CONTROL" to arena)

    fun required(name: String) = requireNotNull(subjects[name]) { "$name subject is required." }

    override fun bundleSha256(role: String) = population?.members?.let { it[role] }?.source?.sha256

    override fun canonical(role: String, index: Int): Boolean =
        stage == CoordinatedEsStage.POPULATION &&
            (index == 0 && role == CoordinatedEsPopulation.role((generation - 1) * 2) ||
                index == 255 && role == CoordinatedEsPopulation.role((generation - 1) * 2 + 1))

    override fun inputPins(): Map<String, String> = buildMap {
        putAll(origin.pins)
        putAll(preparation.pins)
        putAll(inherited.pins)
        putAll(CoordinatedWinEcology.pins(arenaSource, arena))
        put(source.path.toString(), source.sha256)
        subjects.values.filterNotNull().forEach {
            it.verify()
            put(it.path.toString(), it.sha256)
        }
        population?.members?.values?.forEach { bundle ->
            bundle.verify()
            put(bundle.source.path.toString(), bundle.source.sha256)
            bundle.subjects.values.forEach { put(it.path.toString(), it.sha256) }
        }
    }

    override fun metadata(): Map<String, Any?> =
        mapOf(
            "kind" to "$prefix-prepared-1",
            "signature" to signature,
            "scienceSha256" to scienceSha256,
            "scopeSha256" to scopeSha256,
            "ecologySha256" to protocol.ecologySha256,
            "host" to host.name,
            "stage" to stage,
            "generation" to generation,
            "globalStart" to globalStart,
            "count" to count,
            "bank" to bank,
            "dictionaries" to
                origin.scope.dictionaries.entries.associate { it.key.name to it.value.wire() },
            "policyBindings" to arena.overlayMetadata(),
            "subjects" to subjects.mapValues { it.value?.let(CoordinatedEsWire::subject) },
            "source" to source.metadata("source.json"),
            "arena" to arenaSource.metadata("arena.json"),
            "originalBundle" to CoordinatedEsWire.subject(origin.scope.original.source),
            "inputPins" to inputPins(),
        ) + additionalIdentity

    companion object {
        const val VERSION = "coordinated-win-generation-1"
        val SUBJECTS =
            setOf(
                "protocol",
                "arena",
                "originalBundle",
                "projection",
                "warmStart",
                "originProof",
                "center",
                "directions",
                "population",
                "inheritedCalibration",
                "previousUpdate",
                "historical",
                "numericalPreparation",
            )

        fun read(path: Path): CoordinatedWinInput {
            val source = CoordinatedEsWire.source(path)
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                SUBJECTS +
                    setOf("kind", "stage", "generation", "host", "globalStart", "count", "bank"),
            )
            require(node["kind"]?.asText() == VERSION) {
                "An explicit new win-continuation generation source is required."
            }
            val stage =
                CoordinatedEsStage.entries.singleOrNull { it.name == node["stage"]?.asText() }
                    ?: error("Only explicit CONTROLS and POPULATION stages are admitted.")
            val host =
                QualificationHost.entries.singleOrNull { it.name == node["host"]?.asText() }
                    ?: error("The original DESKTOP or MAC owner must be declared.")
            val generation = PurchaseJson.integer(node["generation"], "generation")
            val start = PurchaseJson.integer(node["globalStart"], "global start")
            val count = PurchaseJson.integer(node["count"], "shard count")
            require(
                generation in 1..16 &&
                    count == 32 &&
                    start in 0..224 &&
                    start % 32 == 0 &&
                    (host == QualificationHost.DESKTOP) == (start < 160)
            ) {
                "A continuation shard retains its fixed generation, host and original32-world range."
            }
            val subjects = SUBJECTS.associateWith { name ->
                node.required(name).takeUnless(JsonNode::isNull)?.let {
                    TokenPairedSubject.read(it, source.path.parent)
                }
            }
            fun required(name: String) =
                requireNotNull(subjects[name]) {
                    "$name subject is required from the first continuation step."
                }
            require(
                (subjects["population"] != null) == (stage == CoordinatedEsStage.POPULATION) &&
                    (subjects["previousUpdate"] == null) == (generation == 1)
            ) {
                "Controls omit mutants, and only the first continuation step omits its predecessor."
            }
            val protocol = CoordinatedWinProtocol.read(required("protocol"))
            require(
                required("originalBundle").sha256 == protocol.space["originalBundleSha256"] &&
                    required("projection").sha256 == protocol.space["projectionSha256"]
            ) {
                "The original bundle and projection cannot change when continuing a warm center."
            }
            val bank = CoordinatedEsBank.read(node["bank"], 256)
            require(protocol.generation(generation)["worldBank"] == node["bank"]) {
                "Every shard must retain its complete protocol bank."
            }
            val warm = protocol.warm(required("warmStart"))
            val preparation =
                CoordinatedWinPreparation.read(required("numericalPreparation"), protocol)
            val prepared = PurchaseJson.read(preparation.source.path)
            require(
                prepared["artifacts"]["warm-start.json"]?.asText() ==
                    required("warmStart").sha256 &&
                    prepared["artifacts"]["projection.json"]?.asText() ==
                        required("projection").sha256 &&
                    prepared["artifacts"]["calibration/inherited-calibration.json"]?.asText() ==
                        required("inheritedCalibration").sha256 &&
                    (generation != 1 ||
                        prepared["artifacts"]["initial-center.json"]?.asText() ==
                            required("center").sha256)
            ) {
                "Generation subjects must retain their exact original completed preparation artifacts."
            }
            val center = PurchaseJson.read(required("center").path)
            protocol.center(center, generation - 1, warm)
            protocol.directions(required("directions"), generation, preparation)
            subjects["previousUpdate"]?.let {
                protocol.predecessor(PurchaseJson.read(it.path), center, generation, preparation)
            }
            val origin = CoordinatedWinOrigin.read(required("originProof"), protocol, host)
            origin.validateWarm(warm)
            require(required("originalBundle").sha256 == origin.scope.original.source.sha256) {
                "The current original bundle must retain the proven raw identity."
            }
            val inherited =
                CoordinatedWinCalibration.read(
                    required("inheritedCalibration"),
                    protocol,
                    origin.scope.original,
                )
            CoordinatedWinHistorical.validate(required("historical"), protocol)
            val arena = CoordinatedWinEcology.read(required("arena"), protocol, origin, bank)
            val population =
                subjects["population"]?.let {
                    CoordinatedWinPopulation.read(
                        it,
                        protocol,
                        origin,
                        required("center"),
                        required("directions"),
                        inherited,
                        generation,
                        bank,
                    )
                }
            return CoordinatedWinInput(
                    source,
                    subjects,
                    host,
                    protocol,
                    origin,
                    inherited,
                    preparation,
                    stage,
                    generation,
                    start,
                    count,
                    bank,
                    arena,
                    population,
                )
                .also { it.inputPins() }
        }
    }
}
