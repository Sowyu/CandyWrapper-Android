package org.anikodev.candywrapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.anikodev.candywrapper.core.markdown.CodeToken
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan
import org.anikodev.candywrapper.core.markdown.TokenKind
import org.anikodev.candywrapper.core.media.ImageRef
import org.anikodev.candywrapper.core.media.Media

/**
 * Parsed Markdown drawn after T3's `.chat-markdown` (index.css): blocks 0.65rem apart, headings
 * at 20/18/16sp semibold, links in `--info-foreground`, code blocks as bordered cards with their
 * language and actions on top, real tables, task checkboxes and GitHub alerts. Parsing already
 * happened off the main thread.
 */
@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier, style: TextStyle = CandyType.chat) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (b in blocks) Block(b, style)
    }
}

@Composable
private fun Block(b: MdBlock, style: TextStyle) {
    val c = Candy.colors
    when (b) {
        is MdBlock.Paragraph -> BasicText(rememberSpans(b.spans), style = style.copy(color = c.foreground))
        is MdBlock.Heading -> BasicText(
            rememberSpans(b.spans),
            Modifier.padding(top = if (b.level <= 2) 10.dp else 6.dp),
            style = TextStyle(
                color = if (b.level >= 6) c.mutedForeground else c.foreground, fontWeight = FontWeight.SemiBold,
                fontSize = when (b.level) { 1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 14.sp },
                lineHeight = when (b.level) { 1 -> 26.sp; 2 -> 23.sp; 3 -> 21.sp; else -> 18.sp },
            ),
        )
        is MdBlock.Code -> CodeBlock(b)
        is MdBlock.Quote -> alertOf(b)?.let { AlertQuote(it, style) } ?: Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(c.border))
            MarkdownBlocks(b.blocks, Modifier.padding(start = 13.dp), style.copy(color = c.mutedForeground))
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    Box(Modifier.widthIn(min = 20.dp).padding(end = 4.dp), contentAlignment = Alignment.TopStart) {
                        when {
                            item.checked != null -> Checkbox(item.checked == true, Modifier.padding(top = 4.dp))
                            b.ordered -> BasicText("${b.start + i}.", style = style.copy(color = c.mutedForeground))
                            else -> BasicText("•", Modifier.padding(start = 4.dp), style = style.copy(color = c.foreground))
                        }
                    }
                    MarkdownBlocks(item.blocks, style = style)
                }
            }
        }
        is MdBlock.Table -> Table(b)
        is MdBlock.Image -> MarkdownImage(b)
        MdBlock.Rule -> Divider()
    }
}

/**
 * `![alt](src)` (ChatMarkdown.tsx): web images load directly; files on the computer through a
 * signed `media-file` URL, framed. A blocked source shows its alt text.
 */
@Composable
private fun MarkdownImage(b: MdBlock.Image) {
    val c = Candy.colors
    val images = LocalImages.current
    val ref = remember(b, images?.threadId) { Media.markdownImage(b.src, b.alt, images?.threadId) }
    if (ref == null || images == null) {
        if (b.alt.isNotBlank()) Txt(b.alt, CandyType.sm, c.mutedForeground)
        return
    }
    NaturalImage(ref, onClick = { images.open(listOf(ref), 0) }, framed = ref is ImageRef.Asset)
}

/** A task-list checkbox: 16dp, `rounded-sm`, filled when done. */
@Composable
private fun Checkbox(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Candy.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier.size(16.dp).clip(shape).background(if (checked) c.mutedForeground.copy(alpha = 0.55f) else c.card).border(1.dp, if (checked) Color.Transparent else c.input, shape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Check, c.background, 12.dp)
    }
}

private data class AlertKind(val label: String, val icon: ImageVector, val tone: NoticeTone, val body: List<MdBlock>)

