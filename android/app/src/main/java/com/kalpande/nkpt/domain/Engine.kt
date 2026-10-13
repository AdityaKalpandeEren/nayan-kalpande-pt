package com.kalpande.nkpt.domain

import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.ClosedTrade
import com.kalpande.nkpt.data.EquityPoint
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Order
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderStatus
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Position
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Side
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sign

@kotlinx.serialization.Serializable
data class TradeSettings(
    /** adverse slippage on market / stop fills, basis points of price. */
    val slippageBpsUs: Double = 2.0,
    val slippageBpsNse: Double = 5.0,
    /** margin as a share of position value. NSE intraday 0.2 = 5x leverage (MIS). */
    val nseIntradayMargin: Double = 0.2,
    val usLongMargin: Double = 1.0,
    val usShortMargin: Double = 1.5,
    /** a live quote older than this is not used for an instant fill (the order waits for the next bar). */
    val quoteMaxAgeSec: Long = 300,
) {
    fun slippage(m: Market) = (if (m == Market.US) slippageBpsUs else slippageBpsNse) / 1e4
}

data class EngineEvent(val market: Market, val text: String, val positive: Boolean? = null)

data class PlaceResult(val account: Account, val order: Order?, val error: String?, val events: List<EngineEvent> = emptyList())

/**
 * The paper broker. Pure functions on [Account]; nothing here touches the network or disk.
 *
 * Fills: a market order fills at the live price (+ slippage) while the market is open and the
 * quote is fresh, otherwise at the open of the next 1-minute bar (after-market order). Working
 * limit / stop orders and a position's stop-loss / target are checked on every completed
 * 1-minute bar after they were placed (and on live quotes in between): limit at the price or
 * better (a gap through it fills at the bar's open), stop at the trigger or worse (+ slippage).
 * If one bar touches both the stop-loss and the target, the stop-loss is assumed first.
 * INTRADAY positions are squared off at the first bar at/after 15:20 IST (NSE) / 15:55 ET (US);
 * unfilled INTRADAY orders expire then. HOLD orders stay until filled or cancelled.
 */
object Engine {
    private const val BAR = 60L

    fun marginRate(m: Market, product: Product, long: Boolean, s: TradeSettings): Double = when {
        m == Market.NSE && product == Product.INTRADAY -> s.nseIntradayMargin
        m == Market.NSE -> 1.0
        long -> s.usLongMargin
        else -> s.usShortMargin
    }

    fun equity(acc: Account): Double = acc.cash + acc.positions.sumOf { it.qty * it.lastPrice }

    fun marginUsed(acc: Account, s: TradeSettings): Double =
        acc.positions.sumOf { abs(it.qty) * it.lastPrice * marginRate(acc.market, it.product, it.isLong, s) }

    /** Margin held back for working orders that would add exposure. */
    fun reserved(acc: Account, s: TradeSettings, except: String? = null): Double =
        acc.orders.filter { it.status == OrderStatus.OPEN && it.id != except }.sumOf { o ->
            val pos = acc.positions.find { it.symbol == o.symbol && it.product == o.product }
            val add = addedQty(pos?.qty ?: 0, signed(o.side, o.qty))
            val px = o.limitPrice ?: o.triggerPrice ?: pos?.lastPrice ?: lastKnown(acc, o.symbol) ?: 0.0
            add * px * marginRate(acc.market, o.product, newQtySign(pos?.qty ?: 0, signed(o.side, o.qty)) > 0, s)
        }

    fun buyingPower(acc: Account, s: TradeSettings): Double = equity(acc) - marginUsed(acc, s) - reserved(acc, s)

    private fun lastKnown(acc: Account, symbol: String) = acc.positions.find { it.symbol == symbol }?.lastPrice

    private fun signed(side: Side, qty: Int) = if (side == Side.BUY) qty else -qty

    /** Units by which |position| grows when [q] is applied to [p] (0 for a pure reduction). */
    fun addedQty(p: Int, q: Int): Int = when {
        p == 0 || p.sign == q.sign -> abs(q)
        else -> max(0, abs(q) - abs(p))
    }

