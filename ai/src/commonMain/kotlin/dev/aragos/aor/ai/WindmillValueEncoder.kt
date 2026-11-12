package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardData
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage

/** Fixed public/own state for a conditional ATTEMPT-minus-PASS return; no action selection. */
object WindmillValueEncoder {
    const val VERSION = "windmill-value-public-1"
    private val amounts =
        listOf(
            "cash",
            "writtenCash",
            "hand",
            "stock",
            "pool",
            "markers",
            "misery",
            "chaos",
            "income",
            "interest",
            "turnPosition",
            "orderBox",
            "overseasSlots",
        )
    private val globals = listOf("turn", "epoch", "deck", "ending", "playerCount")
    private val interactions =
        listOf(
            "successOwnHand",
            "successVictimHand",
            "successBurden",
            "costCash",
            "costTurn",
            "burdenMisery",
        )

    /** Lexicographic complete catalog, fixed before data; absent columns are zero. */
    val featureNames: List<String> = buildList {
        add("bias")
        addAll(globals)
        addAll(listOf("offer.tokenCost", "offer.successProbability", "offer.poolFraction"))
        addAll(listOf("own.burdenCount", "own.burdenFraction"))
        for (role in listOf("own", "victim")) {
            addAll(amounts.map { "$role.$it" })
            addAll(ShippingCapability.entries.map { "$role.ships.${it.name}" })
            addAll(AdvanceId.entries.map { "$role.advance.${it.name}" })
            addAll(Commodity.entries.map { "$role.markets.${it.name}" })
        }
        for (card in CardData.all) {
            add("hand.${card.id}")
            add("unplayed.${card.id}")
        }
        addAll(interactions.map { "interaction.$it" })
    }
        .sorted()
        .also {
            check(it.size == 273 && it.distinct().size == 273) {
                "The prospective Windmill dictionary must contain exactly273 unique columns."
            }
        }

    /**
     * Uses the actual quote and existing raw-state units. Commodity inventories count public
     * markers of either colour, not tokens or computed payouts. No authored card value is used.
     */
    fun from(view: GameView, actor: PlayerId): List<Double> {
        val offer = requireNotNull(view.windmillOffer) { "A live Windmill quote is required." }
        require(view.stage == Stage.EXPANSION && offer.player == actor && actor != offer.victim) {
            "Windmill encoding requires the offered expansion actor and a distinct victim."
        }
        val catalog = requireNotNull(view.catalog) { "Public province definitions are required." }
        val raw = RawActorState.from(view, actor, "windmill")
        val own = view.players.single { it.id == actor }
        val victimRole = RawActorState.role(view, actor, offer.victim)
        val handReferences = view.hand.map { it.reference }.toSet()
        require(view.burdenReferences.all { it in handReferences }) {
            "Burden references must name cards in the actor's own hand."
        }
        val provinces = catalog.provinces.associateBy { it.id }
        val burden = view.burdenReferences.size / 10.0
        val cost = offer.tokenCost / 36.0
        val probability = offer.successProbability
        require(offer.tokenCost >= 0 && probability.isFinite() && probability in 0.0..1.0) {
            "The authoritative Windmill quote must have finite probability and nonnegative cost."
        }
        val values = buildMap {
            put("bias", 1.0)
            globals.forEach { put(it, raw[it] ?: 0.0) }
            for ((role, id, rawRole) in
                listOf(
                    Triple("own", actor, "role.0"),
                    Triple("victim", offer.victim, victimRole),
                )) {
                amounts.forEach { put("$role.$it", raw["$rawRole.$it"] ?: 0.0) }
                ShippingCapability.entries.forEach {
                    put("$role.ships.${it.name}", raw["$rawRole.ships.${it.name}"] ?: 0.0)
                }
                AdvanceId.entries.forEach {
                    put("$role.advance.${it.name}", raw["$rawRole.advance.${it.name}"] ?: 0.0)
                }
                Commodity.entries.forEach { commodity ->
                    val count =
                        view.board.count { province ->
                            province.marker?.owner == id &&
                                commodity in
                                    requireNotNull(provinces[province.id]) {
                                            "Every public occupied province must have a catalog definition."
                                        }
                                        .commodities
                        }
                    put("$role.markets.${commodity.name}", count / 10.0)
                }
            }
            CardData.all.forEach { card ->
                put("hand.${card.id}", raw["hand.${card.id}"] ?: 0.0)
                put("unplayed.${card.id}", raw["unplayed.${card.id}"] ?: 0.0)
            }
            put("offer.tokenCost", cost)
            put("offer.successProbability", probability)
            put("offer.poolFraction", offer.tokenCost.toDouble() / maxOf(1, own.poolTokens))
            put("own.burdenCount", burden)
            put(
                "own.burdenFraction",
                view.burdenReferences.size.toDouble() / maxOf(1, view.hand.size),
            )
            put("interaction.successOwnHand", probability * (raw["role.0.hand"] ?: 0.0))
            put("interaction.successVictimHand", probability * (raw["$victimRole.hand"] ?: 0.0))
            put("interaction.successBurden", probability * burden)
            put("interaction.costCash", cost * (raw["role.0.cash"] ?: 0.0))
            put("interaction.costTurn", cost * (raw["turn"] ?: 0.0))
            put("interaction.burdenMisery", burden * (raw["role.0.misery"] ?: 0.0))
        }
        return featureNames.map { name ->
            requireNotNull(values[name]) { "Every fixed Windmill column must be populated: $name." }
                .also { require(it.isFinite()) { "Windmill features must be finite: $name." } }
        }
    }
}
