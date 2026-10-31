package com.guaner.jsonviewer.text

/**
 * 逻辑行起始偏移表。
 *
 * 逐字输入时整表重新扫一遍全文（O(n)）是超大文本卡顿的来源之一：
 * 一次按键只需要把「变化点之后」的行首整体平移 (新增 - 删除) 个字符，
 * 行数不变时不必重扫。只有插入/删除的片段里带换行、行结构真的变了，才退回全量重建。
 */
object LineStarts {

    /** 全量扫描：返回每一逻辑行的起始偏移（升序，首个恒为 0） */
    fun build(text: CharSequence): IntArray {
        val n = text.length
        var arr = IntArray(64)
        arr[0] = 0
        var k = 1
        for (i in 0 until n) {
            if (text[i] == '\n') {
                if (k == arr.size) arr = arr.copyOf(arr.size * 2)
                arr[k++] = i + 1
            }
        }
        return if (k == arr.size) arr else arr.copyOf(k)
    }

    /** 最长逻辑行的字符数 */
    fun maxLineLength(text: CharSequence): Int {
        var max = 0
        var start = 0
        val n = text.length
        for (i in 0 until n) {
            if (text[i] == '\n') {
                val len = i - start
                if (len > max) max = len
                start = i + 1
            }
        }
        val tail = n - start
        return if (tail > max) tail else max
    }

    /**
     * 局部平移：[starts] 是变化前的行首表，本次在 [changeStart] 处删掉 [before] 个字符、
     * 插入 [count] 个字符，两者都不含换行（调用方保证）。
     *
     * 返回平移后的新表；无法安全增量（越界等异常情况）时返回 null，由调用方全量重建。
     *
     * 关键在于用「严格大于 changeStart」作为分界：插入点恰好落在某行行首时，
     * 新插入的字符仍属于该行，该行起点不变；其后的行首才需要整体位移。
     */
    fun shift(starts: IntArray, changeStart: Int, before: Int, count: Int, newLength: Int): IntArray? {
        if (starts.isEmpty()) return null
        val delta = count - before
        if (delta == 0) return starts
        if (changeStart < 0 || changeStart > newLength) return null

        // 二分找到第一个 > changeStart 的下标
        var lo = 0
        var hi = starts.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= changeStart) lo = mid + 1 else hi = mid
        }
        // 其后没有行首（改动发生在最后一行内），整表不变
        if (lo >= starts.size) return starts

        val out = starts.copyOf()
        for (i in lo until out.size) {
            val v = out[i] + delta
            if (v <= changeStart || v > newLength) return null
            out[i] = v
        }
        return out
    }

    /** [start, start+count) 内是否含换行 */
    fun hasNewline(text: CharSequence, start: Int, count: Int): Boolean {
        val end = (start + count).coerceAtMost(text.length)
        var i = start.coerceAtLeast(0)
        while (i < end) {
            if (text[i] == '\n') return true
            i++
        }
        return false
    }
}
