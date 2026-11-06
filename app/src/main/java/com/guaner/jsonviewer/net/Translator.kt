package com.guaner.jsonviewer.net

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import java.net.URLEncoder

/**
 * 值翻译：无需任何 API Key，依次尝试两个免费公开接口。
 *
 * - 微软 Edge 翻译：先 GET `edge.microsoft.com/translate/auth` 拿临时 JWT，
 *   再调 `api.cognitive.microsofttranslator.com` 的翻译接口；国内网络可直接访问，作为默认引擎。
 * - 谷歌 `translate_a/single`（client=gtx 公开接口）：作为备用，前一个失败时自动切换。
 *
 * 源文本默认翻成中文；若检测到原文本身就是中文，则自动改翻英文
 * （与常见翻译工具的行为一致，避免「中译中」原样返回）。
 */
object Translator {

    /** 单次送翻的字符上限：超过截断（谷歌接口对请求体长度敏感，取两家的共同安全值） */
    const val MAX_CHARS = 4500

    /** 翻译失败：[message] 汇总了每个引擎各自的失败原因 */
    class TranslateException(message: String) : Exception(message)

    data class Result(
        val text: String,
        val sourceLang: String,
        val targetLang: String,
        val engine: String,
        /** 实际送翻的字符数（值被截断时小于原文长度，调用方据此提示） */
        val sourceLength: Int
    )

    /** Edge 翻译的临时凭证：有效期约 10 分钟，进程内缓存复用 */
    @Volatile private var edgeJwt: String? = null
    @Volatile private var edgeJwtAt = 0L

    /** 依次尝试 Edge -> Google；都失败时抛 [TranslateException] */
    fun translate(text: String): Result {
        val input = text.take(MAX_CHARS)
        if (input.isBlank()) throw TranslateException("没有可翻译的内容")
        val errors = ArrayList<String>()
        try {
            return viaEdge(input)
        } catch (e: Exception) {
            errors.add("微软：${e.message}")
        }
        try {
            return viaGoogle(input)
        } catch (e: Exception) {
            errors.add("谷歌：${e.message}")
        }
        throw TranslateException(errors.joinToString("；"))
    }

    // ---------- 微软 Edge ----------

    private fun viaEdge(input: String): Result {
        val (detected, text) = edgeRequest(input, "zh-CN")
        if (detected.startsWith("zh", true)) {
            val (src, en) = edgeRequest(input, "en")
            return Result(en, src, "en", "微软", input.length)
        }
        return Result(text, detected, "zh-CN", "微软", input.length)
    }

    /** 返回 (检测到的源语言, 翻译文本)；401 时刷新凭证重试一次 */
    private fun edgeRequest(input: String, to: String): Pair<String, String> {
        val resp = edgeTranslateCall(input, to, edgeAuth())
        val final = if (resp.code == 401) {
            edgeJwt = null
            edgeTranslateCall(input, to, edgeAuth())
        } else {
            resp
        }
        if (!final.isHttpOk) {
            throw TranslateException(final.error ?: "翻译服务返回 ${final.code}")
        }
        return parseEdge(final.body)
    }

    @Synchronized
    private fun edgeAuth(): String {
        edgeJwt?.let { cached ->
            if (System.currentTimeMillis() - edgeJwtAt < 8 * 60_000L) return cached
        }
        val resp = HttpEngine.execute(
            HttpRequest(
                method = "GET",
                url = "https://edge.microsoft.com/translate/auth",
                sendReferer = false
            ),
            timeoutMs = 10_000
        )
        val token = resp.body.trim()
        if (!resp.isHttpOk || token.isEmpty()) {
            throw TranslateException(resp.error ?: "获取翻译凭证失败(${resp.code})")
        }
        edgeJwt = token
        edgeJwtAt = System.currentTimeMillis()
        return token
    }

    private fun edgeTranslateCall(input: String, to: String, jwt: String) =
        HttpEngine.execute(
            HttpRequest(
                method = "POST",
                url = "https://api.cognitive.microsofttranslator.com/translate?api-version=3.0&to=$to",
                headers = listOf(HttpHeader("Authorization", "Bearer $jwt")),
                body = "[{\"Text\":\"${jsonEscape(input)}\"}]",
                contentType = "application/json; charset=utf-8",
                sendReferer = false
            )
        )

