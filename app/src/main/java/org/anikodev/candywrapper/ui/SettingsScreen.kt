package org.anikodev.candywrapper.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.IntOffset
import java.io.File
import kotlin.math.roundToInt
import org.anikodev.candywrapper.AppGraph
import org.anikodev.candywrapper.BuildConfig
import org.anikodev.candywrapper.core.model.EnvironmentKind
import org.anikodev.candywrapper.core.sync.EnvironmentSession
import org.anikodev.candywrapper.update.UpdateSettingsRow

/** Connections, the T3 Connect account, the theme, and the way to the timing report. */
@Composable
fun SettingsScreen(graph: AppGraph, nav: Navigator) {
    val sessions by graph.environments.sessions.collectAsStateWithLifecycle()
    val account by graph.account.collectAsStateWithLifecycle()
    val theme by graph.theme.collectAsStateWithLifecycle()
    val legacySidebar by graph.legacySidebar.value.collectAsStateWithLifecycle()
    val planMode by graph.planMode.value.collectAsStateWithLifecycle()
    val workingSection by graph.workingSection.value.collectAsStateWithLifecycle()
    val style by graph.redesignStyle.collectAsStateWithLifecycle()
    val orchestrator by graph.orchestratorStore.config.collectAsStateWithLifecycle()
    val c = Candy.colors
    val photos = rememberRedesignPhotoFlow(graph)
    var selected by remember { mutableStateOf<EnvironmentSession?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ArtEmphasisEffect(ArtEmphasis.SETTINGS)
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar("Settings", onBack = if (nav.stack.size > 1) ({ nav.pop() }) else null)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                GroupLabel("Computers")
                Card {
                    for ((i, s) in sessions.withIndex()) {
                        if (i > 0) Divider()
                        ConnectionRow(s) { selected = s; confirmRemove = false }
                    }
                    if (sessions.isNotEmpty()) Divider()
                    ListRow("Pair a computer", icon = Icons.Plus, detail = "Paste the link from `npx t3 pair`") { nav.push(Screen.Pair()) }
                }

                GroupLabel("T3 Connect")
                Card {
                    ListRow(
                        if (account == null) "Sign in" else account?.identifier ?: "Signed in",
                        icon = Icons.Cloud,
                        detail = if (account == null) "Reach the computers linked to your account" else "Manage linked computers or sign out",
                    ) { nav.push(Screen.Connect) }
                }

                GroupLabel("Appearance")
                AppearanceSettings(
                    theme, style, graph::setTheme,
                    onRedesign = photos::toggle,
                    onAccent = graph.redesignAccent::set,
                    onChoosePhoto = photos::change,
                    onDim = { graph.redesignDim.set(it.toString()) },
                    orchestrator = orchestrator.enabled,
                    onOrchestrator = { on -> graph.orchestratorStore.update { it.copy(enabled = on) } },
                    onOpenOrchestrator = { nav.push(Screen.OrchestratorSettings) },
                )

                GroupLabel("Sidebar")
                SidebarSettings(workingSection, graph.workingSection::set)

                GroupLabel("Legacy features")
                LegacyFeatures(planMode, legacySidebar, graph.planMode::set, graph.legacySidebar::set)

                GroupLabel("App")
                Card {
                    ListRow("Timing", icon = Icons.Activity, detail = "Start, connect and thread-open steps in milliseconds") { nav.push(Screen.Diagnostics) }
                    Divider()
                    Box(Modifier.padding(horizontal = 4.dp)) { UpdateSettingsRow(graph.updater) }
                }
                Txt("CandyWrapper ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", CandyType.xs, c.mutedForeground,
                    modifier = Modifier.padding(start = 4.dp, top = 16.dp))
                Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 24.dp))
            }
        }

        selected?.let { s ->
            val env = s.env.value
            Sheet({ selected = null }, env.label) {
                ListRow("Open threads", icon = Icons.ChevronRight) { selected = null; nav.reset(Screen.Main(s.id, null)) }
                ListRow("Reconnect now", icon = Icons.RefreshCw) { selected = null; s.wake(); s.networkChanged() }
                if (confirmRemove) {
                    Txt(
                        if (env.kind == EnvironmentKind.CONNECT) "This forgets the computer and its saved threads on this phone. It comes back while you stay signed in to T3 Connect."
                        else "This forgets the computer, its saved threads and this phone's access. Pair it again to come back.",
                        CandyType.sm, c.mutedForeground, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    )
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = ButtonRow) {
                        CButton("Remove", kind = ButtonKind.DESTRUCTIVE) {
                            selected = null
                            scope.launch { graph.environments.remove(s.id) }
                        }
                        CButton("Keep", kind = ButtonKind.GHOST) { confirmRemove = false }
                    }
                } else {
                    ListRow("Remove from this phone", icon = Icons.Trash2, labelColor = c.errorForeground) { confirmRemove = true }
                }
            }
        }
    }
}

