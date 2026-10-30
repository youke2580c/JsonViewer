package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 值编辑的类型切换与片段生成（ValueSheet 的纯逻辑部分） */
class ValueDraftTest {

    @Test
    fun optionsCoverAllSixTypes() {
        assertEquals(6, ValueDraft.options.size)
        assertEquals(
            listOf(
                NodeType.STRING, NodeType.NUMBER, NodeType.BOOLEAN,
                NodeType.NULL, NodeType.OBJECT, NodeType.ARRAY
            ),
            ValueDraft.options.map { it.first }
        )
        assertTrue(ValueDraft.options.all { it.second.isNotBlank() })
    }

    // ---------- 切换类型时编辑框内容 ----------

    @Test
    fun switchingToStringKeepsText() {
        assertEquals("18", ValueDraft.coerceText("18", NodeType.NUMBER, NodeType.STRING))
    }

    @Test
    fun switchingToNumberKeepsValidTextAndFillsEmpty() {
        assertEquals("18", ValueDraft.coerceText("18", NodeType.STRING, NodeType.NUMBER))
        assertEquals("0", ValueDraft.coerceText("", NodeType.STRING, NodeType.NUMBER))
        assertEquals("0", ValueDraft.coerceText("   ", NodeType.STRING, NodeType.NUMBER))
    }

    @Test
    fun switchingToBooleanNormalizes() {
        assertEquals("true", ValueDraft.coerceText("abc", NodeType.STRING, NodeType.BOOLEAN))
        assertEquals("false", ValueDraft.coerceText("false", NodeType.STRING, NodeType.BOOLEAN))
    }

    @Test
    fun switchingToNullLocksNull() {
        assertEquals("null", ValueDraft.coerceText("anything", NodeType.STRING, NodeType.NULL))
    }

    @Test
    fun leavingNullClearsPlaceholder() {
        // 从 null 切到字符串：不能把 "null" 变成字符串 "null"
        assertEquals("", ValueDraft.coerceText("null", NodeType.NULL, NodeType.STRING))
        // 切到数字时占位已清空，于是给 0；不会出现 "null" 这种非法数字
        assertEquals("0", ValueDraft.coerceText("null", NodeType.NULL, NodeType.NUMBER))
        // 切到对象/数组：给空容器占位
        assertEquals("{}", ValueDraft.coerceText("null", NodeType.NULL, NodeType.OBJECT))
        assertEquals("[]", ValueDraft.coerceText("null", NodeType.NULL, NodeType.ARRAY))
    }

    @Test
    fun switchingToContainerKeepsNonEmptyText() {
        // 已经是 JSON 文本时不覆盖用户内容
        assertEquals("{\"a\":1}", ValueDraft.coerceText("{\"a\":1}", NodeType.OBJECT, NodeType.OBJECT))
        assertEquals("[\n1\n]", ValueDraft.coerceText("[\n1\n]", NodeType.ARRAY, NodeType.ARRAY))
    }

    // ---------- 保存时写回的片段 ----------

    @Test
    fun stringRawIsQuotedAndEscaped() {
        assertEquals("\"a\\\"b\"", ValueDraft.toRawJson("a\"b", NodeType.STRING))
        assertEquals("\"\\u0001\"", ValueDraft.toRawJson("\u0001", NodeType.STRING))
    }

    @Test
    fun realNewlinesBecomeEscapes() {
        // 临时代码编辑器里直接敲回车：写回 JSON 时必须变成 \n，否则整份 JSON 语法就断了
        assertEquals("\"第一行\\n第二行\"", ValueDraft.toRawJson("第一行\n第二行", NodeType.STRING))
        assertEquals("\"a\\tb\"", ValueDraft.toRawJson("a\tb", NodeType.STRING))
    }

    @Test
    fun numberRawValidates() {
        assertEquals("12.5", ValueDraft.toRawJson(" 12.5 ", NodeType.NUMBER))
        assertEquals("-3e2", ValueDraft.toRawJson("-3e2", NodeType.NUMBER))
        assertNull(ValueDraft.toRawJson("12 34", NodeType.NUMBER))
        assertNull(ValueDraft.toRawJson("null", NodeType.NUMBER))
    }

    @Test
    fun booleanRawValidates() {
        assertEquals("true", ValueDraft.toRawJson(" true ", NodeType.BOOLEAN))
        assertNull(ValueDraft.toRawJson("yes", NodeType.BOOLEAN))
        assertNull(ValueDraft.toRawJson("1", NodeType.BOOLEAN))
    }

    @Test
    fun nullRawIsAlwaysNull() {
        // 选了 null 类型，编辑框里写什么都不影响写回结果
        assertEquals("null", ValueDraft.toRawJson("", NodeType.NULL))
        assertEquals("null", ValueDraft.toRawJson("abc", NodeType.NULL))
    }

    @Test
    fun containerRawRequiresValidJson() {
        assertEquals("{\"a\":1}", ValueDraft.toRawJson(" {\"a\":1} ", NodeType.OBJECT))
        assertEquals("[1,2]", ValueDraft.toRawJson("[1,2]", NodeType.ARRAY))
        // 半截 / 尾随内容都不接受
        assertNull(ValueDraft.toRawJson("{\"a\":1", NodeType.OBJECT))
        assertNull(ValueDraft.toRawJson("{} {}", NodeType.OBJECT))
        assertNull(ValueDraft.toRawJson("", NodeType.ARRAY))
    }

    @Test
    fun hintsMentionType() {
        assertTrue(ValueDraft.hint(NodeType.STRING).contains("字符串"))
        assertTrue(ValueDraft.hint(NodeType.OBJECT).contains("对象"))
        assertTrue(ValueDraft.invalidHint(NodeType.ARRAY).contains("数组"))
    }
}
