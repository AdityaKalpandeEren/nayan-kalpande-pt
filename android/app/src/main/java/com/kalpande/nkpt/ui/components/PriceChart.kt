package com.kalpande.nkpt.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.Candle
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Timeframe
import com.kalpande.nkpt.domain.Bars
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.domain.MarketHours
import com.kalpande.nkpt.ui.theme.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class ChartLine(val price: Double, val color: Color, val title: String)
data class ChartMarker(val time: Long, val buy: Boolean, val text: String)

val VwapColor = Color(0xFF2EC5FF)

/** The EMA set: each a different colour (none red/green, so they never look like candles) and thicker as it gets slower. */
data class EmaSpec(val len: Int, val color: Color, val width: Float)
val EMAS = listOf(
    EmaSpec(20, Color(0xFFF0B90B), 1.3f),    // yellow
    EmaSpec(50, Color(0xFFB46CFF), 1.5f),    // purple
    EmaSpec(80, Color(0xFFFF8A3D), 1.6f),    // orange
    EmaSpec(100, Color(0xFFFF5FD2), 1.8f),   // pink
    EmaSpec(180, Color(0xFFF2F4F8), 2.2f),   // white, thickest
)

/**
 * Native candlestick chart (Compose Canvas): candles + volume, EMA 20/50/80/100/180, session VWAP, live
 * last-price line, position / order lines, fill markers. Pinch to zoom, drag sideways to scroll
 * (vertical drags still scroll the page), long-press for the crosshair + OHLC, tap to clear it.
 */
@Composable
fun PriceChart(
    market: Market,
    candles: List<Candle>,
    tf: Timeframe,
    key: String,
    lines: List<ChartLine>,
    markers: List<ChartMarker>,
    emas: Set<Int>,
    showVwap: Boolean,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val tm = rememberTextMeasurer()
    var barW by remember(key, tf) { mutableFloatStateOf(with(density) { 8f * this.density }) }
    var offset by remember(key, tf) { mutableFloatStateOf(-3f) }
    var cross by remember(key, tf) { mutableStateOf<Offset?>(null) }
    val n = candles.size
    val emaValues = remember(candles) { EMAS.associate { it.len to ema(candles, it.len) } }
    val vwap = remember(candles) { if (Bars.intraday(tf)) sessionVwap(market, candles) else arrayOfNulls(n) }
    val minW = 0.6f * density.density          // zoomed out: 5 years of daily candles fit on screen
    val maxW = 40f * density.density

    Canvas(
        modifier
            .clipToBounds()
            .pointerInput(key, tf, n) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var lock: Boolean? = null
                    var acc = Offset.Zero
                    do {
                        val e = awaitPointerEvent()
                        if (e.changes.count { it.pressed } >= 2) {
                            barW = (barW * e.calculateZoom()).coerceIn(minW, maxW)
                            e.changes.forEach { it.consume() }
                        } else {
                            val pan = e.calculatePan()
                            acc += pan
                            if (lock == null && (abs(acc.x) > 12 || abs(acc.y) > 12)) lock = abs(acc.x) > abs(acc.y)
                            if (lock == true && cross == null) {
                                offset = (offset + pan.x / barW).coerceIn(-8f, max(0f, n - 8f))
                                e.changes.forEach { it.consume() }
                            }
                        }
                    } while (e.changes.any { it.pressed })
                }
            }
            .pointerInput(key, tf) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { cross = it },
                    onDrag = { c, _ -> cross = c.position; c.consume() },
                )
            }
            .pointerInput(key, tf) { detectTapGestures(onTap = { cross = null }) }
    ) {
        if (n == 0) return@Canvas
        drawChart(tm, market, tf, candles, lines, markers, EMAS.filter { it.len in emas }.map { it to emaValues.getValue(it.len) },
            if (showVwap) vwap else null, barW, offset, cross)
    }
}

