package com.kalpande.nkpt

import com.kalpande.nkpt.data.InstrumentRepository
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.MarketData
import com.kalpande.nkpt.data.Timeframe
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Hits the real Upstox / Yahoo / NASDAQ Trader endpoints. Runs only with NKPT_LIVE=1. */
class LiveDataTest {
    private fun live() = assumeTrue(System.getenv("NKPT_LIVE") == "1")

    @Test fun symbolListsAndPagedSearch() = runBlocking {
        live()
        val dir: File = Files.createTempDirectory("nkpt").toFile()
        val repo = InstrumentRepository(dir)
        val nse = repo.ensure(Market.NSE)
        val us = repo.ensure(Market.US)
        println("NSE symbols ${nse.size} (indices ${nse.count { !it.tradable }}), US symbols ${us.size}")
        assertTrue(nse.size > 2000 && us.size > 8000)
        for ((m, q) in listOf(Market.NSE to "cupid", Market.NSE to "reliance", Market.US to "apple", Market.US to "NVDA", Market.NSE to "")) {
            val p0 = repo.search(m, q, 0, 30)
            val p1 = repo.search(m, q, 1, 30)
            println("search $m '$q': total ${p0.total}, page0 ${p0.items.take(4).map { it.symbol }}, page1 ${p1.items.take(2).map { it.symbol }}, more=${p0.hasMore}")
        }
        assertTrue(repo.search(Market.NSE, "CUPID", 0).items.first().symbol == "CUPID")
        assertTrue(repo.search(Market.US, "AAPL", 0).items.first().symbol == "AAPL")
        // cached copy is reused without a download
        assertTrue(InstrumentRepository(dir).ensure(Market.NSE).size == nse.size)
    }

    @Test fun candlesBarsAndQuotes() = runBlocking {
        live()
        val repo = InstrumentRepository(Files.createTempDirectory("nkpt").toFile())
        val rel = repo.ensure(Market.NSE).first { it.symbol == "RELIANCE" }
        val aapl = repo.ensure(Market.US).first { it.symbol == "AAPL" }
        val data = MarketData { "" }
        for (ins in listOf(rel, aapl)) for (tf in Timeframe.entries) {
            val c = data.candles(ins, tf)
            println("${ins.symbol} ${tf.label}: ${c.size} candles, last ${c.lastOrNull()}")
            assertTrue("${ins.symbol} ${tf.label} empty", c.isNotEmpty())
        }
        val now = System.currentTimeMillis() / 1000
        println("RELIANCE 1m bars since 2 days: ${data.bars(Market.NSE, rel.key, now - 2 * 86400).size}")
        println("AAPL 1m bars since 2 days: ${data.bars(Market.US, "AAPL", now - 2 * 86400).size}")
        val qn = data.quotes(Market.NSE, listOf(rel.key, repo.search(Market.NSE, "TCS", 0).items.first().key))
        val qu = data.quotes(Market.US, listOf("AAPL", "MSFT", "^GSPC"))
        println("NSE quotes (no token): $qn")
        println("US quotes: $qu")
        assertTrue(qn.size == 2 && qu.size == 3)
    }
}
