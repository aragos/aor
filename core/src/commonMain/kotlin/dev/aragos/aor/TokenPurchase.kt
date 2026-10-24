package dev.aragos.aor

import kotlin.math.abs
import kotlin.math.min

/** A signed token purchase: its absolute amount is paid, while only positive amounts buy tokens. */
class TokenPurchase private constructor(val amount: Int) {
    init {
        require(amount != Int.MIN_VALUE) { "A token purchase must have a representable cost." }
    }

    val cost: Int
        get() = abs(amount)

    /** Number received from [stockTokens]; an over-stock purchase still pays its full cost. */
    fun tokensReceived(stockTokens: Int): Int {
        require(stockTokens >= 0) { "Stock tokens cannot be negative." }
        return min(amount.coerceAtLeast(0), stockTokens)
    }

    /** Charge and allocate this already validated purchase at simultaneous reveal. */
    fun applyTo(player: PlayerState): PlayerState {
        checkRule(cost <= player.cash, ViolationCode.INVALID_TOKEN_PURCHASE)
        val received = tokensReceived(player.stockTokens)
        return player.copy(
            cash = player.cash - cost,
            stockTokens = player.stockTokens - received,
            poolTokens = player.poolTokens + received,
        )
    }

    companion object {
        /** Validate [amount] against available [cash] before computing its absolute cost. */
        fun affordable(amount: Int, cash: Int): TokenPurchase {
            require(cash >= 0) { "Available cash cannot be negative." }
            checkRule(amount in -cash..cash, ViolationCode.INVALID_TOKEN_PURCHASE)
            return TokenPurchase(amount)
        }
    }
}
