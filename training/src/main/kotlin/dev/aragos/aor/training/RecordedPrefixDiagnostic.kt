package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotDifficulty
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.BotPolicy
import java.security.MessageDigest
import kotlin.random.Random

/** Public-action diversity only: physical card/province groups never consult simulated outcomes. */
internal object RecordedPrefixProposals {
    const val VERSION = "recorded-prefix-public-groups-1"

    fun commands(
        observation: BotObservation,
        recorded: String,
        current: String,
        seed: Int,
    ): List<String> {
        val offered = observation.candidates.associateBy(BotCandidate::command)
        require(recorded in offered && current in offered) {
            "Recorded and current choices must be offered."
        }
        val chosen =
            listOf(recorded, current, "pass").filter { it in offered }.distinct().toMutableList()
        fun group(candidate: BotCandidate): String = candidate.group.ifBlank { candidate.command }
        val random = Random(seed)
        val remaining =
            observation.candidates.sortedBy(BotCandidate::command).shuffled(random).toMutableList()
        while (chosen.size < 4) {
            val selected = chosen.map {
                requireNotNull(offered[it]) { "Selected commands remain offered." }
            }
            val groups = selected.map(::group).toSet()
            val families = selected.map(BotCandidate::family).toSet()
            val eligible = remaining.filter { group(it) !in groups && it.command !in chosen }
            val next =
                eligible.firstOrNull { it.family !in families } ?: eligible.firstOrNull() ?: break
            chosen.add(next.command)
            remaining.remove(next)
        }
        return chosen.toList()
    }
}

/** State is private executor data; only the receipt is persisted during preparation. */
internal data class PreparedRecordedRoot(
    val request: RecordedPrefixRequest,
    val state: GameState,
    val actor: PlayerId,
    val commands: List<String>,
    val receipt: Map<String, Any?>,
)

/**
 * Realized-world diagnostic, deliberately not an information-safe online teacher or strength test.
 */
internal class RecordedPrefixDiagnostic(private val input: RecordedPrefixInput) {
    fun prepare(): Pair<List<PreparedRecordedRoot>, Map<String, Any>> {
        val (states, terminal) = input.states()
        val roots =
            input.requests.map { request ->
                val state =
                    requireNotNull(states[request.entry]) { "A requested prefix must exist." }
                val session = ExperimentSession(state, 0)
                val actor = session.actors().single()
                val observation = session.observation(actor)
                val row =
                    requireNotNull(input.observations[request.entry]) {
                        "Root lacks a sanitized observation."
                    }
                require(
                    row["seedReconstructible"]?.asBoolean() == true &&
                        row["player"]?.asText() == actor.value &&
                        row["stage"]?.asText() == state.stage.name &&
                        row["turn"]?.asInt() == state.turnNumber &&
                        row["observation"] == PurchaseJson.mapper.valueToTree<JsonNode>(observation)
                ) {
                    "Recorded root ${request.entry} must exactly regenerate its sanitized actor observation."
                }
                val recorded = PurchaseJson.text(row.required("recordedCommand"), "recordedCommand")
                val view = state.viewForPlayer(actor)
                require(
                    input.entries[request.entry] == "${actor.value}: $recorded" &&
                        (state.stage == Stage.EXPANSION && recorded == "pass" ||
                            state.stage == Stage.PLAY_CARDS &&
                                recorded.startsWith("play ") &&
                                view.cardOffers.any {
                                    it.reference == recorded.split(' ')[1] &&
                                        it.leaderDiscounts.isNotEmpty()
                                })
                ) {
                    "Roots must be the predeclared recorded expansion passes or ordinary leader plays."
                }
                val seat = state.playerIds.indexOf(actor)
                val seed = PurchaseJson.integer(row.required("policySeed"), "policySeed")
                require(
                    TrainingConfiguration(state.playerIds.size, state.rules) ==
                        input.arena.configurations.single()
                ) {
                    "The replayed root must use the frozen continuation configuration."
                }
                val decision = SimulationDecision(0, seat, seed, view, observation, 0)
                val current =
                    controllers(seat)[seat]
                        ?.takeIf { it.controls(observation.decision) }
                        ?.choose(decision)
                        ?: BotPolicy.choose(
                            observation,
                            BotDifficulty.HARD,
                            seed,
                            input.arena.candidate,
                        )
                val commands =
                    RecordedPrefixProposals.commands(
                        observation,
                        recorded,
                        current,
                        request.proposalSeed,
                    )
                val receipt =
                    mapOf(
                        "entry" to request.entry,
                        "actor" to actor.value,
                        "turn" to state.turnNumber,
                        "stage" to state.stage.name,
                        "recordedCommand" to recorded,
                        "currentCommand" to current,
                        "policySeed" to seed,
                        "proposalSeed" to request.proposalSeed,
                        "prefixSha256" to hash(input.entries.take(request.entry)),
                        "observationSha256" to hash(observation),
                        "observationParity" to true,
                        "actions" to
                            commands.map { command ->
                                val candidate =
                                    observation.candidates.single { it.command == command }
                                mapOf(
                                    "command" to command,
                                    "family" to candidate.family,
                                    "group" to candidate.group,
                                )
                            },
                        "chanceSeeds" to request.chanceSeeds,
                        "policySeeds" to request.policySeeds,
                        "recordedReferenceEconomics" to
                            RecordedPrefixReplay.verify(
                                state,
                                actor,
                                input.entries.drop(request.entry).map(TextCodec::parse),
                                terminal,
                                Long.MAX_VALUE,
                            ),
                    )
                PreparedRecordedRoot(request, state, actor, commands, receipt)
            }
        return roots to
            mapOf(
                "schemaVersion" to 1,
                "kind" to "hidden-world-specific-recorded-prefix-diagnostic",
                "sourceSha256" to input.sourceSha256,
                "originalSha256" to input.originalSha256,
                "historySha256" to input.historySha256,
                "observationsSha256" to input.observationsSha256,
                "arenaIdentity" to input.arena.identity(),
                "overlayMetadata" to input.arena.overlayMetadata(),
                "rulesVersion" to input.header.rulesVersion,
                "dataVersion" to input.header.dataVersion,
                "proposalVersion" to RecordedPrefixProposals.VERSION,
                "effectiveEntries" to input.entries.size,
                "terminalScores" to terminal.publicView().scores,
                "terminalReplayVerified" to true,
                "selectionReplicates" to (0..15).toList(),
                "evaluationReplicates" to (16..31).toList(),
                "allSeatsAutomataAfterPrefix" to true,
                "maxWorkers" to 6,
                "maxSeconds" to 5400,
                "totalTails" to roots.sumOf { it.commands.size * 32 },
                "roots" to roots.map { it.receipt },
            )
    }

