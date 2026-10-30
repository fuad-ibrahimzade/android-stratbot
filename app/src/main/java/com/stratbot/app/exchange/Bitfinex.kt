package com.stratbot.app.exchange

import android.util.Log
import com.stratbot.app.data.Settings
import com.stratbot.app.model.Candle
import com.stratbot.app.model.Tf
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bitfinex v2 REST+WS.
 *  - public  : configurable     (default https://api-pub.bitfinex.com)
 *  - auth    : configurable     (default https://api.bitfinex.com)  HMAC-SHA384 over /api/v2/{path}{nonce}{body}
 *  - streams : ws /2  (candles + ticker channels)
 * Symbols: "BTCUSD" is normalised to "tBTCUSD" (raw spot pair).
 */
class Bitfinex(private val s: Settings) : Exchange {

    override val id = "bitfinex"
    override val displayName = "Bitfinex"

    private fun pub(): String = s.bitfinexPublicBase.ifBlank { "https://api-pub.bitfinex.com" }.trimEnd('/')
    private fun api(): String = s.bitfinexAuthBase.ifBlank { "https://api.bitfinex.com" }.trimEnd('/')

    override fun normalizeSymbol(sym: String): String {
        val t = sym.trim()
        return when {
            t.startsWith("t") || t.startsWith("f") -> t
            else -> "t$t"
        }
    }

    override fun canonicalSymbol(sym: String) = normalizeSymbol(sym).substring(1)

    private fun tfOf(tf: String) = when (tf) {
        "1d" -> "1D"
        "1w" -> "1W"
        "3m", "2h", "6h", "12h", "3d" -> "1h"   // not supported by Bitfinex → nearest
        else -> tf
    }

    override fun candles(symbol: String, tf: String, startMs: Long, endMs: Long, maxBars: Int): List<Candle> {
        val sym = normalizeSymbol(symbol)
        val ctf = tfOf(tf)
        val tfMs = Tf.toMs(tf)
        val out = ArrayList<Candle>(512)
        val end = if (endMs > 0) endMs else System.currentTimeMillis()

        if (startMs > 0) {
            var cursor = startMs
            while (out.size < maxBars && cursor < end) {
                val url = "${pub()}/v2/candles/trade:$ctf:$sym/hist?limit=1000&sort=1&start=$cursor&end=$end"
                val page = parseCandles(Http.get(url))
                if (page.isEmpty()) break
                out.addAll(page)
                if (page.size < 1000) break
                cursor = page.last().t + tfMs
            }
        } else {
            var cursor = end
            var guard = 0
            while (out.size < maxBars && guard++ < 40) {
                val url = "${pub()}/v2/candles/trade:$ctf:$sym/hist?limit=1000&sort=-1&end=$cursor"
                val page = parseCandles(Http.get(url))
                if (page.isEmpty()) break
                out.addAll(0, page)
                if (page.size < 1000) break
                cursor = page.first().t - 1
            }
        }
        var res = out.distinctBy { it.t }.sortedBy { it.t }
        if (res.size > maxBars) res = res.takeLast(maxBars)
        return res
    }

    private fun parseCandles(body: String): List<Candle> {
        if (body.isBlank() || body == "null") return emptyList()
        val a = JSONArray(body)
        val out = ArrayList<Candle>(a.length())
        for (i in 0 until a.length()) {
            val r = a.optJSONArray(i) ?: continue
            if (r.length() < 6) continue
            out.add(
                Candle(
                    t = r.optLong(0),
                    o = r.optDouble(1),          // [mts, open, close, high, low, volume]
                    h = r.optDouble(3),
                    l = r.optDouble(4),
                    c = r.optDouble(2),
                    v = r.optDouble(5),
                )
            )
        }
        return out
    }

    override fun quote(symbol: String): Quote {
        val sym = normalizeSymbol(symbol)
        val a = JSONArray(Http.get("${pub()}/v2/ticker/$sym"))
        val bid = a.optDouble(0)
        val ask = a.optDouble(2)
        val last = a.optDouble(6)
        return Quote(bid, ask, if (last > 0) last else (bid + ask) / 2)
    }

    // ---------- auth ----------

    private fun nonce(): String = System.nanoTime().toString()

    private fun hmac(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA384")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA384"))
        return mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun post(path: String, body: String): String {
        val key = ServicesHolder.key(id)
        val sec = ServicesHolder.secret(id)
        if (key.isEmpty() || sec.isEmpty()) throw HttpError(401, "Bitfinex API keys not configured")
        val n = nonce()
        val sig = hmac(sec, "/api/v2/$path$n$body")
        val headers = mapOf(
            "bfx-nonce" to n,
            "bfx-signature" to sig,
            "bfx-apikey" to key,
            "Content-Type" to "application/json",
            "Accept" to "application/json",
        )
        return Http.post("${api()}/v2/$path", body, "application/json", headers)
    }

    // ---------- trading ----------

    override fun placeOrder(symbol: String, side: String, type: String, qty: Double, price: Double): OrderResult {
        if (ServicesHolder.key(id).isEmpty())
            return OrderResult(false, status = "NO_KEYS", raw = "Bitfinex API key not configured")
        val sym = normalizeSymbol(symbol)
        val bfxType = if (type == "MARKET") "EXCHANGE MARKET" else "EXCHANGE LIMIT"
        val amount = if (side.equals("BUY", true)) qty else -qty
        val body = JSONObject()
            .put("type", bfxType)
            .put("symbol", sym)
            .put("amount", num(qty * if (side.equals("BUY", true)) 1 else -1))
            .put("price", if (type == "MARKET") "0" else num(price))
            .toString()

        val resp: String = try {
            post("auth/w/order/submit", body)
        } catch (e: Exception) {
            return OrderResult(false, status = "ERROR", raw = e.message ?: "request failed")
        }

        val order = findOrder(JSONArray(resp)) ?: return OrderResult(false, status = "ERROR", raw = resp.take(300))
        val orderId = order.optString(0)
        var status = order.optString(13, "ACTIVE")
        var avg = order.optDouble(17, 0.0)
        var filled = orderFilled(order, Math.abs(amount))
        var left = Math.abs(amount) - filled

        val deadline = System.currentTimeMillis() + 20_000
        while (left > 1e-12 && status != "CANCELED" && System.currentTimeMillis() < deadline) {
            val r = orderStatus(sym, orderId)
            if (r.status == "UNKNOWN") { Thread.sleep(1500); continue }
            filled = r.filledQty; avg = r.avgPrice; status = r.status
            left = Math.abs(amount) - filled
            if (status == "EXECUTED" || status == "CANCELED") break
            Thread.sleep(1200)
        }
        return OrderResult(
            ok = status == "EXECUTED" || status == "PARTIALLY FILLED" || filled > 0,
            orderId = orderId,
            avgPrice = if (avg > 0) avg else price,
            filledQty = filled,
            status = status,
            raw = resp.take(200),
        )
    }

    /** cumulative filled base amount of an order row */
    private fun orderFilled(o: JSONArray, orig: Double): Double {
        val left = o.optDouble(6, orig)
        val origAmt = o.optDouble(7, orig)
        if (left.isNaN() || origAmt.isNaN()) return Math.abs(orig) - Math.abs(left)
        val outfield = Math.abs(origAmt) - Math.abs(left)
        return if (outfield >= 0) outfield else Math.abs(orig)
    }

    override fun orderStatus(symbol: String, orderId: String): OrderResult {
        val sym = normalizeSymbol(symbol)
        val id = orderId
        return try {
            val body = JSONObject().put("id", JSONArray(listOf(id))).toString()
            var arr = JSONArray(post("auth/r/orders/$sym", body))
            var o: JSONArray? = findOrder(arr)
            if (o == null) {
                try {
                    arr = JSONArray(post("auth/r/orders/$sym/hist", body))
                    o = findOrder(arr)
                } catch (_: Exception) {
                }
            }
            if (o == null) return OrderResult(false, orderId, status = "UNKNOWN")
            val status = o.optString(13, if (o.optDouble(6, 1.0) == 0.0) "EXECUTED" else "ACTIVE")
            val avg = o.optDouble(17, 0.0)
            val filled = orderFilled(o, Math.abs(o.optDouble(7, o.optDouble(6, 1.0))))
            OrderResult(filled > 0 || status == "EXECUTED", orderId, avg, filled, status)
        } catch (e: Exception) {
            OrderResult(false, orderId, 0.0, 0.0, "UNKNOWN", e.message ?: "")
        }
    }

    /** find the first order array inside a notification payload */
    private fun findOrder(root: JSONArray): JSONArray? {
        for (i in 0 until root.length()) {
            val v = root.opt(i)
            if (v is JSONArray) {
                if (v.length() >= 18 && (v.opt(0) is Number)) return v
                val inner = findOrder(v)
                if (inner != null) return inner
            }
        }
        return null
    }

    override fun cancelOrder(symbol: String, orderId: String): Boolean {
        return try {
            val resp = post("auth/w/order/cancel", JSONObject().put("id", orderId).toString())
            val o = findOrder(JSONArray(resp))
            o == null || o.optString(13) != "ACTIVE"
        } catch (e: Exception) {
            Log.w(TAG, "cancel failed: ${e.message}")
            false
        }
    }

    // ---------- websocket ----------

    override fun openStream(symbol: String, tf: String, handlers: StreamHandlers): Stream {
        val sym = normalizeSymbol(symbol)
        val ctf = tfOf(tf)
        val tfMs = Tf.toMs(tf)
        val url = s.bitfinexWs.ifBlank { "wss://api-pub.bitfinex.com/ws/2" }
        var ws: WebSocket? = null
        var stopped = false
        val chanType = HashMap<Int, String>()
        var curCandle: Candle? = null

        val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        fun flushBefore(mts: Long) {
            val c = curCandle ?: return
            if (mts > c.t) {
                handlers.onBarClosed(c)
                curCandle = null
            }
        }

        val listener = object : WebSocketListener() {
            var subscribed = 0

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    if (text.startsWith("{")) {
                        val j = JSONObject(text)
                        when (j.optString("event")) {
                            "subscribed" -> j.optInt("chanId").let { id ->
                                chanType[id] = j.optString("channel")
                                subscribed++
                                handlers.onStatus(
                                    "ws subscribed: " +
                                        j.optString("channel") + " " + (j.optString("chan").ifEmpty { j.optString("symbol") })
                                )
                            }
                            "error" -> handlers.onError(j.optString("msg").ifEmpty { j.toString() })
                            "auth" -> Unit
                        }
                        if (j.optString("event") == "subscribed" && j.optString("channel") == "candles") {
                            curCandle = null
                        }
                        return
                    }
                    val arr = JSONArray(text)
                    if (arr.length() < 2) return
                    val cid = arr.optInt(0)
                    val data = arr.opt(1)
                    when (chanType[cid]) {
                        "ticker" -> if (data is JSONArray && data.length() >= 7) {
                            val bid = data.optDouble(0)
                            val ask = data.optDouble(2)
                            val last = data.optDouble(6)
                            handlers.onTick(bid, ask, last)
                        }
                        "candles" -> {
                            val rows = ArrayList<Candle>(4)
                            if (data is JSONArray && data.length() > 0 && data.opt(0) is JSONArray) {
                                for (i in 0 until data.length()) (data.opt(i) as? JSONArray)?.let { rows.add(row(it)) }
                            } else if (data is JSONArray && data.length() >= 6) {
                                rows.add(row(data))
                            }
                            // Bitfinex rows: single value = closed candle?? no — rows are [mts,o,c,h,l,v] updates
                            for (r in rows) {
                                if (r.t <= 0) continue
                                val c = curCandle
                                if (c == null || r.t > c.t) {
                                    if (c != null) handlers.onBarClosed(c)
                                    curCandle = r
                                } else if (r.t == c.t) {
                                    curCandle = r
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "ws parse: ${e.message}")
                }
            }

            private fun row(r: JSONArray) = Candle(
                t = r.optLong(0),
                o = r.optDouble(1),
                c = r.optDouble(2),
                h = r.optDouble(3),
                l = r.optDouble(4),
                v = r.optDouble(5),
            )

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.w(TAG, "ws failure: ${t.message} (${response?.code})")
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
                // subscriptions are sent after the socket opens
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    val w = ws ?: return@postDelayed
                    w.send(JSONObject().put("event", "subscribe").put("channel", "candles").put("key", "trade:$ctf:$sym").toString())
                    w.send(JSONObject().put("event", "subscribe").put("channel", "ticker").put("symbol", sym).toString())
                }, 700)
            }

            override fun close() {
                stopped = true
                ws?.close(1000, "bye")
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    companion object {
        private const val TAG = "Bitfinex"
    }
}