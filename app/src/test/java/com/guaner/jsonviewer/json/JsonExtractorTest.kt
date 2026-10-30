package com.guaner.jsonviewer.json

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 首个有效 JSON 提取：覆盖日志前缀、时间戳中括号、字符串内括号等坑 */
class JsonExtractorTest {

    @Test
    fun extractsJsonAfterTimestampBracket() {
        val raw = "[00:11.566] {\"code\":\"200\",\"data\":{\"list\":[{\"novelid\":\"1473506\"}]},\"message\":\"\"}"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("{\"code\":\"200\",\"data\":{\"list\":[{\"novelid\":\"1473506\"}]},\"message\":\"\"}", r?.text)
        assertEquals(12, r?.start)
    }

    @Test
    fun keepsPlainJsonUnchanged() {
        val raw = "{\"a\":1}"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals(raw, r?.text)
        assertEquals(0, r?.start)
        assertEquals(raw.length, r?.end)
    }

    @Test
    fun extractsArrayAfterPrefix() {
        val raw = "prefix text [1,2,3] trailing"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("[1,2,3]", r?.text)
    }

    @Test
    fun ignoresBracesInsideLeadingString() {
        // 噪声里的 { 位于字符串内部，但配对扫描从 { 开始仍会扫到闭合，只是校验失败
        val raw = "note \"a{b\" then {\"k\":1}"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("{\"k\":1}", r?.text)
    }

    @Test
    fun skipsUnbalancedLeadingBracket() {
        val raw = "[ERR {\"ok\":true}"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("{\"ok\":true}", r?.text)
    }

    @Test
    fun matchesNestedBracketsAndEscapedQuotes() {
        val raw = "log {\"s\":\"a\\\"}\",\"n\":[1,{\"x\":2}]} tail"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("{\"s\":\"a\\\"}\",\"n\":[1,{\"x\":2}]}", r?.text)
    }

    @Test
    fun returnsNullWhenNoJson() {
        assertNull(JsonExtractor.findFirstJson("no json here at all [00:11.566]"))
        assertNull(JsonExtractor.findFirstJson("   "))
    }

    @Test
    fun extractsOnlyFirstJsonWhenMultiplePresent() {
        val raw = "{\"a\":1}{\"b\":2}"
        val r = JsonExtractor.findFirstJson(raw)
        assertEquals("{\"a\":1}", r?.text)
    }

    @Test
    fun matchContainerHandlesUnclosed() {
        assertEquals(-1, JsonExtractor.matchContainer("{\"a\":1", 0))
        assertEquals(3, JsonExtractor.matchContainer("[1]", 0))
    }

    @Test
    fun isValidJsonRejectsTrailingGarbage() {
        assertTrue(JsonExtractor.isValidJson("{\"a\":1}"))
        assertTrue(!JsonExtractor.isValidJson("{\"a\":1}x"))
        assertTrue(!JsonExtractor.isValidJson("[00:11.566]"))
    }
}
