package dev.aragos.aor.training

import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation
import dev.aragos.aor.ai.TerritoryEncoder
import dev.aragos.aor.ai.TerritoryObservation

/** Actor-visible numerical input only; no terminal result, canonical entry or authority view. */
internal data class CoordinatedEsPanelState(
    val number: Int,
    val batchStartNumber: Int,
    val epoch: Int,
    val referenceIndex: Int,
    val observation: TerritoryObservation,
) {
    init {
        require(
            number >= batchStartNumber &&
                batchStartNumber >= 0 &&
                epoch in 1..3 &&
                referenceIndex == observation.baselineIndex &&
                observation.actions.size > 1
        ) {
            "A sensitivity state requires a branching full offer, exact reference and original offsets."
        }
    }
}

internal data class CoordinatedEsPanelWindows(val observed: Int, val branching: Int)

/** First/last curriculum for the assigned public epoch; there is no outcome-dependent choice. */
internal class CoordinatedEsPanel(val globalIndex: Int) {
    val assignedEpoch = 1 + globalIndex / 4 % 3
    private val retained = mutableMapOf<CoordinatedEsHead, List<CoordinatedEsPanelState>>()
    private val counts = mutableMapOf<CoordinatedEsHead, CoordinatedEsPanelWindows>()

    init {
        require(globalIndex in 0..255) {
            "The fixed sensitivity panel uses generation1 worlds0..255."
        }
    }

    fun observe(
        head: CoordinatedEsHead,
        number: Int,
        batchStart: Int,
        epoch: Int,
        observation: TerritoryObservation,
    ) {
        require(head in HEADS) { "Only E/T have a signed-gap sensitivity panel." }
        val encoder =
            if (head == CoordinatedEsHead.EXPANSION) {
                TerritoryEncoder.EXPANSION_2
            } else {
                TerritoryEncoder.TOKEN_2
            }
        require(observation.encoderVersion == encoder.version) {
            "A sensitivity state must use the complete dictionary for its declared head."
        }
        if (epoch != assignedEpoch) return
        val prior = counts[head] ?: CoordinatedEsPanelWindows(0, 0)
        val branching = observation.actions.size > 1
        counts[head] =
            CoordinatedEsPanelWindows(prior.observed + 1, prior.branching + if (branching) 1 else 0)
        if (!branching) return
        val row =
            CoordinatedEsPanelState(
                number,
                batchStart,
                epoch,
                observation.baselineIndex,
                observation,
            )
        val previous = retained[head].orEmpty()
        require(previous.isEmpty() || number > previous.last().number) {
            "Panel decisions must retain their original increasing command numbers."
        }
        retained[head] = if (previous.isEmpty()) listOf(row) else listOf(previous.first(), row)
    }

    fun heads(): Map<String, List<CoordinatedEsPanelState>> = HEADS.associate {
        it.name to retained[it].orEmpty()
    }

    fun windows(): Map<String, CoordinatedEsPanelWindows> = HEADS.associate {
        it.name to (counts[it] ?: CoordinatedEsPanelWindows(0, 0))
    }

    companion object {
        private val HEADS = listOf(CoordinatedEsHead.EXPANSION, CoordinatedEsHead.TOKEN)
    }
}

/** Private canonical recording and public input projection have separate methods/outputs. */
internal class CoordinatedEsTrace(
    override val seat: Int,
    private val gameSeed: Int,
    private val panel: CoordinatedEsPanel? = null,
    private val retainCanonical: Boolean,
) : SimulationTrace {
    var header: GameHeader? = null
        private set

    var entries: List<String>? = null
        private set

    override fun decision(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
    ) {
        val target = panel ?: return
        val head = CoordinatedEsController.territoryHead(observation.decision) ?: return
        if (view.currentEpoch != target.assignedEpoch) return
        // The entry-offset difference is exactly actorIndex in the untouched sealed batch.
        val batchStart = number - (commandEntryIndex - snapshotEntryCount)
        val decision =
            SimulationDecision(
                number,
                seat,
                simulationPolicySeed(gameSeed, batchStart, seat),
                view,
                observation,
                batchStart,
            )
        target.observe(
            head,
            number,
            batchStart,
            view.currentEpoch,
            CoordinatedEsController.observation(decision, chosen.command),
        )
    }

    override fun verified(header: GameHeader, entries: List<String>) {
        if (retainCanonical) {
            this.header = header
            this.entries = entries.toList()
        }
    }

    fun canonical(decisions: Int): TokenPairedCanonical? {
        if (!retainCanonical) return null
        val history =
            requireNotNull(entries) {
                "A prescribed complete replay requires verified canonical entries."
            }
        return TokenPairedCanonical(
            requireNotNull(header) { "A prescribed replay requires its original header." },
            history,
            CardAdvantageExecutor.hash(history),
            decisions,
            terminal = true,
        )
    }
}
