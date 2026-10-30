package dev.aragos.aor

enum class StabilizationPayment {
    CASH,
    MISERY,
}

/** One relief space after Enlightenment, then payment with this turn's MR rating and cash. */
data class MiseryReliefStep(
    val from: MiseryStep,
    val to: MiseryStep,
    val priceBeforeRating: Int,
    val ratingSpent: Int,
) {
    val cashCost: Int
        get() = priceBeforeRating - ratingSpent
}

/** Multiple spaces preserve the per-space rounding that applies when purchasing them separately. */
data class MiseryReliefQuote(val steps: List<MiseryReliefStep>) {
    val cashCost: Int
        get() = steps.sumOf(MiseryReliefStep::cashCost)

    val ratingSpent: Int
        get() = steps.sumOf(MiseryReliefStep::ratingSpent)

    val target: MiseryStep
        get() = steps.last().to
}

/** Income and profit are separate receipts; [interestProfit] excludes this turn's income itself. */
data class IncomeQuote(
    val baseIncome: Int,
    val middleClassBonus: Int,
    val interestProfit: Int,
    val newWorldRelief: Int,
) {
    val income: Int
        get() = baseIncome + middleClassBonus

    val totalCashGain: Int
        get() = income + interestProfit
}

/**
 * Pure player-local economy. The caller enforces phase/substep order, supplies current board
 * counts, and handles the global consequences reported by [EconomyResult]. No board count or credit
 * snapshot is duplicated here. Turn validity and acquisition/usage history consistency belong to
 * the authoritative game.
 */
object PlayerEconomy {
    /** Cost of every card still held, including burdens, before choosing cash or misery payment. */
    fun stabilizationCost(player: PlayerState): Long =
        stabilizationCost(
            handSize = player.hand.size,
            middleClass = player.advances.owns(AdvanceId.MIDDLE_CLASS),
        )

    /** Hands have no limit; use [Long] for the triangular calculation and round halves upward. */
    fun stabilizationCost(handSize: Int, middleClass: Boolean): Long {
        val fullCost = EconomyTables.stabilizationCost(handSize)
        return if (middleClass) (fullCost + 1) / 2 else fullCost
    }

    fun stabilize(player: PlayerState, payment: StabilizationPayment): EconomyResult {
        player.requireActive()
        val cost = stabilizationCost(player)
        val stabilized =
            when (payment) {
                StabilizationPayment.CASH -> {
                    checkRule(player.cash.toLong() >= cost, ViolationCode.INSUFFICIENT_CASH)
                    player.copy(cash = player.cash - cost.toInt())
                }
                StabilizationPayment.MISERY ->
                    player.copy(misery = MiseryRules.payDebt(player.misery, cost))
            }
        return EconomyResult.forPlayer(stabilized)
    }

    /**
     * Quote [spaces] against the player's live tiers and the unused MR of acquisitions this turn.
     * Enlightenment halves each box difference, rounding up, before MR pays the resulting price.
     * The history remains intact; [MiseryReliefUsage] records consumption and resets by turn.
     */
    fun reliefQuote(
        player: PlayerState,
        spaces: Int,
        currentTurn: Int,
    ): MiseryReliefQuote {
        player.requireActive()
        val usage = currentReliefUsage(player, currentTurn)
        val remainingSpaces = minOf(player.advances.tierCount - usage.spaces, player.misery.ordinal)
        checkRule(spaces in 1..remainingSpaces, ViolationCode.INVALID_MISERY_RELIEF)
        val availableRating = player.advances.miseryReliefFor(currentTurn) - usage.ratingSpent
        require(availableRating >= 0) {
            "Spent MR rating cannot exceed this turn's acquired rating."
        }
        val enlightened = player.advances.owns(AdvanceId.ENLIGHTENMENT)
        val initial = ReliefCalculation(remainingRating = availableRating)
        val calculation =
            (0 until spaces).fold(initial) { partial, offset ->
                val from = MiseryStep.entries[player.misery.ordinal - offset]
                val to = MiseryStep.entries[from.ordinal - 1]
                val difference =
                    requireNotNull(from.value) { "Relief starts at a numeric misery box." } -
                        requireNotNull(to.value) { "Relief ends at a numeric misery box." }
                val price = if (enlightened) (difference + 1) / 2 else difference
                val ratingSpent = minOf(partial.remainingRating, price)
                ReliefCalculation(
                    remainingRating = partial.remainingRating - ratingSpent,
                    steps = partial.steps + MiseryReliefStep(from, to, price, ratingSpent),
                )
            }
        return MiseryReliefQuote(calculation.steps)
    }

    fun buyRelief(
        player: PlayerState,
        spaces: Int,
        currentTurn: Int,
    ): EconomyResult {
        val quote = reliefQuote(player, spaces, currentTurn)
        checkRule(
            player.cash >= quote.cashCost,
            ViolationCode.INSUFFICIENT_CASH,
            parameters = mapOf("required" to quote.cashCost, "available" to player.cash),
        )
        val previous = currentReliefUsage(player, currentTurn)
        return EconomyResult.forPlayer(
            player.copy(
                cash = player.cash - quote.cashCost,
                misery = quote.target,
                miseryReliefUsage =
                    MiseryReliefUsage(
                        currentTurn,
                        spaces = previous.spaces + spaces,
                        ratingSpent = previous.ratingSpent + quote.ratingSpent,
                    ),
            )
        )
    }

    /**
     * [initialPlayerCount] includes players later lost to Chaos. [boardMarkerCount] includes the
     * player's home capital only when still dominated by that player, and excludes all partial
     * token holdings. Profit is capped using the cash held before either receipt is added.
     */
    fun incomeQuote(
        player: PlayerState,
        initialPlayerCount: Int,
        boardMarkerCount: Int,
    ): IncomeQuote {
        player.requireActive()
        require(boardMarkerCount in 0..25) { "Income requires zero through 25 dominated markets." }
        val baseIncome = EconomyTables.incomeFor(initialPlayerCount)[boardMarkerCount]
        val middleClass = if (player.advances.owns(AdvanceId.MIDDLE_CLASS)) 10 else 0
        val income = baseIncome + middleClass
        return IncomeQuote(
            baseIncome = baseIncome,
            middleClassBonus = middleClass,
            interestProfit =
                if (player.advances.owns(AdvanceId.INTEREST_PROFIT)) {
                    minOf(player.cash, income)
                } else {
                    0
                },
            newWorldRelief = if (player.advances.owns(AdvanceId.NEW_WORLD)) 1 else 0,
        )
    }

    fun collectIncome(
        player: PlayerState,
        initialPlayerCount: Int,
        boardMarkerCount: Int,
    ): EconomyResult {
        val quote = incomeQuote(player, initialPlayerCount, boardMarkerCount)
        val cash = player.cash.toLong() + quote.totalCashGain
        require(cash <= Int.MAX_VALUE) { "Income cannot exceed the supported cash amount." }
        return EconomyResult.forPlayer(
            player.copy(
                cash = cash.toInt(),
                misery = MiseryRules.shift(player.misery, -quote.newWorldRelief),
            )
        )
    }

    private fun currentReliefUsage(player: PlayerState, currentTurn: Int): MiseryReliefUsage {
        val previous = player.miseryReliefUsage
        return if (previous?.turnNumber == currentTurn) previous else MiseryReliefUsage(currentTurn)
    }

    private data class ReliefCalculation(
        val remainingRating: Int,
        val steps: List<MiseryReliefStep> = emptyList(),
    )
}
