package dev.aragos.aor.training

/** Mechanical fixed-bank execution shared by separately admitted scientific contracts. */
internal interface ThreeRoleScreenInput {
    val kind: String
    val roles: List<String>
    val replayIndices: List<Int>
    val source: TokenPairedSubject
    val raw: ArenaSpecification
    val globalStart: Int
    val indices: IntRange
    val ownedReplays: List<Int>
    val signature: String
    val scienceSha256: String
    val scienceIdentity: Map<String, Any>
    val finalAdmissionSha256: String
    val historicalAdmissionSha256: String

    fun specification(role: String): ArenaSpecification

    fun side(role: String): ArenaSide

    fun verify()

    fun manifest(): Map<String, Any>

    fun snapshot(artifacts: TrainingArtifacts)
}
