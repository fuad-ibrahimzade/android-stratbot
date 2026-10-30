package com.stratbot.app.exchange

import android.util.Log
import com.stratbot.app.data.Settings
import com.stratbot.app.model.Candle
import com.stratbot.app.model.Tf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Binance spot.
 *  - public market data: configurable base (defaults to data-api.binance.vision, an open mirror)
 *  - signed trading:     /api/v3/order on the trade base (api.binance.com by default)
 *  - streams:            combined stream over the configured wss host
 */
class Binance(private val s: Settings) : Exchange {

    override val id = "binance"
    override val displayName = "Binance"

    private fun marketBase(): String = when {
        s.testnet -> "https://testnet.binance.vision"
        else -> s.binanceMarketBase.ifBlank { "https://data-api.binance.vision" }
    }

    private fun tradeBase(): String = when {
        s.testnet -> "https://testnet.binance.vision"
        else -> s.binanceTradeBase.ifBlank { "https://api.binance.com" }
    }

    private fun wsBase(): String = when {
        s.testnet -> "wss://testnet.binance.vision"
        else -> s.binanceWs.ifBlank { "wss://data-stream.binance.vision" }
    }

    override fun normalizeSymbol(sym: String) = sym.trim().uppercase()
    override fun canonicalSymbol(sym: String) = normalizeSymbol(sym)

    private fun interval(tf: String) = tf   // canonical names match Binance

    override fun candles(symbol: String, tf: String, startMs: Long, endMs: Long, maxBars: Int): List<Candle> {
        val sym = normalizeSymbol(symbol)
        val iv = interval(tf)
        val out = ArrayList<Candle>(512)
        val end = if (endMs > 0) endMs else System.currentTimeMillis()

        if (startMs > 0) {
            var cursor = startMs
            while (out.size < maxBars && cursor < end) {
                val url = "${marketBase()}/api/v3/klines?symbol=$sym&interval=$iv&startTime=$cursor&endTime=$end&limit=1000"
                val page = parseKlines(Http.get(url))
                if (page.isEmpty()) break
                out.addAll(page)
                if (page.size < 1000) break
                cursor = page.last().t + 1
            }
        } else {
            var cursor = end
            val need = maxBars
            while (out.size < need) {
                val url = "${marketBase()}/api/v3/klines?symbol=$sym&interval=$iv&endTime=$cursor&limit=1000"
                val page = parseKlines(Http.get(url))
                if (page.isEmpty()) break
                out.addAll(0, page)
                if (page.size < 1000) break
                cursor = page.first().t - 1
            }
        }
        // keep the newest maxBars, ascending, drop a possibly-incomplete last candle
        var res = out.distinctBy { it.t }.sortedBy { it.t }
        if (res.size > maxBars) res = res.takeLast(maxBars)
        return res
    }

    private fun parseKlines(body: String): List<Candle> {
        val a = JSONArray(body)
        val out = ArrayList<Candle>(a.length())
        for (i in 0 until a.length()) {
            val k = a.optJSONArray(i) ?: continue
            if (k.length() < 7) continue
            out.add(
                Candle(
                    t = k.optLong(0),
                    o = k.optString(1).toDoubleOrNull() ?: 0.0,
                    h = k.optString(2).toDoubleOrNull() ?: 0.0,
                    l = k.optString(3).toDoubleOrNull() ?: 0.0,
                    c = k.optString(4).toDoubleOrNull() ?: 0.0,
                    v = k.optString(5).toDoubleOrNull() ?: 0.0,
                )
            )
        }
        return out
    }

    override fun quote(symbol: String): Quote {
        val sym = normalizeSymbol(symbol)
        val b = JSONObject(Http.get("${marketBase()}/api/v3/ticker/bookTicker?symbol=$sym"))
        val bid = b.optString("bidPrice").toDoubleOrNull() ?: 0.0
        val ask = b.optString("askPrice").toDoubleOrNull() ?: 0.0
        val last = try {
            JSONObject(Http.get("${marketBase()}/api/v3/ticker/price?symbol=$sym")).optString("price").toDoubleOrNull() ?: (bid + ask) / 2
        } catch (e: Exception) {
            (bid + ask) / 2
        }
        return Quote(bid, ask, last)
    }

    override fun placeOrder(symbol: String, side: String, type: String, qty: Double, price: Double): OrderResult {
        val sym = normalizeSymbol(symbol)
        if (ServicesHolder.key(id).isEmpty())
            return OrderResult(false, status = "NO_KEYS", raw = "Binance API key not configured")

        val params = LinkedHashMap<String, String>()
        params["symbol"] = sym
        params["side"] = side
        params["type"] = type
        params["recvWindow"] = "5000"
        if (type == "MARKET") {
            params["quantity"] = num(qty)
        } else {
            params["quantity"] = num(qty)
            params["price"] = num(price)
            params["timeInForce"] = "GTC"
        }
        val resp: String = try {
            signedPost("/api/v3/order", params)
        } catch (e: Exception) {
            return OrderResult(false, status = "ERROR", raw = e.message ?: "request failed")
        }
        val j = JSONObject(resp)
        val orderId = j.opt("orderId")?.toString() ?: ""
        var filled = j.optString("executedQty").toDoubleOrNull() ?: 0.0
        var avg = j.optString("cummulativeQuoteQty").toDoubleOrNull()?.let { q -> if (filled > 0) q / filled else 0.0 } ?: 0.0
        var status = j.optString("status", "NEW")

        val deadline = System.currentTimeMillis() + 20_000
        while (orderId.isNotEmpty() && filled <= 0.0 && status == "NEW" && System.currentTimeMillis() < deadline) {
            val r = orderStatus(sym, orderId)
            if (r.status == "UNKNOWN") { Thread.sleep(1500); continue }
            filled = r.filledQty; avg = r.avgPrice; status = r.status
            if (status == "FILLED" || status == "CANCELED" || status == "REJECTED") break
            Thread.sleep(1200)
        }
        val ok = status == "FILLED" || status == "PARTIALLY_FILLED" || filled > 0
        return OrderResult(ok, orderId, if (filled > 0 && avg > 0) avg else price, filled, status, resp)
    }

