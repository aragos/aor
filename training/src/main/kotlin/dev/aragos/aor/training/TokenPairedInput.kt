package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryPolicyModel
import dev.aragos.aor.ai.TokenObservationEncoder
import java.nio.file.Path

/** A readable subject is separate from its declared immutable provenance path. */
internal data class TokenPairedSubject(
    val path: Path,
    val sha256: String,
    val originalPath: String,
) {
    fun metadata(snapshot: String): Map<String, String> =
        mapOf(
            "path" to path.toString(),
            "sha256" to sha256,
            "originalPath" to originalPath,
            "snapshot" to snapshot,
        )

    fun verify() {
        require(PurchaseJson.hash(path) == sha256) { "Token paired subject changed: $path." }
    }

    companion object {
        fun read(node: JsonNode, directory: Path): TokenPairedSubject {
            PurchaseJson.fields(node, setOf("path", "sha256"), setOf("originalPath"))
            val declared =
                directory
                    .resolve(PurchaseJson.text(node["path"], "subject path"))
                    .toAbsolutePath()
                    .normalize()
            val path = declared.toRealPath()
            val hash = PurchaseJson.text(node["sha256"], "subject SHA")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "Token subjects require a SHA-256." }
            val original =
                node["originalPath"]?.let { PurchaseJson.text(it, "originalPath") }
                    ?: declared.toString()
            require(
                Path.of(original).isAbsolute && Path.of(original).normalize().toString() == original
            ) {
                "Original subject provenance must be an absolute normalized path."
            }
            return TokenPairedSubject(path, hash, original).also(TokenPairedSubject::verify)
        }
    }
}

