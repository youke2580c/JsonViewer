package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonParserTest {

    @Test
    fun `valid nested json parses into tree`() {
        val result = JsonParser.parse("""{"a":{"b":[1,"x",true,null]}}""")
        assertTrue(result is ParseResult.Success)
        val root = (result as ParseResult.Success).root
        assertEquals("$", root.path)
        assertEquals(NodeType.OBJECT, root.type)
        val a = root.children[0]
        assertEquals("$.a", a.path)
        val b = a.children[0]
        assertEquals("$.a.b", b.path)
        assertEquals(4, b.children.size)
        assertEquals("$.a.b[0]", b.children[0].path)
        assertEquals(NodeType.NUMBER, b.children[0].type)
        assertEquals("1", b.children[0].scalar)
        assertEquals("$.a.b[1]", b.children[1].path)
        assertEquals(NodeType.STRING, b.children[1].type)
        assertEquals("x", b.children[1].scalar)
        assertEquals(NodeType.BOOLEAN, b.children[2].type)
        assertEquals(NodeType.NULL, b.children[3].type)
    }

    @Test
    fun `root scalar is supported`() {
        val result = JsonParser.parse("42")
        assertTrue(result is ParseResult.Success)
        val root = (result as ParseResult.Success).root
        assertEquals("$", root.path)
        assertEquals("42", root.scalar)
    }

    @Test
    fun `empty and blank text yields Empty`() {
        assertEquals(ParseResult.Empty, JsonParser.parse(""))
        assertEquals(ParseResult.Empty, JsonParser.parse("   \n\t "))
    }

    @Test
    fun `bom prefix is tolerated`() {
        val result = JsonParser.parse("\uFEFF{\"a\":1}")
        assertTrue(result is ParseResult.Success)
    }

    @Test
    fun `trailing comma in object reports the brace position`() {
        // {"a":1,}  -> Jackson 报错偏移在字符串末尾之后，靠整行形状识别为尾随逗号
        val result = JsonParser.parse("{\"a\":1,}")
        assertTrue(result is ParseResult.Failure)
        val err = (result as ParseResult.Failure).error
        assertEquals(1, err.line)
        assertEquals(9, err.column)
        assertEquals("多余的逗号", err.title)
    }

    @Test
    fun `trailing comma in array reports the bracket position`() {
        val result = JsonParser.parse("[1,2,]")
        val err = (result as ParseResult.Failure).error
        assertEquals("多余的逗号", err.title)
        assertEquals(1, err.line)
        assertEquals(7, err.column)
    }

    @Test
    fun `missing comma between members`() {
        // {"a":1 "b":2}
        val result = JsonParser.parse("{\"a\":1 \"b\":2}")
        val err = (result as ParseResult.Failure).error
        assertEquals("缺少逗号", err.title)
        assertEquals(1, err.line)
        assertEquals(9, err.column)
        assertNotNull(err.lineText)
    }

    @Test
    fun `unclosed string`() {
        val result = JsonParser.parse("{\"a\":\"abc}")
        val err = (result as ParseResult.Failure).error
        assertEquals("未闭合", err.title)
        assertTrue(err.detail.contains("引号"))
    }

    @Test
    fun `unclosed brace`() {
        val result = JsonParser.parse("{\n  \"a\": 1")
        val err = (result as ParseResult.Failure).error
        assertEquals("未闭合", err.title)
        assertEquals(2, err.line)
    }

    @Test
    fun `full-width comma gets chinese hint`() {
        val result = JsonParser.parse("{\"a\":1，\"b\":2}")
        val err = (result as ParseResult.Failure).error
        assertEquals("中文逗号", err.title)
        assertTrue(err.detail.contains("英文逗号"))
    }

    @Test
    fun `single quotes get chinese hint`() {
        val result = JsonParser.parse("{'a':1}")
        val err = (result as ParseResult.Failure).error
        assertEquals("单引号", err.title)
        assertTrue(err.detail.contains("双引号"))
    }

    @Test
    fun `trailing content after root`() {
        val result = JsonParser.parse("{\"a\":1} garbage")
        val err = (result as ParseResult.Failure).error
        assertEquals("无法识别的内容", err.title)
        assertTrue(err.lineText!!.contains("garbage"))
    }

    @Test
    fun `lineCol computation`() {
        val text = "abc\ndef\nghi"
        assertEquals(1 to 1, JsonParser.lineCol(text, 0))
        assertEquals(1 to 4, JsonParser.lineCol(text, 3))
        assertEquals(2 to 1, JsonParser.lineCol(text, 4))
        assertEquals(3 to 2, JsonParser.lineCol(text, 9))
    }

    @Test
    fun `lineText extraction`() {
        val text = "line1\nline2\nline3"
        assertEquals("line2", JsonParser.lineText(text, 2))
        assertEquals("line3", JsonParser.lineText(text, 3))
        assertEquals(null, JsonParser.lineText(text, 9))
    }

    @Test
    fun `unicode escapes in strings are decoded in tree`() {
        val result = JsonParser.parse("{\"a\":\"\\u4e2d\\u6587\"}")
        val root = (result as ParseResult.Success).root
        assertEquals("中文", root.children[0].scalar)
    }

    /** 正常深度的嵌套必须还能解析（限额不能误伤真实数据） */
    @Test
    fun `deep but allowed nesting parses`() {
        val depth = 100
        val text = "[".repeat(depth) + "1" + "]".repeat(depth)
        assertTrue(JsonParser.parse(text) is ParseResult.Success)
    }

    /**
     * 超过 MAX_DEPTH 的嵌套必须被拒绝成友好错误。
     *
     * 建树是迭代的，几万层也能建出来；但之后建索引/序列化/渲染都是递归，
     * 一旦放任就会 StackOverflowError（属于 Error，catch Exception 兜不住）直接闪退。
     */
    @Test
    fun `too deep nesting is rejected as friendly error`() {
        val depth = JsonLimits.MAX_DEPTH + 10
        val text = "[".repeat(depth) + "1" + "]".repeat(depth)
        val result = JsonParser.parse(text)
        assertTrue(result is ParseResult.Failure)
        assertEquals("嵌套过深", (result as ParseResult.Failure).error.title)
    }
}