    private fun newQtySign(p: Int, q: Int): Int = (p + q).sign.let { if (it == 0) q.sign else it }

    private fun r2(x: Double) = round(x * 100) / 100

    private fun slipped(price: Double, side: Side, m: Market, s: TradeSettings) =
        r2(if (side == Side.BUY) price * (1 + s.slippage(m)) else price * (1 - s.slippage(m)))

    // ─── placing ──────────────────────────────────────────────────────────

    fun place(acc: Account, req: OrderRequest, quote: Quote?, now: Long, s: TradeSettings): PlaceResult {
        fun reject(msg: String) = PlaceResult(acc, null, msg)
        val ins = req.instrument
        val m = acc.market
        if (!ins.tradable) return reject("${ins.symbol} is an index - it can be charted, not traded")
        if (req.qty < 1) return reject("Quantity must be at least 1")
        if (req.product == Product.INTRADAY && "T2T" in ins.exchange)
            return reject("${ins.symbol} is a trade-for-trade (BE/BZ) stock - NSE allows delivery only, choose Hold")
        if (req.type == OrderType.LIMIT && (req.limitPrice ?: 0.0) <= 0) return reject("Enter a limit price")
        if (req.type == OrderType.STOP && (req.triggerPrice ?: 0.0) <= 0) return reject("Enter a trigger price")
        val today = MarketHours.localDate(m, now)
        if (req.product == Product.INTRADAY && MarketHours.isTradingDay(today) &&
            now >= MarketHours.squareOffAt(m, today) && now < MarketHours.closeAt(m, today)
        ) return reject("Intraday is closed after the square-off time - choose Hold")

        val pos = acc.positions.find { it.symbol == ins.symbol && it.product == req.product }
        val p = pos?.qty ?: 0
        val q = signed(req.side, req.qty)
        val after = p + q
        if (after < 0 && m == Market.NSE && req.product == Product.HOLD)
            return reject("NSE allows short selling only intraday - choose Intraday, or you hold only ${max(p, 0)}")

        val live = quote?.takeIf { it.price > 0 }
        val ref = when (req.type) {
            OrderType.LIMIT -> req.limitPrice!!
            OrderType.STOP -> req.triggerPrice!!
            OrderType.MARKET -> live?.price ?: pos?.lastPrice ?: return reject("No price for ${ins.symbol} yet - wait a moment")
        }
        if (req.type == OrderType.STOP && live != null) {
            if (req.side == Side.BUY && req.triggerPrice!! <= live.price) return reject("A buy stop's trigger must be above the current price")
            if (req.side == Side.SELL && req.triggerPrice!! >= live.price) return reject("A sell stop's trigger must be below the current price")
        }
        val adds = addedQty(p, q)
        val longAfter = newQtySign(p, q) > 0
        if (adds > 0) {
            req.stopLoss?.let { sl ->
                if (longAfter && sl >= ref) return reject("Stop-loss must be below the entry price for a long")
                if (!longAfter && sl <= ref) return reject("Stop-loss must be above the entry price for a short")
            }
            req.target?.let { tg ->
                if (longAfter && tg <= ref) return reject("Target must be above the entry price for a long")
                if (!longAfter && tg >= ref) return reject("Target must be below the entry price for a short")
            }
            val need = adds * ref * marginRate(m, req.product, longAfter, s)
            val bp = buyingPower(acc, s)
            if (need > bp + 1e-6) return reject("Not enough buying power: needs ${Format.money(m, need)}, available ${Format.money(m, bp)}")
        }

        val order = Order(
            id = UUID.randomUUID().toString(), market = m, symbol = ins.symbol, key = ins.key, name = ins.name,
            side = req.side, qty = req.qty, type = req.type, product = req.product,
            limitPrice = req.limitPrice, triggerPrice = req.triggerPrice,
            stopLoss = if (adds > 0) req.stopLoss else null, target = if (adds > 0) req.target else null,
            strategy = req.strategy.trim(), note = req.note.trim(), createdAt = now,
            checkedUntil = (now / BAR + 1) * BAR,
        )
        var a = acc.copy(orders = acc.orders + order)
        // Instant fill while the market is open and the quote is fresh.
        val fresh = live != null && MarketHours.isOpen(m, now) && now - live.time <= s.quoteMaxAgeSec
        if (fresh) {
            val px = when (req.type) {
                OrderType.MARKET -> slipped(live!!.price, req.side, m, s)
                OrderType.LIMIT -> if ((req.side == Side.BUY && live!!.price <= req.limitPrice!!) ||
                    (req.side == Side.SELL && live!!.price >= req.limitPrice!!)) live.price else null
                OrderType.STOP -> null
            }
            if (px != null) {
                val (filled, ev) = fill(a, order, px, now)
                return PlaceResult(filled, filled.orders.first { it.id == order.id }, null, listOf(ev))
            }
        }
        a = equityPoint(a, now)
        return PlaceResult(a, order, null, listOf(EngineEvent(m, "${order.side} ${order.qty} ${order.symbol} placed - ${pendingText(order)}")))
    }

