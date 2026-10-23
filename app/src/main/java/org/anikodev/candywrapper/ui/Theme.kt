package org.anikodev.candywrapper.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * CandyWrapper's palette, from CandyWrapper's docs/design-tokens.md (T3's resolved CSS tokens),
 * plus the plum of CandyWrapper's own send button. Read it with `Candy.colors` inside [CandyTheme].
 */
@Immutable
data class CandyColors(
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val card: Color,
    val muted: Color,
    val mutedForeground: Color,
    val border: Color,
    /** `--input`: the border of fields and outlined controls. */
    val input: Color,
    /** `--accent`: hover and pressed fills, selected rows. */
    val accent: Color,
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
    val infoSurface: Color,
    val diffAddition: Color,
    val diffDeletion: Color,
    val diffAdditionSurface: Color,
    val diffDeletionSurface: Color,
    val iconMuted: Color,
    /** The send button: CandyWrapper's plum, a deliberate difference from T3's blue (design.md). */
    val send: Color,
    /** The overlay a pressed control draws over itself. */
    val pressed: Color,
    val scrim: Color,
    // Syntax colours for code blocks, after T3's Shiki themes (github-light, github-dark).
    val synKeyword: Color,
    val synString: Color,
    val synComment: Color,
    val synNumber: Color,
    val synType: Color,
    val synFunction: Color,
)

private val Light = CandyColors(
    dark = false,
    background = Color(0xFFFCFCFC),
    foreground = Color(0xFF27272A),
    card = Color(0xFFFFFFFF),
    muted = Color(0xFFF4F4F5),
    mutedForeground = Color(0xFF71717B),
    border = Color(0xFFE4E4E7),
    input = Color(0xFFD4D4D8),
    accent = Color(0xFFF4F4F5),
    primary = Color(0xFF1B4ED8),
    primaryForeground = Color(0xFFFFFFFF),
    messageSurface = Color(0xFFF4F4F5),
    codeBackground = Color(0xFFF7F7F8),
    success = Color(0xFF007A55),
    warning = Color(0xFFBB4D00),
    error = Color(0xFFC10007),
    info = Color(0xFF1447E6),
    warningSurface = Color(0x14FD9900),
    errorSurface = Color(0x14FB2C36),
    infoSurface = Color(0x142B7FFF),
    diffAddition = Color(0xFF009966),
    diffDeletion = Color(0xFFE7000B),
    diffAdditionSurface = Color(0x1A00BC7D),
    diffDeletionSurface = Color(0x1AFB2C36),
    iconMuted = Color(0xFF71717B),
    send = Color(0xFF8A4A7C),
    pressed = Color(0x0F18181B),
    scrim = Color(0x52000000),
    synKeyword = Color(0xFFCF222E),
    synString = Color(0xFF0A3069),
    synComment = Color(0xFF6E7781),
    synNumber = Color(0xFF0550AE),
    synType = Color(0xFF953800),
    synFunction = Color(0xFF8250DF),
)

private val Dark = CandyColors(
    dark = true,
    background = Color(0xFF0A0A0A),
    foreground = Color(0xFFF5F5F5),
    card = Color(0xFF111111),
    muted = Color(0xFF161616),
    mutedForeground = Color(0xFF8E8E8E),
    border = Color(0x1AFFFFFF),
    input = Color(0x24FFFFFF),
    accent = Color(0x0FFFFFFF),
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
    infoSurface = Color(0x292B7FFF),
    diffAddition = Color(0xFF00D492),
    diffDeletion = Color(0xFFFF6467),
    diffAdditionSurface = Color(0x2400BC7D),
    diffDeletionSurface = Color(0x24FB414A),
    iconMuted = Color(0xFF8E8E8E),
    send = Color(0xFFA45E8C),
    pressed = Color(0x14FFFFFF),
    scrim = Color(0x99000000),
    synKeyword = Color(0xFFFF7B72),
    synString = Color(0xFFA5D6FF),
    synComment = Color(0xFF8B949E),
    synNumber = Color(0xFF79C0FF),
    synType = Color(0xFFFFA657),
    synFunction = Color(0xFFD2A8FF),
)

/**
 * Text styles on T3's scale (`--text-xs` 12, `--text-sm` 14, `--text-base` 16), one step larger
 * for chat text on a phone. Colour is set where the text is drawn.
 */
object CandyType {
    val title = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp)
    val heading = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)
    val ui = TextStyle(fontSize = 14.sp, lineHeight = 20.sp)
    val uiMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium)
    val small = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val label = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium)
    val mono = TextStyle(fontSize = 13.sp, lineHeight = 19.sp, fontFamily = FontFamily.Monospace)
    val monoSmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp, fontFamily = FontFamily.Monospace)
}

/** Corner radii from the token sheet: `--radius-sm` 6, `--radius-md` 8, `--radius` 10, `--radius-xl` 14, composer 18. */
object Radius {
    val sm = 6.dp
    val md = 8.dp
    val lg = 10.dp
    val xl = 14.dp
    val composer = 18.dp
}

private val LocalCandyColors = staticCompositionLocalOf { Light }

object Candy {
    val colors: CandyColors
        @Composable get() = LocalCandyColors.current
}

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

@Composable
fun CandyTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val c = if (dark) Dark else Light
    // Material stays only for the updater's card; every other screen draws with the kit in Kit.kt.
    val scheme = if (dark) {
        darkColorScheme(primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.card, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, outline = c.border, outlineVariant = c.border, error = c.error)
    } else {
        lightColorScheme(primary = c.primary, onPrimary = c.primaryForeground, background = c.background, onBackground = c.foreground,
            surface = c.background, onSurface = c.foreground, surfaceVariant = c.card, onSurfaceVariant = c.mutedForeground,
            surfaceContainer = c.card, surfaceContainerHigh = c.card, outline = c.border, outlineVariant = c.border, error = c.error)
    }
    val indication = remember(c.pressed) { PressIndication(c.pressed) }
    val selection = remember(c.primary) { TextSelectionColors(c.primary, c.primary.copy(alpha = 0.3f)) }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalCandyColors provides c,
            LocalIndication provides indication,
            LocalTextSelectionColors provides selection,
            content = content,
        )
    }
}
