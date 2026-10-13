package com.kalpande.nkpt

import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.CloudSync
import com.kalpande.nkpt.data.GitHubStore
import com.kalpande.nkpt.data.Http
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.TradeSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class CloudSyncTest {
    private val s = TradeSettings(slippageBpsUs = 0.0)
    private val aapl = Instrument(Market.US, "AAPL", "Apple, Inc.", "AAPL")
    private fun et(d: Int, h: Int, m: Int) = ZonedDateTime.of(LocalDate.of(2026, 10, d), LocalTime.of(h, m), ZoneId.of("America/New_York")).toEpochSecond()

    private fun account(): Account {
        var a = Account(Market.US)
        a = Engine.place(a, OrderRequest(aapl, Side.BUY, 2, OrderType.MARKET, Product.HOLD, stopLoss = 240.0, strategy = "Pull, back", note = "say \"hi\""),
            Quote("AAPL", 250.0, 249.0, et(12, 10, 0)), et(12, 10, 0), s).account
        a = Engine.place(a, OrderRequest(aapl, Side.SELL, 2, OrderType.MARKET, Product.HOLD), Quote("AAPL", 260.0, 249.0, et(13, 11, 0)), et(13, 11, 0), s).account
        return Engine.equityPoint(a, et(13, 11, 1))
    }

    @Test fun csvFilesAreResearchReady() {
        val a = account()
        val trades = CloudSync.tradesCsv(a).trim().lines()
        assertEquals(2, trades.size)
        val head = trades[0].split(",")
        assertEquals("trade_id", head[0])
        assertTrue(trades[1].contains("\"Pull, back\""))
        assertTrue(trades[1].contains("LONG"))
        assertTrue(trades[1].contains("2026-10-12 10:00") && trades[1].contains("2026-10-13 11:00"))
        val orders = CloudSync.ordersCsv(a).trim().lines()
        assertEquals(3, orders.size)
        assertTrue(orders[1].contains("\"say 'hi'\""))
    }

    @Test fun dailyFileHoldsOnlyThatDay() {
        val a = account()
        val d13 = Http.json.parseToJsonElement(CloudSync.dailyJson(a, "2026-10-13")).jsonObject
        assertEquals("2026-10-13", d13["date"]!!.jsonPrimitive.content)
        assertEquals(1, d13["orders"]!!.jsonArray.size)
        assertEquals(1, d13["closedTrades"]!!.jsonArray.size)
        assertEquals(20.0, d13["realizedToday"]!!.jsonPrimitive.content.toDouble(), 0.05)
        val d12 = Http.json.parseToJsonElement(CloudSync.dailyJson(a, "2026-10-12")).jsonObject
        assertEquals(1, d12["orders"]!!.jsonArray.size)
        assertEquals(0, d12["closedTrades"]!!.jsonArray.size)
    }

    /** Real GitHub round trip into users/_selftest of the data repo: NKPT_GH_TOKEN=... NKPT_LIVE=1 */
    @Test fun liveCommitsSurviveARace() = runBlocking {
        val tok = System.getenv("NKPT_GH_TOKEN").orEmpty()
        assumeTrue(System.getenv("NKPT_LIVE") == "1" && tok.isNotBlank())
        val gh = GitHubStore({ tok }, { System.getenv("NKPT_GH_REPO") ?: "AdityaKalpandeEren/nkpt-data" })
        assertTrue(gh.login().isNotBlank())
        val stamp = System.currentTimeMillis().toString()
        // two writers at once: one has to rebuild on top of the other, both files must land
        listOf("a", "b").map { w -> async { gh.commit(mapOf("users/_selftest/$w.txt" to "$w $stamp\n", "users/_selftest/state.json" to "{\"w\":\"$w\"}\n"), "selftest $w") } }.awaitAll()
        assertEquals("a $stamp\n", gh.read("users/_selftest/a.txt"))
        assertEquals("b $stamp\n", gh.read("users/_selftest/b.txt"))
        assertNotNull(gh.read("users/_selftest/state.json"))
        assertEquals(null, gh.read("users/_selftest/missing-$stamp.txt"))
    }
}
