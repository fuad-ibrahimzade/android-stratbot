package com.stratbot.app.runner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.stratbot.app.R
import com.stratbot.app.model.Report
import com.stratbot.app.ui.MainActivity

/**
 * Foreground service keeping live/dry (and backtest) reports reachable at any moment:
 * ongoing notification → tap → report screen.
 */
class RunnerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private val listener: (Report) -> Unit = { _ -> appRefresh(force = false) }
    private val ticker = object : Runnable {
        override fun run() {
            val active = RunManager.sessionReports().any { it.status == com.stratbot.app.model.RunStatus.RUNNING }
            if (!active && !forceKeep) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            appRefresh(force = true)
            handler.postDelayed(this, 15_000L)
        }
    }

    @Volatile
    private var forceKeep = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        RunManager.addGlobalListener(listener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                RunManager.stopAll()
                handler.removeCallbacks(ticker)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        forceKeep = intent?.getBooleanExtra(EXTRA_KEEP, false) ?: false
        startForeground(NOTIF_ID, buildNotification())
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 15_000L)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        RunManager.removeGlobalListener(listener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun appRefresh(force: Boolean) {
        if (force || System.currentTimeMillis() - lastBuild > 3000L) {
            lastBuild = System.currentTimeMillis()
            runCatching { manager().notify(NOTIF_ID, buildNotification()) }
        }
    }

    private fun buildNotification(): Notification {
        val reports = RunManager.allReports(12)
        val summary = RunManager.summaryOf(reports)
        val cut = summary.indexOf('\n')
        val title = if (cut >= 0) summary.substring(0, cut) else summary
        val body = if (cut >= 0) summary.substring(cut + 1) else ""
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_RUNS, true)
        }
        val contentPending = PendingIntent.getActivity(
            this, 1, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, RunnerService::class.java).apply { action = ACTION_STOP_ALL }
        val stopPending = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull() ?: "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(contentPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Stop all", stopPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_desc) }
            manager().createNotificationChannel(ch)
        }
    }

    private fun manager() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "stratbot_runs"
        const val NOTIF_ID = 4317
        const val ACTION_STOP_ALL = "com.stratbot.app.STOP_ALL"
        const val EXTRA_KEEP = "keep"
        @Volatile
        private var lastBuild = 0L

        fun start(context: Context) {
            try {
                val i = Intent(context, RunnerService::class.java).putExtra(EXTRA_KEEP, true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (t: Throwable) {
                // FGS start can be denied by the OS in background — the report still works in-app
            }
        }

        fun stopAll(context: Context) {
            context.startService(Intent(context, RunnerService::class.java).apply { action = ACTION_STOP_ALL })
        }

        fun refresh(context: Context, force: Boolean) {
            if (!force && System.currentTimeMillis() - lastBuild < 10_000L) return
            lastBuild = System.currentTimeMillis()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val anyRunning = RunManager.sessionReports().any { it.status == com.stratbot.app.model.RunStatus.RUNNING }
            if (!anyRunning) {
                runCatching { nm.cancel(NOTIF_ID) }
                return
            }
            runCatching { nm.notify(NOTIF_ID, build(context)) }
        }

        private fun build(context: Context): Notification {
            val reports = RunManager.allReports(12)
            val summary = RunManager.summaryOf(reports)
            val cut = summary.indexOf('\n')
            val title = if (cut >= 0) summary.substring(0, cut) else summary
            val body = if (cut >= 0) summary.substring(cut + 1) else ""
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_RUNS, true)
            }
            val contentPending = PendingIntent.getActivity(
                context, 1, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentTitle(title)
                .setContentText(body.lineSequence().firstOrNull() ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(contentPending)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)
                .build()
        }
    }
}