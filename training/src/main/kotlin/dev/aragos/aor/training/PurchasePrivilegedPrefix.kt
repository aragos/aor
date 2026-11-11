package dev.aragos.aor.training

import com.fasterxml.jackson.databind.JsonNode
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.BotObservations
import dev.aragos.aor.ai.PurchaseObservation
import dev.aragos.aor.ai.PurchaseObservationEncoder

/**
 * Training-only current information, separate from actor inputs, identities and terminal labels.
 */
internal data class PurchasePrivilegedFeatures(
    val stateFeatures: Map<String, Double>,
    /** Raw card counts; role 1 is the next registered seat cyclically after the actor. */
    val opponentHandCounts: Map<String, Int>,
)

/** A replay boundary receipt; entry/action indices are provenance and never critic features. */
internal data class PurchasePrivilegedBoundary(
    val number: Int,
    val entryIndex: Int,
    val turn: Int,
    val decision: String,
    val features: PurchasePrivilegedFeatures,
)

/**
 * Same canonical-prefix/project pattern as PurchaseBoundary, without its later-turn restriction. No
 * controller, sampler, baseline choice or simulated continuation is called. Baseline parity means
 * binding the recorded legal baseline command, not recomputing a policy recommendation.
 */
internal object PurchasePrivilegedPrefix {
    fun observation(state: GameState, actor: PlayerId, baseline: String): PurchaseObservation =
        PurchaseObservationEncoder.project(
                state.viewForPlayer(actor),
                actor,
                BotObservations.from(state, actor),
            )
            .bindBaseline(baseline)

    fun features(
        state: GameState,
        actor: PlayerId,
        observation: PurchaseObservation,
    ): PurchasePrivilegedFeatures {
        val players = state.header.players
        val seat = players.indexOfFirst { it.id == actor }
        require(seat >= 0) { "Privileged extraction requires a registered actor." }
        val counts =
            (1 until players.size)
                .flatMap { role ->
                    state
                        .playerState(players[(seat + role) % players.size].id)
                        .hand
                        .groupingBy { it.definition.id }
                        .eachCount()
                        .map { (card, count) -> "role.$role.card.$card" to count }
                }
                .toMap()
                .toSortedMap()
        return PurchasePrivilegedFeatures(observation.stateFeatures, counts)
    }

    /**
     * Replay only through the last requested pre-action boundary. Later entries are not parsed.
     * Configuration/start actions precede the simulation's zero-based player-decision clock.
     */
    fun extract(
        initial: GameState,
        actor: PlayerId,
        entries: Iterable<String>,
        recorded: List<JsonNode>,
        deadlineNanos: Long = Long.MAX_VALUE,
    ): List<PurchasePrivilegedBoundary> {
        require(recorded.isNotEmpty()) { "The frozen history must contain purchase decisions." }
        val numbers = recorded.map { it.required("number").asInt() }
        require(numbers.first() >= 0 && numbers.zipWithNext().all { (a, b) -> a < b }) {
            "Recorded purchases must retain strictly increasing decision numbers."
        }
        val result = mutableListOf<PurchasePrivilegedBoundary>()
        var state = initial
        var number = 0
        for ((entryIndex, text) in entries.withIndex()) {
            checkTokenValueDeadline(deadlineNanos)
            val entry = TextCodec.parse(text)
            require(entry.toText() == text) { "Prefix entries must retain canonical text." }
            if (entry is Entry.Action && state.stage != Stage.CONFIGURATION) {
                val expected = recorded[result.size]
                if (number == expected.required("number").asInt()) {
                    result.add(capture(state, actor, entry, expected, number, entryIndex))
                    if (result.size == recorded.size) return result.toList()
                } else if (entry.actor == actor && state.stage == Stage.PURCHASE) {
                    val choice = BotObservations.from(state, actor).decision
                    require(choice !in setOf("purchase-shipping", "purchase-advances")) {
                        "Canonical history contains an unrecorded controlled purchase."
                    }
                }
                number++
            }
            state = state.step(entry)
        }
        error("Canonical history ended before every frozen purchase boundary was captured.")
    }

    private fun capture(
        state: GameState,
        actor: PlayerId,
        entry: Entry.Action,
        recorded: JsonNode,
        number: Int,
        entryIndex: Int,
    ): PurchasePrivilegedBoundary {
        val prompt = state.prompts().singleOrNull()
        require(entry.actor == actor && prompt is Prompt.SinglePlayer && prompt.player == actor) {
            "A recorded purchase must belong to the current ordinary actor."
        }
        val original = recorded.required("observation")
        val baselineIndex = original.required("baselineIndex").asInt()
        val actions = original.required("actions")
        require(baselineIndex in 0 until actions.size()) {
            "Recorded purchase baseline must name one original offered command."
        }
        val baseline = actions[baselineIndex].required("command").asText()
        val projected = observation(state, actor, baseline)
        require(PurchaseJson.mapper.valueToTree<JsonNode>(projected) == original) {
            "Replayed actor state, actions or recorded baseline differs from the original."
        }
        val selected = recorded.required("selectedIndex").asInt()
        require(
            selected in projected.actions.indices &&
                projected.actions[selected].command == entry.command.toText()
        ) {
            "The canonical action differs from the original selected offered command."
        }
        val decision = BotObservations.from(state, actor).decision
        val turn = requireNotNull(state.turnNumber) { "A purchase requires its current turn." }
        require(
            recorded.required("decision").asText() == decision &&
                recorded.required("turn").asInt() == turn
        ) {
            "Replayed purchase phase or turn differs from the original boundary."
        }
        return PurchasePrivilegedBoundary(
            number,
            entryIndex,
            turn,
            decision,
            features(state, actor, projected),
        )
    }
}
