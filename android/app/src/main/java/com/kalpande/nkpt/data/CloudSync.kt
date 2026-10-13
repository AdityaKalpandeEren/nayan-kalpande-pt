package com.kalpande.nkpt.data

import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.domain.MarketHours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** GitHub as the database: read one file, or write several files in ONE commit (Git Data API). */
class GitHubStore(private val token: suspend () -> String, private val repo: suspend () -> String) {
    private val api = "https://api.github.com"
    private suspend fun h() = mapOf(
        "Authorization" to "Bearer ${token().trim()}", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28",
    )

    class GitHubError(msg: String) : Exception(msg)

    private fun obj(body: String): JsonObject = Http.json.parseToJsonElement(body).jsonObject

    private suspend fun fail(code: Int, body: String, what: String): Nothing {
        val r = repo()
        val msg = runCatching { obj(body)["message"]?.jsonPrimitive?.content }.getOrNull() ?: body.take(120)
        throw GitHubError(when (code) {
            401 -> "GitHub token is invalid or expired"
            403 -> "GitHub refused ($msg) - the token needs Contents: read and write on $r"
            404 -> "Repo $r not found or the token can't see it"
            else -> "$what failed: HTTP $code $msg"
        })
    }

    /** The token owner's login - used as the user's folder name. */
    suspend fun login(): String {
        val (c, b) = Http.send("GET", "$api/user", headers = h())
        if (c != 200) fail(c, b, "Sign-in")
        val (rc, rb) = Http.send("GET", "$api/repos/${repo()}", headers = h())
        if (rc != 200) fail(rc, rb, "Repo check")
        return obj(b)["login"]!!.jsonPrimitive.content
    }

    /** File text, or null if it doesn't exist yet. */
    suspend fun read(path: String): String? {
        val (c, b) = Http.send("GET", "$api/repos/${repo()}/contents/$path?ref=main", headers = h() + ("Accept" to "application/vnd.github.raw+json"))
        if (c == 404) return null
        if (c != 200) fail(c, b, "Read $path")
        return b
    }

    /** Writes [files] (path -> text) in a single commit on main; retries on top of newer commits. Returns the commit sha. */
    suspend fun commit(files: Map<String, String>, message: String): String {
        repeat(4) {
            val (rc, rb) = Http.send("GET", "$api/repos/${repo()}/git/ref/heads/main", headers = h())
            if (rc != 200) fail(rc, rb, "Read branch")
            val head = obj(rb)["object"]!!.jsonObject["sha"]!!.jsonPrimitive.content
            val (cc, cb) = Http.send("GET", "$api/repos/${repo()}/git/commits/$head", headers = h())
            if (cc != 200) fail(cc, cb, "Read commit")
            val baseTree = obj(cb)["tree"]!!.jsonObject["sha"]!!.jsonPrimitive.content
            val tree = buildJsonObject {
                put("base_tree", baseTree)
                putJsonArray("tree") {
                    files.forEach { (p, text) -> add(buildJsonObject { put("path", p); put("mode", "100644"); put("type", "blob"); put("content", text) }) }
                }
            }
            val (tc, tb) = Http.send("POST", "$api/repos/${repo()}/git/trees", tree.toString(), h())
            if (tc != 201) fail(tc, tb, "Create tree")
            val commit = buildJsonObject {
                put("message", message); put("tree", obj(tb)["sha"]!!.jsonPrimitive.content)
                putJsonArray("parents") { add(head) }
            }
            val (kc, kb) = Http.send("POST", "$api/repos/${repo()}/git/commits", commit.toString(), h())
            if (kc != 201) fail(kc, kb, "Create commit")
            val sha = obj(kb)["sha"]!!.jsonPrimitive.content
            val (uc, ub) = Http.send("PATCH", "$api/repos/${repo()}/git/refs/heads/main", buildJsonObject { put("sha", sha); put("force", false) }.toString(), h())
            if (uc == 200) return sha
            if (uc != 422 && uc != 409) fail(uc, ub, "Update branch")
            delay(700)                       // someone else committed first: rebuild on top of it
        }
        throw GitHubError("GitHub branch kept moving - will retry")
    }
}

data class SyncStatus(
    val enabled: Boolean = false,
    val user: String = "",
    val repo: String = "",
    val lastPush: Long = 0,
    val lastPull: Long = 0,
    val error: String? = null,
    val pushing: Boolean = false,
)

