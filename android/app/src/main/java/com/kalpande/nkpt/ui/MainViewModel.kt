package com.kalpande.nkpt.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kalpande.nkpt.BuildConfig
import com.kalpande.nkpt.NkptApp
import com.kalpande.nkpt.data.BackupFile
import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.Position
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Timeframe
import com.kalpande.nkpt.domain.EngineEvent
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.domain.TradeSettings
import com.kalpande.nkpt.work.FillWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class UiState(
    val market: Market = Market.NSE,
    val accounts: Map<Market, Account> = Market.entries.associateWith { Account(it) },
    val quotes: Map<String, Quote> = emptyMap(),
    val instrumentCount: Map<Market, Int> = emptyMap(),
    val trade: TradeSettings = TradeSettings(),
    val token: String = "",
    val lastRefresh: Long = 0,
    val refreshing: Boolean = false,
    val message: String? = null,
) {
    val account: Account get() = accounts.getValue(market)
}

data class SearchState(
    val query: String = "",
    val items: List<Instrument> = emptyList(),
    val total: Int = 0,
    val page: Int = -1,
    val loading: Boolean = false,
    val listReady: Boolean = false,
)

data class ChartState(val key: String = "", val tf: Timeframe = Timeframe.M5, val candles: List<Candle> = emptyList(), val loading: Boolean = false, val error: String? = null)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val nk = app as NkptApp
    private val broker = nk.broker
    private val instruments = nk.instruments

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()
    private val _chart = MutableStateFlow(ChartState())
    val chart: StateFlow<ChartState> = _chart.asStateFlow()

    private var loop: Job? = null

    /** Tests / screenshots: no network from open() and the chart. */
    var offline = false
    private var watching: Instrument? = null

    init {
        viewModelScope.launch {
            val m = nk.settings.market.first()
            _ui.update { it.copy(market = m, trade = nk.settings.tradeNow(), token = nk.settings.tokenNow()) }
            reloadAccounts()
        }
        Market.entries.forEach { m ->
            viewModelScope.launch {
                instruments.ensure(m)
                _ui.update { it.copy(instrumentCount = it.instrumentCount + (m to instruments.count(m))) }
                if (m == _ui.value.market) runSearch(_search.value.query, reset = true)
            }
        }
    }

    private suspend fun reloadAccounts() {
        val acc = Market.entries.associateWith { broker.store.load(it) }
        _ui.update { it.copy(accounts = acc) }
    }

    // ─── refresh loop (while the app is in the foreground) ───────────────

    fun start() {
        if (offline || loop?.isActive == true) return
        loop = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val m = _ui.value.market
                refreshMarket(m, bars = tick % 12 == 0)
                if (tick % 12 == 6) refreshMarket(Market.entries.first { it != m }, bars = true)
                tick++
                val slow = m == Market.NSE && _ui.value.token.isBlank()
                delay(if (slow) 12_000 else 5_000)
            }
        }
    }

    fun stop() { loop?.cancel(); loop = null }

    fun refreshNow() = viewModelScope.launch {
        _ui.update { it.copy(refreshing = true) }
        refreshMarket(_ui.value.market, bars = true)
        _ui.update { it.copy(refreshing = false) }
    }

    private suspend fun refreshMarket(m: Market, bars: Boolean) {
        val extra = listOfNotNull(watching?.takeIf { it.market == m }?.key)
        runCatching { broker.refresh(m, extra, bars) }.onSuccess { r ->
            _ui.update { it.copy(accounts = it.accounts + (m to r.account), quotes = it.quotes + r.quotes, lastRefresh = System.currentTimeMillis() / 1000) }
            announce(r.events)
        }
    }

    private fun announce(events: List<EngineEvent>) {
        if (events.isEmpty()) return
        _ui.update { it.copy(message = events.joinToString("\n") { e -> e.text }) }
        FillWorker.notify(getApplication(), events.filter { "Stop-loss" in it.text || "Target" in it.text || "Squared" in it.text })
    }

    fun consumeMessage() = _ui.update { it.copy(message = null) }
    private fun toast(s: String) = _ui.update { it.copy(message = s) }

    fun setMarket(m: Market) {
        _ui.update { it.copy(market = m) }
        viewModelScope.launch { nk.settings.setMarket(m); refreshMarket(m, bars = false) }
        runSearch(_search.value.query, reset = true)
    }

    // ─── search (paged) ──────────────────────────────────────────────────

    fun setQuery(q: String) = runSearch(q, reset = true)

    fun loadMore() {
        val s = _search.value
        if (s.loading || (s.page + 1) * PAGE >= s.total) return
        runSearch(s.query, reset = false)
    }

    private fun runSearch(q: String, reset: Boolean) {
        val m = _ui.value.market
        val page = if (reset) 0 else _search.value.page + 1
        _search.update { it.copy(query = q, loading = true) }
        viewModelScope.launch {
            var p = instruments.search(m, q, page, PAGE)
            var items = if (reset) p.items else _search.value.items + p.items
            var total = p.total
            if (reset && total == 0 && m == Market.US && q.length >= 2) {
                val extra = broker.data.yahooSearch(q)
                items = extra; total = extra.size
            }
            if (_search.value.query == q) {
                _search.update { SearchState(q, items, total, page, false, instruments.count(m) > 0) }
            }
        }
    }

    // ─── watchlist ───────────────────────────────────────────────────────

    fun isWatched(i: Instrument) = _ui.value.accounts[i.market]?.watchlist?.any { it.key == i.key } == true

    fun toggleWatch(i: Instrument) = viewModelScope.launch {
        val wl = _ui.value.accounts.getValue(i.market).watchlist
        val next = if (wl.any { it.key == i.key }) wl.filterNot { it.key == i.key } else wl + i
        broker.setWatchlist(i.market, next)
        reloadAccounts()
        if (next.size > wl.size) {
            toast("${i.symbol} added to your ${i.market.label} watchlist")
            refreshMarket(i.market, bars = false)
        }
    }

    fun moveWatch(i: Instrument, up: Boolean) = viewModelScope.launch {
        val wl = _ui.value.accounts.getValue(i.market).watchlist.toMutableList()
        val idx = wl.indexOfFirst { it.key == i.key }
        val j = if (up) idx - 1 else idx + 1
        if (idx < 0 || j !in wl.indices) return@launch
        wl[idx] = wl[j].also { wl[j] = wl[idx] }
        broker.setWatchlist(i.market, wl)
        reloadAccounts()
    }

    // ─── instrument detail ───────────────────────────────────────────────

    fun open(i: Instrument) {
        watching = i
        if (offline) return
        loadChart(i, _chart.value.tf.takeIf { _chart.value.key == i.key } ?: if (i.tradable) Timeframe.M5 else Timeframe.D1)
        viewModelScope.launch { refreshMarket(i.market, bars = false) }
    }

    fun close() { watching = null }

    fun seedChart(c: ChartState) { _chart.value = c }

    fun seedUi(u: UiState) { _ui.value = u }

    fun loadChart(i: Instrument, tf: Timeframe) {
        _chart.value = ChartState(i.key, tf, if (_chart.value.key == i.key && _chart.value.tf == tf) _chart.value.candles else emptyList(), loading = true)
        viewModelScope.launch {
            val r = runCatching { broker.data.candles(i, tf) }
            if (_chart.value.key == i.key && _chart.value.tf == tf) {
                _chart.value = ChartState(i.key, tf, r.getOrDefault(_chart.value.candles), false,
                    r.exceptionOrNull()?.let { "Couldn't load candles: ${it.message}" })
            }
        }
    }

    /** Exact-ticker lookup (popular quick-add chips). */
    suspend fun instrumentFor(m: Market, symbol: String): Instrument? {
        instruments.ensure(m)
        return instruments.search(m, symbol, 0, 5).items.firstOrNull { it.symbol == symbol }
    }

    fun findInstrument(m: Market, key: String): Instrument? =
        instruments.byKey(m, key) ?: _ui.value.accounts[m]?.let { a ->
            a.watchlist.find { it.key == key } ?: a.positions.find { it.key == key }?.let { Instrument(m, it.symbol, it.name, it.key) }
                ?: a.orders.find { it.key == key }?.let { Instrument(m, it.symbol, it.name, it.key) }
        } ?: _search.value.items.find { it.key == key }

    // ─── trading ─────────────────────────────────────────────────────────

    /** Returns null on success, else the rejection message (shown in the ticket). */
    suspend fun place(req: OrderRequest): String? {
        val m = req.instrument.market
        val r = broker.place(m, req)
        if (r.error != null) return r.error
        _ui.update { it.copy(accounts = it.accounts + (m to r.account)) }
        announce(r.events)
        return null
    }

    fun cancel(m: Market, orderId: String) = viewModelScope.launch { broker.cancel(m, orderId); reloadAccounts(); toast("Order cancelled") }

    fun exit(p: Position) = viewModelScope.launch {
        val r = broker.exit(p.market, p)
        if (r.error != null) toast(r.error) else {
            _ui.update { it.copy(accounts = it.accounts + (p.market to r.account)) }
            announce(r.events)
        }
    }

    suspend fun modifyExits(p: Position, sl: Double?, target: Double?): String? {
        val e = broker.modifyExits(p.market, p.id, sl, target)
        reloadAccounts()
        return e
    }

    fun addStrategy(m: Market, name: String) = viewModelScope.launch { broker.addStrategy(m, name); reloadAccounts() }

    fun reset(m: Market) = viewModelScope.launch {
        broker.reset(m); reloadAccounts()
        toast("${m.label} account reset to ${Format.money(m, m.startCash)}")
    }

    // ─── settings ────────────────────────────────────────────────────────

    fun setToken(t: String) = viewModelScope.launch {
        nk.settings.setToken(t); nk.upstoxToken = t.trim()
        _ui.update { it.copy(token = t.trim()) }
        toast(if (t.isBlank()) "Upstox token removed - NSE prices from 1-minute candles" else "Upstox token saved - live NSE quotes on")
        refreshMarket(Market.NSE, bars = false)
    }

    fun setTrade(s: TradeSettings) = viewModelScope.launch { nk.settings.setTrade(s); _ui.update { it.copy(trade = s) } }

    // ─── backup / restore ────────────────────────────────────────────────

    private val _restore = MutableStateFlow<BackupFile?>(null)
    /** A parsed backup waiting for the user's confirmation. */
    val pendingRestore: StateFlow<BackupFile?> = _restore.asStateFlow()

    fun backupTo(uri: Uri) = viewModelScope.launch {
        val r = runCatching {
            val b = broker.backup(BuildConfig.VERSION_NAME)
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt")!!.use { it.write(b.encode().toByteArray()) }
            }
            b
        }
        toast(r.fold({ "Backup saved - ${it.accounts.sumOf { a -> a.trades.size }} trades, ${it.accounts.sumOf { a -> a.orders.size }} orders" },
            { "Backup failed: ${it.message}" }))
    }

    fun readBackup(uri: Uri) = viewModelScope.launch {
        val text = runCatching {
            withContext(Dispatchers.IO) { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
        }.getOrElse { toast("Couldn't open the file: ${it.message}"); return@launch }
        BackupFile.parse(text).fold({ _restore.value = it }, { toast(it.message ?: "Not a valid backup") })
    }

    fun cancelRestore() { _restore.value = null }

    fun confirmRestore() = viewModelScope.launch {
        val b = _restore.value ?: return@launch
        _restore.value = null
        broker.restore(b)
        reloadAccounts()
        _ui.update { it.copy(trade = b.trade ?: it.trade) }
        toast("Restored:\n${b.summary()}")
        b.accounts.forEach { refreshMarket(it.market, bars = false) }
    }

    // ─── export ──────────────────────────────────────────────────────────

    fun exportCsv(m: Market): Intent {
        val a = _ui.value.accounts.getValue(m)
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        val f = File(dir, "nkpt_${m.label.lowercase()}_trades.csv")
        f.writeText(buildString {
            appendLine("closed_at,opened_at,symbol,product,direction,qty,entry,exit,gross,charges,net,r_multiple,strategy,exit_reason,hold_minutes,note")
            a.trades.sortedBy { it.closedAt }.forEach { t ->
                appendLine(listOf(Format.time(m, t.closedAt, "yyyy-MM-dd HH:mm"), Format.time(m, t.openedAt, "yyyy-MM-dd HH:mm"), t.symbol,
                    t.product, if (t.direction.name == "BUY") "LONG" else "SHORT", t.qty, t.entryPrice, t.exitPrice,
                    "%.2f".format(t.gross), "%.2f".format(t.charges), "%.2f".format(t.net), t.rMultiple?.let { "%.2f".format(it) } ?: "",
                    "\"${t.strategy}\"", t.exitReason, t.holdSeconds / 60, "\"${t.note.replace("\"", "'")}\"").joinToString(","))
            }
        })
        val uri = FileProvider.getUriForFile(getApplication(), getApplication<Application>().packageName + ".files", f)
        return Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).let { Intent.createChooser(it, "Export ${m.label} trades") }
    }

    companion object { const val PAGE = 30 }
}
