package dev.aragos.aor.ai

import dev.aragos.aor.BoardCommand
import dev.aragos.aor.Commodity
import dev.aragos.aor.GameView
import dev.aragos.aor.PlayerId
import dev.aragos.aor.ProvinceId
import dev.aragos.aor.PublicCatalog
import dev.aragos.aor.Stage
import dev.aragos.aor.TextCodec
import dev.aragos.aor.TurnCommand

/**
 * Actor-safe signed commitment quotes. Preserve every currently offered amount, not just the six
 * older value-head proposals. The core offers all useful amounts through min(cash, stock), their
 * negative counterparts, and both full-cash endpoints; it does not enumerate every legal integer
 * when cash exceeds stock. Forecasts describe current capabilities, never pending rival orders or
 * guaranteed future routes. Cash uses dollars/200; tokens/36; all features clip at +/-16.
 */
object TokenObservationEncoder {
    const val ENCODER_VERSION = "aor-token-observation-1"
    /** Public capital priority is one-based in six-player units; absent roles remain zero. */
    val capitalChoiceNames = (0..5).map { "role.$it.capitalChoicePosition" }
    /** Exact physical-stock-sized bids plus two bins for the offered full-cash endpoints. */
    val bidAmountNames =
        (-36..36).map { "bid.amount.$it" } + listOf("bid.amount.below", "bid.amount.above")
    private val amounts =
        listOf("amount", "cost", "received", "cashAfter", "stockAfter", "poolAfter")
    private val prospectFields = listOf("present", "required", "shipping", "overseas")

    fun dictionary(
        catalog: PublicCatalog,
        encoder: TerritoryEncoder = TerritoryEncoder.TOKEN_1,
    ): TerritoryFeatureDictionary {
        require(encoder.phase == TerritoryPhase.TOKEN) {
            "A token dictionary requires a token encoder."
        }
        return TerritoryFeatureDictionary(
            (RawActorState.dictionary(catalog, listOf(TerritoryPhase.TOKEN.decision)) +
                    "forecast.available" +
                    catalog.provinces.flatMap { province ->
                        listOf("peaceful", "contested").flatMap { kind ->
                            prospectFields.map { "prospect.${province.id.value}.$kind.$it" }
                        }
                    } +
                    if (encoder == TerritoryEncoder.TOKEN_2) capitalChoiceNames else emptyList())
                .distinct()
                .sorted(),
            (amounts +
                    listOf("kind.token", "negative", "zero", "positive") +
                    if (encoder == TerritoryEncoder.TOKEN_2) bidAmountNames else emptyList())
                .sorted(),
        )
    }

    fun from(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        baselineCommand: String,
        encoder: TerritoryEncoder = TerritoryEncoder.TOKEN_1,
    ): TerritoryObservation {
        require(encoder.phase == TerritoryPhase.TOKEN) {
            "Token observations require a token encoder."
        }
        require(
            view.stage == Stage.TOKEN_PURCHASE &&
                observation.decision == TerritoryPhase.TOKEN.decision &&
                observation.playerId == seat.value
        ) {
            "Token encoding requires the actual acting seat's commitment window."
        }
        val state =
            TerritoryFeatures()
                .apply {
                    values.putAll(RawActorState.from(view, seat, observation.decision))
                    if (encoder == TerritoryEncoder.TOKEN_2) {
                        val ownIndex = view.players.indexOfFirst { it.id == seat }
                        view.players.forEachIndexed { index, player ->
                            val role = (index - ownIndex + view.players.size) % view.players.size
                            val position = view.capitalOrder.indexOf(player.id)
                            require(position >= 0) {
                                "Token-order encoding requires public capital priority."
                            }
                            add("role.$role.capitalChoicePosition", (position + 1) / 6.0)
                        }
                    }
                    view.expansionForecast?.let { forecast ->
                        add("forecast.available")
                        fun prospect(
                            id: ProvinceId,
                            kind: String,
                            required: Int,
                            shipping: Boolean,
                            overseas: Boolean,
                        ) {
                            val prefix = "prospect.${id.value}.$kind"
                            add("$prefix.present")
                            add("$prefix.required", required / 36.0)
                            if (shipping) add("$prefix.shipping")
                            if (overseas) add("$prefix.overseas")
                        }
                        forecast.peacefulMarkets.forEach {
                            prospect(
                                it.destination,
                                "peaceful",
                                it.requiredTokens,
                                it.shipping,
                                it.usesOverseasSlot,
                            )
                        }
                        forecast.contestedMarkets.forEach {
                            prospect(
                                it.destination,
                                "contested",
                                it.requiredTokens,
                                it.shipping,
                                it.usesOverseasSlot,
                            )
                        }
                    }
                }
                .values
                .toMap()
        val offers = view.tokenPurchaseOffers.associateBy { "buy-tokens ${it.amount}" }
        val actions =
            observation.candidates.sortedBy(BotCandidate::command).map { candidate ->
                val quote =
                    requireNotNull(offers[candidate.command]) {
                        "Every token command needs its authoritative owner-visible quote."
                    }
                TerritoryAction(
                    candidate.command,
                    TerritoryFeatures()
                        .apply {
                            add("kind.token")
                            add("amount", quote.amount / 36.0)
                            add("cost", quote.cost / 200.0)
                            add("received", quote.tokensReceived / 36.0)
                            add("cashAfter", quote.cashAfter / 200.0)
                            add("stockAfter", quote.stockAfter / 36.0)
                            add("poolAfter", quote.poolAfter / 36.0)
                            if (encoder == TerritoryEncoder.TOKEN_2) {
                                add(
                                    when {
                                        quote.amount < -36 -> "bid.amount.below"
                                        quote.amount > 36 -> "bid.amount.above"
                                        else -> "bid.amount.${quote.amount}"
                                    }
                                )
                            }
                            add(
                                when {
                                    quote.amount < 0 -> "negative"
                                    quote.amount == 0 -> "zero"
                                    else -> "positive"
                                }
                            )
                        }
                        .values
                        .toMap(),
                )
            }
        require(actions.map(TerritoryAction::command).toSet() == offers.keys) {
            "Token encoding must retain the complete offered quote set."
        }
        return TerritoryObservation(
            encoder.version,
            state,
            actions,
            actions.indexOfFirst { it.command == baselineCommand },
        )
    }
}

