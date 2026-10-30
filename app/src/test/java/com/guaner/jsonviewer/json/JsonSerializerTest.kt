package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonSerializerTest {

    private val sample = """{"store":{"book":[{"a":1}],"n":null,"s":"x\"y"},"ok":true}"""

    private fun root(): TreeNode {
        val result = JsonParser.parse(sample)
        return (result as ParseResult.Success).root
    }

    @Test
    fun `pretty produces indented multiline output`() {
        val pretty = JsonSerializer.pretty(root())
        assertTrue(pretty.startsWith("{\n  \"store\""))
        assertTrue(pretty.contains("\n    \"book\": ["))
        assertTrue(pretty.contains("\n      {\n        \"a\": 1\n      }"))
        assertTrue(pretty.contains("\"s\": \"x\\\"y\""))
        assertTrue(pretty.endsWith("}"))
    }

    @Test
    fun `minify produces single line and reparses identically`() {
        val minified = JsonSerializer.minify(root())
        assertTrue(minified.none { it == '\n' })
        val re = JsonParser.parse(minified)
        assertTrue(re is ParseResult.Success)
        assertEquals(
            JsonSerializer.minify(root()),
            JsonSerializer.minify((re as ParseResult.Success).root)
        )
    }

    @Test
    fun `escapeUnicode escapes non-ascii`() {
        val result = JsonParser.parse("{\"k\":\"中文\"}")
        val escaped = JsonSerializer.escapeUnicode((result as ParseResult.Success).root)
        assertTrue(escaped.contains("\\u4e2d\\u6587"))
        assertTrue(escaped.contains("\"k\""))
    }

    @Test
    fun `compactOf serializes subtree`() {
        val r = root()
        val store = r.children[0]
        assertEquals(
            "{\"book\":[{\"a\":1}],\"n\":null,\"s\":\"x\\\"y\"}",
            JsonSerializer.compactOf(store)
        )
        val bookArray = store.children[0]
        assertEquals("[{\"a\":1}]", JsonSerializer.compactOf(bookArray))
        val leaf = store.children[2]
        assertEquals("\"x\\\"y\"", JsonSerializer.compactOf(leaf))
    }

    @Test
    fun `empty containers render as empty braces`() {
        val result = JsonParser.parse("{\"o\":{},\"a\":[]}")
        val pretty = JsonSerializer.pretty((result as ParseResult.Success).root)
        assertTrue(pretty.contains("\"o\": {}"))
        assertTrue(pretty.contains("\"a\": []"))
    }
}
