package org.anikodev.candywrapper.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * T3 Code's palette, from CandyWrapper's docs/design-tokens.md: every value is the colour Chromium
 * resolves from T3's compiled CSS, in light, dark and the `[data-app-sidebar]` subtree. Names follow
 * the CSS variables (`--muted-foreground` is [mutedForeground]). Read it with `Candy.colors` inside
 * [CandyTheme].
 */
@Immutable
data class CandyColors(
    val dark: Boolean,
    val background: Color,
    val foreground: Color,
    val card: Color,
    val popover: Color,
    val muted: Color,
    val mutedForeground: Color,
    val secondary: Color,
    /** `--secondary-label`: timestamps and counts. */
    val secondaryLabel: Color,
    /** `--accent`: hover and pressed fills, highlighted menu items. */
    val accent: Color,
    val border: Color,
    /** `--input`: the border of fields and outlined controls. */
    val input: Color,
    val ring: Color,
    val primary: Color,
    val primaryForeground: Color,
    val messageSurface: Color,
    val codeBackground: Color,
    val iconMuted: Color,
    val placeholder: Color,
    /** Solid status colours (`--success`, dots and fills) and their text twins (`--success-foreground`). */
    val success: Color,
    val successForeground: Color,
    val warning: Color,
    val warningForeground: Color,
    val warningSurface: Color,
    val error: Color,
    val errorForeground: Color,
    val errorSurface: Color,
    val info: Color,
    val infoForeground: Color,
    /** `--info` at the surface strength T3 uses for warning and error surfaces. */
    val infoSurface: Color,
    val diffAddition: Color,
    val diffAdditionForeground: Color,
    val diffDeletion: Color,
    val diffDeletionForeground: Color,
    /** Diff line backgrounds: `--diff-addition` and `--diff-deletion` at pierre's 10% (light) and 14% (dark). */
    val diffAdditionSurface: Color,
    val diffDeletionSurface: Color,
    val toolErrorIcon: Color,
    val toolbarControl: Color,
    // The `[data-app-sidebar]` subtree.
    val sidebar: Color,
    val sidebarForeground: Color,
    val sidebarMutedForeground: Color,
    val sidebarBorder: Color,
    val sidebarIconColor: Color,
    val sidebarRowHover: Color,
    val sidebarRowActive: Color,
    val sidebarRowSelected: Color,
    val sidebarControlSurface: Color,
    // Thread status label and dot colours (Sidebar.logic.ts:1180-1252).
    val statusWorking: Color,
    val statusWorkingDot: Color,
    val statusApproval: Color,
    val statusApprovalDot: Color,
    val statusInput: Color,
    val statusInputDot: Color,
    val statusPlan: Color,
    val statusPlanDot: Color,
    val statusDone: Color,
    val statusDoneDot: Color,
    /** The send button: CandyWrapper's plum, a deliberate difference from T3's blue (CandyWrapper design.md). */
    val send: Color,
    /** The overlay a pressed control draws over itself: `--accent` strength over any surface. */
    val pressed: Color,
    /** `dialog-backdrop`: the background at 60% (light) or 64% (dark), without the 4px blur. */
    val scrim: Color,
    // Syntax colours for code blocks, after T3's Shiki themes (github-light, github-dark).
    val synKeyword: Color,
    val synString: Color,
    val synComment: Color,
    val synNumber: Color,
    val synType: Color,
    val synFunction: Color,
    /** Settings > Appearance > Redesign: Capy's palette, the art behind the main panel and Hugeicons. */
    val redesign: Boolean = false,
)