    private fun pendingText(o: Order) = when (o.type) {
        OrderType.MARKET -> "fills at the next open"
        OrderType.LIMIT -> "working at ${o.limitPrice}"
        OrderType.STOP -> "triggers at ${o.triggerPrice}"
    }

    fun cancel(acc: Account, orderId: String, reason: String = "Cancelled by you"): Account =
        acc.copy(orders = acc.orders.map { if (it.id == orderId && it.status == OrderStatus.OPEN) it.copy(status = OrderStatus.CANCELLED, reason = reason) else it })

    fun modifyExits(acc: Account, positionId: String, stopLoss: Double?, target: Double?): Pair<Account, String?> {
        val pos = acc.positions.find { it.id == positionId } ?: return acc to "Position not found"
        val px = pos.lastPrice
        if (pos.isLong) {
            if (stopLoss != null && stopLoss >= px) return acc to "Stop-loss must be below the current price"
            if (target != null && target <= px) return acc to "Target must be above the current price"
        } else {
            if (stopLoss != null && stopLoss <= px) return acc to "Stop-loss must be above the current price"
            if (target != null && target >= px) return acc to "Target must be below the current price"
        }
        val risk = if (pos.initialRisk > 0) pos.initialRisk else stopLoss?.let { abs(pos.avgPrice - it) } ?: 0.0
        return acc.copy(positions = acc.positions.map {
            if (it.id == positionId) it.copy(stopLoss = stopLoss, target = target, initialRisk = risk) else it
        }) to null
    }

    // ─── fills ─────────────────────────────────────────────────────────────

