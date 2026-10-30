package com.stratbot.app.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * org.json refuses non-finite numbers: JSONArray/JSONObject.put(double) throw
 * JSONException("Forbidden numeric value") for NaN / ±Infinity. Because report
 * serialization is best-effort, one such value (e.g. profitFactor = +Infinity when a
 * backtest had winners but no losers) aborted the entire write and left the report on
 * disk in its previous RUNNING state — i.e. a run that could never finish.
 * Write every double through here instead; readers fall back to NaN via optDouble().
 */
internal fun JSONObject.num(name: String, value: Double): JSONObject =
    if (value.isFinite()) put(name, value) else this

enum class RunMode { BACKTEST, DRY, LIVE }
enum class RunStatus { RUNNING, DONE, STOPPED, ERROR }

/** Timeframe helpers shared by both exchanges (values are canonical). */
object Tf {
    val ALL = listOf("1m", "3m", "5m", "15m", "30m", "1h", "2h", "4h", "6h", "12h", "1d", "3d", "1w")
    val BITFINEX = listOf("1m", "5m", "15m", "30m", "1h", "4h", "1d", "1w")

    /**
     * Always a *fresh* list: spinner adapters hold a live reference to the list they were
     * built with and refresh themselves with clear()/addAll(), which would otherwise mutate
     * (and crash on) the shared ALL / BITFINEX singletons.
     */
    fun supportedFor(exchange: String): List<String> =
        if (exchange == "bitfinex") ArrayList(BITFINEX) else ArrayList(ALL)

    fun toMs(tf: String): Long {
        val n = tf.dropLast(1).toLongOrNull() ?: 1L
        return when (tf.lastOrNull()) {
            'm' -> n * 60_000L
            'h' -> n * 3_600_000L
            'd' -> n * 86_400_000L
            'w' -> n * 604_800_000L
            else -> 60_000L
        }
    }
}

data class Candle(
    val t: Long,      // open time, ms
    val o: Double,
    val h: Double,
    val l: Double,
    val c: Double,
    val v: Double,
) {
    fun toList(): DoubleArray = doubleArrayOf(o, h, l, c, v)

    fun toJson(): JSONObject = JSONObject()
        .put("t", t).put("o", o).put("h", h).put("l", l).put("c", c).put("v", v)

    companion object {
        @JvmStatic
        fun fromJson(j: JSONObject) =
            Candle(j.optLong("t"), j.optDouble("o"), j.optDouble("h"), j.optDouble("l"), j.optDouble("c"), j.optDouble("v", 0.0))
    }
}

data class Strategy(
    val id: String = UUID.randomUUID().toString().substring(0, 8),
    val name: String = "New strategy",
    val code: String = "",
    val symbol: String = "BTCUSDT",
    val timeframe: String = "5m",
    val exchange: String = "binance",
    val paramsJson: String = "{}",
    val allowShort: Boolean = false,
    val feeBps: Double = 10.0,      // 0.10 %
    val slippageBps: Double = 5.0,  // 0.05 %
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("code", code).put("symbol", symbol)
        .put("timeframe", timeframe).put("exchange", exchange).put("params", paramsJson)
        .put("allowShort", allowShort).put("feeBps", feeBps).put("slippageBps", slippageBps)
        .put("createdAt", System.currentTimeMillis())

    companion object {
        fun fromJson(j: JSONObject) = Strategy(
            id = j.getString("id"),
            name = j.optString("name", "Strategy"),
            code = j.optString("code"),
            symbol = j.optString("symbol", "BTCUSDT"),
            timeframe = j.optString("timeframe", "5m"),
            exchange = j.optString("exchange", "binance"),
            paramsJson = j.optString("params", "{}"),
            allowShort = j.optBoolean("allowShort", false),
            feeBps = j.optDouble("feeBps", 10.0),
            slippageBps = j.optDouble("slippageBps", 5.0),
        )
    }
}

