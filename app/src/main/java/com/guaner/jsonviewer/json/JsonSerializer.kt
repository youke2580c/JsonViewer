package com.guaner.jsonviewer.json

/** JSON 序列化：美化 / 压缩 / Unicode 转义，直接基于 TreeNode，不依赖额外库 */
object JsonSerializer {

    /** 单次序列化的输出上限：超过就放弃，避免在堆上拼出几十 MB 字符串直接 OOM */
    private const val MAX_OUTPUT_CHARS = 8_000_000

    /** 递归深度上限（解析阶段已限制，这里是对手工构造/异常数据的兜底） */
    private const val MAX_LEVEL = JsonLimits.MAX_DEPTH + 50

    /** 美化（默认 2 空格缩进） */
    fun pretty(node: TreeNode, unit: String = "  "): String {
        val sb = StringBuilder()
        write(node, sb, pretty = true, unit = unit, level = 0, escapeNonAscii = false)
        return sb.toString()
    }

    /** 压缩成单行 */
    fun minify(node: TreeNode): String {
        val sb = StringBuilder()
        write(node, sb, pretty = false, unit = "", level = 0, escapeNonAscii = false)
        return sb.toString()
    }

    /** 把非 ASCII 字符转成 \uXXXX 转义（便于嵌入不支持中文的环境） */
    fun escapeUnicode(node: TreeNode): String {
        val sb = StringBuilder()
        write(node, sb, pretty = true, unit = "  ", level = 0, escapeNonAscii = true)
        return sb.toString()
    }

    /** 任意子树的紧凑 JSON（复制子树用） */
    fun compactOf(node: TreeNode): String {
        val sb = StringBuilder()
        write(node, sb, pretty = false, unit = "", level = 0, escapeNonAscii = false)
        return sb.toString()
    }

    /**
     * 原文转义：把非 ASCII 字符（中文、emoji 等）换成 \uXXXX。
     *
     * 直接对原始文本操作（不重新序列化），只动 >=0x80 的字符，
     * 引号、换行等结构性字符原样保留，因此结果仍是同一份 JSON，只是少了可读性。
     */
    fun escapeUnicodeText(text: String): String {
        var needEscape = false
        for (c in text) {
            if (c.code >= 0x80) {
                needEscape = true
                break
            }
        }
        if (!needEscape) return text
        val sb = StringBuilder(text.length + 16)
        for (c in text) {
            if (c.code < 0x80) {
                sb.append(c)
            } else {
                sb.append("\\u")
                sb.append(c.code.toString(16).padStart(4, '0'))
            }
        }
        return sb.toString()
    }

    /**
     * 还原：把 \uXXXX 转回对应字符（含代理对，两个 \\uD83D\\uDE00 会合成一个 emoji）。
     *
     * 只处理 \uXXXX——\n、\" 这类转义不能动：它们在 JSON 字符串里是合法转义，
     * 还原成真实换行/引号反而会把 JSON 弄坏。非法序列（\uZZZZ）原样保留。
     */
    fun unescapeUnicode(text: String): String {
        if (!text.contains("\\u")) return text
        val sb = StringBuilder(text.length)
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c == '\\' && i + 5 < n && text[i + 1] == 'u') {
                val code = text.substring(i + 2, i + 6).toIntOrNull(16)
                if (code != null && code in 0..0xFFFF) {
                    sb.append(code.toChar())
                    i += 6
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * 把一个「纯文本」转成合法的 JSON 字符串字面量（含首尾引号、转义控制字符）。
     *
     * 值弹窗里编辑字符串时，用户看到/输入的是不带引号的原文，
     * 写回原文前必须重新加引号并转义，否则会出现 `{"a":abc}` 这种非法 JSON。
     */
    fun quote(text: String): String {
        val sb = StringBuilder(text.length + 2)
        sb.append('"')
        for (c in text) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ') {
                    sb.append("\\u")
                    sb.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun write(
        node: TreeNode,
        sb: StringBuilder,
        pretty: Boolean,
        unit: String,
        level: Int,
        escapeNonAscii: Boolean
    ) {
        if (level > MAX_LEVEL) {
            // 深到不该出现的程度：用 null 占位，保证输出仍是合法 JSON 且不会栈溢出
            sb.append("null")
            return
        }
        if (sb.length > MAX_OUTPUT_CHARS) {
            throw IllegalStateException("内容过大，无法一次性生成（超过 ${MAX_OUTPUT_CHARS / 1_000_000}M 字符）")
        }
        when (node.type) {
            NodeType.OBJECT -> {
                if (node.children.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append('{')
                node.children.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    if (pretty) {
                        sb.append('\n').append(unit.repeat(level + 1))
                    }
                    sb.append(quote(c.key ?: "", escapeNonAscii)).append(':')
                    if (pretty) sb.append(' ')
                    write(c, sb, pretty, unit, level + 1, escapeNonAscii)
                }
                if (pretty) sb.append('\n').append(unit.repeat(level))
                sb.append('}')
            }
            NodeType.ARRAY -> {
                if (node.children.isEmpty()) {
                    sb.append("[]")
                    return
                }
                sb.append('[')
                node.children.forEachIndexed { i, c ->
                    if (i > 0) sb.append(',')
                    if (pretty) {
                        sb.append('\n').append(unit.repeat(level + 1))
                    }
                    write(c, sb, pretty, unit, level + 1, escapeNonAscii)
                }
                if (pretty) sb.append('\n').append(unit.repeat(level))
                sb.append(']')
            }
            NodeType.STRING -> sb.append(quote(node.scalar ?: "", escapeNonAscii))
            else -> sb.append(node.scalar ?: "null")
        }
    }

    private fun quote(s: String, escapeNonAscii: Boolean): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c.code < 0x20) {
                    sb.append("\\u%04x".format(c.code))
                } else if (escapeNonAscii && c.code > 0x7E) {
                    sb.append("\\u%04x".format(c.code))
                } else {
                    sb.append(c)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }
}
