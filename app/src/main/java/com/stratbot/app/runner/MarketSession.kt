package com.stratbot.app.runner

import com.stratbot.app.engine.Portfolio
import com.stratbot.app.engine.StrategyRuntime
import com.stratbot.app.exchange.Stream
import com.stratbot.app.exchange.StreamHandlers
import com.stratbot.app.model.Candle
import com.stratbot.app.model.RunMode
import com.stratbot.app.model.TickInfo
import com.stratbot.app.model.RunSpec
import com.stratbot.app.model.Tf
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Dry / live session: websocket ticks + closed bars (REST polling fallback),
 * same strategy callbacks as the backtest.
 */
class MarketSession(spec: RunSpec) : RunSession(spec), StreamHandlers {

    override val portfolio = Portfolio(spec.startCash, spec.feeBps, spec.allowShort)

    private val live = spec.mode == RunMode.LIVE
    private val tfMs = Tf.toMs(spec.timeframe)
    private val slip = spec.slippageBps / 10_000.0

    private val hist = ArrayList<Candle>()
    private var lastClosedTs = 0L
    private var firstPrice = 0.0

    private sealed interface Ev {
        data class Tick(val bid: Double, val ask: Double, val last: Double) : Ev
        data class Bar(val c: Candle) : Ev
        data class Status(val msg: String) : Ev
        data class Err(val msg: String) : Ev
    }

    private val queue = LinkedBlockingQueue<Ev>()
    private var stream: Stream? = null
    @Volatile
    private var restMode = false
    private var tickErrorLogged = false
    private var lastRestPoll = 0L
    @Volatile
    private var lastStreamMsg = 0L

    private class DryLimit(val id: String, val side: Int, val price: Double, val qty: Double)
    private val dryLimits = LinkedHashMap<String, DryLimit>()

    private class LiveOrder(val id: String, val side: Int, val price: Double, val qty: Double, var applied: Double)
    private val liveOrders = ArrayList<LiveOrder>()
    private var orderSeq = 0

    private val cancelOnStop: Boolean = params["cancelOnStop"].let { it == null || it.toString() != "false" }

    // ---------------- history ----------------

    override fun barsWindow(n: Int): Array<Candle> {
        if (hist.isEmpty()) return emptyArray()
        val from = maxOf(0, hist.size - n)
        return hist.subList(from, hist.size).toTypedArray()
    }

    override fun openOrdersCount(): Int = dryLimits.size + liveOrders.size

    private fun appendClosed(c: Candle): Boolean {
        if (c.t <= lastClosedTs || c.t + tfMs > System.currentTimeMillis() + 50) return false
        hist.add(c)
        lastClosedTs = c.t
        while (hist.size > Services.settings.warmupBars * 3) hist.removeAt(0)
        if (firstPrice <= 0) firstPrice = c.c
        return true
    }

    // ---------------- orders ----------------

    override fun execMarket(side: Int, qty: Double, refPrice: Double): Boolean {
        if (!live) {
            val px = if (side > 0) ask() * (1 + slip) else bid() * (1 - slip)
            if (px <= 0) {
                log("no quote yet, order skipped")
                return false
            }
            val r = portfolio.apply(side, qty, px, System.currentTimeMillis(), "dry-market")
            if (!r.ok) {
                log("fill failed: " + (r.msg ?: ""))
                return false
            }
            recordPoint(now(), portfolio.equity(px))
            return true
        }

        // live: real order on the exchange
        val res = runCatching { exchange().placeOrder(spec.symbol, if (side > 0) "BUY" else "SELL", "MARKET", qty, 0.0) }
            .getOrElse {
                log("order failed: " + (it.message ?: "network error"))
                return false
            }
        if (!res.ok || res.filledQty <= 0 && res.status == "ERROR") {
            log("order rejected (${res.status}): ${res.raw.take(160)}")
            return false
        }
        val filled = if (res.filledQty > 0) res.filledQty else qty
        val px = if (res.avgPrice > 0) res.avgPrice else (if (side > 0) ask() else bid())
        val r = portfolio.apply(side, filled, px, System.currentTimeMillis(), "live-market")
        if (!r.ok) {
            log("fill accounting failed: " + (r.msg ?: ""))
            return false
        }
        log(String.format("LIVE filled %.8f @ %.4f (%s)", filled, px, res.status))
        if (filled + 1e-12 < qty) log(String.format("partial fill (%.8f of %.8f)", filled, qty))
        if (res.orderId.isNotEmpty() && filled + 1e-12 < qty && res.status == "NEW") {
            liveOrders.add(LiveOrder(res.orderId, side, px, qty, filled))
        }
        return true
    }

