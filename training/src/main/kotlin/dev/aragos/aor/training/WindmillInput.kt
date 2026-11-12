package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.ai.BotObservation
import java.nio.file.Path

/** Epoch assignment depends only on the prospective origin index, never on outcomes or scores. */
internal data class WindmillRequest(val index: Int) {
    init {
        require(index in 0 until 256) {
            "Windmill requests belong to the fixed 256-origin bank."
        }
    }

    val epoch: Int = 1 + index % 3
    val seat: Int = index % 4

    fun captures(epoch: Int, seat: Int, observation: BotObservation): Boolean =
        epoch == this.epoch &&
            seat == this.seat &&
            observation.decision == "windmill" &&
            observation.candidates.size == 2 &&
            observation.candidates.map { it.command }.toSet() == setOf("windmill", "pass")
}

/** Four future streams are shared across all actions, never across independent origin worlds. */
internal data class WindmillSeeds(val gameSeed: Int, val future: List<Long>) {
    init {
        require(future.size == 4) { "Each windmill origin requires four paired future streams." }
    }
}

/**
 * Homogeneous sampled joint actors with explicit raw ordinary, expansion, token and F prior roles.
 */
internal class WindmillInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSha256: String,
    val arena: ArenaSpecification,
    val origins: List<WindmillSeeds>,
) {
    val configuration = arena.configurations.single()
    val signature =
        trainingSignature("windmill-data-1:$sourceSha256:$arenaSha256:${arena.identity()}")

    fun controllers(seat: Int): Map<Int, SimulationController> =
        arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })

    fun verifySources() {
        require(
            PurchaseJson.hash(source) == sourceSha256 &&
                PurchaseJson.hash(arenaSource) == arenaSha256
        ) {
            "Frozen windmill source or arena changed."
        }
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        verifySources()
        artifacts.ensureFileSnapshot(source, sourceSha256, "pilot-source.json")
        arena.snapshotModels(artifacts)
        arena.snapshotOverlays(artifacts)
        artifacts.ensureFileSnapshot(arenaSource, arenaSha256, "arena-source.json")
    }

    companion object {
        fun read(path: Path): WindmillInput {
            val source = path.toRealPath()
            val sourceHash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "arena", "origins"))
            require(node["kind"]?.asText() == "windmill-data-1") {
                "Windmill source requires windmill-data-1."
            }
            val arenaPath =
                source.parent
                    .resolve(PurchaseJson.text(node.required("arena"), "arena"))
                    .toRealPath()
            val arenaHash = PurchaseJson.hash(arenaPath)
            PurchaseJson.fields(
                PurchaseJson.read(arenaPath),
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
                setOf("experimentalRulesTransfer"),
            )
            val arena = ArenaSpecification.read(arenaPath)
            validateEcology(arena)
            val rows = node.required("origins")
            require(rows.isArray && rows.size() == 256) {
                "Declare exactly 256 prospective windmill origins without refill."
            }
            val origins = rows.map { row ->
                PurchaseJson.fields(row, setOf("gameSeed", "future"))
                val future = row.required("future")
                require(future.isArray) { "Future streams must be an array." }
                WindmillSeeds(
                    PurchaseJson.integer(row.required("gameSeed"), "gameSeed"),
                    future.map { value ->
                        require(value.isIntegralNumber && value.canConvertToLong()) {
                            "Future streams must be exact signed 64-bit integers."
                        }
                        value.longValue()
                    },
                )
            }
            val streams = origins.flatMap { listOf(it.gameSeed.toLong()) + it.future }
            require(streams.distinct().size == streams.size) {
                "Origin/future streams must be disjoint; only actions share paired streams."
            }
            require(
                PurchaseJson.hash(source) == sourceHash && PurchaseJson.hash(arenaPath) == arenaHash
            ) {
                "Windmill sources changed while being read."
            }
            return WindmillInput(source, sourceHash, arenaPath, arenaHash, arena, origins)
        }

        internal fun validateEcology(arena: ArenaSpecification) {
            require(arena.configurations.size == 1 && arena.purpose == ArenaPurpose.SCREENING) {
                "Windmill collection requires one SCREENING configuration."
            }
            require(
                arena.jointPolicy != null &&
                    arena.opponents.size == 1 &&
                    arena.candidate == arena.incumbent &&
                    arena.opponents.single() == arena.candidate
            ) {
                "Windmill collection requires one identical ordinary model in every role."
            }
            require(arena.candidate.weights["planning.tokenOrderControl.fixed16To15"] != 1.0) {
                "The ordinary model must remain raw; token control belongs to its explicit overlay."
            }
            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
            val joint =
                requireNotNull(metadata["jointPolicy"]) {
                    "Windmill collection requires sampled joint card/purchase actors and separate prior."
                }
            val shared = joint["sharedBundle"]
            require(shared != null && !shared["card"].isNull && !shared["purchase"].isNull) {
                "Every Windmill role must include both learned heads."
            }
            for (role in listOf("candidateBundle", "incumbentBundle")) {
                require(joint[role]?.get("sha256")?.asText() == shared["sha256"].asText()) {
                    "Every Windmill role must use the identical frozen joint bundle."
                }
            }
            val token = requireNotNull(metadata["tokenOrder"]) { "The token overlay is required." }
            require(
                token["candidateSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["incumbentSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["opponentSelectors"]?.map(JsonNode::asText) == listOf("FIXED_16_TO_15")
            ) {
                "Every windmill role must use the same explicit fixed token selector."
            }
            val expansion =
                requireNotNull(metadata["expansionImitation"]) {
                    "The expansion overlay is required."
                }
            val candidate = expansion["candidateModel"]?.get("sha256")?.asText()
            require(
                candidate != null &&
                    expansion["incumbentModel"]?.get("sha256")?.asText() == candidate &&
                    expansion["opponentModels"]?.map { it["sha256"]?.asText() } == listOf(candidate)
            ) {
                "Every windmill role must use the same frozen expansion model."
            }
        }
    }
}
