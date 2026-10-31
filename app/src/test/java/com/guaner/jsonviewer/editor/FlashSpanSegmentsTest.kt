package com.guaner.jsonviewer.editor

import io.github.rosemoe.sora.lang.styling.TextStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跳转高亮的**行内分段**规则。
 *
 * 这些是纯计算，不依赖 Android 或 Content，所以直接测。
 * 重点盯住两个曾经让「高亮不显示 / 背景蔓延」的坑：
 * 1. 行尾必须有恢复段，否则背景一路蔓延到后面的行
 * 2. 字段名块顶到行尾时，段尾不能和恢复段同列（同列会被去重，恢复段被丢掉）
 */
class FlashSpanSegmentsTest {

    /** 用 (列号, 是否带背景) 表示一段，便于断言 */
    private data class Seg(val column: Int, val bg: Int)

    private fun segments(wordStart: Int, wordEnd: Int, lineLen: Int): List<Seg> {
        // 通过反射拿私有方法，避免为测试而放宽可见性
        val lang = JsonSpansLanguage()
        val mgr = lang.javaClass.getDeclaredField("manager").let {
            it.isAccessible = true
            it.get(lang)
        }
        val cls = Class.forName("com.guaner.jsonviewer.editor.JsonSpansLanguage\$LineFlash")
        val ctor = cls.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        ctor.isAccessible = true
        val flash = ctor.newInstance(wordStart, wordEnd, lineLen)

        val m = mgr.javaClass.getDeclaredMethod("flashSpans", cls, Int::class.javaPrimitiveType)
        m.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val spans = m.invoke(mgr, flash, lineLen) as List<Any>

        return spans.map { s ->
            val c = s.javaClass.getDeclaredField("column").apply { isAccessible = true }.getInt(s)
            val st = s.javaClass.getDeclaredField("style").apply { isAccessible = true }.getLong(s)
            Seg(c, TextStyle.getBackgroundColorId(st))
        }
    }

    @Test
    fun `full line band plus word block`() {
        // 行 "  \"name\": 1," 共 11 列，字段名在 2..7
        val segs = segments(2, 7, 11)
        assertEquals(
            listOf(
                Seg(0, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(2, JsonSpansLanguage.ColorIds.FLASH_WORD),
                Seg(7, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(11, 0)
            ),
            segs
        )
    }

    @Test
    fun `word ending exactly at line end keeps restore segment`() {
        // 字段名一直顶到行尾：只允许一条恢复段，且必须存在
        val segs = segments(2, 11, 11)
        assertEquals(
            listOf(
                Seg(0, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(2, JsonSpansLanguage.ColorIds.FLASH_WORD),
                Seg(11, 0)
            ),
            segs
        )
        // 列号严格递增，否则 MappedSpans.Builder 会抛异常导致整篇样式下发失败
        segs.zipWithNext().forEach { (a, b) -> assertTrue("列号必须递增: $a -> $b", b.column > a.column) }
    }

    @Test
    fun `word at line start`() {
        val segs = segments(0, 4, 10)
        assertEquals(
            listOf(
                Seg(0, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(0, JsonSpansLanguage.ColorIds.FLASH_WORD),
                Seg(4, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(10, 0)
            ),
            segs
        )
        // 同列冲突由优先级解决：高亮内部顺序是「带 → 块」，块必须压过带
        assertTrue(segs[1].bg != segs[0].bg)
    }

    @Test
    fun `empty word falls back to plain band`() {
        val segs = segments(5, 5, 9)
        assertEquals(
            listOf(
                Seg(0, JsonSpansLanguage.ColorIds.FLASH_LINE),
                Seg(9, 0)
            ),
            segs
        )
    }

    @Test
    fun `out of range word is clamped`() {
        // 越界的列号必须夹到行内，否则 Builder 会拒绝
        val segs = segments(-3, 999, 6)
        assertTrue(segs.all { it.column in 0..6 })
        assertEquals(0, segs.last().bg)
        assertEquals(6, segs.last().column)
    }

    @Test
    fun `empty line produces nothing`() {
        assertTrue(segments(0, 0, 0).isEmpty())
    }
}
