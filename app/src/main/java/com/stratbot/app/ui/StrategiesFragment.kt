package com.stratbot.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stratbot.app.R
import com.stratbot.app.data.Services
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunMode
import com.stratbot.app.model.RunSpec
import com.stratbot.app.model.RunStatus
import com.stratbot.app.model.Strategy
import com.stratbot.app.model.Tf
import com.stratbot.app.runner.RunManager

val STARTER_CODE = """// StratBot strategy
// Callbacks: init(ctx), onBar(ctx, bar), onTick(ctx, tick)
// Helpers:   sma, ema, rsi, atr, stdev, macd, highest, lowest, slice(a, from)

function init(ctx) {
  ctx.log(ctx.symbol + " " + ctx.timeframe + " ready");
}

function onBar(ctx, bar) {
  var closes = ctx.closes(120);
  if (closes.length < 60) return;
  var trend = sma(closes, 50);

  if (ctx.position > 0 && bar.c < trend * 0.98) {
    ctx.close();                       // exit on -2% from trend
  } else if (ctx.position <= 0 && bar.c > trend) {
    ctx.buyFraction(0.95);             // spend 95% of available cash
  }
}
"""

class StrategiesFragment : Fragment() {

    private lateinit var adapter: StrategyAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(ctx, R.color.bg))
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 8), Ui.dp(ctx, 16), Ui.dp(ctx, 4))
        }
        head.addView(
            Ui.txt(ctx, "Strategies", 18f, bold = true),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        head.addView(Ui.button(ctx, "+ New") { createStrategy() })
        root.addView(head)

        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
            setPadding(0, 0, 0, Ui.dp(ctx, 24))
        }
        adapter = StrategyAdapter()
        rv.adapter = adapter
        root.addView(rv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val strategies = Services.strategies.list()
        val latest = RunManager.allReports(60).groupBy { it.strategyId }
            .mapValues { (_, v) -> v.maxByOrNull { it.startedAt }!! }
        adapter.items = strategies
        adapter.lastRun = latest
        adapter.notifyDataSetChanged()
    }

    private fun createStrategy() {
        val s = Strategy(
            name = "Untitled strategy",
            code = STARTER_CODE,
            symbol = "BTCUSDT",
            timeframe = "5m",
            exchange = "binance",
            paramsJson = """{}""",
        )
        Services.strategies.save(s)
        (activity as? MainActivity)?.push(StrategyEditFragment.newInstance(s.id))
    }

    // ------------------------------------------------------------------
    // run configuration dialog
    // ------------------------------------------------------------------

    private fun showRunConfig(s: Strategy, mode: RunMode) {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(ctx, 24), Ui.dp(ctx, 8), Ui.dp(ctx, 24), Ui.dp(ctx, 8))
        }

        fun field(label: String, value: String, single: Boolean = true, mono: Boolean = false): EditText {
            content.addView(Ui.txt(ctx, label, 11f, Ui.col(ctx, R.color.textSecondary)))
            val et = EditText(ctx).apply {
                setText(value)
                isSingleLine = single
                setTextColor(Ui.col(ctx, R.color.textPrimary))
                setHintTextColor(Ui.col(ctx, R.color.textSecondary))
                if (mono) typeface = android.graphics.Typeface.MONOSPACE
                inputType = InputType.TYPE_CLASS_TEXT or
                    if (single) InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS else InputType.TYPE_TEXT_FLAG_MULTI_LINE
            }
            content.addView(et, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT))
            return et
        }

        if (mode == RunMode.LIVE) {
            content.addView(
                Ui.txt(
                    ctx,
                    "\u26A0 LIVE mode submits REAL orders with the API key from Settings. Start with backtests and dry runs first.",
                    12f, Ui.col(ctx, R.color.warn), bold = true
                )
            )
        }

        val symbolEt = field("Symbol", s.symbol)
        val tfs = Tf.supportedFor(s.exchange)
        val tfOption: List<String> = if (tfs.contains(s.timeframe)) tfs else listOf(s.timeframe) + tfs
        val tfSpin = Spinner(ctx).apply {
            adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, tfOption)
            setSelection((tfOption.indexOf(s.timeframe)).coerceAtLeast(0))
        }
        val barsEt = EditText(ctx).apply {
            setText("1500")
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(Ui.col(ctx, R.color.textPrimary))
        }

        val middle = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        middle.addView(
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, Ui.dp(ctx, 12), 0)
                addView(Ui.txt(ctx, "Timeframe", 11f, Ui.col(ctx, R.color.textSecondary)))
                addView(tfSpin)
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
        if (mode == RunMode.BACKTEST) {
            middle.addView(
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.txt(ctx, "Bars", 11f, Ui.col(ctx, R.color.textSecondary)))
                    addView(barsEt)
                },
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
        }
        content.addView(middle, LinearLayout.LayoutParams(MATCH, WRAP))

        val cashEt = field("Starting cash (quote)", "10000")
        val paramsEt = field("Params (JSON)", s.paramsJson, single = false, mono = true)

        val scroll = ScrollView(ctx).apply { addView(content) }
        val warnMissingKeys = mode == RunMode.LIVE && !Services.keys.has(s.exchange)

        AlertDialog.Builder(ctx)
            .setTitle("${mode.name.lowercase()} · ${s.name}")
            .setView(scroll)
            .setPositiveButton("Start") { _, _ ->
                if (warnMissingKeys) confirmNoKeys(s, mode, symbolEt, tfSpin, barsEt, cashEt, paramsEt)
                else startRun(s, mode, symbolEt, tfSpin, barsEt, cashEt, paramsEt)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmNoKeys(
        s: Strategy, mode: RunMode, symbol: EditText, tf: Spinner, bars: EditText, cash: EditText, params: EditText
    ) {
        AlertDialog.Builder(requireContext())
            .setTitle("No API keys")
            .setMessage("No ${s.exchange} API key is configured in Settings — the live order will fail. Start anyway?")
            .setPositiveButton("Start anyway") { _, _ -> startRun(s, mode, symbol, tf, bars, cash, params) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startRun(
        s: Strategy, mode: RunMode, symbol: EditText, tf: Spinner,
        bars: EditText, cash: EditText, params: EditText
    ) {
        val tfSel = (tf.selectedItem as? String) ?: s.timeframe
        val spec = RunSpec(
            strategyId = s.id,
            strategyName = s.name,
            mode = mode,
            exchange = s.exchange,
            symbol = symbol.text.toString().trim().ifEmpty { s.symbol },
            timeframe = tfSel,
            code = s.code,
            paramsJson = params.text.toString().trim().ifEmpty { "{}" },
            allowShort = s.allowShort,
            feeBps = s.feeBps,
            slippageBps = s.slippageBps,
            startCash = (cash.text.toString().toDoubleOrNull() ?: 10000.0).let {
                if (it.isFinite() && it > 0) it else 10000.0
            },
            startMs = 0,
            endMs = 0,
            // history is fetched in 1000-bar pages: an unbounded value here makes a
            // backtest run for minutes/hours with no visible progress — clamp it
            maxBars = (bars.text.toString().toIntOrNull() ?: 1500).coerceIn(20, 20_000),
        )
        RunManager.start(spec)
        Ui.toast(requireContext(), "${mode.name} started")
        (activity as? MainActivity)?.selectTab(1)
    }

    // ------------------------------------------------------------------

    private inner class StrategyAdapter : RecyclerView.Adapter<VH>() {
        var items: List<Strategy> = emptyList()
        var lastRun: Map<String, Report> = emptyMap()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
                setBackgroundColor(Ui.col(ctx, R.color.surface))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            row.setOnClickListener { v ->
                val s = items[v.tag as Int]
                (activity as? MainActivity)?.push(StrategyEditFragment.newInstance(s.id))
            }
            return VH(row)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val row = holder.row
            row.tag = position
            row.removeAllViews()
            val ctx = row.context

            val title = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            title.addView(
                Ui.txt(ctx, items[position].name, 16f, bold = true),
                LinearLayout.LayoutParams(0, WRAP, 1f),
            )
            title.addView(
                Ui.txt(ctx, items[position].exchange.uppercase(), 10f, Ui.col(ctx, R.color.textSecondary), bold = true)
            )
            row.addView(title)

            row.addView(
                Ui.txt(
                    ctx,
                    "${items[position].symbol} · ${items[position].timeframe} · ${items[position].allowShort}",
                    12f, Ui.col(ctx, R.color.textSecondary), single = true
                )
            )

            val run = lastRun[items[position].id]
            if (run != null) {
                val m = run.metrics
                val color = if (run.status == RunStatus.RUNNING) Ui.col(ctx, R.color.primary)
                else Ui.pnlColor(ctx, m?.roiPct ?: 0.0)
                row.addView(
                    Ui.txt(
                        ctx,
                        "last ${run.mode.name.lowercase()} · ${run.status} · ${run.summaryLine()}",
                        12f, color, bold = true
                    )
                )
            }

            val actions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            actions.setPadding(0, Ui.dp(ctx, 6), 0, 0)
            actions.addView(Ui.button(ctx, "Edit") {
                (activity as? MainActivity)?.push(StrategyEditFragment.newInstance(items[position].id))
            })
            actions.addView(
                Ui.button(ctx, "Backtest") { showRunConfig(items[position], RunMode.BACKTEST) }
            )
            actions.addView(
                Ui.button(ctx, "Dry") { showRunConfig(items[position], RunMode.DRY) }
            )
            actions.addView(
                Ui.button(ctx, "Live") { showRunConfig(items[position], RunMode.LIVE) }
            )
            row.addView(actions)
            setSpace(row, 4)
        }

        private fun setSpace(row: LinearLayout, dp: Int) {
            val v = View(row.context)
            v.layoutParams = LinearLayout.LayoutParams(MATCH, Ui.dp(row.context, dp))
            row.addView(v)
        }
    }

    private class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)
}

private const val MATCH: Int = LinearLayout.LayoutParams.MATCH_PARENT
private const val WRAP: Int = LinearLayout.LayoutParams.WRAP_CONTENT