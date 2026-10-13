package com.kalpande.nkpt

import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.BackupFile
import com.kalpande.nkpt.data.EquityPoint
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.MarketHours
import com.kalpande.nkpt.domain.TradeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class BackupTest {
    private val s = TradeSettings(slippageBpsNse = 0.0)
    private val rel = Instrument(Market.NSE, "RELIANCE", "Reliance Industries", "NSE_EQ|INE002A01018")

    @Test fun equityHistoryIsCompactedNeverDropped() {
        val start = ZonedDateTime.of(LocalDate.of(2026, 8, 1), LocalTime.of(9, 15), ZoneId.of("Asia/Kolkata")).toEpochSecond()
        val now = start + 60 * 86400
        val pts = (0 until 60 * 96).map { i -> EquityPoint(start + i * 900L, 200_000.0 + i) }
        val c = Engine.compactEquity(pts, Market.NSE, now)
        assertEquals(pts.first(), c.first())                                  // the very first point stays
        assertEquals(pts.last(), c.last())
        val cut = now - 30 * 86400
        assertEquals(pts.count { it.t >= cut }, c.count { it.t >= cut })       // last 30 days in full detail
        val oldDays = pts.filter { it.t < cut }.map { MarketHours.localDate(Market.NSE, it.t) }.toSet()
        assertTrue(c.count { it.t < cut } <= oldDays.size + 1)                // one point per older day
        assertEquals(oldDays, c.filter { it.t < cut }.map { MarketHours.localDate(Market.NSE, it.t) }.toSet())
        assertTrue(c.size < pts.size)
        assertEquals(c, Engine.compactEquity(c, Market.NSE, now))             // stable when run again
    }

    @Test fun backupRoundTripKeepsEverything() {
        val t = ZonedDateTime.of(LocalDate.of(2026, 10, 12), LocalTime.of(10, 0), ZoneId.of("Asia/Kolkata")).toEpochSecond()
        var a = Account(Market.NSE, watchlist = listOf(rel))
        a = Engine.place(a, OrderRequest(rel, Side.BUY, 10, OrderType.MARKET, Product.HOLD, stopLoss = 980.0, strategy = "Breakout", note = "test"),
            Quote(rel.key, 1000.0, 990.0, t), t, s).account
        a = Engine.place(a, OrderRequest(rel, Side.SELL, 4, OrderType.MARKET, Product.HOLD), Quote(rel.key, 1050.0, 990.0, t + 60), t + 60, s).account
        a = Engine.place(a, OrderRequest(rel, Side.BUY, 5, OrderType.LIMIT, Product.HOLD, limitPrice = 900.0), Quote(rel.key, 1050.0, 990.0, t + 90), t + 90, s).account
        val b = BackupFile(exportedAt = t + 100, appVersion = "1.1.0", trade = s, accounts = listOf(a, Account(Market.US)))
        val back = BackupFile.parse(b.encode()).getOrThrow()
        assertEquals(b, back)
        assertEquals(1, back.accounts[0].trades.size)
        assertEquals("Breakout", back.accounts[0].trades[0].strategy)
        assertEquals(3, back.accounts[0].orders.size)
        assertEquals(6, back.accounts[0].positions.single().qty)
        assertTrue(!b.encode().contains("token", ignoreCase = true))
    }

    @Test fun restoreRejectsForeignOrBrokenFiles() {
        assertTrue(BackupFile.parse("{\"hello\": 1}").isFailure)
        assertTrue(BackupFile.parse("not json at all").isFailure)
        val good = BackupFile(exportedAt = 1, appVersion = "1.1.0", accounts = listOf(Account(Market.US)))
        assertTrue(BackupFile.parse(good.copy(app = "other-app").encode()).exceptionOrNull()!!.message!!.contains("isn't"))
        assertTrue(BackupFile.parse(good.copy(format = 99).encode()).exceptionOrNull()!!.message!!.contains("newer"))
        assertTrue(BackupFile.parse(good.copy(accounts = listOf(Account(Market.US), Account(Market.US))).encode()).isFailure)
        assertTrue(BackupFile.parse(good.encode()).isSuccess)
    }
}
