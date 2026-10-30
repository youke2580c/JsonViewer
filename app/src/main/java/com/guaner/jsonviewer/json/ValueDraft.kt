package com.guaner.jsonviewer.json

/**
 * 值编辑的纯逻辑：类型切换时编辑框里该显示什么、保存时该写回什么片段。
 *
 * 抽出来是为了能脱离 Android 做单元测试——这段逻辑一旦写错，
 * 要么把用户的文本冲掉，要么把整份 JSON 写坏。
 */
object ValueDraft {

    /** 选项顺序即值弹窗里 chip 的显示顺序 */
    val options: List<Pair<NodeType, String>> = listOf(
        NodeType.STRING to "字符串",
        NodeType.NUMBER to "数字",
        NodeType.BOOLEAN to "布尔",
        NodeType.NULL to "null",
        NodeType.OBJECT to "对象",
        NodeType.ARRAY to "数组"
    )

    /**
     * 切换类型时把编辑框内容调整成与新类型对得上：
     *
     * - 从 null 切走：占位的 "null" 清空，否则会变成字符串 "null" 或非法数字；
     * - 目标是 null：固定 "null"（输入框随后会被锁住）；
     * - 目标需要内容但当前为空：给个最省事的占位（0 / true / {} / []）；
     * - 其余情况一律保留用户已经敲进去的文本，校验留给保存时做。
     */
    fun coerceText(current: String, from: NodeType, to: NodeType): String {
        var text = current
        if (from == NodeType.NULL && to != NodeType.NULL && text.trim() == "null") text = ""
        val trimmed = text.trim()
        return when (to) {
            NodeType.NULL -> "null"
            NodeType.NUMBER -> if (trimmed.isEmpty()) "0" else text
            NodeType.BOOLEAN -> if (trimmed == "true" || trimmed == "false") text else "true"
            NodeType.OBJECT -> if (trimmed.isEmpty()) "{}" else text
            NodeType.ARRAY -> if (trimmed.isEmpty()) "[]" else text
            NodeType.STRING -> text
        }
    }

    /** 把编辑框内容按目标类型转成可写回原文的 JSON 片段；非法返回 null */
    fun toRawJson(text: String, type: NodeType): String? = when (type) {
        NodeType.STRING -> JsonSerializer.quote(text)
        NodeType.NUMBER -> if (JsonValueValidator.isNumber(text)) text.trim() else null
        NodeType.BOOLEAN -> {
            val t = text.trim()
            if (t == "true" || t == "false") t else null
        }
        NodeType.NULL -> "null"
        NodeType.OBJECT, NodeType.ARRAY ->
            if (JsonValueValidator.isValid(text)) text.trim() else null
    }

    /** 编辑框上方的提示语 */
    fun hint(type: NodeType): String = when (type) {
        NodeType.STRING -> "类型：字符串 · 不用输入引号，保存时自动转义"
        NodeType.NUMBER -> "类型：数字 · 请输入合法数字，如 12.5 / -3"
        NodeType.BOOLEAN -> "类型：布尔 · 只能输入 true 或 false"
        NodeType.NULL -> "类型：null · 该类型没有内容"
        NodeType.OBJECT -> "类型：对象 · 必须是 { } 包起来的合法 JSON"
        NodeType.ARRAY -> "类型：数组 · 必须是 [ ] 包起来的合法 JSON"
    }

    /** 校验失败时的提示语 */
    fun invalidHint(type: NodeType): String = when (type) {
        NodeType.NUMBER -> "不是合法的 JSON 数字"
        NodeType.BOOLEAN -> "只能填 true 或 false"
        NodeType.NULL -> "类型为 null 时不允许有内容"
        NodeType.OBJECT -> "不是合法的 JSON 对象，请检查引号与括号"
        NodeType.ARRAY -> "不是合法的 JSON 数组，请检查引号与括号"
        NodeType.STRING -> "内容不合法"
    }
}
