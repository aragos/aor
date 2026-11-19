package dev.aragos.aor.training

import dev.aragos.aor.PlayerId
import dev.aragos.aor.SystemNeed
import kotlin.random.Random

internal enum class ExperimentChanceMode {
    SEQUENTIAL,
    BY_TURN_AND_EVENT,
}

/** Independent experiment streams for corresponding events within different simulated futures. */
internal class ExperimentChance
private constructor(
    private val seed: Long,
    val mode: ExperimentChanceMode,
    private val sequential: ExperimentRandom,
    private val streams: MutableMap<Channel, ExperimentRandom>?,
) {
    constructor(
        seed: Long,
        mode: ExperimentChanceMode = ExperimentChanceMode.SEQUENTIAL,
    ) : this(
        seed,
        mode,
        ExperimentRandom(seed),
        if (mode == ExperimentChanceMode.BY_TURN_AND_EVENT) mutableMapOf() else null,
    )

    private data class Channel(
        val occurrence: Int,
        val name: String,
        val participants: List<PlayerId> = emptyList(),
    ) {
        /** Stable throughout independent JVMs. Participant lengths delimit the actor tuple. */
        fun seed(root: Long): Long {
            var hash = (root xor occurrence.toLong()) * 1099511628211L
            for (character in name) {
                hash = (hash xor character.code.toLong()) * 1099511628211L
            }
            for (participant in participants) {
                hash = (hash xor participant.value.length.toLong()) * 1099511628211L
                for (character in participant.value) {
                    hash = (hash xor character.code.toLong()) * 1099511628211L
                }
            }
            return hash
        }
    }

    fun forNeed(need: SystemNeed, turn: Int?, epoch: Int): Random {
        val split = streams ?: return sequential
        val channel =
            when (need) {
                is SystemNeed.Shuffle -> Channel(epoch, "shuffle." + need.purpose.name)
                is SystemNeed.GameRoll -> Channel(turn ?: 0, "roll." + need.kind.name, need.players)
                is SystemNeed.Trade ->
                    Channel(turn ?: 0, "trade", listOf(need.owner, need.recipient))
                is SystemNeed.Draw -> error("A recorded draw already identifies its card.")
            }
        return split.getOrPut(channel) { ExperimentRandom(channel.seed(seed)) }
    }

    fun copy(): ExperimentChance =
        ExperimentChance(
            seed,
            mode,
            sequential.copy(),
            streams?.mapValuesTo(mutableMapOf()) { (_, stream) -> stream.copy() },
        )
}

/** SplitMix64 state is copied explicitly, so branches never share a mutable RNG. */
private class ExperimentRandom(private var position: Long) : Random() {
    fun copy(): ExperimentRandom = ExperimentRandom(position)

    override fun nextBits(bitCount: Int): Int {
        require(bitCount in 0..32) { "Random bit count must be between zero and 32." }
        if (bitCount == 0) return 0
        position += -7046029254386353131L
        var mixed = position
        mixed = (mixed xor (mixed ushr 30)) * -4658895280553007687L
        mixed = (mixed xor (mixed ushr 27)) * -7723592293110705685L
        mixed = mixed xor (mixed ushr 31)
        return (mixed ushr (64 - bitCount)).toInt()
    }
}
