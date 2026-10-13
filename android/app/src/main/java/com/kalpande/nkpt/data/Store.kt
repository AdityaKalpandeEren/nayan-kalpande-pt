package com.kalpande.nkpt.data

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kalpande.nkpt.domain.TradeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The paper accounts, one JSON file per market, written atomically. Shared by the UI and the worker. */
class AccountStore(private val dir: File) {
    private val lock = Mutex()

    private fun file(m: Market) = File(dir, "account_$m.json")

    suspend fun load(m: Market): Account = withContext(Dispatchers.IO) {
        runCatching { Http.json.decodeFromString(Account.serializer(), file(m).readText()) }.getOrNull()
            ?: Account(m, createdAt = System.currentTimeMillis() / 1000)
    }

    suspend fun save(a: Account) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val tmp = File(dir, "account_${a.market}.json.tmp")
        tmp.writeText(Http.json.encodeToString(Account.serializer(), a))
        if (!tmp.renameTo(file(a.market))) {
            file(a.market).delete(); tmp.renameTo(file(a.market))
        }
    }

    /** Load -> change -> save under one lock (UI actions and the background worker never interleave). */
    suspend fun <T> update(m: Market, block: suspend (Account) -> Pair<Account, T>): T = lock.withLock {
        val (a, r) = block(load(m))
        save(a)
        r
    }
}

private val Context.prefs by preferencesDataStore("settings")

class SettingsRepository(private val ctx: Context) {
    private object K {
        val token = stringPreferencesKey("upstox_token")
        val slipUs = doublePreferencesKey("slip_us")
        val slipNse = doublePreferencesKey("slip_nse")
        val nseMis = doublePreferencesKey("nse_intraday_margin")
        val usShort = doublePreferencesKey("us_short_margin")
        val market = stringPreferencesKey("market")
    }

    val token: Flow<String> = ctx.prefs.data.map { it[K.token].orEmpty() }
    val market: Flow<Market> = ctx.prefs.data.map { p -> p[K.market]?.let { runCatching { Market.valueOf(it) }.getOrNull() } ?: Market.NSE }
    val trade: Flow<TradeSettings> = ctx.prefs.data.map { p ->
        TradeSettings(
            slippageBpsUs = p[K.slipUs] ?: 2.0,
            slippageBpsNse = p[K.slipNse] ?: 5.0,
            nseIntradayMargin = p[K.nseMis] ?: 0.2,
            usShortMargin = p[K.usShort] ?: 1.5,
        )
    }

    suspend fun tokenNow() = token.first()
    suspend fun tradeNow() = trade.first()

    suspend fun setToken(t: String) = ctx.prefs.edit { it[K.token] = t.trim() }
    suspend fun setMarket(m: Market) = ctx.prefs.edit { it[K.market] = m.name }
    suspend fun setTrade(s: TradeSettings) = ctx.prefs.edit {
        it[K.slipUs] = s.slippageBpsUs; it[K.slipNse] = s.slippageBpsNse
        it[K.nseMis] = s.nseIntradayMargin; it[K.usShort] = s.usShortMargin
    }
}