    /** Applies a fill of [order] at [price] (time [t]): cash, charges, position, closed trade. */
    fun fill(acc: Account, order: Order, price: Double, t: Long): Pair<Account, EngineEvent> {
        val m = acc.market
        val px = r2(price)
        val ch = Charges.of(m, order.side, order.product, order.qty, px).total
        val q = signed(order.side, order.qty)
        val cash = acc.cash + (if (order.side == Side.SELL) order.qty * px else -order.qty * px) - ch
        val done = order.copy(status = OrderStatus.FILLED, fillPrice = px, filledAt = t, charges = ch)
        val orders = acc.orders.map { if (it.id == order.id) done else it }.let { l -> if (l.any { it.id == order.id }) l else l + done }

        val pos = acc.positions.find { it.symbol == order.symbol && it.product == order.product }
        val positions = acc.positions.filter { it !== pos }.toMutableList()
        val trades = acc.trades.toMutableList()
        var realized = 0.0
        val p = pos?.qty ?: 0
        when {
            pos == null || p.sign == q.sign -> {
                val n = p + q
                val avg = if (pos == null) px else (abs(p) * pos.avgPrice + abs(q) * px) / abs(n)
                val sl = order.stopLoss ?: pos?.stopLoss
                val risk = when {
                    order.stopLoss != null -> abs(avg - order.stopLoss)
                    pos != null -> pos.initialRisk
                    else -> 0.0
                }
                positions += Position(
                    market = m, symbol = order.symbol, key = order.key, name = order.name, product = order.product,
                    qty = n, avgPrice = avg, openedAt = pos?.openedAt ?: t, stopLoss = sl, target = order.target ?: pos?.target,
                    strategy = pos?.strategy?.ifEmpty { order.strategy } ?: order.strategy,
                    note = pos?.note?.ifEmpty { order.note } ?: order.note,
                    entryCharges = (pos?.entryCharges ?: 0.0) + ch, lastPrice = px, initialRisk = risk,
                    checkedUntil = (t / BAR + 1) * BAR,
                )
            }
            else -> {
                val closeQty = min(abs(p), abs(q))
                val gross = closeQty * (px - pos.avgPrice) * p.sign
                val entryPart = pos.entryCharges * closeQty / abs(p)
                val exitPart = ch * closeQty / abs(q)
                realized = gross - entryPart - exitPart
                trades += ClosedTrade(
                    id = done.id, market = m, symbol = pos.symbol, name = pos.name, product = pos.product,
                    direction = if (p > 0) Side.BUY else Side.SELL, qty = closeQty, entryPrice = pos.avgPrice, exitPrice = px,
                    openedAt = pos.openedAt, closedAt = t, gross = gross, charges = entryPart + exitPart,
                    strategy = pos.strategy.ifEmpty { order.strategy }, exitReason = order.reason.ifEmpty { "MANUAL" },
                    initialRisk = pos.initialRisk, note = pos.note,
                )
                val n = p + q
                if (n.sign == p.sign && n != 0) {
                    positions += pos.copy(qty = n, entryCharges = pos.entryCharges - entryPart, lastPrice = px)
                } else if (n != 0) {   // flipped: the rest opens the other way
                    val rest = abs(n)
                    positions += Position(
                        market = m, symbol = order.symbol, key = order.key, name = order.name, product = order.product,
                        qty = n, avgPrice = px, openedAt = t, stopLoss = order.stopLoss, target = order.target,
                        strategy = order.strategy, note = order.note, entryCharges = ch * rest / abs(q), lastPrice = px,
                        initialRisk = order.stopLoss?.let { abs(px - it) } ?: 0.0, checkedUntil = (t / BAR + 1) * BAR,
                    )
                }
            }
        }
        val a = equityPoint(acc.copy(cash = cash, chargesPaid = acc.chargesPaid + ch, orders = orders, positions = positions, trades = trades), t)
        val reason = when (order.reason) {
            "SL" -> "🛑 Stop-loss hit · "
            "TARGET" -> "🎯 Target hit · "
            "SQUARE_OFF" -> "⏰ Squared off · "
            else -> ""
        }
        val pnl = if (pos != null && p.sign != q.sign) " · P&L ${Format.signedMoney(m, realized)}" else ""
        return a to EngineEvent(m, "$reason${order.side} ${order.qty} ${order.symbol} filled @ ${Format.price(m, px)}$pnl",
            if (pnl.isEmpty()) null else realized >= 0)
    }

    // ─── working orders, exits, square-off ─────────────────────────────────

