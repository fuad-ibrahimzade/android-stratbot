package com.stratbot.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.stratbot.app.model.Metrics
import com.stratbot.app.model.Report
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

    fun dirFor(runId: String) = File(root, runId).apply { mkdirs() }

    fun write(report: Report) {
        runCatching {
            val d = dirFor(report.runId)
            File(d, "report.json").writeText(report.toJson().toString())
        }
    }

    /** newest first */
    fun listReports(limit: Int = 100): List<Report> {
        val dirs = root.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { d ->
            val f = File(d, "report.json")
            if (!f.exists()) return@mapNotNull null
            runCatching { Report.fromJson(JSONObject(f.readText())) }.getOrNull()
        }.sortedByDescending { it.startedAt }.take(limit)
    }

    fun read(runId: String): Report? = runCatching {
        val f = File(File(root, runId), "report.json")
        if (!f.exists()) null else Report.fromJson(JSONObject(f.readText()))
    }.getOrNull()

    fun delete(runId: String) {
        File(root, runId).deleteRecursively()
    }
}