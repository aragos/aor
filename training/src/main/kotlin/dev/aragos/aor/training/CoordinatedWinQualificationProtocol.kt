package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION

/** Exact win-only independent qualification; model identities belong to the nominated asset. */
internal class CoordinatedWinQualificationProtocol(val source: TokenPairedSubject) {
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
                "inferenceAssetSha256",
                "nominationSha256",
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
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
                record["profile"]?.asText() == CoordinatedInferenceAsset.PROFILE &&
                record["supportProfile"]?.asText() == CoordinatedInferenceAsset.SUPPORT &&
                record["rulesVersion"]?.asText() == RULES_VERSION &&
                record["dataVersion"]?.asText() == DATA_VERSION &&
                record["configuration"]?.asText() ==
                    CoordinatedQualificationProtocol.CONFIGURATION &&
                PurchaseJson.integer(record["maximumSeconds"], "science seconds") == 28800 &&
                PurchaseJson.integer(record["auditSeconds"], "audit seconds") == 900 &&
                PurchaseJson.integer(record["maximumTerminalExecutions"], "terminal budget") ==
                    16400
        ) {
            "Use the fixed supported win-only qualification profile and finite budget."
        }
        listOf(
                "inferenceAssetSha256",
                "nominationSha256",
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
            )
            .forEach { hash(record, it) }
        PurchaseJson.fields(
            record.required("references"),
            CoordinatedInferenceAsset.REFERENCE_NAMES.map { it + "Sha256" }.toSet() +
                "tokenControlVersion",
        )
        CoordinatedInferenceAsset.REFERENCE_NAMES.forEach {
            hash(record["references"], it + "Sha256")
        }
        require(record["references"]["tokenControlVersion"]?.asText() == "FIXED_16_TO_15") {
            "The fixed token reference cannot change."
        }
        require(
            record["interval"] ==
                ExpansionOnPolicyStore.wire(
                    mapOf(
                        "confidence" to .99,
                        "multiplier" to CoordinatedQualificationProtocol.MULTIPLIER,
                        "requiredMetrics" to listOf("wins"),
                        "lowerBoundStrictlyPositive" to true,
                    )
                )
        ) {
            "Only the complete paired99 win lower bound owns this versioned gate."
        }
        PurchaseJson.fields(record.required("banks"), setOf("mixed", "homogeneous"))
        banks =
            listOf("mixed", "homogeneous").associateWith {
                CoordinatedQualificationBank.read(record["banks"][it])
            }
        require(banks.values.flatMap { it.seeds }.distinct().size == 8192) {
            "The two complete independent banks must be disjoint."
        }
    }

    fun bindInputs(asset: TokenPairedSubject, nomination: TokenPairedSubject) {
        require(
            record["inferenceAssetSha256"].asText() == asset.sha256 &&
                record["nominationSha256"].asText() == nomination.sha256
        ) {
            "The exact root-pinned inference asset and nomination are required before model access."
        }
        asset.verify()
        val node = PurchaseJson.read(asset.path)
        require(
            node["currentBundle"]?.let { it["sha256"] } == record["candidateBundleSha256"] &&
                node["originalBundle"]?.let { it["sha256"] } == record["originalBundleSha256"] &&
                node["calibration"]?.let { it["sha256"] } == record["calibrationSha256"] &&
                CoordinatedInferenceAsset.REFERENCE_NAMES.all {
                    node["references"]?.let { refs ->
                        refs[it]?.let { subject -> subject["sha256"] }
                    } == record["references"][it + "Sha256"]
                }
        ) {
            "The protocol must bind every current, original, calibrated and reference asset subject."
        }
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
                "inferenceAssetSha256",
                "nominationSha256",
                "bank",
                "worlds",
                "report",
                "evidence",
            ),
        )
        require(
            value["kind"]?.asText() == "coordinated-win-qualification-confirmation-1" &&
                value["passed"]?.booleanValue() == true &&
                value["complete"]?.booleanValue() == true &&
                value["protocolSha256"]?.asText() == source.sha256 &&
                value["bank"]?.asText() == "mixed" &&
                value["worlds"]?.asInt() == 4096 &&
                value["candidateBundleSha256"] == record["candidateBundleSha256"] &&
                value["inferenceAssetSha256"] == record["inferenceAssetSha256"] &&
                value["nominationSha256"] == record["nominationSha256"]
        ) {
            "Only this candidate's complete passed mixed bank may open the independent homogeneous bank."
        }
        val report = TokenPairedSubject.read(value["report"], gate.path.parent)
        val evidence = TokenPairedSubject.read(value["evidence"], gate.path.parent)
        REPORT.requirePassed(PurchaseJson.read(report.path))
        val completed = PurchaseJson.read(evidence.path)
        PurchaseJson.fields(
            completed,
            setOf(
                "kind",
                "complete",
                "bank",
                "protocolSha256",
                "candidateBundleSha256",
                "inferenceAssetSha256",
                "nominationSha256",
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
            completed["kind"]?.asText() == "coordinated-win-qualification-bank-evidence-1" &&
                completed["complete"]?.booleanValue() == true &&
                completed["bank"]?.asText() == "mixed" &&
                completed["protocolSha256"]?.asText() == source.sha256 &&
                completed["candidateBundleSha256"] == record["candidateBundleSha256"] &&
                completed["inferenceAssetSha256"] == record["inferenceAssetSha256"] &&
                completed["nominationSha256"] == record["nominationSha256"] &&
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
        const val VERSION = "coordinated-win-qualification-protocol-1"
        val REPORT =
            PairedQualificationReport(
                "coordinated-win-qualification-screen-report-1",
                listOf("wins"),
            )

        private fun hash(node: JsonNode, key: String) {
            require(
                node[key]?.isTextual == true && node[key].asText().matches(Regex("[0-9a-f]{64}"))
            ) {
                "$key must be an exact raw SHA-256 subject."
            }
        }
    }
}
