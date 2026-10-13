package com.kalpande.nkpt.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kalpande.nkpt.ui.theme.Palette

/** (open, close) of each intro candle, 0..1 = bottom..top of the chart: a climb with one pullback. */
private val bars = listOf(
    .18f to .26f, .26f to .22f, .22f to .34f, .34f to .40f, .40f to .36f, .36f to .48f, .48f to .55f, .55f to .50f,
    .50f to .47f, .47f to .58f, .58f to .66f, .66f to .63f, .63f to .74f, .74f to .80f, .80f to .77f, .77f to .90f,
)

/**
 * Opening animation: candles rise one by one, a gold trend line traces through them with a glowing
 * tip, then the name and tagline slide in. Shown after the system splash while the symbol lists load.
 */
@Composable
fun Intro(status: String, modifier: Modifier = Modifier) {
    val t = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, tween(1300, easing = FastOutSlowInEasing)) }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(500); title.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
    val loop = rememberInfiniteTransition(label = "intro")
    val pulse by loop.animateFloat(0.5f, 1f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val dots by loop.animateFloat(0f, 3f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "dots")

    Box(
        modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0A1E4A), Palette.Bg, Color(0xFF1A0B33)))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(260.dp, 170.dp)) {
                val w = size.width; val h = size.height
                for (i in 1..3) drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, h * i / 4), Offset(w, h * i / 4), 1.5f)
                val step = w / bars.size
                val shown = t.value * bars.size
                val tops = mutableListOf<Offset>()
                bars.forEachIndexed { i, (o, c) ->
                    val p = (shown - i).coerceIn(0f, 1f)
                    val cx = step * i + step / 2
                    val up = c >= o
                    val col = if (up) Palette.Up else Palette.Down
                    val yo = h - o * h; val yc = h - c * h
                    val top = minOf(yo, yc); val bot = maxOf(yo, yc)
                    tops += Offset(cx, yc)
                    if (p <= 0f) return@forEachIndexed
                    val grow = bot - (bot - top) * p
                    drawLine(col.copy(alpha = p), Offset(cx, bot + 10f), Offset(cx, bot - (bot - top + 20f) * p), 3f, StrokeCap.Round)
                    drawRoundRect(col.copy(alpha = p), Offset(cx - step * 0.28f, grow), Size(step * 0.56f, (bot - grow).coerceAtLeast(2f)), CornerRadius(3f))
                }
                // trend line traced up to the newest candle, with a glowing tip
                val path = Path().apply { tops.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y + 18f) else lineTo(o.x, o.y + 18f) } }
                val pm = PathMeasure().apply { setPath(path, false) }
                val seg = Path()
                val len = pm.length * t.value
                pm.getSegment(0f, len, seg, true)
                drawPath(seg, Brush.linearGradient(listOf(Color(0xFFFFD24D), Palette.Gold)), style = Stroke(6f, cap = StrokeCap.Round))
                val tip = pm.getPosition(len)
                drawCircle(Palette.Gold.copy(alpha = 0.25f * pulse), 26f * pulse, tip)
                drawCircle(Color(0xFFFFE08A), 7f, tip)
            }
            Box(Modifier.height(28.dp))
            Row(
                Modifier.alpha(title.value).graphicsLayer { translationY = (1 - title.value) * 40f },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Nayan Kalpande", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold,
                    style = TextStyle(brush = Brush.linearGradient(listOf(Color.White, Color(0xFFFFE08A), Palette.Gold))))
                Box(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Palette.Accent).padding(horizontal = 9.dp, vertical = 3.dp)) {
                    Text("PT", fontSize = 18.sp, fontWeight = FontWeight.Black, color = Color.White)
                }
            }
            Text(
                "Paper trade 🇮🇳 NSE & 🇺🇸 US · charts · analytics",
                color = Palette.Muted, fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp).alpha(title.value).graphicsLayer { translationY = (1 - title.value) * 30f },
            )
            Box(Modifier.height(36.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(3) { i ->
                    val a = (1f - kotlin.math.abs(dots - i - 0.5f).coerceAtMost(1f)).coerceIn(0.25f, 1f)
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent.copy(alpha = a)))
                }
            }
            Text(status, color = Palette.Muted.copy(alpha = 0.8f), fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
