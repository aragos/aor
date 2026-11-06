package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardData
import dev.aragos.aor.CardLeaderRole
import dev.aragos.aor.Command
import dev.aragos.aor.Commodity
import dev.aragos.aor.CommodityAdjustment
import dev.aragos.aor.EventId
import dev.aragos.aor.GameView
import dev.aragos.aor.LeaderId
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.ShippingCapability
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/**
 * Versioned raw purchase inputs, projected only from an ordinary acting-player [GameView]. Callers
 * must use the ordinary view: decision-only views intentionally omit public unplayed cards.
 * [BotObservation] supplies the complete offered command list, not its authored score features.
 * Role 0 is the actor; other roles follow cyclic public registration order. Names, transient hand
 * references, authority entries and unrevealed submissions are never encoded.
 *
 * Dollars are divided by 200 (credits by 100), misery by 1000, tokens by 36, markers by 25, hands
 * by 10, turns by 20 and deck counts by 80. Every feature is clipped to [-16,16]; absent values are
 * zero. The complete dictionary is catalog-derived before data collection, including currently
 * absent provinces/cards/advances. Unsupported dictionary keys fail inference explicitly.
 */
object PurchaseObservationEncoder {
    const val ENCODER_VERSION = "aor-purchase-observation-1"
    private val decisions = listOf("purchase-advances", "purchase-shipping")
    private val roles = (0..5).map { "role.$it" }
    private val playerAmounts =
        listOf(
            "present",
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
    private val quoteAmounts =
        listOf(
            "present",
            "price",
            "priceKnown",
            "available",
            "categoryCredit",
            "researchCredit",
            "leaderCredit",
            "miseryAfter",
            "chaos",
        )
    private val actionAmounts =
        listOf(
            "cost",
            "cashAfter",
            "printedCost",
            "categoryCredit",
            "researchCredit",
            "leaderCredit",
            "miseryDelta",
            "chaos",
            "reliefRating",
            "credit",
        )
    private val military =
        listOf(EventId.ARMOR, EventId.STIRRUPS, EventId.LONG_BOW, EventId.GUNPOWDER)

    fun dictionary(view: GameView): PurchaseFeatureDictionary =
        dictionary(
            requireNotNull(view.catalog) {
                "Purchase encoding requires the ordinary public catalog."
            }
        )

    /** Vocabulary depends on the public setup/catalog, never on sampled trajectory nonzeros. */
    fun dictionary(catalog: PublicCatalog): PurchaseFeatureDictionary {
        val state = buildSet {
            addAll(
                listOf(
                    "turn",
                    "epoch",
                    "deck",
                    "ending",
                    "playerCount",
                    "stabilization",
                    "reliefAvailable",
                    "war",
                    "crusadesPlayed",
                    "religiousStrifePlayed",
                    "void.armorAndStirrups",
                    "void.crusades",
                    "void.papalDecree",
                    "catalog.${catalog.dataVersion}",
                )
            )
            decisions.forEach { add("decision.$it") }
            AdvanceCategory.entries.forEach { add("papal.${it.name}") }
            CardData.all.forEach {
                add("hand.${it.id}")
                add("unplayed.${it.id}")
            }
            for (role in roles) {
                playerAmounts.forEach { add("$role.$it") }
                add("$role.enlightened")
                add("$role.civilWar")
                military.forEach { add("$role.military.${it.name}") }
                ShippingCapability.entries.forEach { add("$role.ships.${it.name}") }
                AdvanceId.entries.forEach { add("$role.advance.${it.name}") }
                catalog.provinces.forEach { province ->
                    add("$role.capital.${province.id.value}")
                    for (piece in
                        listOf(
                            "marker.coloured",
                            "marker.white",
                            "tokens.coloured",
                            "tokens.white",
                        )) {
                        add("board.${province.id.value}.$role.$piece")
                    }
                }
            }
            AdvanceId.entries.forEach { advance ->
                quoteAmounts.forEach { add("offer.${advance.name}.$it") }
            }
            LeaderId.entries.forEach { leader ->
                listOf("present", "display", "protection", "mongolEnhanced").forEach {
                    add("leader.${leader.cardId}.$it")
                }
                roles.forEach {
                    add("leader.${leader.cardId}.owner.$it")
                    add("leader.${leader.cardId}.claim.$it")
                }
            }
            Commodity.entries.forEach { commodity ->
                CommodityAdjustment.entries.forEach {
                    add("adjustment.${commodity.name}.${it.name}")
                }
            }
        }
            .sorted()
        val action = buildSet {
            addAll(actionAmounts)
            listOf("pass", "advance", "shipping", "patronage").forEach { add("kind.$it") }
            AdvanceId.entries.forEach { add("advance.${it.name}") }
            AdvanceCategory.entries.forEach { add("category.${it.name}") }
            LeaderId.entries.forEach { add("leader.${it.cardId}") }
            ShippingCapability.entries.forEach { add("ships.${it.name}") }
            roles.forEach { add("recipient.$it") }
        }
            .sorted()
        return PurchaseFeatureDictionary(state, action)
    }

    fun from(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        baselineCommand: String,
    ): PurchaseObservation = project(view, seat, observation).bindBaseline(baselineCommand)

    /** Project ordinary actor facts independently of a separately loaded baseline model. */
    fun project(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
    ): PurchaseProjection {
        require(view.stage == Stage.PURCHASE && observation.decision in decisions) {
            "The experimental actor controls shipping and advance purchases only."
        }
        require(observation.playerId == seat.value) {
            "Purchase observation must belong to the acting seat."
        }
        val catalog =
            requireNotNull(view.catalog) { "Purchase encoding requires the public catalog." }
        val ownIndex = view.players.indexOfFirst { it.id == seat }
        require(ownIndex >= 0) { "The acting purchase player must be in the ordinary view." }
        val roleById =
            view.players.indices.associate { index ->
                view.players[index].id to
                    roles[(index - ownIndex + view.players.size) % view.players.size]
            }
        fun role(id: PlayerId): String =
            checkNotNull(roleById[id]) { "Public fact names an unknown player." }
        val state = features {
            add("catalog.${catalog.dataVersion}")
            add("decision.${observation.decision}")
            add("turn", (view.turnNumber ?: 0) / 20.0)
            add("epoch", view.currentEpoch / 3.0)
            add("deck", view.deckRemaining / 80.0)
            add("ending", if (view.endingTriggered) 1.0 else 0.0)
            add("playerCount", view.players.size / 6.0)
            add("stabilization", (view.stabilizationCost ?: 0L) / 200.0)
            add("reliefAvailable", view.reliefRatingAvailable / 100.0)
            for (player in view.players) {
                val prefix = role(player.id)
                add("$prefix.present")
                add("$prefix.cash", player.cash / 200.0)
                add("$prefix.writtenCash", player.writtenCash / 200.0)
                add("$prefix.hand", player.handCount / 10.0)
                add("$prefix.stock", player.stockTokens / 36.0)
                add("$prefix.pool", player.poolTokens / 36.0)
                add("$prefix.markers", player.boardMarkers / 25.0)
                add("$prefix.misery", (player.misery.value ?: 0) / 1000.0)
                add("$prefix.chaos", if (player.misery.value == null) 1.0 else 0.0)
                add("$prefix.income", (player.income?.ordinary ?: 0) / 200.0)
                add("$prefix.interest", (player.income?.interestProfit ?: 0) / 200.0)
                add("$prefix.turnPosition", (view.turnOrder.indexOf(player.id) + 1) / 6.0)
                add("$prefix.orderBox", (view.orderBoxes[player.id] ?: 0) / 10.0)
                add("$prefix.overseasSlots", player.overseasSlots / 6.0)
                add("$prefix.ships.${player.ships.name}")
                player.capital?.let { add("$prefix.capital.$it") }
                player.advances.forEach { add("$prefix.advance.${it.name}") }
            }
            view.hand
                .groupingBy { it.definition.id }
                .eachCount()
                .forEach { (id, count) -> add("hand.$id", count.toDouble()) }
            view.unplayedCards
                .groupingBy { it.id }
                .eachCount()
                .forEach { (id, count) -> add("unplayed.$id", count.toDouble()) }
            for (province in view.board) {
                province.marker?.let { marker ->
                    val side = if (marker.side == PieceSide.COLOURED) "coloured" else "white"
                    add("board.${province.id.value}.${role(marker.owner)}.marker.$side")
                }
                province.tokens.forEach { tokens ->
                    val prefix = "board.${province.id.value}.${role(tokens.owner)}.tokens"
                    add("$prefix.coloured", tokens.coloured / 36.0)
                    add("$prefix.white", tokens.white / 36.0)
                }
            }
            view.advanceOffers.forEach { quote ->
                val prefix = "offer.${quote.advance.name}"
                add("$prefix.present")
                quote.price?.let {
                    add("$prefix.priceKnown")
                    add("$prefix.price", it / 200.0)
                }
                if (quote.unavailableReason == null) add("$prefix.available")
                quote.priceBreakdown?.let { detail ->
                    add("$prefix.categoryCredit", detail.categoryCredit / 100.0)
                    add("$prefix.researchCredit", detail.institutionalResearchCredit / 100.0)
                    add("$prefix.leaderCredit", detail.leaders.sumOf { it.amount } / 100.0)
                    add("$prefix.miseryAfter", (detail.miseryAfter.value ?: 0) / 1000.0)
                    if (detail.miseryAfter.value == null) add("$prefix.chaos")
                }
            }
            view.effects.leaders.forEach { leader ->
                val prefix = "leader.${leader.leader.cardId}"
                add("$prefix.present")
                add("$prefix.owner.${role(leader.owner)}")
                if (leader.role == CardLeaderRole.DISPLAY) add("$prefix.display")
                add("$prefix.protection", leader.protectionCash / 200.0)
                if (leader.mongolEnhanced) add("$prefix.mongolEnhanced")
                leader.patronageClaimants.forEach { add("$prefix.claim.${role(it)}") }
            }
            view.effects.enlightenedRulers.forEach { add("${role(it)}.enlightened") }
            view.effects.civilWarVictims.forEach { add("${role(it)}.civilWar") }
            view.effects.military.forEach { add("${role(it.player)}.military.${it.event.name}") }
            view.effects.papalCategory?.let { add("papal.${it.name}") }
            if (view.effects.crusadesPlayed) add("crusadesPlayed")
            if (view.effects.religiousStrifePlayed) add("religiousStrifePlayed")
            if (view.voids.armorAndStirrups) add("void.armorAndStirrups")
            if (view.voids.crusades) add("void.crusades")
            if (view.voids.papalDecree) add("void.papalDecree")
            if (view.activeWar != null) add("war")
            view.adjustments.forEach { (commodity, adjustments) ->
                adjustments.forEach {
                    add("adjustment.${commodity.name}.${it.name}")
                }
            }
        }
        val actions =
            observation.candidates.sortedBy(BotCandidate::command).map { candidate ->
                PurchaseAction(
                    candidate.command,
                    action(view, seat, TextCodec.parseCommand(candidate.command), ::role),
                )
            }
        return PurchaseProjection(
            stateFeatures = state,
            actions = actions,
        )
    }

    private fun action(
        view: GameView,
        seat: PlayerId,
        command: Command,
        role: (PlayerId) -> String,
    ): Map<String, Double> = features {
        val self = view.players.single { it.id == seat }
        when (command) {
            TurnCommand.Pass -> {
                add("kind.pass")
                add("cashAfter", self.cash / 200.0)
            }
            TurnCommand.UpgradeShips -> {
                val quote =
                    requireNotNull(view.shippingOffer) {
                        "Shipping action requires its authoritative offer."
                    }
                require(quote.blockers.isEmpty()) {
                    "A blocked shipping quote is not an offered action."
                }
                val after =
                    requireNotNull(quote.cashAfter) { "Shipping requires its cash endpoint." }
                val ships = requireNotNull(quote.ships) { "Shipping requires its next capability." }
                add("kind.shipping")
                add("cost", quote.cost / 200.0)
                add("cashAfter", after / 200.0)
                add("ships.${ships.name}")
            }
            is TurnCommand.BuyAdvance -> {
                val definition =
                    requireNotNull(view.catalog) {
                            "Advance encoding requires its public catalog."
                        }
                        .advances
                        .single { it.letter == command.letter }
                val quote = view.advanceOffers.single { it.advance == definition.id }
                val price =
                    requireNotNull(quote.price) {
                        "Offered advance requires an authoritative price."
                    }
                require(quote.unavailableReason == null) {
                    "A blocked advance is not an offered action."
                }
                add("kind.advance")
                add("advance.${definition.id.name}")
                add("category.${definition.category.name}")
                add("cost", price / 200.0)
                add("cashAfter", (self.cash - price) / 200.0)
                add("printedCost", definition.cost / 200.0)
                add("credit", definition.credit / 100.0)
                add("reliefRating", definition.miseryRelief / 100.0)
                quote.priceBreakdown?.let { detail ->
                    add("categoryCredit", detail.categoryCredit / 100.0)
                    add("researchCredit", detail.institutionalResearchCredit / 100.0)
                    add("leaderCredit", detail.leaders.sumOf { it.amount } / 100.0)
                }
                val ownMisery = quote.miseryChanges.filter { it.player == seat }
                if (ownMisery.any { it.toValue == null }) add("chaos")
                else {
                    add(
                        "miseryDelta",
                        ownMisery.sumOf { (it.toValue ?: 0) - (it.fromValue ?: 0) } / 1000.0,
                    )
                }
            }
            is CardCommand.PatronizeLeader -> {
                val quote = view.patronageOffers.single { it.command == command.toText() }
                add("kind.patronage")
                add("leader.${command.leader.cardId}")
                add("cost", quote.cost / 200.0)
                add("cashAfter", quote.payerCashAfter / 200.0)
                add("recipient.${role(quote.recipient)}")
            }
            else -> error("Unsupported offered purchase command: ${command.toText()}.")
        }
    }

    private class Features {
        val values = linkedMapOf<String, Double>()

        fun add(name: String, value: Double = 1.0) {
            val bounded = ((values[name] ?: 0.0) + value).coerceIn(-16.0, 16.0)
            if (bounded != 0.0) values[name] = bounded
        }
    }

    private fun features(block: Features.() -> Unit): Map<String, Double> =
        Features().apply(block).values.toMap()
}
