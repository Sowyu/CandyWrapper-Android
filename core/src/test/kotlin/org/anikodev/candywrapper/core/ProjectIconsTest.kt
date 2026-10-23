package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.media.ProjectIcons
import org.anikodev.candywrapper.core.media.SvgLite
import org.anikodev.candywrapper.core.model.ProjectIconOverride
import org.anikodev.candywrapper.core.sync.ShellProjection

/** Project icons: the favicon resource and its cache keys (shared projectFavicon.ts), overrides, and the SVG reader. */
class ProjectIconsTest {
    private fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject

    @Test
    fun theResourceAndItsKeyMatchT3() {
        assertEquals(json("""{"_tag":"project-favicon","cwd":"/Users/a/t3"}"""), ProjectIcons.resource("/Users/a/t3", null))
        assertEquals(json("""{"_tag":"project-favicon","cwd":"/Users/a/t3","path":"brand/mark.svg"}"""), ProjectIcons.resource("/Users/a/t3", "brand/mark.svg"))
        // JSON.stringify([environmentId, workspaceRoot, faviconPath || null]).
        assertEquals("""["env-1","/Users/a/t3",null]""", ProjectIcons.resourceKey("env-1", "/Users/a/t3", null))
        assertEquals("""["env-1","/Users/a/t3",null]""", ProjectIcons.resourceKey("env-1", "/Users/a/t3", ""))
        assertEquals("""["env-1","/Users/a/\"q\"","x.png"]""", ProjectIcons.resourceKey("env-1", "/Users/a/\"q\"", "x.png"))
    }

    @Test
    fun theRevisionSurvivesANewSignatureAndMovesWithTheBytes() {
        val a = "/api/assets/tokenA/v1-3f2a-favicon.svg?sig=abc"
        val b = "/api/assets/tokenB/v1-3f2a-favicon.svg?sig=def#x"
        val changed = "/api/assets/tokenB/v1-9c1d-favicon.svg"
        assertEquals("v1-3f2a-favicon.svg", ProjectIcons.revision(a))
        assertEquals(ProjectIcons.revision(a), ProjectIcons.revision(b))
        assertNotEquals(ProjectIcons.revision(a), ProjectIcons.revision(changed))
        assertEquals("v1-3f2a-favicon.svg", ProjectIcons.revision("https://host.example/api/assets/t/v1-3f2a-favicon.svg"))
        assertTrue(ProjectIcons.isMissing("/api/assets/token/project-favicon-missing"))
        assertFalse(ProjectIcons.isMissing(a))
    }

    @Test
    fun theTypeComesFromTheResponseElseTheName() {
        assertEquals("image/png", ProjectIcons.mimeType("image/png; charset=binary", "/a/b.svg"))
        assertEquals("image/svg+xml", ProjectIcons.mimeType("application/octet-stream", "/a/v1-x-icon.svg?s=1"))
        assertEquals("image/x-icon", ProjectIcons.mimeType(null, "/a/favicon.ico"))
        assertNull(ProjectIcons.mimeType(null, "/a/readme"))
    }

    @Test
    fun overridesReadAsCandyWrapperReadsThem() {
        assertEquals(ProjectIconOverride(ProjectIconOverride.Kind.MONOGRAM, "T3", "blue"), ProjectIcons.override(json("""{"kind":"monogram","text":"T3","color":"blue"}""")))
        assertEquals(ProjectIconOverride(ProjectIconOverride.Kind.EMOJI, "🥨"), ProjectIcons.override(json("""{"kind":"emoji","emoji":"🥨"}""")))
        assertEquals(ProjectIconOverride(ProjectIconOverride.Kind.LUCIDE, "rocket", "rose"), ProjectIcons.override(json("""{"kind":"lucide","name":"rocket","color":"rose"}""")))
        // A legacy monogram rides on a lucide icon; an unknown colour is gray; an unknown kind is no override.
        assertEquals(ProjectIconOverride(ProjectIconOverride.Kind.MONOGRAM, "CW", "gray"), ProjectIcons.override(json("""{"kind":"lucide","name":"folder","monogramText":"CW","color":"chartreuse"}""")))
        assertNull(ProjectIcons.override(json("""{"kind":"sticker","id":"x"}""")))
        assertNull(ProjectIcons.override(null))
    }

