package com.stratbot.app.exchange

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class HttpError(val code: Int, message: String) : IOException(message)

object Http {
    val jsonType = "application/json; charset=utf-8".toMediaType()
    val formType = "application/x-www-form-urlencoded".toMediaType()

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /** blocking GET; throws [HttpError] on non-2xx */
    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build())
    }

    fun post(url: String, body: String, contentType: String, headers: Map<String, String> = emptyMap()): String {
        val b = Request.Builder().url(url).post(body.toRequestBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        return execute(b.build())
    }

    private fun execute(req: Request): String {
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw HttpError(resp.code, "HTTP ${resp.code}: ${body.take(300)}")
            return body
        }
    }
}

/** number formatting that avoids scientific notation for API query strings */
fun num(d: Double): String {
    if (d.isNaN() || d.isInfinite()) return "0"
    if (d == Math.floor(d) && Math.abs(d) < 1e15) return d.toLong().toString()
    val s = d.toBigDecimal().toPlainString()
    return s.trimEnd('0').trimEnd('.').ifEmpty { "0" }
}