package com.guaner.jsonviewer.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineStartsTest {

    @Test
    fun buildSingleLine() {
        assertArrayEquals(intArrayOf(0), LineStarts.build("abc"))
    }

    @Test
    fun buildMultiLine() {
        assertArrayEquals(intArrayOf(0, 4, 7), LineStarts.build("abc\nde\nz"))
    }

    @Test
    fun buildTrailingNewline() {
        // 末尾换行后还有一个空行，起始偏移等于文本长度
        assertArrayEquals(intArrayOf(0, 4, 5), LineStarts.build("abc\n\n"))
    }

    @Test
    fun buildEmpty() {
        assertArrayEquals(intArrayOf(0), LineStarts.build(""))
    }

    @Test
    fun shiftInsertInsideLine() {
        // "abc\nde" -> 在第 1 个字符后插入 "XY"："aXYbc\nde"
        val before = LineStarts.build("abc\nde")
        val after = LineStarts.shift(before, 1, 0, 2, 8)
        assertArrayEquals(intArrayOf(0, 6), after)
    }

    @Test
    fun shiftInsertAtLineStart() {
        // "ab\ncd" -> 在第 3 个字符（第二行行首）插入 "X"
        // 新字符属于第二行，第二行起点仍是 3；后面没有更多行了
        val before = LineStarts.build("ab\ncd")
        val after = LineStarts.shift(before, 3, 0, 1, 6)
        assertArrayEquals(intArrayOf(0, 3), after)
    }

    @Test
    fun shiftInsertAtLineStartWithTail() {
        // "a\nb\nc"：在第 2 个字符（第二行行首）插入 "XX"
        // 第二行起点保持 2，第三行起点 4 -> 6
        val before = LineStarts.build("a\nb\nc")
        val after = LineStarts.shift(before, 2, 0, 2, 7)
        assertArrayEquals(intArrayOf(0, 2, 6), after)
    }

    @Test
    fun shiftDeleteInsideLine() {
        // "abcde\nf" 删掉 b,c -> "ade\nf"
        val before = LineStarts.build("abcde\nf")
        val after = LineStarts.shift(before, 1, 2, 0, 5)
        assertArrayEquals(intArrayOf(0, 4), after)
    }

    @Test
    fun shiftDeleteAtEnd() {
        // "abc\ndef" 删掉最后两个字符：变化点之后没有行首，整表不变
        val before = LineStarts.build("abc\ndef")
        val after = LineStarts.shift(before, 5, 2, 0, 5)
        assertArrayEquals(intArrayOf(0, 4), after)
    }

    @Test
    fun shiftNoLengthChangeReturnsSameArray() {
        // 等长替换（不含换行）：行结构完全不变
        val before = LineStarts.build("abc\ndef")
        val after = LineStarts.shift(before, 1, 1, 1, 7)
        assertTrue(before === after)
    }

    @Test
    fun shiftRejectsOutOfRange() {
        val before = LineStarts.build("abc\ndef")
        assertNull(LineStarts.shift(before, 99, 0, 1, 8))
    }

    @Test
    fun shiftRejectsResultOverflow() {
        // 声明的最终长度比平移结果还小，说明入参不可信：退回全量重建
        val before = LineStarts.build("a\nb\nc")
        assertNull(LineStarts.shift(before, 1, 0, 10, 5))
    }

    @Test
    fun maxLineLength() {
        assertEquals(0, LineStarts.maxLineLength(""))
        assertEquals(5, LineStarts.maxLineLength("abcde"))
        assertEquals(4, LineStarts.maxLineLength("ab\ncdef\ngh"))
        assertEquals(3, LineStarts.maxLineLength("abc\n"))
    }

    @Test
    fun hasNewline() {
        val text = "abc\ndef"
        assertTrue(LineStarts.hasNewline(text, 2, 2))
        assertFalse(LineStarts.hasNewline(text, 0, 3))
        assertFalse(LineStarts.hasNewline(text, 0, 0))
        // 越界片段按实际长度截断，不抛异常
        assertFalse(LineStarts.hasNewline("abc", 1, 999))
    }

    @Test
    fun incrementalMatchesFullBuild() {
        // 模拟逐字输入：全程走增量，结果必须与全量重建一致
        val sb = StringBuilder()
        var starts = LineStarts.build(sb)
        val pieces = listOf("{\n", "  \"a\": ", "1,\n", "  \"b\": \"x", "y\"\n", "}")
        for (p in pieces) {
            val at = sb.length
            sb.append(p)
            if (!LineStarts.hasNewline(p, 0, p.length) && p.length <= 2048) {
                starts = LineStarts.shift(starts, at, 0, p.length, sb.length) ?: LineStarts.build(sb)
            } else {
                starts = LineStarts.build(sb)
            }
        }
        assertArrayEquals(LineStarts.build(sb.toString()), starts)

        // 回落删掉最后两行里的字符，仍走增量
        val cutAt = sb.length - 2
        sb.setLength(cutAt)
        val shifted = LineStarts.shift(starts, cutAt, 2, 0, sb.length)
        if (shifted != null) assertArrayEquals(LineStarts.build(sb.toString()), shifted)
    }
}
