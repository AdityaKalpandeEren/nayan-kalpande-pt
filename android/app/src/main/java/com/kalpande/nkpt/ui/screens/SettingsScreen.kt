package com.kalpande.nkpt.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.BuildConfig
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.data.Tokens
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.Card
import com.kalpande.nkpt.ui.components.SectionTitle
import com.kalpande.nkpt.ui.components.VSpace
import com.kalpande.nkpt.ui.theme.Palette
import java.util.Locale

@Composable
fun SettingsScreen(vm: MainViewModel, ui: UiState) {
    var token by remember(ui.token) { mutableStateOf(ui.token) }
    var reset by remember { mutableStateOf<Market?>(null) }
    var slipUs by remember(ui.trade) { mutableStateOf(ui.trade.slippageBpsUs.toString()) }
    var slipNse by remember(ui.trade) { mutableStateOf(ui.trade.slippageBpsNse.toString()) }
    var mis by remember(ui.trade) { mutableStateOf((ui.trade.nseIntradayMargin * 100).toInt().toString()) }
    var shortM by remember(ui.trade) { mutableStateOf((ui.trade.usShortMargin * 100).toInt().toString()) }
    val colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line)
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::backupTo) }
    val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::readBackup) }
    val pending by vm.pendingRestore.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp)) {
        SectionTitle("NSE live prices (Upstox)")
        Card(Modifier.fillMaxWidth()) {
            Column {
                val exp = Tokens.expiry(ui.token)
                val nowS = System.currentTimeMillis() / 1000
                Text(
                    when {
                        ui.token.isBlank() -> "Without a token NSE prices come from 1-minute candles (up to ~1 minute behind). Charts, search and fills work either way."
                        exp != null && exp < nowS -> "⚠️ This token expired on ${Format.time(Market.NSE, exp, "dd MMM yyyy")} - paste a fresh one. NSE prices are back on 1-minute candles."
                        exp != null -> "✅ Live NSE quotes on · ${if (Tokens.isExtended(ui.token)) "extended token, " else ""}valid until ${Format.time(Market.NSE, exp, "dd MMM yyyy")}" +
                            if (exp - nowS < 7 * 86400) " - expires soon, paste a new one" else ""
                        else -> "Token saved - NSE quotes are live."
                    },
                    color = if (exp != null && exp - nowS < 7 * 86400) Palette.Down else Palette.Muted, fontSize = 12.sp,
                )
                VSpace(10)
                OutlinedTextField(token, { token = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Upstox access token") }, visualTransformation = PasswordVisualTransformation(),
                    shape = RoundedCornerShape(10.dp), colors = colors)
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { vm.setToken(token) }, shape = RoundedCornerShape(10.dp)) { Text("Save") }
                    OutlinedButton(onClick = { token = ""; vm.setToken("") }, shape = RoundedCornerShape(10.dp)) { Text("Remove") }
                }
                VSpace(6)
                Text("Use your long-lived Upstox (extended) token - the same one as the NSE alert bot. It stays on this phone only: it is never put in backups, never sent anywhere except Upstox.", color = Palette.Muted, fontSize = 11.sp)
            }
        }

        SectionTitle("Fill model")
        Card(Modifier.fillMaxWidth()) {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("US slippage (bps)", slipUs, { slipUs = it }, Modifier.weight(1f), colors)
                    NumField("NSE slippage (bps)", slipNse, { slipNse = it }, Modifier.weight(1f), colors)
                }
                VSpace(8)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumField("NSE intraday margin %", mis, { mis = it }, Modifier.weight(1f), colors)
                    NumField("US short margin %", shortM, { shortM = it }, Modifier.weight(1f), colors)
                }
                VSpace(8)
                Text("Slippage is added against you on market / stop fills. 1 bp = 0.01%. NSE intraday 20% = 5x leverage.", color = Palette.Muted, fontSize = 11.sp)
                VSpace(8)
                Button(onClick = {
                    vm.setTrade(ui.trade.copy(
                        slippageBpsUs = slipUs.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: ui.trade.slippageBpsUs,
                        slippageBpsNse = slipNse.toDoubleOrNull()?.coerceIn(0.0, 100.0) ?: ui.trade.slippageBpsNse,
                        nseIntradayMargin = (mis.toDoubleOrNull()?.coerceIn(5.0, 100.0) ?: 20.0) / 100,
                        usShortMargin = (shortM.toDoubleOrNull()?.coerceIn(100.0, 300.0) ?: 150.0) / 100,
                    ))
                }, shape = RoundedCornerShape(10.dp)) { Text("Apply") }
            }
        }

        SectionTitle("Backup & restore")
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("One file with every order, closed trade, open position, equity curve point, watchlist and strategy tag of both " +
                    "accounts (your Upstox token is never included). Keep a copy in Drive - and use it to study which strategies " +
                    "to automate in the US / NSE alert bots.", color = Palette.Muted, fontSize = 12.sp)
                VSpace(10)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { saver.launch("nkpt_backup_${java.time.LocalDate.now()}.json") }, shape = RoundedCornerShape(10.dp)) { Text("Back up to file") }
                    OutlinedButton(onClick = { opener.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        shape = RoundedCornerShape(10.dp)) { Text("Restore from file") }
                }
            }
        }

        SectionTitle("Accounts")
        Market.entries.forEach { m ->
            val a = ui.accounts.getValue(m)
            Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${m.flag} ${m.label} paper account", fontWeight = FontWeight.SemiBold)
                        Text("Start ${Format.money(m, a.startCash)} · ${a.trades.size} trades · ${a.positions.size} open", color = Palette.Muted, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { reset = m }, shape = RoundedCornerShape(10.dp)) { Text("Reset", color = Palette.Down) }
                }
            }
        }

        SectionTitle("Data")
        Card(Modifier.fillMaxWidth()) {
            Column {
                Text("Symbols: NSE ${"%,d".format(ui.instrumentCount[Market.NSE] ?: 0)} · US ${"%,d".format(ui.instrumentCount[Market.US] ?: 0)} (refreshed daily)", fontSize = 13.sp)
                Text("Last price refresh: ${if (ui.lastRefresh > 0) Format.time(ui.market, ui.lastRefresh, "HH:mm:ss") else "—"}", color = Palette.Muted, fontSize = 12.sp)
                VSpace(6)
                Text("NSE: Upstox (instruments, candles, quotes). US: Yahoo Finance (quotes, candles) + NASDAQ Trader symbol directory.", color = Palette.Muted, fontSize = 12.sp)
                Text("Working orders, stop-losses, targets and the intraday square-off are also checked every ~15 minutes in the background, using 1-minute candles - fills land at the right minute and price even when the app is closed.",
                    color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        SectionTitle("About")
        Card(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Column {
                Text("Nayan Kalpande PT ${BuildConfig.VERSION_NAME}", fontWeight = FontWeight.SemiBold)
                Text("Paper trading only - no real orders are ever sent and no money moves.", color = Palette.Muted, fontSize = 12.sp)
                VSpace(6)
                Text("Charts are drawn natively by the app; live price refreshes every 2 s on the open chart (NSE: with the Upstox token).", color = Palette.Muted, fontSize = 12.sp)
                Text("Charges model: Zerodha published equity rates (NSE); SEC + FINRA TAF sell fees (US).", color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }

    pending?.let { b ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            containerColor = Palette.Surface,
            title = { Text("Restore this backup?") },
            text = {
                Text("Backup from ${java.time.Instant.ofEpochSecond(b.exportedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().toString().replace('T', ' ').take(16)} " +
                    "(app ${b.appVersion}):\n\n${b.summary()}\n\nThe accounts above REPLACE what is on this phone now. Back up first if you want to keep the current data.")
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Restore", color = Palette.Down) } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }

    reset?.let { m ->
        AlertDialog(
            onDismissRequest = { reset = null },
            containerColor = Palette.Surface,
            title = { Text("Reset the ${m.label} account?") },
            text = { Text("All ${m.label} orders, positions and trade history are deleted and the balance goes back to ${Format.money(m, m.startCash)}. Your watchlist and strategy tags stay.") },
            confirmButton = { TextButton(onClick = { vm.reset(m); reset = null }) { Text("Reset", color = Palette.Down) } },
            dismissButton = { TextButton(onClick = { reset = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NumField(label: String, v: String, onChange: (String) -> Unit, modifier: Modifier, colors: androidx.compose.material3.TextFieldColors) =
    OutlinedTextField(v, { s -> onChange(s.filter { it.isDigit() || it == '.' }) }, label = { Text(label, fontSize = 12.sp) }, singleLine = true,
        modifier = modifier, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(10.dp), colors = colors)

@Suppress("unused")
private fun pct(x: Double) = String.format(Locale.US, "%.0f%%", x * 100)
