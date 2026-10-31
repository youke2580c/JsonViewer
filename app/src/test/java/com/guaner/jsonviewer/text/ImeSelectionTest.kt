package com.guaner.jsonviewer.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSelectionTest {

    @Test
    fun `one char range means cursor movement`() {
        // 输入法用 (pos-1, pos) 表达「把光标放到 pos」
        assertEquals(5, ImeSelection.caretIndexFor(4, 5, 100))
    }

    @Test
    fun `reversed one char range still collapses to the right edge`() {
        assertEquals(5, ImeSelection.caretIndexFor(5, 4, 100))
    }

    @Test
    fun `zero length range is handled by the editor itself`() {
        // start == end 是「输入法把光标设置到这里」，Sora 自己处理得很好，别插手
        assertNull(ImeSelection.caretIndexFor(7, 7, 100))
    }

    @Test
    fun `real selections are left alone`() {
        assertNull(ImeSelection.caretIndexFor(0, 12, 100))
        assertNull(ImeSelection.caretIndexFor(20, 18, 100))
    }

    @Test
    fun `out of range positions are clamped`() {
        assertEquals(10, ImeSelection.caretIndexFor(9, 10, 10))
        // 越界的坐标被夹到两端后退化成零长度，交给编辑器自己处理
        assertNull(ImeSelection.caretIndexFor(-3, -2, 10))
        assertNull(ImeSelection.caretIndexFor(99, 200, 10))
        assertNull(ImeSelection.caretIndexFor(0, 0, 0))
    }

    @Test
    fun `line is fully visible when it is strictly inside the viewport`() {
        assertTrue(ImeSelection.wholeLineVisible(10, 5, 20, wordwrap = false))
        assertTrue(ImeSelection.wholeLineVisible(10, 5, 20, wordwrap = true))
    }

    @Test
    fun `wrapped edge lines are not treated as fully visible`() {
        // 软换行时首末两行可能只露出一半，不能假定它们的全部 row 都在屏幕内
        assertFalse(ImeSelection.wholeLineVisible(5, 5, 20, wordwrap = true))
        assertFalse(ImeSelection.wholeLineVisible(20, 5, 20, wordwrap = true))
        // 不换行时一行就是一个 row，边缘也算完整可见
        assertTrue(ImeSelection.wholeLineVisible(5, 5, 20, wordwrap = false))
        assertTrue(ImeSelection.wholeLineVisible(20, 5, 20, wordwrap = false))
    }

    @Test
    fun `lines outside the viewport are never visible`() {
        assertFalse(ImeSelection.wholeLineVisible(4, 5, 20, wordwrap = false))
        assertFalse(ImeSelection.wholeLineVisible(4, 5, 20, wordwrap = true))
        assertFalse(ImeSelection.wholeLineVisible(21, 5, 20, wordwrap = false))
        assertFalse(ImeSelection.wholeLineVisible(21, 5, 20, wordwrap = true))
    }
}