data class RunSpec(
    val runId: String = UUID.randomUUID().toString(),
    val strategyId: String,
    val strategyName: String,
    val mode: RunMode,
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val code: String,
    val paramsJson: String,
    val allowShort: Boolean,
    val feeBps: Double,
    val slippageBps: Double,
    val startCash: Double,
    /** 0 = auto (last [maxBars]) */
    val startMs: Long = 0L,
    /** 0 = now */
    val endMs: Long = 0L,
    val maxBars: Int = 3000,
    val createdAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("runId", runId).put("strategyId", strategyId).put("strategyName", strategyName)
        .put("mode", mode.name).put("exchange", exchange).put("symbol", symbol)
        .put("timeframe", timeframe).put("code", code).put("params", paramsJson)
        .put("allowShort", allowShort).put("feeBps", feeBps).put("slippageBps", slippageBps)
        .put("startCash", startCash).put("startMs", startMs).put("endMs", endMs)
        .put("maxBars", maxBars).put("createdAt", createdAt)

    companion object {
        fun fromJson(j: JSONObject) = RunSpec(
            runId = j.getString("runId"),
            strategyId = j.getString("strategyId"),
            strategyName = j.optString("strategyName", "strategy"),
            mode = RunMode.valueOf(j.getString("mode")),
            exchange = j.getString("exchange"),
            symbol = j.getString("symbol"),
            timeframe = j.getString("timeframe"),
            code = j.optString("code"),
            paramsJson = j.optString("params", "{}"),
            allowShort = j.optBoolean("allowShort", false),
            feeBps = j.optDouble("feeBps", 10.0),
            slippageBps = j.optDouble("slippageBps", 5.0),
            startCash = j.optDouble("startCash", 10000.0),
            startMs = j.optLong("startMs", 0L),
            endMs = j.optLong("endMs", 0L),
            maxBars = j.optInt("maxBars", 3000),
            createdAt = j.optLong("createdAt", System.currentTimeMillis()),
        )
    }
}

/** A completed round trip (position reduced to flat or reduced). */
data class Trade(
    val entryTs: Long,
    val exitTs: Long,
    val side: Int,          // +1 long, -1 short
    val qty: Double,
    val entryPrice: Double,
    val exitPrice: Double,
    val pnl: Double,        // net of fees of the closing leg
    val fee: Double,
    val reason: String = "",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("entryTs", entryTs).put("exitTs", exitTs).put("side", side).num("qty", qty)
        .num("entryPrice", entryPrice).num("exitPrice", exitPrice).num("pnl", pnl)
        .num("fee", fee).put("reason", reason)

    companion object {
        fun fromJson(j: JSONObject) = Trade(
            entryTs = j.optLong("entryTs"), exitTs = j.optLong("exitTs"),
            side = j.optInt("side", 1), qty = j.optDouble("qty"),
            entryPrice = j.optDouble("entryPrice"), exitPrice = j.optDouble("exitPrice"),
            pnl = j.optDouble("pnl"), fee = j.optDouble("fee"), reason = j.optString("reason"),
        )
    }
}

data class LogLine(val ts: Long, val lvl: String, val msg: String) {
    fun toJson(): JSONObject = JSONObject().put("ts", ts).put("lvl", lvl).put("msg", msg)
    companion object {
        fun fromJson(j: JSONObject) = LogLine(j.optLong("ts"), j.optString("lvl", "I"), j.optString("msg"))
    }
}

data class Metrics(
    val startCash: Double = 0.0,
    val finalEquity: Double = 0.0,
    val pnl: Double = 0.0,
    val roiPct: Double = 0.0,
    val tradeCount: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRatePct: Double = 0.0,
    val profitFactor: Double = 0.0,
    val avgTradePnl: Double = 0.0,
    val bestTradePnl: Double = 0.0,
    val worstTradePnl: Double = 0.0,
    val maxDrawdownPct: Double = 0.0,
    val sharpe: Double = 0.0,
    val maxWinStreak: Int = 0,
    val maxLossStreak: Int = 0,
    val fees: Double = 0.0,
    val buyHoldPct: Double = 0.0,
    val exposurePct: Double = 0.0,
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.num("startCash", startCash).num("finalEquity", finalEquity).num("pnl", pnl).num("roiPct", roiPct)
            .put("tradeCount", tradeCount).put("wins", wins).put("losses", losses).num("winRatePct", winRatePct)
            .num("profitFactor", profitFactor).num("avgTradePnl", avgTradePnl).num("bestTradePnl", bestTradePnl)
            .num("worstTradePnl", worstTradePnl).num("maxDrawdownPct", maxDrawdownPct).num("sharpe", sharpe)
            .put("maxWinStreak", maxWinStreak).put("maxLossStreak", maxLossStreak).num("fees", fees)
            .num("buyHoldPct", buyHoldPct).num("exposurePct", exposurePct)
        return o
    }

    companion object {
        fun fromJson(j: JSONObject) = Metrics(
            startCash = j.optDouble("startCash"), finalEquity = j.optDouble("finalEquity"),
            pnl = j.optDouble("pnl"), roiPct = j.optDouble("roiPct"),
            tradeCount = j.optInt("tradeCount"), wins = j.optInt("wins"), losses = j.optInt("losses"),
            winRatePct = j.optDouble("winRatePct"), profitFactor = j.optDouble("profitFactor"),
            avgTradePnl = j.optDouble("avgTradePnl"), bestTradePnl = j.optDouble("bestTradePnl"),
            worstTradePnl = j.optDouble("worstTradePnl"), maxDrawdownPct = j.optDouble("maxDrawdownPct"),
            sharpe = j.optDouble("sharpe"), maxWinStreak = j.optInt("maxWinStreak"),
            maxLossStreak = j.optInt("maxLossStreak"), fees = j.optDouble("fees"),
            buyHoldPct = j.optDouble("buyHoldPct"), exposurePct = j.optDouble("exposurePct"),
        )
    }
}

