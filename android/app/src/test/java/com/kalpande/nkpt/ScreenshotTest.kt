package com.kalpande.nkpt

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.kalpande.nkpt.ui.components.Intro
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.kalpande.nkpt.data.Account
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Http
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Quote
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.data.Timeframe
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.TradeSettings
import com.kalpande.nkpt.ui.AppRoot
import com.kalpande.nkpt.ui.ChartState
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.screens.AnalyticsScreen
import com.kalpande.nkpt.ui.screens.DetailScreen
import com.kalpande.nkpt.ui.screens.OrderTicket
import com.kalpande.nkpt.ui.screens.OrdersScreen
import com.kalpande.nkpt.ui.screens.PortfolioScreen
import com.kalpande.nkpt.ui.screens.SearchScreen
import com.kalpande.nkpt.ui.screens.SettingsScreen
import com.kalpande.nkpt.ui.screens.WatchlistScreen
import com.kalpande.nkpt.ui.theme.NkptTheme
import com.kalpande.nkpt.ui.theme.Palette
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.sin

/**
 * Renders every screen with sample accounts built by the real engine (JVM, no emulator, no network):
 *   ./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest*'   -> app/build/outputs/roborazzi/
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h1600dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    @Serializable
    private data class Cached(val day: String, val items: List<Instrument>)

    private val nseIns = listOf(
        Instrument(Market.NSE, "RELIANCE", "Reliance Industries", "NSE_EQ|INE002A01018", "NSE"),
        Instrument(Market.NSE, "TCS", "Tata Consultancy Services", "NSE_EQ|INE467B01029", "NSE"),
        Instrument(Market.NSE, "HDFCBANK", "HDFC Bank", "NSE_EQ|INE040A01034", "NSE"),
        Instrument(Market.NSE, "INFY", "Infosys", "NSE_EQ|INE009A01021", "NSE"),
        Instrument(Market.NSE, "CUPID", "Cupid Limited", "NSE_EQ|INE509F01029", "NSE"),
        Instrument(Market.NSE, "TATAMOTORS", "Tata Motors", "NSE_EQ|INE155A01022", "NSE"),
        Instrument(Market.NSE, "RELINFRA", "Reliance Infrastructure", "NSE_EQ|INE036A01016", "NSE"),
        Instrument(Market.NSE, "Nifty 50", "Nifty 50", "NSE_INDEX|Nifty 50", "NSE index", tradable = false),
    )
    private val usIns = listOf(
        Instrument(Market.US, "AAPL", "Apple Inc.", "AAPL", "NASDAQ"),
        Instrument(Market.US, "NVDA", "NVIDIA Corporation", "NVDA", "NASDAQ"),
        Instrument(Market.US, "TSLA", "Tesla, Inc.", "TSLA", "NASDAQ"),
        Instrument(Market.US, "SPY", "SPDR S&P 500 ETF Trust", "SPY", "NYSE Arca · ETF"),
    )
    private val px = mapOf(
        "NSE_EQ|INE002A01018" to (1166.7 to 1158.9), "NSE_EQ|INE467B01029" to (2269.3 to 2125.0), "NSE_EQ|INE040A01034" to (962.45 to 970.1),
        "NSE_EQ|INE009A01021" to (1488.2 to 1471.3), "NSE_EQ|INE509F01029" to (362.28 to 351.2), "NSE_EQ|INE155A01022" to (661.0 to 668.9),
        "AAPL" to (344.63 to 340.97), "NVDA" to (212.4 to 208.1), "TSLA" to (431.2 to 438.9), "SPY" to (742.1 to 739.6),
    )
    private val s = TradeSettings()
    private val now = ZonedDateTime.of(LocalDate.of(2026, 10, 13), LocalTime.of(14, 5), ZoneId.of("Asia/Kolkata")).toEpochSecond()

    private fun ist(d: LocalDate, h: Int, m: Int) = ZonedDateTime.of(d, LocalTime.of(h, m), ZoneId.of("Asia/Kolkata")).toEpochSecond()
    private fun q(key: String, p: Double, t: Long) = Quote(key, p, p, t)

    private fun nseAccount(): Account {
        var a = Account(Market.NSE, createdAt = now - 40 * 86400, watchlist = nseIns.take(6))
        val strategies = listOf("Breakout", "Pullback", "Momentum", "Gap", "Breakout", "Mean reversion")
        val moves = listOf(0.032, -0.011, 0.05, -0.02, 0.014, 0.027, -0.009, 0.041, -0.017, 0.022, -0.012, 0.035, 0.018, -0.025, 0.012, -0.006, 0.029, 0.009)
        var d = LocalDate.of(2026, 9, 7)
        moves.forEachIndexed { i, mv ->
            while (d.dayOfWeek.value > 5) d = d.plusDays(1)
            val ins = nseIns[i % 6]
            val p0 = px.getValue(ins.key).first * (1 - 0.03 * sin(i.toDouble()))
            val intraday = i % 3 == 0
            val prod = if (intraday) Product.INTRADAY else Product.HOLD
            val qty = (40_000 / p0).toInt().coerceAtLeast(1)
            val sl = if (i % 2 == 0) p0 * 0.98 else null
            val t0 = ist(d, 10, 5)
            a = Engine.place(a, OrderRequest(ins, Side.BUY, qty, OrderType.MARKET, prod, stopLoss = sl, strategy = strategies[i % 6]), q(ins.key, p0, t0), t0, s).account
            val exitDay = if (intraday) d else d.plusDays(if (i % 4 == 1) 6 else 2).let { x -> var y = x; while (y.dayOfWeek.value > 5) y = y.plusDays(1); y }
            val t1 = ist(exitDay, 14, 40)
            val p1 = p0 * (1 + mv)
            val pos = a.positions.first { it.symbol == ins.symbol && it.product == prod }
            a = Engine.exit(a, pos, p1, t1, if (mv < -0.015 && sl != null) "SL" else if (mv > 0.03) "TARGET" else "MANUAL").first
            d = d.plusDays(2)
        }
        val rel = nseIns[0]
        val tOpen = ist(LocalDate.of(2026, 10, 8), 11, 20)
        a = Engine.place(a, OrderRequest(rel, Side.BUY, 30, OrderType.MARKET, Product.HOLD, stopLoss = 1090.0, target = 1220.0, strategy = "Swing", note = "Holding above 50 EMA"), q(rel.key, 1120.0, tOpen), tOpen, s).account
        val infy = nseIns[3]
        a = Engine.place(a, OrderRequest(infy, Side.SELL, 25, OrderType.MARKET, Product.INTRADAY, stopLoss = 1505.0, strategy = "Gap"), q(infy.key, 1494.0, now - 3000), now - 3000, s).account
        a = Engine.place(a, OrderRequest(nseIns[1], Side.BUY, 8, OrderType.LIMIT, Product.HOLD, limitPrice = 2200.0, strategy = "Pullback"), q(nseIns[1].key, 2269.3, now - 600), now - 600, s).account
        return a.copy(positions = a.positions.map { p -> p.copy(lastPrice = px[p.key]?.first ?: p.lastPrice) })
    }

    private fun state(market: Market = Market.NSE, usWatch: Boolean = false): UiState {
        val nse = nseAccount()
        val us = Account(Market.US, createdAt = now, watchlist = if (usWatch) usIns else emptyList())
        return UiState(
            market = market, accounts = mapOf(Market.NSE to nse, Market.US to us),
            quotes = px.map { (k, v) -> k to Quote(k, v.first, v.second, now - 20) }.toMap(),
            instrumentCount = mapOf(Market.NSE to 3697, Market.US to 12875), trade = s, lastRefresh = now,
        )
    }

    private fun vm(st: UiState): MainViewModel {
        val app = ApplicationProvider.getApplicationContext<NkptApp>()
        val today = LocalDate.now().toString()
        File(app.filesDir, "instruments_NSE.json").writeText(Http.json.encodeToString(Cached.serializer(), Cached(today, nseIns)))
        File(app.filesDir, "instruments_US.json").writeText(Http.json.encodeToString(Cached.serializer(), Cached(today, usIns)))
        runBlocking { st.accounts.values.forEach { app.broker.store.save(it) } }
        return MainViewModel(app).also { it.offline = true; it.seedUi(st) }
    }

    private fun snap(name: String, screen: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent { NkptTheme { Box(Modifier.fillMaxSize().background(Palette.Bg)) { content() } } }
        compose.waitForIdle()
        if (screen) captureScreenRoboImage("build/outputs/roborazzi/$name.png")
        else compose.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun candles(): List<Candle> {
        var p = 1120.0
        val start = ist(LocalDate.of(2026, 10, 13), 9, 15)
        return (0 until 300).map { i ->
            val o = p; p *= 1 + 0.0012 * sin(i / 7.0) + 0.0004
            Candle(start + i * 60L, o, maxOf(o, p) * 1.0008, minOf(o, p) * 0.9992, p, 20_000.0 + 9000 * sin(i / 3.0))
        }
    }

    @Test fun appIcon() {
        compose.setContent {
            androidx.compose.foundation.layout.Row(Modifier.background(Palette.Bg).padding(16.dp)) {
                for (round in listOf(false, true)) {
                    Box(Modifier.padding(8.dp).size(192.dp).clip(if (round) CircleShape else RoundedCornerShape(44.dp))) {
                        Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                    }
                }
                Box(Modifier.padding(8.dp).size(192.dp).clip(CircleShape).background(Color(0xFF3A4A6A))) {
                    Image(painterResource(R.drawable.ic_launcher_monochrome), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                }
            }
        }
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/10_app_icon.png")
    }

    @Test fun intro() {
        compose.mainClock.autoAdvance = false
        compose.setContent { NkptTheme { Intro("Loading NSE & US symbol lists…") } }
        for ((ms, n) in listOf(400L to "a", 500L to "b", 700L to "c")) {
            compose.mainClock.advanceTimeBy(ms)
            compose.onRoot().captureRoboImage("build/outputs/roborazzi/11_intro_$n.png")
        }
    }

    @Test fun appShell() { val st = state(); val v = vm(st); snap("00_app_shell") { AppRoot(v) } }

    @Test fun watchlistNse() { val st = state(); val v = vm(st); snap("01_watchlist_nse") { WatchlistScreen(v, st, {}, {}) } }

    @Test fun watchlistUsEmpty() { val st = state(Market.US); val v = vm(st); snap("02_watchlist_us_empty") { WatchlistScreen(v, st, {}, {}) } }

    @Test fun search() {
        val st = state(); val v = vm(st); v.setQuery("REL")
        snap("03_search") { SearchScreen(v, st, {}, {}) }
    }

    @Test fun detail() {
        val st = state(); val v = vm(st)
        v.seedChart(ChartState("NSE_EQ|INE002A01018", Timeframe.M1, candles()))
        snap("04_detail") { DetailScreen(v, st, Market.NSE, "NSE_EQ|INE002A01018") {} }
    }

    @Test fun detailUsDaily() {
        val st = state(Market.US, usWatch = true); val v = vm(st)
        var p = 180.0
        val day0 = ZonedDateTime.of(LocalDate.of(2026, 3, 2), LocalTime.of(9, 30), ZoneId.of("America/New_York")).toEpochSecond()
        val bars = (0 until 400).map { i -> val o = p; p *= 1 + 0.018 * sin(i / 5.0) + 0.0015 * sin(i / 60.0) + 0.002; Candle(day0 + i * 86400L, o, maxOf(o, p) * 1.01, minOf(o, p) * 0.99, p, 4e7 + 2e7 * sin(i / 2.0)) }
        v.seedChart(ChartState("SNDK", Timeframe.D1, bars))
        snap("04b_detail_us_daily") { DetailScreen(v, st, Market.US, "SNDK") {} }
    }

    @Test fun ticket() {
        val st = state(); val v = vm(st)
        snap("05_order_ticket", screen = true) { OrderTicket(v, st, nseIns[1], Side.BUY, 2269.3) {} }
    }

    @Test fun orders() { val st = state(); val v = vm(st); snap("06_orders") { OrdersScreen(v, st) {} } }

    @Test fun portfolio() { val st = state(); val v = vm(st); snap("07_portfolio") { PortfolioScreen(v, st) {} } }

    @Test fun analytics() { val st = state(); val v = vm(st); snap("08_analytics") { AnalyticsScreen(v, st) } }

    @Test fun settings() { val st = state(); val v = vm(st); snap("09_settings") { SettingsScreen(v, st) } }
}
