package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy

/** Only the named physical card's play choices are withheld; other information remains intact. */
internal object HeldLeaderObservation {
    fun filter(observation: BotObservation, reference: String): BotObservation? {
        val candidates =
            observation.candidates.filterNot {
                it.family == "play" && it.group == "card.$reference"
            }
        if (candidates.size == observation.candidates.size) return observation
        if (candidates.isEmpty()) return null
        val commands = candidates.map(BotCandidate::command).toSet()
        val planning =
            observation.leaderPlanning?.let { original ->
                val leaders = original.leaders.filterKeys { it in commands }
                if (leaders.isEmpty()) {
                    null
                } else {
                    original.copy(
                        leaders = leaders,
                        cashCards = original.cashCards.intersect(commands),
                    )
                }
            }
        return observation.copy(candidates = candidates, leaderPlanning = planning)
    }
}

/** Stateful receipts belong to one tail; policy inputs remain ordinary actor views/observations. */
internal class HeldLeaderController(
    private val actor: PlayerId,
    private val turn: Int,
    private val reference: String,
    private val ordinary: BotModel,
    private val previous: SimulationController?,
) : SimulationController {
    private var maskedDecisions = 0
    private val forcedReleases = mutableListOf<Map<String, Any>>()

    override fun controls(decision: String): Boolean = true

    override fun choose(decision: SimulationDecision): String {
        val active =
            decision.view.turnNumber == turn && decision.observation.playerId == actor.value
        val filtered =
            if (active) {
                HeldLeaderObservation.filter(decision.observation, reference)
            } else {
                decision.observation
            }
        if (filtered != null && filtered !== decision.observation) maskedDecisions++
        val permitted = decision.copy(observation = filtered ?: decision.observation)
        val command =
            previous?.takeIf { it.controls(permitted.observation.decision) }?.choose(permitted)
                ?: BotPolicy.choose(
                    permitted.observation,
                    BotDifficulty.HARD,
                    permitted.seed,
                    ordinary,
                )
        if (active && filtered == null) {
            forcedReleases.add(
                mapOf(
                    "decision" to decision.number,
                    "turn" to turn,
                    "stage" to decision.view.stage.name,
                    "command" to command,
                    "reason" to "no-other-legal-command",
                )
            )
        }
        return command
    }

    fun receipt(): Map<String, Any> =
        mapOf(
            "maskedDecisions" to maskedDecisions,
            "forcedReleases" to forcedReleases.toList(),
            "reference" to reference,
            "releaseAfterTurn" to turn,
        )
}

/** Two fixed policies and thirty-two paired evaluation streams; no arm selection occurs. */
internal class RecordedLeaderHold(private val input: RecordedPrefixInput) {
    init {
        require(input.requests.size == 7 && input.requests.all { it.proposalSeed == null }) {
            "Leader hold requires seven fixed roots and no proposal RNG."
        }
    }

    fun prepare(): Pair<List<PreparedRecordedRoot>, Map<String, Any>> {
        val (roots, original) = RecordedPrefixDiagnostic(input).prepare(proposalSampling = false)
        require(roots.all { it.state.stage == Stage.PLAY_CARDS }) {
            "Every hold root must be an ordinary leader-play prefix."
        }
        val receipts = roots.map { root ->
            root.receipt.filterKeys {
                it !in setOf("actions", "proposalSeed", "chanceSeeds", "policySeeds")
            } +
                mapOf(
                    "policies" to listOf("recorded-play", "hold-original-leader-current-turn"),
                    "chanceSeeds" to root.request.chanceSeeds,
                    "policySeeds" to root.request.policySeeds,
                    "holdFirstCommands" to
                        (0..31).map { replicate ->
                            val session =
                                ExperimentSession(
                                    root.state,
                                    root.request.chanceSeeds[replicate].toLong(),
                                )
                            val continuation =
                                continuation(
                                        root,
                                        replicate,
                                        hold = true,
                                        deadline = Long.MAX_VALUE,
                                    )
                                    .first
                            requireNotNull(
                                continuation
                                    .commands(
                                        session,
                                        mapOf(root.actor to session.observation(root.actor)),
                                    )[root.actor]
                            ) {
                                "A prepared hold root requires its actor-visible first command."
                            }
                        },
                )
        }
        return roots to
            original.filterKeys {
                it !in
                    setOf(
                        "kind",
                        "proposalVersion",
                        "selectionReplicates",
                        "evaluationReplicates",
                        "maxWorkers",
                        "maxSeconds",
                        "totalTails",
                        "roots",
                    )
            } +
                mapOf(
                    "kind" to "recorded-leader-hold-diagnostic-1",
                    "selectionPerformed" to false,
                    "evaluationReplicates" to (0..31).toList(),
                    "maxWorkers" to 3,
                    "maxSeconds" to 3600,
                    "totalTails" to 448,
                    "roots" to receipts,
                    "withholdPlayCommandsOnly" to true,
                    "forcedLegalReleasesRetained" to true,
                    "discardTradeAndChanceUnchanged" to true,
                )
    }

