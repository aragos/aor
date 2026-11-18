package dev.aragos.aor.training

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.TextCodec
import dev.aragos.aor.ai.TerritoryAction
import kotlin.random.Random

/**
 * Fixed whole-origin partition; neither missing windows nor action siblings move between splits.
 */
internal enum class ExpansionPairedSplit {
    TRAIN,
    VALIDATION,
    TEST,
}

/** Zero-based ordinary expansion ordinal in the first own expansion turn of the assigned epoch. */
internal data class ExpansionPairedRequest(val index: Int) {
    init {
        require(index in 0 until ORIGINS) { "Expansion requests belong to 1,024 fixed origins." }
    }

    val seat: Int = index % 4
    val epoch: Int = 1 + (index / 4) % 3
    val ordinal: Int = listOf(0, 2, 4, 6)[(index / 12) % 4]
    val split: ExpansionPairedSplit =
        when {
            index < 768 -> ExpansionPairedSplit.TRAIN
            index < 896 -> ExpansionPairedSplit.VALIDATION
            else -> ExpansionPairedSplit.TEST
        }

    companion object {
        const val ORIGINS = 1024
    }
}

/** Distinct explicit streams; only the two future streams are shared between sibling actions. */
internal data class ExpansionPairedSeeds(
    val gameSeed: Int,
    val proposalSeed: Long,
    val future: List<Long>,
) {
    init {
        require(future.size == 2) { "Each expansion origin requires two paired future streams." }
        require((future + proposalSeed + gameSeed.toLong()).distinct().size == 4) {
            "Origin, proposal and future streams must be distinct."
        }
    }
}

internal enum class ExpansionProposalReason {
    INCUMBENT,
    PASS,
    CARD_PURCHASE,
    DESTINATION_SAMPLE,
    REMAINING_COMMAND_SAMPLE,
}

/** Original full-observation index; no branch outcome or value enters proposal generation. */
internal data class ExpansionPairedProposal(
    val actionIndex: Int,
    val command: String,
    val reason: ExpansionProposalReason,
)

/**
 * At most eight executable first actions. Public physical destinations receive one sample each
 * before additional commitments to the same destination. All groups/commands are sorted before the
 * separate proposal RNG is used; neither authority state nor an action score is accepted.
 */
internal object ExpansionPairedProposals {
    const val MAXIMUM_ARMS = 8

    fun from(
        actions: List<TerritoryAction>,
        baselineIndex: Int,
        seed: Long,
    ): List<ExpansionPairedProposal> {
        val commands = actions.map(TerritoryAction::command)
        require(
            actions.size >= 2 &&
                commands == commands.distinct().sorted() &&
                baselineIndex in actions.indices
        ) {
            "Expansion proposals require the complete canonical offered actions and incumbent."
        }
        require("pass" in commands) { "An ordinary expansion root must offer PASS." }
        val groups = actions.indices.groupBy { group(actions[it].command) }
        val random = Random(seed)
        val selected = mutableListOf<ExpansionPairedProposal>()
        fun add(index: Int, reason: ExpansionProposalReason) {
            if (index >= 0 && selected.none { it.actionIndex == index }) {
                selected += ExpansionPairedProposal(index, actions[index].command, reason)
            }
        }
        add(baselineIndex, ExpansionProposalReason.INCUMBENT)
        add(commands.indexOf("pass"), ExpansionProposalReason.PASS)
        add(commands.indexOf("buy-expansion-card"), ExpansionProposalReason.CARD_PURCHASE)
        val remainingGroups =
            groups.toSortedMap().values.mapNotNull { indices ->
                indices
                    .filter { index -> selected.none { it.actionIndex == index } }
                    .takeIf { it.isNotEmpty() }
            }
        for (indices in remainingGroups.shuffled(random)) {
            if (selected.size == MAXIMUM_ARMS) break
            add(indices[random.nextInt(indices.size)], ExpansionProposalReason.DESTINATION_SAMPLE)
        }
        val remainder =
            actions.indices.filter { index -> selected.none { it.actionIndex == index } }
        for (index in remainder.shuffled(random)) {
            if (selected.size == MAXIMUM_ARMS) break
            add(index, ExpansionProposalReason.REMAINING_COMMAND_SAMPLE)
        }
        return selected.toList()
    }

    private fun group(command: String): String =
        when (val parsed = TextCodec.parseCommand(command)) {
            is BoardCommand.Place -> "province.${parsed.province}"
            is BoardCommand.Attack -> "province.${parsed.province}"
            is BoardCommand.ReduceMarker -> "province.${parsed.province}"
            else -> {
                require(command == "pass" || command == "buy-expansion-card") {
                    "Only ordinary offered expansion commands belong in the proposal set."
                }
                command
            }
        }
}
