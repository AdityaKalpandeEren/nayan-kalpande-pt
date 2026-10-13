package com.kalpande.nkpt

import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Timeframe
import com.kalpande.nkpt.data.Tokens
import com.kalpande.nkpt.domain.Bars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Base64

class BarsTest {
    private val d = LocalDate.of(2026, 10, 13)
    private fun et(h: Int, m: Int, s: Int = 0) = ZonedDateTime.of(d, LocalTime.of(h, m, s), ZoneId.of("America/New_York")).toEpochSecond()
    private fun ist(h: Int, m: Int) = ZonedDateTime.of(d, LocalTime.of(h, m), ZoneId.of("Asia/Kolkata")).toEpochSecond()

    @Test fun yahooLiveBarMergesIntoItsCandle() {
        val src = listOf(
            Candle(et(13, 0), 100.0, 101.0, 99.5, 100.5, 1000.0),
            Candle(et(13, 5), 100.5, 102.0, 100.4, 101.8, 800.0),
            Candle(et(13, 9, 54), 101.8, 101.9, 101.7, 101.75, 50.0),   // Yahoo's unaligned live point
        )
        val n = Bars.normalize(Market.US, Timeframe.M5, src)
        assertEquals(2, n.size)
        assertEquals(et(13, 5), n.last().t)
        assertEquals(101.75, n.last().c, 1e-9)
        assertEquals(102.0, n.last().h, 1e-9)
        assertEquals(850.0, n.last().v, 1e-9)
    }

    @Test fun hourlyBarsAnchorAtTheSessionOpen() {
        assertEquals(et(9, 30), Bars.bucket(Market.US, Timeframe.H1, et(10, 10)))
        assertEquals(et(10, 30), Bars.bucket(Market.US, Timeframe.H1, et(10, 45)))
        assertEquals(ist(9, 15), Bars.bucket(Market.NSE, Timeframe.H1, ist(9, 59)))
        assertEquals(ist(10, 15), Bars.bucket(Market.NSE, Timeframe.H1, ist(10, 16)))
        assertEquals(ist(9, 30), Bars.bucket(Market.NSE, Timeframe.M15, ist(9, 44)))
    }

    @Test fun liveTicksUpdateAndRollCandles() {
        var c = listOf(Candle(et(13, 0), 100.0, 101.0, 99.0, 100.5, 1000.0))
        c = Bars.tick(Market.US, Timeframe.M1, c, 101.5, et(13, 0, 40))
        assertEquals(1, c.size); assertEquals(101.5, c.last().h, 1e-9); assertEquals(101.5, c.last().c, 1e-9)
        c = Bars.tick(Market.US, Timeframe.M1, c, 98.0, et(13, 0, 55))
        assertEquals(98.0, c.last().l, 1e-9)
        c = Bars.tick(Market.US, Timeframe.M1, c, 98.4, et(13, 1, 2))
        assertEquals(2, c.size); assertEquals(et(13, 1), c.last().t); assertEquals(98.4, c.last().o, 1e-9)
        val after = Bars.tick(Market.US, Timeframe.M1, c, 99.0, et(17, 30))         // after the close: no new candle
        assertEquals(2, after.size)
        val daily = Bars.tick(Market.US, Timeframe.D1, listOf(Candle(et(9, 30), 100.0, 101.0, 99.0, 100.0)), 103.0, et(15, 0))
        assertEquals(1, daily.size); assertEquals(103.0, daily.last().h, 1e-9)
    }

    @Test fun badPricesAreDropped() {
        val n = Bars.normalize(Market.NSE, Timeframe.M5, listOf(Candle(ist(10, 0), Double.NaN, 1.0, 1.0, 1.0), Candle(ist(10, 5), 10.0, 11.0, 9.0, 10.5)))
        assertEquals(1, n.size)
        assertTrue(Bars.tick(Market.NSE, Timeframe.M5, n, Double.NaN, ist(10, 6)) == n)
    }

    @Test fun tokenExpiryIsReadLocally() {
        fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        val tok = b64("{\"alg\":\"HS256\"}") + "." + b64("{\"sub\":\"X\",\"isExtended\":true,\"exp\":1820613600}") + ".sig"
        assertEquals(1820613600L, Tokens.expiry(tok))
        assertTrue(Tokens.isExtended(tok))
        assertEquals(null, Tokens.expiry("not-a-jwt"))
    }
}
