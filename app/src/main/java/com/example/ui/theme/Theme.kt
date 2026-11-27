package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = CobaltPrimaryLight,
    onPrimary = Color(0xFF00245C),
    primaryContainer = CobaltContainerDark,
    onPrimaryContainer = OnCobaltContainerDark,
    secondary = SlateSecondaryLight,
    onSecondary = Color(0xFF0F172A),
    secondaryContainer = SlateContainerDark,
    onSecondaryContainer = InkOnSurfaceDark,
    tertiary = SignalEmeraldLight,
    onTertiary = Color(0xFF022C22),
    tertiaryContainer = SignalEmeraldContainerDark,
    onTertiaryContainer = Color(0xFFA7F3D0),
    background = CarbonBackgroundDark,
    onBackground = InkOnSurfaceDark,
    surface = CarbonSurfaceDark,
    onSurface = InkOnSurfaceDark,
    surfaceVariant = CarbonSurfaceVariantDark,
    onSurfaceVariant = InkMutedDark,
    outline = HairlineDark,
    outlineVariant = Color(0xFF1E293B),
    error = ThermalRedLight,
    onError = Color(0xFF450A0A),
    errorContainer = ThermalRedContainerDark,
    onErrorContainer = Color(0xFFFECACA),
)

private val LightColorScheme = lightColorScheme(
    primary = CobaltPrimary,
    onPrimary = Color.White,
    primaryContainer = CobaltContainerLight,
    onPrimaryContainer = OnCobaltContainerLight,
    secondary = SlateSecondary,
    onSecondary = Color.White,
    secondaryContainer = SlateContainerLight,
    onSecondaryContainer = InkOnSurfaceLight,
    tertiary = SignalEmerald,
    onTertiary = Color.White,
    tertiaryContainer = SignalEmeraldContainerLight,
    onTertiaryContainer = Color(0xFF064E3B),
    background = PaperBackgroundLight,
    onBackground = InkOnSurfaceLight,
    surface = PaperSurfaceLight,
    onSurface = InkOnSurfaceLight,
    surfaceVariant = PaperSurfaceVariantLight,
    onSurfaceVariant = InkMutedLight,
    outline = HairlineLight,
    outlineVariant = Color(0xFFE2E8F0),
    error = ThermalRed,
    onError = Color.White,
    errorContainer = ThermalRedContainerLight,
    onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun PrintBridgeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PrintBridgeTypography,
        content = content,
    )
}
