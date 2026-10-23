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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
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
        is MdBlock.Paragraph -> Text(rememberSpans(b.spans), fontSize = 15.sp, lineHeight = 22.sp, color = c.foreground)
        is MdBlock.Heading -> Text(
            rememberSpans(b.spans), color = c.foreground, fontWeight = FontWeight.SemiBold,
            fontSize = when (b.level) { 1 -> 20.sp; 2 -> 18.sp; 3 -> 16.sp; else -> 15.sp },
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().background(c.codeBackground, RoundedCornerShape(8.dp)).border(1.dp, c.border, RoundedCornerShape(8.dp))
                .horizontalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text(b.code, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = c.foreground, softWrap = false)
        }
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
                    Text(marker, fontSize = 15.sp, lineHeight = 22.sp, color = c.mutedForeground, modifier = Modifier.widthIn(min = 22.dp))
                    MarkdownBlocks(item.blocks)
                }
            }
        }
        is MdBlock.Table -> Box(Modifier.horizontalScroll(rememberScrollState())) {
            Text(remember(b) { tableText(b) }, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = c.foreground, softWrap = false)
        }
        MdBlock.Rule -> HorizontalDivider(color = c.border)
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
