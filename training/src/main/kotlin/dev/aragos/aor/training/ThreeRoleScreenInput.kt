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
    val admissionBindings: Map<String, String>

    /** Defaults preserve the original unwrapped TOKEN/CPE execution contract. */
    fun executeGame(deadline: Long, operation: (Long) -> ArenaGame): ArenaGame = operation(deadline)

    fun executeReplay(deadline: Long, operation: (Long) -> StrategyReplay): StrategyReplay =
        operation(deadline)

    fun executionReceipt(expected: Int?): Map<String, Int> = emptyMap()

    /** Legacy screens retain homogeneous index0; explicit mixed contracts own their lineup. */
    fun expectedOpponents(index: Int): List<Int> = List(4) { 0 }

    fun specification(role: String): ArenaSpecification

    fun side(role: String): ArenaSide

    fun verify()

    fun manifest(): Map<String, Any>

    fun snapshot(artifacts: TrainingArtifacts)
}
