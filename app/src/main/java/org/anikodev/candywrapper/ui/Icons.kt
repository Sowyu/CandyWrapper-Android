package org.anikodev.candywrapper.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The few Lucide icons the app draws (Lucide v0.564.0, ISC licence, the version T3 pins), as
 * 24-unit stroke paths copied from CandyWrapper's IconData.generated.swift. Each builds on first use.
 */
object Icons {
    private fun icon(name: String, vararg paths: String): Lazy<ImageVector> = lazy {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black), strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        b.build()
    }

    val ArrowUp by icon("arrowUp", "m5 12 7-7 7 7", "M12 19V5")
    val Square by icon("square", "M5.0 3.0h14.0a2.0 2.0 0 0 1 2.0 2.0v14.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-14.0a2.0 2.0 0 0 1 2.0 -2.0z")
    val ChevronLeft by icon("chevronLeft", "m15 18-6-6 6-6")
    val ChevronRight by icon("chevronRight", "m9 18 6-6-6-6")
    val ChevronDown by icon("chevronDown", "m6 9 6 6 6-6")
    val X by icon("x", "M18 6 6 18", "m6 6 12 12")
    val Plus by icon("plus", "M5 12h14", "M12 5v14")
    val Search by icon("search", "m21 21-4.34-4.34", "M3.0 11.0a8.0 8.0 0 1 0 16.0 0a8.0 8.0 0 1 0 -16.0 0")
    val Settings by icon("settings", "M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915", "M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0")
    val Paperclip by icon("paperclip", "m16 6-8.414 8.586a2 2 0 0 0 2.829 2.829l8.414-8.586a4 4 0 1 0-5.657-5.657l-8.379 8.551a6 6 0 1 0 8.485 8.485l8.379-8.551")
    val Check by icon("check", "M20 6 9 17l-5-5")
    val CircleCheck by icon("circleCheck", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "m9 12 2 2 4-4")
    val CircleAlert by icon("circleAlert", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 8L12 12", "M12 16L12.01 16")
    val Terminal by icon("terminal", "M12 19h8", "m4 17 6-6-6-6")
    val FileText by icon("fileText", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M10 9H8", "M16 13H8", "M16 17H8")
    val PenLine by icon("penLine", "M13 21h8", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
    val Pencil by icon("pencil", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z", "m15 5 4 4")
    val Archive by icon("archive", "M3.0 3.0h18.0a1.0 1.0 0 0 1 1.0 1.0v3.0a1.0 1.0 0 0 1 -1.0 1.0h-18.0a1.0 1.0 0 0 1 -1.0 -1.0v-3.0a1.0 1.0 0 0 1 1.0 -1.0z", "M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8", "M10 12h4")
    val Copy by icon("copy", "M10.0 8.0h10.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0z", "M4 16c-1.1 0-2-.9-2-2V4c0-1.1.9-2 2-2h10c1.1 0 2 .9 2 2")
    val RefreshCw by icon("refreshCw", "M3 12a9 9 0 0 1 9-9 9.75 9.75 0 0 1 6.74 2.74L21 8", "M21 3v5h-5", "M21 12a9 9 0 0 1-9 9 9.75 9.75 0 0 1-6.74-2.74L3 16", "M8 16H3v5")
    val Ellipsis by icon("ellipsis", "M11.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0", "M18.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0", "M4.0 12.0a1.0 1.0 0 1 0 2.0 0a1.0 1.0 0 1 0 -2.0 0")
    val Folder by icon("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    val Brain by icon("brain", "M12 18V5", "M15 13a4.17 4.17 0 0 1-3-4 4.17 4.17 0 0 1-3 4", "M17.598 6.5A3 3 0 1 0 12 5a3 3 0 1 0-5.598 1.5", "M17.997 5.125a4 4 0 0 1 2.526 5.77", "M18 18a4 4 0 0 0 2-7.464", "M19.967 17.483A4 4 0 1 1 12 18a4 4 0 1 1-7.967-.517", "M6 18a4 4 0 0 1-2-7.464", "M6.003 5.125a4 4 0 0 0-2.526 5.77")
    val ListTodo by icon("listTodo", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "M4.0 4.0h4.0a1.0 1.0 0 0 1 1.0 1.0v4.0a1.0 1.0 0 0 1 -1.0 1.0h-4.0a1.0 1.0 0 0 1 -1.0 -1.0v-4.0a1.0 1.0 0 0 1 1.0 -1.0z")
    val Wrench by icon("wrench", "M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.106-3.105c.32-.322.863-.22.983.218a6 6 0 0 1-8.259 7.057l-7.91 7.91a1 1 0 0 1-2.999-3l7.91-7.91a6 6 0 0 1 7.057-8.259c.438.12.54.662.219.984z")
    val Globe by icon("globe", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val Eye by icon("eye", "M2.062 12.348a1 1 0 0 1 0-.696 10.75 10.75 0 0 1 19.876 0 1 1 0 0 1 0 .696 10.75 10.75 0 0 1-19.876 0", "M9.0 12.0a3.0 3.0 0 1 0 6.0 0a3.0 3.0 0 1 0 -6.0 0")
    val Clock by icon("clock", "M12 6v6l4 2", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0")
    val Image by icon("image", "M5.0 3.0h14.0a2.0 2.0 0 0 1 2.0 2.0v14.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-14.0a2.0 2.0 0 0 1 2.0 -2.0z", "M7.0 9.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0", "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val Trash2 by icon("trash2", "M10 11v6", "M14 11v6", "M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6", "M3 6h18", "M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2")
    val Cloud by icon("cloud", "M17.5 19H9a7 7 0 1 1 6.71-9h1.79a4.5 4.5 0 1 1 0 9Z")
    val Laptop by icon("laptop", "M18 5a2 2 0 0 1 2 2v8.526a2 2 0 0 0 .212.897l1.068 2.127a1 1 0 0 1-.9 1.45H3.62a1 1 0 0 1-.9-1.45l1.068-2.127A2 2 0 0 0 4 15.526V7a2 2 0 0 1 2-2z", "M20.054 15.987H3.946")
    val Lock by icon("lock", "M5.0 11.0h14.0a2.0 2.0 0 0 1 2.0 2.0v7.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-7.0a2.0 2.0 0 0 1 2.0 -2.0z", "M7 11V7a5 5 0 0 1 10 0v4")
    val LockOpen by icon("lockOpen", "M5.0 11.0h14.0a2.0 2.0 0 0 1 2.0 2.0v7.0a2.0 2.0 0 0 1 -2.0 2.0h-14.0a2.0 2.0 0 0 1 -2.0 -2.0v-7.0a2.0 2.0 0 0 1 2.0 -2.0z", "M7 11V7a5 5 0 0 1 9.9-1")
    val ShieldCheck by icon("shieldCheck", "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z", "m9 12 2 2 4-4")
    val Zap by icon("zap", "M4 14a1 1 0 0 1-.78-1.63l9.9-10.2a.5.5 0 0 1 .86.46l-1.92 6.02A1 1 0 0 0 13 10h7a1 1 0 0 1 .78 1.63l-9.9 10.2a.5.5 0 0 1-.86-.46l1.92-6.02A1 1 0 0 0 11 14z")
    val Bot by icon("bot", "M12 8V4H8", "M6.0 8.0h12.0a2.0 2.0 0 0 1 2.0 2.0v8.0a2.0 2.0 0 0 1 -2.0 2.0h-12.0a2.0 2.0 0 0 1 -2.0 -2.0v-8.0a2.0 2.0 0 0 1 2.0 -2.0z", "M2 14h2", "M20 14h2", "M15 13v2", "M9 13v2")
    val Sparkles by icon("sparkles", "M11.017 2.814a1 1 0 0 1 1.966 0l1.051 5.558a2 2 0 0 0 1.594 1.594l5.558 1.051a1 1 0 0 1 0 1.966l-5.558 1.051a2 2 0 0 0-1.594 1.594l-1.051 5.558a1 1 0 0 1-1.966 0l-1.051-5.558a2 2 0 0 0-1.594-1.594l-5.558-1.051a1 1 0 0 1 0-1.966l5.558-1.051a2 2 0 0 0 1.594-1.594z", "M20 2v4", "M22 4h-4", "M2.0 20.0a2.0 2.0 0 1 0 4.0 0a2.0 2.0 0 1 0 -4.0 0")
    val FileDiff by icon("fileDiff", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M9 10h6", "M12 13V7", "M9 17h6")
    val History by icon("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val MessageCircleQuestion by icon("messageCircleQuestion", "M2.992 16.342a2 2 0 0 1 .094 1.167l-1.065 3.29a1 1 0 0 0 1.236 1.168l3.413-.998a2 2 0 0 1 1.099.092 10 10 0 1 0-4.777-4.719", "M9.09 9a3 3 0 0 1 5.83 1c0 2-3 3-3 3", "M12 17h.01")
    val FileSearch by icon("fileSearch", "M6 22a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h8a2.4 2.4 0 0 1 1.704.706l3.588 3.588A2.4 2.4 0 0 1 20 8v12a2 2 0 0 1-2 2z", "M14 2v5a1 1 0 0 0 1 1h5", "M9.0 14.5a2.5 2.5 0 1 0 5.0 0a2.5 2.5 0 1 0 -5.0 0", "M13.3 16.3 15 18")
    val TriangleAlert by icon("triangleAlert", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    val SquarePen by icon("squarePen", "M12 3H5a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7", "M18.375 2.625a1 1 0 0 1 3 3l-9.013 9.014a2 2 0 0 1-.853.505l-2.873.84a.5.5 0 0 1-.62-.62l.84-2.873a2 2 0 0 1 .506-.852z")
    val Info by icon("info", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "M12 16v-4", "M12 8h.01")
    val ListChecks by icon("listChecks", "M13 5h8", "M13 12h8", "M13 19h8", "m3 17 2 2 4-4", "m3 7 2 2 4-4")
    val CircleX by icon("circleX", "M2.0 12.0a10.0 10.0 0 1 0 20.0 0a10.0 10.0 0 1 0 -20.0 0", "m15 9-6 6", "m9 9 6 6")
    val Smartphone by icon("smartphone", "M7.0 2.0h10.0a2.0 2.0 0 0 1 2.0 2.0v16.0a2.0 2.0 0 0 1 -2.0 2.0h-10.0a2.0 2.0 0 0 1 -2.0 -2.0v-16.0a2.0 2.0 0 0 1 2.0 -2.0z", "M12 18h.01")
    val Activity by icon("activity", "M22 12h-2.48a2 2 0 0 0-1.93 1.46l-2.35 8.36a.25.25 0 0 1-.48 0L9.24 2.18a.25.25 0 0 0-.48 0l-2.35 8.36A2 2 0 0 1 4.49 12H2")
    val Undo2 by icon("undo2", "M9 14 4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 5.5 5.5a5.5 5.5 0 0 1-5.5 5.5H11")
    val TextWrap by icon("textWrap", "m16 16-3 3 3 3", "M3 12h14.5a1 1 0 0 1 0 7H13", "M3 19h6", "M3 5h18")
    val Sun by icon("sun", "M8.0 12.0a4.0 4.0 0 1 0 8.0 0a4.0 4.0 0 1 0 -8.0 0", "M12 2v2", "M12 20v2", "m4.93 4.93 1.41 1.41", "m17.66 17.66 1.41 1.41", "M2 12h2", "M20 12h2", "m6.34 17.66-1.41 1.41", "m19.07 4.93-1.41 1.41")
    val Moon by icon("moon", "M20.985 12.486a9 9 0 1 1-9.473-9.472c.405-.022.617.46.402.803a6 6 0 0 0 8.268 8.268c.344-.215.825-.004.803.401")
    val Monitor by icon("monitor", "M4.0 3.0h16.0a2.0 2.0 0 0 1 2.0 2.0v10.0a2.0 2.0 0 0 1 -2.0 2.0h-16.0a2.0 2.0 0 0 1 -2.0 -2.0v-10.0a2.0 2.0 0 0 1 2.0 -2.0z", "M8 21L16 21", "M12 17L12 21")
    val LogIn by icon("logIn", "m10 17 5-5-5-5", "M15 12H3", "M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4")
    val Link by icon("link", "M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71", "M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71")

}
