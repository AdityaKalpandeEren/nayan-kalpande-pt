package com.kalpande.nkpt.data

import com.kalpande.nkpt.domain.TradeSettings
import kotlinx.serialization.Serializable

/**
 * Full backup of the app's paper-trading data, one JSON file (format 1):
 *
 *   app, format, exportedAt (epoch s), appVersion
 *   trade     - fill model (slippage bps, margins)
 *   accounts  - one per market (NSE, US): cash, startCash, chargesPaid,
 *               orders[]     every order ever placed (status OPEN / FILLED / CANCELLED / REJECTED, fill price & time, charges, reason)
 *               positions[]  open positions (signed qty, avg price, stop-loss, target, strategy)
 *               trades[]     every closed trade (direction, qty, entry / exit price & time, gross, charges, strategy,
 *                            exitReason MANUAL / SL / TARGET / SQUARE_OFF, initialRisk for R multiples, note)
 *               equity[]     equity curve points (t, equity)
 *               watchlist[], strategies[]
 *
 * All times are epoch seconds (UTC); prices in the market's currency. The Upstox token is never included.
 * Readable from Python with plain json.load - e.g. to study which tagged strategies work before
 * automating them in the US / NSE alert bots.
 */
@Serializable
data class BackupFile(
    val app: String = APP,
    val format: Int = FORMAT,
    val exportedAt: Long,
    val appVersion: String,
    val trade: TradeSettings? = null,
    val accounts: List<Account>,
) {
    companion object {
        const val APP = "nayan-kalpande-pt"
        const val FORMAT = 1

        fun parse(text: String): Result<BackupFile> {
            val b = try {
                Http.json.decodeFromString(serializer(), text)
            } catch (e: Exception) {
                return Result.failure(IllegalArgumentException("Couldn't read the file - is it a Nayan Kalpande PT backup?"))
            }
            return runCatching {
                require(b.app == APP) { "This isn't a Nayan Kalpande PT backup" }
                require(b.format <= FORMAT) { "Backup is from a newer app version (format ${b.format}) - update the app first" }
                require(b.accounts.isNotEmpty()) { "The backup has no accounts" }
                require(b.accounts.map { it.market }.toSet().size == b.accounts.size) { "The backup lists a market twice" }
                b.accounts.forEach { a ->
                    require(a.orders.all { it.market == a.market } && a.trades.all { it.market == a.market }) {
                        "The ${a.market} account contains trades of another market"
                    }
                }
                b
            }
        }
    }

    fun encode(): String = Http.json.encodeToString(serializer(), this)

    fun summary(): String = accounts.joinToString("\n") { a ->
        "${a.market.flag} ${a.market.label}: ${a.trades.size} closed trades, ${a.orders.size} orders, ${a.positions.size} open positions, " +
            "${a.watchlist.size} watchlist stocks"
    }
}
