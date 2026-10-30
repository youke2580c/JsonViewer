package com.guaner.jsonviewer.json

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option

/** 一次 JSONPath 命中结果 */
data class PathHit(
    val path: String,        // 引擎原始路径，如 $['store']['book'][0]
    val normalized: String,  // 规范路径，如 $.store.book[0]（用于在树中定位）
    val kind: String,        // 类型：对象 / 数组 / 字符串 / 数字 / 布尔 / null
    val preview: String,     // 截断后的值预览
    val full: String         // 完整紧凑 JSON（复制用）
)

sealed class QueryResult {
    class Ok(
        val hits: List<PathHit>,
        val expression: String,
        /** 命中数超过 MAX_HITS 被截断 */
        val truncated: Boolean = false,
        /** 截断前的真实命中总数 */
        val totalCount: Int = hits.size
    ) : QueryResult()
    class Err(val message: String) : QueryResult()
}

/**
 * JSONPath 查询引擎（基于 Jayway JsonPath）。
 * 同时取回「命中的路径」和「命中的值」：路径用于在表单树中定位，值用于展示和复制。
 */
object JsonPathEngine {

    private const val PREVIEW_LIMIT = 120
    private const val MAX_HITS = 5_000
    private const val MAX_FULL_CHARS = 100_000

    fun query(json: String, expr: String): QueryResult {
        val trimmed = expr.trim()
        if (trimmed.isEmpty()) return QueryResult.Err("请输入 JSONPath 表达式，例如 $.store.book[0].title")
        if (json.isBlank()) return QueryResult.Err("当前 JSON 为空，请先输入内容")

        // 先用零内存的流式校验确认 JSON 合法（不建树），
        // 避免把 JSON 错误误报成路径错误，也避免和 Jayway 的模型双重驻留内存
        val jsonError = JsonParser.validateOnly(json)
        if (jsonError != null) {
            return QueryResult.Err(
                "当前 JSON 有语法错误（第 ${jsonError.line} 行第 ${jsonError.column} 列：${jsonError.title}），请先修正"
            )
        }

        return try {
            // 两个 context：一个取命中路径（AS_PATH_LIST），一个按路径逐条取值（每条都是定路径，值原样返回）
            val confValue = Configuration.builder()
                .options(Option.SUPPRESS_EXCEPTIONS)
                .build()
            val confPaths = Configuration.builder()
                .options(Option.SUPPRESS_EXCEPTIONS, Option.AS_PATH_LIST)
                .build()
            val valueCtx = JsonPath.using(confValue).parse(json)
            val pathCtx = JsonPath.using(confPaths).parse(json)

            val pathsRaw: Any? = pathCtx.read(trimmed)
            val paths: List<String> = (pathsRaw as? List<*>)?.map { it.toString() } ?: emptyList()
            val truncated = paths.size > MAX_HITS

            val hits = ArrayList<PathHit>(minOf(paths.size, MAX_HITS))
            for (p in paths.take(MAX_HITS)) {
                val v: Any? = try {
                    valueCtx.read<Any>(p)
                } catch (e: Exception) {
                    null
                }
                val full = compactOf(v, MAX_FULL_CHARS)
                hits.add(
                    PathHit(
                        path = p,
                        normalized = TreeNode.normalizePath(p),
                        kind = kindOf(v),
                        preview = if (full.length > PREVIEW_LIMIT) full.take(PREVIEW_LIMIT) + "…" else full,
                        full = full
                    )
                )
            }
            QueryResult.Ok(hits, trimmed, truncated, paths.size)
        } catch (e: Exception) {
            QueryResult.Err(friendlyError(e))
        }
    }

    private fun kindOf(v: Any?): String = when (v) {
        null -> "null"
        is Map<*, *> -> "对象"
        is List<*> -> "数组"
        is String -> "字符串"
        is Number -> "数字"
        is Boolean -> "布尔"
        else -> v.javaClass.simpleName
    }

    /** 紧凑序列化，带字符预算：大子树只序列化前 budget 个字符，防止 OOM */
    private fun compactOf(v: Any?, budget: Int): String = when (v) {
        null -> "null"
        is String -> quote(v, budget)
        is Number, is Boolean -> v.toString()
        is Map<*, *> -> {
            val sb = StringBuilder("{")
            for ((k, val_) in v.entries) {
                if (sb.length >= budget) break
                if (sb.length > 1) sb.append(',')
                sb.append(quote(k.toString(), budget - sb.length))
                sb.append(':')
                sb.append(compactOf(val_, budget - sb.length))
            }
            sb.append('}').toString()
        }
        is List<*> -> {
            val sb = StringBuilder("[")
            for (item in v) {
                if (sb.length >= budget) break
                if (sb.length > 1) sb.append(',')
                sb.append(compactOf(item, budget - sb.length))
            }
            sb.append(']').toString()
        }
        else -> v.toString()
    }

    private fun quote(s: String, budget: Int): String {
        val cut = if (s.length > budget) s.take(budget.coerceAtLeast(0)) else s
        val body = cut.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$body\""
    }

    private fun friendlyError(e: Exception): String {
        val msg = e.message ?: e.javaClass.simpleName
        return when {
            e is com.jayway.jsonpath.InvalidPathException ->
                "JSONPath 语法错误：$msg"
            e is com.jayway.jsonpath.PathNotFoundException ->
                "路径不存在：没有节点匹配该表达式"
            msg.contains("Filter", true) ->
                "过滤表达式写法有误：$msg"
            msg.contains("parse", true) || msg.contains("JSON", true) ->
                "查询失败：$msg"
            else -> "查询失败：$msg"
        }
    }
}
