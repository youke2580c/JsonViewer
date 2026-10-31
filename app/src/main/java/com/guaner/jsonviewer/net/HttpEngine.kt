package com.guaner.jsonviewer.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/** 一条请求头（允许重复同名，如多个 Cookie / Set-Cookie） */
data class HttpHeader(val name: String, val value: String)

data class HttpRequest(
    val method: String,
    val url: String,
    val headers: List<HttpHeader> = emptyList(),
    val body: String? = null,
    /**
     * 用户没填 `User-Agent` 时用它；[null] 表示这次请求**不携带**内置 UA
     * （部分接口要求完全不带 UA，或由服务端按 IP 识别）。
     */
    val userAgent: String? = HttpEngine.MOBILE_UA,
    /** 用户没填 `Referer` 时是否补上「链接的站点根」 */
    val sendReferer: Boolean = true,
    /**
     * 显式指定的 `Content-Type`：[null] 按请求体形态自动推断，
     * 空串表示**不发送** Content-Type，其余原样发送。
     * 用户在请求头里手动填了 Content-Type 时以用户的为准。
     */
    val contentType: String? = null,
    /** 显式编码（utf-8 / gbk…）：同时用于 URL 转义、请求体字节与响应解码；[null] 自动 */
    val charset: String? = null,
    /** URL 转义策略 */
    val urlEncode: UrlEncodeMode = UrlEncodeMode.AUTO
)

/**
 * 一次请求的结果。[error] 非空表示请求根本没完成（地址非法 / 网络不可达 / 超时）。
 *
 * [body] 已按响应头里的 charset 解码（缺省 UTF-8），并自动解 gzip。
 */
data class HttpResponse(
    val code: Int,
    val message: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    val durationMs: Long,
    val byteSize: Int,
    val error: String? = null
) {
    val isHttpOk: Boolean get() = error == null && code in 200..299
}

/**
 * 极简 HTTP 客户端：只依赖 JDK 的 HttpURLConnection，无第三方库、无额外权限声明
 * （联网只需 INTERNET 权限）。
 *
 * 设计目标：把「用户在面板里填的东西」尽可能原样发出去——
 * 自定义头全部透传（含 Referer / User-Agent / Cookie / Origin 等），
 * 只有连接层自己管理的头（Host、Content-Length 等）会被跳过。
 */
object HttpEngine {

    /** 内置 UA 之一：常见 Android 手机浏览器（默认） */
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** 内置 UA 之一：桌面版 Chrome（部分站点会按 UA 返回不同的页面/接口） */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** 兼容旧引用：默认就是手机 UA */
    const val DEFAULT_UA = MOBILE_UA

    /**
     * 默认 Referer：当前链接的站点根（`https://host[:port]/`）。
     * 不少站点会校验来源，缺 Referer 直接 403；用户自己填了就用用户的。
     */
    fun originOf(url: URL): String = "${url.protocol}://${url.authority}/"

    /** 没填 Content-Type 时按正文形态推断，正文本身不做任何改写 */
    fun guessContentType(body: String, charset: java.nio.charset.Charset = Charsets.UTF_8): String {
        val tail = "; charset=" + charset.name().lowercase()
        val head = body.trimStart()
        if (head.startsWith("{") || head.startsWith("[")) {
            return "application/json$tail"
        }
        if (looksLikeForm(body)) return "application/x-www-form-urlencoded$tail"
        return "text/plain$tail"
    }

    /** 形如 `keyword=我的&t=1`：每段都有非空键名 */
    private fun looksLikeForm(body: String): Boolean {
        val text = body.trim()
        if (text.isEmpty() || text.startsWith("<")) return false
        val parts = text.split('&').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return false
        return parts.all { it.indexOf('=') > 0 }
    }

    /** 这些头由 HttpURLConnection 自己管，手动设置会被忽略甚至抛异常 */
    private val RESTRICTED = setOf(
        "host", "content-length", "connection", "transfer-encoding", "expect", "upgrade"
    )

    private const val MAX_BYTES = 8 * 1024 * 1024

    fun execute(request: HttpRequest, timeoutMs: Int = 15_000): HttpResponse {
        val started = System.currentTimeMillis()
        val rawUrl = request.url.trim()
        if (rawUrl.isEmpty()) {
            return fail("请输入链接", started)
        }
        val explicitCharset = request.charset?.trim()?.takeIf { it.isNotEmpty() }?.let { name ->
            try {
                java.nio.charset.Charset.forName(name)
            } catch (e: Exception) {
                return fail("不支持的编码：$name", started)
            }
        }
        val sendCharset = explicitCharset ?: Charsets.UTF_8
        val url = try {
            // 先补协议再转义：保留 ? & = 等结构字符，中文按 charset 编成 %XX
            URL(UrlCodec.encode(normalizeUrl(rawUrl), sendCharset, request.urlEncode))
        } catch (e: Exception) {
            return fail("链接格式不正确，可尝试换一种 URL 转义方式", started)
        }
        if (url.protocol != "http" && url.protocol != "https") {
            return fail("只支持 http / https 链接", started)
        }

        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = request.method.uppercase()
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                useCaches = false
            }

