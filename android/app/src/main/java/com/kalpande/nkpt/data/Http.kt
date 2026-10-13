package com.kalpande.nkpt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object Http {
    val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true; encodeDefaults = true }

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    class HttpError(val code: Int, msg: String) : IOException(msg)

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) NayanKalpandePT/1.0")
            .header("Accept", "application/json, text/plain, */*")
        headers.forEach { (k, v) -> b.header(k, v) }
        client.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code} for ${url.substringBefore('?')}")
            body
        }
    }

    /** Any method with an optional JSON body; returns (status code, body) without throwing on 4xx/5xx. */
    suspend fun send(method: String, url: String, body: String? = null, headers: Map<String, String> = emptyMap()): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val rb = body?.toRequestBody("application/json; charset=utf-8".toMediaType())
            val b = Request.Builder().url(url).method(method, rb ?: if (method == "GET" || method == "DELETE") null else ByteArray(0).toRequestBody(null))
                .header("User-Agent", "NayanKalpandePT/1.3")
            headers.forEach { (k, v) -> b.header(k, v) }
            client.newCall(b.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()) }
        }

    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw HttpError(r.code, "HTTP ${r.code} for $url")
            r.body?.bytes() ?: ByteArray(0)
        }
    }
}
