package com.guaner.jsonviewer.net

import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.JsonSerializer
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.ParseResult
import com.guaner.jsonviewer.json.TreeNode
import java.nio.charset.Charset

/**
 * 从「链接 , {配置}」这种一行文本里拆出来的请求。
 *
 * 常见于抓包工具 / 接口文档的复制结果：
 * ```
 * https://api.example.com/post.json?key=我的,{"method":"POST","body":"a=1","headers":{"X-Token":"…"},"charset":"gbk"}
 * ```
 */
data class RequestRecipe(
    val url: String,
    /** 只认识 GET / POST，其它方法返回 null（保持面板当前选择） */
    val method: String? = null,
    val headers: List<HttpHeader> = emptyList(),
    val body: String? = null,
    /** 编码名（utf-8 / gbk…）；不支持的编码返回 null */
    val charset: String? = null
)

object RequestRecipeParser {

    private val CONFIG_KEYS = setOf("method", "headers", "body", "charset")

    /**
     * 解析一行输入。不是「链接 + 配置」时返回 null，调用方按普通链接处理。
     *
     * 拆分点从右往左找：URL 的查询参数里本来就可能带逗号，只有后面的片段
     * 能解析成对象、且含有配置键时才认。
     */
    fun parse(input: String): RequestRecipe? {
        val text = input.trim()
        if (!text.endsWith("}")) return null

        var comma = text.lastIndexOf(',')
        while (comma > 0) {
            val tail = text.substring(comma + 1).trimStart()
            if (tail.startsWith("{")) {
                val parsed = JsonParser.parse(tail)
                if (parsed is ParseResult.Success && parsed.root.type == NodeType.OBJECT) {
                    val root = parsed.root
                    val keys = root.children.mapNotNull { it.key }
                    if (keys.any { it in CONFIG_KEYS }) {
                        val url = text.substring(0, comma).trim()
                        if (url.isNotEmpty()) return build(url, root)
                    }
                }
            }
            comma = text.lastIndexOf(',', comma - 1)
        }
        return null
    }

    private fun build(url: String, root: TreeNode): RequestRecipe {
        fun field(key: String): TreeNode? = root.children.firstOrNull { it.key == key }

        val method = field("method")?.scalar?.trim()?.uppercase()
            ?.takeIf { it == "GET" || it == "POST" }

        val bodyNode = field("body")
        val body = when {
            bodyNode == null -> null
            bodyNode.type == NodeType.NULL -> null
            bodyNode.isContainer -> JsonSerializer.compactOf(bodyNode)
            else -> bodyNode.scalar.orEmpty()
        }

        val charset = field("charset")?.scalar?.trim()
            ?.takeIf { it.isNotEmpty() && isSupported(it) }

        return RequestRecipe(
            url = url,
            method = method,
            headers = parseHeaders(field("headers")),
            body = body,
            charset = charset
        )
    }

    /**
     * 请求头支持三种写法：
     * `{"A":"1","B":"2"}` / `[{"name":"A","value":"1"}]` / `[["A","1"]]`
     */
    private fun parseHeaders(node: TreeNode?): List<HttpHeader> {
        if (node == null) return emptyList()
        val result = ArrayList<HttpHeader>()
        when (node.type) {
            NodeType.OBJECT -> node.children.forEach { child ->
                val name = child.key?.trim().orEmpty()
                if (name.isEmpty()) return@forEach
                val value = when {
                    child.isContainer -> JsonSerializer.compactOf(child)
                    else -> child.scalar.orEmpty()
                }
                result.add(HttpHeader(name, value))
            }

            NodeType.ARRAY -> node.children.forEach { item ->
                when (item.type) {
                    NodeType.OBJECT -> {
                        val name = item.children.firstOrNull { it.key == "name" }?.scalar.orEmpty()
                        val value = item.children.firstOrNull { it.key == "value" }?.scalar.orEmpty()
                        if (name.isNotBlank()) result.add(HttpHeader(name.trim(), value))
                    }

                    NodeType.ARRAY -> {
                        val name = item.children.getOrNull(0)?.scalar.orEmpty()
                        val value = item.children.getOrNull(1)?.scalar.orEmpty()
                        if (name.isNotBlank()) result.add(HttpHeader(name.trim(), value))
                    }

                    else -> Unit
                }
            }

            else -> Unit
        }
        return result
    }

    private fun isSupported(name: String): Boolean = try {
        Charset.isSupported(name)
    } catch (e: Exception) {
        false
    }
}
