package org.anikodev.candywrapper.core.markdown

/**
 * A small GFM-flavoured Markdown parser for assistant replies, run off the main thread. It covers
 * what agents write: paragraphs, headings, fenced code, lists with task boxes, quotes, tables,
 * rules, emphasis, inline code, strikethrough, links and images. Single newlines are line breaks, as in
 * T3 (remark-breaks, packages/shared/src/markdownPipeline.ts). The app turns the blocks into
 * Compose text; nothing here knows about the UI.
 */
sealed interface MdBlock {
    data class Paragraph(val spans: List<MdSpan>) : MdBlock
    data class Heading(val level: Int, val spans: List<MdSpan>) : MdBlock
    /** [tokens] colour the code; they are computed with the parse, off the main thread. */
    data class Code(val language: String?, val code: String, val tokens: List<CodeToken> = emptyList()) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class ListBlock(val ordered: Boolean, val start: Int, val items: List<ListItem>) : MdBlock
    data class Table(val header: List<List<MdSpan>>, val rows: List<List<List<MdSpan>>>) : MdBlock
    /** `![alt](src)`, lifted out of its paragraph so it gets its own box. */
    data class Image(val src: String, val alt: String) : MdBlock
    data object Rule : MdBlock
}

data class ListItem(val blocks: List<MdBlock>, val checked: Boolean?)

/**
 * A run of text with one style. [style] is a bit set of [BOLD], [ITALIC], [CODE], [STRIKE] and
 * [IMAGE]; an image span carries its alt text and its source in [link].
 */
data class MdSpan(val text: String, val style: Int = 0, val link: String? = null) {
    companion object {
        const val BOLD = 1
        const val ITALIC = 2
        const val CODE = 4
        const val STRIKE = 8
        const val IMAGE = 16
    }
}

object Markdown {
    fun parse(text: String): List<MdBlock> = blocks(text.replace("\r\n", "\n").split('\n'))

    private val fence = Regex("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*).*$")
    private val heading = Regex("^ {0,3}(#{1,6})(?:\\s+(.*?))?\\s*#*\\s*$")
    private val rule = Regex("^ {0,3}([-*_])(?:\\s*\\1){2,}\\s*$")
    private val quote = Regex("^ {0,3}> ?(.*)$")
    private val item = Regex("^( {0,3})([-*+]|\\d{1,9}[.)])(?: +(.*)|$)")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*$")