private val Light = CandyColors(
    dark = false,
    background = Color(0xFFFCFCFC),
    foreground = Color(0xFF27272A),
    card = Color(0xFFFFFFFF),
    popover = Color(0xFFFFFFFF),
    muted = Color(0xFFFAFAFA),
    mutedForeground = Color(0xFF71717B),
    secondary = Color(0xFFFAFAFA),
    secondaryLabel = Color(0xFF71717B),
    accent = Color(0xFFF4F4F5),
    border = Color(0xFFE4E4E7),
    input = Color(0xFFD4D4D8),
    ring = Color(0xFF1B4ED8),
    primary = Color(0xFF1B4ED8),
    primaryForeground = Color(0xFFFFFFFF),
    messageSurface = Color(0xFFF4F4F5),
    codeBackground = Color(0xFFFFFFFF),
    iconMuted = Color(0xFF71717B),
    placeholder = Color(0xFF71717B),
    success = Color(0xFF00BC7D),
    successForeground = Color(0xFF007A55),
    warning = Color(0xFFFD9A00),
    warningForeground = Color(0xFFBB4D00),
    warningSurface = Color(0x14FD9900),
    error = Color(0xFFFB2C36),
    errorForeground = Color(0xFFC10007),
    errorSurface = Color(0x14FB2C36),
    info = Color(0xFF2B7FFF),
    infoForeground = Color(0xFF1447E6),
    infoSurface = Color(0x142B7FFF),
    diffAddition = Color(0xFF00BC7D),
    diffAdditionForeground = Color(0xFF009966),
    diffDeletion = Color(0xFFFB2C36),
    diffDeletionForeground = Color(0xFFE7000B),
    diffAdditionSurface = Color(0x1A00BC7D),
    diffDeletionSurface = Color(0x1AFB2C36),
    toolErrorIcon = Color(0xFFFB2C36),
    toolbarControl = Color(0xFFFFFFFF),
    sidebar = Color(0xFFFAFAFA),
    sidebarForeground = Color(0xFF27272A),
    sidebarMutedForeground = Color(0xFF71717B),
    sidebarBorder = Color(0xFFE4E4E7),
    sidebarIconColor = Color(0xFFA8A8AE),
    sidebarRowHover = Color(0xFFFCFCFC),
    sidebarRowActive = Color(0xFFFFFFFF),
    sidebarRowSelected = Color(0xFFFFFFFF),
    sidebarControlSurface = Color(0xFFF4F4F5),
    statusWorking = Color(0xFF0084D1),
    statusWorkingDot = Color(0xFF00A6F4),
    statusApproval = Color(0xFFE17100),
    statusApprovalDot = Color(0xFFFD9A00),
    statusInput = Color(0xFF4F39F6),
    statusInputDot = Color(0xFF615FFF),
    statusPlan = Color(0xFF8022FE),
    statusPlanDot = Color(0xFF8E51FF),
    statusDone = Color(0xFF009966),
    statusDoneDot = Color(0xFF00BC7D),
    send = Color(0xFF8A4A7C),
    pressed = Color(0x0F18181B),
    scrim = Color(0x99FCFCFC),
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
    popover = Color(0xFF111111),
    muted = Color(0x08FFFFFF),
    mutedForeground = Color(0xFF818181),
    secondary = Color(0x08FFFFFF),
    secondaryLabel = Color(0xFF818181),
    accent = Color(0x0AFFFFFF),
    border = Color(0x0FFFFFFF),
    input = Color(0x14FFFFFF),
    ring = Color(0xFF346BF1),
    primary = Color(0xFF346BF1),
    primaryForeground = Color(0xFFFFFFFF),
    messageSurface = Color(0x0AFFFFFF),
    codeBackground = Color(0xFF111111),
    iconMuted = Color(0xFF818181),
    placeholder = Color(0xFF818181),
    success = Color(0xFF00BC7D),
    successForeground = Color(0xFF00D492),
    warning = Color(0xFFFD9A00),
    warningForeground = Color(0xFFFFBA00),
    warningSurface = Color(0x29FD9900),
    error = Color(0xFFFB414A),
    errorForeground = Color(0xFFFF6467),
    errorSurface = Color(0x29FB414A),
    info = Color(0xFF2B7FFF),
    infoForeground = Color(0xFF51A2FF),
    infoSurface = Color(0x292B7FFF),
    diffAddition = Color(0xFF00BC7D),
    diffAdditionForeground = Color(0xFF00D492),
    diffDeletion = Color(0xFFFB414A),
    diffDeletionForeground = Color(0xFFFF6467),
    diffAdditionSurface = Color(0x2400BC7D),
    diffDeletionSurface = Color(0x24FB414A),
    toolErrorIcon = Color(0xFFFCA5A5),
    toolbarControl = Color(0xFF111111),
    sidebar = Color(0xFF000000),
    sidebarForeground = Color(0xFFF1F3F7),
    sidebarMutedForeground = Color(0xFFA3A3A3),
    sidebarBorder = Color(0x14FFFFFF),
    sidebarIconColor = Color(0xFF545454),
    sidebarRowHover = Color(0x14F1F3F7),
    sidebarRowActive = Color(0x1CF1F3F7),
    sidebarRowSelected = Color(0x12F1F3F7),
    sidebarControlSurface = Color(0xFF0A0A0A),
    statusWorking = Color(0xCC74D4FF),
    statusWorkingDot = Color(0xCC74D4FF),
    statusApproval = Color(0xE6FFD230),
    statusApprovalDot = Color(0xE6FFD230),
    statusInput = Color(0xE6A3B3FF),
    statusInputDot = Color(0xE6A3B3FF),
    statusPlan = Color(0xE6C4B4FF),
    statusPlanDot = Color(0xE6C4B4FF),
    statusDone = Color(0xE65EE9B5),
    statusDoneDot = Color(0xE65EE9B5),
    send = Color(0xFFA45E8C),
    pressed = Color(0x14FFFFFF),
    scrim = Color(0xA30A0A0A),
    synKeyword = Color(0xFFFF7B72),
    synString = Color(0xFFA5D6FF),
    synComment = Color(0xFF8B949E),
    synNumber = Color(0xFF79C0FF),
    synType = Color(0xFFFFA657),
    synFunction = Color(0xFFD2A8FF),
)

