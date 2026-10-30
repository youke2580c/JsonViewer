package com.guaner.jsonviewer.data

import android.content.Context
import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 通用文本历史存储：最近 N 条字符串，最新在前。
 *
 * 存储：逐条写入独立的 SharedPreferences，key 为 e0..e(n-1)，内容经
 * URL 编码（文本里可能有换行、控制字符等任意内容，不能直接当 value 存）。
 * 重复内容只保留最新位置，不产生重复项。
 *
 * 「导入历史」与「链接历史」各用一个实例，只是 prefs 文件与上限来源不同。
 */
class TextHistoryStore(
    private val prefName: String,
    private val limitProvider: () -> Int
) {

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
    }

    private fun p(): SharedPreferences =
        prefs ?: throw IllegalStateException("TextHistoryStore.init() 未调用")

    private fun limit(): Int = limitProvider().coerceIn(1, 100)

    /** 当前保存的条数 */
    fun count(): Int = p().getInt("count", 0)

    /** 全部历史，最新在前 */
    fun entries(): List<String> {
        val n = count()
        val result = ArrayList<String>(n)
        for (i in 0 until n) {
            val raw = p().getString("e$i", "") ?: ""
            result.add(
                try {
                    URLDecoder.decode(raw, "UTF-8")
                } catch (e: Exception) {
                    ""
                }
            )
        }
        return result
    }

    /** 记录一次导入：新内容置顶、去重、按上限裁剪 */
    fun add(text: String) {
        if (text.isBlank()) return
        save(push(entries(), text, limit()))
    }

    /** 设置条数上限；变小则裁掉最旧的 */
    fun trimToLimit() {
        save(entries().take(limit()))
    }

    /** 用给定列表整体替换当前历史（最新在前；超出上限的部分丢弃） */
    fun replaceAll(list: List<String>) {
        save(list.take(limit()))
    }

    fun clear() {
        val editor = p().edit()
        for (i in 0 until count()) editor.remove("e$i")
        editor.putInt("count", 0).apply()
    }

    private fun save(list: List<String>) {
        val editor = p().edit()
        for (i in 0 until count()) editor.remove("e$i")
        list.forEachIndexed { i, text ->
            editor.putString("e$i", URLEncoder.encode(text, "UTF-8"))
        }
        editor.putInt("count", list.size).apply()
    }

    companion object {
        /**
         * 纯函数（供单测）：在旧历史里压入一条新记录。
         * - 空白内容不入历史；
         * - 相同内容只保留最新位置；
         * - 超过 [limit] 的最旧记录被丢弃（limit 最小收敛到 1）。
         */
        fun push(current: List<String>, text: String, limit: Int): List<String> {
            if (text.isBlank()) return current
            val capped = limit.coerceAtLeast(1)
            val result = ArrayList<String>(capped)
            result.add(text)
            for (old in current) if (old != text && result.size < capped) result.add(old)
            return result
        }
    }
}
