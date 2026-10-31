package com.guaner.jsonviewer.text

/**
 * 识别输入法「移动光标」请求。
 *
 * 背景：`InputConnection.setSelection(start, end)` 的语义本是**设置选区**，
 * 但百度、搜狗这类中文输入法在键盘上按左右方向键移动光标时，走的也是这条通道，
 * 而且传进来的是一个**长度为 1 的选区** —— `(pos - 1, pos)` 表示「把光标挪到 pos」。
 *
 * Sora 的 `EditorInputConnection.setSelection()` 不认这个约定，它无条件走选区：
 *
 * ```java
 * this.editor.setSelectionRegion(startPos.line, startPos.column,
 *                                endPos.line, endPos.column, false, 4);
 * ```
 *
 * 于是「按一下左方向键」就变成了「选中一个字」。
 *
 * 这里把这种情况识别出来并折叠成一个光标位置；其余情况一律按真正的选区放行，
 * 免得伤到双击选词、拖动选区这些正常流程。
 */
object ImeSelection {

    /**
     * @param start 输入法给出的选区起点（字符偏移，可能越界）
     * @param end 输入法给出的选区终点（字符偏移，可能越界）
     * @param len 当前文档长度
     * @return 应当放置光标的字符偏移；不是「输入法在移动光标」时返回 null
     */
    fun caretIndexFor(start: Int, end: Int, len: Int): Int? {
        if (len <= 0) return null
        val a = start.coerceIn(0, len)
        val b = end.coerceIn(0, len)
        val lo = minOf(a, b)
        val hi = maxOf(a, b)
        // 只有「恰好一个字符」才算移动光标：
        // 真正的选区（选词、拖选）几乎不会只有一个字符，而输入法表达「光标位置」
        // 却经常借这个形态。光标停在跨度右端，和常规编辑器一致。
        return if (hi - lo == 1) hi else null
    }

    /**
     * 这条 line 的**所有** row 是否都在屏幕里。
     *
     * 用来决定「移动完光标要不要再算一遍像素坐标」：`getCharLayoutOffset()` 要先沿
     * rowTable 线性走到目标列（见调用方的注释），所以能不调用就别调用。
     *
     * @param line 目标逻辑行号
     * @param firstVisibleLine 屏幕上第一个可见的逻辑行号
     * @param lastVisibleLine 屏幕上最后一个可见的逻辑行号
     * @param wordwrap 是否开启了软换行
     */
    fun wholeLineVisible(
        line: Int,
        firstVisibleLine: Int,
        lastVisibleLine: Int,
        wordwrap: Boolean
    ): Boolean = if (wordwrap) {
        // 软换行下一条 line 占多个 row：首末两行可能只露一半，只有严格夹在中间才整条可见
        line > firstVisibleLine && line < lastVisibleLine
    } else {
        // 一行就是一个 row，行号在范围内就必然可见
        line in firstVisibleLine..lastVisibleLine
    }
}
