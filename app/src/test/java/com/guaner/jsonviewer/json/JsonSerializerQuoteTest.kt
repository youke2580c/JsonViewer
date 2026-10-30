package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Test

/** 字符串值编辑保存时要重新加引号并转义，否则写回去就是非法 JSON */
class JsonSerializerQuoteTest {

    @Test
    fun wrapsPlainText() {
        assertEquals("\"abc\"", JsonSerializer.quote("abc"))
        assertEquals("\"\"", JsonSerializer.quote(""))
        // 中文不需要转义（JSON 允许直接放 UTF-8）
        assertEquals("\"中文\"", JsonSerializer.quote("中文"))
    }

    @Test
    fun escapesSpecialChars() {
        assertEquals("\"a\\\"b\"", JsonSerializer.quote("a\"b"))
        assertEquals("\"a\\\\b\"", JsonSerializer.quote("a\\b"))
        assertEquals("\"a\\nb\\tc\"", JsonSerializer.quote("a\nb\tc"))
        assertEquals("\"\\r\"", JsonSerializer.quote("\r"))
    }

    @Test
    fun escapesControlChars() {
        assertEquals("\"\\u0001\"", JsonSerializer.quote("\u0001"))
    }

    @Test
    fun quotedResultIsValidJsonString() {
        val samples = listOf("", "abc", "引号\"与\\反斜杠", "换行\n和制表\t", "\u0001控制符")
        for (s in samples) {
            val raw = JsonSerializer.quote(s)
            assertEquals(true, JsonValueValidator.isValid(raw))
        }
    }
}
