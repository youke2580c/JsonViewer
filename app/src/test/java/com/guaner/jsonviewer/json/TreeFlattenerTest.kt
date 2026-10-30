package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TreeFlattenerTest {

    private val json = """
        {
          "a": { "b": [ { "name": "x" }, { "name": "y" } ] },
          "c": 1
        }
    """.trimIndent()

    private fun root(): TreeNode =
        (JsonParser.parse(json) as ParseResult.Success).root

    @Test
    fun `flatten respects expansion callback`() {
        val r = root()
        // 全部折叠：只有根
        val collapsed = TreeFlattener.flatten(r) { false }
        assertEquals(listOf("$"), collapsed.visible.map { it.path })
        // 根展开：根 + a + c
        val oneLevel = TreeFlattener.flatten(r) { it.depth == 0 }
        assertEquals(listOf("$", "$.a", "$.c"), oneLevel.visible.map { it.path })
        // 全部展开
        val all = TreeFlattener.flatten(r) { true }
        assertEquals(8, all.visible.size)
        assertEquals("$.a.b[0].name", all.visible[4].path)
    }

    @Test
    fun `flatten builds path index`() {
        val flat = TreeFlattener.flatten(root()) { true }
        assertEquals("y", flat.index["$.a.b[1].name"]?.scalar)
        assertEquals(NodeType.NUMBER, flat.index["$.c"]?.type)
    }

    @Test
    fun `collectMatches finds keys and values`() {
        val result = TreeFlattener.collectMatches(root(), "name")
        assertEquals(2, result.matchedPaths.size)
        assertTrue(result.matchedPaths.contains("$.a.b[0].name"))
        assertTrue(result.matchedPaths.contains("$.a.b[1].name"))

        val valueSearch = TreeFlattener.collectMatches(root(), "x")
        assertEquals(listOf("$.a.b[0].name"), valueSearch.matchedPaths)
    }

    @Test
    fun `collectMatches marks ancestors of matches for auto expand`() {
        val result = TreeFlattener.collectMatches(root(), "name")
        assertTrue(result.pathsWithMatchBelow.contains("$"))
        assertTrue(result.pathsWithMatchBelow.contains("$.a"))
        assertTrue(result.pathsWithMatchBelow.contains("$.a.b"))
        assertTrue(result.pathsWithMatchBelow.contains("$.a.b[0]"))
        assertFalse(result.pathsWithMatchBelow.contains("$.a.b[0].name"))
        assertFalse(result.pathsWithMatchBelow.contains("$.c"))
    }

    @Test
    fun `collectMatches is case insensitive`() {
        val result = TreeFlattener.collectMatches(root(), "NAME")
        assertEquals(2, result.matchedPaths.size)
    }

    @Test
    fun `collectMatches with no query match returns empty`() {
        val result = TreeFlattener.collectMatches(root(), "zzz")
        assertTrue(result.matchedPaths.isEmpty())
        assertTrue(result.pathsWithMatchBelow.isEmpty())
    }
}