    /** Live-quote pass (between bars): working orders and position exits that the latest price crossed. */
    fun onQuotes(acc: Account, quotes: Map<String, Quote>, now: Long, s: TradeSettings): Pair<Account, List<EngineEvent>> {
        var a = acc.copy(positions = acc.positions.map { p -> quotes[p.key]?.let { p.copy(lastPrice = it.price) } ?: p })
        val events = mutableListOf<EngineEvent>()
        if (!MarketHours.isOpen(a.market, now)) return equityPoint(a, now) to events
        for (o in a.orders.filter { it.status == OrderStatus.OPEN }) {
            val qt = quotes[o.key]?.takeIf { now - it.time <= s.quoteMaxAgeSec } ?: continue
            val px = qt.price
            val fillAt = when (o.type) {
                OrderType.MARKET -> slipped(px, o.side, a.market, s)
                OrderType.LIMIT -> if ((o.side == Side.BUY && px <= o.limitPrice!!) || (o.side == Side.SELL && px >= o.limitPrice!!)) px else null
                OrderType.STOP -> if ((o.side == Side.BUY && px >= o.triggerPrice!!) || (o.side == Side.SELL && px <= o.triggerPrice!!)) slipped(px, o.side, a.market, s) else null
            } ?: continue
            if (o.product == Product.INTRADAY && MarketHours.intradayClosed(a.market, now)) continue
            val (na, ev) = fill(a, o, fillAt, now); a = na; events += ev
        }
        for (p in a.positions.toList()) {
            val px = quotes[p.key]?.takeIf { now - it.time <= s.quoteMaxAgeSec }?.price ?: continue
            val hitSl = p.stopLoss != null && (if (p.isLong) px <= p.stopLoss else px >= p.stopLoss)
            val hitTg = p.target != null && (if (p.isLong) px >= p.target else px <= p.target)
            if (hitSl || hitTg) {
                val (na, ev) = exit(a, p, if (hitSl) slipped(px, exitSide(p), a.market, s) else px, now, if (hitSl) "SL" else "TARGET")
                a = na; events += ev
            }
        }
        return equityPoint(a, now) to events
    }

    private fun exitSide(p: Position) = if (p.isLong) Side.SELL else Side.BUY

    fun exit(acc: Account, p: Position, price: Double, t: Long, reason: String): Pair<Account, EngineEvent> {
        val o = Order(
            id = UUID.randomUUID().toString(), market = acc.market, symbol = p.symbol, key = p.key, name = p.name,
            side = exitSide(p), qty = abs(p.qty), type = if (reason == "TARGET") OrderType.LIMIT else OrderType.MARKET,
            product = p.product, limitPrice = if (reason == "TARGET") p.target else null, strategy = p.strategy,
            createdAt = t, reason = reason,
        )
        return fill(acc.copy(orders = acc.orders + o), o, price, t)
    }

