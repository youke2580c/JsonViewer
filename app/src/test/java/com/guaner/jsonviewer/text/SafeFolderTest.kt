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

    // ---------- 光标位置映射 ----------
    //
    // 折行只插入换行符，所以「光标在 anchon 处的字符」折行后应该还在同一个字符上；
    // 少了这一步，替换完整篇后光标会停在原字符左边，看起来就是「光标错位」。

    @Test
    fun `anchor maps to the same character after folding`() {
        val text = "{" + (1..500).joinToString(",") { "\"k$it\":$it" } + "}"
        val anchors = listOf(0, 1, 10, 200, 1_000, text.length / 2, text.length - 1)

        val folded = SafeFolder.fold(text, threshold = 200)!!
        for (anchor in anchors) {
            val mapped = SafeFolder.foldWithAnchor(text, threshold = 200, anchor = anchor)!!.anchor
            val before = text[anchor]
            val after = folded[mapped]
            assertEquals("anchor=$anchor 应指向同一个字符", before, after)
        }
    }

    @Test
    fun `anchor before first break stays put`() {
        // 折点在第 20 个逗号之后，anchor 远在它之前：一个换行都没插进来，映射后位置不变
        val text = "{" + (1..50).joinToString(",") { "\"k$it\":$it" } + "}"
        val result = SafeFolder.foldWithAnchor(text, threshold = 20, anchor = 3)!!
        assertEquals(3, result.anchor)
    }

    @Test
    fun `anchor shifts by number of inserted newlines before it`() {
        val text = "{" + (1..500).joinToString(",") { "\"k$it\":$it" } + "}"
        val anchor = text.length / 2
        val result = SafeFolder.foldWithAnchor(text, threshold = 200, anchor = anchor)!!
        val insertedBefore = result.text.substring(0, result.anchor).count { it == '\n' }
        assertEquals(anchor + insertedBefore, result.anchor)
        assertEquals(text[anchor], result.text[result.anchor])
    }

    @Test
    fun `unfolded text returns null even with an anchor`() {
        val text = """{"a": 1}"""
        assertNull(SafeFolder.foldWithAnchor(text, threshold = 200, anchor = 2))
    }

    // ---------- 作用域拿不准时一律不折 ----------
    //
    // 折行的前提是「知道哪些符号在字符串里」。用户粘贴进来的未必是合法 JSON
    // （很多时候正是先粘一段坏东西、再一点点改对），引号一旦不平衡，
    // 状态机就会把字符串里的逗号当成结构符，在字符串内部插入真实换行 ——
    // JSON 字符串不允许裸换行，内容当场报废。这种情况必须整段原样放行。

    @Test
    fun `unbalanced quote never breaks inside the string`() {
        // 少一个收尾引号：扫描到行尾仍处在字符串里，整段都不能动
        val text = "{\"k\":\"" + (1..400).joinToString(",") { "v$it" }
        assertNull(SafeFolder.fold(text, threshold = 200))
    }

    @Test
    fun `bad line does not stop other lines from folding`() {
        // 一行坏掉只影响它自己：别的合法长行仍要照常折短，否则整个文档都救不回来
        val good = "{" + (1..300).joinToString(",") { "\"k$it\":$it" } + "}"
        val bad = "{\"k\":\"" + (1..400).joinToString(",") { "v$it" }
        val folded = SafeFolder.fold("$good\n$bad", threshold = 200)!!
        assertSameIgnoringLineBreaks("$good\n$bad", folded)
        assertTrue("合法那一半必须被折短", folded.split("\n").size > 2)
        // 坏的那一段必须原封不动
        assertTrue(folded.replace("\n", "").contains(bad))
    }
}
