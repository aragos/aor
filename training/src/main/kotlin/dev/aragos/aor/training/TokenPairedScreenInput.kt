package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

internal enum class TokenPairedScreenRole {
    FINAL,
    RAW_T,
    INITIAL,
}

/**
 * One fixed 128-world shard; the full bank and all actor/role identities are shared across hosts.
 */
internal class TokenPairedScreenInput
private constructor(
    val source: TokenPairedSubject,
    val arenaSource: TokenPairedSubject,
    val raw: ArenaSpecification,
    val arena: ArenaSpecification,
    val admission: TokenPairedAdmission,
    val historical: TokenPairedSubject,
    val globalStart: Int,
) {
    val indices = globalStart until globalStart + 128
    val ownedReplays = REPLAYS.filter { it in indices }
    val scienceIdentity: Map<String, Any> =
        mapOf(
            "kind" to VERSION,
            "scopeSha256" to admission.scopeSha256,
            "ecologySha256" to admission.ecologySha256,
            "finalActorSha256" to admission.finalActor.sha256,
            "originalActorSha256" to admission.originalActor.sha256,
            "seeds" to List(1024) { StrategyArena.seed(arena.seed, it) },
            "replayIndices" to REPLAYS,
            "roles" to TokenPairedScreenRole.entries.map { it.name },
        )
    val scienceSha256 = trainingSignature(PurchaseJson.mapper.writeValueAsString(scienceIdentity))
    val signature =
        trainingSignature("$VERSION:${source.sha256}:${admission.source.sha256}:$scienceSha256")

    fun specification(role: TokenPairedScreenRole): ArenaSpecification =
        if (role == TokenPairedScreenRole.RAW_T) raw else arena

    fun side(role: TokenPairedScreenRole): ArenaSide =
        if (role == TokenPairedScreenRole.FINAL) ArenaSide.CANDIDATE else ArenaSide.INCUMBENT

    fun verify() {
        source.verify()
        arenaSource.verify()
        admission.verify()
        historical.verify()
    }

    fun manifest(): Map<String, Any> =
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

    fun snapshot(artifacts: TrainingArtifacts) {
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
        const val VERSION = "token-paired-screen-1"
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

        fun read(path: Path): TokenPairedScreenInput {
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
                "Declare the separate final TOKEN_2 screen source."
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
                TokenPairedAdmission.read(
                    TokenPairedSubject.read(node.required("admission"), file.parent)
                )
            val arenaSource = TokenPairedSubject.read(node.required("arena"), file.parent)
            val raw = readRawArena(arenaSource, admission, screen = true)
            val historical =
                TokenPairedSubject.read(node.required("historicalAdmission"), file.parent)
            validateHistorical(PurchaseJson.read(historical.path), admission)
            fun actor(subject: TokenPairedSubject): TerritoryActorInput {
                val model = TerritoryPolicyInput.read(subject.path)
                subject.verify()
                return TerritoryActorInput(subject.path, subject.sha256, model)
            }
            val final = actor(admission.finalActor)
            val original = actor(admission.originalActor)
            require(
                original.model.actorWeights.all { it == 0.0 } &&
                    listOf(original, final).all {
                        it.model.valueWeights.all { weight -> weight == 0.0 } &&
                            it.model.valueBias == 0.0
                    }
            ) {
                "The original token prior and frozen zero value head must remain exact."
            }
            val arena = raw.copy(tokenActor = ArenaTokenActor.from(final, original))
            return TokenPairedScreenInput(
                    source,
                    arenaSource,
                    raw,
                    arena,
                    admission,
                    historical,
                    start,
                )
                .also(TokenPairedScreenInput::verify)
        }

        internal fun readRawArena(
            source: TokenPairedSubject,
            admission: TokenPairedAdmission,
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

        internal fun validateHistorical(node: JsonNode, admission: TokenPairedAdmission) {
            require(
                node["kind"]?.asText() == "token-paired-historical-admission-1" &&
                    node["complete"]?.booleanValue() == true &&
                    node["controls"]?.intValue() == 2 &&
                    node["zeroFailures"]?.booleanValue() == true &&
                    node["protocolSha256"]?.asText() == admission.protocol.sha256 &&
                    node["scopeSha256"]?.asText() == admission.scopeSha256 &&
                    node["ecologySha256"]?.asText() == admission.ecologySha256 &&
                    node["scientificTerminalExecutions"]?.intValue() == 2 &&
                    node["records"]?.size() == 2
            ) {
                "Both historical controls must complete on Desktop before either host starts the fresh screen."
            }
        }
    }
}
