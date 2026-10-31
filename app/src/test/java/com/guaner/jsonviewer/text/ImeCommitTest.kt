package com.guaner.jsonviewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImeCommitTest {

    private fun longJson(): String =
        "{" + (1..2000).joinToString(",") { "\"k$it\":$it" } + "}"

    @Test
    fun `folding is skipped when auto fold is off`() {
        // 值编辑面板这类实例不自动折行：那里插入的换行会被当成值写回 JSON
        assertNull(ImeCommit.prepare(longJson(), foldEnabled = false))
    }

    @Test
    fun `short or multiline text is left alone`() {
        assertNull(ImeCommit.prepare("{\"a\":1}", foldEnabled = true))
        val multiline = "{\n  \"a\": 1,\n  \"b\": 2\n}"
        assertNull(ImeCommit.prepare(multiline, foldEnabled = true))
    }

    @Test
    fun `long single line is folded before it reaches the editor`() {
        val source = longJson()
        val folded = ImeCommit.prepare(source, foldEnabled = true)
            ?: error("超长单行应当被折行")
        // 只多出换行符，其它字符一个不差
        assertEquals(source.replace("\n", ""), folded.replace("\n", ""))
        assertEquals(
            "折后应当不再有超过阈值的行",
            true,
            ImeCommit.longestLineLength(folded) <= SafeFolder.DEFAULT_THRESHOLD * 2
        )
    }

    @Test
    fun `unfoldable super long string is left untouched`() {
        // 一段没有结构符的超长文本：不能为了折行改动它的内容
        val blob = "x".repeat(5000) + "\""      // 引号不平衡，作用域读不准
        assertNull(ImeCommit.prepare(blob, foldEnabled = true))
    }

    @Test
    fun `longest line length is measured including the last line`() {
        assertEquals(5, ImeCommit.longestLineLength("abcde"))
        assertEquals(5, ImeCommit.longestLineLength("abcde\nab"))
        assertEquals(4, ImeCommit.longestLineLength("ab\ncd\nabcd"))
        assertEquals(3, ImeCommit.longestLineLength("ab\ncd\nabc"))
        assertEquals(0, ImeCommit.longestLineLength(""))
        assertEquals(3, ImeCommit.longestLineLength("a\n\nabc\n"))
    }
}
