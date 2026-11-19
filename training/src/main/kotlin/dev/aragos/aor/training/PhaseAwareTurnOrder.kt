package dev.aragos.aor.training

import dev.aragos.aor.CardEffectData
import dev.aragos.aor.CardEffectSpec
import dev.aragos.aor.Command
import dev.aragos.aor.Commodity
import dev.aragos.aor.EconomyTables
import dev.aragos.aor.GameView
import dev.aragos.aor.PieceSide
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec

internal enum class TieOrderPolicy(val wire: String) {
    ORDINARY("ordinary"),
    EARLY("early"),
    LATE("late"),
    PHASE_AWARE("phase-aware"),
    ROOT_CONTROL("root-control"),
    ROOT_LATE("root-late"),
}

/** Experimentally change a tied slot after commitments have been revealed and paid. */
internal class PhaseAwareTurnOrder(
    private val reference: SimulationController,
    private val policy: TieOrderPolicy,
    private val sampleTurn: Int = 2,
) : SimulationController by reference {
    var firstTie: Map<String, Any>? = null
        private set

    private val preferences = mutableMapOf<String, Int>()

    override fun controls(decision: String): Boolean =
        decision == "choose-turn-position" || reference.controls(decision)

    override fun choose(decision: SimulationDecision): String? {
        fun ordinary(): String? =
            reference.takeIf { it.controls(decision.observation.decision) }?.choose(decision)

        val view = decision.view
        val turn = view.turnNumber
        if (decision.observation.decision != "choose-turn-position" || turn == null || turn < 2) {
            return ordinary()
        }
        val slots =
            decision.observation.candidates.mapNotNull { candidate ->
                TextCodec.parseCommand(candidate.command) as? Command.ChooseTurnPosition
            }
        if (slots.size < 2) return ordinary()
        val sampleOnce = policy == TieOrderPolicy.ROOT_CONTROL || policy == TieOrderPolicy.ROOT_LATE
        if (sampleOnce && (turn < sampleTurn || firstTie != null)) return ordinary()
        if (firstTie == null) {
            firstTie =
                mapOf(
                    "turn" to turn,
                    "purchases" to view.revealedTokenPurchases.mapKeys { it.key.value },
                    "pools" to view.players.associate { it.id.value to it.poolTokens },
                    "slots" to slots.map { it.slot },
                ) +
                    if (sampleOnce) {
                        mapOf(
                            "features" to
                                rootFeatures(
                                    view,
                                    PlayerId(decision.observation.playerId),
                                    slots.size,
                                )
                        )
                    } else emptyMap()
        }
        val preference =
            when (policy) {
                TieOrderPolicy.ORDINARY,
                TieOrderPolicy.ROOT_CONTROL -> return ordinary()
                TieOrderPolicy.EARLY -> "always-early"
                TieOrderPolicy.LATE -> "always-late"
                TieOrderPolicy.ROOT_LATE -> "single-late"
                TieOrderPolicy.PHASE_AWARE ->
                    phasePreference(view, PlayerId(decision.observation.playerId))
            }
        preferences[preference] = (preferences[preference] ?: 0) + 1
        return when (preference) {
            "protect-markets",
            "single-late",
            "always-late" -> slots.maxBy { it.slot }.toText()
            "pay-now",
            "cheap-card",
            "always-early" -> slots.minBy { it.slot }.toText()
            else -> ordinary()
        }
    }

    fun evidence(): Map<String, Any> =
        mapOf("preferences" to preferences, "firstTie" to firstTie.orEmpty())

    private fun rootFeatures(view: GameView, actor: PlayerId, slots: Int): Map<String, Int> {
        val self = view.players.first { it.id == actor }
        val own = view.scores.firstOrNull { it.player == actor }?.total ?: 0
        val rival =
            view.scores.filter { it.player != actor }.mapNotNull { it.total }.maxOrNull() ?: own
        val preference = phasePreference(view, actor)
        val amount = view.revealedTokenPurchases[actor]
        val before =
            view.revealedTokenPurchases.count { (p, bid) ->
                p != actor && amount != null && bid < amount
            }
        return mapOf(
            "turn" to (view.turnNumber ?: 0),
            "slots" to slots,
            "scoreLead" to own - rival,
            "cash" to self.cash,
            "pool" to self.poolTokens,
            "hand" to self.handCount,
            "ownedMarkers" to self.boardMarkers,
            "deck" to view.deckRemaining,
            "earlierUntiedSeats" to before,
            "immediateCommodity" to if (preference == "pay-now") 1 else 0,
            "thinHand" to if (preference == "cheap-card") 1 else 0,
            "concentratedMarkets" to if (preference == "protect-markets") 1 else 0,
        )
    }

    /** A bounded signal from current coloured holdings and the actor's own hand. */
    private fun phasePreference(view: GameView, actor: PlayerId): String {
        val catalog = view.catalog ?: return "ordinary"
        val definitions = catalog.provinces.associateBy { it.id }
        val values = catalog.commodities.associate { it.id to it.baseValue }
        val holdings = mutableMapOf<Commodity, MutableMap<PlayerId, Int>>()
        for (province in view.board) {
            val marker = province.marker ?: continue
            if (marker.side != PieceSide.COLOURED) continue
            for (commodity in definitions[province.id]?.commodities.orEmpty()) {
                val counts = holdings.getOrPut(commodity) { mutableMapOf() }
                counts[marker.owner] = (counts[marker.owner] ?: 0) + 1
            }
        }
        val expectedReceipts = holdings.mapValues { (commodity, counts) ->
            val own = counts[actor] ?: 0
            val rival = counts.filterKeys { it != actor }.values.maxOrNull() ?: 0
            val base = values[commodity] ?: 0
            base * (own * own - rival * rival)
        }
        val hasPayout =
            view.hand.any { card ->
                val effects = CardEffectData.forCard(card.definition.id)
                effects is CardEffectSpec.CommodityChoices &&
                    effects.choices.any { (expectedReceipts[it] ?: 0) >= 15 }
            }
        if (hasPayout) return "pay-now"
        val self = view.players.first { it.id == actor }
        val firstCardCost = EconomyTables.expansionCardCost(0)
        if (
            view.deckRemaining >= view.players.size &&
                view.hand.size <= 1 &&
                self.poolTokens >= firstCardCost
        ) {
            return "cheap-card"
        }
        return if (
            holdings.any { (commodity, counts) ->
                val own = counts[actor] ?: 0
                own >= 2 && (values[commodity] ?: 0) * own >= 8
            }
        )
            "protect-markets"
        else "ordinary"
    }
}

