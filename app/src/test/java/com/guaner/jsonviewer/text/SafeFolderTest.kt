package com.guaner.jsonviewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超长行安全折行。
 *
 * 背景：Sora 的 `WordwrapLayout.breakLine` 每找一个断点都要从**剩余整行**量一次宽度，
 * 单行断行是 O(n²)。一行 50 万字符的压缩 JSON 交给它，打开/打字都能卡死。
 * 折行的作用是把这种行压到阈值内，换行仍然 100% 由官方 `WordwrapLayout` 执行。
 *
 * 因此这里最要紧的两条底线：
 * 1. **不改变内容语义** —— 只在引号外的结构符后插换行，字符串内部一个字符都不动；
 * 2. **不能无限折** —— 找不到可折点的超长字符串宁可保留原样。
 */
class SafeFolderTest {

    /** 折行只插换行，所以去掉所有换行后应与原文完全一致 */
    private fun assertSameIgnoringLineBreaks(original: String, folded: String) {
        assertEquals(original.filter { it != '\n' }, folded.filter { it != '\n' })
    }

    @Test
    fun `short text is untouched`() {
        val text = """{"a": 1, "b": [1, 2, 3]}"""
        assertNull(SafeFolder.fold(text, threshold = 200))
    }

    @Test
    fun `compressed json is split on structural chars`() {
        val text = "{" + (1..500).joinToString(",") { "\"k$it\":$it" } + "}"
        assertTrue("前置条件：这是一行超长文本", text.length > 500)

        val folded = SafeFolder.fold(text, threshold = 200)
        assertNotNull(folded)
        assertSameIgnoringLineBreaks(text, folded!!)

        val lines = folded.split("\n")
        assertTrue("折完必须变成多行", lines.size > 5)
        // 每行大约在阈值附近断开，允许最后一个「键值对」越界一点
        assertTrue(
            "最长行 ${lines.maxOf { it.length }} 应接近阈值",
            lines.maxOf { it.length } <= 200 + 40
        )
    }

    @Test
    fun `commas inside strings never break the value`() {
        // 超长字符串值里有大量逗号和冒号：这些都不能折，否则值就被改了
        val payload = (1..400).joinToString(",") { "a$it:b$it" }
        val text = """{"blob":"$payload","ok":true}"""

        val folded = SafeFolder.fold(text, threshold = 200)
        assertNotNull(folded)
        assertSameIgnoringLineBreaks(text, folded!!)
        // 字符串值本身必须原封不动地出现在某一处
        assertTrue(folded.replace("\n", "").contains("\"$payload\""))
    }

    @Test
    fun `escaped quote does not end string state`() {
        // \" 只是转义引号，不能让扫描器误以为字符串结束、把后面的逗号当结构符
        val text = "{\"k\":\"" + "a\\\"b,".repeat(100) + "\",\"n\":1}"
        val folded = SafeFolder.fold(text, threshold = 200)
        if (folded != null) assertSameIgnoringLineBreaks(text, folded)
    }

    @Test
    fun `unbreakable long string is left alone`() {
        // 一整行只有一个超长字符串、没有任何结构符可折：宁可保留长行，也不能破坏内容
        val text = "\"" + "x".repeat(5_000) + "\""
        assertNull(SafeFolder.fold(text, threshold = 200))
    }

    @Test
    fun `only long lines are folded`() {
        val short = """{"a": 1}"""
        val long = "{" + (1..300).joinToString(",") { "\"k$it\":$it" } + "}"
        val text = "$short\n$long\n$short"
        val folded = SafeFolder.fold(text, threshold = 200)!!
        val lines = folded.split("\n")
        assertEquals(short, lines.first())
        assertEquals(short, lines.last())
        assertSameIgnoringLineBreaks(text, folded)
    }

    @Test
    fun `already formatted text is untouched`() {
        val text = buildString {
            append("{\n")
            repeat(200) { append("  \"k$it\": $it,\n") }
            append("}")
        }
        assertNull(SafeFolder.fold(text, threshold = 200))
    }

    @Test
    fun `crlf and empty text do not crash`() {
        assertNull(SafeFolder.fold("", threshold = 10))
        assertNull(SafeFolder.fold("\n\n\n", threshold = 10))
        val text = "{" + (1..100).joinToString(",") { "\"k$it\":$it" } + "}\r\n"
        val folded = SafeFolder.fold(text, threshold = 100)
        assertNotNull(folded)
        assertSameIgnoringLineBreaks(text, folded!!)
    }
}
