package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Timeframe
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters

/**
 * Candle bucketing anchored to the exchange session, so every source lines up the same way:
 * intraday bars start at the session open (9:15 IST / 9:30 ET) and step by the timeframe;
 * daily bars at the session open of their day, weekly at Monday's open. Used to clean the
 * downloaded series (Yahoo appends an unaligned "live" bar) and to fold live ticks into the chart.
 */
object Bars {
    fun seconds(tf: Timeframe): Long = when (tf) {
        Timeframe.M1 -> 60; Timeframe.M5 -> 300; Timeframe.M15 -> 900; Timeframe.H1 -> 3600
        Timeframe.D1 -> 86400; Timeframe.W1 -> 7 * 86400L
    }

    fun intraday(tf: Timeframe) = tf != Timeframe.D1 && tf != Timeframe.W1

    fun bucket(m: Market, tf: Timeframe, t: Long): Long {
        val day = MarketHours.localDate(m, t)
        return when (tf) {
            Timeframe.D1 -> MarketHours.openAt(m, day)
            Timeframe.W1 -> MarketHours.openAt(m, day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)))
            else -> {
                val open = MarketHours.openAt(m, day)
                if (t < open) t - Math.floorMod(t, seconds(tf)) else open + (t - open) / seconds(tf) * seconds(tf)
            }
        }
    }

    /** Sorted, finite, one candle per bucket (several source bars in one bucket are merged). */
    fun normalize(m: Market, tf: Timeframe, src: List<Candle>): List<Candle> {
        val out = ArrayList<Candle>(src.size)
        for (c in src.filter { listOf(it.o, it.h, it.l, it.c).all { v -> v.isFinite() && v > 0 } }.sortedBy { it.t }) {
            val b = bucket(m, tf, c.t)
            val last = out.lastOrNull()
            if (last != null && last.t == b) {
                out[out.size - 1] = last.copy(h = maxOf(last.h, c.h), l = minOf(last.l, c.l), c = c.c, v = last.v + (if (c.v.isFinite()) c.v else 0.0))
            } else {
                out += Candle(b, c.o, maxOf(c.h, c.o, c.c), minOf(c.l, c.o, c.c), c.c, if (c.v.isFinite()) c.v else 0.0)
            }
        }
        return out
    }

    /**
     * Folds a live trade price into the series: updates the forming candle, or starts a new one when
     * [time] falls in a later bucket (only during the session for intraday timeframes).
     */
    fun tick(m: Market, tf: Timeframe, candles: List<Candle>, price: Double, time: Long): List<Candle> {
        if (candles.isEmpty() || !price.isFinite() || price <= 0) return candles
        val b = bucket(m, tf, time)
        val last = candles.last()
        return when {
            b == last.t -> candles.dropLast(1) + last.copy(h = maxOf(last.h, price), l = minOf(last.l, price), c = price)
            b > last.t && (!intraday(tf) || MarketHours.isOpen(m, time)) -> candles + Candle(b, price, price, price, price, 0.0)
            else -> candles
        }
    }
}
