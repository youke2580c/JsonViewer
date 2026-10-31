package com.guaner.jsonviewer.text

/**
 * 把「一行几十万字符」的压缩 JSON 在**结构符之后**插入换行，压成多行短行。
 *
 * 为什么需要它：Sora 的 `WordwrapLayout.breakLine` 每找一个断点，都要从
 * **剩余整行**里量一次宽度（`getOffsetForAdvance(chars, start, end, advance)`，
 * `end` 是整行末尾）。于是单行断行是 O(n²)：50 万字符的行要算几十亿次字符宽度，
 * 打开/粘贴就能卡几十秒；而且每次按键 `afterInsert` 还会同步重断当前行，同样卡死。
 *
 * 官方换行本身没问题，是这种输入超出了设计。折行之后每行都在阈值内，
 * 换行交回官方 `WordwrapLayout` 全速跑，同时也是用户想看到的「能读完」的样子。
 *
 * 安全性：只在**引号之外**的 `,` `{` `[` `:` 之后断。JSON 里结构之间的空白不影响语义，
 * 所以折行不改变数据的含义；字符串内部一个字都不动（`\` 转义按位跳过）。
 * 一段里实在没有可折点（例如一个超长字符串值）就不折，宁可保留长行也不破坏内容。
 */
object SafeFolder {

    /** 超过这个长度的行才尝试折行 */
    const val DEFAULT_THRESHOLD = 1200

    /**
     * 折行整篇文本。
     *
     * @return 折行后的文本；不需要折行时返回 null（调用方据此跳过整次替换）
     */
    fun fold(text: CharSequence, threshold: Int = DEFAULT_THRESHOLD): String? {
        if (text.isEmpty()) return null
        var changed = false
        // 预估容量：折行只增加换行符，按每 32 字符一个估算已足够
        val sb = StringBuilder(text.length + text.length / 32 + 16)
        var i = 0
        val n = text.length
        while (i <= n) {
            var e = i
            while (e < n && text[e] != '\n') e++
            if (e - i > threshold) {
                if (foldLine(text, i, e, threshold, sb) > 0) changed = true
            } else {
                sb.append(text, i, e)
            }
            if (e >= n) break
            sb.append('\n')
            i = e + 1
        }
        return if (changed) sb.toString() else null
    }

    /**
     * 折 [from, to) 这一段（不含换行）。
     *
     * 一直扫描到「自上一个折点起已攒够 [threshold] 个字符」且存在可折点时，
     * 就近在最后一个可折点之后断行。折点取结构符，是为了让每一行都落在
     * 一个完整的「键值对 / 数组元素」边界上，看起来接近格式化后的样子。
     *
     * @return 实际插入的换行数量，0 表示这段没有可折点、原样输出
     */
    private fun foldLine(text: CharSequence, from: Int, to: Int, threshold: Int, sb: StringBuilder): Int {
        var segStart = from
        var lastBreak = -1      // 可折位置：该下标处的字符之后可以断
        var inString = false
        var escaped = false
        var inserted = 0
        var i = from
        while (i < to) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    ',', '{', '[', ':' -> lastBreak = i
                }
            }
            i++
            if (i - segStart >= threshold && lastBreak > segStart) {
                sb.append(text, segStart, lastBreak + 1)
                sb.append('\n')
                segStart = lastBreak + 1
                lastBreak = -1
                inserted++
            }
        }
        sb.append(text, segStart, to)
        return inserted
    }
}
