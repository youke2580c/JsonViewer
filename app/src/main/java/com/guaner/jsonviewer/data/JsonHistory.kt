package com.guaner.jsonviewer.data

import android.content.Context

/**
 * 导入历史：最近导入（或加载）过的有效 JSON 文本。
 *
 * 具体存储在 [TextHistoryStore]，上限由设置里的「导入历史条数」决定。
 */
object JsonHistory {

    val store = TextHistoryStore("jsonviewer_history") { AppSettings.historyLimit }

    fun init(context: Context) = store.init(context)

    fun count(): Int = store.count()

    fun entries(): List<String> = store.entries()

    fun add(text: String) = store.add(text)

    fun trimToLimit() = store.trimToLimit()

    fun replaceAll(list: List<String>) = store.replaceAll(list)

    fun clear() = store.clear()

    /** 纯函数（供单测），见 [TextHistoryStore.push] */
    fun push(current: List<String>, text: String, limit: Int): List<String> =
        TextHistoryStore.push(current, text, limit)
}
