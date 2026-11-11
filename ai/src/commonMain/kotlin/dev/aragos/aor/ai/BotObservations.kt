package dev.aragos.aor.ai

import dev.aragos.aor.AdvanceId
import dev.aragos.aor.CardChoiceKind
import dev.aragos.aor.CardChoiceView
import dev.aragos.aor.CardCommand
import dev.aragos.aor.CardKind
import dev.aragos.aor.CardOfferView
import dev.aragos.aor.ExpansionAction
import dev.aragos.aor.GameState
import dev.aragos.aor.GameView
import dev.aragos.aor.LegalChoices
import dev.aragos.aor.MapRule
import dev.aragos.aor.MiseryChangeView
import dev.aragos.aor.PlayerEconomy
import dev.aragos.aor.PlayerId
import dev.aragos.aor.Prompt
import dev.aragos.aor.ProvinceDefinition
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.RULES_VERSION
import kotlin.math.abs

/**
 * The authority adapter consults only an ordinary seat projection and its choices. It never reads
 * authoritative hands, pending bids, deck positions, or hidden outcomes. Projection is cheap;
 * evaluation, variation, and bounded planning run in the client.
 */
object BotObservations {
    fun from(state: GameState, seat: PlayerId): BotObservation {
        val prompt = state.prompts().singleOrNull()
        val decision =
            when (prompt) {
                is Prompt.SinglePlayer -> {
                    require(prompt.player == seat) {
                        "Only the deciding seat can request a bot move."
                    }
                    prompt.choice
                }
                is Prompt.MultiPlayer -> {
                    require(seat in prompt.players) {
                        "The seat has no outstanding sealed decision."
                    }
                    prompt.choice
                }
                else -> error("A bot observation requires a player decision.")
            }
        return from(
            state.viewForDecision(seat),
            state.legalChoices(seat),
            seat,
            decision,
            rulesVersion = state.header.rulesVersion,
        )
    }

    /**
     * Public projection entry point also supports isolated policy and privacy tests. [rulesVersion]
     * is the source game's public header version; callers projecting legacy games must retain it.
     */
    fun from(
        view: GameView,
        choices: LegalChoices,
        seat: PlayerId,
        decision: String,
        rulesVersion: String = RULES_VERSION,
    ): BotObservation = ObservationProjection(view, choices, seat, decision, rulesVersion).build()
}

