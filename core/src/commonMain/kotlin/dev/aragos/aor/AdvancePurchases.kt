package dev.aragos.aor

/**
 * A leader discount already authorized for this buyer by card-play and Patronage rules. [amount] is
 * the conditional base dollar discount, before Written Record. [leaderCardId] is the physical
 * card's stable catalog id. Protection money and access to the Leader Display are resolved by the
 * caller, once per claim, before supplying these discounts.
 */
data class AuthorizedLeaderDiscount(
    val leaderCardId: String,
    val advance: AdvanceId,
    val amount: Int,
) {
    init {
        require(leaderCardId.matches(Regex("[a-z][a-z0-9-]*"))) {
            "A leader discount requires a catalog card identifier."
        }
        require(amount > 0) { "A leader discount must have a positive base amount." }
    }
}

/**
 * Ephemeral facts from the authoritative running turn. The game owns turn validity and history
 * consistency; ownership and acquisition history belong to the player.
 */
data class PurchaseContext(
    val currentTurn: Int,
    val papalDecree: AdvanceCategory? = null,
    val leaderDiscounts: List<AuthorizedLeaderDiscount> = emptyList(),
    val rules: RuleOptions = RuleOptions(),
) {
    init {
        require(
            leaderDiscounts
                .map { discount -> discount.leaderCardId to discount.advance }
                .distinct()
                .size == leaderDiscounts.size
        ) {
            "A leader may supply only one discount per advance to a buyer."
        }
    }
}

data class AppliedLeaderDiscount(
    val leaderCardId: String,
    val baseAmount: Int,
    val writtenRecordBonus: Int,
) {
    val amount: Long
        get() = baseAmount.toLong() + writtenRecordBonus
}

/**
 * Dollar components of the current price; excess credit is lost and never returned as cash.
 * [miseryBefore] and [miseryAfter] are track positions from the same purchase calculation,
 * including clamping at zero and terminal Chaos. Purchasing consumes this quoted destination.
 */
data class AdvanceQuote(
    val advance: AdvanceId,
    val baseCost: Int,
    val advanceCredits: List<AdvanceCredit>,
    val institutionalResearchCredit: Int,
    val leaders: List<AppliedLeaderDiscount>,
    val miseryBefore: MiseryStep,
    val miseryAfter: MiseryStep,
) {
    val categoryCredit: Int
        get() = advanceCredits.sumOf(AdvanceCredit::amount)

    /** Actual reduction in dollars after the zero-price floor; excess discounts are excluded. */
    val effectiveDiscount: Int
        get() = baseCost - price

    val price: Int
        get() =
            (baseCost.toLong() -
                    categoryCredit -
                    institutionalResearchCredit -
                    leaders.sumOf(AppliedLeaderDiscount::amount))
                .coerceAtLeast(0)
                .toInt()
}

object AdvancePurchases {
    /** Quote a currently eligible advance, even when its price exceeds the buyer's cash. */
    fun quote(player: PlayerState, advance: AdvanceId, context: PurchaseContext): AdvanceQuote {
        requireEligible(player, advance, context)
        return estimate(player, advance, context)
    }

    /** Prerequisites and Papal Decree authorize purchases independently of informational prices. */
    internal fun requireEligible(
        player: PlayerState,
        advance: AdvanceId,
        context: PurchaseContext,
    ) {
        player.requireActive()
        val definition = AdvanceData.definition(advance)
        val entity = listOf(advance.letter.toString())
        checkRule(!player.advances.owns(advance), ViolationCode.ADVANCE_ALREADY_OWNED, entity)
        val missing = definition.prerequisites.filterNot(player.advances::owns)
        checkRule(
            missing.isEmpty(),
            ViolationCode.MISSING_ADVANCE_PREREQUISITE,
            missing.map { prerequisite -> prerequisite.letter.toString() },
        )
        checkRule(
            definition.category != context.papalDecree,
            ViolationCode.ADVANCE_CATEGORY_FORBIDDEN,
            entity,
        )
    }

