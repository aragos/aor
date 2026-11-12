package dev.aragos.aor.training

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonInclude
import dev.aragos.aor.ai.BotDifficulty

/**
 * Both typed views share one complete origin and terminal result. Numbers are absolute simulator
 * command indices, not phase-local offsets. Empty phase arrays preserve the common origin set.
 */
internal data class JointGameTrajectory(
    val schemaVersion: Int = 1,
    val signature: String,
    val index: Int,
    val configuration: String,
    val gameSeed: Int,
    val controlledSeat: Int,
    val opponentIndices: List<Int>,
    val bundleSha256: String,
    val cardModelSha256: String,
    val purchaseModelSha256: String,
    val cardDecisions: List<CardDecisionRecord>,
    val purchaseDecisions: List<PurchaseDecisionRecord>,
    val terminal: PurchaseTerminal,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val jointEvidence: List<JointDecisionEvidence>? = null,
    @get:JsonIgnore val canonicalReplay: PurchaseCanonicalReplay? = null,
)

/** No callback can expose opponent-private observations or persist an interrupted game's labels. */
internal class JointCollector(
    private val input: JointCollectionInput,
    private val signature: String,
    private val deadlineNanos: Long,
) {
    internal data class TripleRecording(
        val input: TripleCollectionInput,
        val rows: MutableList<TerritoryDecisionRecord>,
    )

    fun collect(index: Int, stateObserver: SimulationStateObserver? = null): JointGameTrajectory =
        collect(index, stateObserver, null)

    fun collectTriple(triple: TripleCollectionInput, index: Int): TripleGameTrajectory {
        require(triple.joint === input && triple.signature == signature) {
            "Triple collection must use the exact joint input and complete behavior signature."
        }
        val expansions = mutableListOf<TerritoryDecisionRecord>()
        val game = collect(index, null, TripleRecording(triple, expansions))
        return TripleGameTrajectory(
            expansionModelSha256 = triple.expansion.sha256,
            expansionDecisions = expansions.toList(),
            joint = game,
        )
    }

    /**
     * Pure controller composition is shared by actual collection and synthetic likelihood proofs.
     */
    internal fun controllers(
        index: Int,
        cardRecord: (CardDecisionRecord) -> Unit,
        purchaseRecord: (PurchaseDecisionRecord) -> Unit,
        evidenceRecord: (JointDecisionEvidence) -> Unit,
        triple: TripleRecording? = null,
    ): Map<Int, SimulationController> {
        require(index in input.seeds.indices) {
            "Controller origin must belong to its frozen bank."
        }
        require(
            triple == null || triple.input.joint === input && triple.input.signature == signature
        ) {
            "Triple controller composition requires its exact input and complete signature."
        }
        val seat = input.seat(index)
        val opponents = input.opponentIndices(index)
        val controllers =
            opponents.indices.associateWith { player ->
                val bundle =
                    if (player == seat) input.bundle else input.opponents[opponents[player]]
                bundle.controller(
                    input.purchaseBaseline,
                    ordinary = input.baseline.model,
                    cardRecord = if (player == seat) cardRecord else null,
                    purchaseRecord = if (player == seat) purchaseRecord else null,
                )
            }
        val composed = input.ecology?.controllers(input, index, controllers) ?: controllers
        val behavior =
            if (triple == null) {
                composed
            } else {
                val reference =
                    requireNotNull(composed[seat]) { "The complete E/T controller is required." }
                composed +
                    (seat to
                        ExpansionOnPolicyController(
                            reference,
                            triple.input.expansion.model,
                            triple.rows::add,
                        ))
            }
        return if (input.ecology == null) {
            behavior
        } else {
            val previous =
                requireNotNull(behavior[seat]) {
                    "Joint evidence requires its controlled actor."
                }
            behavior +
                (seat to
                    object : SimulationController {
                        override fun controls(decision: String): Boolean =
                            previous.controls(decision)

                        override fun choose(decision: SimulationDecision): String? {
                            val chosen = previous.choose(decision) ?: return null
                            JointDecisionEvidence.from(decision, chosen)?.let(evidenceRecord)
                            return chosen
                        }
                    })
        }
    }

    private fun collect(
        index: Int,
        stateObserver: SimulationStateObserver?,
        triple: TripleRecording?,
    ): JointGameTrajectory {
        require(index in input.seeds.indices) {
            "A joint game index must belong to the frozen bank."
        }
        val seat = input.seat(index)
        val cards = mutableListOf<CardDecisionRecord>()
        val purchases = mutableListOf<PurchaseDecisionRecord>()
        val evidence = mutableListOf<JointDecisionEvidence>()
        val opponents = input.opponentIndices(index)
        val observed = controllers(index, cards::add, purchases::add, evidence::add, triple)
        val trace =
            if (index in input.ecology?.replayIndices.orEmpty()) PurchaseCanonicalTrace(seat)
            else null
        val game =
            Simulation()
                .play(
                    configuration = input.configuration,
                    seed = input.seeds[index],
                    competitors =
                        List(input.configuration.playerCount) {
                            Competitor(input.baseline.model, BotDifficulty.HARD)
                        },
                    deadlineNanos = deadlineNanos,
                    controllers = observed,
                    trace = trace,
                    stateObserver = stateObserver,
                )
        return JointGameTrajectory(
            signature = signature,
            index = index,
            configuration = input.configuration.id,
            gameSeed = input.seeds[index],
            controlledSeat = seat,
            opponentIndices = opponents,
            bundleSha256 = input.bundle.sha256,
            cardModelSha256 =
                requireNotNull(input.bundle.card) { "The joint learner requires its card model." }
                    .sha256,
            purchaseModelSha256 = input.bundle.purchase.sha256,
            cardDecisions = cards.toList(),
            purchaseDecisions = purchases.toList(),
            jointEvidence = if (input.ecology == null) null else evidence.toList(),
            canonicalReplay = trace?.result(),
            terminal =
                PurchaseTerminal(
                    game.reward(seat),
                    game.winShare(seat),
                    game.rankReward(seat),
                    game.scores.map(PilotReplayScore::from),
                    game.steps,
                ),
        )
    }
}
