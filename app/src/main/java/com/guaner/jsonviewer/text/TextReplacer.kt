package com.guaner.jsonviewer.text

/**
 * 文本替换工具：把若干命中区间换成同一段文本。
 *
 * 关键点：**必须从后往前替换**。如果从前往后改，第一次替换改变了长度，
 * 后面所有区间下标就全部错位——这是文本编辑器里最常见的替换 bug。
 *
 * 抽成纯函数是为了能单测：既保证不偏移，也保证重叠/越界区间不会把文本改坏。
 */
object TextReplacer {

    /**
     * 把 [text] 里 [ranges] 指定的区间替换为 [replacement]。
     *
     * - [ranges] 里的区间按 `IntRange`（首尾都含）给出，与搜索命中的表示保持一致
     * - 越界的区间会被裁剪到合法范围；空区间（`last < first`）会被跳过
     * - 相互重叠的区间只保留靠前的那个（按起点排序后跳过被包含的）
     *
     * @return 替换后的文本；[ranges] 为空时原样返回 [text]
     */
    fun replace(text: String, ranges: List<IntRange>, replacement: String): String {
        if (ranges.isEmpty() || text.isEmpty()) return text
        val len = text.length
        // 统一成「半开区间 [first, last+1)」。注意 Kotlin 的 IntRange 是闭区间：
        // `1 until 2` 会被规范化成 `1..1`，last 又回到 e-1，所以不能用 first<last 判空。
        val normalized = ArrayList<IntRange>(ranges.size)
        var lastEnd = -1
        ranges
            .map { r ->
                val s = r.first.coerceIn(0, len)
                val e = (r.last + 1).coerceIn(s, len)
                s until e
            }
            // 空区间：起点 == 终点（映射后 last+1 == first）
            .filter { it.last + 1 > it.first }
            .sortedBy { it.first }
            .forEach { r ->
                // lastEnd 记录「已被覆盖到的下一个位置」（半开），
                // 只有新区间起点 >= 它才不重叠
                if (r.first >= lastEnd) {
                    normalized.add(r)
                    lastEnd = r.last + 1
                }
            }
        if (normalized.isEmpty()) return text

        val sb = StringBuilder(text)
        for (i in normalized.indices.reversed()) {
            val r = normalized[i]
            // IntRange 闭区间 → StringBuilder.replace 的 endIndex 是半开，需要 +1
            sb.replace(r.first, r.last + 1, replacement)
        }
        return sb.toString()
    }
}
