package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonUnwrapped

/** New wire kind; unchanged typed phase rows share one complete origin and terminal result. */
internal data class TripleGameTrajectory(
    val kind: String = "triple-on-policy-trajectory-1",
    val expansionModelSha256: String,
    val expansionDecisions: List<TerritoryDecisionRecord>,
    @get:JsonUnwrapped val joint: JointGameTrajectory,
)

/** The same joint factory owns simulation, absolute offsets and canonical history for all heads. */
internal class TripleCollector(
    private val input: TripleCollectionInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): TripleGameTrajectory =
        JointCollector(input.joint, input.signature, deadlineNanos).collectTriple(input, index)
}
