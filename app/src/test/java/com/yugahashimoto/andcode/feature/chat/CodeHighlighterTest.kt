package com.yugahashimoto.andcode.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHighlighterTest {
    private fun typesOf(vararg types: CodeTokenType): List<CodeTokenType> = types.toList()

    private fun tokenAt(code: List<CodeToken>, text: String): CodeToken = code.first { it.text == text }

    @Test
    fun `tokens always reconstruct the original text`() {
        val samples =
            listOf(
                "python" to
                    """
                    def add(a, b: int) -> int:
                        # compute the sum
                        doc = '''triple
                        quoted'''
                        return a + b  # trailing too
                    """.trimIndent(),
                "shell" to
                    "export API_KEY=x # comment\nx=1#not-a-comment\necho \"hello\"",
                "unknown-lang" to
                    "const url = \"http://x/y\" // 42\na # b",
                "python" to
                    "x = \"unterminated\nmore = 'y\"",
                "c" to "/* unterminated block\nstill comment */ int x; // ok",
            )
        for ((language, code) in samples) {
            val tokens = CodeHighlighter.highlight(language, code)
            assertEquals(language, code, tokens.joinToString("") { it.text })
        }
    }

    @Test
    fun `python code highlights keywords strings comments and calls`() {
        val code = "def add(a, b):\n    # compute\n    return a + b"
        val tokens = CodeHighlighter.highlight("python", code)
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "def").type)
        assertEquals(CodeTokenType.Function, tokenAt(tokens, "add").type)
        assertEquals(CodeTokenType.Comment, tokenAt(tokens, "# compute").type)
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "return").type)
    }

    @Test
    fun `strings swallow comment-like characters`() {
        val tokens = CodeHighlighter.highlight("javascript", "const url = \"http://x\"; /* real */")
        assertEquals(CodeTokenType.String, tokenAt(tokens, "\"http://x\"").type)
        assertEquals(CodeTokenType.Comment, tokenAt(tokens, "/* real */").type)
    }

    @Test
    fun `hash comments need word boundaries in shell but not in python`() {
        val shell = CodeHighlighter.highlight("shell", "x=1#nope")
        assertTrue(shell.none { it.type == CodeTokenType.Comment })
        assertEquals(CodeTokenType.Plain, tokenAt(shell, "#nope").type)

        val python = CodeHighlighter.highlight("python", "x=1#boom")
        assertEquals(CodeTokenType.Number, tokenAt(python, "1").type)
        assertEquals(CodeTokenType.Comment, tokenAt(python, "#boom").type)
    }

    @Test
    fun `aliases share the canonical language config`() {
        val code = "def one():\n    return 1"
        assertEquals(
            CodeHighlighter.highlight("python", code).map { it.type },
            CodeHighlighter.highlight("py", code).map { it.type },
        )
    }

    @Test
    fun `unknown language stays mostly plain but marks numbers`() {
        val tokens = CodeHighlighter.highlight("log-ish-language", "count = 42")
        assertTrue(tokens.none { it.type == CodeTokenType.Keyword })
        assertEquals(CodeTokenType.Number, tokenAt(tokens, "42").type)
    }

    @Test
    fun `missing language still marks strings`() {
        val tokens = CodeHighlighter.highlight(null, "value \"text\" 42")
        assertEquals(CodeTokenType.String, tokenAt(tokens, "\"text\"").type)
        assertEquals(CodeTokenType.Number, tokenAt(tokens, "42").type)
    }

    @Test
    fun `diff blocks mark additions removals and metadata`() {
        val code =
            listOf(
                "diff --git a/f b/f",
                "+++ b/f",
                "--- a/f",
                "@@ -1,3 +1,4 @@",
                "+added",
                "-removed",
                " context",
                "",
            ).joinToString("\n")
        val tokens = CodeHighlighter.highlight("diff", code)
        val lines = tokens.filter { it.text != "\n" }
        assertEquals(
            typesOf(
                CodeTokenType.DiffMeta,
                CodeTokenType.DiffMeta,
                CodeTokenType.DiffMeta,
                CodeTokenType.DiffMeta,
                CodeTokenType.DiffAdd,
                CodeTokenType.DiffRemove,
                CodeTokenType.Plain,
                CodeTokenType.Plain,
            ),
            lines.map { it.type },
        )
    }

    @Test
    fun `json keys are marked while values stay strings`() {
        val tokens = CodeHighlighter.highlight("json", "{\"a\": \"b\", \"count\": 42, \"ok\": true}")
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "\"a\"").type)
        assertEquals(CodeTokenType.String, tokenAt(tokens, "\"b\"").type)
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "\"count\"").type)
        assertEquals(CodeTokenType.Number, tokenAt(tokens, "42").type)
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "true").type)
    }

    @Test
    fun `sql keywords match case-insensitively`() {
        val tokens = CodeHighlighter.highlight("sql", "SELECT id FROM users")
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "SELECT").type)
        assertEquals(CodeTokenType.Keyword, tokenAt(tokens, "FROM").type)
    }

    @Test
    fun `oversized code is left unhighlighted`() {
        val code = "a".repeat(21_000)
        val tokens = CodeHighlighter.highlight("python", code)
        assertEquals(listOf(CodeToken(code, CodeTokenType.Plain)), tokens)
    }
}
