package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION
import dev.aragos.aor.ai.JointSupportProfile

/** Fixed whole-bank qualification; the source protocol is independently root-pinned before use. */
internal class CoordinatedQualificationProtocol(val source: TokenPairedSubject) {
    val record: JsonNode = PurchaseJson.read(source.path)
    val banks: Map<String, CoordinatedQualificationBank>

    init {
        source.verify()
        PurchaseJson.fields(
            record,
            setOf(
                "kind",
                "profile",
                "supportProfile",
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
                "originAdmissionSha256",
                "originArenaSha256",
                "originProofSha256",
                "rulesVersion",
                "dataVersion",
                "configuration",
                "references",
                "banks",
                "maximumSeconds",
                "auditSeconds",
                "maximumTerminalExecutions",
                "interval",
            ),
        )
        require(
            record["kind"]?.asText() == VERSION &&
                record["profile"]?.asText() == PROFILE &&
                record["supportProfile"]?.asText() ==
                    JointSupportProfile.KNOWN_OWN_CHAOS_1.version &&
                record["rulesVersion"]?.asText() == RULES_VERSION &&
                record["dataVersion"]?.asText() == DATA_VERSION &&
                record["configuration"]?.asText() == CONFIGURATION &&
                PurchaseJson.integer(record["maximumSeconds"], "science seconds") == 28800 &&
                PurchaseJson.integer(record["auditSeconds"], "audit seconds") == 900 &&
                PurchaseJson.integer(record["maximumTerminalExecutions"], "terminal budget") ==
                    16400
        ) {
            "Declare the exact supported sole-profile qualification and original finite budget."
        }
        for (name in
            listOf(
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
                "originAdmissionSha256",
            )) {
            hash(record, name)
        }
        PurchaseJson.fields(record.required("originArenaSha256"), setOf("DESKTOP", "MAC"))
        PurchaseJson.fields(record.required("originProofSha256"), setOf("DESKTOP", "MAC"))
        for (host in QualificationHost.entries) {
            hash(record["originArenaSha256"], host.name)
            hash(record["originProofSha256"], host.name)
        }
        PurchaseJson.fields(
            record.required("references"),
            setOf(
                "qualifiedOrdinarySha256",
                "cardM8Sha256",
                "purchaseF0Sha256",
                "expansionESha256",
                "tokenControlVersion",
            ),
        )
        for (name in
            listOf(
                "qualifiedOrdinarySha256",
                "cardM8Sha256",
                "purchaseF0Sha256",
                "expansionESha256",
            )) {
            hash(record["references"], name)
        }
        require(record["references"]["tokenControlVersion"]?.asText() == "FIXED_16_TO_15") {
            "The supported profile retains the exact fixed16-to15 token reference."
        }
        require(
            record["interval"] ==
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "confidence" to .99,
                        "multiplier" to MULTIPLIER,
                        "requiredMetrics" to listOf("wins", "rewards"),
                        "lowerBoundStrictlyPositive" to true,
                    )
                )
        ) {
            "Qualification requires both paired99 lower bounds strictly above zero."
        }
        PurchaseJson.fields(record.required("banks"), setOf("mixed", "homogeneous"))
        banks =
            listOf("mixed", "homogeneous").associateWith {
                CoordinatedQualificationBank.read(record["banks"][it])
            }
        require(banks.values.flatMap { it.seeds }.distinct().size == 8192) {
            "Confirmation and homogeneous qualification must use disjoint complete fresh banks."
        }
    }

    fun bindInputs(
        host: QualificationHost,
        arena: TokenPairedSubject,
        admission: TokenPairedSubject,
        proof: TokenPairedSubject,
    ) = CoordinatedEvaluationReferences.bindInputs(record, host, arena, admission, proof)

    fun bind(admission: CoordinatedQualificationOrigin) =
        CoordinatedEvaluationReferences.bind(record, admission)

    fun validateArena(
        arenaSource: TokenPairedSubject,
        arena: ArenaSpecification,
        admission: CoordinatedQualificationOrigin,
        bankName: String,
    ) {
        val bank =
            requireNotNull(banks[bankName]) { "Only mixed or homogeneous banks are declared." }
        CoordinatedEvaluationReferences.validateArena(
            record,
            arenaSource,
            arena,
            admission,
            bankName == "mixed",
            4096,
            bank.nativeOffset,
            ArenaPurpose.CONFIRMATION,
        )
    }

    fun confirmation(subject: TokenPairedSubject?, bankName: String): Map<String, String> {
        if (bankName == "mixed") {
            require(subject == null) { "Mixed confirmation cannot consume a prior result." }
            return emptyMap()
        }
        val gate =
            requireNotNull(subject) {
                "Homogeneous evaluation requires completed mixed99 admission."
            }
        gate.verify()
        val value = PurchaseJson.read(gate.path)
        PurchaseJson.fields(
            value,
            setOf(
                "kind",
                "passed",
                "complete",
                "protocolSha256",
                "candidateBundleSha256",
                "bank",
                "worlds",
                "report",
                "evidence",
            ),
        )
        require(
            value["kind"]?.asText() == "coordinated-support-confirmation-1" &&
                value["passed"]?.booleanValue() == true &&
                value["complete"]?.booleanValue() == true &&
                value["protocolSha256"]?.asText() == source.sha256 &&
                value["bank"]?.asText() == "mixed" &&
                value["worlds"]?.asInt() == 4096 &&
                value["candidateBundleSha256"] == record["candidateBundleSha256"]
        ) {
            "Only this candidate's complete passed mixed bank may open the independent homogeneous bank."
        }
        val report = TokenPairedSubject.read(value["report"], gate.path.parent)
        val evidence = TokenPairedSubject.read(value["evidence"], gate.path.parent)
        CoordinatedQualificationReport.requirePassed(PurchaseJson.read(report.path))
        val completed = PurchaseJson.read(evidence.path)
        PurchaseJson.fields(
            completed,
            setOf(
                "kind",
                "complete",
                "bank",
                "protocolSha256",
                "candidateBundleSha256",
                "scienceSha256",
                "worlds",
                "games",
                "reproductions",
                "scientificTerminalExecutions",
                "gatePassed",
                "chunks",
                "compact",
                "report",
                "reportFinished",
            ),
        )
        require(
            completed["kind"]?.asText() == "coordinated-qualification-bank-evidence-1" &&
                completed["complete"]?.booleanValue() == true &&
                completed["bank"]?.asText() == "mixed" &&
                completed["protocolSha256"]?.asText() == source.sha256 &&
                completed["candidateBundleSha256"] == record["candidateBundleSha256"] &&
                PurchaseJson.integer(completed["worlds"], "completed worlds") == 4096 &&
                PurchaseJson.integer(completed["games"], "completed games") == 8192 &&
                PurchaseJson.integer(completed["reproductions"], "completed reproductions") == 8 &&
                PurchaseJson.integer(
                    completed["scientificTerminalExecutions"],
                    "completed executions",
                ) == 8200 &&
                completed["gatePassed"]?.booleanValue() == true &&
                completed["chunks"]?.isArray == true &&
                completed["chunks"].size() == 32
        ) {
            "The mixed gate must bind the complete32-shard,8200-execution evidence for this exact policy."
        }
        hash(completed, "scienceSha256")
        val evidenceReport = TokenPairedSubject.read(completed["report"], evidence.path.parent)
        require(evidenceReport.sha256 == report.sha256) {
            "The confirmation gate and complete bank evidence must bind the same native99 report."
        }
        return mapOf(
            gate.path.toString() to gate.sha256,
            report.path.toString() to report.sha256,
            evidence.path.toString() to evidence.sha256,
        )
    }

    companion object {
        const val VERSION = "coordinated-support-qualification-protocol-1"
        const val PROFILE = "aor-coordinated-83-support-1"
        const val CONFIGURATION = "players-4/standard/no-crusades/deck-true/art-true"
        const val MULTIPLIER = 2.5758293035489004
        val REPLAYS = listOf(0, 1365, 2730, 4095)

        private fun hash(node: JsonNode, key: String) {
            require(
                node[key]?.isTextual == true && node[key].asText().matches(Regex("[0-9a-f]{64}"))
            ) {
                "Qualification $key must be an exact raw SHA256 subject."
            }
        }
    }
}

