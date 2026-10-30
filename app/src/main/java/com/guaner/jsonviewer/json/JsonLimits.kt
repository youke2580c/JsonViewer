package com.guaner.jsonviewer.json

/**
 * 全局容量限额：所有「大文本入口」共享同一套上限。
 *
 * 目的：把「直接 OOM / ANR 闪退」变成「友好提示 + 降级」。
 * - MAX_INPUT_CHARS：编辑器可容纳的最大字符数（2M）。超过即截断并提示，
 *   避免 EditText 的 Editable 在超大文本下内存失控。
 * - MAX_FILE_BYTES：导入文件的大小预检上限（16MB）。UTF-8 中文最坏 4 字节/字符，
 *   16MB 最多 4M 字符，仍在 MAX_INPUT_CHARS 的兜底范围内。
 * - MAX_NODES：建树节点数上限（25 万）。紧凑数组 [1,1,1,...] 会以小字符产生巨量节点，
 *   每个 TreeNode 还带全路径字符串，不设限必 OOM。
 * - MAX_DEPTH：嵌套层数上限。建树本身是迭代的，但之后建索引/序列化/渲染都是递归，
 *   几万层嵌套会把线程栈打爆（StackOverflowError，属于 Error，catch Exception 兜不住）。
 */
object JsonLimits {
    const val MAX_INPUT_CHARS = 2_000_000
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_NODES = 250_000

    /** 嵌套层数上限：远超正常业务 JSON（通常 < 30 层），又不至于打爆 1MB 线程栈 */
    const val MAX_DEPTH = 400

    /** 读取文件时的字节读取上限（仅当 statSize 不可信时兜底），与文件上限一致 */
    const val MAX_READ_BYTES = MAX_FILE_BYTES

    fun formatChars(n: Int): String = when {
        n >= 1_000_000 -> "%.1fM 字符".format(n / 1_000_000.0)
        n >= 1_000 -> "%dK 字符".format(n / 1_000)
        else -> "$n 字符"
    }

    fun formatBytes(n: Long): String = when {
        n >= 1024L * 1024 * 1024 -> "%.1fGB".format(n / 1024.0 / 1024 / 1024)
        n >= 1024L * 1024 -> "%.1fMB".format(n / 1024.0 / 1024)
        n >= 1024L -> "%dKB".format(n / 1024)
        else -> "$n 字节"
    }
}

/** 节点数超限时抛出，由 JsonParser 转成友好的 Failure，避免 OOM 后难以定位 */
class TooManyNodesException(val limit: Int) :
    RuntimeException("JSON 节点数超过上限 $limit")

/** 嵌套过深时抛出，同样转成友好 Failure，避免后续递归把栈打爆 */
class TooDeepException(val limit: Int) :
    RuntimeException("JSON 嵌套层数超过上限 $limit")