            // 用户没填的默认头才补，填了就按用户的原样发。
            // 值为空的行视为「没填」（界面上常留一行空的 Referer 占位）。
            var hasUa = false
            var hasAccept = false
            var hasReferer = false
            var hasContentType = false
            for (h in request.headers) {
                val name = h.name.trim()
                val value = h.value.trim()
                if (name.isEmpty() || value.isEmpty()) continue
                if (name.lowercase() in RESTRICTED) continue
                if (name.equals("User-Agent", true)) hasUa = true
                if (name.equals("Accept", true)) hasAccept = true
                if (name.equals("Referer", true)) hasReferer = true
                if (name.equals("Content-Type", true)) hasContentType = true
                try {
                    conn.setRequestProperty(name, value)
                } catch (ignored: Exception) {
                    // 个别非法头名会被 JDK 拒绝，跳过即可，不影响其余头
                }
            }
            // 三个默认头都可以关掉：面板里选「不携带 UA / 不携带 Referer」时传 null / false。
            // 注意必须显式写一个空值——连接层（JDK 的 Java/x.y、Android 的 Dalvik/x.y）
            // 在「压根没设过这个头」时会自己补一个默认 UA。
            if (!hasUa) conn.setRequestProperty("User-Agent", request.userAgent.orEmpty())
            if (!hasAccept) conn.setRequestProperty("Accept", "*/*")
            if (!hasReferer && request.sendReferer) {
                conn.setRequestProperty("Referer", originOf(url))
            }
            if (conn.getRequestProperty("Accept-Encoding") == null) {
                // 明确要 gzip，下面自己解压；比依赖 JDK 的透明解压更可控
                conn.setRequestProperty("Accept-Encoding", "gzip")
            }

            val method = conn.requestMethod
            val body = request.body
            if (body != null && method != "GET" && method != "HEAD") {
                // Content-Type 优先级：请求头里手动填的 > 面板里显式指定的 > 按内容猜。
                // 正文本身始终原样发送，不做编码或改写。
                if (!hasContentType) {
                    val chosen = request.contentType
                    when {
                        chosen == null -> conn.setRequestProperty("Content-Type", guessContentType(body, sendCharset))
                        // 明确要求不带：空值同样要写进请求头，否则连接层会给 POST 补
                        // 一个默认的 application/x-www-form-urlencoded
                        chosen.isBlank() -> conn.setRequestProperty("Content-Type", "")
                        else -> conn.setRequestProperty("Content-Type", chosen)
                    }
                }
                val bytes = body.toByteArray(sendCharset)
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }

            val code = conn.responseCode
            val message = conn.responseMessage.orEmpty()
            val fields = conn.headerFields
                .filterKeys { it != null }
                .flatMap { (k, v) -> (v ?: emptyList()).map { k to it } }

            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val raw = stream?.use { readAll(it) } ?: ByteArray(0)
            val decoded = if (isGzip(fields)) unzip(raw) else raw
            val text = String(decoded, explicitCharset ?: charsetOf(fields))
            return HttpResponse(
                code = code,
                message = message,
                headers = fields,
                body = text,
                durationMs = System.currentTimeMillis() - started,
                byteSize = decoded.size
            )
        } catch (e: Exception) {
            val reason = when (e) {
                is java.net.SocketTimeoutException -> "请求超时"
                is java.net.UnknownHostException -> "无法解析域名，请检查网络或链接"
                is java.net.ConnectException -> "连接失败，请检查网络或链接"
                is javax.net.ssl.SSLException -> "HTTPS 证书校验失败"
                else -> e.message ?: e.javaClass.simpleName
            }
            return fail(reason, started)
        } finally {
            conn?.disconnect()
        }
    }

    /** 没写协议时补 https（绝大多数接口都是 https） */
    fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        return if (s.startsWith("http://", true) || s.startsWith("https://", true)) s
        else "https://$s"
    }

    /** 供调用方把意外错误（Error 等）转成统一的结果对象，而不是抛给 UI 线程 */
    fun failWith(reason: String): HttpResponse = fail(reason, System.currentTimeMillis())

    private fun fail(reason: String, started: Long) = HttpResponse(
        code = 0,
        message = "",
        headers = emptyList(),
        body = "",
        durationMs = System.currentTimeMillis() - started,
        byteSize = 0,
        error = reason
    )

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            val keep = minOf(n, MAX_BYTES - total)
            if (keep > 0) out.write(buf, 0, keep)
            total += keep
            if (total >= MAX_BYTES) break
        }
        return out.toByteArray()
    }

    private fun isGzip(headers: List<Pair<String, String>>): Boolean = headers.any {
        it.first.equals("Content-Encoding", true) && it.second.contains("gzip", true)
    }

    private fun unzip(raw: ByteArray): ByteArray = try {
        if (raw.size < 2 || raw[0] != 0x1f.toByte() || raw[1] != 0x8b.toByte()) raw
        else GZIPInputStream(raw.inputStream()).use { readAll(it) }
    } catch (e: Exception) {
        raw
    }

    /** 从 Content-Type 里取 charset，取不到就 UTF-8 */
    fun charsetOf(headers: List<Pair<String, String>>): java.nio.charset.Charset {
        val ct = headers.firstOrNull { it.first.equals("Content-Type", true) }?.second ?: return Charsets.UTF_8
        val m = Regex("charset\\s*=\\s*\"?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(ct)
            ?: return Charsets.UTF_8
        return try {
            java.nio.charset.Charset.forName(m.groupValues[1])
        } catch (e: Exception) {
            Charsets.UTF_8
        }
    }
}
