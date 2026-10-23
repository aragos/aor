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
 * Ephemeral turn facts, excluding ownership and acquisition history, which belong to the player.
 */
data class PurchaseContext(
    val currentTurn: Int,
    val papalDecree: AdvanceCategory? = null,
    val leaderDiscounts: List<AuthorizedLeaderDiscount> = emptyList(),
) {
    init {
        require(currentTurn > 0) { "A purchase requires a positive current turn." }
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

/** Dollar components of the current price; excess credit is lost and never returned as cash. */
data class AdvanceQuote(
    val advance: AdvanceId,
    val baseCost: Int,
    val categoryCredit: Int,
    val institutionalResearchCredit: Int,
    val leaders: List<AppliedLeaderDiscount>,
) {
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
    /**
     * Quotes legal prerequisites/categories even when the buyer cannot afford the resulting price.
     */
    fun quote(player: PlayerState, advance: AdvanceId, context: PurchaseContext): AdvanceQuote {
        player.requireActive()
        player.advances.requireValidTurn(context.currentTurn)
        val definition = definition(advance)
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
        val research =
            if (
                player.advances.owns(AdvanceId.INSTITUTIONAL_RESEARCH) &&
                    definition.category !in setOf(AdvanceCategory.CIVICS, AdvanceCategory.RELIGION)
            )
                10
            else 0
        val writtenRecord = if (player.advances.owns(AdvanceId.WRITTEN_RECORD)) 10 else 0
        return AdvanceQuote(
            advance = advance,
            baseCost = definition.cost,
            categoryCredit = player.advances.creditsFor(definition.category, context.currentTurn),
            institutionalResearchCredit = research,
            leaders =
                context.leaderDiscounts
                    .filter { discount -> discount.advance == advance }
                    .map { discount ->
                        AppliedLeaderDiscount(discount.leaderCardId, discount.amount, writtenRecord)
                    },
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
        return EconomyResult.forPlayer(
            player.copy(
                cash = player.cash - quote.price,
                advances = player.advances.acquire(advance, context.currentTurn),
                ships = ships,
                misery = MiseryRules.shift(player.misery, definition(advance).purchaseMisery),
            )
        )
    }

    /**
     * Immediate rebate when this player plays their own leader. Pass that leader's conditional base
     * discounts only. Patronage and Written Record never increase a Printed Word rebate.
     */
    fun printedWordRebate(player: PlayerState, discounts: List<AuthorizedLeaderDiscount>): Long {
        player.requireActive()
        require(discounts.map(AuthorizedLeaderDiscount::leaderCardId).distinct().size <= 1) {
            "A Printed Word rebate must describe one played leader."
        }
        require(
            discounts.map(AuthorizedLeaderDiscount::advance).distinct().size == discounts.size
        ) {
            "A played leader cannot repeat an advance discount."
        }
        if (!player.advances.owns(AdvanceId.PRINTED_WORD)) return 0
        return discounts
            .filter { discount -> player.advances.owns(discount.advance) }
            .sumOf { discount -> discount.amount.toLong() }
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

    fun upgrade(player: PlayerState, currentTurn: Int): EconomyResult {
        player.requireActive()
        require(currentTurn > 0) { "A ship upgrade requires a positive current turn." }
        require(player.lastShipUpgradeTurn == null || player.lastShipUpgradeTurn <= currentTurn) {
            "A player's last ship upgrade cannot be in a future turn."
        }
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
