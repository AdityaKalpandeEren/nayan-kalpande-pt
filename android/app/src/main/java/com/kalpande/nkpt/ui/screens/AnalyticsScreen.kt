package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.EquityPoint
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.domain.Analytics
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.domain.Group
import com.kalpande.nkpt.domain.Stats
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Card
import com.kalpande.nkpt.ui.components.Empty
import com.kalpande.nkpt.ui.components.SectionTitle
import com.kalpande.nkpt.ui.components.Stat
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val periods = listOf("All" to 0L, "90D" to 90L, "30D" to 30L, "7D" to 7L)

@Composable
fun AnalyticsScreen(vm: MainViewModel, ui: UiState) {
    val ctx = LocalContext.current
    var period by rememberSaveable { mutableIntStateOf(0) }
    val m = ui.market
    val a = ui.account
    val now = System.currentTimeMillis() / 1000
    val days = periods[period].second
    val trades = if (days == 0L) a.trades else Analytics.since(a.trades, now - days * 86400)
    val eqPts = if (days == 0L) a.equity else a.equity.filter { it.t >= now - days * 86400 }
    val st = Analytics.stats(trades)
    val (dd, ddPct) = Analytics.maxDrawdown(eqPts)

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f)) { periods.forEachIndexed { i, (l, _) -> Chip(l, period == i) { period = i } } }
                IconButton(onClick = { ctx.startActivity(vm.exportCsv(m)) }, enabled = a.trades.isNotEmpty()) {
                    Icon(Icons.Outlined.IosShare, "Export CSV", tint = Palette.Muted)
                }
            }
            VSpace(8)
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text("Net P&L · ${st.trades} closed trades", color = Palette.Muted, fontSize = 12.sp)
                    Text(Format.signedMoney(m, st.net), color = Palette.pnl(st.net), fontSize = 26.sp, fontWeight = FontWeight.Bold, style = Num)
                    Text("Gross ${Format.signedMoney(m, st.gross)} · charges ${Format.money(m, st.charges)}", color = Palette.Muted, fontSize = 12.sp, style = Num)
                    VSpace(12)
                    EquityCurve(m, eqPts, a.startCash)
                }
            }
        }
        if (trades.isEmpty()) {
            item { Empty(Icons.Outlined.Insights, "No closed trades in this period", "Tag each trade with a strategy - this screen will show which ones work.") }
            return@LazyColumn
        }
        item {
            SectionTitle("Key numbers")
            Card(Modifier.fillMaxWidth()) {
                Column {
                    KpiRow(
                        Triple("Win rate", "%.1f%%".format(Locale.US, st.winRate), "${st.wins}W / ${st.losses}L"),
                        Triple("Profit factor", st.profitFactor?.let { "%.2f".format(Locale.US, it) } ?: "∞", "wins ÷ losses"),
                        Triple("Expectancy", Format.signedMoney(m, st.expectancy), "per trade"),
                    )
                    VSpace(12)
                    KpiRow(
                        Triple("Avg win", Format.money(m, st.avgWin), null),
                        Triple("Avg loss", Format.money(m, st.avgLoss), null),
                        Triple("Payoff", st.payoff?.let { "%.2f".format(Locale.US, it) } ?: "—", "avg win ÷ avg loss"),
                    )
                    VSpace(12)
                    KpiRow(
                        Triple("Avg R", st.avgR?.let { "%+.2fR".format(Locale.US, it) } ?: "—", "trades with a stop"),
                        Triple("Max drawdown", Format.money(m, dd), "%.1f%% of peak".format(Locale.US, ddPct)),
                        Triple("Avg hold", Format.duration(st.avgHoldSec), null),
                    )
                    VSpace(12)
                    KpiRow(
                        Triple("Best trade", Format.signedMoney(m, st.largestWin), null),
                        Triple("Worst trade", Format.signedMoney(m, st.largestLoss), null),
                        Triple("Streaks", "${st.maxWinStreak}W / ${st.maxLossStreak}L", "longest"),
                    )
                    VSpace(12)
                    Text(edgeText(st), color = Palette.Muted, fontSize = 12.sp)
                }
            }
        }
        groupSection("By strategy", m, Analytics.byStrategy(trades))
        groupSection("Intraday vs hold", m, Analytics.byProduct(trades))
        groupSection("Long vs short", m, Analytics.byDirection(trades))
        groupSection("How trades ended", m, Analytics.byExit(trades))
        groupSection("Holding time", m, Analytics.byHolding(trades))
        groupSection("Day of week (entry)", m, Analytics.byWeekday(m, trades))
        groupSection("By symbol", m, Analytics.bySymbol(trades).let { if (it.size > 12) it.take(6) + it.takeLast(6) else it })
        item { VSpace(24) }
    }
}