/** `> [!NOTE]` and its siblings: GitHub alerts, which T3 draws with a coloured rule, icon and title. */
private fun alertOf(q: MdBlock.Quote): AlertKind? {
    val first = q.blocks.firstOrNull() as? MdBlock.Paragraph ?: return null
    val text = first.spans.firstOrNull()?.text ?: return null
    val m = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]\\s*").find(text) ?: return null
    val rest = listOf(first.spans.first().copy(text = text.substring(m.range.last + 1).trimStart('\n'))) + first.spans.drop(1)
    val body = (if (rest.all { it.text.isBlank() }) emptyList() else listOf(MdBlock.Paragraph(rest.filter { it.text.isNotEmpty() }))) + q.blocks.drop(1)
    return when (m.groupValues[1]) {
        "NOTE" -> AlertKind("Note", Icons.Info, NoticeTone.INFO, body)
        "TIP" -> AlertKind("Tip", Icons.Sparkles, NoticeTone.SUCCESS, body)
        "IMPORTANT" -> AlertKind("Important", Icons.MessageCircle, NoticeTone.INFO, body)
        "WARNING" -> AlertKind("Warning", Icons.TriangleAlert, NoticeTone.WARNING, body)
        else -> AlertKind("Caution", Icons.CircleAlert, NoticeTone.ERROR, body)
    }
}

@Composable
private fun AlertQuote(a: AlertKind, style: TextStyle) {
    val c = Candy.colors
    val color = when (a.tone) {
        NoticeTone.SUCCESS -> c.successForeground
        NoticeTone.WARNING -> c.warningForeground
        NoticeTone.ERROR -> c.errorForeground
        else -> c.infoForeground
    }
    Row(Modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(2.dp).fillMaxHeight().background(color))
        Column(Modifier.padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(a.icon, color, 16.dp)
                Txt(a.label, style.copy(fontWeight = FontWeight.Medium), color)
            }
            if (a.body.isNotEmpty()) MarkdownBlocks(a.body, style = style)
        }
    }
}

@Composable
private fun rememberSpans(spans: List<MdSpan>): AnnotatedString {
    val c = Candy.colors
    return remember(spans, c) { annotate(spans, c.infoForeground, c.accent) }
}

private fun annotate(spans: List<MdSpan>, link: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val code = s.style and MdSpan.CODE != 0
        val style = SpanStyle(
            fontWeight = if (s.style and MdSpan.BOLD != 0) FontWeight.SemiBold else null,
            fontStyle = if (s.style and MdSpan.ITALIC != 0) FontStyle.Italic else null,
            fontFamily = if (code) FontFamily.Monospace else null,
            // `:not(pre) > code`: a muted chip at 0.75rem. Spans cannot draw T3's border and radius.
            background = if (code) codeBackground else Color.Unspecified,
            fontSize = if (code) 13.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            textDecoration = if (s.style and MdSpan.STRIKE != 0) TextDecoration.LineThrough else null,
        )
        val url = s.link
        // An image left inline (in a table cell) reads as its alt text, linked when it is on the web.
        val text = if (s.style and MdSpan.IMAGE != 0 && s.text.isBlank()) "image" else s.text
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link)))) {
                withStyle(style) { append(text) }
            }
        } else {
            withStyle(style) { append(text) }
        }
    }
}

/**
 * A Markdown table as T3 draws it: header cells in `font-medium`, rows split by hairlines, cells
 * `px-3 py-2` in `text-sm`. Columns take their widest cell, and a wide table scrolls sideways.
 */
