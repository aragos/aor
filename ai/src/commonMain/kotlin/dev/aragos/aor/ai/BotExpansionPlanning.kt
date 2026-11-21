package dev.aragos.aor.ai

import dev.aragos.aor.Commodity

/** Independent additive interaction weights; absent weights skip commodity interaction scoring. */
enum class BotBundleFeature(val key: String) {
    HELD_COMMODITY("planning.heldCommodityBundle"),
    PUBLIC_COMMODITY("planning.publicCommodityBundle"),
}

/**
 * Public forecast inputs, in markets, dollars and physical-card exposure counts. [outsideCards]
 * excludes the deciding seat's cards. A public shared-choice card appears in both commodity counts;
 * its later choice is unknown. [adjustmentOffset] is the first current shortage/surplus only.
 */
data class BotPlanningCommodity(
    val commodity: Commodity,
    val markets: Int,
    val baseValue: Int,
    val adjustmentOffset: Int,
    val outsideCards: Int,
) {
    init {
        require(markets in 0..256 && baseValue in 1..10 && outsideCards in 0..512) {
            "Planning commodity counts must be nonnegative and bounded; base values must be 1–10."
        }
        require(adjustmentOffset in -1..1) { "Planning commodity adjustment must be -1, 0 or 1." }
    }
}

/**
 * [markerCost] conservatively reserves a marker for any full market attempt, including competition.
 * [commodities] contains only full, certain land placements; shipping, partial placements and
 * competitions do not earn a bundle interaction. [newOverseasCost] reserves a distinct overseas
 * presence even for a partial placement. These facts never create new legal actions.
 */
data class BotExpansionStep(
    val markerCost: Int,
    val commodities: Set<Commodity> = emptySet(),
    val newOverseasCost: Int = 0,
) {
    init {
        require(markerCost in 0..1 && (commodities.isEmpty() || markerCost == 1)) {
            "An expansion bundle step consumes zero or one marker; commodity gains require one."
        }
        require(newOverseasCost in 0..1) { "An expansion step consumes zero or one overseas slot." }
    }
}

/**
 * Optional planning facts from the ordinary deciding-seat view. [heldCards] contains one choice set
 * per physical own commodity card, retaining duplicates. Forecasts value the current first
 * adjustment for each card; they do not predict future card ownership, play order or adjustment
 * consumption. [markerStock] and [overseasSlots] constrain planning independently of model weights.
 */
data class BotExpansionPlanning(
    val markerStock: Int,
    val industry: Boolean,
    val commodities: List<BotPlanningCommodity>,
    val heldCards: List<Set<Commodity>>,
    val steps: Map<String, BotExpansionStep>,
    val overseasSlots: Int = 0,
) {
    init {
        require(markerStock in 0..256) { "Planning marker stock must be nonnegative and bounded." }
        require(overseasSlots in 0..4) { "Planning overseas slots must be between zero and four." }
        require(
            commodities.map { it.commodity }.toSet() == Commodity.entries.toSet() &&
                commodities.size == Commodity.entries.size
        ) {
            "Expansion planning requires exactly one forecast per commodity."
        }
        require(heldCards.size <= 256 && heldCards.all { it.isNotEmpty() }) {
            "Expansion planning requires at most 256 physical commodity cards with nonempty choices."
        }
        require(
            steps.size <= 1024 &&
                steps.keys.all {
                    it.isNotBlank() && it.length <= 2000 && '\n' !in it && '\r' !in it
                }
        ) {
            "Expansion planning steps require bounded canonical command identifiers."
        }
    }
}

/**
 * Exact interaction of the declared current-payout forecasts: joint gain minus the sum of
 * standalone gains. Own shared-choice cards use their best payout once per physical card. Public
 * exposure keeps the existing per-commodity proxy. Neither is a promise of future cash or an attack
 * expectation.
 */
internal class BotExpansionInteractions
private constructor(
    private val planning: BotExpansionPlanning,
    private val heldWeight: Double,
    private val publicWeight: Double,
) {
    private val portfolio =
        BotCommodityPortfolio(planning.industry, planning.commodities, planning.heldCards)
    private val singleValues =
        planning.steps.mapValues { (_, step) -> portfolio.gains(listOf(step.commodities)) }
    private val interactions = mutableMapOf<Set<String>, Double>()

    fun value(commands: Set<String>): Double =
        interactions.getOrPut(commands) {
            val applicable = commands.filter {
                planning.steps[it]?.commodities?.isNotEmpty() == true
            }
            if (applicable.size < 2) {
                0.0
            } else {
                val joint =
                    portfolio.gains(
                        applicable.map { command ->
                            checkNotNull(planning.steps[command]) {
                                    "A bundle command requires its projected step."
                                }
                                .commodities
                        }
                    )
                val separateHeld = applicable.sumOf { singleValues[it]?.held ?: 0.0 }
                val separatePublic = applicable.sumOf { singleValues[it]?.public ?: 0.0 }
                heldWeight * (joint.held - separateHeld) +
                    publicWeight * (joint.public - separatePublic)
            }
        }

    companion object {
        /** Check weights before allocating forecasts or caches for an existing policy. */
        fun forObservation(
            observation: BotObservation,
            model: BotModel,
        ): BotExpansionInteractions? {
            val held = model.weights[BotBundleFeature.HELD_COMMODITY.key] ?: 0.0
            val public = model.weights[BotBundleFeature.PUBLIC_COMMODITY.key] ?: 0.0
            if (held == 0.0 && public == 0.0) return null
            val planning = observation.expansionPlanning ?: return null
            return BotExpansionInteractions(planning, held, public)
        }
    }
}
