package com.kalpande.nkpt.data

import com.kalpande.nkpt.domain.MarketHours
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Prices and candles.
 *  NSE - Upstox. Candles (v3 historical / intraday) need no login. Live quotes use the v2 quotes
 *        API when an Upstox access token is set (Settings); without one the price is the close of
 *        the latest 1-minute candle.
 *  US  - Yahoo Finance (chart + spark), no key.
 */
class MarketData(private val token: () -> String) {
    private val prevCloseCache = ConcurrentHashMap<String, Pair<String, Double>>()
    private val gate = Semaphore(6)

    // ─── quotes ──────────────────────────────────────────────────────────

    suspend fun quotes(m: Market, keys: Collection<String>): Map<String, Quote> {
        val ks = keys.distinct()
        if (ks.isEmpty()) return emptyMap()
        return if (m == Market.US) yahooQuotes(ks) else nseQuotes(ks)
    }

    private suspend fun yahooQuotes(symbols: List<String>): Map<String, Quote> = coroutineScope {
        symbols.chunked(20).map { chunk ->
            async {
                runCatching {
                    val url = "https://query1.finance.yahoo.com/v8/finance/spark?symbols=${enc(chunk.joinToString(","))}&range=1d&interval=1m"
                    val root = Http.json.parseToJsonElement(Http.get(url)).jsonObject
                    chunk.mapNotNull { s ->
                        val o = root[s]?.jsonObject ?: return@mapNotNull null
                        val ts = o["timestamp"]?.jsonArray ?: return@mapNotNull null
                        val cl = o["close"]?.jsonArray ?: return@mapNotNull null
                        var i = cl.size - 1
                        while (i >= 0 && (cl[i] as? JsonPrimitive)?.doubleOrNull == null) i--
                        if (i < 0) return@mapNotNull null
                        val prev = o["previousClose"]?.jsonPrimitive?.doubleOrNull ?: o["chartPreviousClose"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        s to Quote(s, cl[i].jsonPrimitive.doubleOrNull!!, prev, ts[i].jsonPrimitive.longOrNull ?: 0L)
                    }
                }.getOrDefault(emptyList())
            }
        }.awaitAll().flatten().toMap()
    }

    private suspend fun nseQuotes(keys: List<String>): Map<String, Quote> {
        val tk = token().trim()
        if (tk.isNotEmpty()) {
            val viaApi = runCatching { upstoxQuotes(keys, tk) }.getOrNull()
            if (!viaApi.isNullOrEmpty()) return viaApi
        }
        return coroutineScope {
            keys.map { k ->
                async {
                    gate.withPermit {
                        runCatching {
                            val bars = upstoxIntraday(k, "minutes", 1)
                            val last = bars.maxByOrNull { it.t } ?: return@runCatching null
                            k to Quote(k, last.c, prevClose(k, last.c), last.t + 60)
                        }.getOrNull()
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
    }

    private suspend fun upstoxQuotes(keys: List<String>, tk: String): Map<String, Quote> {
        val out = mutableMapOf<String, Quote>()
        for (chunk in keys.chunked(400)) {
            val url = "https://api.upstox.com/v2/market-quote/quotes?instrument_key=${enc(chunk.joinToString(","))}"
            val data = Http.json.parseToJsonElement(Http.get(url, mapOf("Authorization" to "Bearer $tk"))).jsonObject["data"]?.jsonObject ?: continue
            for ((_, v) in data) {
                val o = v.jsonObject
                val key = o["instrument_token"]?.jsonPrimitive?.content ?: continue
                val ltp = o["last_price"]?.jsonPrimitive?.doubleOrNull ?: continue
                val chg = o["net_change"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val t = o["last_trade_time"]?.jsonPrimitive?.content?.toLongOrNull()?.div(1000) ?: (System.currentTimeMillis() / 1000)
                out[key] = Quote(key, ltp, ltp - chg, t)
            }
        }
        return out
    }

    /** Previous session's close (cached per day) - for the day change when there is no token. */
    private suspend fun prevClose(key: String, fallback: Double): Double {
        val today = LocalDate.now().toString()
        prevCloseCache[key]?.let { if (it.first == today) return it.second }
        val d = runCatching {
            val to = LocalDate.now().minusDays(1)
            upstoxHistorical(key, "days", 1, to, to.minusDays(12)).maxByOrNull { it.t }?.c
        }.getOrNull() ?: return fallback
        prevCloseCache[key] = today to d
        return d
    }

    // ─── candles ─────────────────────────────────────────────────────────

    suspend fun candles(ins: Instrument, tf: Timeframe): List<Candle> =
        if (ins.market == Market.US) yahooCandles(ins.key, tf) else nseCandles(ins.key, tf)

    private suspend fun nseCandles(key: String, tf: Timeframe): List<Candle> = coroutineScope {
        val today = LocalDate.now(MarketHours.zone(Market.NSE))
        val y = today.minusDays(1)
        val parts = when (tf) {
            Timeframe.M1 -> listOf(async { upstoxHistorical(key, "minutes", 1, y, y.minusDays(4)).takeLastSession() }, async { upstoxIntraday(key, "minutes", 1) })
            Timeframe.M5 -> listOf(async { upstoxHistorical(key, "minutes", 5, y, y.minusDays(8)) }, async { upstoxIntraday(key, "minutes", 5) })
            Timeframe.M15 -> listOf(async { upstoxHistorical(key, "minutes", 15, y, y.minusDays(30)) }, async { upstoxIntraday(key, "minutes", 15) })
            Timeframe.H1 -> listOf(async { upstoxHistorical(key, "hours", 1, y, y.minusDays(90)) }, async { upstoxIntraday(key, "hours", 1) })
            Timeframe.D1 -> listOf(async { upstoxHistorical(key, "days", 1, today, today.minusDays(730)) }, async { listOfNotNull(todayBar(key)) })
            Timeframe.W1 -> listOf(async { upstoxHistorical(key, "weeks", 1, today, today.minusYears(10)) })
        }
        merge(parts.awaitAll().flatten())
    }

    private fun List<Candle>.takeLastSession(): List<Candle> {
        if (isEmpty()) return this
        val lastDay = MarketHours.localDate(Market.NSE, maxOf { it.t })
        return filter { MarketHours.localDate(Market.NSE, it.t) == lastDay }
    }

    private suspend fun todayBar(key: String): Candle? {
        val b = runCatching { upstoxIntraday(key, "minutes", 30) }.getOrDefault(emptyList())
        if (b.isEmpty()) return null
        // Upstox stamps daily candles at midnight IST - today's bar is built from the 30-minute bars so far
        val day = MarketHours.localDate(Market.NSE, b.first().t)
        val midnight = java.time.ZonedDateTime.of(day, java.time.LocalTime.MIDNIGHT, MarketHours.zone(Market.NSE)).toEpochSecond()
        return Candle(midnight, b.minBy { it.t }.o, b.maxOf { it.h }, b.minOf { it.l }, b.maxBy { it.t }.c, b.sumOf { it.v })
    }

    private fun merge(c: List<Candle>) = c.groupBy { it.t }.map { it.value.last() }.sortedBy { it.t }

    private suspend fun upstoxHistorical(key: String, unit: String, interval: Int, to: LocalDate, from: LocalDate): List<Candle> =
        gate.withPermit {
            parseUpstox(Http.get("https://api.upstox.com/v3/historical-candle/${enc(key)}/$unit/$interval/$to/$from"))
        }

    private suspend fun upstoxIntraday(key: String, unit: String, interval: Int): List<Candle> =
        parseUpstox(Http.get("https://api.upstox.com/v3/historical-candle/intraday/${enc(key)}/$unit/$interval"))

    private fun parseUpstox(body: String): List<Candle> {
        val arr = Http.json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("candles")?.jsonArray ?: return emptyList()
        return arr.mapNotNull { row ->
            val r = row.jsonArray
            runCatching {
                Candle(
                    OffsetDateTime.parse(r[0].jsonPrimitive.content).toEpochSecond(),
                    r[1].jsonPrimitive.doubleOrNull!!, r[2].jsonPrimitive.doubleOrNull!!, r[3].jsonPrimitive.doubleOrNull!!,
                    r[4].jsonPrimitive.doubleOrNull!!, r[5].jsonPrimitive.doubleOrNull ?: 0.0,
                )
            }.getOrNull()
        }.sortedBy { it.t }
    }

    private suspend fun yahooCandles(symbol: String, tf: Timeframe): List<Candle> {
        val (interval, range) = when (tf) {
            Timeframe.M1 -> "1m" to "5d"
            Timeframe.M5 -> "5m" to "1mo"
            Timeframe.M15 -> "15m" to "1mo"
            Timeframe.H1 -> "60m" to "6mo"
            Timeframe.D1 -> "1d" to "2y"
            Timeframe.W1 -> "1wk" to "10y"
        }
        return yahooChart(symbol, interval, range)
    }

    private suspend fun yahooChart(symbol: String, interval: String, range: String): List<Candle> {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/${enc(symbol)}?interval=$interval&range=$range&includePrePost=false"
        val res = Http.json.parseToJsonElement(Http.get(url)).jsonObject["chart"]?.jsonObject?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
            ?: return emptyList()
        val ts = res["timestamp"]?.jsonArray ?: return emptyList()
        val q = res["indicators"]?.jsonObject?.get("quote")?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        fun col(n: String) = q[n] as? JsonArray
        val o = col("open"); val h = col("high"); val l = col("low"); val c = col("close"); val v = col("volume")
        return ts.indices.mapNotNull { i ->
            val cc = (c?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            Candle(
                ts[i].jsonPrimitive.longOrNull ?: return@mapNotNull null,
                (o?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: cc,
                (h?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: cc,
                (l?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: cc,
                cc,
                (v?.getOrNull(i) as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            )
        }
    }

    /** 1-minute bars since [since] (epoch s) for the order engine; older gaps fall back to 5-minute bars. */
    suspend fun bars(m: Market, key: String, since: Long): List<Candle> {
        val now = System.currentTimeMillis() / 1000
        return if (m == Market.US) {
            if (now - since <= 6 * 86400) yahooChart(key, "1m", "7d") else yahooChart(key, "5m", "60d")
        } else coroutineScope {
            val today = LocalDate.now(MarketHours.zone(Market.NSE))
            val from = MarketHours.localDate(Market.NSE, since)
            val older: Deferred<List<Candle>>? = if (from.isBefore(today)) async {
                runCatching { upstoxHistorical(key, "minutes", 1, today.minusDays(1), maxOf(from, today.minusDays(28))) }.getOrDefault(emptyList())
            } else null
            val intraday = async { runCatching { upstoxIntraday(key, "minutes", 1) }.getOrDefault(emptyList()) }
            merge((older?.await() ?: emptyList()) + intraday.await())
        }.filter { it.t >= since - 60 }
    }

    /** Yahoo's name search, for US symbols missing from the daily list (e.g. listed today). */
    suspend fun yahooSearch(q: String): List<Instrument> = runCatching {
        val url = "https://query1.finance.yahoo.com/v1/finance/search?q=${enc(q)}&quotesCount=20&newsCount=0"
        val quotes = Http.json.parseToJsonElement(Http.get(url)).jsonObject["quotes"]?.jsonArray ?: return emptyList()
        quotes.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val s = o["symbol"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val ex = o["exchange"]?.jsonPrimitive?.content ?: ""
            val type = o["quoteType"]?.jsonPrimitive?.content ?: ""
            if ('.' in s || ex in setOf("NSI", "BSE")) return@mapNotNull null
            Instrument(Market.US, s, o["shortname"]?.jsonPrimitive?.content ?: o["longname"]?.jsonPrimitive?.content ?: s, s,
                "$ex · ${type.lowercase()}", tradable = type == "EQUITY" || type == "ETF")
        }
    }.getOrDefault(emptyList())

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
