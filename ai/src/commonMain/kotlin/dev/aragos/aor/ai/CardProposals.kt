package dev.aragos.aor.ai

import dev.aragos.aor.CardCommand
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/**
 * Versioned exploration coverage shared by collection and deployed inference. Proposals contain the
 * explicit qualified F action, an offered pass, and one offered command from each of up to two
 * other uniformly sampled physical cards. A card's target/protection alternatives do not increase
 * its chance of being sampled. The F card's other commands are not separate proposals.
 *
 * References come from canonical commands, never stable card-definition feature names. Returned
 * indices address the complete, canonically sorted [CardObservation.actions]; F need not be first.
 * This function never invents a command, a pass, or a multi-action commitment.
 */
object CardProposals {
    const val VERSION = "aor-card-proposals-1"

    /** Separate proposal randomness from both the actor sampler and hidden chance outcomes. */
    fun seed(botSeed: Int): Int = botSeed xor 0x510e527f

    /**
     * [botSeed] is the ordinary decision seed, before any actor/proposal salt. Neither that seed
     * nor the sampled proposal indices are learned features. Sampling is deterministic across JVM,
     * JS and the independent numerical implementation.
     */
    fun indices(observation: CardObservation, botSeed: Int): List<Int> {
        val references = observation.actions.map { action -> reference(action.command) }
        val baselineReference = references[observation.baselineIndex]
        val groups =
            references.indices
                .filter { references[it] != null && references[it] != baselineReference }
                .groupBy { checkNotNull(references[it]) { "A card group requires a reference." } }
        val remaining = groups.keys.sorted().toMutableList()
        val random = CardProposalRandom(seed(botSeed))
        val sampled = buildList {
            for (position in 0 until minOf(2, remaining.size)) {
                val selected = position + random.index(remaining.size - position)
                val reference = remaining[selected]
                remaining[selected] = remaining[position]
                remaining[position] = reference
                val commands = checkNotNull(groups[reference]) { "A sampled card must be offered." }
                add(commands[random.index(commands.size)])
            }
        }
        val pass = observation.actions.indexOfFirst { it.command == "pass" }
        return (listOf(observation.baselineIndex) +
                listOfNotNull(pass.takeIf { it >= 0 }) +
                sampled)
            .distinct()
            .sorted()
    }

    private fun reference(command: String): String? =
        when (val parsed = TextCodec.parseCommand(command)) {
            is CardCommand.PlayCard -> parsed.reference
            TurnCommand.Pass -> null
            else ->
                throw IllegalArgumentException(
                    "Card proposals require offered play or pass commands."
                )
        }
}

/**
 * A counter stream followed by the existing 32-bit Murmur finalizer. Each draw first increments by
 * 0x9e3779b9 modulo 2^32. Unsigned rejection sampling avoids modulo bias; a singleton draw still
 * consumes a word. Group and within-group draws are interleaved in selection order.
 */
internal class CardProposalRandom(private var state: Int) {
    fun uniform(): Double = word().toDouble() / 4294967296.0

    fun index(bound: Int): Int {
        require(bound in 1..1024) { "A card proposal draw requires one through 1024 choices." }
        val limit = 4294967296L - 4294967296L % bound
        while (true) {
            val unsigned = word()
            if (unsigned < limit) return (unsigned % bound).toInt()
        }
    }

    private fun word(): Long {
        state += -1640531527
        var mixed = state
        mixed = (mixed xor (mixed ushr 16)) * -2048144789
        mixed = (mixed xor (mixed ushr 13)) * -1028477387
        mixed = mixed xor (mixed ushr 16)
        return mixed.toLong() and 0xffffffffL
    }
}
