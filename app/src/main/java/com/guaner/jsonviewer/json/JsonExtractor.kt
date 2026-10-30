package com.guaner.jsonviewer.json

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken

/**
 * 从混杂文本中提取「第一个可完整解析的 JSON 值」。
 *
 * 典型场景：日志/聊天记录里粘贴出来的内容形如
 * `[00:11.566] {"code":"200","data":{...}}`，
 * 直接找第一个 `{` 或 `[` 会命中时间戳的中括号，导致整段无法解析。
 *
 * 做法：按出现顺序枚举 `{` / `[` 作为候选起点，先做一次「字符串感知的括号配对扫描」
 * 找到该候选的闭合位置，再做一次严格 JSON 校验（Jackson 流式、不建树），
 * 第一个通过校验的子串即为结果。
 *
 * 全部为纯逻辑（无 Android 依赖），可在 JVM 单元测试中直接验证。
 */
object JsonExtractor {

    /** 单次提取最多尝试的候选起点数：防止超长噪声文本里候选爆炸 */
    private const val MAX_CANDIDATES = 64

    /** 扫描预算（字符数）：极端噪声下避免 候选数 × 全文长度 的爆炸式扫描 */
    private const val SCAN_BUDGET = 8_000_000

    /** 提取结果：命中的子串与原文本中的区间 [start, end) */
    data class Result(val text: String, val start: Int, val end: Int)

    /**
     * 找到第一个有效 JSON 值。找不到返回 null（调用方应保留原文，避免误删内容）。
     */
    fun findFirstJson(text: String): Result? {
        if (text.isBlank()) return null
        val n = text.length
        var i = 0
        var tried = 0
        var budget = SCAN_BUDGET
        while (i < n && tried < MAX_CANDIDATES && budget > 0) {
            val c = text[i]
            if (c == '{' || c == '[') {
                tried++
                val end = matchContainer(text, i)
                budget -= if (end > i) end - i else n - i
                if (end > i) {
                    val candidate = text.substring(i, end)
                    budget -= candidate.length
                    if (isValidJson(candidate)) {
                        return Result(candidate, i, end)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * 从 start（必须是 `{` 或 `[`）出发做括号配对扫描：
     * 正确跳过 JSON 字符串内部（含转义）的括号，返回闭合括号之后的下标；不闭合返回 -1。
     */
    fun matchContainer(text: String, start: Int): Int {
        val n = text.length
        if (start >= n) return -1
        val open = text[start]
        if (open != '{' && open != '[') return -1
        var depth = 0
        var i = start
        var inString = false
        var escaped = false
        while (i < n) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    '{', '[' -> depth++
                    '}', ']' -> {
                        depth--
                        if (depth == 0) return i + 1
                        if (depth < 0) return -1
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * 严格校验：整段必须恰好是一个完整的 JSON 值（流式解析、不建树，内存开销极小）。
     */
    fun isValidJson(text: String): Boolean {
        return try {
            val parser = JsonFactory().createParser(text)
            parser.use { p ->
                var depth = 0
                var roots = 0
                while (true) {
                    val t = p.nextToken() ?: break
                    when (t) {
                        JsonToken.START_OBJECT, JsonToken.START_ARRAY -> {
                            depth++
                            if (depth == 1) roots++
                        }
                        JsonToken.END_OBJECT, JsonToken.END_ARRAY -> {
                            depth--
                            if (depth < 0) return false
                        }
                        else -> if (depth == 0) roots++
                    }
                    if (roots > 1) return false
                }
                roots == 1
            }
        } catch (e: Exception) {
            false
        }
    }
}
