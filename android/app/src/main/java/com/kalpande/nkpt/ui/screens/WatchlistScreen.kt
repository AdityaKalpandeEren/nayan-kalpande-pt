package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.POPULAR
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Card
import com.kalpande.nkpt.ui.components.ChangeBadge
import com.kalpande.nkpt.ui.components.Empty
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.Pill
import com.kalpande.nkpt.ui.components.SectionTitle
import com.kalpande.nkpt.ui.components.Stat
import com.kalpande.nkpt.ui.components.SymbolAvatar
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.summary
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette
import kotlinx.coroutines.launch

@Composable
fun WatchlistScreen(vm: MainViewModel, ui: UiState, onSearch: () -> Unit, onOpen: (Instrument) -> Unit) {
    val a = ui.account
    val m = ui.market
    val sm = summary(a, ui.quotes, ui.trade)
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    Text("Portfolio value", color = Palette.Muted, fontSize = 12.sp)
                    Text(Format.money(m, sm.equity), fontSize = 28.sp, fontWeight = FontWeight.Bold, style = Num)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(Format.signedMoney(m, sm.totalPnl), color = Palette.pnl(sm.totalPnl), fontWeight = FontWeight.SemiBold, style = Num)
                        HSpace(8); ChangeBadge(sm.totalPct); HSpace(8)
                        Text("all time", color = Palette.Muted, fontSize = 12.sp)
                    }
                    VSpace(14)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Stat("Today", Format.signedMoney(m, sm.dayPnl), color = Palette.pnl(sm.dayPnl))
                        Stat("Invested", Format.compact(m, sm.invested))
                        Stat("Buying power", Format.compact(m, sm.buyingPower))
                    }
                }
            }
            VSpace(12)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface)
                    .border(1.dp, Palette.Line, RoundedCornerShape(12.dp)).clickable(onClick = onSearch).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, tint = Palette.Muted); HSpace(10)
                Text("Search ${ui.instrumentCount[m]?.let { "%,d".format(it) + " " } ?: ""}${m.label} stocks by name or ticker",
                    color = Palette.Muted, fontSize = 14.sp)
            }
            SectionTitle("Watchlist · ${a.watchlist.size}")
        }
        if (a.watchlist.isEmpty()) {
            item {
                Empty(Icons.Outlined.StarBorder, "Your ${m.label} watchlist is empty", "Search any stock, or start with a popular one:")
                PopularChips(vm, ui, onOpen)
            }
        }
        items(a.watchlist, key = { it.key }) { i -> WatchRow(vm, ui, i, onOpen) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PopularChips(vm: MainViewModel, ui: UiState, onOpen: (Instrument) -> Unit) {
    val scope = rememberCoroutineScope()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        POPULAR.getValue(ui.market).forEach { sym ->
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).clickable {
                    scope.launch { vm.instrumentFor(ui.market, sym)?.let { vm.toggleWatch(it) } }
                }.padding(horizontal = 12.dp, vertical = 7.dp)
            ) { Text("+ $sym", fontSize = 13.sp, color = Palette.Text) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WatchRow(vm: MainViewModel, ui: UiState, i: Instrument, onOpen: (Instrument) -> Unit) {
    val q = ui.quotes[i.key]
    var menu by remember { mutableStateOf(false) }
    val held = ui.account.positions.filter { it.key == i.key }.sumOf { it.qty }
    Box {
        Row(
            Modifier.fillMaxWidth().combinedClickable(onClick = { onOpen(i) }, onLongClick = { menu = true }).padding(vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SymbolAvatar(i.symbol); HSpace(12)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(i.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                    if (held != 0) { HSpace(6); Pill(if (held > 0) "$held held" else "${-held} short", if (held > 0) Palette.Up else Palette.Down) }
                }
                Text(i.name, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(q?.let { Format.price(ui.market, it.price) } ?: "—", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, style = Num)
                if (q != null) ChangeBadge(q.changePct) else Text("loading", color = Palette.Muted, fontSize = 11.sp)
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Move up") }, leadingIcon = { Icon(Icons.Outlined.ArrowUpward, null) }, onClick = { vm.moveWatch(i, true); menu = false })
            DropdownMenuItem(text = { Text("Move down") }, leadingIcon = { Icon(Icons.Outlined.ArrowDownward, null) }, onClick = { vm.moveWatch(i, false); menu = false })
            DropdownMenuItem(text = { Text("Remove") }, leadingIcon = { Icon(Icons.Outlined.Delete, null) }, onClick = { vm.toggleWatch(i); menu = false })
        }
    }
    HorizontalDivider(color = Palette.Line.copy(alpha = 0.5f))
}