/**
 * Every currently offered expansion command, including partial placement, attacks, reductions, card
 * purchases and Pass, is preserved in canonical order. Quotes are rebuilt after each actual
 * transition. No attack outcome, hidden card, future draw or stronger future capability is
 * supplied. Partial offers expose their own pool endpoint; primary-route/income quotes are not
 * misapplied to those different commitments. Dollars/income use200, tokens36, markers25 and market
 * sizes6.
 */
object ExpansionObservationEncoder {
    const val ENCODER_VERSION = "aor-expansion-observation-1"
    internal val cathedralUsageNames =
        (0..5).flatMap { owner ->
            (0..5)
                .filter { it != owner }
                .map { opponent ->
                    "cathedral.used.role.$owner.role.$opponent"
                }
        }
    private val amounts =
        listOf(
            "cost",
            "cashAfter",
            "poolAfter",
            "stockAfter",
            "tokens",
            "required",
            "market",
            "incomeBefore",
            "incomeAfter",
            "shippingUsed",
            "shippingCapacity",
            "shippingRequired",
            "winProbability",
        )
    private val flags =
        listOf(
            "partial",
            "uncertain",
            "establishesMarker",
            "satellite",
            "overseas",
            "incomeBeforeKnown",
            "incomeAfterKnown",
            "shipping",
            "shippingUnlimited",
            "winProbabilityKnown",
            "unknownCard",
        )

    /** Version 2 adds public current-turn Cathedral usage; version 1 is byte-for-byte unchanged. */
    fun dictionary(
        catalog: PublicCatalog,
        encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_1,
    ): TerritoryFeatureDictionary {
        require(encoder.phase == TerritoryPhase.EXPANSION) {
            "An expansion dictionary requires an expansion encoder."
        }
        val raw = RawActorState.dictionary(catalog, listOf(TerritoryPhase.EXPANSION.decision))
        return TerritoryFeatureDictionary(
            if (encoder == TerritoryEncoder.EXPANSION_2) (raw + cathedralUsageNames).sorted()
            else raw,
            (amounts +
                    flags +
                    listOf("pass", "place", "attack", "reduce", "buyCard").map { "kind.$it" } +
                    catalog.provinces.map { "destination.${it.id.value}" } +
                    Commodity.entries.map { "commodity.${it.name}" })
                .distinct()
                .sorted(),
        )
    }

    fun from(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        baselineCommand: String,
        encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_1,
    ): TerritoryObservation = project(view, seat, observation, encoder).bindBaseline(baselineCommand)