    /**
     * Price an unowned advance for an active player, even when prerequisites or Papal Decree
     * prevent purchase. [requireEligible] owns eligibility; projections may show a blocked
     * estimate.
     */
    internal fun estimate(
        player: PlayerState,
        advance: AdvanceId,
        context: PurchaseContext,
    ): AdvanceQuote {
        val definition = AdvanceData.definition(advance)
        val research =
            if (
                player.advances.owns(AdvanceId.INSTITUTIONAL_RESEARCH) &&
                    definition.category !in setOf(AdvanceCategory.CIVICS, AdvanceCategory.RELIGION)
            ) {
                10
            } else {
                0
            }
        val writtenRecord = LeaderRules.writtenRecordBonus(player.advances.owned, context.rules)
        val acquired = player.advances.acquire(advance, context.currentTurn)
        val completionRelief =
            if (context.rules.miseryRelief == MiseryReliefRule.CATEGORY_COMPLETION) {
                acquired.completionRelief - player.advances.completionRelief
            } else {
                0
            }
        return AdvanceQuote(
            advance = advance,
            baseCost = definition.cost,
            advanceCredits = player.advances.creditsFor(definition.category, context.currentTurn),
            institutionalResearchCredit = research,
            leaders =
                context.leaderDiscounts
                    .filter { discount -> discount.advance == advance }
                    .map { discount ->
                        AppliedLeaderDiscount(discount.leaderCardId, discount.amount, writtenRecord)
                    },
            miseryBefore = player.misery,
            miseryAfter =
                MiseryRules.shift(player.misery, definition.purchaseMisery - completionRelief),
        )
    }

    fun purchase(player: PlayerState, advance: AdvanceId, context: PurchaseContext): EconomyResult {
        val quote = quote(player, advance, context)
        checkRule(
            player.cash >= quote.price,
            ViolationCode.INSUFFICIENT_CASH,
            listOf(advance.letter.toString()),
        )
        val ships =
            when (advance) {
                AdvanceId.SEAWORTHY_VESSELS -> ShippingCapability.SEAWORTHY_10
                AdvanceId.OCEAN_NAVIGATION -> ShippingCapability.OCEAN_1
                else -> player.ships
            }
        val acquired = player.advances.acquire(advance, context.currentTurn)
        return EconomyResult.forPlayer(
            player.copy(
                cash = player.cash - quote.price,
                advances = acquired,
                ships = ships,
                misery = quote.miseryAfter,
            )
        )
    }
}

object ShippingPurchases {
    /**
     * The next paid level in this fleet; crossing from galleys to seaworthy requires its advance.
     */
    fun nextUpgrade(current: ShippingCapability): ShippingCapability? =
        when (current) {
            ShippingCapability.NONE -> ShippingCapability.GALLEY_2
            ShippingCapability.GALLEY_2 -> ShippingCapability.GALLEY_4
            ShippingCapability.GALLEY_4 -> ShippingCapability.GALLEY_6
            ShippingCapability.GALLEY_6 -> ShippingCapability.GALLEY_8
            ShippingCapability.SEAWORTHY_10 -> ShippingCapability.SEAWORTHY_12
            ShippingCapability.SEAWORTHY_12 -> ShippingCapability.SEAWORTHY_14
            ShippingCapability.SEAWORTHY_14 -> ShippingCapability.SEAWORTHY_16
            ShippingCapability.OCEAN_1 -> ShippingCapability.OCEAN_2
            ShippingCapability.OCEAN_2 -> ShippingCapability.OCEAN_3
            ShippingCapability.OCEAN_3 -> ShippingCapability.OCEAN_4
            ShippingCapability.GALLEY_8,
            ShippingCapability.SEAWORTHY_16,
            ShippingCapability.OCEAN_4 -> null
        }

    /** Apply at most one paid upgrade in the authoritative game's [currentTurn]. */
    fun upgrade(player: PlayerState, currentTurn: Int): EconomyResult {
        player.requireActive()
        checkRule(
            player.lastShipUpgradeTurn != currentTurn,
            ViolationCode.SHIP_UPGRADE_ALREADY_BOUGHT,
        )
        val next =
            nextUpgrade(player.ships) ?: throw RuleViolation(ViolationCode.SHIP_UPGRADE_UNAVAILABLE)
        checkRule(player.cash >= EconomyTables.shipUpgradeCost, ViolationCode.INSUFFICIENT_CASH)
        return EconomyResult.forPlayer(
            player.copy(
                cash = player.cash - EconomyTables.shipUpgradeCost,
                ships = next,
                lastShipUpgradeTurn = currentTurn,
            )
        )
    }
}
