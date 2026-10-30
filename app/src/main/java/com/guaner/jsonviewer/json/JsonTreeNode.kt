package com.guaner.jsonviewer.json

/** 节点类型 */
enum class NodeType { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

/**
 * JSON 树节点。
 *
 * @param key 对象成员的键名；根节点与数组元素为 null
 * @param scalar 叶子节点的文本值（字符串已反转义）
 * @param path 该节点的 JSONPath，例如 $.store.book[0].title
 */
class TreeNode(
    val key: String?,
    val type: NodeType,
    val scalar: String?,
    val children: MutableList<TreeNode> = ArrayList(),
    val path: String,
    val depth: Int,
    var expanded: Boolean = depth == 0
) {
    val isContainer: Boolean
        get() = type == NodeType.OBJECT || type == NodeType.ARRAY

    /**
     * 表单列表里展示用的「键名/下标」：
     * 对象成员返回键名；数组元素返回形如 [2] 的下标；其余返回 null（可渲染行里不会出现）。
     * 用于让键名处始终有内容，避免右侧三点菜单漂到前面。
     */
    val indexLabel: String?
        get() {
            if (key != null) return key
            val seg = path.substringAfterLast('[')
            if (seg.endsWith(']')) {
                val digits = seg.substring(0, seg.length - 1)
                if (digits.isNotEmpty() && digits.all { it.isDigit() }) return "[$digits]"
            }
            return null
        }

    /** 类型中文标签 */
    val typeLabel: String
        get() = when (type) {
            NodeType.OBJECT -> "对象"
            NodeType.ARRAY -> "数组"
            NodeType.STRING -> "字符串"
            NodeType.NUMBER -> "数字"
            NodeType.BOOLEAN -> "布尔"
            NodeType.NULL -> "null"
        }

    companion object {
        private val IDENT = Regex("[A-Za-z_$][A-Za-z0-9_$]*")

        /** 对象成员的 JSONPath 片段：简单键用 .key，其余用 ['key'] */
        fun childPath(parentPath: String, key: String): String =
            if (IDENT.matches(key)) "$parentPath.$key"
            else "$parentPath['${key.replace("\\", "\\\\").replace("'", "\\'")}']"

        /** 数组元素的 JSONPath 片段 */
        fun indexPath(parentPath: String, index: Int): String = "$parentPath[$index]"

        /** 把 ['a'] 规范成 .a，便于和 JSONPath 引擎输出的路径比对 */
        fun normalizePath(path: String): String =
            Regex("\\['([A-Za-z_$][A-Za-z0-9_$]*)'\\]").replace(path, ".$1")

        /**
         * 把路径切成带前缀的片段：$.a['b c'].d[0] -> [".a", "['b c']", ".d", "[0]"]
         * 点号片段带前导 "."，方括号片段原样保留，便于无歧义地重新拼装。
         */
        fun segmentsOf(path: String): List<String> {
            val segs = ArrayList<String>()
            var i = 1 // 跳过 $
            while (i < path.length) {
                when (path[i]) {
                    '.' -> {
                        val start = i
                        i++
                        while (i < path.length && path[i] != '.' && path[i] != '[') i++
                        if (i > start + 1) segs.add(path.substring(start, i))
                    }
                    '[' -> {
                        val close = path.indexOf(']', i)
                        if (close < 0) return segs
                        segs.add(path.substring(i, close + 1))
                        i = close + 1
                    }
                    else -> i++
                }
            }
            return segs
        }

        /** 面包屑展示：$.store.book[2].title -> ["$", "store", "book[2]", "title"] */
        fun breadcrumbOf(path: String): List<String> {
            val result = ArrayList<String>()
            result.add("$")
            for (seg in segmentsOf(normalizePath(path))) {
                val isNumericIndex =
                    seg.length > 2 && seg.startsWith("[") && seg.endsWith("]") && seg[1].isDigit()
                if (isNumericIndex && result.size > 1) {
                    // 数字下标合并到上一个片段：book + [2] -> book[2]
                    result[result.size - 1] = result[result.size - 1] + seg
                } else {
                    val display =
                        if (seg.startsWith("['") && seg.endsWith("']")) {
                            seg.substring(2, seg.length - 2)
                        } else {
                            seg.removePrefix(".")
                        }
                    result.add(display)
                }
            }
            return result
        }

        /** 所有祖先路径（不含自身），用于搜索命中时自动展开 */
        fun ancestorsOf(path: String): List<String> {
            val segs = segmentsOf(path)
            val result = ArrayList<String>(segs.size)
            var acc = "$"
            for (s in segs) {
                acc += s
                result.add(acc)
            }
            return result.dropLast(1)
        }
    }
}
