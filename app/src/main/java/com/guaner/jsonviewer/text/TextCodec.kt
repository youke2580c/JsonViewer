package com.guaner.jsonviewer.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 底栏「编码」按钮用到的解码工具：Base64、URL 百分号。
 *
 * 为什么不直接用 `java.util.Base64`：minSdk 是 24，`java.util.Base64` 要 26；
 * 而 `android.util.Base64` 在 JVM 单测里是「未实现」的桩，测不了。
 * 解码逻辑本身只有几十行，自己写反而更可控，也能顺手支持 URL-safe 字符与缺省 padding。
 */
object TextCodec {

    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    /** 字符 → 6 bit 值；-1 表示不是 Base64 字符（'-' '_' 按 URL-safe 兼容） */
    private val REVERSE = IntArray(128) { -1 }.also { map ->
        ALPHABET.forEachIndexed { index, c -> map[c.code] = index }
        map['-'.code] = 62
        map['_'.code] = 63
    }

    /**
     * Base64 解码。先丢掉全部空白（含首尾空格、换行、全角空格），
     * 兼容 URL-safe 字符与省略的 `=` 填充。
     *
     * @return 解码后的文本；输入不是合法 Base64，或解出的字节不是 UTF-8 文本时返回 null
     */
    fun decodeBase64(raw: String): String? {
        val bytes = decodeBase64Bytes(raw) ?: return null
        return decodeUtf8(bytes)
    }

    /** 只做 Base64 → 字节。给调用方自己决定怎么解读字节时用。 */
    fun decodeBase64Bytes(raw: String): ByteArray? {
        val sb = StringBuilder(raw.length)
        for (c in raw) {
            if (c.isWhitespace()) continue
            if (c == '=') continue
            if (c.code >= 128 || REVERSE[c.code] < 0) return null
            sb.append(c)
        }
        val len = sb.length
        if (len == 0) return null
        // 单个字符（6 bit）凑不出一个字节，属于残缺输入
        if (len % 4 == 1) return null

        val out = ByteArray(len * 3 / 4)
        var outPos = 0
        var buffer = 0
        var bits = 0
        for (i in 0 until len) {
            buffer = (buffer shl 6) or REVERSE[sb[i].code]
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[outPos++] = ((buffer shr bits) and 0xFF).toByte()
            }
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

    /**
     * URL 百分号解码：把 `%E4%B8%AD` 还成 `中`。
     *
     * 连续的 `%XX` 按 UTF-8 多字节序列合并解码，所以中文、emoji 都能还原；
     * 非法序列（`%ZZ`、`%E4` 后接不到后续字节）原样保留，不会吞字符。
     * `+` 不当作空格——那是表单编码的规则，JSON 正文里的 `+` 应该保持原样。
     */
    fun decodeUrlPercent(text: String): String {
        if (!text.contains('%')) return text
        val n = text.length
        val sb = StringBuilder(n)
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c != '%' || i + 2 >= n) {
                sb.append(c)
                i++
                continue
            }
            val lead = hexByte(text, i)
            if (lead == null) {
                sb.append(c)
                i++
                continue
            }
            val need = utf8Length(lead)
            if (need <= 1) {
                sb.append(decodeUtf8(byteArrayOf(lead.toByte())) ?: "%${text.substring(i + 1, i + 3)}")
                i += 3
                continue
            }
            // 多字节：把紧跟其后的 %XX 一起读出来，凑够 need 个字节
            var cursor = i + 3
            val bytes = ByteArray(need)
            bytes[0] = lead.toByte()
            var ok = true
            for (k in 1 until need) {
                val b = if (cursor + 2 < n && text[cursor] == '%') hexByte(text, cursor) else null
                if (b == null || utf8Length(b) != 0) {
                    ok = false
                    break
                }
                bytes[k] = b.toByte()
                cursor += 3
            }
            val decoded = if (ok) decodeUtf8(bytes) else null
            if (decoded != null) {
                sb.append(decoded)
                i = cursor
            } else {
                // 凑不出合法序列：只吐掉第一个 %XX，剩下的留给下一轮，避免丢内容
                sb.append("%${text.substring(i + 1, i + 3)}")
                i += 3
            }
        }
        return sb.toString()
    }

    /** 读 `%XX` 里的 XX；不是合法十六进制返回 null */
    private fun hexByte(text: String, percent: Int): Int? {
        if (percent + 2 >= text.length) return null
        val hi = Character.digit(text[percent + 1], 16)
        val lo = Character.digit(text[percent + 2], 16)
        if (hi < 0 || lo < 0) return null
        return (hi shl 4) or lo
    }

    /** UTF-8 首字节决定这个字符占几个字节：1=单字节，0=续接字节，n>1=多字节首字节 */
    private fun utf8Length(byte: Int): Int = when {
        byte and 0x80 == 0 -> 1
        byte and 0xE0 == 0xC0 -> 2
        byte and 0xF0 == 0xE0 -> 3
        byte and 0xF8 == 0xF0 -> 4
        else -> 0
    }

    private fun decodeUtf8(bytes: ByteArray): String? {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            null
        }
    }
}