    /**
     * 解析微软翻译响应：
     * `[{"detectedLanguage":{"language":"en","score":1.0},"translations":[{"text":"你好","to":"zh-CN"}]}]`
     * 返回 (检测语言, 全部 translations.text 顺序拼接)。
     */
    private fun parseEdge(body: String): Pair<String, String> {
        var detected = ""
        val sb = StringBuilder()
        try {
            JsonFactory().createParser(body).use { p ->
                if (p.nextToken() != JsonToken.START_ARRAY) throw TranslateException("翻译结果格式异常")
                if (p.nextToken() != JsonToken.START_OBJECT) throw TranslateException("翻译结果格式异常")
                while (p.nextToken() == JsonToken.FIELD_NAME) {
                    when (p.currentName) {
                        "detectedLanguage" -> {
                            if (p.nextToken() == JsonToken.START_OBJECT) {
                                while (p.nextToken() != JsonToken.END_OBJECT) {
                                    if (p.currentToken == JsonToken.FIELD_NAME && p.currentName == "language") {
                                        p.nextToken()
                                        detected = p.valueAsString.orEmpty()
                                    }
                                }
                            }
                        }
                        "translations" -> {
                            if (p.nextToken() == JsonToken.START_ARRAY) {
                                while (p.nextToken() == JsonToken.START_OBJECT) {
                                    while (p.nextToken() != JsonToken.END_OBJECT) {
                                        if (p.currentToken == JsonToken.FIELD_NAME && p.currentName == "text") {
                                            p.nextToken()
                                            sb.append(p.valueAsString.orEmpty())
                                        }
                                    }
                                }
                            }
                        }
                        else -> skipValue(p)
                    }
                }
            }
        } catch (e: TranslateException) {
            throw e
        } catch (e: Exception) {
            throw TranslateException("翻译结果解析失败")
        }
        val text = sb.toString()
        if (text.isEmpty()) throw TranslateException("翻译结果为空")
        return detected to text
    }

    // ---------- 谷歌 ----------

    private fun viaGoogle(input: String): Result {
        val (detected, text) = googleRequest(input, "zh-CN")
        if (detected.startsWith("zh", true)) {
            val (src, en) = googleRequest(input, "en")
            return Result(en, src, "en", "谷歌", input.length)
        }
        return Result(text, detected, "zh-CN", "谷歌", input.length)
    }

    private fun googleRequest(input: String, to: String): Pair<String, String> {
        val form = "client=gtx&sl=auto&tl=$to&dt=t&q=" + URLEncoder.encode(input, "UTF-8")
        val resp = HttpEngine.execute(
            HttpRequest(
                method = "POST",
                url = "https://translate.googleapis.com/translate_a/single",
                body = form,
                contentType = "application/x-www-form-urlencoded; charset=utf-8",
                sendReferer = false
            ),
            timeoutMs = 15_000
        )
        if (!resp.isHttpOk) {
            throw TranslateException(resp.error ?: "翻译服务返回 ${resp.code}")
        }
        return parseGoogle(resp.body)
    }

    /**
     * 解析谷歌 translate_a/single 响应：
     * `[[["你好","hello",null,null,10]],null,"en",[...]]`
     * 顶层元素 0 是句子段数组（每段 `[译文, 原文, null, null, 置信度]`，译文为段内第一个字符串），
     * 顶层元素 2 是检测到的源语言。
     */
    private fun parseGoogle(body: String): Pair<String, String> {
        var detected = ""
        val sb = StringBuilder()
        try {
            JsonFactory().createParser(body).use { p ->
                if (p.nextToken() != JsonToken.START_ARRAY) throw TranslateException("翻译结果格式异常")
                var depth = 1
                var top = -1
                var firstOfString = false
                while (true) {
                    val t = p.nextToken() ?: break
                    when (t) {
                        JsonToken.START_ARRAY -> {
                            if (depth == 1) top++
                            depth++
                            if (depth == 3 && top == 0) firstOfString = true
                        }
                        JsonToken.END_ARRAY -> depth--
                        JsonToken.VALUE_STRING -> {
                            if (depth == 1) top++
                            if (top == 0 && depth == 3 && firstOfString) {
                                sb.append(p.valueAsString.orEmpty())
                                firstOfString = false
                            } else if (top == 2 && depth == 1) {
                                detected = p.valueAsString.orEmpty()
                            }
                        }
                        else -> if (depth == 1) top++
                    }
                }
            }
        } catch (e: TranslateException) {
            throw e
        } catch (e: Exception) {
            throw TranslateException("翻译结果解析失败")
        }
        val text = sb.toString()
        if (text.isEmpty()) throw TranslateException("翻译结果为空")
        return detected to text
    }

    // ---------- 公共小工具 ----------

    /** 当前在 FIELD_NAME 上时跳过其值（容器或标量） */
    private fun skipValue(p: com.fasterxml.jackson.core.JsonParser) {
        when (p.nextToken()) {
            JsonToken.START_OBJECT, JsonToken.START_ARRAY -> p.skipChildren()
            else -> {}
        }
    }

    /** 手写 JSON 字符串转义（工程未引入 jackson-databind，体量也不值得引） */
    private fun jsonEscape(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '\\' -> sb.append("\\\\")
                ch == '"' -> sb.append("\\\"")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch < ' ' -> sb.append("\\u%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }
}
