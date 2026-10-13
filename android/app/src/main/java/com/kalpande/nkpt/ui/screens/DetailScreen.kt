package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderStatus
import com.kalpande.nkpt.data.Position
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.data.Timeframe
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.domain.MarketHours
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Card
import com.kalpande.nkpt.ui.components.PriceChart
import com.kalpande.nkpt.ui.components.ChangeBadge
import com.kalpande.nkpt.ui.components.ChartLine
import com.kalpande.nkpt.ui.components.ChartMarker
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.Pill
import com.kalpande.nkpt.ui.components.SectionTitle
import com.kalpande.nkpt.ui.components.Stat
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette

@Composable
fun DetailScreen(vm: MainViewModel, ui: UiState, m: Market, key: String, onBack: () -> Unit) {
    val ins = remember(m, key) { vm.findInstrument(m, key) ?: Instrument(m, key.substringAfter('|'), key, key) }
    val chart by vm.chart.collectAsStateWithLifecycle()
    var ticket by remember { mutableStateOf<Side?>(null) }
    var editing by remember { mutableStateOf<Position?>(null) }
    var ema by remember { mutableStateOf(true) }
    var vwap by remember { mutableStateOf(true) }
    DisposableEffect(key) { vm.open(ins); onDispose { vm.close() } }

    val a = ui.accounts.getValue(m)
    val q = ui.quotes[key]
    val positions = a.positions.filter { it.key == key }
    val working = a.orders.filter { it.key == key && it.status == OrderStatus.OPEN }
    val fills = a.orders.filter { it.key == key && it.status == OrderStatus.FILLED && it.filledAt != null }
    val price = q?.price ?: positions.firstOrNull()?.lastPrice ?: chart.candles.lastOrNull()?.c
    val intraday = chart.tf in setOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 88.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                Column(Modifier.weight(1f)) {
                    Text(ins.symbol, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("${m.flag} ${ins.name}", color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val watched = a.watchlist.any { it.key == key }
                IconButton(onClick = { vm.toggleWatch(ins) }) {
                    Icon(if (watched) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Watchlist", tint = if (watched) Palette.Gold else Palette.Muted)
                }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(price?.let { "${m.currency}${Format.price(m, it)}" } ?: "—", fontSize = 30.sp, fontWeight = FontWeight.Bold, style = Num)
                    HSpace(10)
                    if (q != null) Column(Modifier.padding(bottom = 5.dp)) {
                        Text(Format.signedMoney(m, q.change), color = Palette.pnl(q.change), fontSize = 13.sp, style = Num)
                    }
                    HSpace(6)
                    if (q != null) Box(Modifier.padding(bottom = 6.dp)) { ChangeBadge(q.changePct) }
                }
                Text(
                    (q?.let { "Last trade ${Format.time(m, it.time, "HH:mm:ss")} · " } ?: "") + MarketHours.statusText(m, System.currentTimeMillis() / 1000) +
                        if (m == Market.NSE && ui.token.isBlank()) " · 1-min delayed (add Upstox token for live)" else "",
                    color = Palette.Muted, fontSize = 11.sp,
                )
            }
            VSpace(10)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Timeframe.entries.forEach { tf ->
                    Chip(tf.label, chart.tf == tf) { vm.loadChart(ins, tf) }
                }
                HSpace(8)
                Chip("EMA 20/50", ema, Palette.Gold) { ema = !ema }
                if (intraday) Chip("VWAP", vwap, Color(0xFF2EC5FF)) { vwap = !vwap }
            }
            VSpace(6)
            Box(Modifier.fillMaxWidth().height(360.dp)) {
                val lines = positions.flatMap { p ->
                    listOfNotNull(
                        ChartLine(p.avgPrice, Palette.Accent, "Avg"),
                        p.stopLoss?.let { ChartLine(it, Palette.Down, "SL") },
                        p.target?.let { ChartLine(it, Palette.Up, "TGT") },
                    )
                } + working.mapNotNull { o -> (o.limitPrice ?: o.triggerPrice)?.let { ChartLine(it, Palette.Gold, if (o.side == Side.BUY) "B" else "S") } }
                val markers = fills.map { ChartMarker(it.filledAt!!, it.side == Side.BUY, "${if (it.side == Side.BUY) "B" else "S"} ${it.qty}") }
                PriceChart(m, chart.candles, chart.tf, chart.key, lines, markers, ema, vwap && intraday, Modifier.fillMaxSize())
                if (chart.loading && chart.candles.isEmpty()) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
                chart.error?.let { Text(it, color = Palette.Down, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) }
            }
            Column(Modifier.padding(horizontal = 16.dp)) {
                DayStats(m, chart.candles, intraday, q?.prevClose)
                if (positions.isNotEmpty()) SectionTitle("Your position")
                positions.forEach { p -> PositionCard(m, p, onEdit = { editing = p }, onExit = { vm.exit(p) }); VSpace(8) }
                if (working.isNotEmpty()) SectionTitle("Working orders")
                working.forEach { o ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${o.side} ${o.qty} · ${o.type}${o.limitPrice?.let { " @ ${Format.price(m, it)}" } ?: ""}${o.triggerPrice?.let { " trig ${Format.price(m, it)}" } ?: ""}",
                                    fontWeight = FontWeight.SemiBold, color = if (o.side == Side.BUY) Palette.Up else Palette.Down)
                                Text("${productLabel(m, o.product)} · placed ${Format.time(m, o.createdAt)}", color = Palette.Muted, fontSize = 12.sp)
                            }
                            TextButton(onClick = { vm.cancel(m, o.id) }) { Text("Cancel", color = Palette.Down) }
                        }
                    }
                }
                if (!ins.tradable) {
                    VSpace(12); Text("Indices can be charted but not traded.", color = Palette.Muted, fontSize = 13.sp)
                }
            }
        }
        if (ins.tradable) Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Palette.Bg).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(onClick = { ticket = Side.SELL }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Down)) { Text("SELL", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
            Button(onClick = { ticket = Side.BUY }, modifier = Modifier.weight(1f).height(50.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Palette.Up)) { Text("BUY", fontWeight = FontWeight.Bold, fontSize = 16.sp) }
        }
    }
    ticket?.let { side -> OrderTicket(vm, ui, ins, side, price, onDismiss = { ticket = null }) }
    editing?.let { p -> EditExitsDialog(vm, p, onDismiss = { editing = null }) }
}

