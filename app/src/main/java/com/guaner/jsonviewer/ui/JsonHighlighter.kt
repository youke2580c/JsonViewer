package com.guaner.jsonviewer.ui

/** 语法高亮的 token 种类 */
enum class TokenKind { KEY, STRING, NUMBER, LITERAL, PUNCT }

data class TokenSpan(val start: Int, val end: Int, val kind: TokenKind)

/**
 * 基于正则的轻量 JSON 语法高亮。
 * 键 = 后面紧跟冒号的带引号字符串；其余按字符串/数字/true/false/null/标点着色。
 */
object JsonHighlighter {

    // 超过该长度跳过整体高亮，保证大文件输入流畅（校验仍然实时进行）
    const val MAX_HIGHLIGHT_CHARS = 400_000

    private val TOKEN = Regex(
        "\"(?:\\\\.|[^\"\\\\])*\"\\s*:" +          // 0: 键（含冒号）
            "|\"(?:\\\\.|[^\"\\\\])*\"" +           // 1: 字符串
            "|-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?" + // 2: 数字
            "|\\b(?:true|false)\\b|\\bnull\\b" +     // 3: 字面量
            "|[{}\\[\\],:]"                          // 4: 标点
    )

    fun findTokens(text: String): List<TokenSpan> {
        if (text.length > MAX_HIGHLIGHT_CHARS) return emptyList()
        val result = ArrayList<TokenSpan>()
        for (m in TOKEN.findAll(text)) {
            val v = m.value
            val kind = when {
                v.endsWith(":") -> TokenKind.KEY
                v.startsWith("\"") -> TokenKind.STRING
                v == "{" || v == "}" || v == "[" || v == "]" || v == "," || v == ":" -> TokenKind.PUNCT
                v == "true" || v == "false" || v == "null" -> TokenKind.LITERAL
                else -> TokenKind.NUMBER
            }
            // KEY 的结束位置需要换算成「绝对下标」：m.value 是相对匹配串的下标，
            // 必须加上 m.range.first 才是相对整段文本的绝对位置，否则非首位的键会触发 start>end。
            val end = if (kind == TokenKind.KEY) {
                m.range.first + m.value.indexOfLast { it != ' ' && it != ':' } + 1
            } else {
                m.range.last + 1
            }
            result.add(TokenSpan(m.range.first, end, kind))
        }
        return result
    }
}