    /** All arms share one future chance/policy stream; no action is selected here. */
    fun replicate(
        root: PreparedRecordedRoot,
        replicate: Int,
        deadlineNanos: Long,
    ): List<RecordedPrefixTail> {
        require(replicate in 0..31) {
            "A recorded replicate must belong to its fixed 32-stream bank."
        }
        val seat = root.state.playerIds.indexOf(root.actor)
        val chanceSeed = root.request.chanceSeeds[replicate].toLong()
        val policySeed = root.request.policySeeds[replicate]
        return root.commands.mapIndexed { index, command ->
            val session = ExperimentSession(root.state, chanceSeed, recordEntries = true)
            session.apply(mapOf(root.actor to command), deadlineNanos)
            val continuation =
                FrozenPolicyContinuation(
                    List(root.state.playerIds.size) { input.arena.candidate },
                    controllers(seat),
                    policySeed,
                    deadlineNanos,
                )
            val result =
                continuation.finish(
                    session,
                    seat,
                    input.arena.configurations.single().id,
                    chanceSeed,
                )
            checkTokenValueDeadline(deadlineNanos)
            val economics =
                RecordedPrefixReplay.verify(
                    root.state,
                    root.actor,
                    session.entries,
                    session.state,
                    deadlineNanos,
                )
            RecordedPrefixTail(
                root.request.entry,
                index,
                command,
                replicate,
                if (replicate < 16) "selection" else "evaluation",
                policySeed,
                result,
                session.entries.map(TextCodec::print),
                economics,
            )
        }
    }

    private fun controllers(seat: Int): Map<Int, SimulationController> =
        input.arena.controllers(
            input.header.players.size,
            seat,
            ArenaSide.CANDIDATE,
            input.arena.candidate,
            List(input.header.players.size) { 0 },
        )

    companion object {
        fun hash(value: Any): String =
            MessageDigest.getInstance("SHA-256")
                .digest(PurchaseJson.mapper.writeValueAsBytes(value))
                .joinToString("") { "%02x".format(it) }
    }
}

internal data class RecordedPrefixTail(
    val entry: Int,
    val action: Int,
    val command: String,
    val replicate: Int,
    val split: String,
    val policySeed: Int,
    val terminal: TokenValueOutcome,
    val entries: List<String>,
    val economics: Map<String, Any?>,
    val replayVerified: Boolean = true,
)
