package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceCategory
import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardCommand
import dev.aragos.aor.Command
import dev.aragos.aor.GameView
import dev.aragos.aor.LeaderId
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

    fun dictionary(view: GameView): PurchaseFeatureDictionary =
        dictionary(
            requireNotNull(view.catalog) {
                "Purchase encoding requires the ordinary public catalog."
            }
        )

    /** Vocabulary depends on the public setup/catalog, never on sampled trajectory nonzeros. */
    fun dictionary(catalog: PublicCatalog): PurchaseFeatureDictionary {
        val state = RawActorState.dictionary(catalog, decisions)
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
        val state = RawActorState.from(view, seat, observation.decision)
        fun role(id: PlayerId): String = RawActorState.role(view, seat, id)
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
