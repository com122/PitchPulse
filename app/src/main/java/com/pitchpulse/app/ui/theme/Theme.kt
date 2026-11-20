package com.pitchpulse.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object PP {
    val Background = Color(0xFF0D1117)
    val Card = Color(0xFF161B22)
    val CardHigh = Color(0xFF1C232C)
    val Border = Color(0xFF30363D)
    val Grass = Color(0xFF10B981)
    val GrassDim = Color(0xFF0B7A57)
    val Sky = Color(0xFF38BDF8)
    val Amber = Color(0xFFFBBF24)
    val Orange = Color(0xFFF97316)
    val Red = Color(0xFFEF4444)
    val Text = Color(0xFFE6EDF3)
    val Muted = Color(0xFF8B949E)
    val PitchDark = Color(0xFF0F3D2E)
    val PitchLight = Color(0xFF12473A)

    val ZoneColors = listOf(Color(0xFF60A5FA), Color(0xFF34D399), Amber, Orange, Red)
}

private val Colors = darkColorScheme(
    primary = PP.Grass,
    onPrimary = PP.Background,
    primaryContainer = PP.GrassDim,
    onPrimaryContainer = PP.Text,
    secondary = PP.Sky,
    onSecondary = PP.Background,
    tertiary = PP.Amber,
    background = PP.Background,
    onBackground = PP.Text,
    surface = PP.Background,
    onSurface = PP.Text,
    surfaceVariant = PP.Card,
    onSurfaceVariant = PP.Muted,
    surfaceContainer = PP.Card,
    surfaceContainerHigh = PP.CardHigh,
    surfaceContainerHighest = PP.CardHigh,
    surfaceContainerLow = PP.Card,
    surfaceContainerLowest = PP.Background,
    outline = PP.Border,
    outlineVariant = PP.Border,
    error = PP.Red,
)

/** Monospaced styles for key numbers. */
object NumberStyle {
    val Large = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 26.sp)
    val Medium = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 18.sp)
    val Small = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 13.sp)
}

@Composable
fun PitchPulseTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, typography = Typography(), content = content)
}
