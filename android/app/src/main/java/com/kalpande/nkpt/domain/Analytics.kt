package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.ClosedTrade
import com.kalpande.nkpt.data.EquityPoint
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Side
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

data class Stats(
    val trades: Int,
    val wins: Int,
    val losses: Int,
    val net: Double,
    val gross: Double,
    val charges: Double,
    val avgWin: Double,
    val avgLoss: Double,
    val largestWin: Double,
    val largestLoss: Double,
    /** sum of wins / |sum of losses| (null when there are no losses). */
    val profitFactor: Double?,
    /** average net P&L per trade. */
    val expectancy: Double,
    /** average R multiple over trades that had a stop-loss (null if none did). */
    val avgR: Double?,
    val avgHoldSec: Long,
    val maxWinStreak: Int,
    val maxLossStreak: Int,
    /** per-trade t-statistic of net P&L - below ~2 the result is still inside the noise. */
    val tStat: Double?,
) {
    val winRate: Double get() = if (trades > 0) wins * 100.0 / trades else 0.0
    val payoff: Double? get() = if (avgLoss < 0) avgWin / abs(avgLoss) else null
}

data class Group(val label: String, val stats: Stats)

object Analytics {
    fun stats(t: List<ClosedTrade>): Stats {
        val nets = t.sortedBy { it.closedAt }.map { it.net }
        val wins = nets.filter { it > 0 }
        val losses = nets.filter { it <= 0 }
        var ws = 0; var ls = 0; var mws = 0; var mls = 0
        for (n in nets) {
            if (n > 0) { ws++; ls = 0 } else { ls++; ws = 0 }
            mws = max(mws, ws); mls = max(mls, ls)
        }
        val mean = if (nets.isEmpty()) 0.0 else nets.average()
        val sd = if (nets.size > 1) sqrt(nets.sumOf { (it - mean) * (it - mean) } / (nets.size - 1)) else 0.0
        val rs = t.mapNotNull { it.rMultiple }
        return Stats(
            trades = t.size, wins = wins.size, losses = losses.size,
            net = nets.sum(), gross = t.sumOf { it.gross }, charges = t.sumOf { it.charges },
            avgWin = if (wins.isEmpty()) 0.0 else wins.average(),
            avgLoss = if (losses.isEmpty()) 0.0 else losses.average(),
            largestWin = wins.maxOrNull() ?: 0.0, largestLoss = losses.minOrNull() ?: 0.0,
            profitFactor = losses.sum().let { l -> if (l < 0) wins.sum() / abs(l) else null },
            expectancy = mean,
            avgR = if (rs.isEmpty()) null else rs.average(),
            avgHoldSec = if (t.isEmpty()) 0 else t.sumOf { it.holdSeconds } / t.size,
            maxWinStreak = mws, maxLossStreak = mls,
            tStat = if (nets.size > 2 && sd > 0) mean / (sd / sqrt(nets.size.toDouble())) else null,
        )
    }

    private fun groups(t: List<ClosedTrade>, key: (ClosedTrade) -> String): List<Group> =
        t.groupBy(key).map { (k, v) -> Group(k, stats(v)) }.sortedByDescending { it.stats.net }

    fun byStrategy(t: List<ClosedTrade>) = groups(t) { it.strategy.ifBlank { "Untagged" } }
    fun bySymbol(t: List<ClosedTrade>) = groups(t) { it.symbol }
    fun byDirection(t: List<ClosedTrade>) = groups(t) { if (it.direction == Side.BUY) "Long" else "Short" }
    fun byProduct(t: List<ClosedTrade>) = groups(t) { if (it.product == Product.INTRADAY) "Intraday" else "Hold (swing / long)" }
    fun byExit(t: List<ClosedTrade>) = groups(t) {
        when (it.exitReason) { "SL" -> "Stop-loss"; "TARGET" -> "Target"; "SQUARE_OFF" -> "Square-off"; else -> "Manual exit" }
    }

    fun byWeekday(m: Market, t: List<ClosedTrade>) = t.groupBy {
        Instant.ofEpochSecond(it.openedAt).atZone(ZoneId.of(m.zone)).dayOfWeek
    }.toSortedMap().map { (d, v) -> Group(d.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, stats(v)) }

    fun byHolding(t: List<ClosedTrade>) = t.groupBy {
        val h = it.holdSeconds
        when {
            h < 3600 -> "0 under 1 hour"
            h < 86400 -> "1 same day"
            h < 7 * 86400 -> "2 1-7 days"
            h < 30 * 86400 -> "3 1-4 weeks"
            else -> "4 over a month"
        }
    }.toSortedMap().map { (k, v) -> Group(k.drop(2), stats(v)) }

    /** Largest peak-to-trough fall of the equity curve, as (amount, percent of the peak). */
    fun maxDrawdown(points: List<EquityPoint>): Pair<Double, Double> {
        var peak = Double.NEGATIVE_INFINITY
        var dd = 0.0
        var ddPct = 0.0
        for (p in points) {
            peak = max(peak, p.equity)
            val d = peak - p.equity
            if (d > dd) { dd = d; ddPct = if (peak > 0) d / peak * 100 else 0.0 }
        }
        return dd to ddPct
    }

    fun since(t: List<ClosedTrade>, fromEpoch: Long) = t.filter { it.closedAt >= fromEpoch }
}
