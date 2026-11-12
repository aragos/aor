package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Path
import kotlin.math.abs

/** Fixed continuation roles. None is nominated or selected from terminal returns. */
internal enum class CardStyleInteractionStyle {
    G3,
    D1_PLUS,
    D1_MINUS,
    D2_PLUS,
    D2_MINUS,
}

/** Same prospective72 schedule, with no inherited TRAIN/VALIDATION/TEST meaning. */
internal data class CardStyleInteractionRequest(val index: Int) {
    val seat: Int
    val epoch: Int
    val ordinal: Int

    init {
        val schedule = ExpansionPairedRequest(index)
        seat = schedule.seat
        epoch = schedule.epoch
        ordinal = schedule.ordinal
    }
}

internal data class CardStyleInteractionSeeds(val gameSeed: Int, val future: List<Long>) {
    init {
        require(future.size == 2 && (future + gameSeed.toLong()).distinct().size == 3) {
            "One origin and two distinct paired future streams are required."
        }
    }
}

/** New study boundary; existing expansion collectors and arena source contracts are unchanged. */
internal class CardStyleInteractionInput
private constructor(
    val source: Path,
    val sourceSha256: String,
    val calibration: Path,
    val calibrationSha256: String,
    val arenaSources: Map<CardStyleInteractionStyle, ModelInitializationSource>,
    val arenas: Map<CardStyleInteractionStyle, ArenaSpecification>,
    val origins: List<CardStyleInteractionSeeds>,
) {
    val arena: ArenaSpecification
        get() =
            requireNotNull(arenas[CardStyleInteractionStyle.G3]) { "The G3 reference is required." }

    val configuration = arena.configurations.single()
    val signature =
        trainingSignature(
            "card-continuation-interaction-1:$sourceSha256:" +
                "$calibrationSha256:" +
                CardStyleInteractionStyle.entries.joinToString(":") {
                    "${it.name}=${requireNotNull(arenaSources[it]) { "Each arena source is required." }.sha256}:" +
                        requireNotNull(arenas[it]) { "Each continuation arena is required." }
                            .identity()
                }
        )

    fun controllers(style: CardStyleInteractionStyle, seat: Int): Map<Int, SimulationController> =
        requireNotNull(arenas[style]) { "The fixed continuation style must be declared." }
            .controllers(4, seat, ArenaSide.CANDIDATE, arena.candidate, List(4) { 0 })

    fun snapshot(artifacts: TrainingArtifacts) {
        artifacts.ensureFileSnapshot(source, sourceSha256, "study-source.json")
        artifacts.ensureFileSnapshot(calibration, calibrationSha256, "calibration.json")
        for (style in CardStyleInteractionStyle.entries) {
            val child = TrainingArtifacts(artifacts.directory.resolve(style.name))
            val origin = requireNotNull(arenaSources[style]) { "Every arena has a source." }
            val policy = requireNotNull(arenas[style]) { "Every arena has a policy." }
            child.ensureFileSnapshot(origin.path, origin.sha256, "arena-source.json")
            policy.snapshotModels(child)
            policy.snapshotOverlays(child)
        }
    }

    companion object {
        fun read(path: Path): CardStyleInteractionInput {
            val source = path.toRealPath()
            val sourceSha = PurchaseJson.hash(source)
            val node = PurchaseJson.read(source)
            PurchaseJson.fields(node, setOf("kind", "arenas", "calibration", "origins"))
            require(node["kind"]?.asText() == "card-continuation-interaction-1") {
                "Declare the explicit card-continuation-interaction-1 study."
            }
            val names = node.required("arenas")
            PurchaseJson.fields(names, CardStyleInteractionStyle.entries.map { it.name }.toSet())
            val subjects =
                CardStyleInteractionStyle.entries.associateWith { style ->
                    val file =
                        source.parent
                            .resolve(PurchaseJson.text(names[style.name], style.name))
                            .toRealPath()
                    ModelInitializationSource(file, PurchaseJson.hash(file))
                }
            val arenas = subjects.mapValues { (_, subject) ->
                PurchaseJson.fields(
                    PurchaseJson.read(subject.path),
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
                ArenaSpecification.read(subject.path)
            }
            val baseline =
                requireNotNull(arenas[CardStyleInteractionStyle.G3]) { "The G3 arena is required." }
            ExpansionPairedInput.validateEcology(baseline)
            val binding = wire(baseline.overlayMetadata())
            for ((style, variant) in arenas) {
                require(
                    variant.configurations == baseline.configurations &&
                        variant.purpose == baseline.purpose &&
                        variant.candidate == baseline.candidate &&
                        variant.incumbent == baseline.incumbent &&
                        variant.opponents == baseline.opponents
                ) {
                    "Only the own-seat joint head may vary."
                }
                val other = wire(variant.overlayMetadata())
                require(
                    other.fieldNames().asSequence().toSet() ==
                        binding.fieldNames().asSequence().toSet() &&
                        other["tokenOrder"] == binding["tokenOrder"] &&
                        other["expansionImitation"] == binding["expansionImitation"]
                ) {
                    "Every continuation must preserve the exact expansion/token ecology."
                }
                val joint = other.required("jointPolicy")
                for (role in listOf("version", "baseline", "sharedBundle", "incumbentBundle")) {
                    require(joint[role] == binding["jointPolicy"][role]) {
                        "All rivals, incumbent roles and the independent F0 prior must remain G3."
                    }
                }
                for (phase in listOf("card", "purchase")) {
                    val original = model(binding["jointPolicy"]["candidateBundle"][phase])
                    val proposed = model(joint["candidateBundle"][phase])
                    val originalWeights = original.remove("actorWeights")
                    val proposedWeights = proposed.remove("actorWeights")
                    require(
                        original == proposed &&
                            originalWeights.size() == 64 &&
                            proposedWeights.size() == 64
                    ) {
                        "Only the64 actor output weights may change; basis, value and prior stay exact."
                    }
                    require(
                        style == CardStyleInteractionStyle.G3 || proposedWeights != originalWeights
                    ) {
                        "Each calibrated style must actually alter both output heads."
                    }
                }
            }
            val subject = node.required("calibration")
            PurchaseJson.fields(subject, setOf("path", "sha256"))
            val calibration =
                source.parent
                    .resolve(PurchaseJson.text(subject["path"], "calibration path"))
                    .toRealPath()
            val calibrationSha = PurchaseJson.text(subject["sha256"], "calibration SHA")
            require(PurchaseJson.hash(calibration) == calibrationSha) {
                "Frozen calibration bytes changed."
            }
            validateCalibration(PurchaseJson.read(calibration), arenas)
            val rows = node.required("origins")
            require(rows.isArray && rows.size() == 1024) {
                "Exactly1024 original worlds are required."
            }
            val origins = rows.map { row ->
                PurchaseJson.fields(row, setOf("gameSeed", "future"))
                val future = row.required("future")
                require(future.isArray) { "Future streams must be an explicit array." }
                CardStyleInteractionSeeds(
                    PurchaseJson.integer(row["gameSeed"], "gameSeed"),
                    future.map {
                        require(it.isIntegralNumber && it.canConvertToLong()) {
                            "Future seeds must be exact signed integers."
                        }
                        it.longValue()
                    },
                )
            }
            require(origins.flatMap { it.future + it.gameSeed.toLong() }.distinct().size == 3072) {
                "All original/future values must be disjoint across the full fixed bank."
            }
            require(
                PurchaseJson.hash(source) == sourceSha &&
                    subjects.values.all { PurchaseJson.hash(it.path) == it.sha256 }
            ) {
                "Study or arena sources changed while reading."
            }
            return CardStyleInteractionInput(
                source,
                sourceSha,
                calibration,
                calibrationSha,
                subjects,
                arenas,
                origins,
            )
        }

        private fun model(subject: JsonNode): ObjectNode {
            require(!subject.isNull) { "Both learned G3 heads are required in every continuation." }
            val path = Path.of(PurchaseJson.text(subject["path"], "component path"))
            require(PurchaseJson.hash(path) == subject["sha256"].asText()) {
                "Raw actor bytes changed."
            }
            return PurchaseJson.read(path) as ObjectNode
        }

        private fun validateCalibration(
            value: JsonNode,
            arenas: Map<CardStyleInteractionStyle, ArenaSpecification>,
        ) {
            require(
                value["kind"]?.asText() == "card-continuation-calibration-1" &&
                    value["passed"]?.booleanValue() == true &&
                    value["actualFits"]?.intValue() == 0 &&
                    value["terminalLabelsDecoded"]?.booleanValue() == false
            ) {
                "Every style requires a completed outcome-free, zero-fit calibration receipt."
            }
            val baseline =
                requireNotNull(arenas[CardStyleInteractionStyle.G3]) {
                    "The exact G3 reference must be present."
                }
            require(
                value["originalBundleSha256"]?.asText() ==
                    wire(baseline.overlayMetadata())["jointPolicy"]["candidateBundle"]["sha256"]
                        .asText()
            ) {
                "Calibration must start from the exact study reference bundle."
            }
            val styles = value.required("styles")
            PurchaseJson.fields(
                styles,
                CardStyleInteractionStyle.entries.drop(1).map { it.name }.toSet(),
            )
            for (style in CardStyleInteractionStyle.entries.drop(1)) {
                val joint =
                    wire(
                        requireNotNull(arenas[style]) { "Each calibrated arena is required." }
                            .overlayMetadata()
                    )["jointPolicy"]
                val row = styles[style.name]
                require(
                    row["bundleSha256"]?.asText() == joint["candidateBundle"]["sha256"].asText()
                ) {
                    "The calibrated bundle must be the actual persistent own-seat policy."
                }
                for (phase in listOf("card", "purchase")) {
                    val proof = row[phase]
                    require(
                        proof["modelSha256"]?.asText() ==
                            joint["candidateBundle"][phase]["sha256"].asText() &&
                            proof["targetKl"]?.doubleValue() == .02 &&
                            proof["actualKl"]?.isNumber == true &&
                            abs(proof["actualKl"].doubleValue() - .02) <= 1e-6
                    ) {
                        "Each actual head requires its independently calibrated fixed KL subject."
                    }
                }
            }
        }

        private fun wire(value: Any): JsonNode = PurchaseJson.mapper.valueToTree(value)
    }
}
