package com.guaner.jsonviewer.data

import android.content.Context
import com.guaner.jsonviewer.json.JsonLimits
import java.io.File

/**
 * 会话记忆：把编辑器里的最后一次内容落盘到应用私有目录，
 * 进程被杀 / 用户误退出后重新打开可以恢复上次的文本。
 *
 * 用文件而不是 SharedPreferences：内容动辄几百 KB 到 2M 字符，
 * SharedPreferences 会在主线程全量读写，且是单文件 XML，容易导致启动卡顿。
 * 写入走「临时文件 + rename」，避免写到一半被杀导致内容损坏。
 */
object SessionStore {

    private const val FILE_NAME = "session_content.json"
    private const val TMP_NAME = "session_content.json.tmp"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** 会话文件的绝对路径（设置页展示用，让用户知道内容存在哪） */
    fun path(context: Context): String = try {
        file(context).absolutePath
    } catch (e: Exception) {
        ""
    }

    /** 已保存内容的字节数；不存在返回 0 */
    fun savedBytes(context: Context): Long = try {
        val f = file(context)
        if (f.exists()) f.length() else 0L
    } catch (e: Exception) {
        0L
    }

    /** 保存内容；空白内容视为「用户主动清空」，直接删文件 */
    fun save(context: Context, text: String) {
        try {
            val target = file(context)
            if (text.isBlank()) {
                if (target.exists()) target.delete()
                return
            }
            val payload = if (text.length > JsonLimits.MAX_INPUT_CHARS) {
                text.take(JsonLimits.MAX_INPUT_CHARS)
            } else {
                text
            }
            val tmp = File(context.filesDir, TMP_NAME)
            tmp.writeText(payload, Charsets.UTF_8)
            if (target.exists() && !target.delete()) {
                // 删不掉就直接覆盖，至少不会丢内容
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            } else if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // 存储失败不能影响编辑（例如磁盘已满）
        }
    }

    /** 读取上次内容；不存在或读取失败返回 null */
    fun load(context: Context): String? = try {
        val f = file(context)
        if (!f.exists() || f.length() == 0L) null
        else f.readText(Charsets.UTF_8).takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    /** 会话文件的字符数上限估算（按 UTF-8 中文 3 字节/字符的常见情况换算，只用于提前放弃超大内容） */
    fun loadSize(context: Context): Long = try {
        val f = file(context)
        if (f.exists()) f.length() / 3 else 0L
    } catch (e: Exception) {
        0L
    }

    fun clear(context: Context) {
        try {
            file(context).delete()
            File(context.filesDir, TMP_NAME).delete()
        } catch (e: Exception) {
            // 忽略
        }
    }
}