fun productLabel(m: Market, p: Product) = when {
    m == Market.NSE && p == Product.INTRADAY -> "Intraday (MIS)"
    m == Market.NSE -> "Hold (CNC)"
    p == Product.INTRADAY -> "Intraday"
    else -> "Hold"
}

@Composable
fun Chip(text: String, on: Boolean, color: Color = Palette.Accent, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(8.dp))
            .background(if (on) color.copy(alpha = 0.18f) else Palette.Surface).clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp)
    ) { Text(text, color = if (on) color else Palette.Muted, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun DayStats(m: Market, candles: List<com.kalpande.nkpt.data.Candle>, intraday: Boolean, prevClose: Double?) {
    if (candles.isEmpty()) return
    val day = if (intraday) {
        val d = MarketHours.localDate(m, candles.last().t)
        candles.filter { MarketHours.localDate(m, it.t) == d }
    } else listOf(candles.last())
    VSpace(12)
    Card(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Open", Format.price(m, day.first().o))
                Stat("High", Format.price(m, day.maxOf { it.h }), color = Palette.Up)
                Stat("Low", Format.price(m, day.minOf { it.l }), color = Palette.Down)
            }
            VSpace(10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("Prev close", prevClose?.let { Format.price(m, it) } ?: "—")
                Stat(if (intraday) "Volume (day)" else "Volume", "%,.0f".format(day.sumOf { it.v }))
                Stat(if (intraday) "Bars" else "Range", if (intraday) "${day.size}" else Format.price(m, day.maxOf { it.h } - day.minOf { it.l }))
            }
        }
    }
}

@Composable
fun PositionCard(m: Market, p: Position, onEdit: () -> Unit, onExit: () -> Unit, onOpen: (() -> Unit)? = null) {
    val pnlPct = if (p.avgPrice > 0) (p.lastPrice - p.avgPrice) / p.avgPrice * 100 * (if (p.isLong) 1 else -1) else 0.0
    Card(Modifier.fillMaxWidth(), onClick = onOpen) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(p.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); HSpace(8)
                Pill(if (p.isLong) "LONG ${p.qty}" else "SHORT ${-p.qty}", if (p.isLong) Palette.Up else Palette.Down); HSpace(6)
                Pill(productLabel(m, p.product))
                if (p.strategy.isNotBlank()) { HSpace(6); Pill(p.strategy, Palette.Gold) }
            }
            VSpace(10)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Stat("Avg / LTP", "${Format.price(m, p.avgPrice)} → ${Format.price(m, p.lastPrice)}")
                Column(horizontalAlignment = Alignment.End) {
                    Text(Format.signedMoney(m, p.unrealized), color = Palette.pnl(p.unrealized), fontWeight = FontWeight.Bold, fontSize = 16.sp, style = Num)
                    Text(Format.pct(pnlPct), color = Palette.pnl(p.unrealized), fontSize = 12.sp, style = Num)
                }
            }
            VSpace(8)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SL ${p.stopLoss?.let { Format.price(m, it) } ?: "—"}", color = Palette.Down, fontSize = 12.sp, style = Num); HSpace(14)
                Text("Target ${p.target?.let { Format.price(m, it) } ?: "—"}", color = Palette.Up, fontSize = 12.sp, style = Num); HSpace(14)
                Text("since ${Format.time(m, p.openedAt)}", color = Palette.Muted, fontSize = 12.sp)
            }
            VSpace(10)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)) { Text("SL / Target") }
                Button(onClick = onExit, modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.Surface2)) { Text("Exit at market", color = Palette.Text) }
            }
        }
    }
}
