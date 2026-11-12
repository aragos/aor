package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * Homogeneous sampled joint actors with explicit raw ordinary, expansion, token and F prior roles.
 */
internal class ExpansionPairedInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSha256: String,
    val arena: ArenaSpecification,
    val origins: List<ExpansionPairedSeeds>,
) {
    val configuration = arena.configurations.single()
    val signature =
        trainingSignature("expansion-paired-data-1:$sourceSha256:$arenaSha256:${arena.identity()}")

    fun controllers(seat: Int): Map<Int, SimulationController> =
        arena.controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })

    fun verifySources() {
        require(
            PurchaseJson.hash(source) == sourceSha256 &&
                PurchaseJson.hash(arenaSource) == arenaSha256
        ) {
            "Frozen expansion paired source or arena changed."
        }
    }

    fun snapshot(artifacts: TrainingArtifacts) {
        verifySources()
        artifacts.ensureFileSnapshot(source, sourceSha256, "study-source.json")
        arena.snapshotModels(artifacts)
        arena.snapshotOverlays(artifacts)
        artifacts.ensureFileSnapshot(arenaSource, arenaSha256, "arena-source.json")
    }

    companion object {
        fun read(path: Path): ExpansionPairedInput {
            val source = path.toRealPath()
            val sourceHash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "arena", "origins"))
            require(node["kind"]?.asText() == "expansion-paired-data-1") {
                "Expansion paired source requires expansion-paired-data-1."
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
            require(rows.isArray && rows.size() == ExpansionPairedRequest.ORIGINS) {
                "Declare exactly 1,024 prospective expansion paired origins without refill."
            }
            val origins = rows.map { row ->
                PurchaseJson.fields(row, setOf("gameSeed", "proposalSeed", "future"))
                val future = row.required("future")
                require(future.isArray) { "Future streams must be an array." }
                ExpansionPairedSeeds(
                    PurchaseJson.integer(row.required("gameSeed"), "gameSeed"),
                    exactLong(row.required("proposalSeed")),
                    future.map { value ->
                        require(value.isIntegralNumber && value.canConvertToLong()) {
                            "Future streams must be exact signed 64-bit integers."
                        }
                        value.longValue()
                    },
                )
            }
            val streams = origins.flatMap {
                listOf(it.gameSeed.toLong(), it.proposalSeed) + it.future
            }
            require(streams.distinct().size == streams.size) {
                "Origin/future streams must be disjoint; only actions share paired streams."
            }
            require(
                PurchaseJson.hash(source) == sourceHash && PurchaseJson.hash(arenaPath) == arenaHash
            ) {
                "Expansion paired sources changed while being read."
            }
            return ExpansionPairedInput(source, sourceHash, arenaPath, arenaHash, arena, origins)
        }

        private fun exactLong(value: JsonNode): Long {
            require(value.isIntegralNumber && value.canConvertToLong()) {
                "Proposal streams must be exact signed 64-bit integers."
            }
            return value.longValue()
        }

        internal fun validateEcology(arena: ArenaSpecification) {
            require(
                arena.configurations.singleOrNull()?.id ==
                    "players-4/standard/no-crusades/deck-true/art-true" &&
                    arena.purpose == ArenaPurpose.SCREENING
            ) {
                "Expansion paired collection requires one SCREENING configuration."
            }
            require(
                arena.jointPolicy != null &&
                    arena.opponents.size == 1 &&
                    arena.candidate == arena.incumbent &&
                    arena.opponents.single() == arena.candidate
            ) {
                "Expansion paired collection requires one identical ordinary model in every role."
            }
            require(
                arena.candidate.weights["planning.tokenOrderControl.fixed16To15"] != 1.0 &&
                    arena.candidate.weights["planning.expansionModel.enabled"] != 1.0
            ) {
                "The ordinary model must remain raw; token/expansion control belongs to explicit overlays."
            }
            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
            val joint =
                requireNotNull(metadata["jointPolicy"]) {
                    "Expansion paired collection requires sampled joint card/purchase actors and separate prior."
                }
            val shared = joint["sharedBundle"]
            require(shared != null && !shared["card"].isNull && !shared["purchase"].isNull) {
                "Every Expansion paired role must include both learned heads."
            }
            for (role in listOf("candidateBundle", "incumbentBundle")) {
                require(joint[role]?.get("sha256")?.asText() == shared["sha256"].asText()) {
                    "Every Expansion paired role must use the identical frozen joint bundle."
                }
            }
            val token = requireNotNull(metadata["tokenOrder"]) { "The token overlay is required." }
            require(
                token["candidateSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["incumbentSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["opponentSelectors"]?.map(JsonNode::asText) == listOf("FIXED_16_TO_15")
            ) {
                "Every expansion paired role must use the same explicit fixed token selector."
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
                "Every expansion paired role must use the same frozen expansion model."
            }
        }
    }
}
