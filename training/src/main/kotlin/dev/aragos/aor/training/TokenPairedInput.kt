package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
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
    val anchor: TokenFrozenAnchor? = null,
    val layout: TokenPairedLayout = TokenPairedLayout(),
    val proposalBudget: Int = 9,
) {
    val version = if (anchor == null) VERSION else ANCHORED_VERSION
    val roundField = if (anchor == null) "iteration" else "round"
    val configuration = arena.configurations.single()
    val dictionary =
        TokenObservationEncoder.dictionary(
            PublicCatalog.forPlayers(4, configuration.rules),
            TerritoryEncoder.TOKEN_2,
        )
    val policyBindings: JsonNode = PurchaseJson.mapper.valueToTree(arena.overlayMetadata())
    val ecologyIdentity =
        TokenPairedEcology.identity(arena, ordinarySha256) +
            (anchor?.let { mapOf("frozenAnchor" to it.identity) } ?: emptyMap())
    val ecologySha256 = TokenPairedEcology.hash(ecologyIdentity)
    val scopeIdentity: Map<String, Any> =
        mapOf(
            "kind" to version,
            "ecology" to ecologyIdentity,
            "originalActorSha256" to originalActor.sha256,
            "dictionary" to dictionary,
            "requestProfile" to
                if (anchor == null) "global-640-seat-epoch-ordinal-1"
                else "declared-seat-epoch-ordinal-1",
            "proposalProfile" to
                if (anchor == null) "uniform-eight-reference-two-futures-1"
                else TokenAnchoredProposals.VERSION,
        ) +
            if (anchor == null) emptyMap()
            else
                mapOf(
                    "trainCount" to layout.trainCount,
                    "originCount" to layout.originCount,
                    "proposalBudget" to proposalBudget,
                )
    val scopeSha256 = trainingSignature(PurchaseJson.mapper.writeValueAsString(scopeIdentity))
    val scienceIdentity: Map<String, Any> =
        scopeIdentity +
            mapOf(
                roundField to iteration,
                "currentActorSha256" to currentActor.sha256,
                "origins" to origins,
                "replayIndices" to replayIndices,
            )
    val scienceSha256 = trainingSignature(PurchaseJson.mapper.writeValueAsString(scienceIdentity))
    val signature =
        trainingSignature("$version:${source.sha256}:${arenaSource.sha256}:$scienceSha256")
    val ownedReplayIndices = replayIndices.filter { it in range.indices }

    init {
        require(if (anchor == null) proposalBudget == 9 else proposalBudget in 9..16) {
            "The token proposal budget must match its fixed legacy or declared anchored family."
        }
        require(if (anchor == null) iteration in 1..2 else iteration in 1..16) {
            "Token collection requires its fixed legacy iteration or a declared bounded anchored round."
        }
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
        anchor?.let {
            val warm = it.bundle.models.token
            require(
                original.stateWeights == warm.stateWeights &&
                    original.stateBias == warm.stateBias &&
                    original.actionWeights == warm.actionWeights &&
                    original.actionBias == warm.actionBias &&
                    current.valueWeights.all { value -> value == 0.0 } &&
                    current.valueBias == 0.0
            ) {
                "An anchored learner starts from exact94 embeddings with a separate zero readout/value."
            }
        }
    }

    fun request(index: Int) = TokenPairedRequest(index, layout)

    fun kind(stage: String) =
        if (anchor == null) "token-paired-$stage-1" else "token-anchored-paired-$stage-1"

    fun actorMetadata(subject: TokenPairedSubject, legacySnapshot: String): Map<String, Any> =
        if (anchor == null) subject.metadata(legacySnapshot)
        else
            mapOf(
                "path" to subject.path.toString(),
                "sha256" to subject.sha256,
                "originalPath" to subject.originalPath,
                "externalImmutable" to true,
            )

    /** Internal legacy data classes do not determine the versioned anchored transport contract. */
    fun originWire(origin: TokenPairedOrigin): ObjectNode =
        PurchaseJson.mapper.valueToTree<ObjectNode>(origin).also { wire ->
            if (anchor != null) {
                wire.put("kind", kind("origin"))
                wire.set<JsonNode>("round", wire.remove("iteration"))
                wire["publicRoot"].takeUnless(JsonNode::isNull)?.let { root ->
                    (root as ObjectNode).remove("inclusionProbability")
                }
                wire["anchors"].forEach { row ->
                    row as ObjectNode
                    row.set<JsonNode>("round", row.remove("iteration"))
                }
            }
        }

    fun controllers(
        globalIndex: Int,
        record: ((TokenPairedDecision) -> Unit)? = null,
    ): Map<Int, SimulationController> {
        val seat = request(globalIndex).seat
        val normal = arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })
        val reference =
            requireNotNull(normal[seat]) { "The complete G3/E/T reference is required." }
        val frozen =
            anchor
                ?: return normal +
                    (seat to TokenPairedController(reference, current, original, record))
        return normal.mapValues { (player, raw) ->
            val qualified = frozen.qualified(raw, arena.candidate)
            if (player == seat) TokenAnchoredController(raw, qualified, frozen, current, record)
            else qualified
        }
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        listOf(source, arenaSource, currentActor, originalActor).forEach(TokenPairedSubject::verify)
        artifacts.ensureFileSnapshot(source.path, source.sha256, "source.json")
        artifacts.ensureFileSnapshot(arenaSource.path, arenaSource.sha256, "arena.json")
        anchor?.let {
            it.verify()
            artifacts.ensureFileSnapshot(it.source.path, it.source.sha256, "anchor.json")
            artifacts.write("anchor-identity.json", it.identity)
            artifacts.write(
                "immutable-input-pins.json",
                it.pins +
                    listOf(source, arenaSource, currentActor, originalActor).associate { row ->
                        row.path.toString() to row.sha256
                    },
            )
            return
        }
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
        const val ANCHORED_VERSION = "token-anchored-paired-collect-1"

        fun read(path: Path): TokenPairedInput {
            val declared = path.toAbsolutePath().normalize()
            val sourcePath = declared.toRealPath()
            val source =
                TokenPairedSubject(sourcePath, PurchaseJson.hash(sourcePath), declared.toString())
            val node = PurchaseJson.read(sourcePath)
            val anchored = node["kind"]?.asText() == ANCHORED_VERSION
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    if (anchored) "round" else "iteration",
                    "globalStart",
                    "count",
                    "arena",
                    "currentActor",
                    "originalActor",
                    "origins",
                    "replayIndices",
                ) + if (anchored) setOf("anchor", "trainCount", "proposalBudget") else emptySet(),
            )
            require(node["kind"]?.asText() in setOf(VERSION, ANCHORED_VERSION)) {
                "Use an explicit legacy or anchored token paired source."
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
            val layout =
                if (anchored)
                    TokenPairedLayout(
                        origins.size,
                        PurchaseJson.integer(node["trainCount"], "train count"),
                        anchored = true,
                    )
                else
                    TokenPairedLayout().also {
                        require(origins.size == 640) {
                            "Every legacy shard must bind the complete original 640-row stream table."
                        }
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
                    layout,
                )
            val replays =
                PurchaseJson.array(node["replayIndices"], "replayIndices").map {
                    PurchaseJson.integer(it, "replay index")
                }
            require(
                if (anchored)
                    replays.isNotEmpty() &&
                        replays == replays.distinct().sorted() &&
                        replays.all { it in origins.indices } &&
                        replays.size <= 16
                else replays == listOf(0, 213, 426, 639)
            ) {
                "Each token family must retain its exact bounded global reproduction schedule."
            }
            val anchor =
                if (anchored)
                    TokenFrozenAnchor.read(
                        TokenPairedSubject.read(node.required("anchor"), sourcePath.parent),
                        arena,
                    )
                else null
            return TokenPairedInput(
                    source,
                    arenaSubject,
                    currentSubject,
                    originalSubject,
                    arena,
                    current,
                    original,
                    PurchaseJson.integer(node[if (anchored) "round" else "iteration"], "round"),
                    range,
                    origins,
                    replays,
                    ordinary.first(),
                    anchor,
                    layout,
                    if (anchored) PurchaseJson.integer(node["proposalBudget"], "proposal budget")
                    else 9,
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
