package com.guaner.jsonviewer.json

/**
 * JSON 文本「路径 → 字符偏移」定位器。
 *
 * 用途：在代码页里从结构大纲点某一项时，直接跳到该字段在原文中的位置并高亮。
 * 树节点只存了 JSONPath、不存原始偏移，因此这里按路径在原文上做一次
 * 轻量扫描（跳过字符串、括号配对），找到目标成员的键或数组元素的位置。
 *
 * 纯 Kotlin 实现，不依赖 Android，可直接 JVM 单测。
 */
object JsonOffsetLocator {

    sealed class Seg {
        data class Key(val name: String) : Seg()
        data class Index(val index: Int) : Seg()
    }

    /**
     * 返回目标项在 [text] 中的字符区间（闭区间）。
     *
     * - 对象成员：返回**键名**本身（不含引号）；
     * - 数组元素：返回元素值的第一个 token（字符串含引号，容器只含起始括号）。
     * - 找不到（JSON 与树不同步、路径非法）返回 null。
     */
    fun find(text: CharSequence, path: String): IntRange? = locate(text, path, wantValue = false)

    /**
     * 返回目标**值**在原文中的字符区间（endExclusive）。
     *
     * 与 [find] 的区别：对象成员返回的是值（不是键名），容器返回完整的 `{...}` / `[...]`。
     * 值弹窗编辑后用它把新值原位替换回原文，不影响周围格式与缩进。
     * 根节点（`$`）不支持编辑，返回 null。
     */
    fun findValueRange(text: CharSequence, path: String): IntRange? =
        locate(text, path, wantValue = true)

    private fun locate(text: CharSequence, path: String, wantValue: Boolean): IntRange? {
        if (path.isEmpty()) return null
        if (path == "$") return if (wantValue || text.isEmpty()) null else 0..0
        val segs = parsePath(path) ?: return null

        var i = skipWs(text, 0)
        var result: IntRange? = null

        for (seg in segs) {
            i = skipWs(text, i)
            if (i >= text.length) return null
            when (seg) {
                is Seg.Key -> {
                    if (text[i] != '{') return null
                    i = skipWs(text, i + 1)
                    var matched = false
                    while (i < text.length && text[i] != '}') {
                        if (text[i] != '"') return null
                        val afterKey = stringEnd(text, i) ?: return null
                        val key = unescape(text, i + 1, afterKey - 1)
                        var j = skipWs(text, afterKey)
                        if (j >= text.length || text[j] != ':') return null
                        j = skipWs(text, j + 1)
                        if (key == seg.name) {
                            result = if (wantValue) {
                                j until skipValue(text, j)
                            } else {
                                (i + 1) until (afterKey - 1)
                            }
                            i = j
                            matched = true
                            break
                        }
                        i = skipWs(text, skipValue(text, j))
                        if (i < text.length && text[i] == ',') {
                            i = skipWs(text, i + 1)
                        } else {
                            break
                        }
                    }
                    if (!matched) return null
                }
                is Seg.Index -> {
                    if (text[i] != '[') return null
                    i = skipWs(text, i + 1)
                    var n = 0
                    while (n < seg.index) {
                        i = skipWs(text, skipValue(text, i))
                        if (i < text.length && text[i] == ',') {
                            i = skipWs(text, i + 1)
                            n++
                        } else {
                            return null
                        }
                    }
                    result = if (wantValue) {
                        i until skipValue(text, i)
                    } else {
                        tokenRange(text, i)
                    }
                }
            }
        }
        return result
    }

    /** 便捷方法：只要起始偏移 */
    fun findOffset(text: CharSequence, path: String): Int? = find(text, path)?.first

    // ---------- 路径解析 ----------

