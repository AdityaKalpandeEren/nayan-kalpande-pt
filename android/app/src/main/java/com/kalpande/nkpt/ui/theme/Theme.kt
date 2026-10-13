package com.kalpande.nkpt.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Bg = Color(0xFF0B0F17)
    val Surface = Color(0xFF121826)
    val Surface2 = Color(0xFF1A2232)
    val Line = Color(0xFF263044)
    val Text = Color(0xFFE6EAF2)
    val Muted = Color(0xFF8B95A7)
    val Accent = Color(0xFF4C8DFF)
    val Up = Color(0xFF00C087)
    val Down = Color(0xFFF6465D)
    val Gold = Color(0xFFF0B90B)
    val UpBg = Color(0x2600C087)
    val DownBg = Color(0x26F6465D)

    fun pnl(x: Double) = if (x >= 0) Up else Down
}

private val scheme = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Color.White,
    secondary = Palette.Gold,
    background = Palette.Bg,
    onBackground = Palette.Text,
    surface = Palette.Surface,
    onSurface = Palette.Text,
    surfaceVariant = Palette.Surface2,
    onSurfaceVariant = Palette.Muted,
    surfaceContainer = Palette.Surface,
    surfaceContainerHigh = Palette.Surface2,
    surfaceContainerLow = Palette.Bg,
    outline = Palette.Line,
    outlineVariant = Palette.Line,
    error = Palette.Down,
)

/** Tabular figures so prices line up column by column. */
val Num = TextStyle(fontFeatureSettings = "tnum")

private val type = Typography().let { t ->
    t.copy(
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.3.sp),
    )
}

@Composable
fun NkptTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = type) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides Palette.Text, content = content)
    }
}
