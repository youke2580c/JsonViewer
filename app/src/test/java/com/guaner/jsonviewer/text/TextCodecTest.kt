package com.guaner.jsonviewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCodecTest {

    // ---------- Base64 ----------

    @Test
    fun base64DecodesUtf8Text() {
        assertEquals("你好", TextCodec.decodeBase64("5L2g5aW9"))
    }

    @Test
    fun base64IgnoresSurroundingAndInnerWhitespace() {
        assertEquals("你好", TextCodec.decodeBase64("  5L2g 5aW9\n"))
        assertEquals("{\"a\":1}", TextCodec.decodeBase64("\teyJhIjoxfQ==\r\n"))
    }

    @Test
    fun base64AcceptsUrlSafeAlphabetAndMissingPadding() {
        // "??>" 的字节 0x3F 0x3F 0x3E，标准 base64 为 Pz8+，URL-safe 变体 Pz8-
        assertEquals("??>", TextCodec.decodeBase64("Pz8-"))
        assertEquals("??>", TextCodec.decodeBase64("Pz8+"))
        // 去掉 padding 也要能解
        assertEquals("hello", TextCodec.decodeBase64("aGVsbG8"))
    }

    @Test
    fun base64RejectsNonBase64Input() {
        assertNull(TextCodec.decodeBase64("这不是 base64"))
        assertNull(TextCodec.decodeBase64(""))
        assertNull(TextCodec.decodeBase64("   "))
        // 单个字符凑不出一个字节
        assertNull(TextCodec.decodeBase64("A"))
    }

    @Test
    fun base64RejectsBytesThatAreNotUtf8() {
        // 0xFF 0xFE 不是合法 UTF-8
        assertNull(TextCodec.decodeBase64("//4="))
    }

    // ---------- URL 百分号 ----------

    @Test
    fun urlDecodesMultiByteChinese() {
        assertEquals("中文", TextCodec.decodeUrlPercent("%E4%B8%AD%E6%96%87"))
    }

    @Test
    fun urlDecodesMixedContent() {
        assertEquals(
            "key=值&x=1",
            TextCodec.decodeUrlPercent("key=%E5%80%BC&x=1")
        )
    }

    @Test
    fun urlDecodesAsciiAndEmoji() {
        assertEquals("%", TextCodec.decodeUrlPercent("%25"))
        assertEquals("😀", TextCodec.decodeUrlPercent("%F0%9F%98%80"))
    }

    @Test
    fun urlKeepsPlusLiteralAndLeavesInvalidSequences() {
        // '+' 是表单编码的规则，JSON 正文里应原样保留
        assertEquals("a+b", TextCodec.decodeUrlPercent("a+b"))
        // 非法十六进制
        assertEquals("%ZZ", TextCodec.decodeUrlPercent("%ZZ"))
        // 多字节首字节后面接不上续接字节：只吐掉第一个 %XX，不能吞掉后面的内容
        assertEquals("%E4abc", TextCodec.decodeUrlPercent("%E4abc"))
    }

    @Test
    fun urlWithoutPercentReturnsSameInstance() {
        val text = "{\"a\":1}"
        assertEquals(text, TextCodec.decodeUrlPercent(text))
    }
}
