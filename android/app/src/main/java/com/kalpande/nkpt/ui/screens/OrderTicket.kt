package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.OrderRequest
import com.kalpande.nkpt.data.OrderType
import com.kalpande.nkpt.data.Position
import com.kalpande.nkpt.data.Product
import com.kalpande.nkpt.data.Side
import com.kalpande.nkpt.domain.Charges
import com.kalpande.nkpt.domain.Engine
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor

private fun Double.fmt2() = String.format(Locale.US, "%.2f", this)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderTicket(vm: MainViewModel, ui: UiState, ins: Instrument, initialSide: Side, ltp: Double?, onDismiss: () -> Unit) {
    val m = ins.market
    val a = ui.accounts.getValue(m)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val held = a.positions.find { it.key == ins.key && it.product == Product.HOLD }
    var side by remember { mutableStateOf(initialSide) }
    var product by remember { mutableStateOf(if (m == Market.NSE && initialSide == Side.SELL && held == null) Product.INTRADAY else Product.HOLD) }
    var type by remember { mutableStateOf(OrderType.MARKET) }
    var qtyText by remember { mutableStateOf(held?.takeIf { initialSide == Side.SELL }?.qty?.toString() ?: "1") }
    var priceText by remember { mutableStateOf(ltp?.fmt2() ?: "") }
    var exits by remember { mutableStateOf(false) }
    var slText by remember { mutableStateOf("") }
    var tgText by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("") }
    var newStrategy by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val qty = qtyText.toIntOrNull() ?: 0
    val px = when (type) { OrderType.MARKET -> ltp; else -> priceText.toDoubleOrNull() } ?: ltp ?: 0.0
    val sideColor = if (side == Side.BUY) Palette.Up else Palette.Down
    val pos = a.positions.find { it.key == ins.key && it.product == product }
    val adds = Engine.addedQty(pos?.qty ?: 0, if (side == Side.BUY) qty else -qty)
    val longAfter = ((pos?.qty ?: 0) + (if (side == Side.BUY) qty else -qty)).let { if (it == 0) side == Side.BUY else it > 0 }
    val rate = Engine.marginRate(m, product, longAfter, ui.trade)
    val margin = adds * px * rate
    val bp = Engine.buyingPower(a, ui.trade)
    val charges = Charges.of(m, side, product, qty.coerceAtLeast(0), px).total
    val sl = slText.toDoubleOrNull()
    val tg = tgText.toDoubleOrNull()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Palette.Surface, dragHandle = null) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding().padding(16.dp)) {
            // header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${side.name} ${ins.symbol}", color = sideColor, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("${m.flag} LTP ${ltp?.let { Format.price(m, it) } ?: "—"} · ${ins.name}", color = Palette.Muted, fontSize = 12.sp, maxLines = 1)
                }
                Segmented(listOf("Buy" to Side.BUY, "Sell" to Side.SELL), side, { if (it == Side.BUY) Palette.Up else Palette.Down }) { side = it }
            }
            VSpace(14)
            Label("Product")
            Segmented(listOf(productLabel(m, Product.INTRADAY) to Product.INTRADAY, productLabel(m, Product.HOLD) to Product.HOLD), product, { Palette.Accent }) { product = it }
            Text(
                if (product == Product.INTRADAY) "Squared off automatically at ${if (m == Market.NSE) "15:20 IST" else "15:55 ET"}" +
                    (if (m == Market.NSE) " · 5x margin · shorting allowed" else "")
                else if (m == Market.NSE) "Delivery - hold for days or months · no short selling" else "Hold as long as you like · shorts need 150% margin",
                color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp),
            )
            VSpace(12)
            Label("Order type")
            Segmented(listOf("Market" to OrderType.MARKET, "Limit" to OrderType.LIMIT, "Stop" to OrderType.STOP), type, { Palette.Accent }) { type = it }
            VSpace(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Label("Quantity")
                    NumberField(qtyText, { qtyText = it.filter { c -> c.isDigit() }.take(7) }, KeyboardType.Number)
                }
                if (type != OrderType.MARKET) Column(Modifier.weight(1f)) {
                    Label(if (type == OrderType.LIMIT) "Limit price" else "Trigger price")
                    NumberField(priceText, { priceText = it }, KeyboardType.Decimal)
                }
            }
            Row(Modifier.padding(top = 6.dp)) {
                listOf(0.25, 0.5, 1.0).forEach { f ->
                    QuickChip(if (f == 1.0) "Max" else "${(f * 100).toInt()}%") {
                        if (px > 0) qtyText = floor(bp * f / (px * rate)).toInt().coerceAtLeast(0).toString()
                    }
                }
                pos?.let { p -> QuickChip("Close ${abs(p.qty)}") { side = if (p.isLong) Side.SELL else Side.BUY; qtyText = abs(p.qty).toString() } }
            }
            VSpace(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stop-loss & target", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Switch(exits, { exits = it }, colors = SwitchDefaults.colors(checkedTrackColor = Palette.Accent))
            }
            if (exits) {
                val dir = if (longAfter) 1 else -1
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Label("Stop-loss", Palette.Down)
                        NumberField(slText, { slText = it }, KeyboardType.Decimal)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(1, 2, 3, 5).forEach { p -> QuickChip("$p%") { if (px > 0) slText = (px * (1 - dir * p / 100.0)).fmt2() } }
                        }
                    }
                    Column(Modifier.weight(1f)) {
                        Label("Target", Palette.Up)
                        NumberField(tgText, { tgText = it }, KeyboardType.Decimal)
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            listOf(2, 4, 6, 10).forEach { p -> QuickChip("$p%") { if (px > 0) tgText = (px * (1 + dir * p / 100.0)).fmt2() } }
                        }
                    }
                }
                if (sl != null && px > 0 && qty > 0) {
                    val risk = abs(px - sl) * qty
                    val reward = tg?.let { abs(it - px) * qty }
                    Text(
                        "Risk ${Format.money(m, risk)} (${(abs(px - sl) / px * 100).fmt2()}%)" +
                            (reward?.let { " · Reward ${Format.money(m, it)} · 1:${(it / risk).let { r -> String.format(Locale.US, "%.1f", r) }}" } ?: ""),
                        color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), style = Num,
                    )
                }
            }
            VSpace(12)
            Label("Strategy tag (for analytics)")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                a.strategies.forEach { s -> SelectChip(s, strategy == s) { strategy = if (strategy == s) "" else s } }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                OutlinedTextField(newStrategy, { newStrategy = it.take(30) }, singleLine = true, modifier = Modifier.weight(1f),
                    placeholder = { Text("New strategy name", fontSize = 13.sp) }, shape = RoundedCornerShape(10.dp), colors = fieldColors())
                TextButton(onClick = { if (newStrategy.isNotBlank()) { vm.addStrategy(m, newStrategy); strategy = newStrategy.trim(); newStrategy = "" } }) { Text("Add") }
            }
            VSpace(8)
            OutlinedTextField(note, { note = it.take(200) }, modifier = Modifier.fillMaxWidth(), placeholder = { Text("Note: why this trade? (optional)", fontSize = 13.sp) },
                shape = RoundedCornerShape(10.dp), colors = fieldColors(), maxLines = 3)
            VSpace(14)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Palette.Surface2).padding(12.dp)) {
                SummaryRow("Order value", Format.money(m, qty * px))
                SummaryRow(if (adds > 0) "Margin needed" else "Margin needed (closing)", Format.money(m, margin))
                SummaryRow("Est. charges", Format.money(m, charges))
                SummaryRow("Buying power", Format.money(m, bp), if (margin > bp) Palette.Down else Palette.Text)
            }
            error?.let { Text(it, color = Palette.Down, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            VSpace(14)
            Button(
                onClick = {
                    error = null; busy = true
                    scope.launch {
                        val e = vm.place(
                            OrderRequest(ins, side, qty, type, product,
                                limitPrice = if (type == OrderType.LIMIT) priceText.toDoubleOrNull() else null,
                                triggerPrice = if (type == OrderType.STOP) priceText.toDoubleOrNull() else null,
                                stopLoss = if (exits) sl else null, target = if (exits) tg else null,
                                strategy = strategy, note = note)
                        )
                        busy = false
                        if (e == null) { sheet.hide(); onDismiss() } else error = e
                    }
                },
                enabled = !busy && qty > 0,
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = sideColor),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("${side.name} $qty ${ins.symbol} · ${type.name.lowercase().replaceFirstChar { it.uppercase() }}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text("Paper trade - no real order is sent.", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
fun EditExitsDialog(vm: MainViewModel, p: Position, onDismiss: () -> Unit) {
    val m = p.market
    var sl by remember { mutableStateOf(p.stopLoss?.fmt2() ?: "") }
    var tg by remember { mutableStateOf(p.target?.fmt2() ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.Surface,
        title = { Text("${p.symbol} stop-loss & target") },
        text = {
            Column {
                Text("LTP ${Format.price(m, p.lastPrice)} · avg ${Format.price(m, p.avgPrice)} · ${if (p.isLong) "long" else "short"} ${abs(p.qty)}",
                    color = Palette.Muted, fontSize = 12.sp)
                VSpace(10)
                Label("Stop-loss (blank = none)", Palette.Down); NumberField(sl, { sl = it }, KeyboardType.Decimal)
                VSpace(8)
                Label("Target (blank = none)", Palette.Up); NumberField(tg, { tg = it }, KeyboardType.Decimal)
                error?.let { Text(it, color = Palette.Down, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    val e = vm.modifyExits(p, sl.toDoubleOrNull(), tg.toDoubleOrNull())
                    if (e == null) onDismiss() else error = e
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Label(text: String, color: Color = Palette.Muted) =
    Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 4.dp))

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line)

@Composable
private fun NumberField(value: String, onChange: (String) -> Unit, kb: KeyboardType) =
    OutlinedTextField(value, { v -> onChange(v.filter { it.isDigit() || it == '.' }) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = kb), shape = RoundedCornerShape(10.dp), colors = fieldColors(),
        textStyle = Num.copy(fontSize = 16.sp, color = Palette.Text))

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp, top = 4.dp).clip(RoundedCornerShape(8.dp)).border(1.dp, Palette.Line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, fontSize = 12.sp, color = Palette.Text) }
}

@Composable
private fun SelectChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(if (on) Palette.Gold.copy(alpha = 0.2f) else Palette.Surface2)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 13.sp, color = if (on) Palette.Gold else Palette.Muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal) }
}

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, color: (T) -> Color, onSelect: (T) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Palette.Surface2).padding(3.dp)) {
        options.forEach { (label, v) ->
            val on = v == selected
            Box(
                Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) color(v) else Color.Transparent)
                    .clickable { onSelect(v) }.padding(horizontal = 14.dp, vertical = 7.dp)
            ) { Text(label, color = if (on) Color.White else Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String, color: Color = Palette.Text) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = Palette.Muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, style = Num)
    }
}
