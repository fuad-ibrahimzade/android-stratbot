package com.stratbot.app.runner

import android.app.Application
import android.util.Log
import com.stratbot.app.data.Services
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunMode
import com.stratbot.app.model.RunSpec
import com.stratbot.app.model.RunStatus
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry of every run (any number, simultaneously) + periodic snapshot writer
 * + notification updates.
 */
object RunManager {
    private const val TAG = "RunManager"
    private const val KEEP_TERMINAL_MS = 10 * 60_000L

    private val sessions = LinkedHashMap<String, RunSession>()
    private val globalListeners = CopyOnWriteArrayList<(Report) -> Unit>()

    @Volatile
    private var app: Application? = null
    private var timer: Timer? = null
    @Volatile
    private var lastNotifRefresh = 0L

    fun init(application: Application) {
        if (app != null) return
        app = application
        timer = Timer("stratbot-maintenance", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    maintain()
                }
            }, 5_000L, 5_000L)
        }
    }

    /** periodic: write snapshots of running sessions, clean finished ones, refresh notification */
    private fun maintain() {
        val now = System.currentTimeMillis()
        val snapshot = synchronized(sessions) { sessions.values.toList() }
        for (s in snapshot) {
            if (s.status == RunStatus.RUNNING) runCatching { s.publish() }
            else if (now - s.snapshot().endedAt >= KEEP_TERMINAL_MS) removeSession(s.runId)
        }
        refreshNotification(force = false)
    }

    // ---------------- registry ----------------

    fun start(spec: RunSpec): RunSession {
        val session: RunSession = when (spec.mode) {
            RunMode.BACKTEST -> BacktestSession(spec)
            else -> MarketSession(spec)
        }
        session.addListener { rep -> dispatch(rep) }
        synchronized(sessions) {
            // never allow two runs with the same id
            sessions.remove(spec.runId)
            sessions[spec.runId] = session
        }
        val startMsg = "starting ${spec.mode} ${spec.exchange} ${spec.symbol} ${spec.timeframe} ${spec.runId.take(8)}"
        Log.i(TAG, startMsg)
        session.start()
        RunnerService.start(app ?: throw IllegalStateException("app not initialised"))
        refreshNotification(force = true)
        return session
    }

    fun stop(runId: String) {
        val s = synchronized(sessions) { sessions[runId] }
        if (s != null) s.stop() else Log.w(TAG, "stop requested for unknown/finished run $runId")
    }

    fun stopAll() {
        val all = synchronized(sessions) { sessions.values.toList() }
        for (s in all) s.stop()
        refreshNotification(force = true)
    }

    fun get(runId: String): RunSession? = synchronized(sessions) { sessions[runId] }

    private fun removeSession(runId: String) {
        synchronized(sessions) { sessions.remove(runId) }
    }

    fun isActive(runId: String): Boolean =
        synchronized(sessions) { sessions[runId] }?.status == RunStatus.RUNNING

    /** in-memory snapshots of known sessions (running + recently finished) */
    fun sessionReports(): List<Report> {
        val list = synchronized(sessions) { sessions.values.toList() }
        return list.mapNotNull { runCatching { it.snapshot() }.getOrNull() }
    }

    fun report(runId: String): Report? {
        get(runId)?.let { return runCatching { it.snapshot() }.getOrNull() }
        return Services.runs.read(runId)
    }

    /** all reports: in-memory first (fresh), then anything on disk */
    fun allReports(limit: Int = 100): List<Report> {
        val mem = HashMap<String, Report>()
        for (r in sessionReports()) mem[r.runId] = r
        for (r in Services.runs.listReports(limit * 2)) if (!mem.containsKey(r.runId)) mem[r.runId] = r
        return mem.values.sortedByDescending { it.startedAt }.take(limit)
    }

    fun delete(runId: String) {
        if (isActive(runId)) stop(runId)
        removeSession(runId)
        Services.runs.delete(runId)
    }

    // ---------------- listeners ----------------

    fun addGlobalListener(l: (Report) -> Unit) = globalListeners.add(l)

    fun removeGlobalListener(l: (Report) -> Unit) = globalListeners.remove(l)

    private fun dispatch(rep: Report) {
        for (l in globalListeners) {
            try {
                l(rep)
            } catch (t: Throwable) {
                Log.w(TAG, "global listener: ${t.message}")
            }
        }
        refreshNotification(force = false)
    }

    fun refreshNotification(force: Boolean) {
        RunnerService.refresh(app ?: return, force)
    }

    fun summaryOf(reports: List<Report>): String {
            val running = reports.filter { it.status == RunStatus.RUNNING }
            val title = when {
                running.isEmpty() -> "StratBot"
                else -> "StratBot · ${running.size} running"
            }
            val body = StringBuilder()
            val shown = (running + reports.filter { it.status != RunStatus.RUNNING }).take(6)
            for (r in shown) {
                val line = when (r.status) {
                    RunStatus.RUNNING -> String.format(
                        "%s · %s · eq %.2f%s",
                        r.strategyName, r.mode, r.equity,
                        if (r.position != 0.0) String.format(" · %.4f", r.position) else ""
                    )
                    else -> String.format("%s · %s · %s", r.strategyName, r.mode, r.summaryLine())
                }
                body.append(line).append("\n")
            }
            if (shown.isEmpty()) body.append("No active runs")
            return title + "\n" + body.toString().trim()
    }
}