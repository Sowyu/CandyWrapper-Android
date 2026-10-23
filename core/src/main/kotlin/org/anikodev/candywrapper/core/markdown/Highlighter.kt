package org.anikodev.candywrapper.core.markdown

enum class TokenKind { KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ADDED, REMOVED }

/** A coloured range of a code block, `[start, end)` in UTF-16 units. */
data class CodeToken(val start: Int, val end: Int, val kind: TokenKind)

/**
 * A small lexer that colours fenced code by language family: keywords, strings, comments,
 * numbers, capitalised types and called functions. It approximates T3's Shiki colouring, which a
 * phone cannot afford to run. Runs during Markdown parsing, off the main thread.
 */
object Highlighter {
    private const val MAX_CHARS = 24_000

    private class Lang(
        val keywords: Set<String>,
        val line: List<String>,
        val block: Pair<String, String>?,
        val quotes: String,
        val types: Boolean = true,
    )

    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val cLike = Lang(
        words(
            "abstract as async await break case catch class const continue default defer do else enum export extends false final finally fn for " +
                "fun func function go guard if impl implements import in interface internal is let match mod mut new nil null object override " +
                "package private protected pub public return self static struct super switch this throw throws true try type typeof val var void " +
                "when where while with yield readonly declare keyof satisfies lateinit suspend data sealed companion init use crate trait loop " +
                "dyn ref unsafe extern char int long float double bool boolean string u8 u16 u32 u64 i32 i64 usize f32 f64",
        ),
        listOf("//"), "/*" to "*/", "\"'`",
    )
    private val python = Lang(
        words("and as assert async await break class continue def del elif else except False finally for from global if import in is lambda None nonlocal not or pass raise return True try while with yield self print"),
        listOf("#"), null, "\"'",
    )
    private val shell = Lang(
        words("if then else elif fi for while do done case esac in function return export local echo cd exit set unset source alias sudo npx npm pnpm git"),
        listOf("#"), null, "\"'", types = false,
    )
    private val ruby = Lang(words("def end class module if elsif else unless while do return yield self nil true false require puts and or not then begin rescue ensure"), listOf("#"), null, "\"'")
    private val sql = Lang(
        words("select from where and or not insert into values update set delete create table index drop alter join left right inner outer on as order by group having limit primary key null is in exists distinct union all".let { it + " " + it.uppercase() }),
        listOf("--"), "/*" to "*/", "'\"", types = false,
    )
    private val data = Lang(words("true false null yes no on off"), listOf("#"), null, "\"'", types = false)
    private val json = Lang(words("true false null"), emptyList(), null, "\"", types = false)
    private val css = Lang(words("important media import from to"), emptyList(), "/*" to "*/", "\"'", types = false)
    private val markup = Lang(emptySet(), emptyList(), "<!--" to "-->", "\"'", types = false)

    private fun lang(name: String?): Lang? = when (name?.lowercase()?.substringBefore('{')) {
        "kotlin", "kt", "kts", "java", "js", "javascript", "jsx", "ts", "typescript", "tsx", "mjs", "cjs", "swift", "go", "golang", "rust", "rs",
        "c", "h", "cpp", "c++", "cc", "hpp", "cs", "csharp", "dart", "scala", "groovy", "gradle", "php", "objc", "zig" -> cLike
        "python", "py" -> python
        "sh", "bash", "zsh", "shell", "console", "shellscript", "fish", "ps1", "powershell" -> shell
        "ruby", "rb" -> ruby
        "sql" -> sql
        "yaml", "yml", "toml", "ini", "dotenv", "env" -> data
        "json", "jsonc", "json5" -> json
        "css", "scss", "less" -> css
        "html", "xml", "svg", "vue", "svelte" -> markup
        else -> null
    }

    fun tokens(language: String?, code: String): List<CodeToken> {
        if (code.length > MAX_CHARS) return emptyList()
        if (language?.lowercase() == "diff" || language?.lowercase() == "patch") return diff(code)
        val l = lang(language) ?: return emptyList()
        val out = ArrayList<CodeToken>()
        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]
            val lineComment = l.line.firstOrNull { code.startsWith(it, i) }
            when {
                lineComment != null && (lineComment != "#" || i == 0 || !code[i - 1].isLetterOrDigit()) -> {
                    val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += CodeToken(i, end, TokenKind.COMMENT); i = end
                }
                l.block != null && code.startsWith(l.block.first, i) -> {
                    val end = code.indexOf(l.block.second, i + l.block.first.length).let { if (it < 0) n else it + l.block.second.length }
                    out += CodeToken(i, end, TokenKind.COMMENT); i = end
                }
                ch in l.quotes -> {
                    var j = i + 1
                    while (j < n && code[j] != ch && !(code[j] == '\n' && ch != '`')) j += if (code[j] == '\\') 2 else 1
                    val end = minOf(n, j + 1)
                    out += CodeToken(i, end, TokenKind.STRING); i = end
                }
                ch.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    out += CodeToken(i, j, TokenKind.NUMBER); i = j
                }
                ch.isLetter() || ch == '_' || ch == '$' || ch == '@' -> {
                    var j = i + 1
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$' || (ch == '@' && code[j] == '.'))) j++
                    val word = code.substring(i, j)
                    var k = j
                    while (k < n && code[k] == ' ') k++
                    val kind = when {
                        word in l.keywords -> TokenKind.KEYWORD
                        ch == '@' -> TokenKind.KEYWORD
                        k < n && code[k] == '(' -> TokenKind.FUNCTION
                        l.types && ch.isUpperCase() && word.length > 1 -> TokenKind.TYPE
                        else -> null
                    }
                    if (kind != null) out += CodeToken(i, j, kind)
                    i = j
                }
                else -> i++
            }
        }
        return out
    }

    private fun diff(code: String): List<CodeToken> {
        val out = ArrayList<CodeToken>()
        var start = 0
        for (line in code.split('\n')) {
            val end = start + line.length
            when {
                line.startsWith("+++") || line.startsWith("---") -> out += CodeToken(start, end, TokenKind.COMMENT)
                line.startsWith("+") -> out += CodeToken(start, end, TokenKind.ADDED)
                line.startsWith("-") -> out += CodeToken(start, end, TokenKind.REMOVED)
                line.startsWith("@@") -> out += CodeToken(start, end, TokenKind.FUNCTION)
            }
            start = end + 1
        }
        return out
    }
}