/** Passed to the optional JS `onTick(ctx, tick)` callback. */
data class TickInfo(
    val ts: Long,
    val bid: Double,
    val ask: Double,
    val last: Double,
)

const val MAX_CURVE_POINTS = 600
const val MAX_TRADES_IN_REPORT = 300
const val MAX_LOG_LINES = 400

data class Report(
    val runId: String,
    val strategyId: String,
    val strategyName: String,
    val mode: RunMode,
    val exchange: String,
    val symbol: String,
    val timeframe: String,
    val status: RunStatus,
    val startedAt: Long,
    val endedAt: Long = 0L,
    val error: String? = null,
    val bars: Long = 0,
    val lastPrice: Double = 0.0,
    val bid: Double = 0.0,
    val ask: Double = 0.0,
    val cash: Double = 0.0,
    val position: Double = 0.0,
    val entryPrice: Double = 0.0,
    val equity: Double = 0.0,
    val fees: Double = 0.0,
    val openOrders: Int = 0,
    val metrics: Metrics? = null,
    /** [ts(ms), equity] pairs */
    val curve: List<DoubleArray> = emptyList(),
    val trades: List<Trade> = emptyList(),
    val logs: List<LogLine> = emptyList(),
) {
    fun summaryLine(): String {
        val m = metrics
        if (m == null) {
            // while a run is in flight the row must show movement (bars processed),
            // otherwise a backtest looks identical to an idle dry run
            return if (status == RunStatus.RUNNING) String.format("%s  %d bars  eq %.2f", status.name, bars, equity)
            else String.format("%s  eq %.2f", status.name, equity)
        }
        return String.format("%+.2f (%+.2f%%)", m.pnl, m.roiPct)
    }

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("runId", runId).put("strategyId", strategyId).put("strategyName", strategyName)
        o.put("mode", mode.name).put("exchange", exchange).put("symbol", symbol).put("timeframe", timeframe)
        o.put("status", status.name).put("startedAt", startedAt).put("endedAt", endedAt)
        if (error != null) o.put("error", error)
        o.put("bars", bars).num("lastPrice", lastPrice).num("bid", bid).num("ask", ask)
        o.num("cash", cash).num("position", position).num("entryPrice", entryPrice)
        o.num("equity", equity).num("fees", fees).put("openOrders", openOrders)
        if (metrics != null) o.put("metrics", metrics.toJson())
        val cv = JSONArray()
        // a single non-finite point used to abort the whole report write
        curve.forEach { p ->
            if (p[0].isFinite() && p[1].isFinite()) cv.put(JSONArray().put(p[0]).put(p[1]))
        }
        o.put("curve", cv)
        val tr = JSONArray(); trades.forEach { tr.put(it.toJson()) }; o.put("trades", tr)
        val lg = JSONArray(); logs.forEach { lg.put(it.toJson()) }; o.put("logs", lg)
        return o
    }

    companion object {
        fun fromJson(j: JSONObject): Report {
            val curve = ArrayList<DoubleArray>()
            j.optJSONArray("curve")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONArray(i) ?: continue
                    if (p.length() >= 2) curve.add(doubleArrayOf(p.optLong(0).toDouble(), p.optDouble(1)))
                }
            }
            val trades = ArrayList<Trade>()
            j.optJSONArray("trades")?.let { a -> for (i in 0 until a.length()) trades.add(Trade.fromJson(a.optJSONObject(i))) }
            val logs = ArrayList<LogLine>()
            j.optJSONArray("logs")?.let { a -> for (i in 0 until a.length()) logs.add(LogLine.fromJson(a.optJSONObject(i))) }
            return Report(
                runId = j.getString("runId"),
                strategyId = j.optString("strategyId"),
                strategyName = j.optString("strategyName"),
                mode = RunMode.valueOf(j.optString("mode", "DRY")),
                exchange = j.optString("exchange", "binance"),
                symbol = j.optString("symbol"), timeframe = j.optString("timeframe"),
                status = RunStatus.valueOf(j.optString("status", "RUNNING")),
                startedAt = j.optLong("startedAt"), endedAt = j.optLong("endedAt"),
                error = if (j.isNull("error")) null else j.optString("error"),
                bars = j.optLong("bars"), lastPrice = j.optDouble("lastPrice"),
                bid = j.optDouble("bid"), ask = j.optDouble("ask"),
                cash = j.optDouble("cash"), position = j.optDouble("position"),
                entryPrice = j.optDouble("entryPrice"), equity = j.optDouble("equity"),
                fees = j.optDouble("fees"), openOrders = j.optInt("openOrders"),
                metrics = j.optJSONObject("metrics")?.let { Metrics.fromJson(it) },
                curve = curve,
                trades = trades, logs = logs,
            )
        }
    }
}