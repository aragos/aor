package dev.aragos.aor.training

import dev.aragos.aor.Entry
import dev.aragos.aor.TextCodec

/**
 * Logical aliases retain all paired returns through explicit references to identical executions.
 */
internal data class PurchasePlanTailRecord(
    val planIndex: Int,
    val replica: Int,
    val continuationSeed: Long,
    val outcome: TokenValueOutcome,
    val canonicalFile: String,
    val canonicalSha256: String,
)

internal data class PurchasePlanTailResult(
    val originIndex: Int,
    val status: PurchasePlanOriginStatus,
    val logicalPlanExecutions: Map<Int, Int>,
    val tails: List<PurchasePlanTailRecord>,
)

/** Starts only with a completed public/prediction admission; never ranks or changes a plan. */
internal class PurchasePlanTails(
    private val input: PurchasePlanInput,
    private val admission: PurchasePlanTailAdmission,
    private val artifacts: TrainingArtifacts,
    private val deadline: Long,
) {
    fun collect(index: Int): PurchasePlanTailResult {
        require(index in 0 until 256) { "A continuation origin must belong to the frozen bank." }
        val public = admission.public(index)
        if (public.status != PurchasePlanOriginStatus.READY) {
            return PurchasePlanTailResult(index, public.status, emptyMap(), emptyList())
        }
        val (initial, offset) = admission.root(index, deadline)
        val study = input.study
        val seat = study.collection.seat(index)
        val models = List(4) { study.collection.baseline.model }
        val supported = public.plans.filter { it.status == PurchasePlanStatus.SUPPORTED }
        val executions = supported.associate { it.index to (it.aliasOf ?: it.index) }
        val tails =
            supported
                .filter { it.aliasOf == null }
                .flatMap { plan ->
                    study.streams[index].futures.mapIndexed { replica, stream ->
                        checkTokenValueDeadline(deadline)
                        val session = ExperimentSession(initial, stream, recordEntries = true)
                        PurchasePlanExecution.force(
                            session,
                            initial.playerIds[seat],
                            plan,
                            deadline,
                        )
                        val continuation =
                            FrozenPolicyContinuation(
                                models,
                                study.controllers(index),
                                study.collection.seeds[index],
                                deadline,
                                offset,
                            )
                        val outcome =
                            continuation.finish(
                                session,
                                seat,
                                study.collection.configuration.id,
                                stream,
                            )
                        val entries = session.entries.map(Entry::toText)
                        val replay =
                            entries.fold(initial) { state, text ->
                                checkTokenValueDeadline(deadline)
                                state.step(TextCodec.parse(text))
                            }
                        require(replay == session.state) {
                            "Every completed plan tail must replay exactly through the authoritative engine."
                        }
                        val name = "tail-$index-${plan.index}-$replica.json"
                        require(artifacts.read(name) == null) {
                            "A completed tail cannot be rerun or overwritten."
                        }
                        artifacts.write(
                            name,
                            mapOf(
                                "kind" to "purchase-plan-tail-canonical-1",
                                "signature" to input.signature,
                                "originIndex" to index,
                                "planIndex" to plan.index,
                                "replica" to replica,
                                "continuationSeed" to stream,
                                "rootNumber" to offset,
                                "backboneSha256" to
                                    PurchaseJson.hash(
                                        admission.publicDirectory.resolve("backbone-$index.json")
                                    ),
                                "publicSha256" to
                                    PurchaseJson.hash(
                                        admission.publicDirectory.resolve("public-$index.json")
                                    ),
                                "predictionsSha256" to PurchaseJson.hash(admission.predictions),
                                "actions" to plan.actions,
                                "entries" to entries,
                                "replayVerified" to true,
                            ),
                        )
                        PurchasePlanTailRecord(
                            plan.index,
                            replica,
                            stream,
                            outcome,
                            name,
                            PurchaseJson.hash(artifacts.directory.resolve(name)),
                        )
                    }
                }
        require(tails.size <= 32) {
            "An origin cannot exceed eight complete plans times four futures."
        }
        return PurchasePlanTailResult(index, public.status, executions, tails)
    }
}
