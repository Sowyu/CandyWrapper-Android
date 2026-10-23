package org.anikodev.candywrapper.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.orchestrator.BotIdentity
import org.anikodev.candywrapper.core.orchestrator.BotMood

/** What the pinned Orchestrator row shows (CandyWrapper OrchestratorSidebarInfo). */
@Immutable
data class OrchestratorRowInfo(
    val name: String,
    val machine: String,
    val bot: BotIdentity,
    /** Approvals and questions waiting across its machine. */
    val needsYou: Int,
    val working: Boolean,
    val open: Boolean = false,
) {
    val mood: BotMood get() = if (needsYou > 0) BotMood.NEEDS_YOU else if (working) BotMood.WORKING else BotMood.IDLE
}

/** The amber of the Mac's "needs you" count and the bot's "!" badge. */
val NeedsYouAmber = Color(0xFFFFB829)

/**
 * Redesign's pinned Orchestrator row above the threads: its bot, its name, its machine under it with the machine
 * row's laptop glyph, and an amber count of what needs you. A tap opens the pane full screen; a long press renames it.
 * Threads never get a bot.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OrchestratorRow(info: OrchestratorRowInfo, actions: SidebarActions) {
    val c = Candy.colors
    val waiting = when (info.needsYou) { 0 -> ""; 1 -> ", 1 thing needs you"; else -> ", ${info.needsYou} things need you" }
    Column(Modifier.padding(horizontal = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 52.dp).clip(RoundedCornerShape(Radius.md))
                .background(if (info.open) c.sidebarRowActive else Color.Transparent)
                .combinedClickable(role = Role.Button, onClickLabel = "Open", onLongClickLabel = "Rename",
                    onLongClick = actions.renameOrchestrator, onClick = actions.openOrchestrator)
                .semantics(mergeDescendants = true) { contentDescription = "${info.name} on ${info.machine}$waiting" }
                .padding(start = 6.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Bot(info.bot, info.mood, 30.dp)
            Column(Modifier.weight(1f)) {
                Txt(info.name, CandyType.baseMedium, c.sidebarForeground, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Icon(Icons.Laptop, c.sidebarMutedForeground, 14.dp)
                    Txt(info.machine, CandyType.sm, c.sidebarMutedForeground, maxLines = 1)
                }
            }
            if (info.needsYou > 0) {
                Box(Modifier.widthIn(min = 20.dp).clip(CircleShape).background(NeedsYouAmber).padding(horizontal = 6.dp, vertical = 2.dp), contentAlignment = Alignment.Center) {
                    Txt(if (info.needsYou > 9) "9+" else "${info.needsYou}", CandyType.xsMedium, Color.Black, align = TextAlign.Center)
                }
            }
        }
        Divider(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), c.sidebarBorder)
    }
}