@Composable
private fun Table(t: MdBlock.Table) {
    val c = Candy.colors
    val rows = remember(t) { listOf(t.header) + t.rows }
    val columns = rows.maxOf { it.size }
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    for (col in 0 until columns) {
                        val spans = row.getOrNull(col).orEmpty()
                        BasicText(
                            rememberSpans(spans), Modifier.widthIn(max = 280.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                            style = CandyType.sm.copy(color = c.foreground, fontWeight = if (r == 0) FontWeight.SemiBold else FontWeight.Normal),
                        )
                    }
                }
                repeat(rows.size) { Box(Modifier.background(c.border)) }
            },
        ) { measurables, constraints ->
            val cells = measurables.take(rows.size * columns).map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
            val widths = IntArray(columns) { col -> rows.indices.maxOf { r -> cells[r * columns + col].width } }
            val heights = IntArray(rows.size) { r -> (0 until columns).maxOf { col -> cells[r * columns + col].height } }
            val total = maxOf(widths.sum(), constraints.minWidth)
            val line = 1.dp.roundToPx()
            val rules = measurables.drop(rows.size * columns).map { it.measure(androidx.compose.ui.unit.Constraints.fixed(total, line)) }
            layout(total, heights.sum() + line * rows.size) {
                var y = 0
                for (r in rows.indices) {
                    var x = 0
                    for (col in 0 until columns) {
                        cells[r * columns + col].place(x, y)
                        x += widths[col]
                    }
                    y += heights[r]
                    rules[r].place(0, y)
                    y += line
                }
            }
        }
    }
}

/**
 * A fenced block (ChatMarkdown's code block): a `rounded-xl` card on `--code-background` with a
 * border, the language and the wrap and copy buttons on top, coloured code that scrolls sideways
 * unless wrapped.
 */
@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val c = Candy.colors
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val text = remember(b, c) { highlight(b.code, b.tokens, c) }
    var wrap by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.xl - 2.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(c.codeBackground).border(1.dp, c.border, shape)) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            LanguageBadge(b.language)
            Box(Modifier.weight(1f))
            IconButton(Icons.TextWrap, if (wrap) "Stop wrapping" else "Wrap lines", tint = if (wrap) c.foreground else c.mutedForeground, size = 16.dp, box = 32.dp) { wrap = !wrap }
            IconButton(Icons.Copy, "Copy code", tint = c.mutedForeground, size = 16.dp, box = 32.dp) { clipboard.setText(AnnotatedString(b.code)) }
        }
        val codeModifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp)
        if (wrap) {
            BasicText(text, codeModifier, style = CandyType.mono.copy(color = c.foreground))
        } else {
            Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                BasicText(text, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), style = CandyType.mono.copy(color = c.foreground), softWrap = false)
            }
        }
    }
}

/** The language at the top of a code block, where T3 shows pierre's file-type icon: a small mono tag. */
@Composable
private fun LanguageBadge(language: String?) {
    val c = Candy.colors
    val label = when (language?.lowercase()) {
        null, "", "text", "plaintext" -> "text"
        "typescript", "ts", "tsx" -> "TS"
        "javascript", "js", "jsx" -> "JS"
        "bash", "sh", "shell", "zsh", "console" -> "\$_"
        else -> language.lowercase()
    }
    Txt(
        label, CandyType.xxs.copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold), c.mutedForeground, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(c.accent).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

private fun highlight(code: String, tokens: List<CodeToken>, c: CandyColors): AnnotatedString = buildAnnotatedString {
    append(code)
    for (t in tokens) {
        if (t.end > code.length || t.start >= t.end) continue
        val style = when (t.kind) {
            TokenKind.KEYWORD -> SpanStyle(color = c.synKeyword)
            TokenKind.STRING -> SpanStyle(color = c.synString)
            TokenKind.COMMENT -> SpanStyle(color = c.synComment, fontStyle = FontStyle.Italic)
            TokenKind.NUMBER -> SpanStyle(color = c.synNumber)
            TokenKind.TYPE -> SpanStyle(color = c.synType)
            TokenKind.FUNCTION -> SpanStyle(color = c.synFunction)
            TokenKind.ADDED -> SpanStyle(color = c.diffAdditionForeground, background = c.diffAdditionSurface)
            TokenKind.REMOVED -> SpanStyle(color = c.diffDeletionForeground, background = c.diffDeletionSurface)
        }
        addStyle(style, t.start, t.end)
    }
}
