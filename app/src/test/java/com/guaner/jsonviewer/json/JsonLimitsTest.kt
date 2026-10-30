package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 大文件防护：节点上限、零内存预检、逐行扫描 */
class JsonLimitsTest {

    @Test
    fun `validateOnly accepts valid json without building tree`() {
        assertNull(JsonParser.validateOnly("""{"a":[1,2,{"b":null}],"c":true}"""))
        assertNull(JsonParser.validateOnly("  "))
        assertNull(JsonParser.validateOnly(""))
        assertNull(JsonParser.validateOnly("\uFEFF123"))
    }

    @Test
    fun `validateOnly rejects invalid json with line and column`() {
        val err = JsonParser.validateOnly("""{"a":1 "b":2}""")
        assertNotNull(err)
        assertEquals("缺少逗号", err!!.title)
        assertEquals(1, err.line)
    }

    @Test
    fun `validateOnly catches unterminated json`() {
        val err = JsonParser.validateOnly("""{"a":[1,2""")
        assertNotNull(err)
        assertEquals("未闭合", err!!.title)
    }

    @Test
    fun `node count over limit returns friendly failure instead of oom`() {
        // [1,1,1,...] 每个叶子一个节点，25 万零 1 个节点即触发上限
        val n = JsonLimits.MAX_NODES + 10
        val json = StringBuilder("[").append("1,".repeat(n - 1)).append("1]").toString()
        val result = JsonParser.parse(json)
        assertTrue(result is ParseResult.Failure)
        val err = (result as ParseResult.Failure).error
        assertEquals("节点过多", err.title)
        assertTrue(err.detail.contains("25"))
    }

    @Test
    fun `node count within limit parses fine`() {
        val n = 100_000
        val json = StringBuilder("[").append("1,".repeat(n - 1)).append("1]").toString()
        val result = JsonParser.parse(json)
        assertTrue(result is ParseResult.Success)
        assertEquals(n, (result as ParseResult.Success).root.children.size)
    }

    @Test
    fun `lineText scans lines without split`() {
        val text = "line1\nline2\nline3"
        assertEquals("line1", JsonParser.lineText(text, 1))
        assertEquals("line2", JsonParser.lineText(text, 2))
        assertEquals("line3", JsonParser.lineText(text, 3))
        assertNull(JsonParser.lineText(text, 4))
        assertNull(JsonParser.lineText(text, 0))
    }

    @Test
    fun `lineText truncates long lines`() {
        val long = "x".repeat(500)
        val result = JsonParser.lineText("a\n$long\nb", 2)
        assertEquals(120, result!!.length)
        assertTrue(result.endsWith("..."))
    }

    @Test
    fun `lineCol handles newlines and unicode`() {
        val text = "a\n中文\nb"
        assertEquals(1 to 1, JsonParser.lineCol(text, 0))
        assertEquals(2 to 1, JsonParser.lineCol(text, 2))
        assertEquals(2 to 3, JsonParser.lineCol(text, 4)) // 中文第二字
        assertEquals(3 to 1, JsonParser.lineCol(text, 5))
        // 超出末尾按末尾计算
        assertEquals(3 to 2, JsonParser.lineCol(text, 7))
    }

    @Test
    fun `limits format helpers`() {
        assertEquals("2.0M 字符", JsonLimits.formatChars(2_000_000))
        assertEquals("500K 字符", JsonLimits.formatChars(500_000))
        assertEquals("999 字符", JsonLimits.formatChars(999))
        assertEquals("16.0MB", JsonLimits.formatBytes(16L * 1024 * 1024))
        assertEquals("512KB", JsonLimits.formatBytes(512L * 1024))
    }
}