private fun DrawScope.drawChart(
    tm: TextMeasurer, m: Market, tf: Timeframe, c: List<Candle>, lines: List<ChartLine>, markers: List<ChartMarker>,
    emas: List<Pair<EmaSpec, Array<Double?>>>, vw: Array<Double?>?, barW: Float, offset: Float, cross: Offset?,
) {
    val dp = density
    val axisW = 60 * dp
    val timeH = 18 * dp
    val top = 36 * dp
    val pw = size.width - axisW
    val chartBottom = size.height - timeH
    val volTop = chartBottom - (chartBottom - top) * 0.18f
    val priceBottom = volTop - 6 * dp
    val n = c.size
    fun x(i: Int) = pw - (n - 1 - offset - i + 0.5f) * barW
    val first = max(0, floor(n - 1 - offset - pw / barW).toInt() - 1)
    val last = min(n - 1, ceil(n - 1 - offset).toInt())
    if (first > last) return
    val vis = (first..last)
    var lo = vis.minOf { c[it].l }
    var hi = vis.maxOf { c[it].h }
    val span0 = (hi - lo).coerceAtLeast(hi * 0.002)
    lines.filter { it.price in (lo - span0 * 0.25)..(hi + span0 * 0.25) }.forEach { lo = min(lo, it.price); hi = max(hi, it.price) }
    val pad = (hi - lo).coerceAtLeast(hi * 0.002) * 0.07
    lo -= pad; hi += pad
    fun y(p: Double) = (top + (hi - p) / (hi - lo) * (priceBottom - top)).toFloat()
    val label = TextStyle(color = Palette.Muted, fontSize = 10.sp)

    // grid + price axis
    val step = niceStep((hi - lo) / 5)
    var g = ceil(lo / step) * step
    while (g <= hi) {
        val yy = y(g)
        drawLine(Palette.Line.copy(alpha = 0.55f), Offset(0f, yy), Offset(pw, yy), 1f)
        val t = tm.measure(Format.price(m, g), label)
        drawText(t, topLeft = Offset(pw + 6 * dp, yy - t.size.height / 2f))
        g += step
    }
    drawLine(Palette.Line, Offset(pw, top - 10 * dp), Offset(pw, chartBottom), 1f)

    // time axis: a label every ~80dp, the date when the day changes (intraday)
    val every = max(1, ceil(80 * dp / barW).toInt())
    var prevDay: java.time.LocalDate? = null
    for (i in vis) {
        val day = MarketHours.localDate(m, c[i].t)
        val newDay = prevDay != null && day != prevDay
        prevDay = day
        if (Bars.intraday(tf) && newDay) drawLine(Palette.Line.copy(alpha = 0.9f), Offset(x(i) - barW / 2, top), Offset(x(i) - barW / 2, chartBottom), 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)))
        if (i % every != 0) continue
        val txt = when {
            tf == Timeframe.W1 -> Format.time(m, c[i].t, "MMM yy")
            tf == Timeframe.D1 -> Format.time(m, c[i].t, "dd MMM")
            newDay -> Format.time(m, c[i].t, "dd MMM")
            else -> Format.time(m, c[i].t, "HH:mm")
        }
        val t = tm.measure(txt, label)
        val xx = x(i) - t.size.width / 2f
        if (xx > 0 && xx + t.size.width < pw) drawText(t, topLeft = Offset(xx, chartBottom + 3 * dp))
    }

    // volume
    val vMax = vis.maxOf { c[it].v }.coerceAtLeast(1.0)
    val bodyW = max(1f, barW * 0.68f)
    for (i in vis) {
        val k = c[i]
        val hgt = ((k.v / vMax) * (chartBottom - volTop)).toFloat()
        drawRect((if (k.c >= k.o) Palette.Up else Palette.Down).copy(alpha = 0.32f), Offset(x(i) - bodyW / 2, chartBottom - hgt), Size(bodyW, hgt))
    }

    // candles
    val wick = max(1f, barW * 0.12f)
    for (i in vis) {
        val k = c[i]
        val col = if (k.c >= k.o) Palette.Up else Palette.Down
        drawLine(col, Offset(x(i), y(k.h)), Offset(x(i), y(k.l)), wick)
        val y1 = y(max(k.o, k.c)); val y2 = y(min(k.o, k.c))
        drawRect(col, Offset(x(i) - bodyW / 2, y1), Size(bodyW, max(1f, y2 - y1)))
    }

    // indicators
    fun series(v: Array<Double?>?, color: Color, dashed: Boolean = false, width: Float = 1.4f) {
        if (v == null) return
        val p = Path(); var started = false
        for (i in vis) {
            val value = v.getOrNull(i)
            if (value == null) { started = false; continue }
            if (!started) { p.moveTo(x(i), y(value)); started = true } else p.lineTo(x(i), y(value))
        }
        drawPath(p, color, style = Stroke(width = width * dp, pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(6f, 5f)) else null))
    }
    clipRect(0f, top - 4 * dp, pw, priceBottom + 2 * dp) {                       // lines never spill into volume / time axis
        emas.asReversed().forEach { (e, v) -> series(v, e.color, width = e.width) }  // slow ones underneath
        series(vw, VwapColor, dashed = true, width = 1.6f)
    }

    // name tag at the right end of every line ("20", "50", ... "VWAP"), pushed apart so they never overlap
    run {
        val tags = (emas.map { (e, v) -> Triple(e.len.toString(), e.color, v) } + listOfNotNull(vw?.let { Triple("VWAP", VwapColor, it) }))
            .mapNotNull { (name, col, v) -> v.getOrNull(last)?.let { Triple(name, col, y(it)) } }
            .filter { it.third in top..priceBottom }
            .sortedBy { it.third }
        val gap = 12 * dp
        var prevY = -1e9f
        tags.forEach { (name, col, yRaw) ->
            val yy = max(yRaw, prevY + gap); prevY = yy
            val t = tm.measure(name, TextStyle(color = col, fontSize = 9.sp, fontWeight = FontWeight.Bold))
            val bx = min(x(last) + barW, pw) - t.size.width - 6 * dp
            drawRect(Palette.Bg.copy(alpha = 0.75f), Offset(bx - 2 * dp, yy - t.size.height / 2f), Size(t.size.width + 4 * dp, t.size.height.toFloat()))
            drawText(t, topLeft = Offset(bx, yy - t.size.height / 2f))
        }
    }

    // fill markers
    for (mk in markers) {
        val i = c.indexOfLast { it.t <= mk.time }
        if (i !in vis) continue
        val xx = x(i)
        val col = if (mk.buy) Palette.Up else Palette.Down
        val yy = if (mk.buy) y(c[i].l) + 6 * dp else y(c[i].h) - 6 * dp
        val tri = Path().apply {
            if (mk.buy) { moveTo(xx, yy); lineTo(xx - 5 * dp, yy + 8 * dp); lineTo(xx + 5 * dp, yy + 8 * dp) }
            else { moveTo(xx, yy); lineTo(xx - 5 * dp, yy - 8 * dp); lineTo(xx + 5 * dp, yy - 8 * dp) }
            close()
        }
        drawPath(tri, col)
        val t = tm.measure(mk.text, TextStyle(color = col, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
        drawText(t, topLeft = Offset(xx - t.size.width / 2f, if (mk.buy) yy + 9 * dp else yy - 9 * dp - t.size.height))
    }

    // price lines (position / orders), then the live last price
    var nAbove = 0
    var nBelow = 0
    fun axisTag(price: Double, color: Color, text: String, filled: Boolean) {
        val raw = y(price)
        val above = raw < top - 2 * dp
        val below = raw > priceBottom + 2 * dp
        // off-screen level: pinned to the chart's edge with an arrow (stacked) instead of disappearing
        val row = 17 * dp
        val yy = when { above -> top + 6 * dp + row * nAbove++; below -> priceBottom - 2 * dp - row * nBelow++; else -> raw }
        if (!above && !below) drawLine(color, Offset(0f, yy), Offset(pw, yy), 1.2f * dp, pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 6f)))
        val label = if (above) "$text ↑" else if (below) "$text ↓" else text
        val t = tm.measure(label, TextStyle(color = if (filled) Color.White else color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
        val boxH = t.size.height + 4 * dp
        val boxW = max(axisW - 1, t.size.width + 8 * dp)
        val bx = size.width - boxW
        drawRect(if (filled) color else Palette.Bg, Offset(bx, yy - boxH / 2), Size(boxW, boxH))
        if (!filled) drawRect(color, Offset(bx, yy - boxH / 2), Size(boxW, boxH), style = Stroke(1f))
        drawText(t, topLeft = Offset(bx + 4 * dp, yy - t.size.height / 2f))
    }
    lines.sortedBy { abs(it.price - c.last().c) }.forEach { l ->
        val inside = y(l.price) in (top - 2 * dp)..(priceBottom + 2 * dp)
        axisTag(l.price, l.color, if (inside) Format.price(m, l.price) else "${l.title} ${Format.price(m, l.price)}", false)
    }
    val lastC = c.last()
    axisTag(lastC.c, if (lastC.c >= lastC.o) Palette.Up else Palette.Down, Format.price(m, lastC.c), true)

    // crosshair + legend
    val ci = cross?.let { ((it.x - pw) / barW + n - 1 - offset + 0.5f).roundToInt().coerceIn(first, last) }
    if (cross != null && ci != null) {
        val xx = x(ci)
        drawLine(Palette.Accent.copy(alpha = 0.8f), Offset(xx, top), Offset(xx, chartBottom), 1f)
        val cy = cross.y.coerceIn(top, priceBottom)
        drawLine(Palette.Accent.copy(alpha = 0.8f), Offset(0f, cy), Offset(pw, cy), 1f)
        val p = hi - (cy - top) / (priceBottom - top) * (hi - lo)
        val t = tm.measure(Format.price(m, p), TextStyle(color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.SemiBold))
        drawRect(Palette.Accent, Offset(pw + 1, cy - t.size.height / 2f - 2 * dp), Size(axisW - 1, t.size.height + 4 * dp))
        drawText(t, topLeft = Offset(pw + 4 * dp, cy - t.size.height / 2f))
        val tt = tm.measure(Format.time(m, c[ci].t, if (Bars.intraday(tf)) "dd MMM HH:mm" else "dd MMM yyyy"),
            TextStyle(color = Color.White, fontSize = 10.sp))
        val bx = (xx - tt.size.width / 2f).coerceIn(0f, pw - tt.size.width)
        drawRect(Palette.Accent, Offset(bx - 3 * dp, chartBottom), Size(tt.size.width + 6 * dp, timeH))
        drawText(tt, topLeft = Offset(bx, chartBottom + 2 * dp))
    }
    val k = c[ci ?: (n - 1)]
    val prev = c.getOrNull((ci ?: (n - 1)) - 1)
    val chg = prev?.let { (k.c - it.c) / it.c * 100 } ?: 0.0
    val up = k.c >= k.o
    val legend = androidx.compose.ui.text.buildAnnotatedString {
        fun part(l: String, v: String, col: Color = Palette.Text) {
            pushStyle(androidx.compose.ui.text.SpanStyle(color = Palette.Muted)); append("$l "); pop()
            pushStyle(androidx.compose.ui.text.SpanStyle(color = col, fontWeight = FontWeight.SemiBold)); append("$v  "); pop()
        }
        part("O", Format.price(m, k.o)); part("H", Format.price(m, k.h)); part("L", Format.price(m, k.l))
        part("C", Format.price(m, k.c), if (up) Palette.Up else Palette.Down)
        pushStyle(androidx.compose.ui.text.SpanStyle(color = if (chg >= 0) Palette.Up else Palette.Down)); append(String.format(Locale.US, "%+.2f%%", chg)); pop()
    }
    drawText(tm.measure(legend, TextStyle(fontSize = 10.sp)), topLeft = Offset(4 * dp, 3 * dp))
    val idx = ci ?: (n - 1)
    val ind = androidx.compose.ui.text.buildAnnotatedString {
        emas.forEach { (e, v) ->
            pushStyle(androidx.compose.ui.text.SpanStyle(color = e.color, fontWeight = FontWeight.SemiBold))
            append("${e.len} ${v.getOrNull(idx)?.let { Format.price(m, it) } ?: "—"}  "); pop()
        }
        vw?.getOrNull(idx)?.let { pushStyle(androidx.compose.ui.text.SpanStyle(color = VwapColor, fontWeight = FontWeight.SemiBold)); append("VW ${Format.price(m, it)}"); pop() }
    }
    if (ind.isNotEmpty()) drawText(tm.measure(ind, TextStyle(fontSize = 9.sp), maxLines = 1, softWrap = false), topLeft = Offset(4 * dp, 18 * dp))
}

private fun niceStep(raw: Double): Double {
    if (raw <= 0 || !raw.isFinite()) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    return mag * when { f < 1.5 -> 1.0; f < 3 -> 2.0; f < 7 -> 5.0; else -> 10.0 }
}

private fun ema(c: List<Candle>, len: Int): Array<Double?> {
    val out = arrayOfNulls<Double>(c.size)
    val k = 2.0 / (len + 1)
    if (c.size < len) return out
    var e = (0 until len).sumOf { c[it].c } / len          // seeded with the SMA of the first [len] closes, like TradingView
    out[len - 1] = e
    for (i in len until c.size) { e = c[i].c * k + e * (1 - k); out[i] = e }
    return out
}

private fun sessionVwap(m: Market, c: List<Candle>): Array<Double?> {
    val out = arrayOfNulls<Double>(c.size)
    var day: java.time.LocalDate? = null
    var pv = 0.0; var v = 0.0
    c.forEachIndexed { i, b ->
        val d = MarketHours.localDate(m, b.t)
        if (d != day) { day = d; pv = 0.0; v = 0.0 }
        pv += (b.h + b.l + b.c) / 3 * b.v; v += b.v
        if (v > 0) out[i] = pv / v
    }
    return out
}
