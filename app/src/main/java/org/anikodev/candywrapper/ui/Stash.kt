package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.anikodev.candywrapper.core.stash.StashEntry

/**
 * The stash's count tab over the composer's right edge (ComposerStashBadge.tsx): the bookmark,
 * "Stash" and how many prompts wait. It hides while the stash is empty. A tap opens the list.
 */
@Composable
fun StashTab(count: Int, onOpen: () -> Unit) {
    if (count == 0) return
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.md)
    val redesign = c.redesign
    // Redesign floats it as a pill inside the composer's corner, 8dp above it.
    Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = if (redesign) 12.dp + Pill.inset else 12.dp, bottom = if (redesign) Pill.gap else 6.dp), contentAlignment = Alignment.CenterEnd) {
        Row(
            Modifier.heightIn(min = if (redesign) Pill.height else 32.dp).let { if (redesign) it.floating(shape).clip(shape) else it.clip(shape).background(c.card).border(1.dp, c.border, shape) }
                .clickable(role = Role.Button, onClickLabel = "Open stash", onClick = onOpen)
                .semantics { contentDescription = "Stashed prompts: $count. Open stash." }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Bookmark, c.mutedForeground, 12.dp)
            Txt("Stash", CandyType.xs, c.mutedForeground, maxLines = 1)
            Txt("$count", CandyType.xsMedium.merge(TextStyle(fontFeatureSettings = "tnum")), c.foreground, maxLines = 1)
        }
    }
}

/**
 * The stash list over the composer (ComposerStashMenu.tsx): a header that closes it, then one row
 * per prompt, newest first, with the snippet, its images, how long ago, and delete. A tap restores
 * the prompt into the composer, after any text already there. With a draft in the composer, swap
 * trades the two: the draft goes into the stash and the prompt comes out.
 */
@Composable
fun StashList(
    entries: List<StashEntry>,
    now: Long,
    canSwap: Boolean,
    onRestore: (StashEntry) -> Unit,
    onSwap: (StashEntry) -> Unit,
    onDelete: (StashEntry) -> Unit,
    onClose: () -> Unit,
) {
    val c = Candy.colors
    val shape = RoundedCornerShape(Radius.xl)
    val panel = if (c.redesign) Modifier.padding(start = 12.dp + Pill.inset, end = 12.dp + Pill.inset, bottom = Pill.gap).floating(shape).clip(shape)
        else Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp).clip(shape).background(c.card).border(1.dp, c.border, shape)
    Column(Modifier.fillMaxWidth().then(panel)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button, onClickLabel = "Close stash", onClick = onClose).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Bookmark, c.mutedForeground, 14.dp)
            Txt("Stash", CandyType.sm, c.mutedForeground, modifier = Modifier.weight(1f))
            Txt("${entries.size}", CandyType.xsMedium.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground)
            Icon(Icons.ChevronDown, c.mutedForeground, 14.dp)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Column(Modifier.heightIn(max = 264.dp).verticalScroll(rememberScrollState()).padding(4.dp)) {
            if (entries.isEmpty()) {
                Txt("Nothing stashed yet. Stash a prompt from the composer to keep it for later.", CandyType.sm, c.mutedForeground, modifier = Modifier.padding(12.dp))
            }
            for (e in entries) StashRow(e, now, canSwap, onRestore, onSwap, onDelete)
        }
    }
}

@Composable
private fun StashRow(e: StashEntry, now: Long, canSwap: Boolean, onRestore: (StashEntry) -> Unit, onSwap: (StashEntry) -> Unit, onDelete: (StashEntry) -> Unit) {
    val c = Candy.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(Radius.sm))
            .clickable(role = Role.Button, onClickLabel = "Restore stashed prompt") { onRestore(e) }
            .padding(start = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.FileText, c.mutedForeground, 14.dp)
        Txt(e.snippet, CandyType.sm, c.foreground.copy(alpha = 0.8f), maxLines = 1, modifier = Modifier.weight(1f))
        if (e.images.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.semantics { contentDescription = if (e.images.size == 1) "1 image" else "${e.images.size} images" }) {
                Icon(Icons.Image, c.mutedForeground, 12.dp)
                Txt("${e.images.size}", CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground)
            }
        }
        Txt(relativeTime(e.createdAtMs, now), CandyType.xs.merge(TextStyle(fontFeatureSettings = "tnum")), c.mutedForeground, maxLines = 1)
        Row {
            if (canSwap) IconButton(Icons.ArrowDownUp, "Swap with the composer", size = 14.dp, box = 40.dp) { onSwap(e) }
            IconButton(Icons.X, "Delete stashed prompt", size = 14.dp, box = 40.dp) { onDelete(e) }
        }
    }
}
