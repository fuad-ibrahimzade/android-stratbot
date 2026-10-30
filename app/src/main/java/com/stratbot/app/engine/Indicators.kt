package com.stratbot.app.engine

import kotlin.math.sqrt

/** Pure-Kotlin indicator maths, exposed to strategies as globals. */
object Indicators {

    @JvmStatic
    fun sma(a: DoubleArray, period: Int): Double {
        if (period <= 0 || a.size < period) return Double.NaN
        var s = 0.0
        val start = a.size - period
        for (i in start until a.size) s += a[i]
        return s / period
    }

    @JvmStatic
    fun ema(a: DoubleArray, period: Int): Double {
        if (period <= 0 || a.size < period) return Double.NaN
        val k = 2.0 / (period + 1)
        var e = 0.0
        val start = a.size - period
        for (i in 0 until period) e += a[start + i]
        e /= period
        for (i in (start + period) until a.size) e = a[i] * k + e * (1 - k)
        return e
    }

    /** Wilder RSI. */
    @JvmStatic
    fun rsi(a: DoubleArray, period: Int): Double {
        if (a.size <= period || period <= 0) return Double.NaN
        var gain = 0.0
        var loss = 0.0
        val n = a.size
        // seed over the first `period` changes ending at the last element
        val startIdx = n - period
        for (i in (startIdx - period + 1) until startIdx) {
            val d = a[i + 1] - a[i]
            if (d >= 0) gain += d else loss -= d
        }
        // the classic seeds use `period` deltas; use the last `period` deltas instead:
        gain = 0.0; loss = 0.0
        for (i in (n - period) until n) {
            val d = a[i] - a[i - 1]
            if (d >= 0) gain += d else loss -= d
        }
        gain /= period
        loss /= period
        if (loss == 0.0) return 100.0
        val rs = gain / loss
        return 100.0 - 100.0 / (1.0 + rs)
    }

    @JvmStatic
    fun stdev(a: DoubleArray, period: Int): Double {
        if (a.size < period || period <= 1) return Double.NaN
        val start = a.size - period
        var mean = 0.0
        for (i in start until a.size) mean += a[i]
        mean /= period
        var v = 0.0
        for (i in start until a.size) {
            val d = a[i] - mean
            v += d * d
        }
        return sqrt(v / (period - 1))
    }

    @JvmStatic
    fun atr(highs: DoubleArray, lows: DoubleArray, closes: DoubleArray, period: Int): Double {
        val n = minOf(highs.size, lows.size, closes.size)
        if (n < period + 1 || period <= 0) return Double.NaN
        val tr = DoubleArray(n)
        for (i in 1 until n) {
            val hl = highs[i] - lows[i]
            val hc = kotlin.math.abs(highs[i] - closes[i - 1])
            val lc = kotlin.math.abs(lows[i] - closes[i - 1])
            tr[i] = maxOf(hl, hc, lc)
        }
        var s = 0.0
        for (i in (n - period) until n) s += tr[i]
        return s / period
    }

    /** returns [macd, signal, histogram] */
    @JvmStatic
    fun macd(a: DoubleArray, fast: Int, slow: Int, signal: Int): DoubleArray {
        if (a.size < slow + signal) return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN)
        val emaFast = emaSeries(a, fast)
        val emaSlow = emaSeries(a, slow)
        val line = DoubleArray(a.size)
        for (i in a.indices) line[i] = emaFast[i] - emaSlow[i]
        val from = maxOf(0, a.size - signal)
        val sig = ema(line.copyOfRange(from, a.size), signal)
        val cur = line.last()
        return doubleArrayOf(cur, sig, cur - sig)
    }

    private fun emaSeries(a: DoubleArray, period: Int): DoubleArray {
        val out = DoubleArray(a.size)
        if (period <= 0 || a.size < period) {
            out.fill(Double.NaN)
            return out
        }
        val k = 2.0 / (period + 1)
        var e = 0.0
        for (i in 0 until period) e += a[i]
        e /= period
        out[period - 1] = e
        for (i in period until a.size) {
            e = a[i] * k + e * (1 - k)
            out[i] = e
        }
        return out
    }

    @JvmStatic
    fun highest(a: DoubleArray, n: Int): Double {
        if (a.isEmpty() || n <= 0) return Double.NaN
        var m = a[a.size - 1]
        for (i in maxOf(0, a.size - n) until a.size) m = maxOf(m, a[i])
        return m
    }

    @JvmStatic
    fun lowest(a: DoubleArray, n: Int): Double {
        if (a.isEmpty() || n <= 0) return Double.NaN
        var m = a[a.size - 1]
        for (i in maxOf(0, a.size - n) until a.size) m = minOf(m, a[i])
        return m
    }
}