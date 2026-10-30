package com.guaner.jsonviewer.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「JSON 填入」的解析规则：多种写法都要能落到「名称 → 值」 */
class HeaderJsonTest {

    private fun headers(raw: String): List<HttpHeader> {
        val result = HeaderJson.parse(raw)
        assertTrue("应当解析成功，实际：$result", result is HeaderParseResult.Ok)
        return (result as HeaderParseResult.Ok).headers
    }

    @Test
    fun objectFormKeepsOrder() {
        val list = headers("""{"Referer":"https://a.com","X-Token":"abc"}""")
        assertEquals(listOf("Referer", "X-Token"), list.map { it.name })
        assertEquals("https://a.com", list[0].value)
        assertEquals("abc", list[1].value)
    }

    @Test
    fun numericAndBooleanValuesBecomeText() {
        val list = headers("""{"X-Retry":3,"X-Debug":true}""")
        assertEquals("3", list[0].value)
        assertEquals("true", list[1].value)
    }

    @Test
    fun arrayValueIsJoinedByComma() {
        val list = headers("""{"Accept":["application/json","text/plain"]}""")
        assertEquals("application/json, text/plain", list[0].value)
    }

    @Test
    fun objectArrayWithNameValue() {
        val list = headers(
            """[{"name":"Referer","value":"https://b.com"},{"Key":"Cookie","VALUE":"a=1"}]"""
        )
        assertEquals(2, list.size)
        assertEquals("Referer", list[0].name)
        assertEquals("https://b.com", list[0].value)
        // 字段名不区分大小写
        assertEquals("Cookie", list[1].name)
        assertEquals("a=1", list[1].value)
    }

    @Test
    fun pairArrayForm() {
        val list = headers("""[["Referer","https://c.com"],["Accept","application/json"]]""")
        assertEquals(2, list.size)
        assertEquals("Accept", list[1].name)
        assertEquals("application/json", list[1].value)
    }

    @Test
    fun stringArrayWithColon() {
        val list = headers("""["Referer: https://d.com","User-Agent: UA/1.0"]""")
        assertEquals("Referer", list[0].name)
        assertEquals("https://d.com", list[0].value)
        assertEquals("UA/1.0", list[1].value)
    }

    @Test
    fun plainLinesFallback() {
        val list = headers(
            """
            Referer: https://e.com
            # 这是注释
            X-Trace: 1
            """.trimIndent()
        )
        assertEquals(listOf("Referer", "X-Trace"), list.map { it.name })
    }

    @Test
    fun duplicateNamesLastValueWins() {
        val list = headers("""{"X-A":"1","X-B":"2","x-a":"3"}""")
        assertEquals(2, list.size)
        // 位置取首次出现，值取最后一次
        assertEquals("X-A", list[0].name)
        assertEquals("3", list[0].value)
    }

    @Test
    fun nullValueAndInvalidNameAreSkipped() {
        val result = HeaderJson.parse("""{"X-Null":null,"中文头":"v","X-Ok":"1"}""")
        assertTrue(result is HeaderParseResult.Ok)
        result as HeaderParseResult.Ok
        assertEquals(listOf("X-Ok"), result.headers.map { it.name })
        assertEquals(2, result.skipped)
    }

    @Test
    fun newlineInValueIsFlattened() {
        val list = headers("""{"X-Note":"a\nb"}""")
        assertEquals("a b", list[0].value)
    }

    @Test
    fun blankInputFails() {
        assertTrue(HeaderJson.parse("   ") is HeaderParseResult.Error)
    }

    @Test
    fun garbageFails() {
        assertTrue(HeaderJson.parse("这不是请求头") is HeaderParseResult.Error)
    }
}
