package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** 中文 ↔ \uXXXX 互转（转义按钮的纯逻辑） */
class UnicodeConvertTest {

    // ---------- 转义：中文 → \uXXXX ----------

    @Test
    fun escapesNonAsciiOnly() {
        assertEquals(
            "{\"a\":\"\\u4e2d\\u6587\"}",
            JsonSerializer.escapeUnicodeText("{\"a\":\"中文\"}")
        )
    }

    @Test
    fun keepsAsciiAndStructure() {
        // 引号、换行、空格这些结构性字符必须原样保留，否则 JSON 会坏
        val text = "{\n  \"k\": 1,\n  \"s\": \"a b\\tc\"\n}"
        assertEquals(text, JsonSerializer.escapeUnicodeText(text))
    }

    @Test
    fun escapesEmojiWithSurrogates() {
        val escaped = JsonSerializer.escapeUnicodeText("x😀")
        // 😀 是代理对，拆成两个 \uXXXX
        assertEquals("x\\ud83d\\ude00", escaped)
        // 且能完整还原回来
        assertEquals("x😀", JsonSerializer.unescapeUnicode(escaped))
    }

    @Test
    fun pureAsciiFastPathReturnsSameInstanceContent() {
        assertEquals("plain {\"json\":1}", JsonSerializer.escapeUnicodeText("plain {\"json\":1}"))
    }

    // ---------- 还原：\uXXXX → 中文 ----------

    @Test
    fun unescapesBasic() {
        assertEquals("中文", JsonSerializer.unescapeUnicode("\\u4e2d\\u6587"))
    }

    @Test
    fun unescapeInsideJsonKeepsOtherEscapes() {
        // \n 这类转义不能动：还原成真实换行会把 JSON 弄坏
        val text = "{\"s\":\"\\u4e2d\\n\"}"
        assertEquals("{\"s\":\"中\\n\"}", JsonSerializer.unescapeUnicode(text))
    }

    @Test
    fun invalidSequenceKeptAsIs() {
        assertEquals("\\uZZZZ", JsonSerializer.unescapeUnicode("\\uZZZZ"))
        // 截断的序列也不动
        assertEquals("a\\u12", JsonSerializer.unescapeUnicode("a\\u12"))
    }

    @Test
    fun withoutUnicodeEscapeReturnsSame() {
        val text = "{\"a\":\"\\n\\t\"}"
        assertEquals(text, JsonSerializer.unescapeUnicode(text))
    }

    // ---------- 互转闭环 ----------

    @Test
    fun roundTrip() {
        val original = "{\"name\":\"张三\",\"city\":\"北京\",\"tag\":\"🔥\"}"
        val escaped = JsonSerializer.escapeUnicodeText(original)
        assertNotEquals(original, escaped)
        // 转义后仍是合法 JSON
        assertEquals(original, JsonSerializer.unescapeUnicode(escaped))
    }

    @Test
    fun literalBackslashEscapeAlsoConverted() {
        // JSON 字符串里的字面量 \\u4e2d（双反斜杠）也会被还原成 中——
        // 本功能按"所见即所得"处理原文，不区分它在字符串里是否有意写成字面量
        // 扫描按顺序消费：\\u4e2d 里第二个反斜杠与后面的 u4e2d 组成一次还原
        assertEquals("中\\中", JsonSerializer.unescapeUnicode("\\u4e2d\\\\u4e2d"))
    }
}