/** Record exact economic receipts from ordinary offers and which owned markers survived Income. */
internal class OrderPhaseEvidence {
    private var afterExpansion: Set<ProvinceId>? = null
    private var committedMarkets = 0L
    private var retainedMarkets = 0L
    private var expansionCardTokens = 0L
    private var ownCommodityCash = 0L

    fun observe(decision: SimulationDecision, player: PlayerId, command: String) {
        val view = decision.view
        when (view.stage) {
            Stage.TOKEN_PURCHASE -> {
                if (decision.observation.decision != "buy-tokens") return
                afterExpansion?.let { before ->
                    committedMarkets += before.size
                    retainedMarkets +=
                        view.board.count { it.id in before && it.marker?.owner == player }
                }
                afterExpansion = null
            }
            Stage.EXPANSION -> {
                if (decision.observation.decision != "expand") return
                if (command == "pass") {
                    afterExpansion =
                        view.board
                            .mapNotNull { province ->
                                province.id.takeIf { province.marker?.owner == player }
                            }
                            .toSet()
                }
                if (command == "buy-expansion-card") {
                    expansionCardTokens += view.expansionCardCost ?: 0
                }
            }
            Stage.PLAY_CARDS,
            Stage.FINAL_CARD_PLAY -> {
                if (!command.startsWith("play ")) return
                val offer =
                    view.cardOffers
                        .asSequence()
                        .filter { it.commodities.isNotEmpty() }
                        .flatMap { it.choices.asSequence() }
                        .firstOrNull { it.command == command }
                ownCommodityCash +=
                    offer
                        ?.cashChanges
                        ?.firstOrNull { it.player == player }
                        ?.let {
                            it.after.toLong() - it.before
                        } ?: 0L
            }
            else -> Unit
        }
    }

    fun evidence(): Map<String, Long> =
        mapOf(
            "expansionCardTokens" to expansionCardTokens,
            "markerObservations" to committedMarkets,
            "retainedMarkers" to retainedMarkets,
            "commodityCardReceipts" to ownCommodityCash,
        )
}
