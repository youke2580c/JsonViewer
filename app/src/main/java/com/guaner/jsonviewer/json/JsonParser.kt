package com.guaner.jsonviewer.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.io.JsonEOFException
import java.io.IOException

/** 一条 JSON 语法错误，精确定位到行/列/字符偏移 */
data class JsonError(
    val offset: Int,      // 0 起始的字符偏移，用于编辑器跳转
    val line: Int,        // 1 起始行号
    val column: Int,      // 1 起始列号
    val title: String,    // 简短标题，如「缺少逗号」
    val detail: String,   // 详细中文说明
    val lineText: String? // 出错行内容（截断）
)

sealed class ParseResult {
    class Success(val root: TreeNode) : ParseResult()
    class Failure(val error: JsonError) : ParseResult()
    object Empty : ParseResult()
}

/**
 * JSON 解析器：一次流式解析同时完成「校验 + 建树 + 错误定位」。
 * 使用 Jackson 流式 API，解析异常自带行号/列号；每次解析都新建 TreeBuilder，避免状态残留。
 */
object JsonParser {

    fun parse(text: String): ParseResult {
        val src = text.removePrefix("\uFEFF")
        if (src.isBlank()) return ParseResult.Empty
        return try {
            val parser = JsonFactory().createParser(src)
            parser.use { p ->
                val root = TreeBuilder().build(p)
                if (root == null) {
                    ParseResult.Failure(
                        JsonError(0, 1, 1, "没有内容", "输入的内容不是有效的 JSON", null)
                    )
                } else {
                    ParseResult.Success(root)
                }
            }
        } catch (e: TooManyNodesException) {
            ParseResult.Failure(
                JsonError(
                    0, 1, 1, "节点过多",
                    "JSON 的节点数超过 ${e.limit} 个，已停止加载（防止内存不足）。" +
                        "可先用「压缩」或分段查看，或提取需要的片段。",
                    null
                )
            )
        } catch (e: TooDeepException) {
            ParseResult.Failure(
                JsonError(
                    0, 1, 1, "嵌套过深",
                    "JSON 的嵌套层数超过 ${e.limit} 层，已停止加载（过深的嵌套会让后续渲染栈溢出）。" +
                        "请检查是否有异常的数据结构。",
                    null
                )
            )
        } catch (e: JsonEOFException) {
            ParseResult.Failure(errorFrom(e, src, true))
        } catch (e: JsonParseException) {
            ParseResult.Failure(errorFrom(e, src, false))
        } catch (e: IOException) {
            ParseResult.Failure(ioError(src, e))
        }
    }

    /**
     * 零内存流式校验：只推进 token、不建树，O(1) 额外内存。
     * 用于 JSONPath 查询前的 JSON 预检——避免再建一棵树和 Jayway 模型双重驻留。
     * 返回 null 表示合法（含空文本）。
     */
    fun validateOnly(text: String): JsonError? {
        val src = text.removePrefix("\uFEFF")
        if (src.isBlank()) return null
        return try {
            val parser = JsonFactory().createParser(src)
            parser.use { p ->
                while (p.nextToken() != null) {
                    // 仅推进，不建树
                }
            }
            null
        } catch (e: JsonEOFException) {
            errorFrom(e, src, true)
        } catch (e: JsonParseException) {
            errorFrom(e, src, false)
        } catch (e: IOException) {
            ioError(src, e)
        }
    }

