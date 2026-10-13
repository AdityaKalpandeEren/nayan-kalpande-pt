package com.kalpande.nkpt.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import java.io.File
import java.util.zip.GZIPInputStream

data class SearchPage(val items: List<Instrument>, val total: Int, val page: Int, val pageSize: Int) {
    val hasMore: Boolean get() = (page + 1) * pageSize < total
}

/**
 * Every listed symbol of both markets, refreshed once a day and kept on the phone:
 *  NSE - Upstox's public instrument master (all NSE equities + NSE indices, chart-only)
 *  US  - NASDAQ Trader symbol directory (NASDAQ + NYSE / NYSE American / Arca / Cboe listings,
 *        test issues and preferreds removed) + the main US indices, chart-only
 * [search] ranks by ticker / name match and serves results a page at a time.
 */
class InstrumentRepository(private val dir: File) {
    private val lists = mutableMapOf<Market, List<Instrument>>()
    private val lock = Mutex()
    private var lastQuery: Pair<Market, String>? = null
    private var lastRanked: List<Instrument> = emptyList()

    @Serializable
    private data class Cached(val day: String, val items: List<Instrument>)

    @Serializable
    private data class UpstoxRow(
        val segment: String = "",
        val name: String = "",
        @SerialName("instrument_type") val type: String = "",
        @SerialName("instrument_key") val key: String = "",
        @SerialName("trading_symbol") val symbol: String = "",
    )

    fun count(m: Market) = lists[m]?.size ?: 0

    suspend fun ensure(m: Market, force: Boolean = false): List<Instrument> = lock.withLock {
        val today = java.time.LocalDate.now().toString()
        val f = File(dir, "instruments_$m.json")
        if (!force) {
            lists[m]?.let { if (it.isNotEmpty()) return it }
            runCatching { Http.json.decodeFromString<Cached>(f.readText()) }.getOrNull()?.let { c ->
                lists[m] = c.items
                if (c.day == today) return c.items
            }
        }
        val fresh = runCatching { if (m == Market.NSE) downloadNse() else downloadUs() }.getOrNull()
        if (!fresh.isNullOrEmpty()) {
            lists[m] = fresh
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                val tmp = File(dir, "instruments_$m.json.tmp")
                tmp.writeText(Http.json.encodeToString(Cached.serializer(), Cached(today, fresh)))
                tmp.renameTo(f)
            }
            if (lastQuery?.first == m) lastQuery = null
        }
        lists[m].orEmpty()
    }

    @OptIn(ExperimentalSerializationApi::class)
    private suspend fun downloadNse(): List<Instrument> = withContext(Dispatchers.IO) {
        val gz = Http.bytes("https://assets.upstox.com/market-quote/instruments/exchange/NSE.json.gz")
        GZIPInputStream(gz.inputStream()).use { input ->
            Http.json.decodeToSequence<UpstoxRow>(input, DecodeSequenceMode.ARRAY_WRAPPED)
                .filter { it.key.isNotEmpty() && (it.segment == "NSE_INDEX" || (it.segment == "NSE_EQ" && it.type in NSE_TYPES.keys)) }
                .map {
                    val index = it.segment == "NSE_INDEX"
                    Instrument(Market.NSE, if (index) it.name else it.symbol, it.name.ifBlank { it.symbol }, it.key,
                        if (index) "NSE index" else NSE_TYPES.getValue(it.type), tradable = !index)
                }
                .toList()
                .distinctBy { it.key }
                .sortedBy { it.symbol }
        }
    }

    private suspend fun downloadUs(): List<Instrument> {
        val out = mutableListOf<Instrument>()
        val nasdaq = Http.get("https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt")
        nasdaq.lineSequence().drop(1).forEach { line ->
            val c = line.split('|')
            if (c.size >= 8 && c[3] == "N" && !line.startsWith("File Creation")) {
                addUs(out, c[0], c[1], if (c[6] == "Y") "NASDAQ · ETF" else "NASDAQ")
            }
        }
        val other = Http.get("https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt")
        val ex = mapOf("A" to "NYSE American", "N" to "NYSE", "P" to "NYSE Arca", "Z" to "Cboe", "V" to "IEX")
        other.lineSequence().drop(1).forEach { line ->
            val c = line.split('|')
            if (c.size >= 8 && c[6] == "N" && !line.startsWith("File Creation")) {
                addUs(out, c[0], c[1], (ex[c[2]] ?: c[2]) + if (c[4] == "Y") " · ETF" else "")
            }
        }
        listOf("^GSPC" to "S&P 500", "^IXIC" to "NASDAQ Composite", "^DJI" to "Dow Jones Industrial Average",
            "^RUT" to "Russell 2000", "^VIX" to "CBOE Volatility Index").forEach { (s, n) ->
            out += Instrument(Market.US, s, n, s, "Index", tradable = false)
        }
        return out.distinctBy { it.key }.sortedBy { it.symbol }
    }

    private fun addUs(out: MutableList<Instrument>, symbol: String, name: String, exchange: String) {
        if (symbol.isBlank() || '$' in symbol || symbol.contains(' ')) return
        val yahoo = symbol.replace('.', '-')
        val clean = name.substringBefore(" - ").replace(" Common Stock", "").replace(" Class A Common Stock", " Class A").trim()
        out += Instrument(Market.US, symbol, clean, yahoo, exchange)
    }

    fun byKey(m: Market, key: String): Instrument? = lists[m]?.find { it.key == key }

    /** Ranked matches for [query] (ticker or company name), [pageSize] at a time. Blank = browse A-Z. */
    fun search(m: Market, query: String, page: Int, pageSize: Int = 30): SearchPage {
        val q = query.trim().uppercase()
        val ranked = if (lastQuery == m to q) lastRanked else rank(lists[m].orEmpty(), q).also {
            lastQuery = m to q; lastRanked = it
        }
        val from = (page * pageSize).coerceAtMost(ranked.size)
        return SearchPage(ranked.subList(from, (from + pageSize).coerceAtMost(ranked.size)), ranked.size, page, pageSize)
    }

    companion object {
        /** NSE_EQ instrument types kept (shares and units); bonds, NCDs, T-bills and gold bonds are left out. */
        val NSE_TYPES = mapOf(
            "EQ" to "NSE", "BE" to "NSE · T2T", "BZ" to "NSE · T2T", "SM" to "NSE · SME", "ST" to "NSE · SME", "IV" to "NSE · InvIT/REIT",
        )

        fun rank(all: List<Instrument>, q: String): List<Instrument> {
            if (q.isEmpty()) return all.sortedWith(compareBy({ !it.tradable }, { it.symbol }))
            return all.mapNotNull { i ->
                val sym = i.symbol.uppercase()
                val name = i.name.uppercase()
                val score = when {
                    sym == q -> 0
                    sym.startsWith(q) -> 1
                    name.startsWith(q) -> 2
                    name.contains(" $q") -> 3
                    sym.contains(q) -> 4
                    name.contains(q) -> 5
                    else -> return@mapNotNull null
                }
                Triple(score, i, sym.length)
            }.sortedWith(compareBy({ it.first }, { !it.second.tradable }, { it.third }, { it.second.symbol })).map { it.second }
        }
    }
}
