package org.anikodev.candywrapper.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp

/**
 * Settings > Appearance > Redesign (Experimental): CandyWrapper's Redesign theme on the Mac (internally
 * "Dither", after Capy's desktop app). [picture] is the owner's photo as a [DitherPictures] id, null
 * until one is picked, when Redesign draws Capy's plain background; [dim] is the share of the background
 * over the art behind an open thread, 0 to 90 (the Mac's "Background dim", 35 by default).
 */
@Immutable
data class RedesignStyle(
    val on: Boolean = false,
    val accent: String = RedesignAccent.TEAL.id,
    val picture: String? = null,
    val dim: Int = 35,
) {
    /** Redesign is on with no photo, as after an update from the painted pictures: Settings and the sidebar ask for one. */
    val needsPicture get() = on && picture == null

    companion object {
        /** From the stored values. A painted id older builds stored (dusk, lagoon, ember, meadow) reads as no picture. */
        fun stored(on: Boolean, accent: String?, picture: String?, dim: String?) = RedesignStyle(
            on, accent ?: RedesignAccent.TEAL.id, picture?.takeIf(DitherPictures::isPhoto), dim?.toIntOrNull() ?: 35,
        )
    }
}

/**
 * Redesign's accent choices, from CandyWrapper's `DitherAccent`: teal is Capy's own `--primary`, the
 * others sit at about its lightness. Text on the accent is white while it reads at 3:1, else near black.
 */
@Immutable
data class RedesignAccent(val id: String, val label: String, val light: Color, val dark: Color) {
    fun color(dark: Boolean) = if (dark) this.dark else light

    fun foreground(dark: Boolean): Color = if (contrast(color(dark).luminance(), 1f) >= 3f) Color.White else Color(0xFF111111)

    companion object {
        val TEAL = RedesignAccent("teal", "Teal", Color(0xFF0B9F95), Color(0xFF2B948B))
        val ALL = listOf(
            TEAL,
            RedesignAccent("blue", "Blue", Color(0xFF2F6FDB), Color(0xFF3F7EE0)),
            RedesignAccent("violet", "Violet", Color(0xFF7552E0), Color(0xFF8466E6)),
            RedesignAccent("pink", "Pink", Color(0xFFCF3F86), Color(0xFFD4558F)),
            RedesignAccent("red", "Red", Color(0xFFD03A35), Color(0xFFD4504A)),
            RedesignAccent("orange", "Orange", Color(0xFFD9632A), Color(0xFFE0763E)),
            RedesignAccent("amber", "Amber", Color(0xFFD99A0B), Color(0xFFE0A82E)),
            RedesignAccent("green", "Green", Color(0xFF2F9A45), Color(0xFF46A35A)),
        )

        fun named(id: String) = ALL.firstOrNull { it.id == id } ?: TEAL

        private fun contrast(a: Float, b: Float) = (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }
}

/**
 * Capy's default theme as CandyWrapper's Redesign palette (`capyDarkColors`, `capyLightColors` in
 * ThemePalette.swift): near-black cool grays, the chrome a step darker than the panel, hover fills as
 * translucent overlays, lines as light (dark) or dark (light) overlays over the panel, and the accent
 * on the ring, primary and send button. Status colours stay T3's.
 */
fun capyColors(dark: Boolean, accent: RedesignAccent): CandyColors {
    val a = accent.color(dark)
    val base = candyColors(dark)
    return if (dark) base.copy(
        background = Color(0xFF131314),
        foreground = Color(0xFFF5F5F5),
        card = Color(0xFF181819),
        popover = Color(0xFF202021),
        muted = Color(0xFF1C1C1D),
        mutedForeground = Color(0xFFAAAAAB),
        secondary = Color(0x14FFFFFF),
        secondaryLabel = Color(0xFF848485),
        accent = Color(0x14FFFFFF),
        // #232324 and #2a2a2b over #131314, as white overlays (WindowGlass.frosted `lines`).
        border = Color(0x11FFFFFF),
        input = Color(0x19FFFFFF),
        ring = a,
        primary = a,
        primaryForeground = accent.foreground(true),
        messageSurface = Color(0xFF202021),
        codeBackground = Color(0xFF0F0F10),
        iconMuted = Color(0xFF686869),
        placeholder = Color(0xFF616162),
        toolbarControl = Color(0xFF181819),
        sidebar = Color(0xFF0A0A0B),
        sidebarForeground = Color(0xFFF5F5F5),
        sidebarMutedForeground = Color(0xFF848485),
        sidebarBorder = Color(0xFF0A0A0B),
        sidebarIconColor = Color(0xFF686869),
        sidebarRowHover = Color(0x0FFFFFFF),
        sidebarRowActive = Color(0x17FFFFFF),
        sidebarRowSelected = Color(0x17FFFFFF),
        sidebarControlSurface = Color(0xFF141415),
        send = a,
        pressed = Color(0x14FFFFFF),
        scrim = Color(0xA3131314),
        redesign = true,
    ) else base.copy(
        background = Color(0xFFF9F9F9),
        foreground = Color(0xFF1E1E1E),
        card = Color(0xFFFFFFFF),
        popover = Color(0xFFFFFFFF),
        muted = Color(0xFFE1E1E2),
        mutedForeground = Color(0xFF6C6C6C),
        secondary = Color(0x0D000000),
        secondaryLabel = Color(0xFF898989),
        accent = Color(0x0D000000),
        // #dadada and #d4d4d4 over #f9f9f9, as black overlays.
        border = Color(0x20000000),
        input = Color(0x26000000),
        ring = a,
        primary = a,
        primaryForeground = accent.foreground(false),
        messageSurface = Color(0xFFEFEFEF),
        codeBackground = Color(0xFFF4F4F4),
        iconMuted = Color(0xFF959595),
        placeholder = Color(0xFFA2A2A2),
        toolbarControl = Color(0xFFFFFFFF),
        sidebar = Color(0xFFECECEC),
        sidebarForeground = Color(0xFF1E1E1E),
        sidebarMutedForeground = Color(0xFF898989),
        sidebarBorder = Color(0xFFECECEC),
        sidebarIconColor = Color(0xFF959595),
        sidebarRowHover = Color(0x0A000000),
        sidebarRowActive = Color(0x12000000),
        sidebarRowSelected = Color(0x12000000),
        sidebarControlSurface = Color(0xFFE4E4E4),
        send = a,
        pressed = Color(0x0D000000),
        scrim = Color(0x99F9F9F9),
        redesign = true,
    )
}

/** Redesign's pills and frosted panes: `surfaceRaised` (dark) or `card` (light) at 62% (DitherPill.fill). */
fun CandyColors.paneTint() = if (dark) Color(0x9E202021) else Color(0x9EFFFFFF)

/** The 1dp rim every floating Redesign surface draws (DitherChrome.rim). */
fun CandyColors.rim() = if (dark) Color(0x14FFFFFF) else Color(0x14000000)

/** Redesign's settings card over the art: Capy's #222223 at 86% in dark, white at 86% in light. */
fun CandyColors.settingsCard() = if (dark) Color(0xDB222223) else Color(0xDBFFFFFF)

/** A hover or pressed overlay over [over], opaque, for rows that sit on the art. */
fun Color.over(over: Color) = compositeOver(over)

/** The accent mixed toward black, for a pressed send button (DitherAccent `mix(black: 0.12)`). */
fun Color.pressedAccent() = lerp(this, Color.Black, 0.12f)
