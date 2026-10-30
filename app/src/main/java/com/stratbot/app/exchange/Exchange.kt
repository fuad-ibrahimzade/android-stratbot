package com.stratbot.app.exchange

import com.stratbot.app.data.Services
import com.stratbot.app.model.Candle

data class Quote(val bid: Double, val ask: Double, val last: Double)

data class OrderResult(
    val ok: Boolean,
    val orderId: String = "",
    val avgPrice: Double = 0.0,
    val filledQty: Double = 0.0,
    val status: String = "",
    val raw: String = "",
)

/** Live market stream; implementations may silently fall back to REST polling. */
interface Stream : AutoCloseable {
    fun start()
    override fun close()
}

interface StreamHandlers {
    fun onTick(bid: Double, ask: Double, last: Double)
    fun onBarClosed(c: Candle)
    fun onStatus(msg: String)
    fun onError(msg: String)
}

interface Exchange {
    val id: String
    val displayName: String

    /** canonical symbol as the user typed it (exchanges normalize internally) */
    fun normalizeSymbol(sym: String): String
    fun canonicalSymbol(sym: String): String

    /** blocking, ascending candles. startMs=0 → last `maxBars` ending now. */
    fun candles(symbol: String, tf: String, startMs: Long, endMs: Long, maxBars: Int): List<Candle>

    fun quote(symbol: String): Quote

    fun placeOrder(symbol: String, side: String, type: String, qty: Double, price: Double): OrderResult

    /** cumulative fill info for an order (filledQty is cumulative). */
    fun orderStatus(symbol: String, orderId: String): OrderResult

    fun cancelOrder(symbol: String, orderId: String): Boolean

    fun openStream(symbol: String, tf: String, handlers: StreamHandlers): Stream
}

/** Credential bridge (wired once from Application; keeps exchange classes context-free). */
object ServicesHolder {
    @Volatile var keyProvider: (String) -> String = { "" }
    @Volatile var secretProvider: (String) -> String = { "" }
    fun key(exchange: String): String = runCatching { keyProvider(exchange) }.getOrDefault("")
    fun secret(exchange: String): String = runCatching { secretProvider(exchange) }.getOrDefault("")
}

object Exchanges {
    private val map = linkedMapOf<String, () -> Exchange>(
        "binance" to { Binance(Services.settings) },
        "bitfinex" to { Bitfinex(Services.settings) },
    )

    val ids: List<String> get() = map.keys.toList()

    fun get(id: String): Exchange {
        val f = map[id] ?: map["binance"]!!
        return f()
    }
}