/** T3's General > Working section (beta), stored on this phone as T3 stores it per client. */
@Composable
fun SidebarSettings(workingSection: Boolean, onWorkingSection: (Boolean) -> Unit) {
    Card {
        SwitchRow(
            "Working section", workingSection,
            detail = "Fold working and monitoring threads into a Working section. They return to the top of the inbox when they need you.",
            icon = Icons.CircleDashed, onChange = onWorkingSection,
        )
    }
}

/**
 * T3's "Legacy features" settings: both off by default and stored on this phone only. Plan mode
 * off sends every turn in build mode; the legacy sidebar is the per-project tree.
 */
@Composable
fun LegacyFeatures(planMode: Boolean, legacySidebar: Boolean, onPlanMode: (Boolean) -> Unit, onLegacySidebar: (Boolean) -> Unit) {
    Card {
        SwitchRow("Plan mode", planMode, detail = "Restore the Plan toggle in the composer. Off uses build mode.", icon = Icons.ListTodo, onChange = onPlanMode)
        Divider()
        SwitchRow("Legacy sidebar", legacySidebar, detail = "Restore per-project thread trees instead of the default flat sidebar.", icon = Icons.PanelLeft, onChange = onLegacySidebar)
    }
}

@Composable
private fun ConnectionRow(session: EnvironmentSession, onClick: () -> Unit) {
    val env by session.env.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val routes = env.directRoutes.joinToString { it.kind }
    ListRow(
        env.label,
        icon = Icons.Laptop,
        detail = listOfNotNull(
            if (env.kind == EnvironmentKind.CONNECT) "T3 Connect" else "Paired",
            state.label(),
            routes.ifEmpty { null }?.let { "direct: $it" },
        ).joinToString(" · "),
        trailing = { StatusDot(state.color()) },
        onClick = onClick,
    )
}

/**
 * Settings > Appearance: light, dark or the system's, then Redesign (Experimental) and, while it is on,
 * its own options from the Mac's Appearance page: the accent, the photo it dithers and how much an
 * open thread dims it, and the Orchestrator's switch with the way to its page. Each change applies live.
 * Redesign needs a photo, so [onRedesign] goes through [RedesignPhotoFlow] and there is no way to remove it.
 */
