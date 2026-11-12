package dev.aragos.aor.ai

import dev.aragos.aor.Commodity
import dev.aragos.aor.ExpansionBonusView
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog

/** Research inputs only: old territory policies deliberately reject this separate encoder tag. */
data class ExpansionPairedObservation(
    val encoderVersion: String,
    val stateFeatures: Map<String, Double>,
    val actions: List<TerritoryAction>,
    val baselineIndex: Int,
) {
    init {
        require(encoderVersion == ExpansionPairedEncoder.VERSION) {
            "Expansion paired observations require their explicit research encoder."
        }
        requireTerritoryProjection(TerritoryEncoder.EXPANSION_2.version, stateFeatures, actions)
        require(baselineIndex in actions.indices) { "The exact E incumbent must be offered." }
    }
}

/**
 * Existing full public/own state and action quotes, plus literal current commodity/bonus facts.
 * Counts use cards/10, markers/25, payout dollars/200 and pool tokens/36. Public unplayed counts
 * include held cards/private discards; current payout quotes include white markers and are not a
 * promise of later income or Famine protection. The bonus quote contains only public eligibility.
 */
object ExpansionPairedEncoder {
    const val VERSION = "aor-expansion-paired-observation-1"
    private val cardFields =
        listOf("epoch1", "epoch2", "epoch3", "unplayed", "unplayedKnown", "ownHand")

    fun dictionary(catalog: PublicCatalog): TerritoryFeatureDictionary {
        val original = ExpansionObservationEncoder.dictionary(catalog, TerritoryEncoder.EXPANSION_2)
        val additions =
            Commodity.entries.flatMap { commodity ->
                cardFields.map { "commodity.${commodity.name}.cards.$it" } +
                    (0..5).flatMap { role ->
                        listOf("markets", "payout").map {
                            "commodity.${commodity.name}.role.$role.$it"
                        }
                    }
            } +
                (0..5).map { "role.$it.expansionBonusEligible" } +
                listOf("expansion.cardCost", "expansion.cardCostKnown")
        return TerritoryFeatureDictionary(
            (original.stateFeatureNames + additions).distinct().sorted(),
            original.actionFeatureNames,
        )
    }

    fun from(
        view: GameView,
        seat: PlayerId,
        ordinary: BotObservation,
        incumbent: String,
        bonus: ExpansionBonusView,
    ): ExpansionPairedObservation {
        val original =
            ExpansionObservationEncoder.from(
                view,
                seat,
                ordinary,
                incumbent,
                TerritoryEncoder.EXPANSION_2,
            )
        require(view.commodityCards.map { it.commodity }.toSet() == Commodity.entries.toSet()) {
            "Expansion public inventory must contain every commodity."
        }
        require(view.commodityPayouts.map { it.commodity }.toSet() == Commodity.entries.toSet()) {
            "Expansion public payout quotes must contain every commodity."
        }
        val inspections = view.cardInspections.associateBy { it.definition.id }
        val own =
            view.hand.map { card ->
                requireNotNull(inspections[card.definition.id]) {
                        "A full own/public expansion view must retain own card inspections."
                    }
                    .commodities
            }
        val state = buildMap {
            putAll(original.stateFeatures)
            fun add(key: String, value: Double) {
                if (value != 0.0) put(key, value.coerceIn(-16.0, 16.0))
            }
            for (inventory in view.commodityCards) {
                val prefix = "commodity.${inventory.commodity.name}.cards"
                inventory.epochs.forEachIndexed { index, count ->
                    add("$prefix.epoch${index + 1}", count / 10.0)
                }
                inventory.unplayed?.let {
                    add("$prefix.unplayedKnown", 1.0)
                    add("$prefix.unplayed", it / 10.0)
                }
                add("$prefix.ownHand", own.count { inventory.commodity in it } / 10.0)
            }
            for (quote in view.commodityPayouts) {
                for (owner in quote.owners) {
                    val role = RawActorState.role(view, seat, owner.player)
                    val prefix = "commodity.${quote.commodity.name}.$role"
                    add("$prefix.markets", owner.marketCount / 25.0)
                    add("$prefix.payout", owner.payout / 200.0)
                }
            }
            for (player in bonus.players) {
                add(
                    "${RawActorState.role(view, seat, player.player)}.expansionBonusEligible",
                    player.eligibleMarkers / 25.0,
                )
            }
            view.expansionCardCost?.let {
                add("expansion.cardCostKnown", 1.0)
                add("expansion.cardCost", it / 36.0)
            }
        }
        return ExpansionPairedObservation(VERSION, state, original.actions, original.baselineIndex)
    }
}
