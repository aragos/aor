package dev.aragos.aor.training

import dev.aragos.aor.GameHeader
import dev.aragos.aor.GameView
import dev.aragos.aor.ai.BotCandidate
import dev.aragos.aor.ai.BotModel
import dev.aragos.aor.ai.BotObservation

/**
 * Complete authoritative history, segregated from all purchase observations and training labels.
 */
internal data class PurchaseCanonicalReplay(val header: GameHeader, val entries: List<String>)

/** Simulation delivers these entries only after replaying them to the identical terminal state. */
internal class PurchaseCanonicalTrace(override val seat: Int) : SimulationTrace {
    private var verified: PurchaseCanonicalReplay? = null

    override fun decision(
        number: Int,
        snapshotEntryCount: Int,
        commandEntryIndex: Int,
        view: GameView,
        observation: BotObservation,
        chosen: BotCandidate,
        model: BotModel,
    ) = Unit

    override fun verified(header: GameHeader, entries: List<String>) {
        check(verified == null) { "A purchase history must be verified exactly once." }
        verified = PurchaseCanonicalReplay(header, entries.toList())
    }

    fun result(): PurchaseCanonicalReplay =
        checkNotNull(verified) {
            "A declared purchase replay requires its verified complete history."
        }
}
