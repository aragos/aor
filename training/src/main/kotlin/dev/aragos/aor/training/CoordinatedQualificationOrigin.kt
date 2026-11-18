package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * A root-pinned zero-game proof made by the frozen83 runtime owns its historical admission. This
 * reader rehashes that exact local closure before decoding under84, then joins the stable policy
 * scope. It never reinterprets the old runtime-bound ecology as the new execution identity.
 */
internal class CoordinatedQualificationOrigin
private constructor(
    val source: TokenPairedSubject,
    val proof: TokenPairedSubject,
    val scope: CoordinatedEsScope,
    val originEcologySha256: String,
    val calibration: CoordinatedEsCalibration,
    val finalBundle: CoordinatedEsBundle,
    val historical: TokenPairedSubject,
    val pins: Map<String, String>,
) {
    companion object {
        const val VERSION = "coordinated-qualification-origin-proof-1"
        const val TRAINING_SHA = "f35e48eb03c93b8e159c94de5d1a7ca3d29fff04bad999f97ed865093f2adf11"
        val RUNTIME_NAMES =
            setOf(
                "ai-jvm.jar",
                "annotations-13.0.jar",
                "core-jvm.jar",
                "jackson-annotations-2.22.jar",
                "jackson-core-2.22.4.jar",
                "jackson-databind-2.22.4.jar",
                "kotlin-stdlib-2.4.21.jar",
                "kotlinx-collections-immutable-jvm-0.5.2.jar",
                "training.jar",
            )
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

        fun read(
            proof: TokenPairedSubject,
            source: TokenPairedSubject,
            arena: TokenPairedSubject,
            host: QualificationHost,
        ): CoordinatedQualificationOrigin {
            proof.verify()
            val value = PurchaseJson.read(proof.path)
            PurchaseJson.fields(
                value,
                setOf(
                    "kind",
                    "host",
                    "passed",
                    "complete",
                    "admission",
                    "arena",
                    "runtime",
                    "scopeIdentity",
                    "scopeSha256",
                    "ecologySha256",
                    "subjects",
                    "inputPins",
                    "execution",
                ),
            )
            require(
                value["kind"]?.asText() == VERSION &&
                    value["host"]?.asText() == host.name &&
                    value["passed"]?.booleanValue() == true &&
                    value["complete"]?.booleanValue() == true &&
                    value["execution"] ==
                        ExpansionOnPolicyStore.wire(
                            mapOf("newGames" to 0, "policyCalls" to 0, "actualFits" to 0)
                        )
            ) {
                "The exact host's successful frozen-runtime zero-game origin proof is required."
            }
            val pins = closure(value.required("inputPins"))
            fun subject(node: JsonNode): TokenPairedSubject {
                val result = TokenPairedSubject.read(node, proof.path.parent)
                require(pins[result.path.toString()] == result.sha256) {
                    "Every origin proof subject must belong to its rehashed immutable closure."
                }
                return result
            }
            val originalAdmission = subject(value.required("admission"))
            val originalArena = subject(value.required("arena"))
            require(
                originalAdmission.path == source.path &&
                    originalAdmission.sha256 == source.sha256 &&
                    originalArena.path == arena.path &&
                    originalArena.sha256 == arena.sha256
            ) {
                "Origin proof must bind the exact retained local admission and arena subjects."
            }
            PurchaseJson.fields(value.required("runtime"), RUNTIME_NAMES)
            val runtime = RUNTIME_NAMES.associateWith { subject(value["runtime"].required(it)) }
            require(
                runtime["training.jar"]?.sha256 == TRAINING_SHA &&
                    runtime.values.map { it.path }.distinct().size == 9
            ) {
                "Origin validation must use the exact frozen83 f35 runtime and nine distinct JARs."
            }
            val admitted = PurchaseJson.read(source.path)
            PurchaseJson.fields(value.required("subjects"), SUBJECTS)
            val subjects = SUBJECTS.associateWith { key ->
                val declared = subject(value["subjects"].required(key))
                val original = TokenPairedSubject.read(admitted.required(key), source.path.parent)
                require(declared.path == original.path && declared.sha256 == original.sha256) {
                    "The frozen origin proof cannot substitute an admitted $key subject."
                }
                declared
            }
            fun named(key: String) =
                requireNotNull(subjects[key]) { "$key origin subject required." }
            val oldEcology = CoordinatedEsWire.sha(value["ecologySha256"], "origin ecology")
            val oldScope = CoordinatedEsWire.sha(value["scopeSha256"], "origin scope")
            require(
                admitted["scopeSha256"]?.asText() == oldScope &&
                    admitted["ecologySha256"]?.asText() == oldEcology &&
                    CpePairedDigest.json(value.required("scopeIdentity")) == oldScope
            ) {
                "The original admission's stable scope and runtime ecology must remain distinct and exact."
            }
            // All raw closure bytes passed before any current-runtime model decode.
            val scope = CoordinatedEsScope.read(arena, named("originalBundle"))
            require(
                scope.sha256 == oldScope &&
                    ExpansionOnPolicyStore.wire(scope.identity) == value["scopeIdentity"]
            ) {
                "The new runtime must preserve the complete original policy/configuration scope."
            }
            val protocol = named("protocol")
            val plan = CoordinatedEsWire.protocol(protocol)
            require(
                plan["original"]["scopeSha256"]?.asText() == oldScope &&
                    plan["original"]["ecologySha256"]?.asText() == oldEcology &&
                    plan["original"]["bundleSha256"]?.asText() == scope.original.source.sha256
            ) {
                "The frozen protocol must retain its original scope, ecology and model bundle."
            }
            val calibration = CoordinatedEsCalibration.read(named("calibration"), scope.original)
            calibration.version.validateProtocol(plan)
            require(
                calibration.record["protocolSha256"]?.asText() == protocol.sha256 &&
                    calibration.record["projectionSha256"]?.asText() == named("projection").sha256
            ) {
                "The qualification role must retain the exact original sealed calibration."
            }
            val finalBundle = CoordinatedEsBundle.read(named("finalBundle"), scope.dictionaries)
            finalBundle.models.validateFrozenValues(scope.original.models)
            val decoded =
                scope.inputPins() +
                    finalBundle.subjects.values.associate { it.path.toString() to it.sha256 }
            require(decoded.all { (path, hash) -> pins[path] == hash }) {
                "Current role decoding cannot introduce a subject outside the frozen origin closure."
            }
            return CoordinatedQualificationOrigin(
                source,
                proof,
                scope,
                oldEcology,
                calibration,
                finalBundle,
                named("historical"),
                pins + (proof.path.toString() to proof.sha256),
            )
        }

        private fun closure(node: JsonNode): Map<String, String> {
            require(node.isObject && node.size() > 0) { "The full origin closure is required." }
            return node.fields().asSequence().associate { (name, value) ->
                val path = Path.of(name)
                val hash = CoordinatedEsWire.sha(value, "origin closure hash")
                require(
                    path.isAbsolute &&
                        path.toRealPath().toString() == name &&
                        PurchaseJson.hash(path) == hash
                ) {
                    "An origin closure file changed or moved from its exact retained local path: $name."
                }
                name to hash
            }
        }
    }
}