    /** $.a['b c'][2].d 或 $['x'][0] → [Key(a), Key(b c), Index(2), Key(d)] */
    fun parsePath(path: String): List<Seg>? {
        if (!path.startsWith("$")) return null
        val segs = ArrayList<Seg>()
        var i = 1
        while (i < path.length) {
            when (path[i]) {
                '.' -> {
                    val start = i + 1
                    var j = start
                    while (j < path.length && path[j] != '.' && path[j] != '[') j++
                    if (j == start) return null
                    segs.add(Seg.Key(path.substring(start, j)))
                    i = j
                }
                '[' -> {
                    if (i + 1 < path.length && path[i + 1] == '\'') {
                        // ['key']，内部可能有 \\ 与 \' 转义
                        val sb = StringBuilder()
                        var j = i + 2
                        var closed = false
                        while (j < path.length) {
                            val c = path[j]
                            if (c == '\\' && j + 1 < path.length) {
                                sb.append(path[j + 1])
                                j += 2
                                continue
                            }
                            if (c == '\'' && j + 1 < path.length && path[j + 1] == ']') {
                                closed = true
                                j += 2
                                break
                            }
                            sb.append(c)
                            j++
                        }
                        if (!closed) return null
                        segs.add(Seg.Key(sb.toString()))
                        i = j
                    } else {
                        val close = path.indexOf(']', i)
                        if (close < 0) return null
                        val digits = path.substring(i + 1, close)
                        val idx = digits.toIntOrNull() ?: return null
                        segs.add(Seg.Index(idx))
                        i = close + 1
                    }
                }
                else -> return null
            }
        }
        return segs
    }

    // ---------- 扫描辅助 ----------

    private fun isWs(c: Char): Boolean = c == ' ' || c == '\n' || c == '\t' || c == '\r'

    private fun skipWs(text: CharSequence, from: Int): Int {
        var i = from
        while (i < text.length && isWs(text[i])) i++
        return i
    }

    /** text[start] 必须是引号，返回闭合引号之后的下标 */
    private fun stringEnd(text: CharSequence, start: Int): Int? {
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> return i + 1
                else -> i++
            }
        }
        return null
    }

    private fun unescape(text: CharSequence, from: Int, to: Int): String {
        val sb = StringBuilder(to - from)
        var i = from
        while (i < to) {
            val c = text[i]
            if (c != '\\' || i + 1 >= to) {
                sb.append(c)
                i++
                continue
            }
            when (val e = text[i + 1]) {
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'u' -> {
                    if (i + 5 < to) {
                        val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) {
                            sb.append(code.toChar())
                            i += 6
                        } else {
                            sb.append(e); i += 2
                        }
                    } else {
                        sb.append(e); i += 2
                    }
                }
                else -> { sb.append(e); i += 2 }
            }
        }
        return sb.toString()
    }

    /** 跳过一个值（字符串 / 容器 / 原子 token），返回其后的下标 */
    private fun skipValue(text: CharSequence, start: Int): Int {
        var i = skipWs(text, start)
        if (i >= text.length) return i
        return when (text[i]) {
            '"' -> stringEnd(text, i) ?: text.length
            '{', '[' -> {
                var depth = 0
                var j = i
                while (j < text.length) {
                    when (text[j]) {
                        '"' -> {
                            j = stringEnd(text, j) ?: return text.length
                            continue
                        }
                        '{', '[' -> depth++
                        '}', ']' -> {
                            depth--
                            if (depth == 0) return j + 1
                        }
                    }
                    j++
                }
                text.length
            }
            else -> {
                var j = i
                while (j < text.length && text[j] != ',' && text[j] != ']' && text[j] != '}' &&
                    !isWs(text[j])
                ) {
                    j++
                }
                j
            }
        }
    }

    /** 值的「首个 token」区间，用于高亮：字符串到闭合引号，容器只取起始括号 */
    private fun tokenRange(text: CharSequence, start: Int): IntRange? {
        val i = skipWs(text, start)
        if (i >= text.length) return null
        return when (text[i]) {
            '"' -> {
                val end = stringEnd(text, i) ?: return null
                i until end
            }
            '{', '[' -> i..i
            else -> {
                var j = i
                while (j < text.length && text[j] != ',' && text[j] != ']' && text[j] != '}' &&
                    !isWs(text[j])
                ) {
                    j++
                }
                if (j <= i) i..i else i until j
            }
        }
    }
}
