package dev.aragos.aor.training

import java.nio.file.Path

/**
 * Compact pure receipt admission precedes final tensor decoding; no interim candidate is accepted.
 */
internal class CoordinatedEsAdmission
private constructor(
    val source: TokenPairedSubject,
    val subjects: Map<String, TokenPairedSubject>,
    val scope: CoordinatedEsScope,
    val calibration: CoordinatedEsCalibration,
    val finalBundle: CoordinatedEsBundle,
    val pins: Map<String, String>,
) {
    val protocol: TokenPairedSubject
        get() = requireNotNull(subjects["protocol"]) { "Protocol required." }

    val historical: TokenPairedSubject
        get() = requireNotNull(subjects["historical"]) { "Historical proof required." }

    fun verify() {
        pins.forEach { (path, hash) ->
            require(PurchaseJson.hash(Path.of(path)) == hash) {
                "Admitted final subject changed: $path."
            }
        }
    }

    companion object {
        private val SUBJECTS =
            setOf(
                "protocol",
                "originalBundle",
                "projection",
                "calibration",
                "center",
                "finalBundle",
                "generationAudit",
                "historical",
                "centerMaterialization",
            )

        fun read(source: TokenPairedSubject, arena: TokenPairedSubject): CoordinatedEsAdmission {
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
                        "validationEffectsUsedForSelection",
                    ),
            )
            require(
                node["kind"]?.asText() == "coordinated-es-final-admission-1" &&
                    node["complete"]?.booleanValue() == true &&
                    node["completedGenerations"]?.asInt() == 16 &&
                    node["validationEffectsUsedForSelection"]?.isBoolean == true &&
                    !node["validationEffectsUsedForSelection"].booleanValue()
            ) {
                "Only the complete fixed16-generation mean may enter the final screen."
            }
            val subjects = SUBJECTS.associateWith {
                TokenPairedSubject.read(node.required(it), source.path.parent)
            }
            fun subject(name: String) =
                requireNotNull(subjects[name]) { "$name final subject required." }
            val protocol = subject("protocol")
            val plan = CoordinatedEsWire.protocol(protocol)
            val scope = CoordinatedEsScope.read(arena, subject("originalBundle"))
            require(
                node["scopeSha256"]?.asText() == scope.sha256 &&
                    node["ecologySha256"]?.asText() == scope.ecologySha256
            ) {
                "Final admission must retain the actual complete original scope and ecology."
            }
            CoordinatedEsWire.original(plan, scope, subject("projection"))
            CoordinatedEsWire.historical(subject("historical"), protocol, scope)
            val calibration = CoordinatedEsCalibration.read(subject("calibration"), scope.original)
            require(
                calibration.record["protocolSha256"]?.asText() == protocol.sha256 &&
                    calibration.record["projectionSha256"]?.asText() == subject("projection").sha256
            ) {
                "Final role must retain the original sealed protocol calibration."
            }
            val expectedScope = CoordinatedEsWire.scope(scope, subject("projection"))
            val center = PurchaseJson.read(subject("center").path)
            CoordinatedEsWire.center(center, expectedScope, 16)
            val pins = buildMap {
                put(source.path.toString(), source.sha256)
                putAll(scope.inputPins())
                subjects.values.forEach { put(it.path.toString(), it.sha256) }
                putAll(
                    validateGenerations(
                        subject("generationAudit"),
                        protocol.sha256,
                        expectedScope,
                        scope.ecologySha256,
                        calibration.source.sha256,
                        subject("center"),
                    )
                )
            }
            val materialization = PurchaseJson.read(subject("centerMaterialization").path)
            PurchaseJson.fields(
                materialization,
                setOf(
                    "kind",
                    "protocolSha256",
                    "centerSha256",
                    "generation",
                    "projectionSha256",
                    "originalBundleSha256",
                    "calibrationSha256",
                    "bundleSha256",
                    "finalCandidate",
                ),
            )
            require(
                materialization["kind"]?.asText() == "coordinated-es-center-materialization-1" &&
                    materialization["protocolSha256"]?.asText() == protocol.sha256 &&
                    materialization["centerSha256"]?.asText() == subject("center").sha256 &&
                    materialization["generation"]?.asInt() == 16 &&
                    materialization["finalCandidate"]?.booleanValue() == true &&
                    materialization["projectionSha256"]?.asText() == subject("projection").sha256 &&
                    materialization["originalBundleSha256"]?.asText() ==
                        scope.original.source.sha256 &&
                    materialization["calibrationSha256"]?.asText() == calibration.source.sha256 &&
                    materialization["bundleSha256"]?.asText() == subject("finalBundle").sha256
            ) {
                "The final bundle must be the exact numerical materialization of the sole admitted mean16."
            }
            // Final tensors become readable only after every completed-generation and
            // materialization subject passed.
            val finalBundle = CoordinatedEsBundle.read(subject("finalBundle"), scope.dictionaries)
            finalBundle.models.validateFrozenValues(scope.original.models)
            val allPins =
                pins + finalBundle.subjects.values.associate { it.path.toString() to it.sha256 }
            return CoordinatedEsAdmission(
                    source,
                    subjects,
                    scope,
                    calibration,
                    finalBundle,
                    allPins,
                )
                .also { it.verify() }
        }

        internal fun validateGenerations(
            subject: TokenPairedSubject,
            protocolSha256: String,
            expectedScope: Map<String, String>,
            ecologySha256: String,
            calibrationSha256: String,
            finalCenter: TokenPairedSubject,
        ): Map<String, String> {
            val node = PurchaseJson.read(subject.path)
            PurchaseJson.fields(
                node,
                setOf(
                    "kind",
                    "complete",
                    "passed",
                    "protocolSha256",
                    "scopeSha256",
                    "ecologySha256",
                    "originalBundleSha256",
                    "projectionSha256",
                    "calibrationSha256",
                    "completedGenerations",
                    "finalCenterSha256",
                    "generations",
                ),
            )
            require(
                node["kind"]?.asText() == "coordinated-es-generation-audit-1" &&
                    node["complete"]?.booleanValue() == true &&
                    node["passed"]?.booleanValue() == true &&
                    node["protocolSha256"]?.asText() == protocolSha256 &&
                    node["ecologySha256"]?.asText() == ecologySha256 &&
                    node["calibrationSha256"]?.asText() == calibrationSha256 &&
                    node["completedGenerations"]?.asInt() == 16 &&
                    node["finalCenterSha256"]?.asText() == finalCenter.sha256 &&
                    expectedScope.all { (key, value) -> node[key]?.asText() == value }
            ) {
                "The pure whole-generation audit must bind all16 complete banks and the exact final center."
            }
            val rows = PurchaseJson.array(node["generations"], "completed generations")
            require(rows.size == 16) {
                "No missing, shortened or additional generation can enter the final screen."
            }
            val zero =
                mapOf(
                    "kind" to "coordinated-es-center-1",
                    "scope" to expectedScope,
                    "generation" to 0,
                    "coordinates" to List(256) { 0.0 },
                )
            var previousCenterSha256 = CpePairedDigest.json(zero)
            return buildMap {
                put(subject.path.toString(), subject.sha256)
                rows.forEachIndexed { index, row ->
                    PurchaseJson.fields(
                        row,
                        setOf(
                            "generation",
                            "inputCenterSha256",
                            "outputCenterSha256",
                            "outputCenter",
                            "update",
                            "populationSha256",
                            "controls",
                            "mutants",
                            "reproductions",
                        ),
                    )
                    require(
                        row["generation"]?.asInt() == index + 1 &&
                            row["inputCenterSha256"]?.asText() == previousCenterSha256 &&
                            row["controls"]?.asInt() == 256 &&
                            row["mutants"]?.asInt() == 8192 &&
                            row["reproductions"]?.asInt() == 2
                    ) {
                        "Each generation must consume its immediate mean and retain all256 controls/8192 mutants/two reproductions."
                    }
                    val updateSubject = TokenPairedSubject.read(row["update"], subject.path.parent)
                    val outputSubject =
                        TokenPairedSubject.read(row["outputCenter"], subject.path.parent)
                    require(row["outputCenterSha256"]?.asText() == outputSubject.sha256) {
                        "The output center raw subject changed."
                    }
                    val update = PurchaseJson.read(updateSubject.path)
                    val output = PurchaseJson.read(outputSubject.path)
                    CoordinatedEsWire.center(output, expectedScope, index + 1)
                    require(
                        update["kind"]?.asText() == "coordinated-es-update-1" &&
                            update["complete"]?.booleanValue() == true &&
                            update["protocolSha256"]?.asText() == protocolSha256 &&
                            update["previousCenterSha256"]?.asText() == previousCenterSha256 &&
                            update["populationSha256"]?.asText() ==
                                CoordinatedEsWire.sha(row["populationSha256"], "population") &&
                            update["center"] == output
                    ) {
                        "The complete numerical update must produce this exact next center from the original population."
                    }
                    put(updateSubject.path.toString(), updateSubject.sha256)
                    put(outputSubject.path.toString(), outputSubject.sha256)
                    previousCenterSha256 = outputSubject.sha256
                }
                require(previousCenterSha256 == finalCenter.sha256) {
                    "Only the final mean16 subject is eligible."
                }
            }
        }
    }
}