    override fun execLimit(side: Int, price: Double, qty: Double): String? {
        portfolio.check(side, qty, price)?.let {
            log("limit rejected: $it")
            return null
        }
        if (!live) {
            orderSeq++
            val id = "dry-limit-$orderSeq"
            dryLimits[id] = DryLimit(id, side, price, qty)
            return id
        }
        val res = runCatching { exchange().placeOrder(spec.symbol, if (side > 0) "BUY" else "SELL", "LIMIT", qty, price) }
            .getOrElse {
                log("limit order failed: " + (it.message ?: "network error"))
                return null
            }
        if (!res.ok && res.filledQty <= 0) {
            log("limit rejected (${res.status}): ${res.raw.take(160)}")
            return null
        }
        if (res.filledQty > 0) {
            val px = if (res.avgPrice > 0) res.avgPrice else price
            portfolio.apply(side, res.filledQty, px, System.currentTimeMillis(), "live-limit")
        }
        if (res.orderId.isNotEmpty() && res.filledQty + 1e-12 < qty && res.status != "CANCELED") {
            liveOrders.add(LiveOrder(res.orderId, side, price, qty, res.filledQty))
        }
        return res.orderId.ifEmpty { null }
    }

    private fun pollDryLimits() {
        if (dryLimits.isEmpty()) return
        val b = bid()
        val a = ask()
        if (b <= 0 && a <= 0) return
        val it = dryLimits.entries.iterator()
        while (it.hasNext()) {
            val d = it.next().value
            val hit = when {
                d.side > 0 && a in 0.0..d.price -> true
                d.side < 0 && b >= d.price -> true
                else -> false
            }
            if (hit) {
                val px = if (d.side > 0) minOf(d.price, if (a > 0) a else d.price) else maxOf(d.price, b)
                val r = portfolio.apply(d.side, d.qty, px, System.currentTimeMillis(), "dry-limit")
                log(
                    String.format(
                        "%s limit filled %.8f @ %.4f%s",
                        if (d.side > 0) "BUY" else "SELL", d.qty, px,
                        if (r.ok) "" else " FAILED: " + (r.msg ?: "")
                    )
                )
                it.remove()
            }
        }
    }

    private fun pollLiveOrders() {
        if (liveOrders.isEmpty()) return
        val ex = exchange()
        val it = liveOrders.iterator()
        while (it.hasNext()) {
            val o = it.next()
            val st = runCatching { ex.orderStatus(spec.symbol, o.id) }.getOrNull() ?: continue
            if (st.status == "UNKNOWN" && st.filledQty <= 0) continue
            val delta = st.filledQty - o.applied
            if (delta > 1e-12) {
                val px = if (st.avgPrice > 0) st.avgPrice else o.price
                val r = portfolio.apply(o.side, delta, px, System.currentTimeMillis(), "live-limit-fill")
                o.applied += delta
                if (r.ok) log(String.format("LIVE limit %.8f @ %.4f (%s)", delta, px, st.status))
            }
            val terminal = st.status in listOf("FILLED", "CANCELED", "REJECTED", "EXPIRED") || o.applied + 1e-12 >= o.qty
            if (terminal) {
                log("order ${o.id} → ${st.status}")
                it.remove()
            }
        }
    }

    // ---------------- stream callbacks (OkHttp thread) ----------------

    override fun onTick(bid: Double, ask: Double, last: Double) {
        lastStreamMsg = System.currentTimeMillis()
        queue.offer(Ev.Tick(bid, ask, last))
    }

    override fun onBarClosed(c: Candle) {
        lastStreamMsg = System.currentTimeMillis()
        queue.offer(Ev.Bar(c))
    }

    override fun onStatus(msg: String) {
        queue.offer(Ev.Status(msg))
    }

    override fun onError(msg: String) {
        queue.offer(Ev.Err(msg))
    }

    // ---------------- engine ----------------