    @Test
    fun theShellCarriesTheIconFields() {
        val p = ShellProjection.project(json("""{"id":"p1","title":"T3","workspaceRoot":"/w","faviconPath":"public/logo.png","projectIcon":null}"""), "env")!!
        assertEquals("public/logo.png", p.faviconPath)
        assertNull(p.icon)
        assertTrue(ProjectIcons.wantsFile(p))
        val q = ShellProjection.project(json("""{"id":"p2","title":"T3","workspaceRoot":"/w","projectIcon":{"kind":"emoji","emoji":"🥨"}}"""), "env")!!
        assertFalse(ProjectIcons.wantsFile(q))
    }

    @Test
    fun svgShapesBecomePathsWithTheirPaint() {
        val svg = """<?xml version="1.0"?><!-- logo -->
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 32 32" fill="none">
              <style>.a{fill:#ff0000} .b, .c { stroke: rgb(0, 128, 255); stroke-width: 2 }</style>
              <defs><linearGradient id="g"><stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#fff"/></linearGradient></defs>
              <rect width="32" height="32" rx="6" fill="url(#g)"/>
              <g transform="translate(4 4) scale(2)" opacity=".5">
                <path class="a" d="M0 0h4v4z" fill-rule="evenodd"/>
                <circle class="b" cx="8" cy="8" r="2"/>
              </g>
              <text>ignored</text>
              <path d="M1 1" style="display:none"/>
            </svg>"""
        val image = assertNotNull(SvgLite.parse(svg))
        assertEquals(listOf(0f, 0f, 32f, 32f), listOf(image.minX, image.minY, image.width, image.height))
        assertEquals(3, image.shapes.size)
        val (bg, square, ring) = image.shapes
        assertEquals(0xFF7F7F7F.toInt(), bg.fill)
        assertTrue(bg.path.startsWith("M6.0 0.0 H26.0 A6.0 6.0"))
        assertEquals(0x7FFF0000, square.fill)
        assertTrue(square.evenOdd)
        assertContentEquals(floatArrayOf(2f, 0f, 0f, 2f, 4f, 4f), square.matrix)
        // The root's fill="none" inherits; the class gives a stroke.
        assertNull(ring.fill)
        assertEquals(0x7F0080FF, ring.stroke)
        assertEquals(2f, ring.strokeWidth)
    }

    @Test
    fun svgWithoutASizeOrAnythingVisibleIsNotDrawn() {
        assertNull(SvgLite.parse("""<svg><path d="M0 0h1v1z"/></svg>"""))
        assertNull(SvgLite.parse("""<svg viewBox="0 0 1 1"><text>hi</text></svg>"""))
        assertNull(SvgLite.parse("""<html><body/></html>"""))
        assertNotNull(SvgLite.parse("""<svg width="16px" height="16"><use href="#d"/><defs><path id="d" d="M0 0h1v1z"/></defs></svg>"""))
    }

    @Test
    fun svgColoursAndTransformsParse() {
        assertEquals(0xFFAABBCC.toInt(), SvgLite.color("#abc"))
        assertEquals(0x80112233.toInt(), SvgLite.color("#11223380"))
        assertEquals(0xFFFFFFFF.toInt(), SvgLite.color("White"))
        assertEquals(0x7F0A141E, SvgLite.color("rgba(10, 20, 30, .5)"))
        assertNull(SvgLite.color("#12"))
        val r = SvgLite.transform("rotate(90 1 1)")
        // (2, 1) turns about (1, 1) to (1, 2).
        assertEquals(1f, r[0] * 2 + r[2] * 1 + r[4], 1e-4f)
        assertEquals(2f, r[1] * 2 + r[3] * 1 + r[5], 1e-4f)
    }
}
