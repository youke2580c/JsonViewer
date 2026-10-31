package com.guaner.jsonviewer.data

import android.content.Context
import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.JsonSerializer
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.ParseResult
import com.guaner.jsonviewer.net.HttpHeader

/**
 * 链接历史：网络请求面板里访问成功的请求（最新在前）。
 *
 * 每条记录不只是链接，还带着**当时的请求方法、请求头与请求体**，
 * 点历史即可一键还原整次请求；条数上限用设置里的 [AppSettings.urlHistoryLimit]。
 *
 * 存储复用 [TextHistoryStore]（一个条目一段文本），条目内容是本文件 [encode]
 * 生成的 JSON。旧版本只存了纯 URL，[decode] 会把解析失败的内容当成「只有链接」的记录。
 */
object UrlHistory {

    /** 一条历史记录 */
    data class Entry(
        val url: String,
        val method: String = "GET",
        val headers: List<HttpHeader> = emptyList(),
        val body: String = "",
        /** 当时选中的内置 UA 名称（[UaProfiles.NONE] 表示不携带）；空串 = 旧记录，用当前设置 */
        val uaName: String = "",
        /** 当时是否自动补 Referer */
        val sendReferer: Boolean = true,
        /** 当时的 Content-Type 选择：auto / 空串（不发送） / 具体值 */
        val contentType: String = AppSettings.CT_AUTO,
        /** 当时的显式编码；空串 = 自动 */
        val charset: String = "",
        /** 当时的 URL 转义策略：auto / none / all */
        val urlEncode: String = "auto"
    ) {
        /** 历史列表里的一行：`POST https://… · 3 头 · 有 body` */
        fun label(): String = buildString {
            append(method.uppercase())
            append(' ')
            append(url)
            val count = headers.count { it.name.isNotBlank() && it.value.isNotBlank() }
            if (count > 0) append(" · $count 头")
            if (body.isNotBlank()) append(" · 有 body")
        }
    }

    val store = TextHistoryStore("jsonviewer_url_history") { AppSettings.urlHistoryLimit }

    fun init(context: Context) = store.init(context)

    /** 全部历史（已解码），最新在前 */
    fun entries(): List<Entry> = store.entries().map { decode(it) }

    /** 记录一次成功请求；同名链接的旧记录会被新记录覆盖 */
    fun add(entry: Entry) {
        if (entry.url.isBlank()) return
        store.add(encode(entry))
    }

    fun trimToLimit() = store.trimToLimit()

    fun clear() = store.clear()

    /** 序列化成单行 JSON（键固定，方便 decode 与旧数据兼容） */
    fun encode(entry: Entry): String = buildString {
        append('{')
        append("\"url\":").append(JsonSerializer.quote(entry.url))
        append(",\"method\":").append(JsonSerializer.quote(entry.method.uppercase()))
        append(",\"body\":").append(JsonSerializer.quote(entry.body))
        append(",\"uaName\":").append(JsonSerializer.quote(entry.uaName))
        append(",\"sendReferer\":").append(entry.sendReferer)
        append(",\"contentType\":").append(JsonSerializer.quote(entry.contentType))
        append(",\"charset\":").append(JsonSerializer.quote(entry.charset))
        append(",\"urlEncode\":").append(JsonSerializer.quote(entry.urlEncode))
        append(",\"headers\":[")
        entry.headers.forEachIndexed { index, header ->
            if (index > 0) append(',')
            append("{\"name\":").append(JsonSerializer.quote(header.name))
            append(",\"value\":").append(JsonSerializer.quote(header.value))
            append('}')
        }
        append("]}")
    }

    /** 反序列化；不是本格式（例如旧版本存的纯 URL）时退化成只有链接的记录 */
    fun decode(text: String): Entry {
        val raw = text.trim()
        val parsed = JsonParser.parse(raw)
        if (parsed !is ParseResult.Success || parsed.root.type != NodeType.OBJECT) {
            return Entry(raw)
        }
        val root = parsed.root
        fun field(key: String): String? =
            root.children.firstOrNull { it.key == key }?.scalar

        val headers = root.children
            .firstOrNull { it.key == "headers" && it.type == NodeType.ARRAY }
            ?.children
            ?.mapNotNull { node ->
                val name = node.children.firstOrNull { it.key == "name" }?.scalar.orEmpty()
                val value = node.children.firstOrNull { it.key == "value" }?.scalar.orEmpty()
                if (name.isBlank()) null else HttpHeader(name, value)
            }
            .orEmpty()

        val method = (field("method") ?: "GET").uppercase()
        return Entry(
            url = field("url") ?: raw,
            method = if (method == "POST" || method == "GET") method else "GET",
            headers = headers,
            body = field("body").orEmpty(),
            // 旧记录没有这些键：uaName 留空表示「沿用当前设置」，其余取默认值
            uaName = field("uaName").orEmpty(),
            sendReferer = field("sendReferer") != "false",
            contentType = field("contentType") ?: AppSettings.CT_AUTO,
            charset = field("charset").orEmpty(),
            urlEncode = field("urlEncode") ?: "auto"
        )
    }
}