    override fun engineBody() {
        val ex = exchange()
        val now = System.currentTimeMillis()
        val end = now - tfMs          // exclude the candle that is still forming
        log("loading warmup history …")
        val bars = try {
            ex.candles(spec.symbol, spec.timeframe, 0L, end, Services.settings.warmupBars)
        } catch (t: Throwable) {
            throw RuntimeException("history load failed: " + (t.message ?: "network"))
        }
        if (bars.isEmpty()) throw RuntimeException("no candles returned")
        val clean = bars.filter { it.t + tfMs <= now }
        hist.addAll(clean)
        lastClosedTs = hist.maxOfOrNull { it.t } ?: 0L
        firstPrice = hist.firstOrNull()?.c ?: 0.0
        lastPrice = hist.lastOrNull()?.c ?: 0.0
        if (hist.isEmpty()) throw RuntimeException("no closed candles yet")
        log("warmup: ${hist.size} bars, last=${lastPrice}, mode=${spec.mode}")

        val rt = StrategyRuntime(this)
        runtime = rt
        rt.init()
        rt.load(spec.code)
        if (rt.has("init")) rt.call("init", rt.ctx)

        // market data (ws with automatic REST fallback)
        stream = ex.openStream(spec.symbol, spec.timeframe, this)
        stream?.start()
        lastStreamMsg = System.currentTimeMillis()
        publish(force = true)

        var lastLivePoll = 0L
        var started = System.currentTimeMillis()
        while (!stopRequested) {
            var ev: Ev? = queue.poll(300, TimeUnit.MILLISECONDS)
            var handled = 0
            while (ev != null && handled++ < 100) {
                when (ev) {
                    is Ev.Tick -> handleTick(ev.bid, ev.ask, ev.last)
                    is Ev.Bar -> handleBar(ev.c)
                    is Ev.Status -> log("stream: ${ev.msg}")
                    is Ev.Err -> handleStreamError(ev.msg)
                }
                if (stopRequested) break
                ev = queue.poll()
            }

            val t = System.currentTimeMillis()
            // websocket looks dead → fall back to REST polling
            if (!restMode && Services.settings.restFallback && t - lastStreamMsg > 45_000) {
                restMode = true
                log("stream silent for 45s → switching to REST polling every ${Services.settings.pollIntervalMs} ms")
            }
            if (restMode && t - lastRestPoll >= Services.settings.pollIntervalMs) {
                lastRestPoll = t
                runCatching { restPoll() }.onFailure { log("poll failed: " + (it.message ?: "")) }
            }
            if (t - lastLivePoll >= 4000) {
                lastLivePoll = t
                if (live) pollLiveOrders()
                else pollDryLimits()
            }
            publish()
        }
        log("stopping after " + (System.currentTimeMillis() - started) + " ms")

        // teardown
        if (live && liveOrders.isNotEmpty() && cancelOnStop) {
            for (o in liveOrders) {
                val ok = runCatching { exchange().cancelOrder(spec.symbol, o.id) }.getOrDefault(false)
                log("cancel ${o.id}: " + (if (ok) "ok" else "failed"))
            }
        }
        liveOrders.clear()
        dryLimits.clear()

        metricsCache = computeMetrics(firstPrice, lastPrice)
        val m = metricsCache
        log(
            "run finished (${spec.mode}): trades=" + (m?.tradeCount ?: 0) +
                " ROI " + String.format("%+.2f%%", m?.roiPct ?: 0.0) +
                " equity " + String.format("%.2f", portfolio.equity(currentRefPrice()))
        )
        publish(force = true)
    }

    private fun handleTick(bid: Double, ask: Double, last: Double) {
        if (bid > 0) curBid = bid
        if (ask > 0) curAsk = ask
        if (last > 0) lastPrice = last
        else if (bid > 0 && ask > 0) lastPrice = (bid + ask) / 2
        pollDryLimits()
        val rt = runtime
        if (rt != null && !stopRequested && rt.has("onTick")) {
            val info = TickInfo(System.currentTimeMillis(), curBid, curAsk, lastPrice)
            runCatching { rt.call("onTick", rt.ctx, info) }.onFailure { t ->
                if (!tickErrorLogged) {
                    tickErrorLogged = true
                    log("onTick error: " + (t.message ?: t.javaClass.simpleName))
                }
            }
        }
        publish()
    }

    private fun handleBar(c: Candle) {
        if (!appendClosed(c)) return
        barsDone++
        if (portfolio.position != 0.0) barsInMarket++
        lastPrice = c.c
        recordPoint(c.t + tfMs, portfolio.equity(c.c))
        val rt = runtime ?: return
        if (stopRequested) return
        if (rt.has("onBar")) rt.call("onBar", rt.ctx, c)
        publish()
    }

    private fun handleStreamError(msg: String) {
        if (Services.settings.restFallback && !restMode) {
            restMode = true
            log("stream error ($msg) → REST polling fallback")
        } else {
            log("stream: $msg")
        }
    }

    private fun restPoll() {
        val q = runCatching { exchange().quote(spec.symbol) }.getOrNull()
        if (q != null) handleTick(q.bid, q.ask, q.last)
        val recent = runCatching {
            exchange().candles(spec.symbol, spec.timeframe, 0L, System.currentTimeMillis(), 3)
        }.getOrNull() ?: return
        for (c in recent) if (c.t > lastClosedTs) handleBar(c)
    }

    override fun dispose() {
        stream?.close()
        stream = null
        queue.clear()
        dryLimits.clear()
        liveOrders.clear()
        hist.clear()
    }
}

private typealias Services = com.stratbot.app.data.Services