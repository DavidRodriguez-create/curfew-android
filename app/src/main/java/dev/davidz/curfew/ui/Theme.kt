package dev.davidz.curfew.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0E1116)
val Surface1 = Color(0xFF151A21)
val Surface2 = Color(0xFF1D242D)
val Outline = Color(0xFF2A323D)
val Accent = Color(0xFF7DD3FC)
val AccentInk = Color(0xFF05121A)
val Warn = Color(0xFFFBBF24)
val Danger = Color(0xFFF87171)
val Good = Color(0xFF4ADE80)
val TextHigh = Color(0xFFF8FAFC)
val TextMuted = Color(0xFF94A3B8)
val TextFaint = Color(0xFF64748B)

private val CurfewColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    secondary = Accent,
    onSecondary = AccentInk,
    background = Ink,
    onBackground = TextHigh,
    surface = Surface1,
    onSurface = TextHigh,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextMuted,
    outline = Outline,
    error = Danger,
    onError = AccentInk,
)

@Composable
fun CurfewTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = CurfewColors, content = content)
}
