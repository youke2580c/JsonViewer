package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonPathEngineTest {

    private val json = """
        {
          "store": {
            "book": [
              { "category": "reference", "author": "Nigel Rees", "title": "Sayings of the Century", "price": 8.95 },
              { "category": "fiction", "author": "Evelyn Waugh", "title": "Sword of Honour", "price": 12.99 },
              { "category": "fiction", "author": "Herman Melville", "title": "Moby Dick", "isbn": "0-553-21311-3", "price": 8.99 },
              { "category": "fiction", "author": "J. R. R. Tolkien", "title": "The Lord of the Rings", "isbn": "0-395-19395-8", "price": 22.99 }
            ],
            "bicycle": { "color": "red", "price": 19.95 }
          },
          "expensive": 10
        }
    """.trimIndent()

    @Test
    fun `definite path returns single hit with normalized path`() {
        val result = JsonPathEngine.query(json, "$.store.book[0].title")
        assertTrue(result is QueryResult.Ok)
        val hits = (result as QueryResult.Ok).hits
        assertEquals(1, hits.size)
        assertEquals("$.store.book[0].title", hits[0].normalized)
        assertEquals("字符串", hits[0].kind)
        assertEquals("\"Sayings of the Century\"", hits[0].full)
    }

    @Test
    fun `recursive descent finds all prices`() {
        val result = JsonPathEngine.query(json, "$..price")
        val hits = (result as QueryResult.Ok).hits
        assertEquals(5, hits.size)
        assertTrue(hits.all { it.kind == "数字" })
        assertTrue(hits.any { it.normalized == "$.store.bicycle.price" })
    }

    @Test
    fun `wildcard finds all authors`() {
        val result = JsonPathEngine.query(json, "$.store.book[*].author")
        val hits = (result as QueryResult.Ok).hits
        assertEquals(4, hits.size)
        assertEquals("$.store.book[0].author", hits[0].normalized)
        assertEquals("\"Nigel Rees\"", hits[0].full)
    }

    @Test
    fun `filter expression works`() {
        val result = JsonPathEngine.query(json, "$.store.book[?(@.price < 10)]")
        val hits = (result as QueryResult.Ok).hits
        assertEquals(2, hits.size)
        assertEquals("对象", hits[0].kind)
        assertTrue(hits[0].normalized.startsWith("$.store.book["))
    }

    @Test
    fun `slice works`() {
        val result = JsonPathEngine.query(json, "$.store.book[0:2]")
        val hits = (result as QueryResult.Ok).hits
        assertEquals(2, hits.size)
        assertEquals("$.store.book[0]", hits[0].normalized)
        assertEquals("$.store.book[1]", hits[1].normalized)
    }

    @Test
    fun `definite path whose value is an array is one hit`() {
        val result = JsonPathEngine.query(json, "$.store.book")
        val hits = (result as QueryResult.Ok).hits
        assertEquals(1, hits.size)
        assertEquals("数组", hits[0].kind)
        assertTrue(hits[0].full.startsWith("["))
    }

    @Test
    fun `no match returns empty list`() {
        val result = JsonPathEngine.query(json, "$.store.notexist")
        val hits = (result as QueryResult.Ok).hits
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `invalid expression returns error`() {
        val result = JsonPathEngine.query(json, "$..")
        assertTrue(result is QueryResult.Err)
    }

    @Test
    fun `unterminated bracket filter returns error`() {
        val result = JsonPathEngine.query(json, "$.store.book[?(@.price")
        assertTrue(result is QueryResult.Err)
    }

    @Test
    fun `empty expression returns error`() {
        val result = JsonPathEngine.query(json, "   ")
        assertTrue(result is QueryResult.Err)
    }

    @Test
    fun `invalid json returns error mentioning line`() {
        val result = JsonPathEngine.query("{\"a\":,}", "$.a")
        assertTrue(result is QueryResult.Err)
        assertTrue((result as QueryResult.Err).message.contains("行"))
    }
}
