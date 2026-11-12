package dev.aragos.aor.training

import dev.aragos.aor.Stage
import dev.aragos.aor.ai.BotObservation
import java.nio.file.Path

/** Prospective phase assignment is balanced over seats in every twenty consecutive origins. */
internal data class RenaissancePhaseRequest(val index: Int) {
    init {
        require(index in 0 until 512) {
            "Renaissance requests belong to the fixed 512-origin bank."
        }
    }

    val seat: Int = index % 4
    val targetPhase: Stage = phases[(index / 4) % phases.size]

    /** Only a branching assigned-phase window is captured; pass-only windows remain coverage. */
    fun captures(phase: Stage, observation: BotObservation): Boolean =
        observation.decision == "renaissance" &&
            phase == targetPhase &&
            observation.candidates.size > 1

    /** Stateless prefix-only intervention. The caller stops before a captured root command. */
    fun prefixCommand(observation: BotObservation, incumbent: String): String {
        if (observation.decision != "renaissance") return incumbent
        require(observation.candidates.any { it.command == "pass" }) {
            "A Renaissance prefix window must offer PASS."
        }
        return "pass"
    }

    companion object {
        val phases =
            listOf(
                Stage.DRAW_CARD,
                Stage.BUY_CARD,
                Stage.PLAY_CARDS,
                Stage.PURCHASE,
                Stage.EXPANSION,
            )
    }
}

/** Both future streams are shared across all actions, never across independent origin worlds. */
internal data class RenaissancePhaseSeeds(val gameSeed: Int, val future: List<Long>) {
    init {
        require(future.size == 2) { "Each Renaissance origin requires two paired future streams." }
    }
}

/** Explicit data-only source; no rival acquisition profile or trained card actor is admitted. */
internal class RenaissancePhaseInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSha256: String,
    val arena: ArenaSpecification,
    val origins: List<RenaissancePhaseSeeds>,
) {
    val configuration = arena.configurations.single()
    val signature =
        trainingSignature("renaissance-phase-data-1:$sourceSha256:$arenaSha256:${arena.identity()}")

    fun controllers(seat: Int): Map<Int, SimulationController> =
        arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })

    fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(source, sourceSha256, "pilot-source.json")
        require(PurchaseJson.hash(arenaSource) == arenaSha256) {
            "The frozen arena source changed."
        }
        arena.snapshotModels(artifacts)
        arena.snapshotOverlays(artifacts)
        artifacts.ensureFileSnapshot(arenaSource, arenaSha256, "arena-source.json")
    }

    companion object {
        fun read(path: Path): RenaissancePhaseInput {
            val source = path.toRealPath()
            val sourceHash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "arena", "origins"))
            require(node["kind"]?.asText() == "renaissance-phase-data-1") {
                "Renaissance source requires renaissance-phase-data-1."
            }
            val arenaPath =
                source.parent
                    .resolve(PurchaseJson.text(node.required("arena"), "arena"))
                    .toRealPath()
            val arenaHash = PurchaseJson.hash(arenaPath)
            val arena = ArenaSpecification.read(arenaPath)
            AcquisitionPilotInput.validateEcology(arena)
            val rows = node.required("origins")
            require(rows.isArray && rows.size() == 512) {
                "Declare exactly 512 prospective Renaissance origins without refill."
            }
            val origins = rows.map { row ->
                PurchaseJson.fields(row, setOf("gameSeed", "future"))
                val future = row.required("future")
                require(future.isArray) { "Future streams must be an array." }
                RenaissancePhaseSeeds(
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
                "Renaissance sources changed while being read."
            }
            return RenaissancePhaseInput(source, sourceHash, arenaPath, arenaHash, arena, origins)
        }
    }
}
