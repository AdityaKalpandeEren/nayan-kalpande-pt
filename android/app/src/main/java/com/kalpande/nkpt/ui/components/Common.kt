package com.kalpande.nkpt.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.data.Market
import com.kalpande.nkpt.domain.Format
import com.kalpande.nkpt.ui.theme.Num
import com.kalpande.nkpt.ui.theme.Palette

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.Surface)
            .border(1.dp, Palette.Line, RoundedCornerShape(14.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(14.dp)
    ) { content() }
}

@Composable
fun ChangeBadge(pct: Double, modifier: Modifier = Modifier) {
    val up = pct >= 0
    Box(
        modifier.clip(RoundedCornerShape(6.dp)).background(if (up) Palette.UpBg else Palette.DownBg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(Format.pct(pct), color = if (up) Palette.Up else Palette.Down, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = Num)
    }
}

@Composable
fun Pill(text: String, color: Color = Palette.Muted, modifier: Modifier = Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1) }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            modifier = Modifier.weight(1f))
        trailing()
    }
}

@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier, color: Color = Palette.Text, sub: String? = null) {
    Column(modifier) {
        Text(label, color = Palette.Muted, fontSize = 11.sp, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, style = Num, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, color = Palette.Muted, fontSize = 11.sp, style = Num, maxLines = 1)
    }
}

@Composable
fun MarketSwitch(selected: Market, onSelect: (Market) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Palette.Surface2).border(1.dp, Palette.Line, RoundedCornerShape(50)).padding(3.dp)
    ) {
        for (m in listOf(Market.NSE, Market.US)) {
            val on = m == selected
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (on) Palette.Accent else Color.Transparent)
                    .clickable { onSelect(m) }.padding(horizontal = 12.dp, vertical = 5.dp)
            ) { Text("${m.flag} ${m.label}", color = if (on) Color.White else Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

@Composable
fun Empty(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 48.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(Palette.Surface2), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Palette.Accent)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Palette.Muted, fontSize = 13.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))

@Composable
fun HSpace(w: Int) = Spacer(Modifier.width(w.dp))

@Composable
fun VSpace(h: Int) = Spacer(Modifier.height(h.dp))

@Composable
fun SymbolAvatar(symbol: String) {
    val hue = (symbol.hashCode().toUInt() % 360u).toFloat()
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(Color.hsv(hue, 0.45f, 0.35f)), contentAlignment = Alignment.Center) {
        Text(symbol.filter { it.isLetterOrDigit() }.take(2), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

@Composable
fun Arranged(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically, content = content)
