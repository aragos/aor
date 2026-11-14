package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files

/** New execution identity over an unchanged, manifest-bound parameter origin. */
internal class CoordinatedWinProtocol
private constructor(
    val source: TokenPairedSubject,
    val record: JsonNode,
) {
    val space: Map<String, String> =
        listOf("scopeSha256", "originalBundleSha256", "projectionSha256").associateWith {
            CoordinatedEsWire.sha(record["parameterSpace"][it], "parameter $it")
        }
    val scopeSha256 = requireNotNull(space["scopeSha256"]) { "Original scope required." }
    val ecologySha256 =
        CoordinatedEsWire.sha(record["evaluation"]["ecologySha256"], "evaluation ecology")
    val warmStartSha256 =
        CoordinatedEsWire.sha(record["warmStart"]["identitySha256"], "warm identity")

    fun generation(number: Int): JsonNode {
        require(number in 1..16) { "Continuation generation must be in1..16." }
        return record["generations"][number - 1]
    }

    fun warm(subject: TokenPairedSubject): JsonNode {
        require(subject.sha256 == warmStartSha256) {
            "The exact admitted warm-start record is required."
        }
        subject.verify()
        val value = PurchaseJson.read(subject.path)
        PurchaseJson.fields(
            value,
            setOf(
                "kind",
                "parameterSpace",
                "originCenterSha256",
                "originBundleSha256",
                "originProtocolSha256",
                "originAdmissionSha256",
                "calibrationSha256",
                "coordinates",
            ),
        )
        require(
            value["kind"]?.asText() == "coordinated-es-warm-start-1" &&
                value["parameterSpace"] == ExpansionOnPolicyStore.wire(space) &&
                value["originCenterSha256"] == record["warmStart"]["centerSha256"] &&
                value["originBundleSha256"] == record["warmStart"]["bundleSha256"] &&
                value["originProtocolSha256"] == record["warmStart"]["protocolSha256"] &&
                value["originAdmissionSha256"] == record["warmStart"]["admissionSha256"] &&
                value["calibrationSha256"] == record["calibration"]["originCalibrationSha256"]
        ) {
            "Warm start must retain the complete parent center, bundle, admission and immutable calibration."
        }
        coordinates(value["coordinates"])
        return value
    }

    fun center(value: JsonNode, step: Int, warm: JsonNode) {
        PurchaseJson.fields(
            value,
            setOf("kind", "parameterSpace", "warmStartSha256", "step", "coordinates"),
        )
        require(
            value["kind"]?.asText() == "coordinated-es-continuation-center-1" &&
                value["parameterSpace"] == ExpansionOnPolicyStore.wire(space) &&
                value["warmStartSha256"]?.asText() == warmStartSha256 &&
                PurchaseJson.integer(value["step"], "local center step") == step &&
                step in 0..16 &&
                (step != 0 || value["coordinates"] == warm["coordinates"])
        ) {
            "Continuation consumes the immediate local center; step0 is the exact admitted parent mean."
        }
        coordinates(value["coordinates"])
    }

    fun directions(
        subject: TokenPairedSubject,
        number: Int,
        preparation: CoordinatedWinPreparation,
    ) {
        require(subject.sha256 == preparation.directionHashes[number]) {
            "The executable direction table must be the exact prospectively prepared generation artifact."
        }
        subject.verify()
        val value = PurchaseJson.read(subject.path)
        PurchaseJson.fields(value, setOf("kind", "scope", "generation", "vectors"))
        val vectors = PurchaseJson.array(value["vectors"], "direction vectors")
        require(
            value["kind"]?.asText() == "coordinated-es-directions-1" &&
                value["scope"] == ExpansionOnPolicyStore.wire(space) &&
                PurchaseJson.integer(value["generation"], "direction generation") == number &&
                vectors.size == 16 &&
                vectors.all { row ->
                    row.isArray &&
                        row.size() == 256 &&
                        row.all { it.isNumber && it.doubleValue().isFinite() }
                }
        ) {
            "All16 original prepared directions must retain their generation and parameter origin."
        }
    }

    fun predecessor(
        update: JsonNode,
        center: JsonNode,
        number: Int,
        preparation: CoordinatedWinPreparation,
    ) {
        require(
            number in 2..16 &&
                update["kind"]?.asText() == "coordinated-es-continuation-update-1" &&
                update["complete"]?.booleanValue() == true &&
                update["mathRecipe"] == ExpansionOnPolicyStore.wire(MATH_RECIPE) &&
                update["objective"]?.asText() == OBJECTIVE &&
                update["protocolSha256"]?.asText() == source.sha256 &&
                update["warmStartSha256"]?.asText() == warmStartSha256 &&
                update["center"] == center &&
                update["directionsSha256"]?.asText() == preparation.directionHashes[number - 1]
        ) {
            "The immediate complete win-only predecessor and its prepared directions must produce this center."
        }
    }

    companion object {
        const val OBJECTIVE = "AUTHORITATIVE_WIN_SHARE"
        val MATH_RECIPE: Map<String, Any> =
            mapOf(
                "kind" to "coordinated-normalized-antithetic-es-1",
                "dimensions" to 256,
                "directions" to 16,
                "worlds" to 256,
                "generations" to 16,
                "sigma" to 1.0,
                "stepRms" to 1.0,
                "centerRmsLimit" to 4.0,
                "minimumGradientRms" to 1e-12,
            )
        val RECIPE: Map<String, Any> =
            mapOf(
                "kind" to "coordinated-win-normalized-antithetic-1",
                "heads" to listOf("CARD", "PURCHASE", "EXPANSION", "TOKEN"),
                "dimensions" to 256,
                "coordinatesPerHead" to 64,
                "generations" to 16,
                "directions" to 16,
                "mutants" to 32,
                "worlds" to 256,
                "sigma" to 1.0,
                "stepRms" to 1.0,
                "maximumCenterRms" to 4.0,
                "minimumGradientRms" to 1e-12,
                "dtype" to "float64",
                "threads" to 1,
                "reward" to OBJECTIVE,
                "ranking" to false,
                "valueUpdates" to false,
                "mutantProjection" to false,
                "liveHeadMovement" to "DIAGNOSTIC_ONLY",
                "candidate" to "FINAL_MEAN_16_ONLY",
                "initialization" to "ADMITTED_PARENT_FINAL_COORDINATES",
                "parameterOrigin" to "UNCHANGED_ORIGINAL_PROJECTION",
                "radiusOrigin" to "ORIGINAL_ZERO",
            )
        val EVALUATION: Map<String, Any> =
            mapOf(
                "kind" to "coordinated-supported-mixed-1",
                "supportProfile" to "known-own-chaos-support-1",
                "supportRoles" to "ALL_SEATS_CP",
                "control" to "ORIGINAL_G3_WITH_SUPPORT1",
                "rivals" to listOf("E_OFF_T_OFF", "E_ON_T_OFF", "E_OFF_T_ON", "E_ON_T_ON"),
                "seat" to "GLOBAL_INDEX_MOD_4",
                "opponentIndices" to "(GLOBAL_INDEX_DIV_4_PLUS_SEAT)_MOD_4",
            )

        fun coordinates(node: JsonNode?) {
            val values =
                PurchaseJson.array(
                    requireNotNull(node) { "Center coordinates are required." },
                    "center coordinates",
                )
            require(
                values.size == 256 &&
                    values.all { it.isNumber && it.doubleValue().isFinite() } &&
                    CoordinatedEsWire.rootMeanSquare(values.map(JsonNode::doubleValue)) <=
                        4.0 + 2e-15
            ) {
                "All256 center coordinates must be finite and within the original-zero RMS4 radius."
            }
        }

        fun read(source: TokenPairedSubject): CoordinatedWinProtocol {
            source.verify()
            val bytes = Files.readAllBytes(source.path)
            require(
                bytes.size > 1 &&
                    bytes.last() == 10.toByte() &&
                    bytes[bytes.lastIndex - 1] != 10.toByte()
            ) {
                "The new protocol requires exactly one framing LF."
            }
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "recipe",
                    "parameterSpace",
                    "warmStart",
                    "calibration",
                    "evaluation",
                    "prefixGate",
                    "gameLimits",
                    "budget",
                    "scienceSeconds",
                    "auditSeconds",
                    "hosts",
                    "generations",
                    "screenBank",
                    "screenGate",
                    "historicalControls",
                    "automaticRetry",
                    "automaticSuccessor",
                ),
            )
            require(
                node["kind"]?.asText() == "coordinated-win-continuation-1" &&
                    node["recipe"] == ExpansionOnPolicyStore.wire(RECIPE) &&
                    node["evaluation"]["recipe"] == ExpansionOnPolicyStore.wire(EVALUATION) &&
                    node["recipe"]["kind"]?.asText() == "coordinated-win-normalized-antithetic-1" &&
                    node["recipe"]["reward"]?.asText() == OBJECTIVE &&
                    node["recipe"]["initialization"]?.asText() ==
                        "ADMITTED_PARENT_FINAL_COORDINATES" &&
                    node["recipe"]["parameterOrigin"]?.asText() ==
                        "UNCHANGED_ORIGINAL_PROJECTION" &&
                    node["recipe"]["radiusOrigin"]?.asText() == "ORIGINAL_ZERO" &&
                    PurchaseJson.integer(node["scienceSeconds"], "science seconds") == 86400 &&
                    PurchaseJson.integer(node["auditSeconds"], "audit seconds") == 900 &&
                    PurchaseJson.integer(
                        node["budget"]["maximumTerminalExecutions"],
                        "maximum executions",
                    ) == 141358 &&
                    PurchaseJson.integer(node["gameLimits"]["maximumSeconds"], "game seconds") ==
                        300 &&
                    PurchaseJson.integer(
                        node["gameLimits"]["maximumDecisions"],
                        "game decisions",
                    ) == 12000 &&
                    node["automaticRetry"]?.booleanValue() == false &&
                    node["automaticRetry"]?.isBoolean == true &&
                    node["automaticSuccessor"]?.booleanValue() == false &&
                    node["automaticSuccessor"]?.isBoolean == true
            ) {
                "Only the fixed bounded win-only warm continuation is admitted."
            }
            val rows = PurchaseJson.array(node["generations"], "generation banks")
            require(rows.size == 16) { "Exactly16 complete generation banks are required." }
            val streams =
                rows.flatMapIndexed { index, row ->
                    PurchaseJson.fields(row, setOf("generation", "worldBank", "directionSeed"))
                    val seed = PurchaseJson.integer(row["directionSeed"], "direction seed")
                    require(
                        PurchaseJson.integer(row["generation"], "generation") == index + 1 &&
                            seed > 0
                    ) {
                        "Generation order and positive prepared direction streams must remain exact."
                    }
                    CoordinatedEsBank.read(row["worldBank"], 256).seeds + seed
                } + CoordinatedEsBank.read(node["screenBank"], 2048).seeds
            require(streams.size == 6160 && streams.distinct().size == streams.size) {
                "Every declared continuation world and direction stream must be distinct."
            }
            return CoordinatedWinProtocol(source, node)
        }
    }
}
