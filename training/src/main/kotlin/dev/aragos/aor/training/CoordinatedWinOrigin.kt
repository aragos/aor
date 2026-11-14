package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/**
 * A protocol-pinned frozen-runtime proof admits its host-local raw closure before current decoding.
 */
internal class CoordinatedWinOrigin
private constructor(
    val proof: TokenPairedSubject,
    val admission: TokenPairedSubject,
    val scope: CoordinatedEsScope,
    val subjects: Map<String, TokenPairedSubject>,
    val originEcologySha256: String,
    val pins: Map<String, String>,
) {
    fun named(name: String): TokenPairedSubject =
        requireNotNull(subjects[name]) { "$name origin subject required." }

    fun validateWarm(warm: JsonNode) {
        require(PurchaseJson.read(named("center").path)["coordinates"] == warm["coordinates"]) {
            "The warm coordinate vector must equal the complete admitted parent final mean."
        }
    }

    companion object {
        fun read(
            proof: TokenPairedSubject,
            protocol: CoordinatedWinProtocol,
            host: QualificationHost,
        ): CoordinatedWinOrigin {
            require(
                proof.sha256 == protocol.record["warmStart"]["proofSha256"][host.name].asText()
            ) {
                "Only this protocol's exact host-local frozen-runtime proof may admit parent tensors."
            }
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
                value["kind"]?.asText() in
                    setOf(
                        "coordinated-qualification-origin-proof-1",
                        "coordinated-win-origin-proof-1",
                    ) &&
                    value["host"]?.asText() == host.name &&
                    value["passed"]?.booleanValue() == true &&
                    value["complete"]?.booleanValue() == true &&
                    value["execution"] ==
                        ExpansionOnPolicyStore.wire(
                            mapOf("newGames" to 0, "policyCalls" to 0, "actualFits" to 0)
                        )
            ) {
                "A passed, zero-execution proof from the exact retained parent runtime is required."
            }
            val declaredPins = value.required("inputPins")
            require(declaredPins.isObject && declaredPins.size() > 0) {
                "The complete parent input closure is required."
            }
            val pins =
                declaredPins.fields().asSequence().associate { (name, hashNode) ->
                    val path = Path.of(name)
                    val hash = CoordinatedEsWire.sha(hashNode, "origin input")
                    require(
                        path.isAbsolute &&
                            path.toRealPath().toString() == name &&
                            PurchaseJson.hash(path) == hash
                    ) {
                        "An origin subject changed or moved from its exact retained path: $name."
                    }
                    name to hash
                }
            fun subject(node: JsonNode, base: Path = proof.path.parent): TokenPairedSubject {
                val result = TokenPairedSubject.read(node, base)
                require(pins[result.path.toString()] == result.sha256) {
                    "Every origin subject must belong to its rehashed raw closure."
                }
                return result
            }
            PurchaseJson.fields(
                value.required("runtime"),
                CoordinatedQualificationOrigin.RUNTIME_NAMES,
            )
            val runtime =
                CoordinatedQualificationOrigin.RUNTIME_NAMES.map {
                    subject(value["runtime"].required(it))
                }
            require(runtime.map { it.path }.distinct().size == 9) {
                "The root-pinned original runtime requires nine distinct JAR subjects."
            }
            val admission = subject(value.required("admission"))
            val arenaSource = subject(value.required("arena"))
            require(admission.sha256 == protocol.record["warmStart"]["admissionSha256"].asText()) {
                "Origin proof must admit the protocol's complete parent publication."
            }
            val admitted = PurchaseJson.read(admission.path)
            require(
                admitted["complete"]?.booleanValue() == true &&
                    admitted["completedGenerations"]?.asInt() == 16 &&
                    admitted["kind"]?.asText() in
                        setOf(
                            "coordinated-es-final-admission-1",
                            "coordinated-win-final-admission-1",
                        )
            ) {
                "Warm continuation requires a complete admitted parent final16 publication."
            }
            val names =
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
            require(
                value["subjects"]?.isObject == true && names.all { value["subjects"].has(it) }
            ) {
                "The origin proof must retain every parent policy and lineage subject."
            }
            val subjects =
                value["subjects"].fields().asSequence().associate { (name, node) ->
                    val expected = subject(admitted.required(name), admission.path.parent)
                    val actual = subject(node)
                    require(actual.path == expected.path && actual.sha256 == expected.sha256) {
                        "The original admitted $name cannot be substituted by the current runtime."
                    }
                    name to actual
                }
            fun named(name: String) =
                requireNotNull(subjects[name]) { "$name origin subject required." }
            val joins =
                mapOf(
                    "protocol" to protocol.record["warmStart"]["protocolSha256"],
                    "center" to protocol.record["warmStart"]["centerSha256"],
                    "finalBundle" to protocol.record["warmStart"]["bundleSha256"],
                    "centerMaterialization" to
                        protocol.record["warmStart"]["materializationSha256"],
                    "originalBundle" to protocol.record["parameterSpace"]["originalBundleSha256"],
                    "projection" to protocol.record["parameterSpace"]["projectionSha256"],
                    "calibration" to protocol.record["calibration"]["originCalibrationSha256"],
                )
            require(
                joins.all { (name, hash) -> named(name).sha256 == hash.asText() } &&
                    value["scopeSha256"]?.asText() == protocol.scopeSha256 &&
                    admitted["scopeSha256"] == value["scopeSha256"] &&
                    admitted["ecologySha256"] == value["ecologySha256"] &&
                    CpePairedDigest.json(value.required("scopeIdentity")) == protocol.scopeSha256
            ) {
                "Parent policy, original parameter scope, calibration and raw provenance must join exactly."
            }
            val descriptor = PurchaseJson.read(named("originalBundle").path)
            require(
                CoordinatedEsHead.entries.all { head ->
                    descriptor["heads"]?.get(head.name)?.get("sha256") ==
                        protocol.record["parameterSpace"]["heads"][head.name]["actorSha256"] &&
                        descriptor["heads"]?.get(head.name)?.get("dictionarySha256") ==
                            protocol.record["parameterSpace"]["heads"][head.name][
                                "dictionarySha256"]
                }
            ) {
                "All original head subjects must match the manifest before current tensor decoding."
            }
            // Complete original raw closure and role joins passed before any model reader is
            // called.
            val arena = ArenaSpecification.read(arenaSource.path)
            val original =
                CoordinatedEsBundle.read(
                    named("originalBundle"),
                    CoordinatedEsBundle.dictionaries(arena.configurations.single()),
                )
            val arenaRecord = PurchaseJson.read(arenaSource.path)
            val baselinePath =
                arenaSource.path.parent
                    .resolve(
                        PurchaseJson.text(arenaRecord["jointPolicy"]["baseline"], "F0 baseline")
                    )
                    .toRealPath()
            require(pins[baselinePath.toString()] == PurchaseJson.hash(baselinePath)) {
                "The unchanged F0 baseline must belong to the admitted closure."
            }
            val baseline =
                PurchaseBaselineInput(
                    baselinePath,
                    PurchaseJson.hash(baselinePath),
                    TrainingArtifacts.readModel(baselinePath),
                )
            val scope = CoordinatedEsScope(arenaSource, arena, original, baseline)
            require(
                scope.sha256 == protocol.scopeSha256 &&
                    ExpansionOnPolicyStore.wire(scope.identity) == value["scopeIdentity"] &&
                    scope.inputPins().all { (path, hash) -> pins[path] == hash }
            ) {
                "Current decoding must preserve the complete admitted original policy/configuration scope."
            }
            return CoordinatedWinOrigin(
                proof,
                admission,
                scope,
                subjects,
                CoordinatedEsWire.sha(value["ecologySha256"], "parent ecology"),
                pins + (proof.path.toString() to proof.sha256),
            )
        }
    }
}
