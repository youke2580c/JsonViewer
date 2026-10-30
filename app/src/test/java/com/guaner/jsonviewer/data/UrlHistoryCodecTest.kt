package com.guaner.jsonviewer.data

import com.guaner.jsonviewer.net.HttpHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 链接历史的编解码：一条记录要能完整还原「链接 + 方法 + 请求头 + 请求体」 */
class UrlHistoryCodecTest {

    @Test
    fun roundTripKeepsEverything() {
        val entry = UrlHistory.Entry(
            url = "https://a.com/api?q=中",
            method = "POST",
            headers = listOf(
                HttpHeader("Referer", "https://a.com/"),
                HttpHeader("X-Token", "a\"b\\c"),
                HttpHeader("Cookie", "k=v; k2=v2")
            ),
            body = "keyword=我的&t=1\n第二行"
        )
        val back = UrlHistory.decode(UrlHistory.encode(entry))
        assertEquals(entry.url, back.url)
        assertEquals("POST", back.method)
        assertEquals(entry.headers, back.headers)
        assertEquals(entry.body, back.body)
    }

    @Test
    fun legacyPlainUrlStillDecodes() {
        // 旧版本只存了链接，升级后不能被丢掉
        val back = UrlHistory.decode("https://old.example.com/path")
        assertEquals("https://old.example.com/path", back.url)
        assertEquals("GET", back.method)
        assertTrue(back.headers.isEmpty())
        assertEquals("", back.body)
    }

    @Test
    fun garbageDecodesToUrlOnly() {
        val back = UrlHistory.decode("not json at all")
        assertEquals("not json at all", back.url)
        assertTrue(back.headers.isEmpty())
    }

    @Test
    fun unknownMethodFallsBackToGet() {
        val raw = """{"url":"https://a.com","method":"DELETE","body":"","headers":[]}"""
        assertEquals("GET", UrlHistory.decode(raw).method)
    }

    @Test
    fun blankHeaderNameIsDropped() {
        val raw = """{"url":"https://a.com","method":"GET","body":"","headers":[
            {"name":"","value":"x"},{"name":"X-A","value":"1"}]}"""
        assertEquals(listOf("X-A"), UrlHistory.decode(raw).headers.map { it.name })
    }

    @Test
    fun requestDefaultsRoundTrip() {
        val entry = UrlHistory.Entry(
            url = "https://a.com/x",
            method = "POST",
            body = "k=v",
            uaName = "电脑 UA",
            sendReferer = false,
            contentType = ""
        )
        val back = UrlHistory.decode(UrlHistory.encode(entry))
        assertEquals("电脑 UA", back.uaName)
        assertEquals(false, back.sendReferer)
        assertEquals("", back.contentType)
    }

    @Test
    fun legacyRecordUsesCurrentDefaults() {
        // 4.9 之前的历史没有默认头相关字段，读出来要用「自动」语义
        val raw = """{"url":"https://a.com","method":"POST","body":"x","headers":[]}"""
        val back = UrlHistory.decode(raw)
        assertEquals("", back.uaName)
        assertEquals(true, back.sendReferer)
        assertEquals(AppSettings.CT_AUTO, back.contentType)
    }

    @Test
    fun labelShowsMethodHeadersAndBody() {
        val entry = UrlHistory.Entry(
            url = "https://a.com/x",
            method = "POST",
            headers = listOf(HttpHeader("A", "1"), HttpHeader("B", "")),
            body = "k=v"
        )
        // 空值头不算数
        assertEquals("POST https://a.com/x · 1 头 · 有 body", entry.label())
        assertEquals("GET https://a.com/y", UrlHistory.Entry("https://a.com/y").label())
    }
}
