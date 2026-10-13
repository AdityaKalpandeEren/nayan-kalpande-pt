package com.kalpande.nkpt.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kalpande.nkpt.data.Instrument
import com.kalpande.nkpt.ui.MainViewModel
import com.kalpande.nkpt.ui.UiState
import com.kalpande.nkpt.ui.components.HSpace
import com.kalpande.nkpt.ui.components.Pill
import com.kalpande.nkpt.ui.components.SymbolAvatar
import com.kalpande.nkpt.ui.theme.Palette

@Composable
fun SearchScreen(vm: MainViewModel, ui: UiState, onBack: () -> Unit, onOpen: (Instrument) -> Unit) {
    val s by vm.search.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val list = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= s.items.size - 6 } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(nearEnd, s.items.size) { if (nearEnd) vm.loadMore() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            OutlinedTextField(
                value = s.query, onValueChange = vm::setQuery, singleLine = true,
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text("${ui.market.flag} Name or ticker, e.g. ${if (ui.market.label == "NSE") "Reliance, CUPID" else "Apple, NVDA"}") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                trailingIcon = { if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Outlined.Close, "Clear") } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Search),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Palette.Accent, unfocusedBorderColor = Palette.Line),
            )
        }
        Text(
            when {
                !s.listReady && s.items.isEmpty() -> "Loading the ${ui.market.label} symbol list…"
                s.query.isBlank() -> "All ${"%,d".format(s.total)} ${ui.market.label} symbols, A-Z"
                else -> "${"%,d".format(s.total)} matches · showing ${s.items.size}"
            },
            color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(state = list, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(s.items, key = { _, i -> i.key }) { _, i ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpen(i) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SymbolAvatar(i.symbol); HSpace(12)
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(i.symbol, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            HSpace(6)
                            Pill(i.exchange.ifBlank { i.market.label }, if (i.tradable) Palette.Muted else Palette.Gold)
                        }
                        Text(i.name, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val watched = ui.accounts[i.market]?.watchlist?.any { it.key == i.key } == true
                    IconButton(onClick = { vm.toggleWatch(i) }) {
                        Icon(if (watched) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Watch", tint = if (watched) Palette.Gold else Palette.Muted)
                    }
                }
                HorizontalDivider(color = Palette.Line.copy(alpha = 0.4f), modifier = Modifier.padding(start = 66.dp))
            }
            if (s.loading) item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }
            }
        }
    }
}
