package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonTreeNodeTest {

    @Test
    fun `childPath uses dot notation for simple keys`() {
        assertEquals("$.a", TreeNode.childPath("$", "a"))
        assertEquals("$.a.b_1", TreeNode.childPath("$.a", "b_1"))
        assertEquals("$.a.${'$'}x", TreeNode.childPath("$.a", "${'$'}x"))
    }

    @Test
    fun `childPath uses bracket notation for special keys`() {
        assertEquals("$['a b']", TreeNode.childPath("$", "a b"))
        assertEquals("$['a.b']", TreeNode.childPath("$", "a.b"))
        assertEquals("$['a-b']", TreeNode.childPath("$", "a-b"))
        assertEquals("$['it\\'s']", TreeNode.childPath("$", "it's"))
        assertEquals("$['中文']", TreeNode.childPath("$", "中文"))
    }

    @Test
    fun `indexPath appends index`() {
        assertEquals("$[0]", TreeNode.indexPath("$", 0))
        assertEquals("$.a[12]", TreeNode.indexPath("$.a", 12))
    }

    @Test
    fun `normalizePath converts bracket keys to dot keys`() {
        assertEquals("$.store.book[0].title", TreeNode.normalizePath("\$['store']['book'][0]['title']"))
        assertEquals("$.a['b c'].d", TreeNode.normalizePath("\$['a']['b c']['d']"))
    }

    @Test
    fun `segmentsOf splits mixed notation keeping prefixes`() {
        assertEquals(
            listOf(".a", "['b c']", ".d", "[0]"),
            TreeNode.segmentsOf("\$.a['b c'].d[0]")
        )
    }

    @Test
    fun `breadcrumbOf produces display segments`() {
        assertEquals(
            listOf("$", "store", "book[2]", "title"),
            TreeNode.breadcrumbOf("\$.store.book[2].title")
        )
        assertEquals(
            listOf("$", "a b"),
            TreeNode.breadcrumbOf("\$['a b']")
        )
    }

    @Test
    fun `ancestorsOf returns strict ancestors without self or root`() {
        assertEquals(
            listOf("\$.store", "\$.store.book", "\$.store.book[0]"),
            TreeNode.ancestorsOf("\$.store.book[0].title")
        )
        assertEquals(emptyList<String>(), TreeNode.ancestorsOf("\$"))
        assertEquals(listOf("\$.a"), TreeNode.ancestorsOf("\$.a[0]"))
    }
}
