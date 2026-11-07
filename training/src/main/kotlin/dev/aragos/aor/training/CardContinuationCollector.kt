package dev.aragos.aor.training

import dev.aragos.aor.ai.CardProposals

/**
 * F owns the complete prospective backbone. A forced F/A action is applied before either own-seat
 * tail can choose a move. All four arms share each declared chance-stream start and frozen
 * purchases.
 */
internal class CardContinuationCollector(
    private val input: CardContinuationDiagnosticInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): CardContinuationOrigin {
        require(index in input.origins.indices) { "Diagnostic origin index is outside its source." }
        val request = input.origins[index]
        val baseline = CardAdvantageExecutor(input.backboneInput, request, deadlineNanos)
        val capture =
            CardAdvantageRootCapture(input.backboneInput, deadlineNanos).capture(index, baseline)
        val identity =
            CardContinuationOrigin(
                signature = input.signature(),
                index = index,
                split = request.split,
                seed = request.seed,
                seat = request.seat,
                status = "skipped",
                skipReason = "requested-root-not-reached",
                request = request,
                backboneDecisions = capture.backboneDecisions,
                backboneCanonicalSha256 = capture.backboneCanonicalSha256,
                root = null,
                outcomes = emptyMap(),
            )
        val captured = capture.root ?: return identity
        val decision = baseline.decision(captured.state, captured.actor, captured.number)
        val reference = CardAdvantageController.recommendation(decision, input.environment)
        check(reference == captured.chosenCommand) {
            "Priority tail policies may never alter the F backbone or root recommendation."
        }
        val observation = CardAdvantageController.observation(decision, reference)
        val proposals = CardProposals.indices(observation, decision.seed)
        val acquisition =
            CardAdvantageAcquisition.select(
                proposals,
                observation.baselineIndex,
                decision.seed,
                sampled = true,
            )
        val alternative = acquisition.sampledActionIndex
        val priority =
            CardAdvantageExecutor(
                input.backboneInput.copy(continuation = input.priority),
                request,
                deadlineNanos,
            )
        val snapshot = ExperimentSession(captured.state, 0L, recordEntries = true)
        val outcomes = linkedMapOf<String, CardAdvantageOutcome>()
        val arms =
            CardContinuationArm.IDS.map { id ->
                val action = CardContinuationArm.action(id, observation.baselineIndex, alternative)
                val continuation = CardContinuationArm.tail(id)
                val executor = if (continuation == "F") baseline else priority
                val ids = CardContinuationArm.outcomeIds(id, alternative)
                ids.forEachIndexed { replicate, outcomeId ->
                    if (outcomeId !in outcomes) {
                        executor.check(captured.number)
                        val branch =
                            snapshot.fork(CardAdvantageExecutor.chanceSeed(request.seed, replicate))
                        branch.apply(
                            mapOf(captured.actor to observation.actions[action].command),
                            deadlineNanos,
                        )
                        outcomes[outcomeId] =
                            executor.finish(
                                branch,
                                captured.state,
                                captured.number,
                                replicate,
                            )
                    }
                }
                CardContinuationArm(id, action, continuation, ids)
            }
        check(
            snapshot.state == captured.state &&
                snapshot.decisions == 0 &&
                snapshot.entries.isEmpty()
        ) {
            "Diagnostic arms must not mutate their shared root or chance-stream start."
        }
        return identity.copy(
            status = "collected",
            skipReason = null,
            root =
                CardContinuationRoot(
                    captured.number,
                    decision.seed,
                    requireNotNull(captured.state.turnNumber) {
                        "A normal card root requires its turn."
                    },
                    captured.state.currentEpoch.number,
                    observation,
                    proposals,
                    observation.baselineIndex,
                    acquisition,
                    alternative,
                    arms,
                ),
            outcomes = outcomes.toMap(),
        )
    }
}
