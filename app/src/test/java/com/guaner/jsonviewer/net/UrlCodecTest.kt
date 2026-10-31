package com.guaner.jsonviewer.net

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlCodecTest {

    private val utf8 = Charsets.UTF_8
    private val gbk = Charset.forName("GBK")

    @Test
    fun `自动模式按 UTF-8 编码中文`() {
        assertEquals(
            "https://a.com/s?key=%E6%88%91%E7%9A%84&t=1",
            UrlCodec.encode("https://a.com/s?key=我的&t=1", utf8, UrlEncodeMode.AUTO)
        )
    }

    @Test
    fun `自动模式按 GBK 编码中文`() {
        assertEquals(
            "https://a.com/s?key=%CE%D2%B5%C4",
            UrlCodec.encode("https://a.com/s?key=我的", gbk, UrlEncodeMode.AUTO)
        )
    }

    @Test
    fun `保留结构字符与已有转义`() {
        val url = "https://a.com/x/y?b=1&c=%E6%88%91#frag"
        assertEquals(url, UrlCodec.encode(url, utf8, UrlEncodeMode.AUTO))
    }

    @Test
    fun `空格编码为百分号二十`() {
        assertEquals("a%20b", UrlCodec.encode("a b", utf8, UrlEncodeMode.AUTO))
    }

    @Test
    fun `不转义模式原样返回`() {
        val raw = "https://a.com/s?key=我的&x=100%"
        assertEquals(raw, UrlCodec.encode(raw, utf8, UrlEncodeMode.NONE))
    }

    @Test
    fun `百分号也转义`() {
        assertEquals(
            "a=%25E6%2588%2591",
            UrlCodec.encode("a=%E6%88%91", utf8, UrlEncodeMode.ALL)
        )
        assertEquals("100%25", UrlCodec.encode("100%", utf8, UrlEncodeMode.ALL))
    }

    @Test
    fun `非法的百分号在自动模式下被编码`() {
        assertEquals("100%25", UrlCodec.encode("100%", utf8, UrlEncodeMode.AUTO))
    }

    @Test
    fun `四字节字符按 UTF-8 编码`() {
        assertEquals("%F0%9F%98%80", UrlCodec.encode("😀", utf8, UrlEncodeMode.AUTO))
    }

    @Test
    fun `GBK 下 ASCII 不受影响`() {
        assertEquals("https://a.com/a?b=1", UrlCodec.encode("https://a.com/a?b=1", gbk, UrlEncodeMode.AUTO))
    }

    @Test
    fun `模式解析容错`() {
        assertEquals(UrlEncodeMode.AUTO, UrlEncodeMode.of(null))
        assertEquals(UrlEncodeMode.AUTO, UrlEncodeMode.of("unknown"))
        assertEquals(UrlEncodeMode.ALL, UrlEncodeMode.of("all"))
    }
}
