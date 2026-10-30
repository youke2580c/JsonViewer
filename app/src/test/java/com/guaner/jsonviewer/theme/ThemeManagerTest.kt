package com.guaner.jsonviewer.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeManagerTest {

    private val BLACK = 0xFF000000.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()

    /** 用纯实现的 parseColorOrNull 构造颜色，便于断言 */
    private fun argb(hex: String): Int = ThemeManager.parseColorOrNull(hex)!!

    private val base = ThemeColors(
        brand = argb("#2E6BE6"),
        background = argb("#FFFFFF"),
        surface = argb("#F5F7FA"),
        text = argb("#1F2933"),
        gutter = argb("#EEF1F5"),
        gutterText = argb("#98A2B3"),
        jsonKey = argb("#7C3AED"),
        jsonString = argb("#0B7A4B"),
        jsonNumber = argb("#B45309"),
        jsonBoolean = argb("#DC2626"),
        jsonPunct = argb("#9CA3AF"),
        jsonNull = argb("#6B7280"),
        accent = argb("#2E6BE6"),
        errorLine = argb("#FDE2E2"),
        errorColumn = argb("#FCA5A5"),
        searchMatch = argb("#FDE68A"),
        searchCurrent = argb("#F59E0B"),
        treeSelected = argb("#D6E4FF"),
        folderObject = argb("#2E6BE6"),
        folderArray = argb("#E08600")
    )

    @Test
    fun contrastText_lightBg_returnsBlack() {
        assertEquals(BLACK, ThemeManager.contrastText(argb("#FFFFFF")))
    }

    @Test
    fun contrastText_darkBg_returnsWhite() {
        assertEquals(WHITE, ThemeManager.contrastText(argb("#000000")))
        assertEquals(WHITE, ThemeManager.contrastText(argb("#121212")))
    }

    @Test
    fun parseColorOrNull_supportsFormats() {
        assertEquals(argb("#123456"), ThemeManager.parseColorOrNull("#123456"))
        assertEquals(argb("#112233"), ThemeManager.parseColorOrNull("#123"))
        assertEquals(argb("#123456"), ThemeManager.parseColorOrNull("123456"))
        // 8 位 #aarrggbb：忽略 alpha，取末尾 6 位
        assertEquals(argb("#BBCCDD"), ThemeManager.parseColorOrNull("#AABBCCDD"))
        assertNull(ThemeManager.parseColorOrNull("#zzzzzz"))
        assertNull(ThemeManager.parseColorOrNull(""))
        assertNull(ThemeManager.parseColorOrNull(null))
    }

    @Test
    fun toHex_roundTrip() {
        assertEquals("#2E6BE6", ThemeManager.toHex(argb("#2E6BE6")).uppercase())
    }

    @Test
    fun stripBeforeFirstContainer_trimsPrefix() {
        assertEquals("{\"a\":1}", ThemeManager.stripBeforeFirstContainer("说明文字 {\"a\":1}"))
        assertEquals("[1,2]", ThemeManager.stripBeforeFirstContainer("前缀说明 [1,2]"))
        // 没有容器符号不裁剪（避免误删纯说明文本）
        assertEquals("纯说明文本", ThemeManager.stripBeforeFirstContainer("纯说明文本"))
        // 以容器开头不裁剪
        assertEquals("{\"a\":1}", ThemeManager.stripBeforeFirstContainer("{\"a\":1}"))
    }

    @Test
    fun deriveColors_customBg_autoContrast_recomputesForeground() {
        val out = ThemeManager.deriveColors(
            base, brand = base.brand, background = argb("#000000"), accent = base.brand,
            customBackground = true, autoContrast = true
        )
        assertEquals(WHITE, out.text)
        assertEquals(WHITE, out.gutterText)
        assertEquals(WHITE, out.jsonNull)
        assertEquals(base.brand, out.accent)
        assertEquals(argb("#000000"), out.background)
        // 自定义背景+自动对比：surface/gutter 自动派生（与背景不同）
        assertEquals(false, out.surface == out.background)
        assertEquals(false, out.gutter == out.background)
    }

    @Test
    fun deriveColors_customBg_autoContrastOff_keepsBaseForeground() {
        val out = ThemeManager.deriveColors(
            base, brand = base.brand, background = argb("#000000"), accent = base.brand,
            customBackground = true, autoContrast = false
        )
        assertEquals(base.text, out.text)
        // 关闭自动对比时：背景仍应用自定义值（只是前景不重算）
        assertEquals(argb("#000000"), out.background)
    }

    @Test
    fun deriveColors_customAccent_isApplied() {
        val out = ThemeManager.deriveColors(
            base, brand = base.brand, background = base.background, accent = argb("#FF0000"),
            customBackground = false, autoContrast = false
        )
        assertEquals(argb("#FF0000"), out.accent)
        // 未自定义背景时不重算前景，沿用预设
        assertEquals(base.text, out.text)
    }

    @Test
    fun mix_interpolatesEndpoints() {
        assertEquals(argb("#000000"), ThemeManager.mix(argb("#000000"), argb("#FFFFFF"), 0f))
        assertEquals(argb("#FFFFFF"), ThemeManager.mix(argb("#000000"), argb("#FFFFFF"), 1f))
        // 50% 中点：各通道 127.5 截断为 127
        val mid = ThemeManager.mix(argb("#000000"), argb("#FFFFFF"), 0.5f)
        assertEquals(127, (mid shr 16 and 0xFF))
        assertEquals(127, (mid shr 8 and 0xFF))
        assertEquals(127, (mid and 0xFF))
    }
}
