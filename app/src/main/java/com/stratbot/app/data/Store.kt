package com.stratbot.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.stratbot.app.model.Metrics
import com.stratbot.app.model.Report
import com.stratbot.app.model.RunStatus
import com.stratbot.app.model.Strategy
import org.json.JSONObject
import java.io.File

/** Non-secret app settings (host overrides, polling, defaults). */
class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("stratbot.prefs", Context.MODE_PRIVATE)

    var binanceMarketBase: String
        get() = p.getString("bn_market", "https://data-api.binance.vision")!!
        set(v) = p.edit().putString("bn_market", v.trim().trimEnd('/')).apply()

    var binanceTradeBase: String
        get() = p.getString("bn_trade", "https://api.binance.com")!!
        set(v) = p.edit().putString("bn_trade", v.trim().trimEnd('/')).apply()

    var binanceWs: String
        get() = p.getString("bn_ws", "wss://data-stream.binance.vision")!!
        set(v) = p.edit().putString("bn_ws", v.trim().trimEnd('/')).apply()

    var testnet: Boolean
        get() = p.getBoolean("testnet", false)
        set(v) { p.edit().putBoolean("testnet", v).apply() }

    var bitfinexPublicBase: String
        get() = p.getString("bf_public", "https://api-pub.bitfinex.com")!!
        set(v) = p.edit().putString("bf_public", v.trim().trimEnd('/')).apply()

    var bitfinexAuthBase: String
        get() = p.getString("bf_auth", "https://api.bitfinex.com")!!
        set(v) = p.edit().putString("bf_auth", v.trim().trimEnd('/')).apply()

    var bitfinexWs: String
        get() = p.getString("bf_ws", "wss://api-pub.bitfinex.com/ws/2")!!
        set(v) = p.edit().putString("bf_ws", v.trim().trimEnd('/')).apply()

    /** fall back to REST polling when the websocket cannot connect */
    var restFallback: Boolean
        get() = p.getBoolean("rest_fallback", true)
        set(v) { p.edit().putBoolean("rest_fallback", v).apply() }

    var pollIntervalMs: Long
        get() = p.getLong("poll_ms", 4000L)
        set(v) = p.edit().putLong("poll_ms", v.coerceAtLeast(1000L)).apply()

    var warmupBars: Int
        get() = p.getInt("warmup", 300)
        set(v) = p.edit().putInt("warmup", v.coerceIn(50, 1500)).apply()

    var snapshotIntervalMs: Long
        get() = p.getLong("snap_ms", 15_000L)
        set(v) = p.edit().putLong("snap_ms", v).apply()

    var startCash: Double
        get() = java.lang.Double.longBitsToDouble(p.getLong("cash", java.lang.Double.doubleToRawLongBits(10000.0)))
        set(v) = p.edit().putLong("cash", java.lang.Double.doubleToRawLongBits(v)).apply()

}

/** Encrypted API credentials, one pair per exchange. */
class Keys(ctx: Context) {
    private val p: SharedPreferences = try {
        val mk = MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            ctx, "stratbot.keys", mk,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        Log.w("StratBot", "secure keystore unavailable, using plain prefs", t)
        ctx.getSharedPreferences("stratbot.keys.plain", Context.MODE_PRIVATE)
    }

    fun key(exchange: String): String = p.getString("key_$exchange", "") ?: ""
    fun secret(exchange: String): String = p.getString("secret_$exchange", "") ?: ""
    fun has(exchange: String): Boolean = key(exchange).isNotBlank() && secret(exchange).isNotBlank()

    fun save(exchange: String, key: String, secret: String) {
        p.edit().putString("key_$exchange", key.trim()).putString("secret_$exchange", secret.trim()).apply()
    }
}

/** Strategies as JSON files: filesDir/strategies/<id>.json */
class StrategyStore(private val ctx: Context) {
    private val dir: File get() = File(ctx.filesDir, "strategies").apply { mkdirs() }

    fun list(): List<Strategy> =
        dir.listFiles { f -> f.extension == "json" }
            ?.mapNotNull { f ->
                runCatching { Strategy.fromJson(JSONObject(f.readText())) }.getOrNull()
            }?.sortedBy { it.name.lowercase() } ?: emptyList()

