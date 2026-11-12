package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Controllable acquisition routes; bonus/shortage draws are context, never forced actions. */
internal enum class AcquisitionRoute(val decision: String, val buyCommand: String) {
    CASH("buy-card", "buy-card"),
    EXPANSION("expand", "buy-expansion-card"),
}

/** Integer thirds of the pool observed at the first own expansion decision of THIS turn. */
internal enum class AcquisitionBand {
    EARLY,
    MIDDLE,
    LATE;

    companion object {
        fun from(initial: Int, remaining: Int): AcquisitionBand {
            require(initial >= 0 && remaining >= 0) { "Expansion pools cannot be negative." }
            return when {
                remaining.toLong() * 3 > initial.toLong() * 2 -> EARLY
                remaining.toLong() * 3 > initial -> MIDDLE
                else -> LATE
            }
        }
    }
}

/** Explicit future chance starts pair BUY/reference only; they do NOT resample a hidden deck. */
internal data class AcquisitionOriginSeeds(
    val gameSeed: Int,
    val cash: List<Long>,
    val expansion: List<Long>,
) {
    init {
        require(cash.size == 2 && expansion.size == 2) {
            "Each acquisition route requires exactly two future chance streams."
        }
    }

    fun future(route: AcquisitionRoute): List<Long> =
        if (route == AcquisitionRoute.CASH) cash else expansion
}

/** Prospective request depends on world index alone, never hidden cards or terminal results. */
internal data class AcquisitionRequest(val index: Int) {
    init {
        require(index in 0 until 256) { "Acquisition requests belong to the fixed 256-world bank." }
    }

    val seat: Int = index % 4
    val epoch: Int = 1 + (index / 4) % 3
    val band: AcquisitionBand = AcquisitionBand.entries[(index / 12) % 3]

    fun eligible(
        route: AcquisitionRoute,
        epoch: Int,
        decision: String,
        offered: List<String>,
        incumbent: String,
        initialPool: Int?,
        remainingPool: Int,
    ): Boolean =
        epoch == this.epoch &&
            decision == route.decision &&
            route.buyCommand in offered &&
            incumbent in offered &&
            if (route == AcquisitionRoute.CASH) {
                // A discard followed by a possible buy is not an observed acquisition decline.
                incumbent == "pass"
            } else {
                incumbent != route.buyCommand &&
                    initialPool != null &&
                    AcquisitionBand.from(initialPool, remainingPool) == band
            }
}

/** Exact homogeneous sampled ecology; concrete M8/E/G2/F0 bytes are pinned by the source. */
internal class AcquisitionPilotInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val arenaSource: Path,
    val arenaSha256: String,
    val arena: ArenaSpecification,
    val origins: List<AcquisitionOriginSeeds>,
) {
    val configuration = arena.configurations.single()
    val signature =
        trainingSignature("acquisition-data-pilot-1:$sourceSha256:$arenaSha256:${arena.identity()}")

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
        fun read(path: Path): AcquisitionPilotInput {
            val source = path.toRealPath()
            val hash = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "arena", "origins"))
            require(node["kind"]?.asText() == "acquisition-data-pilot-1") {
                "Acquisition source requires acquisition-data-pilot-1."
            }
            val arenaPath =
                source.parent
                    .resolve(PurchaseJson.text(node.required("arena"), "arena"))
                    .toRealPath()
            val arenaHash = PurchaseJson.hash(arenaPath)
            val arena = ArenaSpecification.read(arenaPath)
            validateEcology(arena)
            val values = node.required("origins")
            require(values.isArray && values.size() == 256) {
                "An acquisition pilot declares exactly 256 origins, without refill."
            }
            val origins = values.map { origin ->
                PurchaseJson.fields(origin, setOf("gameSeed", "cash", "expansion"))
                fun streams(name: String): List<Long> {
                    val list = origin.required(name)
                    require(list.isArray) { "Future chance streams must be arrays." }
                    return list.map { value ->
                        require(value.isIntegralNumber && value.canConvertToLong()) {
                            "Future chance streams must be exact signed 64-bit integers."
                        }
                        value.longValue()
                    }
                }
                AcquisitionOriginSeeds(
                    PurchaseJson.integer(origin.required("gameSeed"), "gameSeed"),
                    streams("cash"),
                    streams("expansion"),
                )
            }
            val streams = origins.flatMap { listOf(it.gameSeed.toLong()) + it.cash + it.expansion }
            require(streams.distinct().size == streams.size) {
                "Origin and future streams must be distinct; only the two arms share a stream."
            }
            require(
                PurchaseJson.hash(source) == hash && PurchaseJson.hash(arenaPath) == arenaHash
            ) {
                "Acquisition inputs changed while being read."
            }
            return AcquisitionPilotInput(source, hash, arenaPath, arenaHash, arena, origins)
        }

        private fun validateEcology(arena: ArenaSpecification) {
            require(arena.configurations.size == 1 && arena.purpose == ArenaPurpose.SCREENING) {
                "Acquisition collection requires one SCREENING configuration."
            }
            require(
                arena.jointPolicy == null &&
                    arena.opponents.size == 1 &&
                    arena.candidate == arena.incumbent &&
                    arena.opponents.single() == arena.candidate
            ) {
                "Acquisition collection requires one identical ordinary model in every role."
            }
            require(arena.candidate.weights["planning.tokenOrderControl.fixed16To15"] != 1.0) {
                "The ordinary model must remain raw; token control belongs to its explicit overlay."
            }
            val metadata = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
            val purchase =
                requireNotNull(metadata["frozenPurchase"]) {
                    "Acquisition collection requires a frozen sampled purchase policy and separate prior."
                }
            require(purchase.fieldNames().asSequence().toSet() == setOf("baseline", "policy")) {
                "Acquisition purchase roles must all use the shared sampled actor and prior."
            }
            val token = requireNotNull(metadata["tokenOrder"]) { "The token overlay is required." }
            require(
                token["candidateSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["incumbentSelector"]?.asText() == "FIXED_16_TO_15" &&
                    token["opponentSelectors"]?.map(JsonNode::asText) == listOf("FIXED_16_TO_15")
            ) {
                "Every acquisition role must use the same explicit fixed token selector."
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
                "Every acquisition role must use the same frozen expansion model."
            }
        }
    }
}