@Composable
fun AppearanceSettings(
    theme: ThemeChoice,
    style: RedesignStyle,
    onTheme: (ThemeChoice) -> Unit,
    onRedesign: (Boolean) -> Unit,
    onAccent: (String) -> Unit,
    onChoosePhoto: () -> Unit,
    onDim: (Int) -> Unit,
    orchestrator: Boolean = false,
    onOrchestrator: (Boolean) -> Unit = {},
    onOpenOrchestrator: () -> Unit = {},
) {
    val c = Candy.colors
    Card {
        for ((i, choice) in ThemeChoice.entries.withIndex()) {
            if (i > 0) Divider()
            ListRow(
                when (choice) { ThemeChoice.SYSTEM -> "Match the system"; ThemeChoice.LIGHT -> "Light"; ThemeChoice.DARK -> "Dark" },
                icon = when (choice) { ThemeChoice.SYSTEM -> Icons.Smartphone; ThemeChoice.LIGHT -> Icons.Sun; ThemeChoice.DARK -> Icons.Moon },
                selected = choice == theme,
            ) { onTheme(choice) }
        }
    }
    Spacer(Modifier.height(12.dp))
    Card {
        SwitchRow(
            "Redesign (Experimental)", style.on,
            detail = "Capy's colours, a dithered picture behind threads and new icons, as in CandyWrapper on the Mac.",
            icon = Icons.FlaskConical, onChange = onRedesign,
        )
        if (style.on) {
            Divider()
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp)) {
                Txt("Accent color", CandyType.base, c.foreground)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    for (a in RedesignAccent.ALL) Swatch(a.color(c.dark), a.label, a.id == style.accent) { onAccent(a.id) }
                }
            }
            Divider()
            val picture = style.picture
            if (picture == null) {
                ListRow("Pick a picture for Redesign", icon = Icons.Image, trailing = { Icon(Icons.ChevronRight, c.mutedForeground, 16.dp) }, onClick = onChoosePhoto)
            } else {
                ListRow(
                    "Picture", detail = "Dithered behind threads and Settings",
                    leading = { PhotoThumbnail(picture) },
                    trailing = { CButton("Change picture", kind = ButtonKind.SECONDARY, size = ButtonSize.SM, onClick = onChoosePhoto) },
                    onClick = onChoosePhoto,
                )
            }
            Divider()
            DimSlider(style.dim, onDim)
            Divider()
            SwitchRow(
                "Experimental Orchestrator features", orchestrator,
                detail = "A thread that manages your other threads, as in CandyWrapper on the Mac.",
                icon = Icons.Bot, onChange = onOrchestrator,
            )
            if (orchestrator) {
                Divider()
                ListRow("Orchestrator", icon = Icons.Settings, detail = "Its machine, models, prompt and notebook",
                    trailing = { Icon(Icons.ChevronRight, c.mutedForeground, 16.dp) }, onClick = onOpenOrchestrator)
            }
        }
    }
}

/** One accent choice: a 24dp disc in a 40dp target, ringed while chosen. */
@Composable
private fun Swatch(color: Color, label: String, chosen: Boolean, onClick: () -> Unit) {
    val c = Candy.colors
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.RadioButton, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label; selected = chosen },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).let { if (chosen) it.border(2.dp, color, CircleShape) else it }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(color))
        }
    }
}

/**
 * Capy's settings select, as wide as its value: 11dp in, the value, 8dp to the chevron, 8dp out, 28dp
 * tall, on a fill a step lighter than the card with a faint rim.
 */
@Composable
fun SelectValue(value: String, open: Boolean = false) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    Row(
        Modifier.height(28.dp).clip(shape).background(c.foreground.copy(alpha = if (c.dark) 0.035f else 0.03f))
            .border(1.dp, c.foreground.copy(alpha = if (open) 0.2f else 0.1f), shape).padding(start = 11.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Txt(value, CandyType.sm, c.foreground, maxLines = 1)
        Icon(Icons.ChevronDown, c.mutedForeground, 12.dp)
    }
}

/**
 * The Mac's "Background dim": how much of the background covers the art behind an open thread, 0 to
 * 90% in steps of 5. The thumb follows the finger; the setting is written when it lets go.
 */
