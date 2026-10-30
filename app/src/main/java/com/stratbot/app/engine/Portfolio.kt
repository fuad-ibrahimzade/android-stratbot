package com.stratbot.app.engine

import com.stratbot.app.model.Trade
import kotlin.math.abs

data class FillResult(
    val ok: Boolean,
    val qty: Double = 0.0,
    val price: Double = 0.0,
    val realized: Double = 0.0,
    val msg: String? = null,
)

data class PortfolioSnapshot(
    val cash: Double,
    val position: Double,
    val entryPrice: Double,
    val fees: Double,
    val realized: Double,
    val tradeCount: Int,
)

/**
 * Spot-style accounting.  position > 0 = long, position < 0 = short (only when allowShort).
 * cash is the quote balance; equity = cash + position * price
 */
class Portfolio(
    val startCash: Double,
    val feeBps: Double,
    val allowShort: Boolean,
) {
    private companion object {
        const val EPS = 1e-12
    }

    var cash = startCash
        private set
    var position = 0.0
        private set
    var avgEntry = 0.0
        private set
    var entryTs = 0L
        private set
    var feesPaid = 0.0
        private set
    var realizedPnl = 0.0
        private set
    var timeInMarket = false
        private set

    val trades = ArrayList<Trade>()

    fun equity(price: Double): Double = cash + position * price

    fun unrealized(price: Double): Double =
        if (abs(position) < EPS) 0.0 else (price - avgEntry) * position

    @Synchronized
    fun snapshot() = PortfolioSnapshot(cash, position, avgEntry, feesPaid, realizedPnl, trades.size)

    @Synchronized
    fun tradesList(): List<Trade> = trades.toList()

    /** Returns an error string when the order cannot be executed, otherwise null. */
    @Synchronized
    fun check(side: Int, qty: Double, price: Double): String? {
        if (qty <= 0 || qty.isNaN()) return "quantity must be > 0"
        if (price <= 0 || price.isNaN()) return "invalid price"
        if (side < 0 && position <= EPS) {
            // selling down an existing long is fine, opening a new short is not
            if (!allowShort) return "shorting is disabled for this run"
            return null
        }
        if (side > 0 && position < -EPS) return null // covering a short always allowed
        if (side > 0 && position >= -EPS) {
            val need = qty * price * (1 + feeBps / 10_000.0)
            if (need > cash + 1e-9)
                return "insufficient cash (need " + "%.2f".format(need) + ", have " + "%.2f".format(cash) + ")"
        }
        return null
    }

    /**
     * Executes an order atomically: reduces an opposing position first, then opens a new one.
     */
    @Synchronized
    fun apply(side: Int, qty: Double, price: Double, ts: Long, reason: String): FillResult {
        check(side, qty, price)?.let { return FillResult(false, msg = it) }

        val fee = qty * price * feeBps / 10_000.0
        var remaining = qty
        var realized = 0.0

        // 1. reduce an opposing position
        val cur = position
        val reduceQty = when {
            side > 0 && cur < -EPS -> minOf(remaining, -cur)
            side < 0 && cur > EPS -> minOf(remaining, cur)
            else -> 0.0
        }
        if (reduceQty > EPS) {
            val sign = if (cur > 0) 1.0 else -1.0
            val closeFee = fee * reduceQty / qty
            val pnl = (price - avgEntry) * reduceQty * sign - closeFee
            realized = pnl
            realizedPnl += pnl
            trades.add(
                Trade(
                    entryTs = entryTs, exitTs = ts, side = sign.toInt(), qty = reduceQty,
                    entryPrice = avgEntry, exitPrice = price, pnl = pnl, fee = closeFee, reason = reason,
                )
            )
            position += side * reduceQty
            remaining -= reduceQty
            if (abs(position) < EPS) {
                position = 0.0
                avgEntry = 0.0
                timeInMarket = false
            }
        }

        // 2. open with what is left
        if (remaining > EPS) {
            if (abs(position) < EPS) {
                avgEntry = price
                entryTs = ts
            }
            position += side * remaining
            timeInMarket = true
        }

        cash += -side * qty * price - fee
        feesPaid += fee
        return FillResult(true, qty, price, realized)
    }

    fun summary(price: Double): String =
        String.format("cash=%.2f pos=%.6f entry=%.4f eq=%.2f", cash, position, avgEntry, equity(price))
}