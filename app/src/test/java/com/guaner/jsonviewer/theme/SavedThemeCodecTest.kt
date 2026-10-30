package com.guaner.jsonviewer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 自定义主题的编码/解码：保证存下去能原样读回来（纯函数，无需 Android 环境） */
class SavedThemeCodecTest {

    private fun sample(
        id: String = "t1",
        name: String = "我的蓝",
        accent: String? = "#FF8800"
    ) = SavedTheme(
        id = id,
        name = name,
        presetId = "classic_blue",
        brand = "#2E6BE6",
        background = "#101010",
        accent = accent,
        fontFamily = "sans",
        codeFontSize = 16f,
        formFontSize = 18f,
        codeWrap = true,
        codeZoom = false,
        autoContrast = false
    )

    @Test
    fun roundTrip_keepsAllFields() {
        val list = listOf(sample())
        val decoded = SavedThemeCodec.decode(SavedThemeCodec.encode(list))
        assertEquals(1, decoded.size)
        val t = decoded[0]
        assertEquals("t1", t.id)
        assertEquals("我的蓝", t.name)
        assertEquals("classic_blue", t.presetId)
        assertEquals("#2E6BE6", t.brand)
        assertEquals("#101010", t.background)
        assertEquals("#FF8800", t.accent)
        assertEquals("sans", t.fontFamily)
        assertEquals(16f, t.codeFontSize, 0.001f)
        assertEquals(18f, t.formFontSize, 0.001f)
        assertTrue(t.codeWrap)
        assertEquals(false, t.codeZoom)
        assertEquals(false, t.autoContrast)
    }

    @Test
    fun nullAccentMeansFollowBrand() {
        val decoded = SavedThemeCodec.decode(SavedThemeCodec.encode(listOf(sample(accent = null))))
        assertNull(decoded[0].accent)
    }

    @Test
    fun multipleThemesKeepOrder() {
        val list = listOf(sample(id = "a", name = "甲"), sample(id = "b", name = "乙"))
        val decoded = SavedThemeCodec.decode(SavedThemeCodec.encode(list))
        assertEquals(listOf("a", "b"), decoded.map { it.id })
    }

    @Test
    fun nameSeparatorsAreSanitized() {
        val decoded = SavedThemeCodec.decode(
            SavedThemeCodec.encode(listOf(sample(name = "坏|名字\n第二行")))
        )
        assertEquals(1, decoded.size)
        assertEquals("坏 名字 第二行", decoded[0].name)
    }

    @Test
    fun decodeSkipsBrokenRecords() {
        val good = SavedThemeCodec.encode(listOf(sample()))
        val decoded = SavedThemeCodec.decode("bad|record\n$good\n\n")
        assertEquals(1, decoded.size)
        assertEquals("t1", decoded[0].id)
    }

    @Test
    fun decodeHandlesEmpty() {
        assertTrue(SavedThemeCodec.decode(null).isEmpty())
        assertTrue(SavedThemeCodec.decode("").isEmpty())
    }

    @Test
    fun previewColorsAreFour() {
        assertEquals(4, sample().previewColors().size)
    }
}
