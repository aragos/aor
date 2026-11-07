package dev.aragos.aor.training

import dev.aragos.aor.ai.CardProposals

/** F captures the root; schedules change only tail policy seeds, never authority randomness. */
internal class CardPolicyCouplingCollector(
    private val input: CardPolicyCouplingInput,
    private val deadlineNanos: Long,
) {
    fun collect(index: Int): CardPolicyCouplingOrigin {
        require(index in input.origins.indices) { "Coupling origin index is outside its source." }
        val request = input.origins[index]
        val executor = CardAdvantageExecutor(input.backboneInput, request, deadlineNanos)
        val capture =
            CardAdvantageRootCapture(input.backboneInput, deadlineNanos).capture(index, executor)
        val identity =
            CardPolicyCouplingOrigin(
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
                policyTraces = emptyMap(),
            )
        val captured = capture.root ?: return identity
        val decision = executor.decision(captured.state, captured.actor, captured.number)
        val reference = CardAdvantageController.recommendation(decision, input.environment)
        check(reference == captured.chosenCommand) {
            "Seed schedules may not change the F prefix or root."
        }
        val observation = CardAdvantageController.observation(decision, reference)
        val proposals = CardProposals.indices(observation, decision.seed)
        val acquisition =
            CardAdvantageAcquisition.select(
                proposals,
                observation.baselineIndex,
                decision.seed,
                true,
            )
        val alternative = acquisition.sampledActionIndex
        val turn =
            requireNotNull(captured.state.turnNumber) { "A normal card root requires its turn." }
        val snapshot = ExperimentSession(captured.state, 0L, recordEntries = true)
        val outcomes = linkedMapOf<String, CardAdvantageOutcome>()
        val traces = linkedMapOf<String, List<CardPolicySeedTrace>>()
        val arms =
            CardPolicyCouplingArm.IDS.map { id ->
                val action =
                    CardPolicyCouplingArm.action(id, observation.baselineIndex, alternative)
                val schedule = CardPolicyCouplingArm.schedule(id)
                val ids = CardPolicyCouplingArm.outcomeIds(id, alternative)
                ids.forEachIndexed { replicate, outcomeId ->
                    if (outcomeId !in outcomes) {
                        executor.check(captured.number)
                        val chance = CardPolicySeeds.chanceSeed(request.seed, replicate)
                        val branch = snapshot.fork(chance)
                        val seeds = CardPolicySeeds(request.seed, replicate, schedule)
                        seeds.reserveRoot(captured.number, turn, request.seat)
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
                                seeds,
                                chance,
                            )
                        traces[outcomeId] = seeds.trace
                    }
                }
                CardPolicyCouplingArm(id, schedule.name, action, ids)
            }
        check(
            snapshot.state == captured.state &&
                snapshot.decisions == 0 &&
                snapshot.entries.isEmpty()
        ) {
            "Coupling branches may not mutate their root snapshot or shared chance start."
        }
        return identity.copy(
            status = "collected",
            skipReason = null,
            root =
                CardPolicyCouplingRoot(
                    captured.number,
                    decision.seed,
                    turn,
                    captured.state.currentEpoch.number,
                    observation,
                    proposals,
                    observation.baselineIndex,
                    acquisition,
                    alternative,
                    arms,
                ),
            outcomes = outcomes.toMap(),
            policyTraces = traces.toMap(),
        )
    }
}