    fun replicate(
        root: PreparedRecordedRoot,
        replicate: Int,
        deadline: Long,
    ): List<RecordedHeldLeaderTail> {
        val chance = root.request.chanceSeeds[replicate].toLong()
        return listOf(false, true).mapIndexed { action, hold ->
            val session = ExperimentSession(root.state, chance, recordEntries = true)
            val (continuation, controller) = continuation(root, replicate, hold, deadline)
            val command =
                if (hold) {
                    requireNotNull(
                        continuation
                            .commands(
                                session,
                                mapOf(root.actor to session.observation(root.actor)),
                            )[root.actor]
                    ) {
                        "Hold policy must choose its root command."
                    }
                } else {
                    root.commands.single()
                }
            session.apply(mapOf(root.actor to command), deadline)
            val terminal =
                continuation.finish(
                    session,
                    root.state.playerIds.indexOf(root.actor),
                    input.arena.configurations.single().id,
                    chance,
                )
            val economics =
                RecordedPrefixReplay.verify(
                    root.state,
                    root.actor,
                    session.entries,
                    session.state,
                    deadline,
                )
            RecordedHeldLeaderTail(
                root.request.entry,
                action,
                if (hold) "hold-original-leader-current-turn" else "recorded-play",
                command,
                replicate,
                "evaluation",
                root.request.policySeeds[replicate],
                terminal,
                session.entries.map(TextCodec::print),
                economics,
                controller?.receipt(),
                replayVerified = true,
            )
        }
    }

    private fun continuation(
        root: PreparedRecordedRoot,
        replicate: Int,
        hold: Boolean,
        deadline: Long,
    ): Pair<FrozenPolicyContinuation, HeldLeaderController?> {
        val seat = root.state.playerIds.indexOf(root.actor)
        val count = root.state.playerIds.size
        val controllers =
            input.arena.controllers(
                count,
                seat,
                ArenaSide.CANDIDATE,
                input.arena.candidate,
                List(count) { 0 },
            )
        val controller =
            if (hold) {
                HeldLeaderController(
                    root.actor,
                    requireNotNull(root.state.turnNumber) { "Leader hold requires a live turn." },
                    root.commands.single().split(' ')[1],
                    input.arena.candidate,
                    controllers[seat],
                )
            } else {
                null
            }
        return FrozenPolicyContinuation(
            List(count) { input.arena.candidate },
            if (controller == null) controllers else controllers + (seat to controller),
            root.request.policySeeds[replicate],
            deadline,
        ) to controller
    }
}

internal data class RecordedHeldLeaderTail(
    val entry: Int,
    val action: Int,
    val policy: String,
    val command: String,
    val replicate: Int,
    val split: String,
    val policySeed: Int,
    val terminal: TokenValueOutcome,
    val entries: List<String>,
    val economics: Map<String, Any?>,
    val hold: Map<String, Any>?,
    val replayVerified: Boolean,
)
