package com.guaner.jsonviewer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonHistoryTest {

    @Test
    fun `push adds newest first`() {
        val result = JsonHistory.push(listOf("a", "b"), "c", 5)
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun `push dedupes same content to front`() {
        val result = JsonHistory.push(listOf("a", "b", "c"), "b", 5)
        assertEquals(listOf("b", "a", "c"), result)
    }

    @Test
    fun `push evicts oldest beyond limit`() {
        val result = JsonHistory.push(listOf("a", "b", "c", "d"), "e", 3)
        assertEquals(listOf("e", "a", "b"), result)
    }

    @Test
    fun `push blank text is ignored`() {
        val result = JsonHistory.push(listOf("a"), "   ", 5)
        assertEquals(listOf("a"), result)
    }

    @Test
    fun `limit coerces to at least one`() {
        val result = JsonHistory.push(listOf("a", "b"), "c", 0)
        assertEquals(listOf("c"), result)
    }

    @Test
    fun `push into empty history`() {
        val result = JsonHistory.push(emptyList(), "x", 5)
        assertEquals(listOf("x"), result)
    }
}