private fun edgeText(st: Stats): String {
    val t = st.tStat ?: return "Need at least 3 trades to judge the edge."
    return when {
        st.trades < 30 -> "Only ${st.trades} trades - too few to tell skill from luck (aim for 30+ per strategy)."
        abs(t) < 2 -> "t-stat %.2f: the result is still inside the noise - keep collecting trades.".format(Locale.US, t)
        t > 0 -> "t-stat %.2f: a real positive edge is likely so far - check it holds by strategy.".format(Locale.US, t)
        else -> "t-stat %.2f: consistently losing - change or drop this approach.".format(Locale.US, t)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.groupSection(title: String, m: Market, groups: List<Group>) {
    if (groups.isEmpty()) return
    item {
        SectionTitle(title)
        Card(Modifier.fillMaxWidth()) {
            Column {
                Row {
                    Text("", modifier = Modifier.weight(1.4f))
                    Header("Trades", 0.7f); Header("Win %", 0.7f); Header("Net", 1.1f)
                }
                val maxAbs = groups.maxOf { abs(it.stats.net) }.coerceAtLeast(1e-9)
                groups.forEach { g ->
                    VSpace(8)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1.4f)) {
                            Text(g.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                            Box(Modifier.padding(top = 3.dp).height(4.dp).fillMaxWidth(0.9f).clip(RoundedCornerShape(2.dp)).background(Palette.Surface2)) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth((abs(g.stats.net) / maxAbs).toFloat().coerceIn(0.02f, 1f))
                                    .clip(RoundedCornerShape(2.dp)).background(Palette.pnl(g.stats.net)))
                            }
                        }
                        Cell("${g.stats.trades}", 0.7f)
                        Cell("%.0f".format(Locale.US, g.stats.winRate), 0.7f)
                        Cell(Format.signedMoney(m, g.stats.net), 1.1f, Palette.pnl(g.stats.net))
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Header(t: String, w: Float) =
    Text(t, color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(w), textAlign = androidx.compose.ui.text.style.TextAlign.End)

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(t: String, w: Float, color: Color = Palette.Text) =
    Text(t, color = color, fontSize = 13.sp, style = Num, modifier = Modifier.weight(w), textAlign = androidx.compose.ui.text.style.TextAlign.End, maxLines = 1)

@Composable
private fun KpiRow(vararg items: Triple<String, String, String?>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        items.forEach { (l, v, s) -> Stat(l, v, Modifier.width(104.dp), sub = s) }
    }
}

@Composable
private fun EquityCurve(m: Market, pts: List<EquityPoint>, start: Double) {
    if (pts.size < 2) {
        Text("The equity curve appears after a few refreshes with trades.", color = Palette.Muted, fontSize = 12.sp); return
    }
    val lo = minOf(pts.minOf { it.equity }, start)
    val hi = max(pts.maxOf { it.equity }, start)
    val span = (hi - lo).coerceAtLeast(1e-6)
    val up = pts.last().equity >= start
    val col = if (up) Palette.Up else Palette.Down
    Column {
        Canvas(Modifier.fillMaxWidth().height(140.dp)) {
            val w = size.width; val h = size.height
            fun x(i: Int) = i * w / (pts.size - 1)
            fun y(v: Double) = (h - ((v - lo) / span * h)).toFloat()
            val line = Path().apply { pts.forEachIndexed { i, p -> if (i == 0) moveTo(x(i), y(p.equity)) else lineTo(x(i), y(p.equity)) } }
            val area = Path().apply { addPath(line); lineTo(w, h); lineTo(0f, h); close() }
            drawPath(area, Brush.verticalGradient(listOf(col.copy(alpha = 0.28f), Color.Transparent)))
            drawLine(Palette.Muted.copy(alpha = 0.5f), Offset(0f, y(start)), Offset(w, y(start)), 1.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            drawPath(line, col, style = Stroke(width = 4f))
        }
        Row(Modifier.fillMaxWidth()) {
            Text(Format.time(m, pts.first().t, "dd MMM"), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            Text("start ${Format.compact(m, start)} - - -", color = Palette.Muted, fontSize = 11.sp)
            Text(Format.time(m, pts.last().t, "dd MMM"), color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}