    override fun orderStatus(symbol: String, orderId: String): OrderResult {
        if (ServicesHolder.key(id).isEmpty()) return OrderResult(false, orderId, status = "NO_KEYS")
        return try {
            val params = linkedMapOf("symbol" to normalizeSymbol(symbol), "orderId" to orderId, "recvWindow" to "5000")
            val j = JSONObject(signedGet("/api/v3/order", params))
            val filled = j.optString("executedQty").toDoubleOrNull() ?: 0.0
            val quote = j.optString("cummulativeQuoteQty").toDoubleOrNull() ?: 0.0
            val status = j.optString("status", "NEW")
            OrderResult(filled > 0 || status == "FILLED", orderId, if (filled > 0 && quote > 0) quote / filled else 0.0, filled, status)
        } catch (e: Exception) {
            OrderResult(false, orderId, 0.0, 0.0, "UNKNOWN", e.message ?: "")
        }
    }

    override fun cancelOrder(symbol: String, orderId: String): Boolean {
        return try {
            val params = linkedMapOf("symbol" to normalizeSymbol(symbol), "orderId" to orderId, "recvWindow" to "5000")
            val j = JSONObject(signedDelete("/api/v3/order", params))
            j.optString("status") == "CANCELED" || j.has("orderId")
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed: ${e.message}")
            false
        }
    }

    // ---------- signing ----------

    private fun qs(params: Map<String, String>): String =
        params.entries.joinToString("&") { "${it.key}=${it.value}" }

    private fun sign(qs: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(ServicesHolder.secret(id).toByteArray(), "HmacSHA256"))
        return mac.doFinal(qs.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun apiKey(): Map<String, String> = mapOf("X-MBX-APIKEY" to ServicesHolder.key(id))

    private fun signedGet(path: String, params: Map<String, String>): String {
        val q = qs(params + ("timestamp" to System.currentTimeMillis().toString()))
        val full = "$q&signature=${sign(q)}"
        return Http.get("${tradeBase()}$path?$full", apiKey())
    }

    private fun signedPost(path: String, params: Map<String, String>): String {
        val q = qs(params + ("timestamp" to System.currentTimeMillis().toString()))
        val full = "$q&signature=${sign(q)}"
        return Http.post("${tradeBase()}$path", full, "application/x-www-form-urlencoded", apiKey())
    }

    private fun signedDelete(path: String, params: Map<String, String>): String {
        val q = qs(params + ("timestamp" to System.currentTimeMillis().toString()))
        val full = "$q&signature=${sign(q)}"
        val req = Request.Builder().url("${tradeBase()}$path?$full").delete().header("X-MBX-APIKEY", ServicesHolder.key(id)).build()
        Http.client.newCall(req).execute().use { r ->
            val body = r.body?.string() ?: ""
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code}: ${body.take(300)}")
            return body
        }
    }

    // ---------- websocket ----------

    override fun openStream(symbol: String, tf: String, handlers: StreamHandlers): Stream {
        val sym = normalizeSymbol(symbol).lowercase()
        val streams = "$sym@bookTicker/$sym@kline_$tf"
        val url = "${wsBase()}/stream?streams=$streams"
        var ws: WebSocket? = null
        var stopped = false
        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val listener = object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    val data = root.optJSONObject("data") ?: return
                    val stream = root.optString("stream")
                    if (stream.contains("bookTicker")) {
                        val bid = data.optString("b").toDoubleOrNull() ?: 0.0
                        val ask = data.optString("a").toDoubleOrNull() ?: 0.0
                        if (bid > 0 || ask > 0) handlers.onTick(bid, ask, (bid + ask) / 2)
                    } else if (stream.contains("kline")) {
                        val k = data.optJSONObject("k") ?: return
                        if (k.optBoolean("x", false)) {
                            handlers.onBarClosed(
                                Candle(
                                    t = k.optLong("t"), o = k.optDouble("o"), h = k.optDouble("h"),
                                    l = k.optDouble("l"), c = k.optDouble("c"), v = k.optDouble("v"),
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ws parse: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.w(TAG, "ws failure: ${t.message} (code=${response?.code})")
                if (!stopped) handlers.onError(t.message ?: "stream failed")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped) handlers.onError("stream closed: $reason")
            }
        }

        return object : Stream {
            override fun start() {
                handlers.onStatus("connecting $url")
                ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
            }

            override fun close() {
                stopped = true
                ws?.close(1000, "bye")
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    companion object {
        private const val TAG = "Binance"
    }
}