    /** Project the acting player's ordinary view without choosing or transporting a baseline. */
    fun project(
        view: GameView,
        seat: PlayerId,
        observation: BotObservation,
        encoder: TerritoryEncoder = TerritoryEncoder.EXPANSION_1,
    ): ExpansionProjection {
        require(encoder.phase == TerritoryPhase.EXPANSION) {
            "Expansion observations require an expansion encoder."
        }
        require(
            view.stage == Stage.EXPANSION &&
                observation.decision == TerritoryPhase.EXPANSION.decision &&
                observation.playerId == seat.value
        ) {
            "Expansion encoding requires the acting seat's ordinary expansion window."
        }
        val catalog =
            requireNotNull(view.catalog) { "Expansion encoding requires the public catalog." }
        val self = view.players.single { it.id == seat }
        val actions =
            observation.candidates.sortedBy(BotCandidate::command).map { candidate ->
                val command = TextCodec.parseCommand(candidate.command)
                val features = TerritoryFeatures()
                fun destination(id: ProvinceId) {
                    val province = catalog.provinces.single { it.id == id }
                    features.add("destination.${id.value}")
                    features.add("market", province.marketNumber / 6.0)
                    province.commodities.forEach { features.add("commodity.${it.name}") }
                    if (province.isSatellite) features.add("satellite")
                    if (province.overseas != null) features.add("overseas")
                }
                fun placement(id: ProvinceId, tokens: Int) {
                    val offer = view.expansionOffers.single { it.destination == id }
                    val partial = offer.partialPlacements.firstOrNull { it.tokens == tokens }
                    require(
                        partial != null ||
                            tokens == offer.requiredTokens && offer.blockers.isEmpty()
                    ) {
                        "An offered placement requires its own feasible primary or partial quote."
                    }
                    destination(id)
                    features.add("tokens", tokens / 36.0)
                    features.add("required", offer.requiredTokens / 36.0)
                    features.add(
                        "poolAfter",
                        (partial?.resultingPool
                            ?: requireNotNull(offer.resultingPool) {
                                "Primary placement requires its quoted pool endpoint."
                            }) / 36.0,
                    )
                    offer.incomeBefore?.let {
                        features.add("incomeBeforeKnown")
                        features.add("incomeBefore", it / 200.0)
                    }
                    if (partial != null) features.add("partial")
                    else {
                        if (offer.establishesMarker) features.add("establishesMarker")
                        offer.incomeAfter?.let {
                            features.add("incomeAfterKnown")
                            features.add("incomeAfter", it / 200.0)
                        }
                        offer.shipping?.let { shipping ->
                            features.add("shipping")
                            features.add("shippingUsed", shipping.used / 36.0)
                            features.add("shippingRequired", shipping.required / 36.0)
                            shipping.capacity?.let { features.add("shippingCapacity", it / 36.0) }
                                ?: features.add("shippingUnlimited")
                        }
                    }
                    if (offer.uncertain) features.add("uncertain")
                    offer.competitionWinProbability?.let {
                        features.add("winProbabilityKnown")
                        features.add("winProbability", it)
                    }
                }
                when (command) {
                    TurnCommand.Pass -> features.add("kind.pass")
                    is BoardCommand.Place -> {
                        features.add("kind.place")
                        placement(command.province, command.tokens)
                    }
                    is BoardCommand.Attack -> {
                        features.add("kind.attack")
                        placement(
                            command.province,
                            view.expansionOffers
                                .single { it.destination == command.province }
                                .requiredTokens,
                        )
                    }
                    is BoardCommand.ReduceMarker -> {
                        val quote =
                            view.markerReductionOffers.single { it.command == candidate.command }
                        features.add("kind.reduce")
                        destination(command.province)
                        features.add("poolAfter", quote.poolAfter / 36.0)
                        features.add("stockAfter", quote.stockAfter / 36.0)
                        quote.incomeBefore?.let {
                            features.add("incomeBeforeKnown")
                            features.add("incomeBefore", it / 200.0)
                        }
                        quote.incomeAfter?.let {
                            features.add("incomeAfterKnown")
                            features.add("incomeAfter", it / 200.0)
                        }
                    }
                    BoardCommand.BuyExpansionCard -> {
                        val cost =
                            requireNotNull(view.expansionCardCost) {
                                "An offered expansion-card purchase requires its actual token cost."
                            }
                        features.add("kind.buyCard")
                        features.add("unknownCard")
                        features.add("tokens", cost / 36.0)
                        features.add("poolAfter", (self.poolTokens - cost) / 36.0)
                    }
                    else ->
                        error(
                            "Nested or unsupported command cannot enter ordinary expansion: ${candidate.command}."
                        )
                }
                features.add("cashAfter", self.cash / 200.0)
                TerritoryAction(candidate.command, features.values.toMap())
            }
        val raw = RawActorState.from(view, seat, observation.decision)
        val state =
            if (encoder == TerritoryEncoder.EXPANSION_2) {
                buildMap {
                    putAll(raw)
                    view.cathedralPairs
                        .filter { it.used }
                        .forEach { pair ->
                            val owner = RawActorState.role(view, seat, pair.owner)
                            val opponent = RawActorState.role(view, seat, pair.opponent)
                            put("cathedral.used.$owner.$opponent", 1.0)
                        }
                }
            } else raw
        return ExpansionProjection(
            encoder.version,
            state,
            actions,
        )
    }
}

private class TerritoryFeatures {
    val values = linkedMapOf<String, Double>()

    fun add(name: String, value: Double = 1.0) {
        val bounded = ((values[name] ?: 0.0) + value).coerceIn(-16.0, 16.0)
        if (bounded != 0.0) values[name] = bounded else values.remove(name)
    }
}
