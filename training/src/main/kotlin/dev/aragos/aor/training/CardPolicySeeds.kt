package dev.aragos.aor.training

import dev.aragos.aor.Stage
import java.security.MessageDigest

/** Primitive public decision context; authority state and selected commands are never inputs. */
internal fun interface CardPolicySeedProvider {
    fun seed(
        number: Int,
        batchStart: Int,
        turn: Int,
        stage: Stage,
        decision: String,
        seat: Int,
    ): Int
}

/** L preserves historical seeds; G/S compare global and semantic random-table addresses. */
internal enum class CardPolicySchedule {
    L,
    G,
    S,
}

/**
 * Executor provenance only. A forced root records its actual legacy seed and consumes occurrence
 * zero without using the selected schedule to choose a command. No private facts are exported.
 */
internal data class CardPolicySeedTrace(
    val number: Int,
    val batchStart: Int,
    val turn: Int,
    val stage: String,
    val decision: String,
    val seat: Int,
    val occurrence: Int,
    val seed: Int,
    val forced: Boolean,
) {
    init {
        require(number in 0..11999 && batchStart in 0..number && number - batchStart <= 3) {
            "A policy trace requires bounded absolute command and common batch indices."
        }
        require(turn >= 1 && Stage.entries.any { it.name == stage } && seat in 0..3) {
            "A policy trace requires an actual turn, stage and registered seat."
        }
        require(decision.matches(Regex("[a-z][a-z-]{0,63}")) && occurrence in 0..11999) {
            "A policy trace requires a canonical decision name and bounded local occurrence."
        }
    }
}

/**
 * One tail owns its counters and trace. Occurrence counts are since the fork within each (turn,
 * stage, decision, registration seat) bucket; the forced root reserves the first slot. The master
 * and SHA-256 address are independent of authority random-word consumption. Hashed schedules change
 * historical seeded trajectories; they are diagnostic sampling contracts.
 */
internal class CardPolicySeeds(
    private val worldSeed: Int,
    private val replicate: Int,
    private val schedule: CardPolicySchedule,
) : CardPolicySeedProvider {
    private data class Bucket(val turn: Int, val stage: Stage, val decision: String, val seat: Int)

    private val counters = mutableMapOf<Bucket, Int>()
    private val records = mutableListOf<CardPolicySeedTrace>()
    private val master = master(worldSeed, replicate)

    val trace: List<CardPolicySeedTrace>
        get() = records.toList()

    fun reserveRoot(number: Int, turn: Int, seat: Int) {
        require(records.isEmpty()) { "A tail must reserve its forced root exactly once, first." }
        append(number, number, turn, Stage.PLAY_CARDS, "play-cards", seat, forced = true)
    }

    override fun seed(
        number: Int,
        batchStart: Int,
        turn: Int,
        stage: Stage,
        decision: String,
        seat: Int,
    ): Int {
        require(records.isNotEmpty()) { "A diagnostic tail must reserve its forced root first." }
        return append(number, batchStart, turn, stage, decision, seat, forced = false).seed
    }

    private fun append(
        number: Int,
        batchStart: Int,
        turn: Int,
        stage: Stage,
        decision: String,
        seat: Int,
        forced: Boolean,
    ): CardPolicySeedTrace {
        val previous = records.lastOrNull()
        require(previous == null || number == previous.number + 1) {
            "Policy trace commands must be contiguous, including every member of a sealed batch."
        }
        require(
            previous == null ||
                batchStart == number ||
                batchStart == previous.batchStart &&
                    turn == previous.turn &&
                    stage.name == previous.stage &&
                    decision == previous.decision &&
                    records.takeLast(number - batchStart).none { it.seat == seat }
        ) {
            "A sealed policy batch must retain its pre-state context and distinct actors."
        }
        val bucket = Bucket(turn, stage, decision, seat)
        val occurrence = counters[bucket] ?: 0
        val seed =
            if (forced || schedule == CardPolicySchedule.L) {
                CardAdvantageExecutor.policySeed(worldSeed, batchStart, seat)
            } else {
                hashedSeed(
                    master,
                    schedule,
                    batchStart,
                    turn,
                    stage.name,
                    decision,
                    seat,
                    occurrence,
                )
            }
        val record =
            CardPolicySeedTrace(
                number,
                batchStart,
                turn,
                stage.name,
                decision,
                seat,
                occurrence,
                seed,
                forced,
            )
        counters[bucket] = occurrence + 1
        records.add(record)
        return record
    }

    companion object {
        const val VERSION = "policy-seed-coupling-1"
        const val MASTER_DOMAIN = "aor-policy-coupling-master-1\n"
        const val SEED_DOMAIN = "aor-policy-coupling-seed-1\n"

        fun chanceSeed(worldSeed: Int, replicate: Int): Long {
            require(replicate in 0..7) { "A coupling diagnostic requires eight declared streams." }
            return (worldSeed.toLong() shl 4) xor 0x37a51b20dL xor replicate.toLong()
        }

        fun master(worldSeed: Int, replicate: Int): ByteArray {
            require(replicate in 0..7) {
                "A policy master requires a declared replicate zero to seven."
            }
            return digest("$MASTER_DOMAIN$worldSeed\n$replicate\n".toByteArray(Charsets.UTF_8))
        }

        fun hashedSeed(
            master: ByteArray,
            schedule: CardPolicySchedule,
            batchStart: Int,
            turn: Int,
            stage: String,
            decision: String,
            seat: Int,
            occurrence: Int,
        ): Int {
            require(master.size == 32 && schedule != CardPolicySchedule.L) {
                "A hashed policy seed requires its SHA-256 master and a hashed schedule."
            }
            val key =
                when (schedule) {
                    CardPolicySchedule.G -> "G\n$batchStart\n$seat\n"
                    CardPolicySchedule.S -> "S\n$turn\n$stage\n$decision\n$seat\n$occurrence\n"
                    CardPolicySchedule.L -> error("Legacy seeds do not use a hashed address.")
                }
            val hash =
                digest(
                    master +
                        SEED_DOMAIN.toByteArray(Charsets.UTF_8) +
                        key.toByteArray(Charsets.UTF_8)
                )
            return ((hash[0].toInt() and 255) shl 24) or
                ((hash[1].toInt() and 255) shl 16) or
                ((hash[2].toInt() and 255) shl 8) or
                (hash[3].toInt() and 255)
        }

        private fun digest(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