private class ObservationProjection(
    private val view: GameView,
    private val choices: LegalChoices,
    private val seat: PlayerId,
    private val decision: String,
    private val rulesVersion: String,
) {
    private val self = view.players.single { it.id == seat }
    private val catalog =
        requireNotNull(view.catalog) { "Bot observations require a public catalog." }
    private val provinces = catalog.provinces.associateBy(ProvinceDefinition::id)
    private val endgame =
        if (view.endingTriggered) 1.0
        else if (view.currentEpoch == 3) (1.0 - view.deckRemaining / 18.0).coerceIn(0.0, 1.0)
        else 0.0
    private val stabilization = (view.stabilizationCost ?: 0L).toDouble()
    private val economic by lazy(LazyThreadSafetyMode.NONE) { BotEconomicProjection(view, seat) }
    private val purchases by lazy(LazyThreadSafetyMode.NONE) { BotPurchaseProjection(view, seat) }
    private val tokenValue by
        lazy(LazyThreadSafetyMode.NONE) {
            BotTokenValueProjection(view, seat, endgame)
        }
    private val conditions =
        listOf(
            "count.${view.players.size}",
            "epoch.${view.currentEpoch}",
            "map.${view.rules.map.commandValue}",
            "initial.${view.rules.initialCard.commandValue}",
        ) +
            listOfNotNull(
                "deckModified".takeIf { view.rules.deckModification },
                "immediateArt".takeIf { view.rules.immediateMasterArtDiscard },
            )

    fun build(): BotObservation {
        val commands =
            when (decision) {
                "configure" -> listOf("start")
                "bid-capital" -> (0..40).map { "bid-capital $it" }
                "buy-tokens" -> view.tokenPurchaseOffers.map { "buy-tokens ${it.amount}" }
                else -> choices.commands
            }
        val augmented = commands + expansionCommands() + subsetCommands() + cardCommands()
        val candidates = augmented.distinct().map(::candidate)
        require(candidates.isNotEmpty()) { "No bot candidate for $decision." }
        require(candidates.size <= 1024) { "Bot projection exceeded its candidate bound." }
        return BotObservation(
            playerId = seat.value,
            decision = decision,
            context =
                mapOf(
                    "cash" to self.cash.toDouble(),
                    "tokens" to self.poolTokens.toDouble(),
                    "stock" to self.stockTokens.toDouble(),
                    "misery" to (self.misery.value ?: 1000).toDouble(),
                    "income" to (self.income?.ordinary ?: 0).toDouble(),
                    "handCount" to self.handCount.toDouble(),
                    "advances" to self.advances.size.toDouble(),
                    "playerCount" to view.players.size.toDouble(),
                    "epoch" to view.currentEpoch.toDouble(),
                    "turn" to (view.turnNumber ?: 0).toDouble(),
                    "deckRemaining" to view.deckRemaining.toDouble(),
                    "stabilization" to stabilization,
                    "endgame" to endgame,
                    "european" to if (view.rules.map == MapRule.EUROPEAN) 1.0 else 0.0,
                    "deckModified" to if (view.rules.deckModification) 1.0 else 0.0,
                    "immediateArt" to if (view.rules.immediateMasterArtDiscard) 1.0 else 0.0,
                ),
            availableCash = self.cash,
            availableTokens = self.poolTokens,
            candidates = candidates,
            rulesVersion = rulesVersion,
            dataVersion = catalog.dataVersion,
            configuration =
                BotConfiguration(
                    playerCount = view.players.size,
                    map = view.rules.map,
                    initialCard = view.rules.initialCard,
                    deckModification = view.rules.deckModification,
                    immediateMasterArtDiscard = view.rules.immediateMasterArtDiscard,
                    drawThreeKeepTwo = view.rules.drawThreeKeepTwo,
                ),
            expansionPlanning = if (decision == "expand") economic.planning(candidates) else null,
            expansionPosition = if (decision == "expand") view.expansionPosition else null,
            reserveShortfallBasis =
                if (decision == "purchase-advances") {
                    contextualFeatures(mapOf("reserveShortfall" to 1.0))
                } else null,
            advancePlanning =
                if (decision == "purchase-advances") {
                    BotAdvancePlanningProjection.from(view, seat, candidates)
                } else null,
            leaderPlanning =
                if (decision == "play-cards") {
                    BotLeaderProjection.from(view, seat, candidates, endgame)
                } else null,
        )
    }

    private fun expansionCommands(): List<String> {
        if (decision != "expand") return emptyList()
        return view.expansionOffers.flatMap { offer ->
            val primary =
                if (offer.blockers.isEmpty() && offer.requiredTokens > 0) {
                    when (offer.primaryAction) {
                        ExpansionAction.PLACE ->
                            "place ${offer.requiredTokens} ${offer.destination}"
                        ExpansionAction.ATTACK -> "attack ${offer.destination}"
                        ExpansionAction.INSPECT -> null
                    }
                } else null
            listOfNotNull(primary) +
                offer.partialPlacements.map {
                    "place ${it.tokens} ${offer.destination}"
                }
        }
    }

    private fun subsetCommands(): List<String> =
        when (decision) {
            "replace-event-token" -> {
                val replacement =
                    requireNotNull(view.replacementToken) {
                        "An event replacement decision requires its projected constraints."
                    }
                val ordered = replacement.provinces.sortedByDescending(::provinceValue)
                (listOf(emptyList<ProvinceId>()) +
                        (1..replacement.maxReplacements).flatMap { count ->
                            boundedSubsets(ordered, count)
                        })
                    .map { CardCommand.ReplaceEventTokens(it.toSet()).toText() }
            }
            "war-surrender" -> {
                val surrender =
                    requireNotNull(view.warCession) {
                        "A war surrender requires its projected constraints."
                    }
                val ordered = surrender.eligibleProvinces.sortedBy(::provinceValue)
                boundedSubsets(ordered, surrender.requiredCount)
                    .filter { selected ->
                        selected.count { provinces[it]?.overseas != null } <=
                            surrender.overseasCapacity
                    }
                    .map { CardCommand.CedeWar(it.toSet()).toText() }
            }
            else -> emptyList()
        }

    private fun cardCommands(): List<String> {
        if (decision !in setOf("play-cards", "prepare-war")) return emptyList()
        return view.cardOffers.filter(CardOfferView::playable).flatMap { offer ->
            // The military preparation projection is broad; retain the driver's military filter.
            if (
                decision == "prepare-war" &&
                    choices.commands.none { it.startsWith("play ${offer.reference}") }
            ) {
                return@flatMap emptyList()
            }
            val targets =
                offer.requiredTargetCount
                    ?.let { count ->
                        boundedSubsets(
                                offer.provinceTargets.sortedByDescending { province ->
                                    val owner =
                                        view.board.firstOrNull { it.id == province }?.marker?.owner
                                    provinceValue(province) * if (owner == seat) -2.0 else 1.0
                                },
                                count,
                            )
                            .map { subset ->
                                "play ${offer.reference} provinces ${subset.joinToString(" ")}"
                            }
                    }
                    .orEmpty()
            val protection =
                offer.protectionMax
                    ?.let { maximum ->
                        listOf(0, 1, 5, 10, 20, maximum)
                            .distinct()
                            .filter { it in 0..maximum }
                            .map { "play ${offer.reference} protect $it" }
                    }
                    .orEmpty()
            offer.choices.mapNotNull(CardChoiceView::command) + targets + protection
        }
    }

    private fun candidate(command: String): BotCandidate {
        val parts = command.split(' ')
        val features = mutableMapOf<String, Double>()
        var cashCost = 0
        var tokenCost = 0
        var family = parts.first()
        var group = command
        var playerTarget: PlayerId? = null
        var leaderCash: BotLeaderCash? = null
        fun add(name: String, amount: Double) {
            if (amount != 0.0) features[name] = (features[name] ?: 0.0) + amount
        }
        when (parts.first()) {
            "bid-capital" -> {
                cashCost = parts[1].toInt()
                add("cash", -cashCost.toDouble())
                add("bidPreference", -abs(cashCost - 3).toDouble())
            }
            "buy-tokens" -> {
                val amount = parts[1].toInt()
                val quote =
                    requireNotNull(view.tokenPurchaseOffers.firstOrNull { it.amount == amount }) {
                        "A token purchase candidate requires an owner-visible quote."
                    }
                cashCost = quote.cost
                add("cash", -cashCost.toDouble())
                add("tokens", quote.tokensReceived * 0.6)
                purchases.tokens(quote).forEach { (name, value) -> add(name, value) }
                features.putAll(tokenValue.features(quote))
            }
            "choose-capital" -> {
                val province = provinces[ProvinceId(parts[1])]
                add("capital.${parts[1]}", 1.0)
                add(
                    "commodity",
                    province?.commodities?.sumOf(economic.expansionValues::commodityMarginal)
                        ?: 0.0,
                )
                val connections =
                    catalog.landBorders.count {
                        it.first == province?.id || it.second == province?.id
                    }
                add("reach", connections.toDouble())
                val neighbours = landNeighbours(ProvinceId(parts[1]))
                val nearby = (neighbours + neighbours.flatMap(::landNeighbours)).toSet()
                val available =
                    nearby
                        .mapNotNull { provinces[it] }
                        .filter { candidate ->
                            view.players.none { it.capital == candidate.id.value } &&
                                candidate.access.all { it.isSatisfiedBy(self.advances) }
                        }
                add(
                    "capitalOpportunity",
                    available.sumOf { candidate ->
                        (candidate.commodities.sumOf(economic.expansionValues::commodityMarginal) +
                            2.0) / candidate.marketNumber
                    },
                )
                for (card in view.hand) {
                    // Keep the 6 x 64 private hand/capital signatures separate from rule-condition
                    // cross products; capital one-hots and numeric signals carry those contexts.
                    add("capitalHand.${parts[1]}.${card.definition.id}", 1.0)
                    val inspection =
                        view.cardInspections.single { it.definition.id == card.definition.id }
                    add(
                        "initialCommodity",
                        inspection.commodities
                            .filter {
                                it in province?.commodities.orEmpty()
                            }
                            .sumOf(economic.expansionValues::commodityMarginal),
                    )
                    add(
                        "capitalCommodityPotential",
                        available.sumOf { candidate ->
                            candidate.commodities
                                .intersect(inspection.commodities)
                                .sumOf(economic.expansionValues::commodityMarginal) /
                                candidate.marketNumber
                        },
                    )
                    add(
                        "capitalLeaderOpportunity",
                        (inspection.leaderDiscounts.maxOfOrNull { it.effectiveDiscount } ?: 0) *
                            available.size / 10.0,
                    )
                }
            }
            "choose-turn-position" -> add("order", 7.0 - parts[1].toInt())
            "swap-order" -> {
                val target = PlayerId(parts[1])
                add(
                    "order",
                    (view.turnOrder.indexOf(seat) - view.turnOrder.indexOf(target)).toDouble(),
                )
            }
            "buy-advance" -> {
                family = "advance"
                val advance = AdvanceId.fromLetter(parts[1].single())
                group = "advance.${advance.name}"
                val quote =
                    requireNotNull(view.advanceOffers.firstOrNull { it.advance == advance }) {
                        "An advance candidate requires its owner-visible offer."
                    }
                val definition = catalog.advances.single { it.id == advance }
                cashCost = requireNotNull(quote.price) { "A legal advance requires a price." }
                add("cash", -cashCost.toDouble())
                add("points", definition.cost.toDouble())
                add("discount", definition.credit * (1.0 - endgame) * 0.8)
                addMisery(features, quote.miseryChanges)
                features.putAll(
                    purchases.finalAdvanceScore(definition.cost, cashCost, quote.miseryChanges)
                )
                add(
                    "reach",
                    when (advance) {
                        AdvanceId.THE_HEAVENS,
                        AdvanceId.CARAVAN -> 3.0
                        AdvanceId.OVERLAND_EAST,
                        AdvanceId.SEAWORTHY_VESSELS -> 7.0
                        AdvanceId.OCEAN_NAVIGATION,
                        AdvanceId.NEW_WORLD -> 12.0
                        else -> 0.0
                    },
                )
                purchases.advanceIncome(advance, cashCost, endgame).forEach { (name, value) ->
                    add(name, value)
                }
                add(
                    "military",
                    when (advance) {
                        AdvanceId.PROSELYTISM,
                        AdvanceId.CATHEDRAL,
                        AdvanceId.NATIONALISM -> 1.0
                        else -> 0.0
                    },
                )
                add("advance.${advance.letter}", 1.0)
            }
            "upgrade-ships" -> {
                val offer =
                    requireNotNull(view.shippingOffer) { "A ship upgrade requires its quote." }
                cashCost = offer.cost
                add("cash", -cashCost.toDouble())
                add("reach", 6.0)
                purchases.shipping(offer).forEach { (name, value) -> add(name, value) }
            }
            "stabilize" -> {
                if (parts[1] == "cash") {
                    cashCost = stabilization.toInt()
                    add("cash", -stabilization)
                    add("stabilizeCash", 1.0)
                } else {
                    if (view.stabilizationMisery == null) add("chaos", 1.0)
                    else
                        add(
                            "misery",
                            (view.stabilizationMisery ?: 0).toDouble() -
                                (self.misery.value ?: 1000),
                        )
                }
            }
            "relieve-misery" -> {
                val offer = view.reliefOffers.single { it.spaces == parts[1].toInt() }
                cashCost = offer.cashCost
                add("cash", -cashCost.toDouble())
                add(
                    "misery",
                    (offer.miseryAfter ?: 1000).toDouble() - (offer.miseryBefore ?: 1000),
                )
            }
            "place",
            "attack" -> {
                family = "expansion"
                val province = ProvinceId(parts.last())
                group = "province.$province"
                val offer =
                    requireNotNull(
                        view.expansionOffers.firstOrNull { it.destination == province }
                    ) {
                        "An expansion candidate requires its owner-visible offer."
                    }
                tokenCost =
                    if (parts.first() == "attack") offer.requiredTokens else parts[1].toInt()
                features.putAll(
                    economic.expansionValues.nominalFeatures(
                        province,
                        BotExpansionActionInputs.from(offer),
                        tokenCost,
                    )
                )
                economic.expansion(province, offer, tokenCost).forEach { (name, amount) ->
                    add(name, amount)
                }
            }
            "buy-card",
            "buy-expansion-card" -> {
                if (parts.first() == "buy-card") cashCost = view.cardPurchaseOffer?.cost ?: 0
                else tokenCost = view.expansionCardCost ?: 0
                add("cash", -cashCost.toDouble())
                add("tokens", -tokenCost.toDouble())
                add("cards", 1.0)
                add("misery", stabilizationIncrease())
            }
            "return-card",
            "discard-card",
            "offer-card" -> {
                val card = view.hand.single { it.reference == parts[1] }
                group = "card.${card.reference}"
                add("cards", -cardValue(card.reference) / 7.5)
                if (card.reference in view.burdenReferences) add("burden", -1.0)
                if (parts.first() == "offer-card") add("cards", 1.0)
                else add("cash", stabilizationReduction())
            }
            "play" -> {
                group = "card.${parts[1]}"
                val offer = view.cardOffers.single { it.reference == parts[1] }
                val card = view.hand.single { it.reference == parts[1] }
                val choice = offer.choices.firstOrNull { it.command == command }
                if (choice?.kind == CardChoiceKind.PLAYER) {
                    playerTarget =
                        PlayerId(
                            requireNotNull(choice.value) {
                                "An authorized player card choice requires its target."
                            }
                        )
                }
                add("card.${card.definition.id}", 1.0)
                add("progress", 1.0)
                add("cash", stabilizationReduction())
                if (choice != null) {
                    economic.area(choice).forEach { (name, value) -> add(name, value) }
                    addMisery(features, choice.miseryChanges)
                    if (card.definition.kind != CardKind.LEADER) {
                        choice.cashChanges.forEach { change ->
                            add(
                                if (change.player == seat) "cash" else "opponentCash",
                                (change.after - change.before).toDouble(),
                            )
                        }
                    }
                } else {
                    addMisery(features, offer.miseryChanges)
                }
                if (card.definition.kind == CardKind.LEADER) {
                    add(
                        "discount",
                        offer.leaderDiscounts
                            .filter { it.advance !in self.advances }
                            .maxOfOrNull { it.effectiveDiscount }
                            ?.toDouble() ?: 0.0,
                    )
                    if (parts.getOrNull(2) == "protect") {
                        cashCost = parts[3].toInt()
                        add("protection", cashCost.toDouble())
                    }
                    val outcome = BotLeaderCash.from(offer, seat, self.cash, cashCost)
                    leaderCash = outcome
                    add("cash", outcome.change.toDouble())
                }
                if (parts.getOrNull(2) == "provinces" || parts.getOrNull(2) == "province") {
                    parts.drop(3).map(::ProvinceId).forEach { province ->
                        val owner = view.board.firstOrNull { it.id == province }?.marker?.owner
                        add("commodity", provinceValue(province) * if (owner == seat) -2.0 else 0.6)
                    }
                }
                if (card.definition.id in setOf("armor", "stirrups", "long-bow", "gunpowder")) {
                    add("military", 1.0)
                }
                economic.card(card.reference).forEach { (name, amount) -> add(name, amount) }
            }
            "replace-event-tokens" -> {
                val selected = parts.drop(1).filterNot { it == "none" }.map(::ProvinceId)
                tokenCost = selected.size * (view.replacementToken?.poolCost ?: 1)
                add("tokens", -tokenCost.toDouble())
                add("reach", selected.sumOf { provinceValue(it) } * 0.6)
            }
            "cede-war" -> {
                val selected = parts.drop(1).map(::ProvinceId)
                add("commodity", -selected.sumOf { provinceValue(it) })
                add("income", -selected.size * 2.0)
            }
            "civil-war-loss" -> {
                val loss =
                    requireNotNull(view.civilWarLoss) { "A Civil War loss requires its quote." }
                if (parts[1] == "cash") {
                    cashCost = loss.cashCost
                    add("cash", -cashCost.toDouble())
                } else {
                    tokenCost = loss.tokenCost
                    add("tokens", -tokenCost.toDouble())
                }
            }
            "move-marker" -> {
                add("commodity", -provinceValue(ProvinceId(parts[1])))
                add("reach", 1.0)
            }
            "forgo-marker" -> add("income", -4.0)
            "cathedral" -> add("cathedral", 1.0)
            "windmill" -> {
                val offer =
                    requireNotNull(view.windmillOffer) {
                        "A Wind/Watermill attempt requires its quote."
                    }
                tokenCost = offer.tokenCost
                add("tokens", -tokenCost.toDouble())
                add("windmill", 1.0)
                val worst = view.hand.minOfOrNull { cardValue(it.reference) } ?: 7.5
                add("cards", (7.5 - worst) / 7.5 * offer.successProbability)
            }
            "patronize" -> {
                val quote = view.patronageOffers.single { it.command == command }
                cashCost = quote.cost
                add("cash", -cashCost.toDouble())
                add("opponentCash", cashCost.toDouble())
                val leader = view.playedLeaderDiscounts.firstOrNull { it.leader.cardId == parts[1] }
                add(
                    "discount",
                    leader
                        ?.discounts
                        ?.filter { it.advance !in self.advances }
                        ?.maxOfOrNull { it.effectiveDiscount }
                        ?.toDouble() ?: 0.0,
                )
            }
            "cancel-adjustment" -> {
                val quote = view.adjustmentOffers.single { it.command == command }
                cashCost = quote.cost
                add("cash", -cashCost.toDouble())
                add(
                    "commodity",
                    quote.commodities.sumOf { commodity ->
                        val owned =
                            view.commodityPayouts
                                .firstOrNull { it.commodity == commodity }
                                ?.owners
                                ?.firstOrNull { it.player == seat }
                                ?.marketCount ?: 0
                        owned.toDouble()
                    },
                )
            }
            "reduce-marker" -> {
                val quote = view.markerReductionOffers.single { it.command == command }
                add("income", (quote.incomeAfter ?: 0).toDouble() - (quote.incomeBefore ?: 0))
                add("commodity", -provinceValue(quote.province))
                add("tokens", (quote.poolAfter - quote.poolBefore).toDouble())
            }
        }
        val cashAfter = leaderCash?.after ?: (self.cash - cashCost)
        val interest =
            if (leaderCash == null) purchases.foregoneInterest(cashCost)
            else purchases.interestAfterCash(cashAfter)
        interest.forEach { (name, value) -> add(name, value) }
        if (cashAfter != self.cash && decision != "purchase-stabilization") {
            val before = (stabilization - self.cash).coerceAtLeast(0.0)
            add(
                "reserveShortfall",
                (stabilization - cashAfter).coerceAtLeast(0.0) - before,
            )
        }
        add("action.$family", 1.0)
        return BotCandidate(
            command,
            contextualFeatures(features),
            cashCost,
            tokenCost,
            group,
            family,
            playerTarget,
        )
    }

    /** Candidate values and the per-dollar reserve basis share identical context expansion. */
    private fun contextualFeatures(base: Map<String, Double>): Map<String, Double> = buildMap {
        putAll(base)
        for ((feature, value) in base) {
            if (feature.startsWith("capitalHand.") || feature.startsWith("planning.")) continue
            if (endgame != 0.0) put("endgame.$feature", value * endgame)
            for (condition in conditions) put("$condition.$feature", value)
        }
    }

    private fun addMisery(features: MutableMap<String, Double>, changes: List<MiseryChangeView>) {
        for (change in changes) {
            val own = change.player == seat
            val name = if (own) "misery" else "opponentMisery"
            if (own && change.toValue == null) features["chaos"] = 1.0
            val delta = ((change.toValue ?: 1200) - (change.fromValue ?: 1000)).toDouble()
            features[name] = (features[name] ?: 0.0) + delta
        }
    }

    private fun provinceValue(province: ProvinceId): Double {
        val definition = provinces[province] ?: return 0.0
        return 2.0 +
            definition.commodities.sumOf(economic.expansionValues::commodityMarginal) * 0.25 +
            if (self.capital == province.value) 8.0 else 0.0
    }

    private fun landNeighbours(province: ProvinceId): Set<ProvinceId> =
        catalog.landBorders
            .mapNotNull { connection ->
                when (province) {
                    connection.first -> connection.second
                    connection.second -> connection.first
                    else -> null
                }
            }
            .toSet()

    private fun cardValue(reference: String): Double {
        if (reference in view.burdenReferences) return 0.0
        val offer = view.cardOffers.firstOrNull { it.reference == reference }
        val ownCash =
            offer?.choices?.maxOfOrNull { choice ->
                choice.cashChanges.filter { it.player == seat }.sumOf { it.after - it.before }
            } ?: 0
        return maxOf(4.0, ownCash.toDouble())
    }

    private fun stabilizationReduction(): Double =
        (PlayerEconomy.stabilizationCost(self.handCount, AdvanceId.MIDDLE_CLASS in self.advances) -
                PlayerEconomy.stabilizationCost(
                    (self.handCount - 1).coerceAtLeast(0),
                    AdvanceId.MIDDLE_CLASS in self.advances,
                ))
            .toDouble()

    private fun stabilizationIncrease(): Double =
        (PlayerEconomy.stabilizationCost(
                self.handCount + 1,
                AdvanceId.MIDDLE_CLASS in self.advances,
            ) -
                PlayerEconomy.stabilizationCost(
                    self.handCount,
                    AdvanceId.MIDDLE_CLASS in self.advances,
                ))
            .toDouble() * 0.3
}

/** Linear-sized alternatives cover every target in at least one subset, without 2^n enumeration. */
private fun <T> boundedSubsets(ordered: List<T>, count: Int): List<List<T>> {
    if (count == 0) return listOf(emptyList())
    if (count > ordered.size) return emptyList()
    val best = ordered.take(count)
    return (listOf(best) +
            ordered.drop(count).map { alternative ->
                best.dropLast(1) + alternative
            } +
            ordered.reversed().take(count).let(::listOf))
        .distinct()
        .take(128)
}