/**
 * Online storage of the paper accounts in a GitHub repo (default AdityaKalpandeEren/nkpt-data), one folder
 * per user (the GitHub login of the token):
 *
 *   users/<login>/<NSE|US>/state.json          full account - what the app loads (source of truth)
 *   users/<login>/<NSE|US>/trades.csv          every closed trade, research-ready
 *   users/<login>/<NSE|US>/orders.csv          every order
 *   users/<login>/<NSE|US>/daily/<date>.json   that day: orders, fills, closed trades, positions, cash, equity
 *
 * Every change (order, fill, exit, SL/target edit, watchlist, strategies, reset, restore) is committed ~2 s
 * later as ONE commit with all four files; after the close an end-of-day commit records the final equity.
 * A failed push is remembered and retried on the next refresh. On start the remote state wins when it is
 * newer (new phone / reinstall / another device).
 */
class CloudSync(
    private val store: AccountStore,
    private val settings: SettingsRepository,
    private val gh: GitHubStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val pending = mutableMapOf<Market, Job>()
    private val _status = MutableStateFlow(SyncStatus())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val pretty = Json(from = Http.json) { prettyPrint = true }

    suspend fun init() {
        val c = settings.cloud()
        _status.value = SyncStatus(enabled = c.token.isNotBlank() && c.user.isNotBlank(), user = c.user, repo = c.repo)
    }

    private suspend fun ready() = settings.cloud().let { it.token.isNotBlank() && it.user.isNotBlank() }

    /** Checks the token + repo, remembers the user, pulls anything newer from GitHub, then pushes local data. */
    suspend fun connect(token: String, repo: String): Result<String> = runCatching {
        settings.setCloud(token.trim(), repo.trim(), "")
        val login = gh.login()
        settings.setCloud(token.trim(), repo.trim(), login)
        _status.value = SyncStatus(enabled = true, user = login, repo = repo.trim())
        pull()
        Market.entries.forEach { push(it, "connected from the app") }
        login
    }.onFailure { e -> _status.update { it.copy(error = e.message) } }

    suspend fun disconnect() {
        settings.setCloud("", settings.cloud().repo, "")
        _status.value = SyncStatus(repo = settings.cloud().repo)
    }

    /** Remote state wins when it is newer than the phone's. Returns the markets that changed. */
    suspend fun pull(): List<Market> {
        if (!ready()) return emptyList()
        val user = settings.cloud().user
        val changed = mutableListOf<Market>()
        runCatching {
            for (m in Market.entries) {
                val text = gh.read("users/$user/$m/state.json") ?: continue
                val remote = Http.json.decodeFromString(Account.serializer(), text)
                if (remote.market != m) continue
                store.replaceIfNewer(remote)?.let { changed += m; settings.setDirty(m, false) }
            }
            _status.update { it.copy(lastPull = now(), error = null) }
        }.onFailure { e -> _status.update { it.copy(error = "Pull failed: ${e.message}") } }
        return changed
    }

    /** Debounced push (2 s), so a burst of fills becomes one commit. */
    fun request(m: Market, reason: String) {
        scope.launch {
            if (!ready()) return@launch
            settings.setDirty(m, true)
            synchronized(pending) { pending[m]?.cancel() }
            val job = scope.launch { delay(2_000); push(m, reason) }
            synchronized(pending) { pending[m] = job }
        }
    }

    /** Retries an earlier failed push and writes the end-of-day file once the session is over. */
    suspend fun maintain() {
        if (!ready()) return
        val t = now()
        for (m in Market.entries) {
            val day = MarketHours.localDate(m, t).toString()
            val eodDue = MarketHours.isTradingDay(MarketHours.localDate(m, t)) && t >= MarketHours.closeAt(m, MarketHours.localDate(m, t)) &&
                settings.lastEod(m) != day && store.load(m).let { it.orders.isNotEmpty() || it.positions.isNotEmpty() }
            when {
                eodDue -> if (push(m, "end of day $day")) settings.setLastEod(m, day)
                settings.dirty(m) -> push(m, "sync")
            }
        }
    }

    /** One commit with state.json, trades.csv, orders.csv and today's daily file. */
    suspend fun push(m: Market, reason: String): Boolean = lock.withLock {
        if (!ready()) return false
        val user = settings.cloud().user
        _status.update { it.copy(pushing = true) }
        val r = runCatching {
            val a = store.load(m)
            val base = "users/$user/$m"
            val day = MarketHours.localDate(m, now()).toString()
            gh.commit(
                mapOf(
                    "$base/state.json" to pretty.encodeToString(Account.serializer(), a),
                    "$base/trades.csv" to tradesCsv(a),
                    "$base/orders.csv" to ordersCsv(a),
                    "$base/daily/$day.json" to dailyJson(a, day),
                ),
                "$user ${m.label}: ${reason.take(90)}",
            )
        }
        r.onSuccess { settings.setDirty(m, false); _status.update { it.copy(lastPush = now(), error = null, pushing = false) } }
            .onFailure { e -> _status.update { it.copy(error = e.message, pushing = false) } }
        r.isSuccess
    }

    private fun now() = System.currentTimeMillis() / 1000

    companion object {
        private fun q(s: String) = "\"" + s.replace("\"", "'") + "\""

        fun tradesCsv(a: Account): String = buildString {
            appendLine("trade_id,market,symbol,product,direction,qty,entry_price,exit_price,opened_at,closed_at,opened_local,closed_local,hold_minutes,gross,charges,net,r_multiple,initial_risk,strategy,exit_reason,note")
            a.trades.sortedBy { it.closedAt }.forEach { t ->
                appendLine(listOf(t.id, t.market, t.symbol, t.product, if (t.direction == Side.BUY) "LONG" else "SHORT", t.qty, t.entryPrice, t.exitPrice,
                    t.openedAt, t.closedAt, Format.time(a.market, t.openedAt, "yyyy-MM-dd HH:mm"), Format.time(a.market, t.closedAt, "yyyy-MM-dd HH:mm"),
                    t.holdSeconds / 60, "%.2f".format(t.gross), "%.2f".format(t.charges), "%.2f".format(t.net), t.rMultiple?.let { "%.3f".format(it) } ?: "",
                    "%.4f".format(t.initialRisk), q(t.strategy), t.exitReason, q(t.note)).joinToString(","))
            }
        }

        fun ordersCsv(a: Account): String = buildString {
            appendLine("order_id,market,symbol,side,qty,type,product,limit_price,trigger_price,stop_loss,target,status,fill_price,created_at,filled_at,created_local,charges,strategy,reason,note")
            a.orders.sortedBy { it.createdAt }.forEach { o ->
                appendLine(listOf(o.id, o.market, o.symbol, o.side, o.qty, o.type, o.product, o.limitPrice ?: "", o.triggerPrice ?: "", o.stopLoss ?: "",
                    o.target ?: "", o.status, o.fillPrice ?: "", o.createdAt, o.filledAt ?: "", Format.time(a.market, o.createdAt, "yyyy-MM-dd HH:mm"),
                    "%.2f".format(o.charges), q(o.strategy), q(o.reason), q(o.note)).joinToString(","))
            }
        }

        /** The day's activity in the market's own calendar day. */
        fun dailyJson(a: Account, day: String): String {
            val m = a.market
            fun onDay(t: Long?) = t != null && MarketHours.localDate(m, t).toString() == day
            val pts = a.equity.filter { onDay(it.t) }
            val j = buildJsonObject {
                put("date", day); put("market", m.name)
                put("cash", a.cash); put("equity", Engine.equity(a)); put("startCash", a.startCash); put("chargesPaidTotal", a.chargesPaid)
                pts.firstOrNull()?.let { put("equityFirst", it.equity) }
                pts.lastOrNull()?.let { put("equityLast", it.equity) }
                put("realizedToday", a.trades.filter { onDay(it.closedAt) }.sumOf { it.net })
                put("orders", Http.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Order.serializer()),
                    a.orders.filter { onDay(it.createdAt) || onDay(it.filledAt) }))
                put("closedTrades", Http.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(ClosedTrade.serializer()),
                    a.trades.filter { onDay(it.closedAt) }))
                put("positions", Http.json.encodeToJsonElement(kotlinx.serialization.builtins.ListSerializer(Position.serializer()), a.positions))
                putJsonObject("equityCurve") { pts.forEach { put(it.t.toString(), it.equity) } }
            }
            return Json(from = Http.json) { prettyPrint = true }.encodeToString(JsonObject.serializer(), j)
        }
    }
}

@Serializable
data class CloudConfig(val token: String = "", val repo: String = DEFAULT_DATA_REPO, val user: String = "")

const val DEFAULT_DATA_REPO = "AdityaKalpandeEren/nkpt-data"
