package com.stratbot.app.runner

import com.stratbot.app.engine.Portfolio
import com.stratbot.app.engine.StrategyRuntime
import com.stratbot.app.model.Candle
import com.stratbot.app.model.RunSpec
import com.stratbot.app.model.Tf

/**
 * Historical simulation.  Orders created inside onBar are filled at the NEXT bar's open
 * (no look-ahead), limit orders fill when the next bar's range crosses them.
 */
class BacktestSession(spec: RunSpec) : RunSession(spec) {

    override val portfolio = Portfolio(spec.startCash, spec.feeBps, spec.allowShort)

    private class Pending(val side: Int, val qty: Double, val limitPrice: Double, val kind: String)

    private val pending = ArrayList<Pending>()
    private val hist = ArrayList<Candle>()
    private var idx = 0
    private var orderSeq = 0
    private val slip = spec.slippageBps / 10_000.0
    private val tfMs = Tf.toMs(spec.timeframe)
    private val closeAtEnd: Boolean = params["closeAtEnd"].let {
        it == null || (it.toString() != "false")
    }

    override fun barsWindow(n: Int): Array<Candle> {
        if (hist.isEmpty()) return emptyArray()
        val from = maxOf(0, idx - n + 1)
        val to = minOf(idx + 1, hist.size)
        if (from >= to) return emptyArray()
        return hist.subList(from, to).toTypedArray()
    }

    override fun openOrdersCount(): Int = pending.size

    override fun execMarket(side: Int, qty: Double, refPrice: Double): Boolean {
        pending.add(Pending(side, qty, 0.0, "MKT"))
        return true
    }

    override fun execLimit(side: Int, price: Double, qty: Double): String? {
        portfolio.check(side, qty, price)?.let {
            log("limit rejected: $it")
            return null
        }
        pending.add(Pending(side, qty, price, "LMT"))
        orderSeq++
        return "bt-limit-$orderSeq"
    }

    private fun flushPending(effective: Long, openPrice: Double, high: Double, low: Double) {
        if (pending.isEmpty()) return
        val it = pending.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (p.kind == "MKT") {
                val px = if (p.side > 0) openPrice * (1 + slip) else openPrice * (1 - slip)
                val r = portfolio.apply(p.side, p.qty, px, effective, "market")
                if (!r.ok) log("fill failed: " + (r.msg ?: "unknown"))
                it.remove()
            } else {
                val crossed = if (p.side > 0) low <= p.limitPrice else high >= p.limitPrice
                if (crossed) {
                    val px = if (p.side > 0) minOf(p.limitPrice, openPrice) else maxOf(p.limitPrice, openPrice)
                    val r = portfolio.apply(p.side, p.qty, px, effective, "limit")
                    if (!r.ok) log("limit fill failed: " + (r.msg ?: "unknown"))
                    it.remove()
                }
            }
        }
    }

    override fun engineBody() {
        val ex = exchange()
        log("loading ${spec.maxBars} ${spec.timeframe} bars from ${ex.displayName} …")
        val all = try {
            ex.candles(spec.symbol, spec.timeframe, spec.startMs, spec.endMs, spec.maxBars)
        } catch (t: Throwable) {
            throw RuntimeException("history load failed: " + (t.message ?: t.javaClass.simpleName))
        }
        if (all.size < 10) throw RuntimeException("not enough candles returned (${all.size})")
        hist.addAll(all)
        lastPrice = all.first().c
        log("loaded ${hist.size} candles  " +
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(hist.first().t)) +
            " → " +
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(java.time.ZoneOffset.UTC)
                .format(java.time.Instant.ofEpochMilli(hist.last().t)))

        val rt = StrategyRuntime(this)
        runtime = rt
        rt.init()
        rt.load(spec.code)
        if (rt.has("init")) rt.call("init", rt.ctx)

        val firstPrice = all.first().c
        barsDone = 0
        idx = 1
        while (idx < hist.size) {
            if (stopRequested) break
            val bar = hist[idx]
            // 1. fill what the previous bar asked for
            flushPending(bar.t, bar.o, bar.h, bar.l)
            // 2. present this bar to the strategy
            lastPrice = bar.c
            if (curBid <= 0) {
                curBid = bar.o
                curAsk = bar.o
            }
            if (rt.has("onBar")) rt.call("onBar", rt.ctx, bar)
            // 3. book keep
            barsDone++
            if (portfolio.position != 0.0) barsInMarket++
            recordPoint(bar.t + tfMs, portfolio.equity(bar.c))
            lastPrice = bar.c
            curBid = bar.c
            curAsk = bar.c
            if (barsDone % 250L == 0L) publish()
            idx++
        }
        idx = maxOf(0, hist.size - 1)
        val lastBar = hist.last()
        lastPrice = lastBar.c

        if (pending.isNotEmpty()) {
            log("dropping ${pending.size} unfilled order(s) at end of test")
            pending.clear()
        }

        if (portfolio.position != 0.0 && closeAtEnd && !stopRequested) {
            val pos = portfolio.position
            val side = if (pos > 0) -1 else +1
            val px = if (side > 0) lastPrice * (1 + slip) else lastPrice * (1 - slip)
            val r = portfolio.apply(side, kotlin.math.abs(pos), px, lastBar.t + tfMs, "closeAtEnd")
            if (r.ok) log(String.format("closed position @ %.4f", px))
            else log("could not close position: " + (r.msg ?: ""))
            recordPoint(lastBar.t + tfMs, portfolio.equity(lastPrice))
        }

        metricsCache = computeMetrics(firstPrice, lastPrice)
        val m = metricsCache
        log(
            "backtest done: " + barsDone + " bars, " + (m?.tradeCount ?: 0) + " trades, ROI " +
                String.format("%+.2f%%", m?.roiPct ?: 0.0) + ", maxDD " +
                String.format("%.2f%%", m?.maxDrawdownPct ?: 0.0)
        )
        publish(force = true)
    }

    override fun dispose() {
        pending.clear()
        hist.clear()
    }
}