package com.stratbot.app.ui

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.stratbot.app.R
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunStatus
import com.stratbot.app.runner.RunManager

class RunsFragment : Fragment() {

    private lateinit var adapter: Adapter
    private lateinit var empty: TextView
    private lateinit var status: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!isAdded) return
            refresh()
            handler.postDelayed(this, 2500L)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.col(ctx, R.color.bg))
        }

        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 10), Ui.dp(ctx, 16), Ui.dp(ctx, 4))
        }
        status = Ui.txt(ctx, "Runs", 18f, bold = true)
        head.addView(status, LinearLayout.LayoutParams(MATCH, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(Ui.button(ctx, "Stop all") {
            RunManager.stopAll()
            Ui.toast(ctx, "Stop requested for all runs")
            handler.post { refresh() }
        })
        root.addView(head)

        val overlap = FrameLayout(ctx)
        val rv = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            clipToPadding = false
            setPadding(0, Ui.dp(ctx, 4), 0, Ui.dp(ctx, 24))
        }
        adapter = Adapter()
        rv.adapter = adapter
        overlap.addView(rv, FrameLayout.LayoutParams(MATCH, MATCH))

        empty = Ui.txt(
            ctx,
            "No runs yet.\n\nStart one from the Strategies tab:\n\n" +
                "Backtest — replay history, simulated fills\n" +
                "Dry — live prices, simulated money\n" +
                "Live — real orders (needs API keys)",
            13f, Ui.col(ctx, R.color.textSecondary)
        ).apply { gravity = Gravity.CENTER }
        overlap.addView(
            empty, FrameLayout.LayoutParams(MATCH, WRAP).apply { gravity = Gravity.CENTER }
        )
        root.addView(overlap, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    override fun onResume() {
        super.onResume()
        refresh()
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, 2500L)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    private fun refresh() {
        if (!isAdded) return
        val reports = RunManager.allReports(60)
        adapter.items = reports
        adapter.notifyDataSetChanged()
        empty.visibility = if (reports.isEmpty()) View.VISIBLE else View.GONE
        val running = reports.count { it.status == RunStatus.RUNNING }
        status.text = if (running > 0) "Runs · $running running" else "Runs"
    }

    // ------------------------------------------------------------------

    private inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<Report> = emptyList()
        private lateinit var inflater: android.view.LayoutInflater

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            inflater = LayoutInflater.from(parent.context)
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Ui.col(ctx, R.color.surface))
                setPadding(Ui.dp(ctx, 16), Ui.dp(ctx, 12), Ui.dp(ctx, 16), Ui.dp(ctx, 12))
                layoutParams = RecyclerView.LayoutParams(MATCH, WRAP)
            }
            return VH(row)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val r = items[position]
            val row = holder.row
            row.removeAllViews()
            val ctx = row.context

            val top = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            top.addView(
                Ui.txt(ctx, r.runId.takeLast(6), 11f, Ui.col(ctx, R.color.textSecondary)),
            )
            val chip = Ui.txt(ctx, r.mode.name, 10f, Ui.col(ctx, R.color.bg), bold = true).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(ctx, 8), Ui.dp(ctx, 3), Ui.dp(ctx, 8), Ui.dp(ctx, 3))
                setBackgroundResource(R.drawable.chip_bg)
            }
            top.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP))
            top.addView(Ui.txt(ctx, " " + r.symbol + " · " + r.timeframe + " · " + r.exchange, 11f, Ui.col(ctx, R.color.textSecondary)))
            top.addView(
                Ui.txt(
                    ctx, r.status.name, 11f,
                    if (r.status == RunStatus.RUNNING) Ui.col(ctx, R.color.primary)
                    else Ui.pnlColor(ctx, r.metrics?.pnl ?: 0.0),
                    bold = true,
                ),
                LinearLayout.LayoutParams(MATCH, WRAP)
            )
            row.addView(top)

            val statusColor = when {
                r.status == RunStatus.ERROR -> Ui.col(ctx, R.color.down)
                r.status == RunStatus.RUNNING -> Ui.col(ctx, R.color.primary)
                else -> Ui.pnlColor(ctx, r.metrics?.pnl ?: 0.0)
            }
            row.addView(
                Ui.txt(
                    ctx,
                    "${Ui.fmtTs(r.startedAt)} · ${r.summaryLine()}",
                    13f, statusColor, bold = true
                )
            )
            if (r.status == RunStatus.ERROR) {
                row.addView(Ui.txt(ctx, r.error ?: "error", 10f, Ui.col(ctx, R.color.down)))
            }

            val bar = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, Ui.dp(ctx, 6), 0, 0) }
            bar.addView(Ui.button(ctx, "Open report") {
                // `view` here is Fragment.getView() — the root layout, whose id is
                // View.NO_ID — so replacing into it throws
                // "No view found for id 0xffffffff" and crashes the app.
                // Navigate exactly like the row click does.
                (activity as? MainActivity)?.push(ReportFragment.newInstance(r.runId))
            })
            if (r.status == RunStatus.RUNNING || r.status == RunStatus.ERROR) {
                bar.addView(Ui.button(ctx, "Stop") { RunManager.stop(r.runId) })
            }
            if (r.status != RunStatus.RUNNING) {
                bar.addView(Ui.button(ctx, "Delete") { confirmDelete(r) })
            }
            row.addView(bar)

            row.setOnClickListener {
                (activity as? MainActivity)?.push(ReportFragment.newInstance(r.runId))
            }
        }

        private fun confirmDelete(r: Report) {
            AlertDialog.Builder(requireContext())
                .setTitle("Delete run and report?")
                .setMessage(r.runId)
                .setPositiveButton("Delete") { _, _ -> RunManager.delete(r.runId); refresh() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private class VH(val row: LinearLayout) : RecyclerView.ViewHolder(row)

    companion object {
        private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT
    }
}