package com.guaner.jsonviewer.json

import com.fasterxml.jackson.core.JsonFactory

/**
 * JSON **值片段**校验：判断一段文本是否是单个合法 JSON 值（可有前后空白，不允许尾随内容）。
 *
 * 用途：值弹窗里编辑「数字 / 布尔 / null / 对象 / 数组」时，先做一次片段校验，
 * 避免把半截文本写回原文、把整份 JSON 弄坏。
 *
 * 纯 Kotlin + jackson-core 流式解析，不依赖 Android，可直接 JVM 单测。
 */
object JsonValueValidator {

    fun isValid(raw: String?): Boolean {
        if (raw == null) return false
        val text = raw.trim()
        if (text.isEmpty()) return false
        return try {
            JsonFactory().createParser(text).use { p ->
                if (p.nextToken() == null) return false
                // 跳过当前值的所有子节点（对象/数组会一次跳完）
                p.skipChildren()
                // 还有 token 说明是「两个值拼在一起」，视为非法
                p.nextToken() == null
            }
        } catch (e: Exception) {
            false
        }
    }

    /** 合法的 JSON 数字（不接受 NaN / Infinity / 前导 + / 前导 0 等） */
    fun isNumber(raw: String?): Boolean {
        val s = raw?.trim() ?: return false
        return NUMBER.matches(s)
    }

    private val NUMBER = Regex("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d+)?")
}
