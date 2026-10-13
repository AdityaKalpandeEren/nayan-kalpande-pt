package com.kalpande.nkpt.data

import kotlinx.serialization.Serializable

/** The two paper accounts. Each keeps its own currency and starting capital. */
@Serializable
enum class Market(val label: String, val flag: String, val currency: String, val startCash: Double, val zone: String) {
    US("US", "🇺🇸", "$", 2_000.0, "America/New_York"),
    NSE("NSE", "🇮🇳", "₹", 200_000.0, "Asia/Kolkata"),
}

@Serializable enum class Side { BUY, SELL }

/** MARKET fills at the live price (or at the next open when the market is shut);
 *  LIMIT fills at the price or better; STOP becomes a market order once the trigger trades. */
@Serializable enum class OrderType { MARKET, LIMIT, STOP }

/** INTRADAY is squared off automatically before the close; HOLD (delivery) stays until you exit. */
@Serializable enum class Product { INTRADAY, HOLD }

@Serializable enum class OrderStatus { OPEN, FILLED, CANCELLED, REJECTED }

/** A tradable (or chart-only) symbol. [key] is what the data source needs:
 *  Upstox instrument key for NSE ("NSE_EQ|INE002A01018"), Yahoo symbol for US ("BRK-B"). */
@Serializable
data class Instrument(
    val market: Market,
    val symbol: String,
    val name: String,
    val key: String,
    val exchange: String = "",
    val tradable: Boolean = true,
)

/** One OHLCV bar; [t] is epoch seconds of the bar's start. */
@Serializable
data class Candle(val t: Long, val o: Double, val h: Double, val l: Double, val c: Double, val v: Double = 0.0)

@Serializable
data class Quote(val key: String, val price: Double, val prevClose: Double, val time: Long) {
    val change: Double get() = price - prevClose
    val changePct: Double get() = if (prevClose > 0) change / prevClose * 100 else 0.0
}

@Serializable
data class Order(
    val id: String,
    val market: Market,
    val symbol: String,
    val key: String,
    val name: String,
    val side: Side,
    val qty: Int,
    val type: OrderType,
    val product: Product,
    val limitPrice: Double? = null,
    val triggerPrice: Double? = null,
    val stopLoss: Double? = null,
    val target: Double? = null,
    val strategy: String = "",
    val note: String = "",
    val createdAt: Long,
    val status: OrderStatus = OrderStatus.OPEN,
    val fillPrice: Double? = null,
    val filledAt: Long? = null,
    val charges: Double = 0.0,
    /** why it was rejected / cancelled, or the automatic exit that created it (SL, TARGET, SQUARE_OFF). */
    val reason: String = "",
    /** engine bookkeeping: 1-minute bars before this time were already checked. */
    val checkedUntil: Long = createdAt,
)

/** An open position. [qty] is signed: positive = long, negative = short. */
@Serializable
data class Position(
    val market: Market,
    val symbol: String,
    val key: String,
    val name: String,
    val product: Product,
    val qty: Int,
    val avgPrice: Double,
    val openedAt: Long,
    val stopLoss: Double? = null,
    val target: Double? = null,
    val strategy: String = "",
    val note: String = "",
    /** charges paid to build the still-open part; moved to the closed trade when it is closed. */
    val entryCharges: Double = 0.0,
    val lastPrice: Double = avgPrice,
    /** stop-loss distance at entry, for R multiples (0 when no stop was set). */
    val initialRisk: Double = 0.0,
    val checkedUntil: Long = openedAt,
) {
    val isLong: Boolean get() = qty > 0
    val unrealized: Double get() = (lastPrice - avgPrice) * qty
    val id: String get() = "$market|$symbol|$product"
}

@Serializable
data class ClosedTrade(
    val id: String,
    val market: Market,
    val symbol: String,
    val name: String,
    val product: Product,
    /** BUY = it was a long, SELL = a short. */
    val direction: Side,
    val qty: Int,
    val entryPrice: Double,
    val exitPrice: Double,
    val openedAt: Long,
    val closedAt: Long,
    val gross: Double,
    val charges: Double,
    val strategy: String,
    /** MANUAL, SL, TARGET, SQUARE_OFF */
    val exitReason: String,
    val initialRisk: Double = 0.0,
    val note: String = "",
) {
    val net: Double get() = gross - charges
    /** P&L in multiples of the risk taken (null when no stop-loss was set). */
    val rMultiple: Double? get() = if (initialRisk > 0) net / (initialRisk * qty) else null
    val holdSeconds: Long get() = closedAt - openedAt
}

@Serializable
data class EquityPoint(val t: Long, val equity: Double)

@Serializable
data class Account(
    val market: Market,
    val cash: Double = market.startCash,
    val startCash: Double = market.startCash,
    val createdAt: Long = 0,
    val chargesPaid: Double = 0.0,
    val orders: List<Order> = emptyList(),
    val positions: List<Position> = emptyList(),
    val trades: List<ClosedTrade> = emptyList(),
    val equity: List<EquityPoint> = emptyList(),
    val watchlist: List<Instrument> = emptyList(),
    val strategies: List<String> = DEFAULT_STRATEGIES,
) {
    companion object {
        val DEFAULT_STRATEGIES = listOf("Breakout", "Pullback", "Momentum", "Mean reversion", "Gap", "News", "Swing", "Long term")
    }
}

/** What the order ticket sends to the engine. */
data class OrderRequest(
    val instrument: Instrument,
    val side: Side,
    val qty: Int,
    val type: OrderType,
    val product: Product,
    val limitPrice: Double? = null,
    val triggerPrice: Double? = null,
    val stopLoss: Double? = null,
    val target: Double? = null,
    val strategy: String = "",
    val note: String = "",
)

enum class Timeframe(val label: String) { M1("1m"), M5("5m"), M15("15m"), H1("1H"), D1("1D"), W1("1W") }
