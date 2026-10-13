package com.kalpande.nkpt.ui

import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.MarketHours
import com.kalpande.nkpt.domain.TradeSettings
import kotlin.math.abs

data class Summary(
    val equity: Double,
    val totalPnl: Double,
    val totalPct: Double,
    val dayPnl: Double,
    val invested: Double,
    val unrealized: Double,
    val buyingPower: Double,
    val cash: Double,
)

fun summary(a: Account, quotes: Map<String, Quote>, s: TradeSettings, now: Long = System.currentTimeMillis() / 1000): Summary {
    val today = MarketHours.localDate(a.market, now)
    val eq = Engine.equity(a)
    val day = a.positions.sumOf { p ->
        val prev = quotes[p.key]?.prevClose?.takeIf { it > 0 }
        val opened = MarketHours.localDate(a.market, p.openedAt)
        if (opened == today || prev == null) (p.lastPrice - p.avgPrice) * p.qty else (p.lastPrice - prev) * p.qty
    } + a.trades.filter { MarketHours.localDate(a.market, it.closedAt) == today }.sumOf { it.net }
    return Summary(
        equity = eq,
        totalPnl = eq - a.startCash,
        totalPct = (eq - a.startCash) / a.startCash * 100,
        dayPnl = day,
        invested = a.positions.sumOf { abs(it.qty) * it.avgPrice },
        unrealized = a.positions.sumOf { it.unrealized },
        buyingPower = Engine.buyingPower(a, s),
        cash = a.cash,
    )
}

val POPULAR = mapOf(
    Market.NSE to listOf("RELIANCE", "HDFCBANK", "TCS", "INFY", "ICICIBANK", "SBIN", "BHARTIARTL", "ITC", "TATAMOTORS", "LT"),
    Market.US to listOf("AAPL", "MSFT", "NVDA", "AMZN", "TSLA", "META", "GOOGL", "SPY", "QQQ", "AMD"),
)