@Composable
private fun DimSlider(dim: Int, onDim: (Int) -> Unit) {
    val c = Candy.colors
    var value by remember(dim) { mutableIntStateOf(dim.coerceIn(0, 90)) }
    var width by remember { mutableIntStateOf(1) }
    val thumb = with(LocalDensity.current) { 20.dp.toPx() }
    fun at(x: Float) = (((x - thumb / 2) / (width - thumb).coerceAtLeast(1f)).coerceIn(0f, 1f) * 18).roundToInt() * 5
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt("Background dim", CandyType.base, c.foreground, modifier = Modifier.weight(1f))
            Txt("$value%", CandyType.sm, c.mutedForeground)
        }
        Txt("How much an open thread dims the picture", CandyType.sm, c.mutedForeground)
        Box(
            Modifier.fillMaxWidth().height(44.dp).onSizeChanged { width = it.width }
                .semantics {
                    contentDescription = "Background dim"
                    progressBarRangeInfo = ProgressBarRangeInfo(value.toFloat(), 0f..90f, 17)
                    setProgress { v -> onDim((v / 5).roundToInt() * 5); true }
                }
                .pointerInput(Unit) { detectTapGestures { value = at(it.x); onDim(value) } }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(onDragEnd = { onDim(value) }) { change, _ -> value = at(change.position.x) }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val fraction = value / 90f
            Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(c.foreground.copy(alpha = 0.1f)))
            Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.001f)).height(4.dp).clip(CircleShape).background(c.primary))
            Box(
                Modifier.offset { IntOffset(((width - thumb) * fraction).roundToInt(), 0) }.size(20.dp).clip(CircleShape)
                    .background(if (c.dark) Color(0xFFF5F5F5) else Color.White).border(1.dp, c.border, CircleShape),
            )
        }
    }
}

/** Redesign's photo cropped to a 40dp square, decoded off the main thread from the art store's files. */
@Composable
private fun PhotoThumbnail(picture: String) {
    val c = Candy.colors
    val filesDir = LocalArtStore.current?.filesDir
    val px = with(LocalDensity.current) { 40.dp.roundToPx() }
    val image by produceState<ImageBitmap?>(null, picture, filesDir, px) {
        value = filesDir?.let { dir ->
            withContext(Dispatchers.Default) { DitherPictures.pixels(picture, dir, px, px)?.let { Bitmap.createBitmap(it, px, px, Bitmap.Config.ARGB_8888).asImageBitmap() } }
        }
    }
    val shape = RoundedCornerShape(Radius.md)
    Box(Modifier.size(40.dp).clip(shape).background(c.muted).border(1.dp, c.border, shape)) {
        image?.let { Image(it, "Redesign picture", Modifier.fillMaxSize()) }
    }
}

/**
 * Redesign takes the owner's photo. Turning it on without one opens the picker first and only a pick turns
 * it on; a cancel, or a photo that cannot be copied, leaves it off. [open] launches the system picker and
 * [picked] gets the copied photo's id, or null.
 */
class RedesignPhotoFlow(
    private val hasPhoto: () -> Boolean,
    private val setOn: (Boolean) -> Unit,
    private val setPicture: (String) -> Unit,
) {
    var open: () -> Unit = {}
    // ponytail: lost if Android kills the process behind the picker, then the toggle is tapped again; save it if that shows up.
    private var enabling = false

    fun toggle(on: Boolean) {
        if (on && !hasPhoto()) { enabling = true; open() } else setOn(on)
    }

    fun change() { enabling = false; open() }

    fun picked(id: String?) {
        if (id != null) { setPicture(id); if (enabling) setOn(true) }
        enabling = false
    }
}

/** The picker flow bound to the system photo picker and [graph]'s Redesign settings. Settings and the sidebar banner use it. */
@Composable
fun rememberRedesignPhotoFlow(graph: AppGraph): RedesignPhotoFlow {
    val context = LocalContext.current
    val flow = remember(graph) { RedesignPhotoFlow({ graph.redesignStyle.value.picture != null }, graph.redesign::set, graph.redesignPicture::set) }
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) flow.picked(null) else scope.launch { flow.picked(withContext(Dispatchers.IO) { copyPhoto(context, uri) }) }
    }
    flow.open = { launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    return flow
}

/** Copies a picked photo into the app's files as Redesign's picture and returns its id; the one it replaces goes. */
private fun copyPhoto(context: Context, uri: Uri): String? = runCatching {
    val dir = DitherPictures.folder(context.filesDir).apply { mkdirs() }
    val target = File(dir, "photo-${System.currentTimeMillis()}.img")
    context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
    dir.listFiles()?.filter { it != target }?.forEach { it.delete() }
    DitherPictures.photoId(target)
}.getOrNull()
