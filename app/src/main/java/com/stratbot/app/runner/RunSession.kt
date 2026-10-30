package com.stratbot.app.runner

import android.util.Log
import com.stratbot.app.data.Services
import com.stratbot.app.engine.HostApi
import com.stratbot.app.engine.Portfolio
import com.stratbot.app.engine.StrategyRuntime
import com.stratbot.app.model.Candle
import com.stratbot.app.model.LogLine
import com.stratbot.app.model.MAX_CURVE_POINTS
import com.stratbot.app.model.MAX_LOG_LINES
import com.stratbot.app.model.MAX_TRADES_IN_REPORT
import com.stratbot.app.model.Metrics
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunMode
import com.stratbot.app.model.RunSpec
import com.stratbot.app.model.RunStatus
import com.stratbot.app.model.Tf
import com.stratbot.app.model.Trade
import org.json.JSONObject
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Base class for every run (backtest / dry / live).
 * Owns the engine thread, logging, the equity curve, publishing & report building.
 */
abstract class RunSession(val spec: RunSpec) : HostApi {

    val runId: String = spec.runId
    protected val startedAt = System.currentTimeMillis()

    @Volatile
    var status: RunStatus = RunStatus.RUNNING

    @Volatile
    var errorText: String? = null

    @Volatile
    protected var stopRequested = false

    @Volatile
    protected var endedAtMs = 0L

    @Volatile
    protected var barsDone = 0L

    @Volatile
    protected var lastPrice = 0.0

    @Volatile
    protected var curBid = 0.0

    @Volatile
    protected var curAsk = 0.0

    @Volatile
    protected var metricsCache: Metrics? = null

    protected val logs: MutableList<LogLine> = Collections.synchronizedList(ArrayList())
    protected val curve: MutableList<DoubleArray> = Collections.synchronizedList(ArrayList())

    protected val params: Map<String, Any?> = parseParams(spec.paramsJson)
    protected abstract val portfolio: Portfolio
    protected var runtime: StrategyRuntime? = null

    private val listeners = CopyOnWriteArrayList<(Report) -> Unit>()
    @Volatile
    private var lastPublishMs = 0L
    private var engineThread: Thread? = null

    protected fun toast(msg: String) = log(msg)

    // ---------------- lifecycle ----------------

    fun start() {
        if (engineThread != null) return
        engineThread = Thread({
            try {
                log("run started: ${spec.mode} ${spec.exchange} ${spec.symbol} ${spec.timeframe}")
                engineBody()
                if (status == RunStatus.RUNNING) status = if (stopRequested) RunStatus.STOPPED else RunStatus.DONE
            } catch (t: Throwable) {
                Log.e(TAG, "run failed", t)
                errorText = t.message ?: t.javaClass.simpleName
                status = RunStatus.ERROR
                log("ERROR " + errorText)
            } finally {
                endedAtMs = System.currentTimeMillis()
                runCatching { dispose() }
                runCatching { runtime?.destroy() }
                runtime = null
                publish(force = true)
            }
        }, "stratbot-run").apply { isDaemon = true; start() }
    }

    /** request a cooperative stop */
    @Synchronized
    fun stop() {
        if (status != RunStatus.RUNNING) return
        stopRequested = true
        log("stop requested")
        runCatching { onStopRequested() }
        publish(force = true)
    }

    /** actual stop (called on the engine thread before exiting [engineBody]) */
    protected abstract fun engineBody()

    protected abstract fun dispose()

    protected open fun onStopRequested() {}

    fun addListener(l: (Report) -> Unit) = listeners.add(l)

    fun removeListener(l: (Report) -> Unit) = listeners.remove(l)

    // ---------------- publishing ----------------

