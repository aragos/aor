package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceData
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.GameView
import dev.aragos.aor.MiseryStep
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/** Public relief-capacity curriculum; no return, hidden rival fact or policy score enters it. */
enum class PurchaseSupportExclusion {
    WRONG_DECISION,
    INCUMBENT_CONTINUES,
    NO_POSITIVE_MR,
    NO_POSITIVE_MISERY,
    NOT_ONE_MISSING_CATEGORY,
    NO_OFFERED_COMPLETION,
    NO_OFFERED_PASS,
}

/** Only sign/identity facts are needed; clipping raw actor amounts preserves these predicates. */
data class PurchaseSupportFacts(
    val advanceWindow: Boolean,
    val positiveRelief: Boolean,
    val positiveMisery: Boolean,
    val advances: Set<AdvanceId>,
) {
    val missingCategory: AdvanceCategory?
        get() =
            AdvanceCategory.entries.singleOrNull { category ->
                advances.none { AdvanceData.definition(it).category == category }
            }

    companion object {
        fun from(view: GameView, actor: PlayerId, decision: String): PurchaseSupportFacts {
            val player = view.players.single { it.id == actor }
            return PurchaseSupportFacts(
                advanceWindow = view.stage == Stage.PURCHASE && decision == "purchase-advances",
                positiveRelief = view.reliefRatingAvailable > 0,
                positiveMisery =
                    player.misery != MiseryStep.ZERO && player.misery != MiseryStep.CHAOS,
                advances = player.advances.toSet(),
            )
        }

        /** Reads the existing raw encoder vocabulary; does not fabricate a game view or quote. */
        fun from(observation: PurchaseObservation): PurchaseSupportFacts {
            val state = observation.stateFeatures
            return PurchaseSupportFacts(
                advanceWindow = (state["decision.purchase-advances"] ?: 0.0) > 0.0,
                positiveRelief = (state["reliefAvailable"] ?: 0.0) > 0.0,
                positiveMisery =
                    (state["role.0.misery"] ?: 0.0) > 0.0 && (state["role.0.chaos"] ?: 0.0) == 0.0,
                advances =
                    AdvanceId.entries
                        .filter { (state["role.0.advance.${it.name}"] ?: 0.0) > 0.0 }
                        .toSet(),
            )
        }
    }
}

/** At most four prospective first advances plus actual PASS, in stable declared order. */
class PurchaseSupport private constructor(val commands: List<String>) {
    companion object {
        const val FIRST_ACTIONS = 4

        fun advance(command: String): AdvanceId? =
            (TextCodec.parseCommand(command) as? TurnCommand.BuyAdvance)?.let {
                AdvanceId.fromLetter(it.letter)
            }

        fun completion(command: String, category: AdvanceCategory): Boolean =
            advance(command)?.let { AdvanceData.definition(it).category == category } == true

        fun exclusion(
            facts: PurchaseSupportFacts,
            offered: List<String>,
            incumbentContinues: Boolean = false,
        ): PurchaseSupportExclusion? {
            if (!facts.advanceWindow) return PurchaseSupportExclusion.WRONG_DECISION
            if (incumbentContinues) return PurchaseSupportExclusion.INCUMBENT_CONTINUES
            if (!facts.positiveRelief) return PurchaseSupportExclusion.NO_POSITIVE_MR
            if (!facts.positiveMisery) return PurchaseSupportExclusion.NO_POSITIVE_MISERY
            val missing =
                facts.missingCategory ?: return PurchaseSupportExclusion.NOT_ONE_MISSING_CATEGORY
            if (offered.none { completion(it, missing) }) {
                return PurchaseSupportExclusion.NO_OFFERED_COMPLETION
            }
            if ("pass" !in offered) return PurchaseSupportExclusion.NO_OFFERED_PASS
            return null
        }

        /** Full outcome-blind order, also used by the teacher to report truncated proposals. */
        fun orderedAdvances(offered: List<String>, missing: AdvanceCategory): List<String> =
            offered
                .filter { advance(it) != null }
                .sortedWith(compareBy<String> { !completion(it, missing) }.thenBy { it })

        fun from(facts: PurchaseSupportFacts, offered: List<String>): PurchaseSupport? {
            if (exclusion(facts, offered) != null) return null
            val missing =
                checkNotNull(facts.missingCategory) { "Eligible support has one missing category." }
            return PurchaseSupport(
                listOf("pass") + orderedAdvances(offered, missing).take(FIRST_ACTIONS)
            )
        }

        fun from(observation: PurchaseObservation): PurchaseSupport? =
            from(
                PurchaseSupportFacts.from(observation),
                observation.actions.map(PurchaseAction::command),
            )
    }
}
