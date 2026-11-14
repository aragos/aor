package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Portable final16 lineage is checked before host-local origin admission or actor decoding. */
internal class CoordinatedWinAdmission
private constructor(
    val source: TokenPairedSubject,
    val protocol: CoordinatedWinProtocol,
    val subjects: Map<String, TokenPairedSubject>,
    val origin: CoordinatedWinOrigin,
    val inherited: CoordinatedWinCalibration,
    val startBundle: CoordinatedEsBundle,
    val finalBundle: CoordinatedEsBundle,
    val pins: Map<String, String>,
) {
    fun named(key: String) = requireNotNull(subjects[key]) { "$key final subject required." }

    val bindings: Map<String, String> =
        listOf(
                "finalBundle",
                "startBundle",
                "originalBundle",
                "projection",
                "warmStart",
                "inheritedCalibration",
                "calibration",
                "center",
            )
            .associate { "$it" + "Sha256" to named(it).sha256 }

    fun verify() {
        pins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "An admitted final subject changed: $path."
            }
        }
    }

    companion object {
        val SUBJECTS =
            setOf(
                "protocol",
                "originalBundle",
                "projection",
                "warmStart",
                "startBundle",
                "inheritedCalibration",
                "calibration",
                "numericalPreparation",
                "center",
                "finalBundle",
                "centerMaterialization",
                "generationAudit",
                "historical",
            )

        fun read(
            source: TokenPairedSubject,
            proof: TokenPairedSubject,
            host: QualificationHost,
        ): CoordinatedWinAdmission {
            source.verify()
            val node = PurchaseJson.read(source.path)
            PurchaseJson.fields(
                node,
                SUBJECTS +
                    setOf(
                        "kind",
                        "complete",
                        "completedGenerations",
                        "scopeSha256",
                        "ecologySha256",
                        "warmStartSha256",
                        "validationEffectsUsedForSelection",
                    ),
            )
            val subjects = SUBJECTS.associateWith {
                TokenPairedSubject.read(node.required(it), source.path.parent)
            }
            fun named(key: String) =
                requireNotNull(subjects[key]) { "$key final subject required." }
            val protocol = CoordinatedWinProtocol.read(named("protocol"))
            require(
                node["kind"]?.asText() == "coordinated-win-final-admission-1" &&
                    node["complete"]?.booleanValue() == true &&
                    PurchaseJson.integer(node["completedGenerations"], "completed generations") ==
                        16 &&
                    node["scopeSha256"]?.asText() == protocol.scopeSha256 &&
                    node["ecologySha256"]?.asText() == protocol.ecologySha256 &&
                    node["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                    node["validationEffectsUsedForSelection"]?.isBoolean == true &&
                    !node["validationEffectsUsedForSelection"].booleanValue()
            ) {
                "Only the single complete fixed final16 continuation can enter the screen."
            }
            val expected =
                mapOf(
                    "originalBundle" to protocol.space["originalBundleSha256"],
                    "projection" to protocol.space["projectionSha256"],
                    "startBundle" to protocol.record["warmStart"]["bundleSha256"].asText(),
                    "calibration" to
                        protocol.record["calibration"]["originCalibrationSha256"].asText(),
                )
            require(expected.all { (key, hash) -> named(key).sha256 == hash }) {
                "Final publication must retain every original and warm parent raw subject."
            }
            val warm = protocol.warm(named("warmStart"))
            val preparation =
                CoordinatedWinPreparation.read(named("numericalPreparation"), protocol)
            val prepared = PurchaseJson.read(preparation.source.path)
            val artifactJoins =
                mapOf(
                    "warm-start.json" to "warmStart",
                    "projection.json" to "projection",
                    "warm-bundle/bundle.json" to "startBundle",
                    "calibration/inherited-calibration.json" to "inheritedCalibration",
                )
            require(
                artifactJoins.all { (file, key) ->
                    prepared["artifacts"]?.get(file)?.asText() == named(key).sha256
                }
            ) {
                "The complete final lineage must retain the prepared warm bundle, projection and inherited envelope."
            }
            val audit = PurchaseJson.read(named("generationAudit").path)
            require(
                audit["kind"]?.asText() == "coordinated-win-generation-audit-1" &&
                    audit["passed"]?.booleanValue() == true &&
                    audit["complete"]?.booleanValue() == true &&
                    audit["completedGenerations"]?.asInt() == 16 &&
                    audit["protocolSha256"]?.asText() == protocol.source.sha256 &&
                    audit["scopeSha256"]?.asText() == protocol.scopeSha256 &&
                    audit["ecologySha256"]?.asText() == protocol.ecologySha256 &&
                    audit["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                    audit["objective"]?.asText() == CoordinatedWinProtocol.OBJECTIVE &&
                    listOf(
                            "originalBundle",
                            "projection",
                            "inheritedCalibration",
                            "calibration",
                            "numericalPreparation",
                        )
                        .all {
                            audit["${it}Sha256"]?.asText() == named(it).sha256
                        }
            ) {
                "The complete generation audit must bind this exact parameter origin, supported ecology and inherited selector."
            }
            val rows = PurchaseJson.array(audit["generations"], "final generation lineage")
            require(rows.size == 16) { "Every one of the sixteen complete updates is required." }
            val lineagePins = linkedMapOf<String, String>()
            var previous =
                CoordinatedEsWire.sha(
                    prepared["artifacts"]?.get("initial-center.json"),
                    "prepared initial center",
                )
            rows.forEachIndexed { index, row ->
                val number = index + 1
                val updateSubject =
                    TokenPairedSubject.read(
                        row.required("update"),
                        named("generationAudit").path.parent,
                    )
                val centerSubject =
                    TokenPairedSubject.read(
                        row.required("outputCenter"),
                        named("generationAudit").path.parent,
                    )
                val update = PurchaseJson.read(updateSubject.path)
                val center = PurchaseJson.read(centerSubject.path)
                protocol.center(center, number, warm)
                require(
                    PurchaseJson.integer(row["generation"], "lineage generation") == number &&
                        row["inputCenterSha256"]?.asText() == previous &&
                        row["outputCenterSha256"]?.asText() == centerSubject.sha256 &&
                        row["controls"]?.asInt() == 256 &&
                        row["mutants"]?.asInt() == 8192 &&
                        row["reproductions"]?.asInt() == 2 &&
                        update["kind"]?.asText() == "coordinated-es-continuation-update-1" &&
                        update["complete"]?.booleanValue() == true &&
                        update["mathRecipe"] ==
                            ExpansionOnPolicyStore.wire(CoordinatedWinProtocol.MATH_RECIPE) &&
                        update["objective"]?.asText() == CoordinatedWinProtocol.OBJECTIVE &&
                        update["protocolSha256"]?.asText() == protocol.source.sha256 &&
                        update["warmStartSha256"]?.asText() == protocol.warmStartSha256 &&
                        update["previousCenterSha256"]?.asText() == previous &&
                        update["directionsSha256"]?.asText() ==
                            preparation.directionHashes[number] &&
                        update["populationSha256"] == row["populationSha256"] &&
                        update["center"] == center
                ) {
                    "Each complete update must consume its immediate center and exact prepared directions and produce the retained next mean."
                }
                CoordinatedEsWire.sha(row["populationSha256"], "completed population")
                lineagePins[updateSubject.path.toString()] = updateSubject.sha256
                lineagePins[centerSubject.path.toString()] = centerSubject.sha256
                previous = centerSubject.sha256
            }
            protocol.center(PurchaseJson.read(named("center").path), 16, warm)
            require(
                previous == named("center").sha256 &&
                    audit["finalCenterSha256"]?.asText() == previous
            ) {
                "The screen must use the final sixteenth mean, never an earlier center or mutant."
            }
            val materialized = PurchaseJson.read(named("centerMaterialization").path)
            require(
                materialized ==
                    ExpansionOnPolicyStore.wire(
                        mapOf(
                            "kind" to "coordinated-es-continuation-center-materialization-1",
                            "protocolSha256" to protocol.source.sha256,
                            "centerSha256" to named("center").sha256,
                            "step" to 16,
                            "warmStartSha256" to protocol.warmStartSha256,
                            "projectionSha256" to named("projection").sha256,
                            "originalBundleSha256" to named("originalBundle").sha256,
                            "inheritedCalibrationSha256" to named("inheritedCalibration").sha256,
                            "bundleSha256" to named("finalBundle").sha256,
                            "finalCandidate" to true,
                        )
                    )
            ) {
                "The final four-head bundle must be the exact final16 coordinate materialization."
            }
            CoordinatedWinHistorical.validate(named("historical"), protocol)
            val componentPins =
                listOf("originalBundle", "startBundle", "finalBundle")
                    .flatMap { key ->
                        val descriptor = PurchaseJson.read(named(key).path)
                        require(descriptor["kind"]?.asText() == CoordinatedEsBundle.VERSION) {
                            "Every final role requires a complete typed bundle."
                        }
                        PurchaseJson.fields(
                            descriptor.required("heads"),
                            CoordinatedEsHead.entries.map { it.name }.toSet(),
                        )
                        CoordinatedEsHead.entries.map { head ->
                            val component = descriptor["heads"].required(head.name)
                            val originalHead = protocol.record["parameterSpace"]["heads"][head.name]
                            require(
                                component["dictionarySha256"] == originalHead["dictionarySha256"] &&
                                    (key != "originalBundle" ||
                                        component["sha256"] == originalHead["actorSha256"])
                            ) {
                                "Portable role descriptors must retain their exact original dictionaries and original-head identities."
                            }
                            TokenPairedSubject.read(
                                PurchaseJson.mapper.createObjectNode().also {
                                    it.set<JsonNode>("path", component.required("path"))
                                    it.set<JsonNode>("sha256", component.required("sha256"))
                                },
                                named(key).path.parent,
                            )
                        }
                    }
                    .associate { it.path.toString() to it.sha256 }
            // All portable lineage receipts passed before the first current-runtime actor decode.
            val origin = CoordinatedWinOrigin.read(proof, protocol, host)
            origin.validateWarm(warm)
            val inherited =
                CoordinatedWinCalibration.read(
                    named("inheritedCalibration"),
                    protocol,
                    origin.scope.original,
                )
            require(inherited.calibration.source.sha256 == named("calibration").sha256) {
                "The raw portable seal must equal the unchanged inherited seal."
            }
            val start = CoordinatedEsBundle.read(named("startBundle"), origin.scope.dictionaries)
            val final = CoordinatedEsBundle.read(named("finalBundle"), origin.scope.dictionaries)
            listOf(start, final).forEach {
                it.models.validateFrozenValues(origin.scope.original.models)
            }
            val pins =
                origin.pins +
                    inherited.pins +
                    preparation.pins +
                    lineagePins +
                    componentPins +
                    subjects.values.associate { it.path.toString() to it.sha256 } +
                    (source.path.toString() to source.sha256) +
                    (start.subjects.values + final.subjects.values).associate {
                        it.path.toString() to it.sha256
                    }
            return CoordinatedWinAdmission(
                    source,
                    protocol,
                    subjects,
                    origin,
                    inherited,
                    start,
                    final,
                    pins,
                )
                .also { it.verify() }
        }
    }
}