    /** 字符偏移 -> (行, 列)，均 1 起始 */
    fun lineCol(text: String, offset: Int): Pair<Int, Int> {
        var line = 1
        var col = 1
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) {
            if (text[i] == '\n') {
                line++
                col = 1
            } else {
                col++
            }
        }
        return line to col
    }

    /** 取第 line 行的内容（截断到 120 字符）。逐行扫描，避免 split 产生全篇子串副本 */
    fun lineText(text: String, line: Int): String? {
        if (line < 1) return null
        if (line == 1) return firstLine(text)
        var idx = 0
        var l = 1
        while (l < line) {
            val nl = text.indexOf('\n', idx)
            if (nl < 0) return null
            idx = nl + 1
            l++
        }
        val nl = text.indexOf('\n', idx)
        val raw = (if (nl < 0) text.substring(idx) else text.substring(idx, nl)).trim()
        return if (raw.length > 120) raw.take(117) + "..." else raw
    }

    private fun firstLine(text: String): String? {
        val nl = text.indexOf('\n')
        val raw = (if (nl < 0) text else text.substring(0, nl)).trim()
        return if (raw.length > 120) raw.take(117) + "..." else raw
    }

    // ---------- 内部实现 ----------

    private fun errorFrom(e: JsonParseException, src: String, eof: Boolean): JsonError {
        val offset = e.location.charOffset.coerceIn(0, src.length.toLong()).toInt()
        val (line, col) = lineCol(src, offset)
        val (title, detail) = describe(src, offset, line, e.message ?: "", eof)
        return JsonError(offset, line, col, title, detail, lineText(src, line))
    }

    private fun ioError(src: String, e: IOException): JsonError {
        val (line, col) = lineCol(src, 0)
        return JsonError(0, line, col, "读取失败", "读取 JSON 时发生 IO 错误：${e.message}", null)
    }

    /**
     * 把 Jackson 的英文原始报错翻译成中文友好提示。
     * 策略：先看出错位置的字符；再用「出错整行」兜底（Jackson 的偏移有时偏一两个字符，
     * 且国内用户的高频坑是全角符号、单引号、尾随逗号）；最后匹配 Jackson 原始消息关键词。
     */
    internal fun describe(
        src: String,
        offset: Int,
        line: Int,
        raw: String,
        eof: Boolean
    ): Pair<String, String> {
        val ch = src.getOrNull(offset)
        val prev = previousNonSpace(src, offset)
        val next = nextNonSpace(src, offset)
        val text = lineText(src, line).orEmpty()
        val rawLower = raw.lowercase()
        return when {
            // —— 1. 出错位置字符精确命中 ——
            ch == '\'' -> "单引号" to "JSON 的键和字符串都必须用英文双引号 \" 包围，这里出现了单引号 '"
            ch == '，' -> "中文逗号" to "这里是中文全角逗号「，」，JSON 只支持英文逗号 ,"
            ch == '：' -> "中文冒号" to "这里是中文全角冒号「：」，JSON 只支持英文冒号 :"
            ch == '“' || ch == '”' || ch == '＂' -> "中文引号" to "这里是中文引号，JSON 只支持英文双引号 \""
            ch == '；' -> "中文分号" to "这里是中文分号「；」，JSON 中不需要分号，请删除"
            ch == '、' -> "顿号" to "这里是顿号「、」，JSON 数组元素之间请用英文逗号 , 分隔"
            ch == ',' && (prev == '}' || prev == ']' || next == '}' || next == ']') ->
                "多余的逗号" to "这里多了一个逗号（trailing comma），JSON 不允许最后一个成员/元素后面带逗号，请删除"
            ch == '}' && prev == ',' -> "多余的逗号" to "右花括号前多了一个逗号，请删除"
            ch == ']' && prev == ',' -> "多余的逗号" to "右方括号前多了一个逗号，请删除"

            // —— 2. 出错整行兜底 ——
            text.trimEnd().endsWith(",}") || text.trimEnd().endsWith(",]") ->
                "多余的逗号" to "这一行最后一个成员/元素后面多了一个逗号（trailing comma），JSON 不允许，请删除"
            indexOfUnquoted(text, '，') >= 0 &&
                (rawLower.contains("unexpected character") || rawLower.contains("was expecting comma")) ->
                "中文逗号" to "这一行在引号外包含中文全角逗号「，」，JSON 只支持英文逗号 ,"
            indexOfUnquoted(text, '：') >= 0 && rawLower.contains("unexpected") ->
                "中文冒号" to "这一行在引号外包含中文全角冒号「：」，JSON 只支持英文冒号 :"
            indexOfUnquoted(text, '\'') >= 0 && rawLower.contains("unexpected character") ->
                "单引号" to "这一行包含引号外的单引号 '，JSON 的键和字符串必须用英文双引号 \" 包围"
            indexOfUnquoted(text, '“') >= 0 || indexOfUnquoted(text, '”') >= 0 ->
                "中文引号" to "这一行包含中文引号，JSON 只支持英文双引号 \""

            // —— 3. Jackson 原始消息关键词 ——
            eof || rawLower.contains("end-of-input") ->
                "未闭合" to "JSON 在这里意外结束，通常是有引号、花括号 {} 或方括号 [] 没有闭合"
            rawLower.contains("was expecting comma to separate") ->
                "缺少逗号" to "两个数组成员之间缺少英文逗号 , 分隔"
            rawLower.contains("was expecting comma") ->
                "缺少逗号" to "这里需要一个英文逗号 , 来分隔前后内容"
            rawLower.contains("was expecting colon") ->
                "缺少冒号" to "键名后面缺少英文冒号 :"
            rawLower.contains("was expecting quote-quoted name") || rawLower.contains("was expecting a name") ->
                "键名未加引号" to "对象的键名必须用英文双引号包围，例如 \"name\": 1"
            rawLower.contains("was expecting a value") ->
                "缺少值" to "冒号后面需要跟一个值（字符串、数字、true/false/null、对象或数组）"
            rawLower.startsWith("unrecognized token") ->
                "无法识别的内容" to "这里有无法识别的内容「${tokenAt(src, offset)}」，可能有多余字符、拼写错误（true/false/null）或缺少引号"
            rawLower.contains("invalid utf-8") -> "编码错误" to "文本包含无效的 UTF-8 字节，请检查文件编码"
            else -> "语法错误" to "JSON 语法错误：$raw"
        }
    }

    /** 在引号外查找目标字符（字符串值内部的全角符号不算错误） */
    private fun indexOfUnquoted(line: String, target: Char): Int {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\\' && inString) {
                i += 2
                continue
            }
            if (c == '"') inString = !inString
            else if (!inString && c == target) return i
            i++
        }
        return -1
    }

    private fun tokenAt(src: String, offset: Int): String {
        var i = offset
        while (i < src.length && !src[i].isWhitespace()) i++
        val t = src.substring(offset.coerceIn(0, src.length), i.coerceAtMost(src.length))
        return if (t.length > 20) t.take(20) + "..." else t
    }

    private fun previousNonSpace(src: String, offset: Int): Char? {
        var i = offset - 1
        while (i >= 0 && src[i].isWhitespace()) i--
        return src.getOrNull(i)
    }

    private fun nextNonSpace(src: String, offset: Int): Char? {
        var i = offset + 1
        while (i < src.length && src[i].isWhitespace()) i++
        return src.getOrNull(i)
    }
}

