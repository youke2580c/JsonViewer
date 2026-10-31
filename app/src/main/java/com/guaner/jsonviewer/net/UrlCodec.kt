package com.guaner.jsonviewer.net

import java.nio.charset.Charset

/** URL 转义策略。发送前按当前 charset 编码字节，三者只在「% 怎么处理」上有区别 */
enum class UrlEncodeMode(val key: String, val label: String) {
    /** 只转义非法字符；已有的 %XX 视为已编码，不重复转 */
    AUTO("auto", "自动"),

    /** 原样发送，一个字符都不动 */
    NONE("none", "不转义"),

    /** 把 % 也当普通字符编码（已编码的内容会被双重编码） */
    ALL("all", "% 也转义");

    companion object {
        fun of(raw: String?): UrlEncodeMode = entries.firstOrNull { it.key == raw } ?: AUTO
    }
}

/**
 * URL 百分号编码。
 *
 * 与 `URLEncoder.encode` 的区别：那是表单编码（空格变 `+`、`/` 也被转义），
 * 这里按 RFC 3986 处理——保留 `? & = # / :` 等结构字符，空格编码成 `%20`，
 * 中文等多字节字符按传入的 [charset] 取字节（GBK 页面就要用 GBK 编）。
 */
object UrlCodec {

    private const val UNRESERVED =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private const val RESERVED = ":/?#[]@!$&'()*+,;="
    private val KEEP = (UNRESERVED + RESERVED).toSet()
    private val HEX = "0123456789ABCDEF".toCharArray()

    fun encode(raw: String, charset: Charset, mode: UrlEncodeMode): String {
        if (mode == UrlEncodeMode.NONE) return raw
        val sb = StringBuilder(raw.length + 16)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val count = Character.charCount(cp)
            val ch = if (count == 1) raw[i] else '\u0000'

            if (ch == '%' && mode == UrlEncodeMode.AUTO && isHexPair(raw, i + 1)) {
                // 已经是 %XX，原样保留
                sb.append(raw, i, i + 3)
                i += 3
                continue
            }
            if (count == 1 && ch in KEEP) {
                sb.append(ch)
            } else {
                for (b in String(Character.toChars(cp)).toByteArray(charset)) {
                    sb.append('%')
                    sb.append(HEX[(b.toInt() shr 4) and 0xF])
                    sb.append(HEX[b.toInt() and 0xF])
                }
            }
            i += count
        }
        return sb.toString()
    }

    private fun isHexPair(text: String, start: Int): Boolean =
        start + 1 < text.length && isHex(text[start]) && isHex(text[start + 1])

    private fun isHex(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'
}
