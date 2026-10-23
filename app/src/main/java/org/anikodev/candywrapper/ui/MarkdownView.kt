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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.layout.Row as LayoutRow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import org.anikodev.candywrapper.core.markdown.CodeToken
import org.anikodev.candywrapper.core.markdown.TokenKind
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.MdSpan

/** Draws parsed Markdown with Compose text. Parsing already happened off the main thread. */
@Composable
fun MarkdownBlocks(blocks: List<MdBlock>, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (b in blocks) Block(b)
    }
}

@Composable
private fun Block(b: MdBlock) {
    val c = Candy.colors
    when (b) {
        is MdBlock.Paragraph -> BasicText(rememberSpans(b.spans), style = CandyType.body.copy(color = c.foreground))
        is MdBlock.Heading -> BasicText(
            rememberSpans(b.spans),
            Modifier.padding(top = 4.dp),
            style = TextStyle(color = c.foreground, fontWeight = FontWeight.SemiBold,
                fontSize = when (b.level) { 1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 15.sp }, lineHeight = when (b.level) { 1 -> 28.sp; 2 -> 25.sp; else -> 22.sp }),
        )
        is MdBlock.Code -> CodeBlock(b)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.border, RoundedCornerShape(2.dp)))
            MarkdownBlocks(b.blocks, Modifier.padding(start = 10.dp))
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    val marker = when {
                        item.checked == true -> "☑"
                        item.checked == false -> "☐"
                        b.ordered -> "${b.start + i}."
                        else -> "•"
                    }
                    BasicText(marker, Modifier.widthIn(min = 22.dp), style = CandyType.body.copy(color = c.mutedForeground))
                    MarkdownBlocks(item.blocks)
                }
            }
        }
        is MdBlock.Table -> Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.md)).border(1.dp, c.border, RoundedCornerShape(Radius.md))
                .horizontalScroll(rememberScrollState()).padding(10.dp),
        ) {
            BasicText(remember(b) { tableText(b) }, style = CandyType.mono.copy(color = c.foreground), softWrap = false)
        }
        MdBlock.Rule -> Divider()
    }
}

@Composable
private fun rememberSpans(spans: List<MdSpan>): AnnotatedString {
    val c = Candy.colors
    return remember(spans, c) { annotate(spans, c.primary, c.codeBackground) }
}

private fun annotate(spans: List<MdSpan>, link: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (s in spans) {
        val style = SpanStyle(
            fontWeight = if (s.style and MdSpan.BOLD != 0) FontWeight.SemiBold else null,
            fontStyle = if (s.style and MdSpan.ITALIC != 0) FontStyle.Italic else null,
            fontFamily = if (s.style and MdSpan.CODE != 0) FontFamily.Monospace else null,
            background = if (s.style and MdSpan.CODE != 0) codeBackground else Color.Unspecified,
            fontSize = if (s.style and MdSpan.CODE != 0) 13.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            textDecoration = if (s.style and MdSpan.STRIKE != 0) TextDecoration.LineThrough else null,
        )
        val url = s.link
        if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
            withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(color = link, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(s.text) }
            }
        } else {
            withStyle(style) { append(s.text) }
        }
    }
}

/** Tables as aligned monospace text, which keeps wide tables cheap to lay out on a phone. */
private fun tableText(t: MdBlock.Table): String {
    val rows = listOf(t.header) + t.rows
    val text = rows.map { r -> r.map { cell -> cell.joinToString("") { it.text } } }
    val widths = IntArray(text.maxOf { it.size }) { col -> text.maxOf { it.getOrNull(col)?.length ?: 0 } }
    fun line(r: List<String>) = r.indices.joinToString("  ") { r[it].padEnd(widths[it]) }.trimEnd()
    return buildString {
        appendLine(line(text[0]))
        appendLine(widths.joinToString("  ") { "─".repeat(it) })
        text.drop(1).forEach { appendLine(line(it)) }
    }.trimEnd()
}

/** A fenced block: language label and copy on top, coloured code that scrolls sideways. */
@Composable
private fun CodeBlock(b: MdBlock.Code) {
    val c = Candy.colors
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val text = remember(b, c) { highlight(b.code, b.tokens, c) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).background(c.codeBackground).border(1.dp, c.border, RoundedCornerShape(Radius.lg))) {
        LayoutRow(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(b.language ?: "text", CandyType.meta, c.mutedForeground, modifier = Modifier.weight(1f))
            IconButton(Icons.Copy, "Copy code", size = 15.dp) { clipboard.setText(AnnotatedString(b.code)) }
        }
        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
            BasicText(text, style = CandyType.mono.copy(color = c.foreground), softWrap = false)
        }
    }
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
