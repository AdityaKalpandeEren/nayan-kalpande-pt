package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.ClosedTrade
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Position
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Card
import com.kalpande.nkpt.ui.components.Empty
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.Pill
import com.kalpande.nkpt.ui.components.Stat
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.summary
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PortfolioScreen(vm: MainViewModel, ui: UiState, onOpen: (Instrument) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Position?>(null) }
    val a = ui.account
    val m = ui.market
    val sm = summary(a, ui.quotes, ui.trade)
    Column(Modifier.fillMaxSize()) {
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Equity", Format.money(m, sm.equity))
                    Stat("Unrealised", Format.signedMoney(m, sm.unrealized), color = Palette.pnl(sm.unrealized))
                    Stat("Realised", Format.signedMoney(m, a.trades.sumOf { it.net }), color = Palette.pnl(a.trades.sumOf { it.net }))
                }
                VSpace(10)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Stat("Cash", Format.compact(m, sm.cash))
                    Stat("Buying power", Format.compact(m, sm.buyingPower))
                    Stat("Charges paid", Format.money(m, a.chargesPaid))
                }
            }
        }
        SecondaryTabRow(selectedTabIndex = tab, containerColor = Palette.Bg, contentColor = Palette.Accent) {
            listOf("Positions · ${a.positions.size}", "Trade journal · ${a.trades.size}").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) },
                    selectedContentColor = Palette.Accent, unselectedContentColor = Palette.Muted)
            }
        }
        if (tab == 0) {
            if (a.positions.isEmpty()) Empty(Icons.Outlined.AccountBalanceWallet, "No open positions",
                "Intraday positions square off before the close; Hold positions stay until you exit.")
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(a.positions.sortedBy { it.symbol }, key = { it.id }) { p ->
                    PositionCard(m, p, onEdit = { editing = p }, onExit = { vm.exit(p) }, onOpen = { onOpen(Instrument(m, p.symbol, p.name, p.key)) })
                }
            }
        } else {
            if (a.trades.isEmpty()) Empty(Icons.Outlined.History, "No closed trades yet", "Every closed trade lands here with its P&L, R-multiple and strategy.")
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                items(a.trades.sortedByDescending { it.closedAt }, key = { it.id }) { t ->
                    TradeRow(m, t)
                    HorizontalDivider(color = Palette.Line.copy(alpha = 0.5f))
                }
            }
        }
    }
    editing?.let { p -> EditExitsDialog(vm, p, onDismiss = { editing = null }) }
}

@Composable
fun TradeRow(m: Market, t: ClosedTrade) {
    Row(Modifier.fillMaxWidth().padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); HSpace(6)
                Pill(if (t.direction == Side.BUY) "LONG" else "SHORT", if (t.direction == Side.BUY) Palette.Up else Palette.Down); HSpace(6)
                Pill(when (t.exitReason) { "SL" -> "Stop-loss"; "TARGET" -> "Target"; "SQUARE_OFF" -> "Square-off"; else -> "Manual" },
                    when (t.exitReason) { "SL" -> Palette.Down; "TARGET" -> Palette.Up; else -> Palette.Muted })
            }
            Text("${t.qty} × ${Format.price(m, t.entryPrice)} → ${Format.price(m, t.exitPrice)} · ${Format.duration(t.holdSeconds)}" +
                (if (t.strategy.isNotBlank()) " · ${t.strategy}" else ""), color = Palette.Muted, fontSize = 12.sp, style = Num)
            Text("Closed ${Format.time(m, t.closedAt)} · charges ${Format.money(m, t.charges)}", color = Palette.Muted, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.signedMoney(m, t.net), color = Palette.pnl(t.net), fontWeight = FontWeight.Bold, style = Num)
            t.rMultiple?.let { Text(String.format(Locale.US, "%+.2fR", it), color = Palette.pnl(it), fontSize = 12.sp, style = Num) }
        }
    }
}
