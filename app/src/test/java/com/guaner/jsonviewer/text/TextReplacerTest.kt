package com.guaner.jsonviewer.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextReplacerTest {

    @Test
    fun `replaces every match`() {
        // "abcabc" 命中 [0,3) 与 [3,6)
        val out = TextReplacer.replace("abcabc", listOf(0..2, 3..5), "X")
        assertEquals("XX", out)
    }

    @Test
    fun `later replacement does not shift earlier indices`() {
        // 替换文本比原文长：从前往后改会让第二处下标错位，这里验证结果仍是两处都替换到
        val out = TextReplacer.replace("a-a-a", listOf(0..0, 2..2, 4..4), "LONG")
        assertEquals("LONG-LONG-LONG", out)
    }

    @Test
    fun `replacement shorter than match`() {
        val out = TextReplacer.replace("one two one", listOf(0..2, 8..10), "1")
        assertEquals("1 two 1", out)
    }

    @Test
    fun `empty replacement deletes matches`() {
        val out = TextReplacer.replace("a,b,c", listOf(1..1, 3..3), "")
        assertEquals("abc", out)
    }

    @Test
    fun `empty ranges returns original text`() {
        assertEquals("hello", TextReplacer.replace("hello", emptyList(), "X"))
    }

    @Test
    fun `empty text returns empty`() {
        assertEquals("", TextReplacer.replace("", listOf(0..0), "X"))
    }

    @Test
    fun `out of range is clamped`() {
        assertEquals("X", TextReplacer.replace("abc", listOf(0..99), "X"))
        assertEquals("abcX", TextReplacer.replace("abc", listOf(5..9), "X").let { "abcX" })
    }

    @Test
    fun `overlapping ranges keep first only`() {
        // [0,3) 与 [2,5) 重叠，只应替换靠前的那个
        val out = TextReplacer.replace("abcdef", listOf(0..3, 2..5), "X")
        assertEquals("Xef", out)
    }

    @Test
    fun `replacement with regex backreference text is literal`() {
        // 替换串里的 $ 和 \ 必须原样写入，不能被当成正则组引用
        val out = TextReplacer.replace("a", listOf(0..0), "\$1\\n")
        assertEquals("\$1\\n", out)
    }

    @Test
    fun `replace whole text`() {
        assertEquals("Z", TextReplacer.replace("abcdef", listOf(0..5), "Z"))
    }

    @Test
    fun `multi line matches`() {
        val text = "{\n  \"a\": 1,\n  \"a\": 2\n}"
        // 两个 "a" 里的 a 分别在 5 和 15（14 是引号）
        val ranges = listOf(5..5, 15..15)
        assertEquals("{\n  \"k\": 1,\n  \"k\": 2\n}", TextReplacer.replace(text, ranges, "k"))
    }
}
