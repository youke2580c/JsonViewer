package com.guaner.jsonviewer.editor

import io.github.rosemoe.sora.lang.styling.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonEditorThemeTest {

    @Test
    fun `custom color ids fit into 19 bits`() {
        // Sora: checkColorId 要求 0 <= id <= 524287，否则 makeStyle 直接抛异常
        for (id in JsonSpansLanguage.ColorIds.ALL) {
            assertTrue("颜色 ID $id 必须 >= 0", id >= 0)
            assertTrue("颜色 ID $id 超出 19 位上界", id <= 524287)
        }
    }

    @Test
    fun `custom color ids do not collide with builtin range`() {
        // 内置槽位只用到 END_COLOR_ID = 66（protected，用字面量），自定义槽位必须排在其后
        val builtinEnd = 66
        assertTrue(
            "自定义槽位必须大于内置上界 $builtinEnd",
            JsonSpansLanguage.ColorIds.ALL.all { it > builtinEnd }
        )
        assertEquals(
            "自定义槽位不能重复",
            JsonSpansLanguage.ColorIds.ALL.size,
            JsonSpansLanguage.ColorIds.ALL.toSet().size
        )
    }

    @Test
    fun `style value carries color id and no stray strike-through`() {
        for (kind in TokenKind.values()) {
            val style = JsonSpansLanguage().styleOf(JsonSpansLanguage.Token(0, 1, kind))
            // 没有删除线/斜体/加粗：早期手写 shl 32 会把这些位点亮
            assertTrue("$kind 不应带删除线", !TextStyle.isStrikeThrough(style))
            assertTrue("$kind 不应带斜体", !TextStyle.isItalics(style))
            assertTrue("$kind 不应带加粗", !TextStyle.isBold(style))
        }
    }

    @Test
    fun `style value round trips color id`() {
        val lang = JsonSpansLanguage()
        val key = lang.styleOf(JsonSpansLanguage.Token(0, 1, TokenKind.KEY))
        assertEquals(JsonSpansLanguage.ColorIds.KEY, TextStyle.getForegroundColorId(key))

        val str = lang.styleOf(JsonSpansLanguage.Token(0, 1, TokenKind.STRING))
        assertEquals(JsonSpansLanguage.ColorIds.STRING, TextStyle.getForegroundColorId(str))
    }

    // 注意：EditorColorScheme() / JsonEditorTheme.schemeOf() / isDark() 在 JVM 单测里会抛
    // RuntimeException: Method xxx in android.graphics.Color not mocked（未开 returnDefaultValues）。
    // 因此这里只验证不触碰 android.graphics.Color 的纯逻辑；配色映射靠 UI 端手测 + lint 保障。

}