    private fun blocks(lines: List<String>): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.isBlank()) { i++; continue }
            val f = fence.matchEntire(line)
            if (f != null) {
                val marker = f.groupValues[1]
                val body = ArrayList<String>()
                i++
                while (i < lines.size && !isClosingFence(lines[i], marker)) body += lines[i++]
                i++
                val language = f.groupValues[2].ifBlank { null }
                val code = body.joinToString("\n")
                out += MdBlock.Code(language, code, Highlighter.tokens(language, code))
                continue
            }
            val h = heading.matchEntire(line)
            if (h != null) {
                out += MdBlock.Heading(h.groupValues[1].length, inlines(h.groupValues[2]))
                i++
                continue
            }
            if (rule.matches(line)) { out += MdBlock.Rule; i++; continue }
            if (quote.matches(line)) {
                val inner = ArrayList<String>()
                while (i < lines.size && lines[i].isNotBlank()) {
                    val q = quote.matchEntire(lines[i])
                    if (q == null && startsBlock(lines[i])) break
                    inner += q?.groupValues?.get(1) ?: lines[i]
                    i++
                }
                out += MdBlock.Quote(blocks(inner))
                continue
            }
            if (item.matches(line)) {
                i = list(lines, i, out)
                continue
            }
            if (line.contains('|') && i + 1 < lines.size && tableSeparator.matches(lines[i + 1]) && lines[i + 1].contains('-')) {
                val header = cells(line)
                i += 2
                val rows = ArrayList<List<List<MdSpan>>>()
                while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) rows += cells(lines[i++])
                out += MdBlock.Table(header, rows)
                continue
            }
            val para = ArrayList<String>()
            while (i < lines.size && lines[i].isNotBlank() && (para.isEmpty() || !startsBlock(lines[i]))) para += lines[i++]
            paragraph(inlines(para.joinToString("\n") { it.trimStart() }), out)
        }
        return out
    }

    /** A paragraph, split around its images: each image is a block of its own, the text between stays a paragraph. */
    private fun paragraph(spans: List<MdSpan>, out: MutableList<MdBlock>) {
        if (spans.none { it.style and MdSpan.IMAGE != 0 }) { out += MdBlock.Paragraph(spans); return }
        var run = ArrayList<MdSpan>()
        fun flush() {
            // Line breaks left between an image and the text are layout, not content.
            val trimmed = run.dropWhile { it.text.isBlank() }.dropLastWhile { it.text.isBlank() }
            if (trimmed.isNotEmpty()) out += MdBlock.Paragraph(trimmed)
            run = ArrayList()
        }
        for (span in spans) {
            if (span.style and MdSpan.IMAGE != 0) {
                flush()
                out += MdBlock.Image(span.link.orEmpty(), span.text)
            } else run += span
        }
        flush()
    }

    private fun isClosingFence(line: String, marker: String): Boolean {
        val t = line.trim()
        return t.length >= marker.length && t.all { it == marker[0] }
    }

    private fun startsBlock(line: String): Boolean =
        fence.matches(line) || heading.matches(line) || rule.matches(line) || quote.matches(line) ||
            item.matchEntire(line)?.let { it.groupValues[3].isNotBlank() } == true

    /** Parses a list starting at [start]; returns the index after it. */
    private fun list(lines: List<String>, start: Int, out: MutableList<MdBlock>): Int {
        val first = item.matchEntire(lines[start])!!
        val ordered = first.groupValues[2].first().isDigit()
        val delimiter = first.groupValues[2].last()
        val items = ArrayList<ListItem>()
        var i = start
        while (i < lines.size) {
            val m = item.matchEntire(lines[i]) ?: break
            val marker = m.groupValues[2]
            if (marker.first().isDigit() != ordered || marker.last() != delimiter) break
            val indent = m.groupValues[1].length + marker.length + 1
            val body = ArrayList<String>()
            body += m.groupValues[3]
            i++
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) {
                    // A blank line continues the item only when indented content follows.
                    val next = lines.getOrNull(i + 1)
                    if (next != null && next.isNotBlank() && leading(next) >= indent) { body += ""; i++; continue }
                    break
                }
                if (leading(l) >= indent) { body += l.drop(indent); i++; continue }
                if (item.matches(l) || startsBlock(l)) break
                body += l.trimStart()
                i++
            }
            var checked: Boolean? = null
            val head = body[0]
            if (head.startsWith("[ ] ") || head.startsWith("[x] ") || head.startsWith("[X] ")) {
                checked = head[1] != ' '
                body[0] = head.drop(4)
            }
            items += ListItem(blocks(body), checked)
            if (i < lines.size && lines[i].isBlank()) {
                val next = lines.getOrNull(i + 1)
                if (next != null && item.matchEntire(next)?.groupValues?.get(2)?.let { it.first().isDigit() == ordered } == true) i++
            }
        }
        out += MdBlock.ListBlock(ordered, if (ordered) first.groupValues[2].dropLast(1).toIntOrNull() ?: 1 else 1, items)
        return i
    }

    private fun leading(s: String): Int {
        var n = 0
        for (c in s) { if (c == ' ') n++ else if (c == '\t') n += 4 else break }
        return n
    }

    private fun cells(line: String): List<List<MdSpan>> {
        var t = line.trim()
        if (t.startsWith('|')) t = t.drop(1)
        if (t.endsWith('|') && !t.endsWith("\\|")) t = t.dropLast(1)
        return t.split(Regex("(?<!\\\\)\\|")).map { inlines(it.trim().replace("\\|", "|")) }
    }

    // Inlines

    fun inlines(s: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        Inline(s, out).run(0, s.length, 0, null)
        return merge(out)
    }

    private class Inline(val s: String, val out: MutableList<MdSpan>) {
        private val buf = StringBuilder()

        fun run(from: Int, to: Int, style: Int, link: String?) {
            var i = from
            while (i < to) {
                val c = s[i]
                when {
                    c == '\\' && i + 1 < to && s[i + 1] in PUNCT -> { buf.append(s[i + 1]); i += 2 }
                    c == '\\' && i + 1 < to && s[i + 1] == '\n' -> { buf.append('\n'); i += 2 }
                    c == '`' -> {
                        val n = runLength(i, to, '`')
                        val close = findRun(i + n, to, '`', n)
                        if (close < 0) { buf.append(s, i, i + n); i += n } else {
                            flush(style, link)
                            var code = s.substring(i + n, close).replace('\n', ' ')
                            if (code.length > 2 && code.startsWith(' ') && code.endsWith(' ')) code = code.substring(1, code.length - 1)
                            out += MdSpan(code, style or MdSpan.CODE, link)
                            i = close + n
                        }
                    }
                    (c == '*' || c == '_') -> {
                        val n = minOf(runLength(i, to, c), 3)
                        val opens = i + n < to && !s[i + n].isWhitespace() && (c == '*' || i == from || !s[i - 1].isLetterOrDigit())
                        val close = if (opens) findEmphasisClose(i + n, to, c, n) else -1
                        if (close < 0) { buf.append(s, i, i + n); i += n } else {
                            flush(style, link)
                            val add = when (n) { 1 -> MdSpan.ITALIC; 2 -> MdSpan.BOLD; else -> MdSpan.BOLD or MdSpan.ITALIC }
                            run(i + n, close, style or add, link)
                            flush(style or add, link)
                            i = close + n
                        }
                    }
                    c == '~' && i + 1 < to && s[i + 1] == '~' -> {
                        val close = s.indexOf("~~", i + 2).takeIf { it in 0 until to } ?: -1
                        if (close <= i + 2) { buf.append("~~"); i += 2 } else {
                            flush(style, link)
                            run(i + 2, close, style or MdSpan.STRIKE, link)
                            flush(style or MdSpan.STRIKE, link)
                            i = close + 2
                        }
                    }
                    c == '!' && i + 1 < to && s[i + 1] == '[' && link == null -> {
                        val parsed = linkAt(i + 1, to)
                        if (parsed == null) { buf.append(c); i++ } else {
                            flush(style, link)
                            val (textEnd, url, end) = parsed
                            out += MdSpan(s.substring(i + 2, textEnd), MdSpan.IMAGE, url)
                            i = end
                        }
                    }
                    c == '[' && link == null -> {
                        val parsed = linkAt(i, to)
                        if (parsed == null) { buf.append(c); i++ } else {
                            flush(style, link)
                            val (textEnd, url, end) = parsed
                            if (textEnd > i + 1) run(i + 1, textEnd, style, url) else buf.append(url)
                            flush(style, url)
                            i = end
                        }
                    }
                    c == '<' && link == null -> {
                        val close = s.indexOf('>', i)
                        val inner = if (close in 0 until to) s.substring(i + 1, close) else null
                        if (inner != null && (inner.startsWith("http://") || inner.startsWith("https://")) && inner.none { it.isWhitespace() }) {
                            flush(style, link)
                            out += MdSpan(inner, style, inner)
                            i = close + 1
                        } else { buf.append(c); i++ }
                    }
                    c == 'h' && link == null && (i == from || !s[i - 1].isLetterOrDigit()) &&
                        (s.startsWith("https://", i) || s.startsWith("http://", i)) -> {
                        var end = i
                        while (end < to && !s[end].isWhitespace() && s[end] != '<') end++
                        while (end > i && s[end - 1] in ".,;:!?)'\"") end--
                        flush(style, link)
                        val url = s.substring(i, end)
                        out += MdSpan(url, style, url)
                        i = end
                    }
                    else -> { buf.append(c); i++ }
                }
            }
            flush(style, link)
        }

        private fun flush(style: Int, link: String?) {
            if (buf.isNotEmpty()) {
                out += MdSpan(buf.toString(), style, link)
                buf.setLength(0)
            }
        }

        private fun runLength(i: Int, to: Int, c: Char): Int {
            var j = i
            while (j < to && s[j] == c) j++
            return j - i
        }

        private fun findRun(from: Int, to: Int, c: Char, n: Int): Int {
            var j = from
            while (j < to) {
                if (s[j] == c) {
                    val len = runLength(j, to, c)
                    if (len == n) return j
                    j += len
                } else j++
            }
            return -1
        }

        private fun findEmphasisClose(from: Int, to: Int, c: Char, n: Int): Int {
            var j = from
            while (j < to) {
                when (s[j]) {
                    '\\' -> j += 2
                    '`' -> {
                        val len = runLength(j, to, '`')
                        val close = findRun(j + len, to, '`', len)
                        j = if (close < 0) j + len else close + len
                    }
                    c -> {
                        val len = runLength(j, to, c)
                        val closes = !s[j - 1].isWhitespace() && (c == '*' || j + len >= to || !s[j + len].isLetterOrDigit())
                        if (closes && len >= n && j > from) return j + (len - n)
                        j += len
                    }
                    else -> j++
                }
            }
            return -1
        }

        /** `[text](url)` at [open]: (end of text, url, index after the closing paren). */
        private fun linkAt(open: Int, to: Int): Triple<Int, String, Int>? {
            var depth = 0
            var j = open
            while (j < to) {
                when (s[j]) {
                    '\\' -> j++
                    '[' -> depth++
                    ']' -> { depth--; if (depth == 0) break }
                }
                j++
            }
            if (j >= to || j + 1 >= to || s[j + 1] != '(') return null
            val close = s.indexOf(')', j + 2).takeIf { it in 0 until to } ?: return null
            val url = s.substring(j + 2, close).trim().substringBefore(' ').removeSurrounding("<", ">")
            if (url.isEmpty()) return null
            return Triple(j, url, close + 1)
        }

        companion object {
            const val PUNCT = "\\`*_{}[]()#+-.!|~<>\"'"
        }
    }

    private fun merge(spans: List<MdSpan>): List<MdSpan> {
        if (spans.size < 2) return spans
        val out = ArrayList<MdSpan>(spans.size)
        for (s in spans) {
            val last = out.lastOrNull()
            if (last != null && last.style == s.style && last.link == s.link && s.style and MdSpan.IMAGE == 0) out[out.size - 1] = last.copy(text = last.text + s.text) else out += s
        }
        return out
    }
}
