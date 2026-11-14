package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.DATA_VERSION
import dev.aragos.aor.RULES_VERSION

/** Prospectively fixed factorial cells; their shared bank supplies512 independent world units. */
internal class CoordinatedDiagnosticProtocol(val source: TokenPairedSubject) {
    val record: JsonNode = PurchaseJson.read(source.path)
    val nativeOffset: Int
    val seeds: List<Int>

    init {
        source.verify()
        PurchaseJson.fields(
            record,
            setOf(
                "kind",
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
                "originAdmissionSha256",
                "originArenaSha256",
                "originProofSha256",
                "references",
                "rulesVersion",
                "dataVersion",
                "configuration",
                "bank",
                "heads",
                "masks",
                "supportProfiles",
                "ecologies",
                "replayWorlds",
                "diagnosticOnly",
                "promotionAllowed",
                "maximumSeconds",
                "auditSeconds",
                "maximumTerminalExecutions",
                "interval",
            ),
        )
        require(
            record["kind"]?.asText() == "coordinated-diagnostic-protocol-1" &&
                record["rulesVersion"]?.asText() == RULES_VERSION &&
                record["dataVersion"]?.asText() == DATA_VERSION &&
                record["configuration"]?.asText() ==
                    CoordinatedQualificationProtocol.CONFIGURATION &&
                record["diagnosticOnly"]?.isBoolean == true &&
                record["diagnosticOnly"].booleanValue() &&
                record["promotionAllowed"]?.isBoolean == true &&
                !record["promotionAllowed"].booleanValue() &&
                PurchaseJson.integer(record["maximumSeconds"], "diagnostic seconds") == 14400 &&
                PurchaseJson.integer(record["auditSeconds"], "audit seconds") == 900 &&
                PurchaseJson.integer(
                    record["maximumTerminalExecutions"],
                    "diagnostic executions",
                ) == 32832
        ) {
            "Use the sole frozen83 factorial diagnostic and its exact finite non-promoting budget."
        }
        for (key in
            listOf(
                "candidateBundleSha256",
                "originalBundleSha256",
                "calibrationSha256",
                "originAdmissionSha256",
            )) {
            hash(record, key)
        }
        for (key in listOf("originArenaSha256", "originProofSha256")) {
            PurchaseJson.fields(record.required(key), setOf("DESKTOP", "MAC"))
            QualificationHost.entries.forEach { hash(record[key], it.name) }
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
        for (key in
            listOf(
                "qualifiedOrdinarySha256",
                "cardM8Sha256",
                "purchaseF0Sha256",
                "expansionESha256",
            )) {
            hash(record["references"], key)
        }
        require(record["references"]["tokenControlVersion"]?.asText() == "FIXED_16_TO_15") {
            "Every mask retains the exact fixed16-to15 token reference."
        }
        fun exact(key: String, value: Any) {
            require(record[key] == ExpansionOnPolicyStore.wire(value)) {
                "Diagnostic $key must retain its complete fixed recipe."
            }
        }
        exact("heads", mapOf("CARD" to 1, "PURCHASE" to 2, "EXPANSION" to 4, "TOKEN" to 8))
        exact("masks", (0..15).toList())
        exact("supportProfiles", DiagnosticSupport.entries.map { it.name })
        exact("ecologies", DiagnosticEcology.entries.map { it.name })
        exact("replayWorlds", REPLAYS)
        exact(
            "interval",
            mapOf("confidence" to .95, "multiplier" to 1.96, "unit" to "WORLD", "gates" to false),
        )
        val bank = record.required("bank")
        PurchaseJson.fields(bank, setOf("nativeOffset", "count", "seeds"))
        nativeOffset = PurchaseJson.integer(bank["nativeOffset"], "diagnostic native offset")
        seeds =
            PurchaseJson.array(bank["seeds"], "diagnostic seeds").map {
                PurchaseJson.integer(it, "world seed")
            }
        require(
            nativeOffset in 0..100_000_000 &&
                PurchaseJson.integer(bank["count"], "world count") == 512 &&
                seeds == List(512) { StrategyArena.seed(nativeOffset, it) }
        ) {
            "The diagnostic requires its entire original512-world native bank in global order."
        }
    }

    companion object {
        val REPLAYS = listOf(0, 170, 341, 511)

        private fun hash(node: JsonNode, key: String) {
            require(
                node[key]?.isTextual == true && node[key].asText().matches(Regex("[0-9a-f]{64}"))
            ) {
                "Diagnostic $key must bind exact raw SHA256 bytes."
            }
        }
    }
}

internal enum class DiagnosticSupport {
    RAW,
    SUPPORT1,
}

internal enum class DiagnosticEcology {
    HOMOGENEOUS,
    MIXED,
}

/** Complementary roles execute each mask once; role names never infer a policy winner. */
internal data class DiagnosticCell(
    val ecology: DiagnosticEcology,
    val support: DiagnosticSupport,
    val pair: Int,
) {
    init {
        require(pair in 0..7) { "A diagnostic pair must be one of the eight fixed complements." }
    }

    val roleMasks = mapOf("CANDIDATE" to pair, "INCUMBENT" to 15 - pair)

    fun replayRoles(index: Int): List<String> =
        roleMasks.filterValues { replay(it) == index }.keys.toList()

    val replayIndices: List<Int> = roleMasks.values.map(::replay).sorted()

    fun opponents(index: Int): List<Int> {
        require(index in 0..511) { "Diagnostic lineups retain original global indices0..511." }
        return List(4) { if (ecology == DiagnosticEcology.MIXED) (index / 4 + it) % 4 else 0 }
    }

    companion object {
        fun replay(mask: Int): Int {
            require(mask in 0..15) { "A head mask must retain all four original bit positions." }
            return CoordinatedDiagnosticProtocol.REPLAYS[mask % 4]
        }

        fun range(host: QualificationHost): IntRange =
            if (host == QualificationHost.DESKTOP) 0..319 else 320..511
    }
}