/** 流式建树：一边校验一边把 JSON 转成 TreeNode。每次解析新建实例，杜绝跨次解析的状态残留。 */
private class TreeBuilder {

    private val stack = ArrayDeque<TreeNode>()
    private var root: TreeNode? = null
    private var pendingKey: String? = null
    private var nodeCount = 0

    private fun newNode(
        key: String?, type: NodeType, scalar: String?, parent: TreeNode?
    ): TreeNode {
        // 节点数硬上限：紧凑 JSON 能以极小字符量产生巨量节点，不限必 OOM
        if (++nodeCount > JsonLimits.MAX_NODES) throw TooManyNodesException(JsonLimits.MAX_NODES)
        // 嵌套硬上限：建树是迭代的，但建索引/序列化/渲染都是递归，层数太多必栈溢出
        val depth = (parent?.depth ?: -1) + 1
        if (depth > JsonLimits.MAX_DEPTH) throw TooDeepException(JsonLimits.MAX_DEPTH)
        val path = when {
            parent == null -> "$"
            parent.type == NodeType.ARRAY -> TreeNode.indexPath(parent.path, parent.children.size)
            else -> TreeNode.childPath(parent.path, key ?: "")
        }
        return TreeNode(
            key = key,
            type = type,
            scalar = scalar,
            path = path,
            depth = depth
        )
    }

    fun build(p: JsonParser): TreeNode? {
        var token = p.nextToken()
        while (token != null) {
            when (token) {
                JsonToken.START_OBJECT -> open(NodeType.OBJECT)
                JsonToken.START_ARRAY -> open(NodeType.ARRAY)
                JsonToken.FIELD_NAME -> pendingKey = p.currentName()
                JsonToken.VALUE_STRING -> leaf(NodeType.STRING, p.text)
                JsonToken.VALUE_NUMBER_INT, JsonToken.VALUE_NUMBER_FLOAT ->
                    leaf(NodeType.NUMBER, p.text)
                JsonToken.VALUE_TRUE -> leaf(NodeType.BOOLEAN, "true")
                JsonToken.VALUE_FALSE -> leaf(NodeType.BOOLEAN, "false")
                JsonToken.VALUE_NULL -> leaf(NodeType.NULL, "null")
                JsonToken.END_OBJECT, JsonToken.END_ARRAY ->
                    if (stack.isNotEmpty()) stack.removeLast()
                else -> Unit
            }
            token = p.nextToken()
        }
        return root
    }

    private fun open(type: NodeType) {
        val parent = stack.lastOrNull()
        val key = if (parent?.type == NodeType.OBJECT) pendingKey else null
        pendingKey = null
        val node = newNode(key, type, null, parent)
        attach(parent, node)
        stack.addLast(node)
    }

    private fun leaf(type: NodeType, value: String) {
        val parent = stack.lastOrNull()
        val key = if (parent?.type == NodeType.OBJECT) pendingKey else null
        pendingKey = null
        val node = newNode(key, type, value, parent)
        attach(parent, node)
    }

    private fun attach(parent: TreeNode?, node: TreeNode) {
        if (parent != null) parent.children.add(node) else root = node
    }
}
