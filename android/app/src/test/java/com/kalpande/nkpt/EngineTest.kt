package com.kalpande.nkpt

import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.InstrumentRepository
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderStatus
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Analytics
import com.kalpande.nkpt.domain.Charges
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.TradeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class EngineTest {
    private val s = TradeSettings(slippageBpsNse = 0.0, slippageBpsUs = 0.0)
    private val rel = Instrument(Market.NSE, "RELIANCE", "Reliance Industries", "NSE_EQ|INE002A01018")
    private val aapl = Instrument(Market.US, "AAPL", "Apple", "AAPL")
    private val mon = LocalDate.of(2026, 10, 12)          // a Monday

    private fun ist(h: Int, m: Int, d: LocalDate = mon) = ZonedDateTime.of(d, LocalTime.of(h, m), ZoneId.of("Asia/Kolkata")).toEpochSecond()
    private fun et(h: Int, m: Int, d: LocalDate = mon) = ZonedDateTime.of(d, LocalTime.of(h, m), ZoneId.of("America/New_York")).toEpochSecond()
    private fun nse() = Account(Market.NSE)
    private fun q(key: String, px: Double, t: Long) = Quote(key, px, px, t)
    private fun bar(t: Long, o: Double, h: Double, l: Double, c: Double) = Candle(t, o, h, l, c)
    private fun req(i: Instrument, side: Side, qty: Int, type: OrderType = OrderType.MARKET, product: Product = Product.HOLD,
                    limit: Double? = null, trig: Double? = null, sl: Double? = null, tg: Double? = null) =
        OrderRequest(i, side, qty, type, product, limit, trig, sl, tg, "Breakout")

    @Test fun chargesMatchPublishedRates() {
        assertEquals(118.65, Charges.nse(Side.BUY, Product.HOLD, 100, 1000.0).total, 0.05)
        assertEquals(52.22, Charges.nse(Side.SELL, Product.INTRADAY, 100, 1000.0).total, 0.01)
        assertEquals(15.34 + 100 + 2.97 + 0.1 + 0.5526, Charges.nse(Side.SELL, Product.HOLD, 100, 1000.0).total, 0.01)
        assertEquals(0.06, Charges.us(Side.SELL, 10, 150.0).total, 1e-9)
        assertEquals(0.0, Charges.us(Side.BUY, 10, 150.0).total, 1e-9)
    }

    @Test fun marketOrderFillsAtLiveQuoteWhileOpen() {
        val t = ist(10, 0)
        val r = Engine.place(nse(), req(rel, Side.BUY, 10), q(rel.key, 1000.0, t - 5), t, s.copy(slippageBpsNse = 10.0))
        assertNull(r.error)
        assertEquals(OrderStatus.FILLED, r.order!!.status)
        assertEquals(1001.0, r.order.fillPrice!!, 1e-9)          // 10 bps adverse slippage
        val p = r.account.positions.single()
        assertEquals(10, p.qty)
        assertEquals(200_000 - 10_010 - r.order.charges, r.account.cash, 1e-6)
    }

    @Test fun afterMarketOrderFillsAtNextOpen() {
        val evening = ist(19, 0)
        val r = Engine.place(nse(), req(rel, Side.BUY, 5), q(rel.key, 1000.0, ist(15, 29)), evening, s)
        assertEquals(OrderStatus.OPEN, r.order!!.status)
        val tue = mon.plusDays(1)
        val (a, ev) = Engine.onBars(r.account, mapOf(rel.key to listOf(bar(ist(9, 15, tue), 1010.0, 1015.0, 1005.0, 1012.0))), ist(9, 20, tue), s)
        val o = a.orders.single()
        assertEquals(OrderStatus.FILLED, o.status)
        assertEquals(1010.0, o.fillPrice!!, 1e-9)
        assertTrue(ev.single().text.contains("filled"))
    }

    @Test fun limitBuyGapFillsAtTheBetterOpen() {
        val t = ist(10, 0)
        val r = Engine.place(nse(), req(rel, Side.BUY, 10, OrderType.LIMIT, limit = 990.0), q(rel.key, 1000.0, t), t, s)
        assertEquals(OrderStatus.OPEN, r.order!!.status)
        val bars = listOf(bar(ist(10, 1), 999.0, 1001.0, 995.0, 996.0), bar(ist(10, 2), 985.0, 988.0, 980.0, 987.0))
        val (a, _) = Engine.onBars(r.account, mapOf(rel.key to bars), ist(10, 5), s)
        assertEquals(985.0, a.orders.single().fillPrice!!, 1e-9)    // gapped through 990 -> filled at the 985 open
    }

    @Test fun stopLossWinsWhenOneBarTouchesBoth() {
        val t = ist(10, 0)
        val r = Engine.place(nse(), req(rel, Side.BUY, 10, sl = 980.0, tg = 1030.0), q(rel.key, 1000.0, t), t, s)
        val (a, ev) = Engine.onBars(r.account, mapOf(rel.key to listOf(bar(ist(10, 1), 1000.0, 1040.0, 970.0, 1000.0))), ist(10, 3), s)
        assertTrue(a.positions.isEmpty())
        val tr = a.trades.single()
        assertEquals("SL", tr.exitReason)
        assertEquals(980.0, tr.exitPrice, 1e-9)
        assertEquals(-200.0, tr.gross, 1e-9)                // exactly one R (10 x Rs 20) before charges
        assertEquals((-200.0 - tr.charges) / 200.0, tr.rMultiple!!, 1e-9)   // net R includes the charges
        assertTrue(ev.any { it.text.contains("Stop-loss") })
    }

    @Test fun shortSellingRules() {
        val t = ist(10, 0)
        val held = Engine.place(nse(), req(rel, Side.SELL, 5), q(rel.key, 1000.0, t), t, s)
        assertNotNull(held.error)                              // NSE delivery short not allowed
        val mis = Engine.place(nse(), req(rel, Side.SELL, 5, product = Product.INTRADAY), q(rel.key, 1000.0, t), t, s)
        assertNull(mis.error)
        assertEquals(-5, mis.account.positions.single().qty)
        val usT = et(10, 0)
        val us = Engine.place(Account(Market.US), req(aapl, Side.SELL, 5), q("AAPL", 200.0, usT), usT, s)
        assertNull(us.error)                                   // $1,000 short * 150% = $1,500 <= $2,000
        val tooBig = Engine.place(Account(Market.US), req(aapl, Side.SELL, 7), q("AAPL", 200.0, usT), usT, s)
        assertNotNull(tooBig.error)                            // $1,400 * 150% = $2,100 > $2,000
    }

    @Test fun intradayLeverageAndBuyingPower() {
        val t = ist(10, 0)
        val ok = Engine.place(nse(), req(rel, Side.BUY, 900, product = Product.INTRADAY), q(rel.key, 1000.0, t), t, s)
        assertNull(ok.error)                                   // Rs 9 lakh at 20% margin = Rs 1.8 lakh
        val no = Engine.place(nse(), req(rel, Side.BUY, 300), q(rel.key, 1000.0, t), t, s)
        assertNotNull(no.error)                                // Rs 3 lakh delivery > Rs 2 lakh cash
    }

    @Test fun partialCloseSplitsChargesAndCashAddsUp() {
        var a = nse()
        val t = ist(10, 0)
        a = Engine.place(a, req(rel, Side.BUY, 10), q(rel.key, 1000.0, t), t, s).account
        a = Engine.place(a, req(rel, Side.SELL, 4), q(rel.key, 1100.0, t + 60), t + 60, s).account
        assertEquals(6, a.positions.single().qty)
        assertEquals(400.0, a.trades.single().gross, 1e-9)
        a = Engine.place(a, req(rel, Side.SELL, 6), q(rel.key, 1050.0, t + 120), t + 120, s).account
        assertTrue(a.positions.isEmpty())
        val net = a.trades.sumOf { it.net }
        assertEquals(200_000 + net, a.cash, 1e-6)              // cash = start + realised net P&L
        assertEquals(a.chargesPaid, a.trades.sumOf { it.charges }, 1e-6)
    }

    @Test fun intradayIsSquaredOffAt1520() {
        val t = ist(14, 0)
        val r = Engine.place(nse(), req(rel, Side.BUY, 10, product = Product.INTRADAY), q(rel.key, 1000.0, t), t, s)
        val bars = listOf(bar(ist(15, 19), 1005.0, 1006.0, 1004.0, 1005.0), bar(ist(15, 20), 1007.0, 1008.0, 1006.0, 1007.0))
        val (a, _) = Engine.onBars(r.account, mapOf(rel.key to bars), ist(15, 25), s)
        assertEquals("SQUARE_OFF", a.trades.single().exitReason)
        assertEquals(1007.0, a.trades.single().exitPrice, 1e-9)
    }

    @Test fun analyticsBasics() {
        var a = nse()
        val t = ist(10, 0)
        for ((i, exit) in listOf(1100.0, 950.0, 1050.0).withIndex()) {
            a = Engine.place(a, req(rel, Side.BUY, 10), q(rel.key, 1000.0, t + i * 600), t + i * 600, s).account
            a = Engine.place(a, req(rel, Side.SELL, 10), q(rel.key, exit, t + i * 600 + 300), t + i * 600 + 300, s).account
        }
        val st = Analytics.stats(a.trades)
        assertEquals(3, st.trades)
        assertEquals(2, st.wins)
        assertTrue(st.profitFactor!! > 1)
        assertEquals("Breakout", Analytics.byStrategy(a.trades).single().label)
    }

    @Test fun searchRanksTickerBeforeName() {
        val all = listOf(
            Instrument(Market.NSE, "CUPIDALBV", "Cupid Breweries", "k1"),
            Instrument(Market.NSE, "CUPID", "Cupid Ltd", "k2"),
            Instrument(Market.NSE, "ABC", "Super Cupid Corp", "k3"),
            Instrument(Market.NSE, "XYZ", "Nothing", "k4"),
        )
        assertEquals(listOf("CUPID", "CUPIDALBV", "ABC"), InstrumentRepository.rank(all, "CUPID").map { it.symbol })
    }
}
