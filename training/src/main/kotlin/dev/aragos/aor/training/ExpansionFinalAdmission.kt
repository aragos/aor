package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Copied evidence keeps its original absolute subject separate from its current readable path. */
internal data class ExpansionScreenSubject(
    val path: Path,
    val sha256: String,
    val originalPath: String,
) {
    fun read(): JsonNode {
        verify()
        return PurchaseJson.read(path).also { verify() }
    }

    fun verify() {
        require(PurchaseJson.hash(path) == sha256) { "Frozen screen subject changed: $path." }
    }

    fun metadata(): Map<String, String> =
        mapOf("path" to path.toString(), "sha256" to sha256, "originalPath" to originalPath)

    companion object {
        fun read(source: Path, node: JsonNode): ExpansionScreenSubject {
            PurchaseJson.fields(node, setOf("path", "sha256"), setOf("originalPath"))
            val declared =
                source.parent
                    .resolve(PurchaseJson.text(node["path"], "subject path"))
                    .toAbsolutePath()
                    .normalize()
            val path = declared.toRealPath()
            val hash = PurchaseJson.text(node["sha256"], "subject hash")
            val original =
                node["originalPath"]?.let { PurchaseJson.text(it, "originalPath") }
                    ?: declared.toString()
            require(hash.matches(Regex("[0-9a-f]{64}")) && Path.of(original).isAbsolute) {
                "A frozen subject requires its exact SHA and original absolute path."
            }
            return ExpansionScreenSubject(path, hash, original).also { it.verify() }
        }
    }
}