/** Qualification's4096-world wire is distinct from the unchanged256/2048 ES bank type. */
internal data class CoordinatedQualificationBank(val nativeOffset: Int, val seeds: List<Int>) {
    init {
        require(
            nativeOffset in 0..100_000_000 &&
                seeds == List(4096) { StrategyArena.seed(nativeOffset, it) }
        ) {
            "A qualification bank requires all4096 original native global-index seeds."
        }
    }

    companion object {
        fun read(node: JsonNode): CoordinatedQualificationBank {
            PurchaseJson.fields(node, setOf("nativeOffset", "count", "seeds"))
            require(PurchaseJson.integer(node["count"], "qualification bank count") == 4096) {
                "Only a complete4096 paired-world qualification bank is admitted."
            }
            return CoordinatedQualificationBank(
                PurchaseJson.integer(node["nativeOffset"], "qualification native offset"),
                PurchaseJson.array(node["seeds"], "qualification seeds").map {
                    PurchaseJson.integer(it, "qualification game seed")
                },
            )
        }
    }
}

/**
 * Fixed two-host allocation; source.host is provenance, never part of the paired policy identity.
 */
internal enum class QualificationHost {
    DESKTOP,
    MAC;

    fun owns(start: Int): Boolean = if (this == DESKTOP) start < 20 * 128 else start >= 20 * 128
}
