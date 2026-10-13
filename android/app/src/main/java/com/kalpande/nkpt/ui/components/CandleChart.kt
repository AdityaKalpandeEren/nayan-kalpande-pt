package com.kalpande.nkpt.ui.components

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Market
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant
import java.time.ZoneId

data class ChartLine(val price: Double, val color: String, val title: String)
data class ChartMarker(val time: Long, val buy: Boolean, val text: String)

/**
 * Candlestick chart (TradingView Lightweight Charts in a WebView, bundled in assets/chart):
 * candles + volume, EMA 20/50, session VWAP (intraday), entry / stop-loss / target lines and fill
 * markers; pinch to zoom, drag to scroll, long-press for the OHLC readout.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CandleChart(
    market: Market,
    candles: List<Candle>,
    intraday: Boolean,
    key: String,
    lines: List<ChartLine>,
    markers: List<ChartMarker>,
    showEma: Boolean,
    showVwap: Boolean,
    modifier: Modifier = Modifier,
) {
    val holder = remember { Holder() }
    val payload = buildPayload(market, candles, intraday, key, lines, markers, showEma, showVwap)
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = false
                settings.domStorageEnabled = false
                setBackgroundColor(0xFF0B0F17.toInt())
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                addJavascriptInterface(holder.bridge(this), "Android")
                loadUrl("file:///android_asset/chart/index.html")
                holder.view = this
            }
        },
        update = { holder.render(payload) },
    )
}

private class Holder {
    var view: WebView? = null
    private var ready = false
    private var pending: String? = null
    private val main = Handler(Looper.getMainLooper())

    fun bridge(w: WebView) = object {
        @JavascriptInterface
        fun ready() {
            main.post { ready = true; pending?.let { push(w, it) } }
        }
    }

    fun render(json: String) {
        pending = json
        val w = view ?: return
        if (ready) push(w, json)
    }

    private fun push(w: WebView, json: String) = w.evaluateJavascript("window.render($json)", null)
}

/** Lightweight Charts works in UTC; shifting by the exchange's offset shows exchange-local times. */
private fun buildPayload(
    market: Market, candles: List<Candle>, intraday: Boolean, key: String,
    lines: List<ChartLine>, markers: List<ChartMarker>, ema: Boolean, vwap: Boolean,
): String {
    val zone = ZoneId.of(market.zone)
    fun shift(t: Long) = t + zone.rules.getOffset(Instant.ofEpochSecond(t)).totalSeconds
    val decimals = if (candles.isNotEmpty() && candles.last().c < 1.0) 4 else 2
    return buildJsonObject {
        putJsonArray("candles") {
            candles.forEach { c ->
                add(buildJsonObject {
                    put("time", shift(c.t)); put("open", c.o); put("high", c.h); put("low", c.l); put("close", c.c); put("volume", c.v)
                })
            }
        }
        putJsonArray("lines") {
            lines.forEach { l -> add(buildJsonObject { put("price", l.price); put("color", l.color); put("title", l.title) }) }
        }
        val first = candles.firstOrNull()?.t ?: Long.MAX_VALUE
        put("markers", buildJsonArray {
            markers.filter { it.time >= first }.forEach { m ->
                // snap the fill time onto the bar that contains it
                val bar = candles.lastOrNull { it.t <= m.time } ?: return@forEach
                add(buildJsonObject {
                    put("time", shift(bar.t)); put("position", if (m.buy) "belowBar" else "aboveBar")
                    put("color", if (m.buy) "#00C087" else "#F6465D"); put("shape", if (m.buy) "arrowUp" else "arrowDown")
                    put("text", m.text)
                })
            }
        })
        put("ema", ema); put("vwap", vwap); put("intraday", intraday); put("decimals", decimals); put("key", key)
    }.toString()
}
