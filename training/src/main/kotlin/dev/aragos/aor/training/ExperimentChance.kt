package dev.aragos.aor.training

import dev.aragos.aor.BoardDecision
import dev.aragos.aor.CardDecision
import dev.aragos.aor.Entry
import dev.aragos.aor.GameState
import dev.aragos.aor.SystemNeed
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.random.Random

/** Offline suffix-only sampling contracts; neither changes production authority randomness. */
internal enum class ExperimentChanceMode {
    LEGACY,
    LOGICAL_REQUEST_1,
}

/**
 * Private random-table address. Fields mirror logical request context, not full state/history.
 * Length-prefixed UTF-8 strings preserve physical identities without delimiter ambiguities.
 */
internal data class ExperimentChanceRequest(val fields: List<String>) {
    val bytes: ByteArray
        get() =
            fields.fold(ByteArray(0)) { result, field ->
                val encoded = field.toByteArray(Charsets.UTF_8)
                result + ByteBuffer.allocate(4).putInt(encoded.size).array() + encoded
            }

    val sha256: String
        get() = digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION = "experimental-logical-chance-request-1"

        fun from(state: GameState, need: SystemNeed): ExperimentChanceRequest =
            ExperimentChanceRequest(
                buildList {
                    add(VERSION)
                    add(state.turnNumber?.toString().orEmpty())
                    add(state.currentEpoch.number.toString())
                    add(state.stage.name)
                    add(state.flow.actor?.value.orEmpty())
                    add(state.flow.shortageRolls.toString())
                    when (need) {
                        is SystemNeed.Shuffle -> {
                            add("shuffle")
                            add(need.purpose.name)
                            add(need.cards.size.toString())
                            addAll(need.cards.sorted())
                        }
                        is SystemNeed.GameRoll -> {
                            add("roll")
                            add(need.kind.name)
                            add(need.diceCount.toString())
                            add(need.players.size.toString())
                            addAll(need.players.map { it.value })
                        }
                        is SystemNeed.Trade -> {
                            add("trade")
                            add(need.owner.value)
                            add(need.recipient.value)
                        }
                        is SystemNeed.Draw -> error("Fixed deck draws do not query random tables.")
                    }
                    when (val decision = state.flow.decisions.lastOrNull()) {
                        is BoardDecision.CompetitionDice -> {
                            add("competition")
                            add(decision.plan.player.value)
                            add(decision.plan.destination.value)
                            add(decision.plan.defenders.size.toString())
                            addAll(decision.plan.defenders.map { it.value })
                        }
                        is BoardDecision.WindmillDice -> {
                            add("windmill")
                            add(decision.winner.value)
                            add(decision.victim.value)
                            add(decision.marketNumber.toString())
                        }
                        is BoardDecision.RandomTrade -> {
                            add("random-trade")
                            add(decision.owner.value)
                            add(decision.recipient.value)
                            add(decision.offeredReference)
                        }
                        is CardDecision.WarRoll -> {
                            add("war")
                            add(decision.war.initiator.value)
                            add(decision.war.opponent.value)
                            add(decision.war.startedTurn.toString())
                            add(decision.war.ties.toString())
                            val round = decision.war.lastRound
                            add(round?.turn?.toString().orEmpty())
                            add(round?.finalCardPlay?.toString().orEmpty())
                            add(round?.actor?.value.orEmpty())
                        }
                        else -> add("none")
                    }
                }
            )

        fun digest(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}

/** Executor-only trace. It is never passed to a controller or observation encoder. */
internal data class ExperimentChanceRecord(
    val requestSha256: String,
    val occurrence: Int,
    val outcome: String,
)

/**
 * One suffix owns its counters. Preparing a request consumes nothing until authority accepts it.
 * Forks copy counters/traces; replacing the master changes future draws, not the copied world.
 */
internal class ExperimentChance(
    val mode: ExperimentChanceMode,
    private val master: Long,
    private val occurrences: MutableMap<ExperimentChanceRequest, Int> = mutableMapOf(),
    private val records: MutableList<ExperimentChanceRecord> = mutableListOf(),
    private val recordTrace: Boolean = true,
) {
    private var acceptedRequests = occurrences.values.sum()

    data class Pending(
        val request: ExperimentChanceRequest,
        val occurrence: Int,
        val random: Random,
    )

    val trace: List<ExperimentChanceRecord>
        get() = records.toList()

    fun copy(seed: Long? = null): ExperimentChance =
        ExperimentChance(
            mode,
            seed ?: master,
            occurrences.toMutableMap(),
            records.toMutableList(),
            recordTrace,
        )

    fun prepare(state: GameState, need: SystemNeed, legacy: Random): Pending {
        check(acceptedRequests < 100000) {
            "A diagnostic suffix exceeded its chance-request limit."
        }
        val request = ExperimentChanceRequest.from(state, need)
        val occurrence = occurrences[request] ?: 0
        val random =
            if (mode == ExperimentChanceMode.LEGACY) legacy
            else {
                val domain = "aor-experimental-chance-table-1\n".toByteArray(Charsets.UTF_8)
                val key =
                    domain +
                        ByteBuffer.allocate(8).putLong(master).array() +
                        request.bytes +
                        ByteBuffer.allocate(4).putInt(occurrence).array()
                ExperimentRandom(ByteBuffer.wrap(ExperimentChanceRequest.digest(key)).long)
            }
        return Pending(request, occurrence, random)
    }

    fun accepted(pending: Pending, outcome: Entry) {
        check((occurrences[pending.request] ?: 0) == pending.occurrence) {
            "An authority request must be accepted once in its original counter position."
        }
        occurrences[pending.request] = pending.occurrence + 1
        acceptedRequests++
        if (recordTrace) {
            records.add(
                ExperimentChanceRecord(pending.request.sha256, pending.occurrence, outcome.toText())
            )
        }
    }
}