/** Host ranges cannot change the shared scientific identity or any global request/stream. */
internal class TokenPairedInput
private constructor(
    val source: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val currentActor: TokenPairedSubject,
    val originalActor: TokenPairedSubject,
    val arena: ArenaSpecification,
    val current: TerritoryPolicyModel,
    val original: TerritoryPolicyModel,
    val iteration: Int,
    val range: TokenPairedRange,
    val origins: List<TokenPairedSeeds>,
    val replayIndices: List<Int>,
    val ordinarySha256: String,
) {
    val configuration = arena.configurations.single()
    val dictionary =
        TokenObservationEncoder.dictionary(
            PublicCatalog.forPlayers(4, configuration.rules),
            TerritoryEncoder.TOKEN_2,
        )
    val policyBindings: JsonNode = PurchaseJson.mapper.valueToTree(arena.overlayMetadata())
    val ecologyIdentity = TokenPairedEcology.identity(arena, ordinarySha256)
    val ecologySha256 = TokenPairedEcology.hash(ecologyIdentity)
    val scopeIdentity: Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "ecology" to ecologyIdentity,
            "originalActorSha256" to originalActor.sha256,
            "dictionary" to dictionary,
            "requestProfile" to "global-640-seat-epoch-ordinal-1",
            "proposalProfile" to "uniform-eight-reference-two-futures-1",
        )
    val scopeSha256 = trainingSignature(PurchaseJson.mapper.writeValueAsString(scopeIdentity))
    val scienceIdentity: Map<String, Any> =
        scopeIdentity +
            mapOf(
                "iteration" to iteration,
                "currentActorSha256" to currentActor.sha256,
                "origins" to origins,
                "replayIndices" to replayIndices,
            )
    val scienceSha256 = trainingSignature(PurchaseJson.mapper.writeValueAsString(scienceIdentity))
    val signature =
        trainingSignature("$VERSION:${source.sha256}:${arenaSource.sha256}:$scienceSha256")
    val ownedReplayIndices = replayIndices.filter { it in range.indices }

    init {
        require(iteration in 1..2) { "Only the two fixed token iterations are supported." }
        require(iteration != 1 || currentActor.sha256 == originalActor.sha256) {
            "The first iteration must use the exact original token actor."
        }
        require(
            listOf(current, original).all {
                it.schemaVersion == 3 &&
                    it.encoderVersion == TerritoryEncoder.TOKEN_2.version &&
                    it.stateFeatureNames == dictionary.stateFeatureNames &&
                    it.actionFeatureNames == dictionary.actionFeatureNames
            }
        ) {
            "Token collection requires schema 3 and the complete TOKEN_2 dictionary."
        }
        require(
            original.actorWeights.all { it == 0.0 } &&
                original.valueWeights.all { it == 0.0 } &&
                original.valueBias == 0.0
        ) {
            "The original token actor must be the prospective zero-output initializer."
        }
    }

    fun controllers(
        globalIndex: Int,
        record: ((TokenPairedDecision) -> Unit)? = null,
    ): Map<Int, SimulationController> {
        val seat = TokenPairedRequest(globalIndex).seat
        val normal = arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
        val reference =
            requireNotNull(normal[seat]) { "The complete G3/E/T reference is required." }
        return normal + (seat to TokenPairedController(reference, current, original, record))
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        listOf(source, arenaSource, currentActor, originalActor).forEach(TokenPairedSubject::verify)
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena.json")
        artifacts.ensureFileSnapshot(currentActor.path, currentActor.sha256, "current-actor.json")
        artifacts.ensureFileSnapshot(
            originalActor.path,
            originalActor.sha256,
            "original-actor.json",
        )
        val ecology = TrainingArtifacts(artifacts.directory.resolve("ecology"))
        arena.snapshotModels(ecology)
        arena.snapshotOverlays(ecology)
    }

    companion object {
        const val VERSION = "token-paired-collect-1"
        const val ACTOR_VERSION = "aor-token-policy-2"

        fun read(path: Path): TokenPairedInput {
            val declared = path.toAbsolutePath().normalize()
            val sourcePath = declared.toRealPath()
            val source =
                TokenPairedSubject(sourcePath, PurchaseJson.hash(sourcePath), declared.toString())
            val node = PurchaseJson.read(sourcePath)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "iteration",
                    "globalStart",
                    "count",
                    "arena",
                    "currentActor",
                    "originalActor",
                    "origins",
                    "replayIndices",
                ),
            )
            require(node["kind"]?.asText() == VERSION) {
                "Use the explicit token-paired-collect-1 source."
            }
            val arenaSubject = TokenPairedSubject.read(node.required("arena"), sourcePath.parent)
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
            val ordinary =
                (listOf(arenaNode["candidate"], arenaNode["incumbent"]) +
                        PurchaseJson.array(arenaNode["opponents"], "opponents"))
                    .map { item ->
                        PurchaseJson.hash(
                            arenaSubject.path.parent
                                .resolve(PurchaseJson.text(item, "ordinary model"))
                                .toRealPath()
                        )
                    }
            require(ordinary.distinct().size == 1) {
                "Every ordinary role requires the identical raw M8 bytes."
            }
            val currentSubject =
                TokenPairedSubject.read(node.required("currentActor"), sourcePath.parent)
            val originalSubject =
                TokenPairedSubject.read(node.required("originalActor"), sourcePath.parent)
            val current = TerritoryPolicyInput.read(currentSubject.path)
            val original =
                if (currentSubject.sha256 == originalSubject.sha256) current
                else TerritoryPolicyInput.read(originalSubject.path)
            val origins =
                PurchaseJson.array(node["origins"], "origins").map { row ->
                    PurchaseJson.fields(row, setOf("gameSeed", "proposalSeed", "future"))
                    TokenPairedSeeds(
                        PurchaseJson.integer(row["gameSeed"], "gameSeed"),
                        exactLong(row["proposalSeed"]),
                        PurchaseJson.array(row["future"], "future").map(::exactLong),
                    )
                }
            require(origins.size == 640) {
                "Every shard must bind the complete original 640-row stream table."
            }
            val streams = origins.flatMap {
                listOf(it.gameSeed.toLong(), it.proposalSeed) + it.future
            }
            require(streams.distinct().size == streams.size) {
                "All world/proposal/future streams must be disjoint within the iteration."
            }
            val range =
                TokenPairedRange(
                    PurchaseJson.integer(node["globalStart"], "globalStart"),
                    PurchaseJson.integer(node["count"], "count"),
                )
            val replays =
                PurchaseJson.array(node["replayIndices"], "replayIndices").map {
                    PurchaseJson.integer(it, "replay index")
                }
            require(replays == listOf(0, 213, 426, 639)) {
                "Every shard binds the same four global reproduction indices; only its own range executes them."
            }
            return TokenPairedInput(
                    source,
                    arenaSubject,
                    currentSubject,
                    originalSubject,
                    arena,
                    current,
                    original,
                    PurchaseJson.integer(node["iteration"], "iteration"),
                    range,
                    origins,
                    replays,
                    ordinary.first(),
                )
                .also {
                    listOf(source, arenaSubject, currentSubject, originalSubject)
                        .forEach(TokenPairedSubject::verify)
                }
        }

        private fun exactLong(value: JsonNode): Long {
            require(value.isIntegralNumber && value.canConvertToLong()) {
                "Streams must be exact signed 64-bit integers."
            }
            return value.longValue()
        }
    }
}