    fun publish(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublishMs < 1000) return
        lastPublishMs = now
        val rep = snapshot()
        runCatching { Services.runs.write(rep) }
        for (l in listeners) {
            try {
                l(rep)
            } catch (t: Throwable) {
                Log.w(TAG, "listener: ${t.message}")
            }
        }
    }

    fun snapshot(): Report {
        val ps = portfolio.snapshot()
        val ref = currentRefPrice()
        val eq = if (ref > 0) portfolio.equity(ref) else ps.cash
        val trades = portfolio.tradesList().takeLast(MAX_TRADES_IN_REPORT)
        val logsSnap: List<LogLine>
        synchronized(logs) { logsSnap = logs.takeLast(MAX_LOG_LINES) }
        val curveSnap: List<DoubleArray>
        synchronized(curve) { curveSnap = downsample(curve.toList()) }
        return Report(
            runId = runId,
            strategyId = spec.strategyId,
            strategyName = spec.strategyName,
            mode = spec.mode,
            exchange = spec.exchange,
            symbol = spec.symbol,
            timeframe = spec.timeframe,
            status = status,
            startedAt = startedAt,
            endedAt = endedAtMs,
            error = errorText,
            bars = barsDone,
            lastPrice = lastPrice,
            bid = curBid,
            ask = curAsk,
            cash = ps.cash,
            position = ps.position,
            entryPrice = ps.entryPrice,
            equity = eq,
            fees = ps.fees,
            openOrders = runCatching { openOrdersCount() }.getOrDefault(0),
            metrics = metricsCache,
            curve = curveSnap,
            trades = trades,
            logs = logsSnap,
        )
    }

    private fun downsample(list: List<DoubleArray>): List<DoubleArray> {
        if (list.size <= MAX_CURVE_POINTS) return list
        val step = (list.size + MAX_CURVE_POINTS - 1) / MAX_CURVE_POINTS
        val out = ArrayList<DoubleArray>(MAX_CURVE_POINTS + 2)
        var i = 0
        while (i < list.size) {
            out.add(list[i])
            i += step
        }
        if (out.last() !== list.last()) out.add(list.last())
        return out
    }

    // ---------------- helpers ----------------

    protected fun logError(msg: String) {
        logs.add(LogLine(System.currentTimeMillis(), "E", msg))
        Log.e(TAG, "[$runId] $msg")
    }

    protected fun recordPoint(ts: Long, equity: Double) {
        if (equity.isNaN()) return
        synchronized(curve) {
            val last = curve.lastOrNull()
            if (last != null && last[0] == ts.toDouble()) {
                last[1] = equity
            } else {
                curve.add(doubleArrayOf(ts.toDouble(), equity))
            }
        }
    }

    protected fun currentRefPrice(): Double = when {
        lastPrice > 0 -> lastPrice
        curBid > 0 -> curBid
        else -> 0.0
    }

    /** window ending at the bar the strategy is currently looking at */
    protected abstract fun barsWindow(n: Int): Array<Candle>

    protected abstract fun execMarket(side: Int, qty: Double, refPrice: Double): Boolean

    protected abstract fun execLimit(side: Int, price: Double, qty: Double): String?

    protected abstract fun openOrdersCount(): Int

    // ---------------- HostApi ----------------

    final override val exchangeId: String get() = spec.exchange
    final override val symbol: String get() = spec.symbol
    final override val timeframe: String get() = spec.timeframe
    final override val modeName: String = spec.mode.name

    final override fun now(): Long = System.currentTimeMillis()
    final override fun lastPrice(): Double = currentRefPrice()
    final override fun bid(): Double = if (curBid > 0) curBid else currentRefPrice()
    final override fun ask(): Double = if (curAsk > 0) curAsk else currentRefPrice()
    final override fun position(): Double = portfolio.position
    final override fun entryPrice(): Double = portfolio.avgEntry
    final override fun cash(): Double = portfolio.cash
    final override fun equity(): Double = portfolio.equity(currentRefPrice())
    final override fun openOrders(): Int = openOrdersCount()

    final override fun buy(qty: Double): Boolean {
        val ref = currentRefPrice()
        return execSide(+1, qty, ref, "buy")
    }

    final override fun sell(qty: Double): Boolean {
        val ref = currentRefPrice()
        return execSide(-1, qty, ref, "sell")
    }

    final override fun buyFraction(frac: Double): Boolean {
        val f = frac.coerceIn(0.0, 1.0)
        val ref = currentRefPrice()
        if (ref <= 0) return false
        val budget = portfolio.cash * f
        val qty = budget / (ref * (1 + portfolio.feeBps / 10_000.0)) / ref
        return execSide(+1, qty, ref, "buyFrac")
    }

    final override fun sellFraction(frac: Double): Boolean {
        val f = frac.coerceIn(0.0, 1.0)
        val pos = portfolio.position
        val ref = currentRefPrice()
        if (pos <= 0 && !portfolio.allowShort) return false
        val qty = if (pos > 0) pos * f else abs(portfolio.cash * f / ref)  // weird case: shortStart — use equity fraction
        return execSide(-1, qty, ref, "sellFrac")
    }

    final override fun closePosition(): Boolean {
        val pos = portfolio.position
        if (abs(pos) < 1e-12) return true
        return execSide(if (pos > 0) -1 else +1, abs(pos), currentRefPrice(), "close")
    }

    final override fun limitBuy(price: Double, qty: Double): String? = execLimit(+1, price, qty)
    final override fun limitSell(price: Double, qty: Double): String? = execLimit(-1, price, qty)

    final override fun cancel(orderId: String): Boolean = runCatching {
        exchange().cancelOrder(spec.symbol, orderId)
    }.getOrDefault(false)

    protected fun execSide(side: Int, qty: Double, ref: Double, reason: String): Boolean {
        if (qty.isNaN() || qty <= 0 || ref <= 0) {
            log("order rejected: bad qty (${qty})")
            return false
        }
        val err = portfolio.check(side, qty, ref)
        if (err != null) {
            log("order rejected: $err")
            return false
        }
        val ok = execMarket(side, qty, ref)
        if (ok) log(String.format("%s %s %.8f @ ref %.4f", reason, if (side > 0) "BUY" else "SELL", qty, ref))
        return ok
    }

    final override fun bars(n: Int): Array<Candle> = barsWindow(n)
    final override fun opens(n: Int): DoubleArray = barsWindow(n).map { it.o }.toDoubleArray()
    final override fun highs(n: Int): DoubleArray = barsWindow(n).map { it.h }.toDoubleArray()
    final override fun lows(n: Int): DoubleArray = barsWindow(n).map { it.l }.toDoubleArray()
    final override fun closes(n: Int): DoubleArray = barsWindow(n).map { it.c }.toDoubleArray()
    final override fun volumes(n: Int): DoubleArray = barsWindow(n).map { it.v }.toDoubleArray()

    final override fun param(key: String, def: Any?): Any? = params[key] ?: def

    final override fun log(msg: String) {
        logs.add(LogLine(System.currentTimeMillis(), "I", msg))
        Log.i(TAG, "[$runId] $msg")
    }

    final override fun requestStop() {
        stopRequested = true
        onRollupStarted()
    }

    protected open fun onRollupStarted() {}

    protected fun exchange() = com.stratbot.app.exchange.Exchanges.get(spec.exchange)

    // ---------------- metrics ----------------

    protected fun computeMetrics(firstPrice: Double, lastPriceNow: Double): Metrics {
        val trades = portfolio.tradesList()
        val ps = portfolio.snapshot()
        val eqNow = if (lastPriceNow > 0) portfolio.equity(lastPriceNow) else ps.cash
        var wins = 0
        var losses = 0
        var grossWin = 0.0
        var grossLoss = 0.0
        var best = 0.0
        var worst = 0.0
        var winStreak = 0
        var lossStreak = 0
        var curWin = 0
        var curLoss = 0
        for (t in trades) {
            if (t.pnl >= 0) {
                wins++; grossWin += t.pnl; best = maxOf(best, t.pnl)
                curWin++; curLoss = 0
                winStreak = maxOf(winStreak, curWin)
            } else {
                losses++; grossLoss += t.pnl; worst = minOf(worst, t.pnl)
                curLoss++; curWin = 0
                lossStreak = maxOf(lossStreak, curLoss)
            }
        }
        val n = trades.size
        // max drawdown + sharpe from the curve
        var peak = Double.NEGATIVE_INFINITY
        var maxDd = 0.0
        val rets = ArrayList<Double>()
        var prev = Double.NaN
        synchronized(curve) {
            for (p in curve) {
                val eq = p[1]
                if (eq.isNaN()) continue
                if (eq > peak) peak = eq
                if (peak > 0) {
                    val dd = (peak - eq) / peak * 100.0
                    if (dd > maxDd) maxDd = dd
                }
                if (!prev.isNaN() && prev > 0) rets.add(eq / prev - 1.0)
                prev = eq
            }
        }
        var sharpe = 0.0
        if (rets.size > 2) {
            val mean = rets.average()
            var v = 0.0
            for (r in rets) v += (r - mean) * (r - mean)
            val sd = sqrt(v / (rets.size - 1))
            if (sd > 1e-12) {
                val periodsPerYear = (365.0 * 24 * 3600 * 1000) / Tf.toMs(spec.timeframe).toDouble()
                sharpe = mean / sd * sqrt(periodsPerYear)
            }
        }
        val pnl = eqNow - spec.startCash
        val exposure = if (barsDone > 0) barsInMarket.toDouble() / barsDone * 100.0 else 0.0
        return Metrics(
            startCash = spec.startCash,
            finalEquity = eqNow,
            pnl = pnl,
            roiPct = if (spec.startCash > 0) pnl / spec.startCash * 100.0 else 0.0,
            tradeCount = n,
            wins = wins,
            losses = losses,
            winRatePct = if (n > 0) wins.toDouble() / n * 100.0 else 0.0,
            profitFactor = if (grossLoss < 0) grossWin / abs(grossLoss) else if (grossWin > 0) Double.POSITIVE_INFINITY else 0.0,
            avgTradePnl = if (n > 0) trades.map { it.pnl }.average() else 0.0,
            bestTradePnl = best,
            worstTradePnl = worst,
            maxDrawdownPct = maxDd,
            sharpe = sharpe,
            maxWinStreak = winStreak,
            maxLossStreak = lossStreak,
            fees = ps.fees,
            buyHoldPct = if (firstPrice > 0) (lastPriceNow / firstPrice - 1.0) * 100.0 else 0.0,
            exposurePct = exposure,
        )
    }

    @Volatile
    protected var barsInMarket = 0L

    companion object {
        const val TAG = "StratBot"

        fun parseParams(json: String): Map<String, Any?> {
            val j = runCatching { JSONObject(json) }.getOrElse { return emptyMap() }
            val out = HashMap<String, Any?>()
            val it = j.keys()
            while (it.hasNext()) {
                val k = it.next()
                out[k] = j.get(k)
            }
            return out
        }
    }
}