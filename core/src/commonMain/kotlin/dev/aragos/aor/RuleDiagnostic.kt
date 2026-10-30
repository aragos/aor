package dev.aragos.aor

/** Only public entity families are permitted in rejected-action context. */
enum class RuleEntityKind {
    PLAYER,
    PROVINCE,
    ADVANCE,
    COMMODITY,
}

data class RuleEntityReference(val kind: RuleEntityKind, val id: String)

/** Stable presentation data; internal explanations and private card identities are excluded. */
data class RuleDiagnostic(
    val code: String,
    val entityIds: List<String> = emptyList(),
    val entities: List<RuleEntityReference> = emptyList(),
    val parameters: Map<String, Int> = emptyMap(),
)

interface DomainRejection {
    val diagnostic: RuleDiagnostic
}
