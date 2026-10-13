package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Side
import kotlin.math.ceil
import kotlin.math.min

/**
 * Broker-style charges for one executed order.
 *
 * NSE - Zerodha's published equity rates (2025):
 *   HOLD (delivery): brokerage 0 · STT 0.1% on buy and sell · stamp duty 0.015% on buy ·
 *                    DP charge Rs 15.34 per sell
 *   INTRADAY:        brokerage 0.03% or Rs 20 per order, whichever is lower · STT 0.025% on sell ·
 *                    stamp duty 0.003% on buy
 *   both:            NSE transaction charge 0.00297% · SEBI Rs 10 per crore ·
 *                    GST 18% on brokerage + transaction + SEBI charges
 * US - zero-commission broker (Alpaca / Robinhood style):
 *   SEC fee $27.80 per $1M sold · FINRA TAF $0.000166 per share sold (max $8.30)
 */
object Charges {
    data class Breakdown(
        val brokerage: Double = 0.0,
        val stt: Double = 0.0,
        val exchange: Double = 0.0,
        val sebi: Double = 0.0,
        val stamp: Double = 0.0,
        val gst: Double = 0.0,
        val dp: Double = 0.0,
        val secFee: Double = 0.0,
        val taf: Double = 0.0,
    ) {
        val total: Double get() = brokerage + stt + exchange + sebi + stamp + gst + dp + secFee + taf
    }

    fun of(market: Market, side: Side, product: Product, qty: Int, price: Double): Breakdown =
        if (market == Market.NSE) nse(side, product, qty, price) else us(side, qty, price)

    fun nse(side: Side, product: Product, qty: Int, price: Double): Breakdown {
        val turnover = qty * price
        val buy = side == Side.BUY
        val intraday = product == Product.INTRADAY
        val brokerage = if (intraday) min(turnover * 0.0003, 20.0) else 0.0
        val stt = when {
            intraday -> if (buy) 0.0 else turnover * 0.00025
            else -> turnover * 0.001
        }
        val exchange = turnover * 0.0000297
        val sebi = turnover * 10.0 / 1e7
        val stamp = if (buy) turnover * (if (intraday) 0.00003 else 0.00015) else 0.0
        val gst = (brokerage + exchange + sebi) * 0.18
        val dp = if (!intraday && !buy) 15.34 else 0.0
        return Breakdown(brokerage = brokerage, stt = stt, exchange = exchange, sebi = sebi, stamp = stamp, gst = gst, dp = dp)
    }

    fun us(side: Side, qty: Int, price: Double): Breakdown {
        if (side == Side.BUY) return Breakdown()
        val sec = ceil(qty * price * 27.80 / 1e6 * 100) / 100
        val taf = min(ceil(qty * 0.000166 * 100) / 100, 8.30)
        return Breakdown(secFee = sec, taf = taf)
    }
}
