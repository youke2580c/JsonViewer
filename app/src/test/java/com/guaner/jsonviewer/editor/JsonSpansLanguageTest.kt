package com.guaner.jsonviewer.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSpansLanguageTest {

    private fun kinds(line: String): List<TokenKind> =
        JsonSpansLanguage.scanLine(line).map { it.kind }

    private fun texts(line: String): List<String> =
        JsonSpansLanguage.scanLine(line).map { line.substring(it.start, it.end) }

    @Test
    fun `key is string followed by colon`() {
        val line = """  "name": "value","""
        assertEquals(
            listOf(TokenKind.KEY, TokenKind.PUNCT, TokenKind.STRING, TokenKind.PUNCT),
            kinds(line)
        )
        assertEquals(listOf("\"name\"", ":", "\"value\"", ","), texts(line))
    }

    @Test
    fun `string with colon inside stays a string`() {
        // 冒号在字符串内部：不能因为里面出现 ':' 就判成键
        val line = """{"url": "http://a.b/c","""
        assertEquals(
            listOf(TokenKind.PUNCT, TokenKind.KEY, TokenKind.PUNCT, TokenKind.STRING, TokenKind.PUNCT),
            kinds(line)
        )
    }

    @Test
    fun `array elements without colon are strings`() {
        assertEquals(
            listOf(
                TokenKind.PUNCT, TokenKind.STRING, TokenKind.PUNCT,
                TokenKind.STRING, TokenKind.PUNCT, TokenKind.PUNCT
            ),
            kinds("""["a","b",]""")
        )
    }

    @Test
    fun `numbers cover sign decimal exponent`() {
        for (raw in listOf("0", "-1", "1.5", "-0.25", "1e10", "1E+10", "2.5e-3")) {
            val tokens = JsonSpansLanguage.scanLine(raw)
            assertEquals(raw, 1, tokens.size)
            assertEquals(TokenKind.NUMBER, tokens[0].kind)
            assertEquals(raw.length, tokens[0].end - tokens[0].start)
        }
    }

    @Test
    fun `literals are recognized`() {
        assertEquals(
            listOf(TokenKind.LITERAL, TokenKind.PUNCT, TokenKind.LITERAL, TokenKind.PUNCT, TokenKind.LITERAL),
            kinds("""true,false,null""")
        )
    }

    @Test
    fun `escaped quote does not end the string`() {
        val line = """{"s": "a\"b"}"""
        val tokens = JsonSpansLanguage.scanLine(line)
        val strings = tokens.filter { it.kind == TokenKind.KEY || it.kind == TokenKind.STRING }
        assertEquals(2, strings.size)
        assertEquals("\"a\\\"b\"", line.substring(strings[1].start, strings[1].end))
    }

    @Test
    fun `unterminated string stops at line end`() {
        val line = "{\"a\": \"not closed"
        val tokens = JsonSpansLanguage.scanLine(line)
        val last = tokens.last()
        assertEquals(line.length, last.end)
        assertEquals(TokenKind.STRING, last.kind)
    }

    @Test
    fun `token positions are non overlapping and ascending`() {
        val line = """{"k1":[1,-2.5,true,null],"k2":{"n":"x"}}"""
        val tokens = JsonSpansLanguage.scanLine(line)
        var prevEnd = -1
        for (t in tokens) {
            assertTrue("start ${t.start} should be >= $prevEnd", t.start >= prevEnd)
            assertTrue("end ${t.end} should be > start ${t.start}", t.end > t.start)
            prevEnd = t.end
        }
    }

    @Test
    fun `token budget caps huge single line`() {
        val line = "[" + (1..5_000).joinToString(",") + "]"
        val tokens = JsonSpansLanguage.scanLine(line, maxTokens = 100)
        assertEquals(100, tokens.size)
    }

    @Test
    fun `empty and whitespace lines produce no tokens`() {
        assertTrue(JsonSpansLanguage.scanLine("").isEmpty())
        assertTrue(JsonSpansLanguage.scanLine("    ").isEmpty())
    }

    @Test
    fun `plain text does not crash and yields no tokens`() {
        // 未美化且非 JSON 的超大文本也走这条路径：不能抛异常
        assertTrue(JsonSpansLanguage.scanLine("这不是 json，只是一段普通文字").isEmpty())
    }

    @Test
    fun `brackets and commas are punctuation`() {
        assertEquals(
            listOf(
                TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.PUNCT,
                TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.PUNCT
            ),
            kinds("{[}],:")
        )
    }

    @Test
    fun `colors ids are outside builtin range`() {
        // 自定义槽位必须远离内置编号，避免和 Sora 自带配色互相覆盖
        val ids = listOf(
            JsonSpansLanguage.ColorIds.KEY,
            JsonSpansLanguage.ColorIds.STRING,
            JsonSpansLanguage.ColorIds.NUMBER,
            JsonSpansLanguage.ColorIds.LITERAL,
            JsonSpansLanguage.ColorIds.PUNCT
        )
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it > 100 })
    }
}