    /**
     * Bar pass: [bars] = completed-or-forming 1-minute bars per instrument key (any order). Only
     * bars that have finished by [now] are used; each order / position remembers how far it got.
     */
    fun onBars(acc: Account, bars: Map<String, List<Candle>>, now: Long, s: TradeSettings): Pair<Account, List<EngineEvent>> {
        var a = acc
        val m = acc.market
        val events = mutableListOf<EngineEvent>()
        fun done(key: String, from: Long) = bars[key].orEmpty().filter { it.t >= from && it.t + BAR <= now }.sortedBy { it.t }

        for (o0 in a.orders.filter { it.status == OrderStatus.OPEN }.sortedBy { it.createdAt }) {
            var o = o0
            var filled = false
            for (b in done(o.key, o.checkedUntil)) {
                val day = MarketHours.localDate(m, b.t)
                if (o.product == Product.INTRADAY && b.t >= MarketHours.squareOffAt(m, day)) {
                    a = cancel(a, o.id, "Intraday order expired at the square-off time"); filled = true
                    events += EngineEvent(m, "${o.side} ${o.qty} ${o.symbol} (intraday) expired unfilled"); break
                }
                val px: Double? = when (o.type) {
                    OrderType.MARKET -> slipped(b.o, o.side, m, s)
                    OrderType.LIMIT -> when {
                        o.side == Side.BUY && b.l <= o.limitPrice!! -> min(o.limitPrice, b.o)
                        o.side == Side.SELL && b.h >= o.limitPrice!! -> max(o.limitPrice, b.o)
                        else -> null
                    }
                    OrderType.STOP -> when {
                        o.side == Side.BUY && b.h >= o.triggerPrice!! -> slipped(max(o.triggerPrice, b.o), o.side, m, s)
                        o.side == Side.SELL && b.l <= o.triggerPrice!! -> slipped(min(o.triggerPrice, b.o), o.side, m, s)
                        else -> null
                    }
                }
                if (px != null) {
                    val (na, ev) = fill(a, o, px, b.t + BAR / 2); a = na; events += ev; filled = true; break
                }
                o = o.copy(checkedUntil = b.t + BAR)
            }
            if (!filled) a = a.copy(orders = a.orders.map { if (it.id == o.id) it.copy(checkedUntil = o.checkedUntil) else it })
        }

        for (p0 in a.positions.toList()) {
            var p = p0
            var closed = false
            val bs = done(p.key, p.checkedUntil)
            for (b in bs) {
                val day = MarketHours.localDate(m, b.t)
                if (p.product == Product.INTRADAY && b.t >= MarketHours.squareOffAt(m, day)) {
                    val (na, ev) = exit(a, p, b.o, b.t, "SQUARE_OFF"); a = na; events += ev; closed = true; break
                }
                val long = p.isLong
                val sl = p.stopLoss?.let { if (long) b.l <= it else b.h >= it } ?: false
                val tg = p.target?.let { if (long) b.h >= it else b.l <= it } ?: false
                if (sl) {
                    val px = if (long) min(p.stopLoss!!, b.o) else max(p.stopLoss!!, b.o)
                    val (na, ev) = exit(a, p, slipped(px, exitSide(p), m, s), b.t + BAR / 2, "SL"); a = na; events += ev; closed = true; break
                }
                if (tg) {
                    val px = if (long) max(p.target!!, b.o) else min(p.target!!, b.o)
                    val (na, ev) = exit(a, p, px, b.t + BAR / 2, "TARGET"); a = na; events += ev; closed = true; break
                }
                p = p.copy(checkedUntil = b.t + BAR, lastPrice = b.c)
            }
            if (!closed) {
                // no bars past the square-off (data gap / holiday half-day): close at the last known price
                val sq = MarketHours.squareOffAt(m, MarketHours.localDate(m, p.openedAt))
                if (p.product == Product.INTRADAY && now >= sq + 15 * 60 && p.checkedUntil <= sq + BAR) {
                    val (na, ev) = exit(a, p, p.lastPrice, now, "SQUARE_OFF"); a = na; events += ev
                } else {
                    a = a.copy(positions = a.positions.map { if (it.id == p.id) p else it })
                }
            }
        }
        return equityPoint(a, now) to events
    }

    /** One equity point per 15 minutes (the latest value wins inside the window); history is never dropped -
     *  see [compactEquity]. */
    fun equityPoint(acc: Account, t: Long): Account {
        val e = equity(acc)
        val last = acc.equity.lastOrNull()
        val pts = if (last != null && t - last.t < 15 * 60 && t >= last.t) acc.equity.dropLast(1) + EquityPoint(last.t, e)
        else acc.equity + EquityPoint(t, e)
        return acc.copy(equity = compactEquity(pts, acc.market, t))
    }

    /** Full 15-minute detail for the last [detailDays] days; older history keeps the very first point and
     *  the last point of each day (the day's closing equity), so the curve always reaches back to the start. */
    fun compactEquity(pts: List<EquityPoint>, m: Market, now: Long, detailDays: Long = 30): List<EquityPoint> {
        val cut = now - detailDays * 86400
        if (pts.isEmpty() || pts.first().t >= cut) return pts
        val old = pts.filter { it.t < cut }
        val daily = old.groupBy { MarketHours.localDate(m, it.t) }.values.map { it.last() }
        return (listOf(old.first()) + daily).distinctBy { it.t }.sortedBy { it.t } + pts.filter { it.t >= cut }
    }

    fun hasWork(acc: Account) = acc.orders.any { it.status == OrderStatus.OPEN } ||
        acc.positions.any { it.stopLoss != null || it.target != null || it.product == Product.INTRADAY }
}
