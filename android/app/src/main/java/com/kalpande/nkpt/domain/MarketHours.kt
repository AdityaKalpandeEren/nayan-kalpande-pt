package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.Market
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Regular sessions (weekends closed; exchange holidays show up as "no new candles"). */
object MarketHours {
    data class Session(val open: LocalTime, val close: LocalTime, val squareOff: LocalTime)

    fun session(m: Market): Session = when (m) {
        Market.NSE -> Session(LocalTime.of(9, 15), LocalTime.of(15, 30), LocalTime.of(15, 20))
        Market.US -> Session(LocalTime.of(9, 30), LocalTime.of(16, 0), LocalTime.of(15, 55))
    }

    fun zone(m: Market): ZoneId = ZoneId.of(m.zone)

    fun localDate(m: Market, epochSec: Long): LocalDate = Instant.ofEpochSecond(epochSec).atZone(zone(m)).toLocalDate()

    fun isTradingDay(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    fun isOpen(m: Market, epochSec: Long): Boolean {
        val z = Instant.ofEpochSecond(epochSec).atZone(zone(m))
        val s = session(m)
        return isTradingDay(z.toLocalDate()) && !z.toLocalTime().isBefore(s.open) && z.toLocalTime().isBefore(s.close)
    }

    /** Epoch seconds of the intraday square-off on [d]. */
    fun squareOffAt(m: Market, d: LocalDate): Long = ZonedDateTime.of(d, session(m).squareOff, zone(m)).toEpochSecond()

    fun openAt(m: Market, d: LocalDate): Long = ZonedDateTime.of(d, session(m).open, zone(m)).toEpochSecond()

    fun closeAt(m: Market, d: LocalDate): Long = ZonedDateTime.of(d, session(m).close, zone(m)).toEpochSecond()

    /** True once today's intraday window is over (after square-off, or a non-trading day). */
    fun intradayClosed(m: Market, epochSec: Long): Boolean {
        val d = localDate(m, epochSec)
        return !isTradingDay(d) || epochSec >= squareOffAt(m, d)
    }

    fun statusText(m: Market, epochSec: Long): String {
        if (isOpen(m, epochSec)) return "Market open"
        val z = Instant.ofEpochSecond(epochSec).atZone(zone(m))
        var d = z.toLocalDate()
        if (!isTradingDay(d) || !z.toLocalTime().isBefore(session(m).open)) {
            d = d.plusDays(1)
            while (!isTradingDay(d)) d = d.plusDays(1)
        }
        val day = if (d == z.toLocalDate()) "today" else d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        return "Closed · opens $day ${session(m).open}"
    }
}
