package com.guaner.jsonviewer.net

import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.JsonSerializer
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.ParseResult
import com.guaner.jsonviewer.json.TreeNode

/**
 * 把「JSON 形式的请求头」解析成扁平的 [HttpHeader] 列表，供网络面板一键填入。
 *
 * 支持拷贝粘贴时最常见的几种写法：
 * 1. 对象：`{"Referer":"https://a.com","X-Token":"abc"}`
 * 2. 对象数组（字段名兼容 name/key/header/field 与 value/v）：
 *    `[{"name":"Referer","value":"https://a.com"}]`
 * 3. 二元组数组：`[["Referer","https://a.com"],["Accept","application/json"]]`
 * 4. 字符串数组：`["Referer: https://a.com"]`
 * 5. 兜底：非 JSON 的整段「Name: Value」文本（从开发者工具直接复制时常见）
 *
 * 数组值（`{"Accept":["a/b","c/d"]}`）按 HTTP 语义用 `", "` 合成一行；
 * 同名头（不区分大小写）后出现的覆盖先出现的，位置取首次出现处。
 */
/** 请求头 JSON 的解析结果：成功给出头列表，失败给出可直接展示的说明 */
sealed interface HeaderParseResult {
    /** [skipped] 是无法识别而跳过的条目数 */
    data class Ok(val headers: List<HttpHeader>, val skipped: Int) : HeaderParseResult
    data class Error(val message: String) : HeaderParseResult
}

object HeaderJson {

    /** 请求头名称的合法性：HTTP token（RFC 7230），中文等非法字符会被跳过 */
    private fun validName(s: String): Boolean {
        if (s.isEmpty()) return false
        return s.all { c ->
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' ||
                c in "!#\$%&'*+-.^_`|~"
        }
    }

    /** HTTP 头的值不能含换行，统一压成空格 */
    private fun cleanValue(s: String): String =
        s.replace("\r", " ").replace("\n", " ").trim()

    /** 中间结果：解析出的条目 + 无法识别而被跳过的条目数 */
    private class Entries(val list: List<Pair<String, String>>, val skipped: Int)

    fun parse(raw: String): HeaderParseResult {
        val text = raw.trim()
        if (text.isEmpty()) return HeaderParseResult.Error("内容为空，请粘贴 JSON 形式的请求头")

        if (text.first() == '{' || text.first() == '[') {
            val parsed = JsonParser.parse(text)
            if (parsed is ParseResult.Success) {
                val entries = when (parsed.root.type) {
                    NodeType.OBJECT -> fromObject(parsed.root)
                    NodeType.ARRAY -> fromArray(parsed.root)
                    else -> null
                }
                if (entries == null) {
                    return HeaderParseResult.Error(
                        "JSON 结构不支持：需要对象 {\"名称\":\"值\"}，或 [{\"name\":..,\"value\":..}] 数组"
                    )
                }
                if (entries.list.isEmpty()) {
                    return HeaderParseResult.Error("没有解析到可用的请求头，请检查名称是否为字母、数字或 -_ 等合法字符")
                }
                return dedupe(entries.list, entries.skipped)
            }
            // 不是合法 JSON 时继续走「Name: Value」按行兜底
        }
        return parseLines(text)
    }

    /** 对象形式：每个成员一条头 */
    private fun fromObject(root: TreeNode): Entries {
        val out = ArrayList<Pair<String, String>>()
        var skipped = 0
        for (child in root.children) {
            val name = child.key?.trim().orEmpty()
            val value = valueOf(child)
            if (!validName(name) || value == null) {
                skipped++
                continue
            }
            out.add(name to value)
        }
        return Entries(out, skipped)
    }

    /** 数组形式：对象数组 / 二元组 / 字符串数组 */
    private fun fromArray(root: TreeNode): Entries {
        val out = ArrayList<Pair<String, String>>()
        var skipped = 0
        for (item in root.children) {
            when (item.type) {
                NodeType.OBJECT -> {
                    val name = pick(item, "name", "key", "header", "field")
                    val value = pick(item, "value", "val", "v")
                    if (name != null && value != null && validName(name)) {
                        out.add(name to value)
                    } else {
                        skipped++
                    }
                }
                NodeType.ARRAY -> {
                    val name = item.children.getOrNull(0)?.scalar?.trim().orEmpty()
                    val value = item.children.getOrNull(1)?.scalar
                    if (item.children.size >= 2 && validName(name) && value != null) {
                        out.add(name to cleanValue(value))
                    } else {
                        skipped++
                    }
                }
                NodeType.STRING -> {
                    val pair = splitPair(item.scalar.orEmpty())
                    if (pair.isEmpty()) skipped++ else out.addAll(pair)
                }
                else -> skipped++
            }
        }
        return Entries(out, skipped)
    }

    private fun pick(node: TreeNode, vararg keys: String): String? =
        node.children.firstOrNull { it.key?.lowercase() in keys }?.let { valueOf(it) }

    /** 叶子转成文本：字符串/数字/布尔直接取，数组用 ", " 连接，对象用紧凑 JSON */
    private fun valueOf(node: TreeNode): String? = when (node.type) {
        NodeType.STRING, NodeType.NUMBER, NodeType.BOOLEAN ->
            cleanValue(node.scalar.orEmpty())
        NodeType.NULL -> null
        NodeType.ARRAY -> node.children
            .mapNotNull { valueOf(it) }
            .joinToString(", ")
            .takeIf { it.isNotEmpty() }
            ?.let { cleanValue(it) }
        NodeType.OBJECT -> cleanValue(JsonSerializer.compactOf(node))
    }

    /** 「Name: Value」按行解析，空行与 # /// 注释跳过 */
    private fun parseLines(text: String): HeaderParseResult {
        val out = ArrayList<Pair<String, String>>()
        var skipped = 0
        for (line in text.lines()) {
            val t = line.trim().trimEnd(',')
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("//")) continue
            val idx = t.indexOf(':')
            if (idx <= 0) {
                skipped++
                continue
            }
            val name = t.substring(0, idx).trim().trim('"', '\'', ',')
            val value = t.substring(idx + 1).trim().trim('"', '\'', ',')
            if (validName(name)) out.add(name to cleanValue(value)) else skipped++
        }
        if (out.isEmpty()) {
            return HeaderParseResult.Error(
                "无法识别：请粘贴 {\"Referer\":\"https://…\"} 形式的 JSON，或每行一条「名称: 值」"
            )
        }
        return dedupe(out, skipped)
    }

    /** 把 "Referer: x" 这样的单个字符串拆成一条头 */
    private fun splitPair(s: String): List<Pair<String, String>> {
        val idx = s.indexOf(':')
        if (idx <= 0) return emptyList()
        val name = s.substring(0, idx).trim()
        if (!validName(name)) return emptyList()
        return listOf(name to cleanValue(s.substring(idx + 1)))
    }

    /** 同名（忽略大小写）去重：位置与写法取第一次，值取最后一次 */
    private fun dedupe(
        entries: List<Pair<String, String>>,
        skippedInput: Int = 0
    ): HeaderParseResult.Ok {
        val map = LinkedHashMap<String, HttpHeader>()
        var skipped = skippedInput
        for ((name, value) in entries) {
            if (value.isEmpty()) {
                skipped++
                continue
            }
            val key = name.lowercase()
            val kept = map[key]
            map[key] = if (kept == null) HttpHeader(name, value) else HttpHeader(kept.name, value)
        }
        return HeaderParseResult.Ok(map.values.toList(), skipped)
    }
}
