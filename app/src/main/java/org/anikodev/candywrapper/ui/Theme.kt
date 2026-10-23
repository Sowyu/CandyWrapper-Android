package org.anikodev.candywrapper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * CandyWrapper's palette, from CandyWrapper's docs/design-tokens.md (T3's resolved CSS tokens).
 * Read it with `Candy.colors` inside [CandyTheme].
 */
@Immutable
data class CandyColors(
    val background: Color,
    val foreground: Color,
    val card: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    val primary: Color,
    val primaryForeground: Color,
    val messageSurface: Color,
    val codeBackground: Color,
    val success: Color,
    val warning: Color,
    val error: Color,
    val info: Color,
    val warningSurface: Color,
    val errorSurface: Color,
)

private val Light = CandyColors(
    background = Color(0xFFFCFCFC),
    foreground = Color(0xFF27272A),
    card = Color(0xFFFFFFFF),
    muted = Color(0xFFF4F4F5),
    mutedForeground = Color(0xFF71717B),
    border = Color(0xFFE4E4E7),
    primary = Color(0xFF1B4ED8),
    primaryForeground = Color(0xFFFFFFFF),
    messageSurface = Color(0xFFF4F4F5),
    codeBackground = Color(0xFFF4F4F5),
    success = Color(0xFF007A55),
    warning = Color(0xFFBB4D00),
    error = Color(0xFFC10007),
    info = Color(0xFF1447E6),
    warningSurface = Color(0x14FD9900),
    errorSurface = Color(0x14FB2C36),
)

private val Dark = CandyColors(
    background = Color(0xFF0A0A0A),
    foreground = Color(0xFFF5F5F5),
    card = Color(0xFF111111),
    muted = Color(0x0AFFFFFF),
    mutedForeground = Color(0xFF818181),
    border = Color(0x14FFFFFF),
    primary = Color(0xFF346BF1),
    primaryForeground = Color(0xFFFFFFFF),
    messageSurface = Color(0x0FFFFFFF),
    codeBackground = Color(0xFF111111),
    success = Color(0xFF00D492),
    warning = Color(0xFFFFBA00),
    error = Color(0xFFFF6467),
    info = Color(0xFF51A2FF),
    warningSurface = Color(0x29FD9900),
    errorSurface = Color(0x29FB414A),
)

private val LocalCandyColors = staticCompositionLocalOf { Light }

object Candy {
    val colors: CandyColors
        @Composable get() = LocalCandyColors.current
}

@Composable
fun CandyTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val c = if (dark) Dark else Light
    val scheme = if (dark) {
        darkColorScheme(primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.card, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, outline = c.border, outlineVariant = c.border, error = c.error)
    } else {
        lightColorScheme(primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.card, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, outline = c.border, outlineVariant = c.border, error = c.error)
    }
    CompositionLocalProvider(LocalCandyColors provides c) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