    fun get(id: String): Strategy? =
        runCatching { Strategy.fromJson(JSONObject(File(dir, "$id.json").readText())) }.getOrNull()

    fun save(s: Strategy) {
        File(dir, "${s.id}.json").writeText(s.toJson().toString())
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
    }

    /** First launch: copy the bundled example strategies. */
    fun seedIfEmpty() {
        if (dir.listFiles()?.isNotEmpty() == true) return
        val names = listOf("sma_crossover.js", "rsi_reversion.js")
        for (n in names) {
            val code = runCatching { ctx.assets.open("strategies/$n").bufferedReader().readText() }.getOrNull() ?: continue
            val displayName = n.removeSuffix(".js").replace('_', ' ').replaceFirstChar { it.uppercase() }
            val symbol = "BTCUSDT"
            val params = when (n) {
                "sma_crossover.js" -> """{"fast":20,"slow":60,"size":0.95}"""
                else -> """{"rsiPeriod":14,"oversold":30,"overbought":55,"stopPct":3,"takePct":5,"size":0.9}"""
            }
            save(
                Strategy(
                    name = displayName, code = code, symbol = symbol, timeframe = "5m",
                    exchange = "binance", paramsJson = params,
                )
            )
        }
    }
}

/** Run snapshots under filesDir/runs/<runId>/report.json  */
class RunStore(private val ctx: Context) {
    private val root: File get() = File(ctx.filesDir, "runs").apply { mkdirs() }
    private val lock = Any()
    private val statusRe = Regex("\"status\"\\s*:\\s*\"(\\w+)\"")

    fun dirFor(runId: String) = File(root, runId).apply { mkdirs() }

    /**
     * Written from the engine thread, the maintenance timer and the UI, so it is
     * serialised and staged through a temp file — a partially written report.json is
     * unreadable and would make the run vanish from the list.
     *
     * Failures are logged rather than swallowed: silently losing the terminal snapshot
     * is what used to leave a finished backtest on disk as RUNNING forever.
     */
    fun write(report: Report) {
        // serialised outside the lock: json is a pure function of the snapshot
        val json = try {
            report.toJson().toString()
        } catch (t: Throwable) {
            Log.e("StratBot", "report serialisation failed for ${report.runId}: ${t.message}")
            return
        }
        try {
            synchronized(lock) {
                val d = dirFor(report.runId)
                val target = File(d, "report.json")
                // a finished run must never be overwritten by a stale RUNNING snapshot
                val onDisk = readStatus(target)
                if (report.status == RunStatus.RUNNING &&
                    (onDisk == RunStatus.DONE || onDisk == RunStatus.STOPPED || onDisk == RunStatus.ERROR)
                ) return
                val tmp = File(d, "report.json.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(target)) {
                    target.writeText(json)
                    tmp.delete()
                }
            }
        } catch (t: Throwable) {
            Log.e("StratBot", "report write failed for ${report.runId}: ${t.message}")
        }
    }

    /** status of an already stored report, without parsing the whole (large) file */
    private fun readStatus(f: File): RunStatus? {
        return try {
            if (!f.exists()) return null
            val buf = ByteArray(64 * 1024)
            val n = f.inputStream().use { it.read(buf) }
            val token = if (n > 0) statusRe.find(String(buf, 0, n))?.groupValues?.get(1) else null
            token?.let { runCatching { RunStatus.valueOf(it) }.getOrNull() }
        } catch (t: Throwable) {
            null
        }
    }

    /** newest first — parses at most [limit] files, newest on disk first */
    fun listReports(limit: Int = 100): List<Report> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val f = File(d, "report.json")
            if (f.exists()) f else null
        }.sortedByDescending { it.lastModified() }
            .asSequence()
            .mapNotNull { f -> runCatching { Report.fromJson(JSONObject(f.readText())) }.getOrNull() }
            .take(limit)
            .sortedByDescending { it.startedAt }
            .toList()
    }

    fun read(runId: String): Report? = runCatching {
        val f = File(File(root, runId), "report.json")
        if (!f.exists()) null else Report.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    fun delete(runId: String) {
        File(root, runId).deleteRecursively()
    }
}