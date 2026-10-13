package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Order
import com.kalpande.nkpt.data.OrderStatus
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Empty
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.Pill
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrdersScreen(vm: MainViewModel, ui: UiState, onOpen: (Instrument) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val m = ui.market
    val all = ui.account.orders.sortedByDescending { it.filledAt ?: it.createdAt }
    val open = all.filter { it.status == OrderStatus.OPEN }
    val done = all.filter { it.status == OrderStatus.FILLED }
    val other = all.filter { it.status == OrderStatus.CANCELLED || it.status == OrderStatus.REJECTED }
    Column(Modifier.fillMaxSize()) {
        SecondaryTabRow(selectedTabIndex = tab, containerColor = Palette.Bg, contentColor = Palette.Accent) {
            listOf("Open · ${open.size}", "Executed · ${done.size}", "Cancelled · ${other.size}").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) },
                    selectedContentColor = Palette.Accent, unselectedContentColor = Palette.Muted)
            }
        }
        val list = listOf(open, done, other)[tab]
        if (list.isEmpty()) Empty(Icons.Outlined.ReceiptLong, listOf("No working orders", "No executed orders yet", "Nothing cancelled")[tab],
            "Open a stock from your watchlist and tap Buy or Sell.")
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
            items(list, key = { it.id }) { o ->
                OrderRow(o, onOpen = { onOpen(Instrument(o.market, o.symbol, o.name, o.key)) }, onCancel = { vm.cancel(m, o.id) })
                HorizontalDivider(color = Palette.Line.copy(alpha = 0.5f))
            }
        }
    }
}

@Composable
private fun OrderRow(o: Order, onOpen: () -> Unit, onCancel: () -> Unit) {
    val m = o.market
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill(o.side.name, if (o.side == Side.BUY) Palette.Up else Palette.Down); HSpace(6)
                Text(o.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); HSpace(6)
                Pill(productLabel(m, o.product))
                if (o.reason in setOf("SL", "TARGET", "SQUARE_OFF")) { HSpace(6); Pill(o.reason.replace('_', '-'), Palette.Gold) }
            }
            Text(
                buildString {
                    append("${o.qty} · ${o.type.name.lowercase()}")
                    o.limitPrice?.let { append(" @ ${Format.price(m, it)}") }
                    o.triggerPrice?.let { append(" trigger ${Format.price(m, it)}") }
                    if (o.strategy.isNotBlank()) append(" · ${o.strategy}")
                },
                color = Palette.Muted, fontSize = 12.sp,
            )
            Text(
                when (o.status) {
                    OrderStatus.FILLED -> "Filled ${Format.time(m, o.filledAt ?: o.createdAt)} · charges ${Format.money(m, o.charges)}"
                    OrderStatus.OPEN -> "Placed ${Format.time(m, o.createdAt)}"
                    else -> "${o.status.name.lowercase().replaceFirstChar { it.uppercase() }} · ${o.reason}"
                },
                color = Palette.Muted, fontSize = 11.sp,
            )
        }
        if (o.status == OrderStatus.FILLED) Text(Format.price(m, o.fillPrice ?: 0.0), fontWeight = FontWeight.SemiBold, style = Num)
        if (o.status == OrderStatus.OPEN) TextButton(onClick = onCancel) { Text("Cancel", color = Palette.Down) }
    }
}