/** Consumes the completed scientific audit; never selects among training checkpoints. */
internal class ExpansionFinalAdmission
private constructor(
    val source: ExpansionScreenSubject,
    val subjects: Map<String, ExpansionScreenSubject>,
) {
    fun subject(name: String): ExpansionScreenSubject =
        requireNotNull(subjects[name]) { "Missing admission subject $name." }

    val finalPolicy
        get() = subject("finalPolicy")

    val initialPolicy
        get() = subject("initialPolicy")

    val prepared
        get() = subject("prepared").read()

    fun verify() {
        source.verify()
        subjects.values.forEach(ExpansionScreenSubject::verify)
    }

    fun validateEcology(arena: ArenaSpecification) {
        val prepared = prepared
        val binding = PurchaseJson.mapper.valueToTree<JsonNode>(arena.overlayMetadata())
        val old = prepared.required("policyBindings")
        require(
            prepared["configuration"]?.asText() == arena.configurations.single().id &&
                prepared["frozenCardPurchaseBundleSha256"]?.asText() ==
                    binding["jointPolicy"]["sharedBundle"]["sha256"].asText() &&
                prepared["referenceExpansionModelSha256"]?.asText() ==
                    binding["expansionImitation"]["candidateModel"]["sha256"].asText() &&
                old["jointPolicy"]["baseline"]["sha256"] ==
                    binding["jointPolicy"]["baseline"]["sha256"] &&
                old["tokenOrder"] == binding["tokenOrder"]
        ) {
            "Screen G3/F0/E/T/configuration must equal the original learning ecology."
        }
        val originalArena = subject("originalArena").read()
        val ordinary = subject("ordinary")
        val originalDirectory = Path.of(subject("originalArena").originalPath).parent
        require(
            originalDirectory.resolve(originalArena["candidate"].asText()).normalize().toString() ==
                ordinary.originalPath &&
                TrainingArtifacts.readModel(ordinary.path) == arena.candidate
        ) {
            "The screen requires the exact original raw ordinary model, not embedded E/T weights."
        }
    }

    companion object {
        const val VERSION = "expansion-final-admission-1"
        private val names =
            setOf(
                "terminalAudit",
                "learningAudit",
                "learningProtocol",
                "prepared",
                "finalPolicy",
                "initialPolicy",
                "finalReceipt",
                "finalIdentity",
                "finalCheckpoint",
                "originalArena",
                "ordinary",
            )

        fun read(source: ExpansionScreenSubject): ExpansionFinalAdmission {
            val node = source.read()
            PurchaseJson.fields(node, setOf("kind", "subjects"))
            require(node["kind"]?.asText() == VERSION) {
                "Explicit final expansion admission is required."
            }
            val raw = node.required("subjects")
            PurchaseJson.fields(raw, names)
            val subjects = names.associateWith {
                ExpansionScreenSubject.read(source.path, raw.required(it))
            }
            val admission = ExpansionFinalAdmission(source, subjects)
            val terminal = admission.subject("terminalAudit").read()
            val learning = admission.subject("learningAudit").read()
            val protocol = admission.subject("learningProtocol").read()
            require(
                terminal["kind"]?.asText() == "expansion-on-policy-terminal-audit-1" &&
                    terminal["passed"]?.booleanValue() == true &&
                    terminal["status"]?.asText() == "completed" &&
                    terminal["allOwnedProcessesExited"]?.booleanValue() == true &&
                    terminal["clockGuardStopped"]?.booleanValue() == true &&
                    terminal["completedBatches"]?.intValue() == 64 &&
                    terminal["eligibleFinalActor"]?.booleanValue() == true &&
                    terminal["protocolSha256"]?.asText() ==
                        admission.subject("learningProtocol").sha256 &&
                    terminal["learningAuditSha256"]?.asText() ==
                        admission.subject("learningAudit").sha256 &&
                    terminal["learningAudit"] == learning
            ) {
                "Only the completed, cleaned, audited batch-64 learning attempt is eligible."
            }
            require(
                learning["kind"]?.asText() == "expansion-on-policy-learning-audit-1" &&
                    learning["passed"]?.booleanValue() == true &&
                    learning["complete"]?.booleanValue() == true &&
                    learning["completedBatches"]?.intValue() == 64 &&
                    learning["scientificWorlds"]?.intValue() == 16384 &&
                    learning["historicalReproductions"]?.intValue() == 64 &&
                    learning["eligibleFinalActor"]?.booleanValue() == true &&
                    learning["batches"]?.map { it["batch"].intValue() } == (1..64).toList() &&
                    learning["finalActorSha256"]?.asText() == admission.finalPolicy.sha256
            ) {
                "Final-only admission requires all 64 audited updates and their exact final actor."
            }
            val pins = learning.required("artifactPins")
            for (name in
                listOf("finalPolicy", "finalReceipt", "finalIdentity", "finalCheckpoint")) {
                val subject = admission.subject(name)
                require(pins[subject.originalPath]?.asText() == subject.sha256) {
                    "Final $name must be an exact original audited artifact."
                }
            }
            val finalDirectory = Path.of(admission.finalPolicy.originalPath).parent
            require(
                finalDirectory.fileName.toString() == "numeric" &&
                    finalDirectory.parent.fileName.toString() == "batch-64" &&
                    admission.subject("finalIdentity").originalPath ==
                        finalDirectory.resolve("identity.json").toString() &&
                    admission.subject("finalCheckpoint").originalPath ==
                        finalDirectory.resolve("checkpoint.json").toString() &&
                    admission.subject("finalReceipt").originalPath ==
                        finalDirectory.parent.resolve("receipt.json").toString()
            ) {
                "The admitted actor and numeric lineage must come from batch-64, never an earlier checkpoint."
            }
            val receipt = admission.subject("finalReceipt").read()
            val identity = admission.subject("finalIdentity").read()
            val checkpoint = admission.subject("finalCheckpoint").read()
            require(
                receipt["batch"]?.intValue() == 64 &&
                    receipt["complete"]?.booleanValue() == true &&
                    receipt["actorSha256"]?.asText() == admission.finalPolicy.sha256 &&
                    identity["actorVersion"]?.asText() == ExpansionOnPolicyInput.ACTOR_VERSION &&
                    identity["expansionMcObjective"]?.get("version")?.asText() ==
                        "expansion-mc-actor-only-1" &&
                    checkpoint["payload"]?.get("model") == admission.finalPolicy.read()
            ) {
                "Final receipt must bind the admitted batch-64 policy and original numeric lineage."
            }
            require(
                protocol["kind"]?.asText() == "expansion-on-policy-loop-1" &&
                    protocol["batches"]?.intValue() == 64 &&
                    protocol["actorSha256"]?.asText() == admission.initialPolicy.sha256 &&
                    protocol["actor"]?.asText() == admission.initialPolicy.originalPath &&
                    protocol["arenaSha256"]?.asText() ==
                        admission.subject("originalArena").sha256 &&
                    protocol["arena"]?.asText() ==
                        admission.subject("originalArena").originalPath &&
                    protocol["prepared"]?.get("sha256")?.asText() ==
                        admission.subject("prepared").sha256 &&
                    protocol["files"]?.get(admission.subject("ordinary").originalPath)?.asText() ==
                        admission.subject("ordinary").sha256
            ) {
                "Initializer, prepared dictionary and ordinary ecology must be the prospectively frozen learning subjects."
            }
            admission.verify()
            return admission
        }
    }
}