/** Text trimmed to its line box the way CSS draws it, so a 20sp line is 20dp tall. */
private val CssLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun t(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, family: FontFamily = FontFamily.Default) =
    TextStyle(fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontFamily = family, lineHeightStyle = CssLines)

/**
 * T3's type scale (`--text-*` in rem, 1rem = 16sp) in the system sans, Roboto on most phones, as T3
 * uses the system stack. Where T3 sizes a control up for touch below its `sm` breakpoint (buttons
 * and menu items take `text-base`), these use the touch size. Colour is set where text is drawn.
 */
object CandyType {
    /** `text-3xs` is 10px; phones get `text-2xs` (11) so time labels stay readable. */
    val xxs = t(11, 14)
    val xs = t(12, 16)
    val xsMedium = t(12, 16, FontWeight.Medium)
    val sm = t(14, 20)
    val smMedium = t(14, 20, FontWeight.Medium)
    val base = t(16, 24)
    val baseMedium = t(16, 24, FontWeight.Medium)
    val lg = t(18, 28, FontWeight.SemiBold)
    val xl = t(20, 28, FontWeight.SemiBold)
    val xxl = t(24, 32, FontWeight.SemiBold)
    /** Chat text: `.chat-markdown` and the composer are `text-sm` on desktop; a phone reads them a step up. */
    val chat = t(15, 23)
    val mono = t(13, 20, family = FontFamily.Monospace)
    val monoSmall = t(12, 18, family = FontFamily.Monospace)

}

/**
 * Corner radii: `--radius-sm` 6, `--control-radius`/`--radius-md` 8, `--radius` 10, `--radius-xl` 14,
 * `--radius-2xl` 18. Under Redesign they follow CandyWrapper's `DitherChrome.radius`: controls 8,
 * cards, popovers and bubbles 12, the composer and panels 16.
 */
object Radius {
    val xs = 2.dp
    val sm: Dp @Composable get() = if (Candy.redesign) 8.dp else 6.dp
    val md = 8.dp
    val lg: Dp @Composable get() = if (Candy.redesign) 12.dp else 10.dp
    val xl: Dp @Composable get() = if (Candy.redesign) 12.dp else 14.dp
    val xxl: Dp @Composable get() = if (Candy.redesign) 12.dp else 18.dp
    /** The composer card, `rounded-[20px]`; Redesign's panel corner. */
    val composer: Dp @Composable get() = if (Candy.redesign) 16.dp else 20.dp
}

/** T3's motion: Tailwind's 150 ms default and `cubic-bezier(.4,0,.2,1)`; sheets and the drawer take 200 ms. */
object Motion {
    val Ease = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
    val EaseOut = CubicBezierEasing(0f, 0f, 0.2f, 1f)
    const val FAST = 150
    const val SHEET = 200
    fun <T> fast() = tween<T>(FAST, easing = Ease)
    fun <T> sheet() = tween<T>(SHEET, easing = Ease)
}

private val LocalCandyColors = staticCompositionLocalOf { Light }

object Candy {
    val colors: CandyColors
        @Composable get() = LocalCandyColors.current
    /** True under Settings > Appearance > Redesign. */
    val redesign: Boolean
        @Composable get() = LocalCandyColors.current.redesign
    /** Redesign's options; read by the art and the Appearance settings. */
    val style: RedesignStyle
        @Composable get() = LocalRedesign.current
}

private val LocalRedesign = staticCompositionLocalOf { RedesignStyle() }

enum class ThemeChoice { SYSTEM, LIGHT, DARK }

/** The palette a theme choice resolves to; screenshot tests pass it directly. */
fun candyColors(dark: Boolean, style: RedesignStyle = RedesignStyle()) =
    if (style.on) capyColors(dark, RedesignAccent.named(style.accent)) else if (dark) Dark else Light

@Composable
fun CandyTheme(choice: ThemeChoice = ThemeChoice.SYSTEM, style: RedesignStyle = RedesignStyle(), content: @Composable () -> Unit) {
    val dark = when (choice) {
        ThemeChoice.SYSTEM -> isSystemInDarkTheme()
        ThemeChoice.LIGHT -> false
        ThemeChoice.DARK -> true
    }
    val c = remember(dark, style.on, style.accent) { candyColors(dark, style) }
    val indication = remember(c.pressed) { PressIndication(c.pressed) }
    val selection = remember(c.primary) { TextSelectionColors(c.primary, c.primary.copy(alpha = 0.3f)) }
    CompositionLocalProvider(
        LocalCandyColors provides c,
        LocalRedesign provides style,
        LocalIndication provides indication,
        LocalTextSelectionColors provides selection,
        content = content,
    )
}
