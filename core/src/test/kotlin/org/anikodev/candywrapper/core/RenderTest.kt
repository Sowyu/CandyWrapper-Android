package org.anikodev.candywrapper.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import org.anikodev.candywrapper.core.diff.ItemDetail
import org.anikodev.candywrapper.core.diff.LineKind
import org.anikodev.candywrapper.core.diff.UnifiedDiff
import org.anikodev.candywrapper.core.json.WireJson
import org.anikodev.candywrapper.core.markdown.Highlighter
import org.anikodev.candywrapper.core.markdown.MdBlock
import org.anikodev.candywrapper.core.markdown.Markdown
import org.anikodev.candywrapper.core.markdown.TokenKind

class RenderTest {
    @Test
    fun parsesARecordedTurnDiff() {
        // The shape getTurnDiff returned in 03-turn2 (protocol.md 8.4).
        val text = """
            diff --git a/hello.py b/hello.py
            index 1111111..2222222 100644
            --- a/hello.py
            +++ b/hello.py
            @@ -1,2 +1,3 @@ def greet
             def greet(name):
            -    return "Hi"
            +    return f"Hello, {name}!"
            +
            diff --git a/notes.md b/notes.md
            new file mode 100644
            --- /dev/null
            +++ b/notes.md
            @@ -0,0 +1 @@
            +# Notes
            \ No newline at end of file
        """.trimIndent()
        val files = UnifiedDiff.parse(text)
        assertEquals(listOf("hello.py", "notes.md"), files.map { it.path })
        val hello = files[0]
        assertEquals(2, hello.additions)
        assertEquals(1, hello.deletions)
        assertEquals(listOf(LineKind.HUNK, LineKind.CONTEXT, LineKind.REMOVED, LineKind.ADDED, LineKind.ADDED), hello.lines.map { it.kind })
        assertEquals(2, hello.lines[2].oldLine)
        assertEquals(3, hello.lines[4].newLine)
        assertEquals(LineKind.NOTE, files[1].lines.last().kind)
    }

    @Test
    fun linesDiffKeepsCommonLines() {
        val lines = UnifiedDiff.lines("a\nb\nc", "a\nB\nc\nd")
        assertEquals("a,-b,+B,c,+d", lines.joinToString(",") {
            when (it.kind) { LineKind.ADDED -> "+${it.text}"; LineKind.REMOVED -> "-${it.text}"; else -> it.text }
        })
    }

    @Test
    fun itemDetailReadsFetchedItems() {
        fun json(s: String) = WireJson.parseToJsonElement(s) as JsonObject
        assertEquals("3\n", ItemDetail.from(json("""{"type":"command_execution","output":"3\n"}""")).text)
        val edit = ItemDetail.from(json("""{"type":"file_change","fileName":"a.txt","oldStr":"x","newStr":"y"}"""))
        assertEquals(listOf(LineKind.REMOVED, LineKind.ADDED), edit.diff!!.map { it.kind })
        assertEquals("Output is no longer available.", ItemDetail.from(null).text)
        assertTrue(ItemDetail.from(json("""{"type":"dynamic_tool","input":{"path":"/x"},"output":null}""")).text!!.contains("\"path\""))
    }

    @Test
    fun highlightsCodeBlocks() {
        val code = Markdown.parse("```kotlin\nval x = \"hi\" // note\nfun f() = 42\n```").single() as MdBlock.Code
        fun kindOf(word: String) = code.tokens.first { code.code.substring(it.start, it.end) == word }.kind
        assertEquals(TokenKind.KEYWORD, kindOf("val"))
        assertEquals(TokenKind.STRING, kindOf("\"hi\""))
        assertEquals(TokenKind.COMMENT, kindOf("// note"))
        assertEquals(TokenKind.FUNCTION, kindOf("f"))
        assertEquals(TokenKind.NUMBER, kindOf("42"))
        assertTrue(Highlighter.tokens("unknown-lang", "anything").isEmpty())
        assertEquals(TokenKind.ADDED, Highlighter.tokens("diff", "+x\n-y")[0].kind)
        // Shell comments need a word boundary: a#b is not a comment.
        assertTrue(Highlighter.tokens("sh", "echo a#b").none { it.kind == TokenKind.COMMENT })
    }
}
