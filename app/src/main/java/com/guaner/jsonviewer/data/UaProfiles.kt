package com.guaner.jsonviewer.data

import android.content.Context
import android.content.SharedPreferences
import com.guaner.jsonviewer.net.HttpEngine
import java.net.URLDecoder
import java.net.URLEncoder

/** 一个可选的 User-Agent：[name] 是面板里显示的名字，[value] 是真正发出去的字符串 */
data class UaProfile(val name: String, val value: String, val custom: Boolean = false)

/**
 * 内置 User-Agent 管理。
 *
 * 面板上可以在「手机 UA / 电脑 UA / 自定义项 / 不携带」之间随时切换，选择结果落地到
 * SharedPreferences，下次打开面板还是这一次的 UA。
 *
 * 自定义项用「名称 \t 值」逐行存储（名称与值都经过 URL 编码，值里带换行、竖线、
 * 中文都不会把格式撑破）；读取时任何脏行直接丢弃，不会连累其余项。
 */
object UaProfiles {

    /** 「不携带 UA」的哨兵名（不会与用户起的名字冲突） */
    const val NONE = "__none__"

    private const val PREF = "jsonviewer_settings"
    private const val KEY_CUSTOM = "uaCustom"
    private const val KEY_ACTIVE = "uaActive"

    /** 两个内置选项，开箱即用 */
    val builtin: List<UaProfile> = listOf(
        UaProfile("手机 UA", HttpEngine.MOBILE_UA),
        UaProfile("电脑 UA", HttpEngine.DESKTOP_UA)
    )

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** 用户自定义的 UA（按添加顺序） */
    val custom: List<UaProfile>
        get() = if (::prefs.isInitialized) decode(prefs.getString(KEY_CUSTOM, "").orEmpty())
        else emptyList()

    /** 内置 + 自定义，用于选择列表 */
    val all: List<UaProfile>
        get() = builtin + custom

    /** 当前选中的名称；可能是不存在的自定义项（已删除），读取时用 [active] 兜底 */
    var activeName: String
        get() = if (::prefs.isInitialized) prefs.getString(KEY_ACTIVE, builtin[0].name) ?: builtin[0].name
        else builtin[0].name
        set(v) = if (::prefs.isInitialized) prefs.edit().putString(KEY_ACTIVE, v).apply() else Unit

    /** 当前生效的 UA；null 表示「不携带 UA」 */
    val active: UaProfile?
        get() {
            if (activeName == NONE) return null
            return all.firstOrNull { it.name == activeName } ?: builtin[0]
        }

    /** 面板上显示的名字 */
    val activeLabel: String
        get() = active?.name ?: "不携带"

    fun select(name: String) {
        activeName = name
    }

    fun selectNone() {
        activeName = NONE
    }

    /**
     * 新增一条自定义 UA，返回落库后的对象。
     * 重名会自动加序号（「我的 UA 2」），避免和已有项撞车。
     */
    fun addCustom(name: String, value: String): UaProfile {
        val finalName = uniqueName(name.ifBlank { "自定义 UA" })
        val profile = UaProfile(finalName, value.trim(), custom = true)
        writeCustom(custom + profile)
        return profile
    }

    /** 删除一条自定义项；如果删的正好是当前选中的，回落到第一个内置项 */
    fun removeCustom(name: String) {
        writeCustom(custom.filterNot { it.name == name })
        if (activeName == name) activeName = builtin[0].name
    }

    /** 纯函数：保证名称在「内置 + 自定义」里唯一 */
    fun uniqueName(base: String, existing: List<UaProfile> = builtin + custom): String {
        val taken = existing.map { it.name }.toSet()
        if (base !in taken && base != NONE) return base
        var i = 2
        while ("$base $i" in taken) i++
        return "$base $i"
    }

    private fun writeCustom(list: List<UaProfile>) {
        if (!::prefs.isInitialized) return
        prefs.edit().putString(KEY_CUSTOM, encode(list)).apply()
    }

    /** 序列化：每行「编码后的名称 \t 编码后的值」 */
    fun encode(list: List<UaProfile>): String = list.joinToString("\n") { p ->
        enc(p.name) + "\t" + enc(p.value)
    }

    /** 反序列化：坏行（缺分隔符 / 解码失败 / 空值）直接跳过 */
    fun decode(raw: String): List<UaProfile> {
        if (raw.isBlank()) return emptyList()
        val result = ArrayList<UaProfile>()
        for (line in raw.split('\n')) {
            if (line.isBlank()) continue
            val cut = line.indexOf('\t')
            if (cut <= 0) continue
            val name = dec(line.substring(0, cut)).trim()
            val value = dec(line.substring(cut + 1)).trim()
            if (name.isEmpty() || value.isEmpty()) continue
            if (result.any { it.name == name }) continue
            result.add(UaProfile(name, value, custom = true))
        }
        return result
    }

    /** UA 值里的空格、括号、分号都要原样保留，这里只用 URL 编码规避分隔符冲突 */
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    private fun dec(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        ""
    }

    /** 列表里一行显示的摘要：折成一行、超长截断 */
    fun summary(value: String, max: Int = 52): String {
        val flat = value.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= max) flat else flat.take(max - 1) + "…"
    }
}
