package com.stratbot.app.ui

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.stratbot.app.R
import com.stratbot.app.model.Metrics
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunStatus
import com.stratbot.app.model.Trade
import com.stratbot.app.runner.RunManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class ReportFragment : Fragment() {

    companion object {
        private const val ARG_RUN = "run_id"
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
        fun newInstance(runId: String) = ReportFragment().apply {
            arguments = Bundle().apply { putString(ARG_RUN, runId) }
        }
    }

    private lateinit var runId: String
    private lateinit var body: LinearLayout
    private lateinit var status: TextView

    // skeleton sections, built once
    private lateinit var headerBox: LinearLayout     // strategy + dates + summary
    private lateinit var metricsBox: LinearLayout    // metric pairs
    private lateinit var chart: EquityCurveView
    private lateinit var tradesBox: LinearLayout
    private lateinit var logsBox: LinearLayout

    private var built = false
    private var metricsSig: String? = null
    private var tradesSig: String? = null
    private var logsSig: String? = null

    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            render()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        runId = requireArguments().getString(ARG_RUN) ?: ""

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(ctx, R.color.bg))
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 4))
        }
        head.addView(Ui.button(ctx, "< Back") { parentFragmentManager.popBackStack() })
        status = Ui.txt(ctx, "Report", 16f, bold = true)
        head.addView(status, LinearLayout.LayoutParams(MATCH, WRAP, 1f))
        head.addView(Ui.button(ctx, "Stop") { RunManager.stop(runId) })
        root.addView(head)

        body = LinearLayout(ctx).apply {
           orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 16), 0, Ui.dp(ctx, 16), Ui.dp(ctx, 24))
        }
        val scroll = ScrollView(ctx).apply {
            isFillViewport = true
            addView(body)
        }
        root.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun render() {
        if (!isAdded) return
        ensureSkeleton()
        val r = RunManager.report(runId) ?: return
        val m = r.metrics
        val ctx = requireContext()

        val headerColor = when {
            r.status == RunStatus.ERROR -> Ui.col(ctx, R.color.down)
            r.status == RunStatus.RUNNING -> Ui.col(ctx, R.color.primary)
            (m?.pnl ?: 0.0) > 0 -> Ui.col(ctx, R.color.up)
            (m?.pnl ?: 0.0) < 0 -> Ui.col(ctx, R.color.down)
            else -> Ui.col(ctx, R.color.textPrimary)
        }
        status.text = buildString {
            append(r.mode.name.lowercase()).append(" · ").append(r.status)
            if (r.status == RunStatus.RUNNING) append(" · ").append(Ui.fmtTs(System.currentTimeMillis()))
        }
        status.setTextColor(headerColor)

        val msig = "${r.equity}|${r.cash}|${r.position}|${r.bars}|${r.lastPrice}|${r.openOrders}|" +
            "${m?.pnl ?: -1.0}|${m?.roiPct ?: -1.0}|${r.status}|${r.error ?: ""}|${r.fees}"
        if (msig != metricsSig) {
            metricsSig = msig
            renderHeader(r, m)
            renderMetrics(r, m)
            chart.setCurve(r.curve, m?.startCash ?: 0.0)
        } else {
            chart.setCurve(r.curve, m?.startCash ?: 0.0)
        }

        val tsig = "${r.trades.size}"
        if (tsig != tradesSig) {
            tradesSig = tsig
            renderTrades(r.trades, m)
        }
        val lsig = "${r.logs.size}"
        if (lsig != logsSig) {
            logsSig = lsig
            renderLogs(r.logs)
        }
        lastReport = r
    }

    private var lastReport: Report? = null

    private fun ensureSkeleton() {
        if (built) return
        val ctx = requireContext()
        headerBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        metricsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        chart = EquityCurveView(ctx).also {
            it.id = R.id.chart
            it.layoutParams = LinearLayout.LayoutParams(MATCH, Ui.dp(ctx, 190))
        }
        tradesBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        logsBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        body.addView(headerBox, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(Ui.section(ctx, "metrics"))
        body.addView(metricsBox, LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(Ui.section(ctx, "equity curve"))
        body.addView(chart, LinearLayout.LayoutParams(MATCH, Ui.dp(ctx, 190)))

        // trades / logs boxes appended on demand by callers below
        val tradesHead = Ui.section(ctx, "trades")
        body.addView(tradesHead)
        body.addView(tradesBox, LinearLayout.LayoutParams(MATCH, WRAP))
        val logsHead = Ui.section(ctx, "log")
        body.addView(logsHead)
        body.addView(logsBox, LinearLayout.LayoutParams(MATCH, WRAP))
        built = true
    }

    private fun renderHeader(r: Report, m: Metrics?) {
        val ctx = requireContext()
        headerBox.removeAllViews()
        headerBox.addView(Ui.txt(ctx, r.strategyName, 20f, bold = true, single = true))
        headerBox.addView(
            Ui.txt(
                ctx,
                "${r.symbol} · ${r.timeframe} · ${r.exchange} · ${r.runId.takeLast(6)}",
                12f, Ui.col(ctx, R.color.textSecondary)
            )
        )
        headerBox.addView(
            Ui.txt(
                ctx,
                "${Ui.fmtDateTime(r.startedAt)} → " + if (r.endedAt > 0) Ui.fmtDateTime(r.endedAt) else "now",
                12f, Ui.col(ctx, R.color.textSecondary)
            )
        )
        if (r.status == RunStatus.ERROR && !r.error.isNullOrBlank()) {
            headerBox.addView(Ui.txt(ctx, "ERROR: ${r.error}", 13f, Ui.col(ctx, R.color.down), bold = true))
        }
        val eq = m?.finalEquity ?: r.equity
        headerBox.addView(Ui.txt(ctx, String.format("%.2f", eq), 34f, bold = true))
        if (m != null) {
            headerBox.addView(
                Ui.txt(
                    ctx,
                    "${String.format("%+.2f", m.pnl)} (${Ui.pct(m.roiPct)})   " +
                        "buy&hold ${Ui.pct(m.buyHoldPct)}   ${r.mode.name.lowercase()}",
                    14f, Ui.pnlColor(ctx, m.pnl), bold = true
                )
            )
        }
    }

    private fun renderMetrics(r: Report, m: Metrics?) {
        val ctx = requireContext()
        metricsBox.removeAllViews()
        if (m == null) {
            metricsBox.addView(Ui.txt(ctx, "computing…", 12f, Ui.col(ctx, R.color.textSecondary)))
            return
        }
        val pairs = listOf(
            "Max drawdown" to String.format("-%.2f%%", m.maxDrawdownPct),
            "Win rate" to String.format("%.1f%%  (%d/%d)", m.winRatePct, m.wins, m.tradeCount),
            "Profit factor" to String.format("%.2f", m.profitFactor),
            "Sharpe (ann.)" to String.format("%.2f", m.sharpe),
            "Trades" to m.tradeCount.toString(),
            "Exposure" to String.format("%.1f%%", m.exposurePct),
            "Avg trade" to String.format("%+.2f", m.avgTradePnl),
            "Fees" to String.format("%.2f", m.fees),
            "Best / worst" to String.format("%+.2f / %+.2f", m.bestTradePnl, m.worstTradePnl),
            "Win/loss streak" to "${m.maxWinStreak} / ${m.maxLossStreak}",
            "Cash" to String.format("%.2f", m.startCash),
            "Position" to Ui.qty(r.position),
            "Price" to Ui.money(r.lastPrice),
            "Bid / ask" to "${Ui.money(r.bid)} / ${Ui.money(r.ask)}",
            "Bars" to String.format("%,d", r.bars),
            "Open orders" to r.openOrders.toString(),
        )
        pairs.chunked(2).forEach { pair ->
            val line = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEach { (k, v) ->
                line.addView(metricCell(ctx, k, v), LinearLayout.LayoutParams(0, WRAP, 1f))
            }
            if (pair.size == 1) line.addView(View(ctx), LinearLayout.LayoutParams(0, WRAP, 1f))
            metricsBox.addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private fun renderTrades(trades: List<Trade>, m: Metrics?) {
        val ctx = requireContext()
        tradesBox.removeAllViews()
        if (trades.isEmpty()) {
            tradesBox.addView(
                Ui.txt(
                    ctx,
                    if (m == null) "no closed trades yet" else "${m.tradeCount} closed trades",
                    12f, Ui.col(ctx, R.color.textSecondary)
                )
            )
            return
        }
        trades.takeLast(80).reversed().forEach { t -> tradesBox.addView(tradeRow(ctx, t)) }
    }

    private fun renderLogs(logs: List<com.stratbot.app.model.LogLine>) {
        val ctx = requireContext()
        logsBox.removeAllViews()
        if (logs.isEmpty()) {
            logsBox.addView(Ui.txt(ctx, "no output", 12f, Ui.col(ctx, R.color.textSecondary)))
            return
        }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        logs.takeLast(150).forEach { l ->
            val txt = "${fmt.format(Date(l.ts))}  ${l.lvl}  ${l.msg}"
            val color = if (l.lvl == "E") Ui.col(ctx, R.color.down)
            else if (l.lvl == "W") Ui.col(ctx, R.color.warn)
            else Ui.col(ctx, R.color.textSecondary)
            logsBox.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(
                        TextView(ctx).apply {
                            text = txt
                            textSize = 11f
                            setTextColor(color)
                            typeface = Typeface.MONOSPACE
                            maxLines = 4
                        },
                        LinearLayout.LayoutParams(MATCH, WRAP),
                    )
                }
            )
        }
    }

    private fun metricCell(ctx: android.content.Context, k: String, v: String): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(ctx, 4), Ui.dp(ctx, 8), Ui.dp(ctx, 4))
            addView(Ui.txt(ctx, k, 11f, Ui.col(ctx, R.color.textSecondary)))
            addView(Ui.txt(ctx, v, 14f, bold = true))
        }

    private fun tradeRow(ctx: android.content.Context, t: Trade): LinearLayout {
        val dir = if (t.side >= 0) "LONG" else "SHORT"
        val move = if (t.entryPrice > 0)
            (t.exitPrice / t.entryPrice - 1.0) * 100.0 * t.side
        else 0.0
        val line1 = "● ${Ui.fmtTs(t.entryTs)}  $dir  ${Ui.qty(t.qty)} @ ${Ui.money(t.entryPrice)}"
        val line2 = "→ ${Ui.money(t.exitPrice)}  ${Ui.fmtTs(t.exitTs)}  " +
            "pnl ${String.format("%+.2f", t.pnl)} (${Ui.pct(move)})" +
            if (t.reason.isNotEmpty()) "  · ${t.reason}" else ""
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(ctx, 6), 0, Ui.dp(ctx, 6))
            addView(Ui.txt(ctx, line1, 12f, bold = true))
            addView(Ui.txt(ctx, line2, 12f, Ui.pnlColor(ctx, t.pnl)))
        }
    }

}