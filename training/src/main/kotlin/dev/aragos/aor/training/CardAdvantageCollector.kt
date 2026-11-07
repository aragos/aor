package dev.aragos.aor.training

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardKind
import dev.aragos.aor.Entry
import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.CardAdvantagePolicy
import dev.aragos.aor.ai.CardObservation
import dev.aragos.aor.ai.CardProposals

/**
 * Root and child interventions use the same frozen continuation, including every seat's purchases.
 */
internal class CardAdvantageCollector(
    private val input: CardAdvantageCollectionInput,
    private val deadlineNanos: Long,
) {
    private data class Root(
        val number: Int,
        val entries: Int,
        val observation: BotObservation,
        val chosen: String,
    )

    private class Trace(
        private val request: CardAdvantageRequest,
        private val allowTrivial: Boolean,
    ) : SimulationTrace {
        override val seat: Int = request.seat
        var root: Root? = null
        var ordinal = 0
        lateinit var header: GameHeader
        lateinit var entries: List<String>

        override fun decision(
            number: Int,
            snapshotEntryCount: Int,
            commandEntryIndex: Int,
            view: GameView,
            observation: BotObservation,
            chosen: BotCandidate,
            model: BotModel,
        ) {
            if (
                view.stage != Stage.PLAY_CARDS ||
                    observation.decision != "play-cards" ||
                    view.currentEpoch != request.epoch
            )
                return
            ordinal++
            if (
                root != null ||
                    ordinal < request.ordinal ||
                    !eligible(view, observation, request, allowTrivial)
            )
                return
            check(snapshotEntryCount == commandEntryIndex) {
                "An ordinary card root requires a single-player batch."
            }
            root = Root(number, snapshotEntryCount, observation, chosen.command)
        }

        override fun verified(header: GameHeader, entries: List<String>) {
            this.header = header
            this.entries = entries
        }
    }

    private data class Node(
        val observation: CardObservation,
        val number: Int,
        val botSeed: Int,
        val turn: Int,
        val epoch: Int,
        val proposals: List<Int>,
        val continuationIndex: Int,
    )

    fun collect(index: Int): CardAdvantageOrigin {
        require(index in input.origins.indices) {
            "An advantage origin index must belong to its frozen source."
        }
        val request = input.origins[index]
        val executor = CardAdvantageExecutor(input, request, deadlineNanos)
        val trace = Trace(request, allowTrivial = input.collection != null)
        val backbone =
            Simulation()
                .play(
                    configuration = input.environment.configuration,
                    seed = request.seed,
                    competitors =
                        List(4) {
                            Competitor(input.environment.baseline.model, BotDifficulty.HARD)
                        },
                    deadlineNanos = deadlineNanos,
                    verifyReplay = true,
                    trace = trace,
                    controllers = executor.controllers,
                )
        val identity =
            CardAdvantageOrigin(
                schemaVersion = input.schemaVersion,
                signature = input.signature(),
                index = index,
                split = request.split,
                seed = request.seed,
                seat = request.seat,
                status = "skipped",
                skipReason = "requested-root-not-reached",
                request = request,
                backboneDecisions = backbone.steps,
                backboneCanonicalSha256 = CardAdvantageExecutor.hash(trace.entries),
                observations = emptyMap(),
                groups = emptyList(),
                outcomes = emptyMap(),
            )
        val captured = trace.root ?: return identity
        val rootState =
            trace.entries.take(captured.entries).fold(GameState(trace.header)) { state, text ->
                executor.check(captured.number)
                state.step(TextCodec.parse(text))
            }
        val actor = rootState.playerIds[request.seat]
        check(BotObservations.from(rootState, actor) == captured.observation) {
            "The recorded root observation must regenerate exactly."
        }
        val root = node(rootState, actor, captured.number, executor)
        check(root.observation.actions[root.continuationIndex].command == captured.chosen) {
            "The replayed root must preserve its actual continuation policy and seed."
        }
        val snapshot = ExperimentSession(rootState, seed = 0L, recordEntries = true)
        if (input.collection != null) {
            return rootPairs(identity, rootState, actor, root, snapshot, executor)
        }
        val observations = linkedMapOf<String, CardObservation>()
        val groups = mutableListOf<CardAdvantageGroup>()
        val outcomes = linkedMapOf<String, CardAdvantageOutcome>()
        fun observe(observation: CardObservation): String {
            val hash = CardAdvantageExecutor.hash(observation)
            val previous = observations.putIfAbsent(hash, observation)
            check(previous == null || previous == observation) {
                "An observation hash must identify identical safe input."
            }
            return hash
        }
        fun saveOutcome(id: String, session: ExperimentSession, replicate: Int): String {
            check(id !in outcomes && outcomes.size < 52) {
                "An advantage origin may execute at most 52 distinct complete tails."
            }
            outcomes[id] = finish(session, rootState, captured.number, executor, replicate)
            return id
        }
        val rootReturns =
            root.proposals.map { actionIndex ->
                val command = root.observation.actions[actionIndex].command
                val ids =
                    (0..3).map { replicate ->
                        executor.check(captured.number)
                        val session =
                            snapshot.fork(CardAdvantageExecutor.chanceSeed(request.seed, replicate))
                        session.apply(mapOf(actor to command), deadlineNanos)
                        val child =
                            if (command == "pass") null
                            else nextChild(session, actor, root.turn, captured.number, executor)
                        if (child == null) {
                            saveOutcome("root-$actionIndex-$replicate", session, replicate)
                        } else {
                            val childState = session.state
                            val childDecisions = session.decisions
                            val prefixHash =
                                CardAdvantageExecutor.hash(session.entries.map(Entry::toText))
                            val childId = "child-$actionIndex-$replicate"
                            val childReturns =
                                child.proposals.map { childAction ->
                                    val branch = session.fork()
                                    check(
                                        branch.state == childState &&
                                            branch.decisions == childDecisions &&
                                            CardAdvantageExecutor.hash(
                                                branch.entries.map(Entry::toText)
                                            ) == prefixHash
                                    ) {
                                        "A child intervention must copy its exact state, command count and chance-stream position."
                                    }
                                    branch.apply(
                                        mapOf(
                                            actor to child.observation.actions[childAction].command
                                        ),
                                        deadlineNanos,
                                    )
                                    CardAdvantageReturns(
                                        childAction,
                                        listOf(
                                            saveOutcome("$childId-$childAction", branch, replicate)
                                        ),
                                    )
                                }
                            check(
                                session.state == childState && session.decisions == childDecisions
                            ) {
                                "Child forks may not mutate their saved parent."
                            }
                            groups.add(
                                CardAdvantageGroup(
                                    id = childId,
                                    kind = "child",
                                    observationSha256 = observe(child.observation),
                                    number = child.number,
                                    botSeed = child.botSeed,
                                    turn = child.turn,
                                    epoch = child.epoch,
                                    proposalIndices = child.proposals,
                                    referenceIndex = child.observation.baselineIndex,
                                    returns = childReturns,
                                    parentActionIndex = actionIndex,
                                    replicate = replicate,
                                    continuationIndex = child.continuationIndex,
                                    prefixCanonicalSha256 = prefixHash,
                                )
                            )
                            // This is the exact already evaluated pi action, not a new or
                            // approximate rollout.
                            childReturns
                                .single { it.actionIndex == child.continuationIndex }
                                .outcomeIds
                                .single()
                        }
                    }
                CardAdvantageReturns(actionIndex, ids)
            }
        groups.add(
            0,
            CardAdvantageGroup(
                id = "root",
                kind = "root",
                observationSha256 = observe(root.observation),
                number = root.number,
                botSeed = root.botSeed,
                turn = root.turn,
                epoch = root.epoch,
                proposalIndices = root.proposals,
                referenceIndex = root.observation.baselineIndex,
                returns = rootReturns,
                continuationIndex = root.continuationIndex,
            ),
        )
        check(
            snapshot.state == rootState && snapshot.decisions == 0 && snapshot.entries.isEmpty()
        ) {
            "Origin forks must leave the real root unchanged."
        }
        check(outcomes.size <= 52 && groups.size <= 13) {
            "Full root/child proposals must respect the predeclared branch cap."
        }
        return identity.copy(
            status = "collected",
            skipReason = null,
            observations = observations.toMap(),
            groups = groups.toList(),
            outcomes = outcomes.toMap(),
        )
    }

    private fun rootPairs(
        identity: CardAdvantageOrigin,
        rootState: GameState,
        actor: PlayerId,
        root: Node,
        snapshot: ExperimentSession,
        executor: CardAdvantageExecutor,
    ): CardAdvantageOrigin {
        val plan = requireNotNull(input.collection) { "Root pairs require their explicit plan." }
        val acquisition =
            CardAdvantageAcquisition.select(
                root.proposals,
                root.observation.baselineIndex,
                root.botSeed,
                sampled = executor.request.split in plan.sampledSplits,
            )
        val observed = acquisition.observedIndices(root.proposals, root.observation.baselineIndex)
        val outcomes = linkedMapOf<String, CardAdvantageOutcome>()
        val returns = observed.map { actionIndex ->
            val ids =
                (0 until plan.replicates).map { replicate ->
                    executor.check(root.number)
                    val branch =
                        snapshot.fork(
                            CardAdvantageExecutor.chanceSeed(executor.request.seed, replicate)
                        )
                    branch.apply(
                        mapOf(actor to root.observation.actions[actionIndex].command),
                        deadlineNanos,
                    )
                    val id = "root-$actionIndex-$replicate"
                    outcomes[id] = finish(branch, rootState, root.number, executor, replicate)
                    id
                }
            CardAdvantageReturns(actionIndex, ids)
        }
        check(
            snapshot.state == rootState && snapshot.decisions == 0 && snapshot.entries.isEmpty()
        ) {
            "Paired root interventions must leave the original state and chance stream unchanged."
        }
        val hash = CardAdvantageExecutor.hash(root.observation)
        return identity.copy(
            status = "collected",
            skipReason = null,
            observations = mapOf(hash to root.observation),
            groups =
                listOf(
                    CardAdvantageGroup(
                        id = "root",
                        kind = "root",
                        observationSha256 = hash,
                        number = root.number,
                        botSeed = root.botSeed,
                        turn = root.turn,
                        epoch = root.epoch,
                        proposalIndices = root.proposals,
                        referenceIndex = root.observation.baselineIndex,
                        returns = returns,
                        continuationIndex = root.continuationIndex,
                        observedActionIndices = observed,
                        acquisition = acquisition,
                    )
                ),
            outcomes = outcomes.toMap(),
        )
    }

    private fun node(
        state: GameState,
        actor: PlayerId,
        number: Int,
        executor: CardAdvantageExecutor,
    ): Node {
        val decision = executor.decision(state, actor, number)
        val baseline = CardAdvantageController.recommendation(decision, input.environment)
        val observation = CardAdvantageController.observation(decision, baseline)
        check(CardAdvantagePolicy.supports(observation) && state.stage == Stage.PLAY_CARDS) {
            "Advantage roots and children require an ordinary normal window."
        }
        val proposals = CardProposals.indices(observation, decision.seed)
        check(proposals.any { observation.actions[it].command == "pass" }) {
            "A normal card proposal set must contain its offered Pass."
        }
        val model = input.continuation?.model
        val selected =
            model?.let { CardAdvantagePolicy.choose(observation, it, decision.seed).index }
                ?: observation.baselineIndex
        check(
            selected in proposals && CardProposals.indices(observation, decision.seed) == proposals
        ) {
            "The frozen continuation must use the exact same versioned proposals and seed."
        }
        return Node(
            observation,
            number,
            decision.seed,
            requireNotNull(state.turnNumber) { "A card node requires a turn." },
            state.currentEpoch.number,
            proposals,
            selected,
        )
    }

    private fun nextChild(
        session: ExperimentSession,
        actor: PlayerId,
        turn: Int,
        rootNumber: Int,
        executor: CardAdvantageExecutor,
    ): Node? {
        while (sameWindow(session.state, actor, turn)) {
            val number = rootNumber + session.decisions
            executor.check(number)
            if (actor in session.actors() && session.observation(actor).decision == "play-cards")
                return node(session.state, actor, number, executor)
            session.apply(executor.commands(session.state, number), deadlineNanos)
        }
        return null
    }

    private fun finish(
        session: ExperimentSession,
        rootState: GameState,
        rootNumber: Int,
        executor: CardAdvantageExecutor,
        replicate: Int,
    ): CardAdvantageOutcome {
        while (session.state.stage != Stage.FINISHED) {
            val number = rootNumber + session.decisions
            executor.check(number)
            session.apply(executor.commands(session.state, number), deadlineNanos)
        }
        val canonical = session.entries.map(Entry::toText)
        val replay =
            canonical.fold(rootState) { state, entry ->
                executor.check(rootNumber + session.decisions)
                state.step(TextCodec.parse(entry))
            }
        check(replay == session.state) {
            "Every complete advantage tail must replay through ordinary authoritative entries."
        }
        val game =
            SimulatedGame(
                input.environment.configuration.id,
                executor.request.seed,
                rootNumber + session.decisions,
                session.state.publicView().scores,
                GameCoverage(
                    emptyMap(),
                    emptyMap(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                    emptySet(),
                ),
                0L,
            )
        return CardAdvantageOutcome(
            game.reward(executor.request.seat),
            game.winShare(executor.request.seat),
            game.rankReward(executor.request.seat),
            game.scores.map(PilotReplayScore::from),
            game.steps,
            CardAdvantageExecutor.chanceSeed(executor.request.seed, replicate),
            CardAdvantageExecutor.hash(canonical),
        )
    }

    companion object {
        internal fun eligible(
            view: GameView,
            observation: BotObservation,
            request: CardAdvantageRequest,
            allowTrivial: Boolean = false,
        ): Boolean {
            val groups =
                observation.candidates
                    .filter { it.family == "play" }
                    .mapNotNull { it.group }
                    .toSet()
            if (observation.candidates.none { it.command == "pass" }) return false
            if (groups.isEmpty()) {
                return allowTrivial && request.cohort == AdvantageCohort.GENERIC.wire
            }
            if (request.cohort == AdvantageCohort.GENERIC.wire) return true
            val leader =
                view.hand.any {
                    it.definition.kind == CardKind.LEADER && "card.${it.reference}" in groups
                }
            if (!leader) return false
            return request.cohort == AdvantageCohort.LEADER.wire ||
                AdvanceId.PRINTED_WORD in
                    view.players.single { it.id.value == observation.playerId }.advances
        }

        internal fun sameWindow(state: GameState, actor: PlayerId, turn: Int): Boolean =
            state.stage == Stage.PLAY_CARDS && state.turnNumber == turn && state.flow.actor == actor
    }
}
