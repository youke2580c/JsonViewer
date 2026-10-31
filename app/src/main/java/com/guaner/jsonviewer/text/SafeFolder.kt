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
     * 折行结果。
     *
     * [anchor] 是「折行前 [anchor] 偏移的字符」在折行后文本里的位置。
     * 折行只会**插入**换行符，所以只需要把光标往后挪「插在它前面的换行数」，
     * 否则光标会停在原来那个字符的左侧，看起来就是「光标错位」。
     */
    class Result(val text: String, val anchor: Int)

    /**
     * 折行整篇文本。
     *
     * @return 折行后的文本；不需要折行时返回 null（调用方据此跳过整次替换）
     */
    fun fold(text: CharSequence, threshold: Int = DEFAULT_THRESHOLD): String? =
        foldWithAnchor(text, threshold, -1)?.text

    /**
     * 折行整篇文本，并把 [anchor]（折行前的字符偏移）映射到折行后的位置。
     *
     * @param anchor 需要保持位置的字符偏移；传 -1 表示不关心
     */
    fun foldWithAnchor(text: CharSequence, threshold: Int = DEFAULT_THRESHOLD, anchor: Int = -1): Result? {
        if (text.isEmpty()) return null
        var changed = false
        // 预估容量：折行只增加换行符，按每 32 字符一个估算已足够
        val sb = StringBuilder(text.length + text.length / 32 + 16)
        // 已经插在 anchor 之前的换行数量
        val shift = intArrayOf(0)
        var i = 0
        val n = text.length
        while (i <= n) {
            var e = i
            while (e < n && text[e] != '\n') e++
            if (e - i > threshold) {
                if (foldLine(text, i, e, threshold, sb, anchor, shift) > 0) changed = true
            } else {
                sb.append(text, i, e)
            }
            if (e >= n) break
            sb.append('\n')
            i = e + 1
        }
        if (!changed) return null
        val mapped = if (anchor < 0) -1 else (anchor + shift[0]).coerceIn(0, sb.length)
        return Result(sb.toString(), mapped)
    }

    /**
     * 折 [from, to) 这一段（不含换行）。
     *
     * 分两步：**先**整段扫出所有打算断行的位置，**再**确认安全才真的写入。
     *
     * 为什么不能边扫边写：这段文字是否处在某个字符串内部，只有扫到最后才知道。
     * 而 receives 到的文本未必是合法 JSON（用户可能正在把一段坏东西改好），
     * 一旦引号不平衡，状态机就会把「字符串里的逗号」当成结构符，
     * 在**字符串里面**插入真实换行 —— JSON 的字符串不允许裸换行，内容当场报废，
     * 表现就是「文本里的 \n 变成了实际换行、再也美化不了」。
     * 于是凡是扫到最后仍处于字符串内的段落，一律整段原样输出、一个换行都不插。
     *
     * 断点优先取结构符，是为了让每一行都落在一个完整的「键值对 / 数组元素」边界上，
     * 看起来接近格式化后的样子。
     *
     * @param anchor 光标偏移，用于统计插在它前面的换行数（见 [shift]）
     * @param shift  单元素数组，累计「插在 anchor 之前的换行数」
     * @return 实际插入的换行数量，0 表示这段没有可折点、原样输出
     */
    private fun foldLine(
        text: CharSequence,
        from: Int,
        to: Int,
        threshold: Int,
        sb: StringBuilder,
        anchor: Int,
        shift: IntArray
    ): Int {
        // ---- 第一遍：找断点，同时盯住「这段最后是不是还在字符串里」 ----
        val breaks = ArrayList<Int>()   // 每个元素表示：在该下标处的字符之后可以断
        var segStart = from
        var lastBreak = -1      // 可折位置：该下标处的字符之后可以断
        var inString = false
        var escaped = false
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
                breaks.add(lastBreak)
                segStart = lastBreak + 1
                lastBreak = -1
            }
        }

        // 拿不准作用域就别动：宁可留着长行慢慢断，也不能把内容改坏
        if (breaks.isEmpty() || inString || escaped) {
            sb.append(text, from, to)
            return 0
        }

        // ---- 第二遍：确认安全，按断点写入 ----
        segStart = from
        for (b in breaks) {
            sb.append(text, segStart, b + 1)
            sb.append('\n')
            if (anchor >= 0 && b + 1 <= anchor) shift[0]++
            segStart = b + 1
        }
        sb.append(text, segStart, to)
        return breaks.size
    }
}
