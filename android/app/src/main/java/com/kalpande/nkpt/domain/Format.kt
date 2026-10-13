package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.Market
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

object Format {
    private val indian = java.text.DecimalFormat("#,##,##0.00")
    private val western = java.text.DecimalFormat("#,##0.00")

    /** Indian grouping (1,23,456.78) for NSE, western (123,456.78) for US. */
    fun num(m: Market, x: Double): String {
        if (m == Market.NSE) return indianGroup(x)
        return western.format(x)
    }

    private fun indianGroup(x: Double): String {
        val neg = x < 0
        val s = String.format(Locale.US, "%.2f", abs(x))
        val (int, frac) = s.split(".")
        val grouped = if (int.length <= 3) int else {
            val last3 = int.takeLast(3)
            val rest = int.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$rest,$last3"
        }
        return (if (neg) "-" else "") + grouped + "." + frac
    }

    fun money(m: Market, x: Double): String = (if (x < 0) "-" else "") + m.currency + num(m, abs(x))

    fun signedMoney(m: Market, x: Double): String = (if (x >= 0) "+" else "-") + m.currency + num(m, abs(x))

    fun price(m: Market, x: Double): String = num(m, x)

    fun pct(x: Double): String = String.format(Locale.US, "%+.2f%%", x)

    fun compact(m: Market, x: Double): String {
        val a = abs(x)
        val sign = if (x < 0) "-" else ""
        return sign + m.currency + when {
            m == Market.NSE && a >= 1e7 -> String.format(Locale.US, "%.2fCr", a / 1e7)
            m == Market.NSE && a >= 1e5 -> String.format(Locale.US, "%.2fL", a / 1e5)
            m == Market.US && a >= 1e9 -> String.format(Locale.US, "%.2fB", a / 1e9)
            m == Market.US && a >= 1e6 -> String.format(Locale.US, "%.2fM", a / 1e6)
            a >= 1e3 -> String.format(Locale.US, "%.1fK", a / 1e3)
            else -> String.format(Locale.US, "%.2f", a)
        }
    }

    fun time(m: Market, epochSec: Long, pattern: String = "dd MMM HH:mm"): String =
        DateTimeFormatter.ofPattern(pattern, Locale.US).format(Instant.ofEpochSecond(epochSec).atZone(ZoneId.of(m.zone)))

    fun duration(sec: Long): String = when {
        sec < 3600 -> "${sec / 60}m"
        sec < 86400 -> "${sec / 3600}h ${sec % 3600 / 60}m"
        else -> "${sec / 86400}d ${sec % 86400 / 3600}h"
    }
}
