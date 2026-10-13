package com.kalpande.nkpt.data

import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.EngineEvent
import com.kalpande.nkpt.domain.PlaceResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.math.abs

data class RefreshResult(val account: Account, val quotes: Map<String, Quote>, val events: List<EngineEvent>)

/** Data + engine + storage: one place that the screens and the background worker both call. */
class Broker(val store: AccountStore, val data: MarketData, val settings: SettingsRepository) {
    private fun now() = System.currentTimeMillis() / 1000

    /** Called after every change worth saving online (the cloud sync hooks in here). */
    @Volatile var onChange: (Market, String) -> Unit = { _, _ -> }

    private fun Account.touched() = copy(updatedAt = maxOf(now(), updatedAt + 1))

    /** [store.update] that stamps the account as changed and tells the sync. */
    private suspend fun <T> change(m: Market, why: String, block: suspend (Account) -> Pair<Account, T>): T {
        val r = store.update(m) { a -> block(a).let { (n, v) -> (if (n == a) n else n.touched()) to v } }
        onChange(m, why)
        return r
    }

    /**
     * Quotes for the watchlist, positions and working orders (+ [extra]), then the live-quote pass;
     * with [bars] also the 1-minute bar pass for every instrument with a working order or exit.
     */
    suspend fun refresh(m: Market, extra: Collection<String> = emptyList(), bars: Boolean = true): RefreshResult {
        val s = settings.tradeNow()
        val a0 = store.load(m)
        val keys = (a0.watchlist.map { it.key } + a0.positions.map { it.key } +
            a0.orders.filter { it.status == OrderStatus.OPEN }.map { it.key } + extra).toSet()
        val quotes = data.quotes(m, keys)
        val barMap: Map<String, List<Candle>> = if (bars && Engine.hasWork(a0)) coroutineScope {
            val since = mutableMapOf<String, Long>()
            a0.orders.filter { it.status == OrderStatus.OPEN }.forEach { since.merge(it.key, it.checkedUntil, ::minOf) }
            a0.positions.forEach { since.merge(it.key, it.checkedUntil, ::minOf) }
            since.map { (k, t) -> async { k to runCatching { data.bars(m, k, t) }.getOrDefault(emptyList()) } }.awaitAll().toMap()
        } else emptyMap()
        val t = now()
        val (acc, events) = store.update(m) { a ->
            var x = a
            val ev = mutableListOf<EngineEvent>()
            if (barMap.isNotEmpty()) Engine.onBars(x, barMap, t, s).let { (n, e) -> x = n; ev += e }
            Engine.onQuotes(x, quotes, t, s).let { (n, e) -> x = n; ev += e }
            val y = if (ev.isNotEmpty()) x.touched() else x
            y to (y to ev)
        }
        if (events.isNotEmpty()) onChange(m, events.joinToString("; ") { it.text })
        return RefreshResult(acc, quotes, events)
    }

    suspend fun place(m: Market, req: OrderRequest): PlaceResult {
        val s = settings.tradeNow()
        val q = runCatching { data.quotes(m, listOf(req.instrument.key))[req.instrument.key] }.getOrNull()
        return change(m, "${req.side} ${req.qty} ${req.instrument.symbol} ${req.type}") { a ->
            val r = Engine.place(a, req, q, now(), s)
            r.account to r
        }
    }

    suspend fun cancel(m: Market, orderId: String) = change(m, "cancel order") { a -> Engine.cancel(a, orderId) to Unit }

    suspend fun exit(m: Market, position: Position): PlaceResult {
        val ins = Instrument(m, position.symbol, position.name, position.key)
        return place(m, OrderRequest(ins, if (position.isLong) Side.SELL else Side.BUY, abs(position.qty), OrderType.MARKET, position.product,
            strategy = position.strategy))
    }

    suspend fun modifyExits(m: Market, positionId: String, sl: Double?, target: Double?): String? =
        change(m, "edit SL/target $positionId") { a -> Engine.modifyExits(a, positionId, sl, target) }

    suspend fun setWatchlist(m: Market, items: List<Instrument>) = change(m, "watchlist") { a -> a.copy(watchlist = items) to Unit }

    suspend fun addStrategy(m: Market, name: String) = change(m, "strategy $name") { a ->
        (if (name.isBlank() || name in a.strategies) a else a.copy(strategies = a.strategies + name.trim())) to Unit
    }

    suspend fun backup(appVersion: String): BackupFile =
        BackupFile(exportedAt = now(), appVersion = appVersion, trade = settings.tradeNow(), accounts = Market.entries.map { store.load(it) })

    /** Replaces the accounts listed in [b] (and the fill settings); markets not in the file are left as they are. */
    suspend fun restore(b: BackupFile) {
        b.accounts.forEach { acc -> change(acc.market, "restore from backup") { acc.copy(updatedAt = 0) to Unit } }
        b.trade?.let { settings.setTrade(it) }
    }

    suspend fun reset(m: Market) = change(m, "reset account") { a ->
        Account(m, createdAt = now(), watchlist = a.watchlist, strategies = a.strategies) to Unit
    }
}
