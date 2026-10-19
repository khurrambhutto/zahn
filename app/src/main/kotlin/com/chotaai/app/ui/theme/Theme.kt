package com.chotaai.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Forest green on warm paper; amber for streaks and highlights.
private val Forest = Color(0xFF2F5D50)
private val ForestLight = Color(0xFF8FC1AE)
private val Amber = Color(0xFFC98B2B)
private val AmberLight = Color(0xFFF0C27A)
private val Paper = Color(0xFFF7F5F0)
private val Ink = Color(0xFF1D2421)

private val LightColors = lightColorScheme(
    primary = Forest,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E8DF),
    onPrimaryContainer = Color(0xFF0E2B22),
    secondary = Amber,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF8E3C2),
    onSecondaryContainer = Color(0xFF3D2800),
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Color(0xFFE6E3DB),
    onSurfaceVariant = Color(0xFF4F5652),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF2F0EA),
    surfaceContainer = Color(0xFFEDEAE3),
    surfaceContainerHigh = Color(0xFFE7E4DC),
    outline = Color(0xFF7F8782),
    outlineVariant = Color(0xFFCFCCC4),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = ForestLight,
    onPrimary = Color(0xFF0E2B22),
    primaryContainer = Color(0xFF1F4439),
    onPrimaryContainer = Color(0xFFD3E8DF),
    secondary = AmberLight,
    onSecondary = Color(0xFF3D2800),
    secondaryContainer = Color(0xFF5A3E0B),
    onSecondaryContainer = Color(0xFFF8E3C2),
    background = Color(0xFF121614),
    onBackground = Color(0xFFE2E4E1),
    surface = Color(0xFF121614),
    onSurface = Color(0xFFE2E4E1),
    surfaceVariant = Color(0xFF2A302D),
    onSurfaceVariant = Color(0xFFB9C0BB),
    surfaceContainerLowest = Color(0xFF0D100F),
    surfaceContainerLow = Color(0xFF1A1E1C),
    surfaceContainer = Color(0xFF1E2321),
    surfaceContainerHigh = Color(0xFF252A28),
    outline = Color(0xFF89918C),
    outlineVariant = Color(0xFF3F4642),
)

private val ChotaTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 0.2.sp),
    )
}

@Composable
fun ChotaTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ChotaTypography,
        content = content,
    )
}
