package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path

/** Independent prospective repetitions; only the desktop result can later be nominated. */
internal enum class ExpansionBaselineReplica {
    PRIMARY_DESKTOP,
    MAC_REPLICATION;

    companion object {
        fun read(value: JsonNode): ExpansionBaselineReplica =
            requireNotNull(entries.singleOrNull { it.name == value.asText() }) {
                "Declare the exact prospective baseline-control replica."
            }
    }
}

/** Admits the two completed eighth actors together, before any final actor is decoded. */
internal class ExpansionBaselineAdmission
private constructor(
    val source: ExpansionScreenSubject,
    val replica: ExpansionBaselineReplica,
    private val subjects: Map<String, ExpansionScreenSubject>,
) {
    fun subject(name: String): ExpansionScreenSubject =
        requireNotNull(subjects[name]) { "Missing baseline-screen subject $name." }

    val treatmentPolicy
        get() = subject("treatmentPolicy")

    val controlPolicy
        get() = subject("controlPolicy")

    val prepared
        get() = subject("prepared").read()

    fun verify() {
        source.verify()
        subjects.values.forEach(ExpansionScreenSubject::verify)
    }

    fun validateEcology(arena: ArenaSpecification) {
        val binding = ExpansionOnPolicyStore.wire(arena.overlayMetadata())
        val original = prepared
        val previous = original.required("policyBindings")
        require(
            original["configuration"]?.asText() == arena.configurations.single().id &&
                original["frozenCardPurchaseBundleSha256"]?.asText() ==
                    binding["jointPolicy"]["sharedBundle"]["sha256"].asText() &&
                original["referenceExpansionModelSha256"]?.asText() ==
                    binding["expansionImitation"]["candidateModel"]["sha256"].asText() &&
                previous["jointPolicy"]["baseline"]["sha256"] ==
                    binding["jointPolicy"]["baseline"]["sha256"] &&
                previous["tokenOrder"] == binding["tokenOrder"]
        ) {
            "The three-role screen must preserve the prepared G3/F0/E/T ecology."
        }
        val oldArena = subject("originalArena").read()
        val ordinary = subject("ordinary")
        val originalDirectory = Path.of(subject("originalArena").originalPath).parent
        require(
            originalDirectory.resolve(oldArena["candidate"].asText()).normalize().toString() ==
                ordinary.originalPath &&
                TrainingArtifacts.readModel(ordinary.path) == arena.candidate
        ) {
            "The screen ordinary policy must be the exact original raw M8 subject."
        }
    }

    private fun validateProtocol(protocol: JsonNode) {
        require(
            protocol["kind"]?.asText() == "expansion-baseline-control-loop-1" &&
                protocol["rounds"]?.intValue() == 8 &&
                protocol["worldsPerBatch"]?.intValue() == 256 &&
                protocol["prefixRounds"]?.intValue() == 2 &&
                protocol["actor"]?.asText() == subject("initialPolicy").originalPath &&
                protocol["actorSha256"]?.asText() == subject("initialPolicy").sha256 &&
                protocol["arena"]?.asText() == subject("originalArena").originalPath &&
                protocol["arenaSha256"]?.asText() == subject("originalArena").sha256 &&
                protocol["prepared"]?.get("sha256")?.asText() == subject("prepared").sha256 &&
                protocol["prepared"]?.get("path")?.asText() == subject("prepared").originalPath &&
                protocol["files"]?.get(subject("ordinary").originalPath)?.asText() ==
                    subject("ordinary").sha256
        ) {
            "The screen requires the prospectively fixed eight-round initializer and ecology."
        }
    }

    private fun validateAudit(audit: JsonNode) {
        require(
            audit["kind"]?.asText() == "expansion-baseline-learning-audit-1" &&
                audit["replica"]?.asText() == replica.name &&
                audit["passed"]?.booleanValue() == true &&
                audit["complete"]?.booleanValue() == true &&
                audit["completedRounds"]?.intValue() == 8 &&
                audit["completedBatches"] ==
                    ExpansionOnPolicyStore.wire(mapOf("CONTROL" to 8, "TREATMENT" to 8)) &&
                audit["scientificWorlds"]?.intValue() == 4096 &&
                audit["historicalReproductions"]?.intValue() == 16 &&
                audit["firstBatchParityPassed"]?.booleanValue() == true &&
                audit["operationalPrefixPassed"]?.booleanValue() == true &&
                audit["eligibleFinalActors"]?.booleanValue() == true &&
                audit["protocolSha256"]?.asText() == subject("learningProtocol").sha256 &&
                audit["preparedSha256"]?.asText() == subject("prepared").sha256
        ) {
            "Both arms must complete all eight audited batches and the fixed equality/prefix gates."
        }
        PurchaseJson.fields(audit.required("arms"), setOf("CONTROL", "TREATMENT"))
        PurchaseJson.fields(audit.required("receiptSha256"), setOf("CONTROL", "TREATMENT"))
        for (arm in listOf("CONTROL", "TREATMENT")) {
            val receipts = audit["receiptSha256"].required(arm)
            PurchaseJson.fields(receipts, (1..8).map(Int::toString).toSet())
            require(receipts.all { it.isTextual && it.asText().matches(Regex("[0-9a-f]{64}")) }) {
                "Every original $arm batch receipt must be independently pinned."
            }
        }
    }

    private fun validateArm(audit: JsonNode, arm: String) {
        val prefix = arm.lowercase()
        val policy = subject("${prefix}Policy")
        val identity = subject("${prefix}Identity")
        val checkpoint = subject("${prefix}Checkpoint")
        val receipt = subject("${prefix}Receipt")
        val actorDirectory = Path.of(policy.originalPath).parent
        val armDirectory = actorDirectory.parent.parent
        require(
            actorDirectory.fileName.toString() == "actor" &&
                actorDirectory.parent.fileName.toString() == "numeric" &&
                armDirectory.fileName.toString() == arm &&
                armDirectory.parent.fileName.toString() == "batch-08" &&
                Path.of(policy.originalPath).fileName.toString() == "policy.json" &&
                identity.originalPath == actorDirectory.resolve("identity.json").toString() &&
                checkpoint.originalPath == actorDirectory.resolve("checkpoint.json").toString() &&
                receipt.originalPath == armDirectory.resolve("receipt.json").toString()
        ) {
            "Only the exact $arm batch-08 actor and original numeric lineage are eligible."
        }
        val completed = audit["arms"].required(arm)
        val pins = audit.required("artifactPins")
        for ((name, value) in
            mapOf(
                "policy" to policy,
                "identity" to identity,
                "checkpoint" to checkpoint,
                "receipt" to receipt,
            )) {
            require(
                completed["${name}Path"]?.asText() == value.originalPath &&
                    completed["${name}Sha256"]?.asText() == value.sha256 &&
                    pins[value.originalPath]?.asText() == value.sha256
            ) {
                "The audited $arm $name must preserve its exact original path and bytes."
            }
        }
        require(audit["receiptSha256"][arm]["8"]?.asText() == receipt.sha256) {
            "The final $arm receipt must be the eighth original audited receipt."
        }
        val record = receipt.read()
        require(
            record["kind"]?.asText() == "expansion-baseline-batch-1" &&
                record["arm"]?.asText() == arm &&
                record["replica"]?.asText() == replica.name &&
                record["protocolSha256"]?.asText() == subject("learningProtocol").sha256 &&
                record["batch"]?.intValue() == 8 &&
                record["complete"]?.booleanValue() == true &&
                record["actorSha256"]?.asText() == policy.sha256
        ) {
            "The final receipt must identify the exact completed $arm eighth actor."
        }
        val numeric = identity.read()
        require(numeric["actorVersion"]?.asText() == ExpansionOnPolicyInput.ACTOR_VERSION) {
            "Each final actor must retain the schema-2 expansion numeric contract."
        }
        if (arm == "CONTROL") {
            require(
                numeric["expansionMcObjective"]?.get("version")?.asText() ==
                    "expansion-mc-actor-only-1" && numeric["expansionBaselineObjective"] == null
            ) {
                "CONTROL must retain the original constant-baseline MC objective."
            }
        } else {
            val objective = numeric.required("expansionBaselineObjective")
            require(
                objective["version"]?.asText() == "expansion-previous-batch-state-baseline-1" &&
                    objective["phase"]?.asText() == "expansion" &&
                    objective["arm"]?.asText() == "TREATMENT" &&
                    objective["replica"]?.asText() == replica.name &&
                    objective["batch"]?.intValue() == 8 &&
                    numeric["expansionMcObjective"] == null
            ) {
                "TREATMENT must retain its explicit previous-batch state-baseline identity."
            }
        }
    }

    companion object {
        const val VERSION = "expansion-baseline-screen-admission-1"
        private val names =
            setOf(
                "learningAudit",
                "learningProtocol",
                "prepared",
                "initialPolicy",
                "originalArena",
                "ordinary",
                "controlPolicy",
                "controlCheckpoint",
                "controlIdentity",
                "controlReceipt",
                "treatmentPolicy",
                "treatmentCheckpoint",
                "treatmentIdentity",
                "treatmentReceipt",
            )

        fun read(source: ExpansionScreenSubject): ExpansionBaselineAdmission {
            val node = source.read()
            PurchaseJson.fields(node, setOf("kind", "subjects"))
            require(node["kind"]?.asText() == VERSION) {
                "An explicit two-arm eighth-actor admission is required."
            }
            val values = node.required("subjects")
            PurchaseJson.fields(values, names)
            val subjects = names.associateWith {
                ExpansionScreenSubject.read(source.path, values.required(it))
            }
            val protocol =
                requireNotNull(subjects["learningProtocol"]) {
                        "The exact replica protocol is required."
                    }
                    .read()
            val replica = ExpansionBaselineReplica.read(protocol.required("replica"))
            val result = ExpansionBaselineAdmission(source, replica, subjects)
            result.validateProtocol(protocol)
            val audit = result.subject("learningAudit").read()
            result.validateAudit(audit)
            result.validateArm(audit, "CONTROL")
            result.validateArm(audit, "TREATMENT")
            for (arm in listOf("control", "treatment")) {
                require(
                    result.subject("${arm}Checkpoint").read()["payload"]?.get("model") ==
                        result.subject("${arm}Policy").read()
                ) {
                    "The admitted $arm model must be exactly its audited numeric checkpoint model."
                }
            }
            result.verify()
            return result
        }
    }
}
