package com.guaner.jsonviewer.theme

/**
 * 用户命名保存的自定义主题。
 *
 * 只存「决定观感」的字段：预设基线 + 主色 / 背景 / 强调色 + 字体 / 字号 / 编辑器开关。
 * 编码为单行文本（`|` 分隔字段、`\n` 分隔记录），全部是纯函数，便于 JVM 单测。
 */
data class SavedTheme(
    val id: String,
    val name: String,
    val presetId: String,
    val brand: String,
    val background: String,
    /** null 表示强调色跟随主色 */
    val accent: String?,
    val fontFamily: String,
    val codeFontSize: Float,
    val formFontSize: Float,
    val codeWrap: Boolean,
    val codeZoom: Boolean,
    val autoContrast: Boolean
) {
    /** 卡片预览用的 4 个代表色：主色 / 背景 / 强调 / 文字（按背景对比度） */
    fun previewColors(): List<Int> {
        val b = ThemeManager.parseColorOrNull(brand) ?: 0xFF2E6BE6.toInt()
        val bg = ThemeManager.parseColorOrNull(background) ?: 0xFFFFFFFF.toInt()
        val ac = accent?.let { ThemeManager.parseColorOrNull(it) } ?: b
        return listOf(b, bg, ac, ThemeManager.contrastText(bg))
    }
}

object SavedThemeCodec {

    private const val FIELD = '|'
    private const val RECORD = '\n'
    private const val FIELD_COUNT = 12

    fun encode(list: List<SavedTheme>): String = list.joinToString(RECORD.toString()) { t ->
        listOf(
            t.id,
            sanitize(t.name),
            t.presetId,
            t.brand,
            t.background,
            t.accent.orEmpty(),
            t.fontFamily,
            t.codeFontSize.toString(),
            t.formFontSize.toString(),
            if (t.codeWrap) "1" else "0",
            if (t.codeZoom) "1" else "0",
            if (t.autoContrast) "1" else "0"
        ).joinToString(FIELD.toString())
    }

    fun decode(raw: String?): List<SavedTheme> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECORD).mapNotNull { decodeRecord(it) }
    }

    private fun decodeRecord(record: String): SavedTheme? {
        val p = record.split(FIELD)
        if (p.size != FIELD_COUNT) return null
        if (p[0].isBlank() || p[3].isBlank() || p[4].isBlank()) return null
        return SavedTheme(
            id = p[0],
            name = p[1],
            presetId = p[2],
            brand = p[3],
            background = p[4],
            accent = p[5].ifBlank { null },
            fontFamily = p[6],
            codeFontSize = p[7].toFloatOrNull() ?: 13f,
            formFontSize = p[8].toFloatOrNull() ?: 14f,
            codeWrap = p[9] == "1",
            codeZoom = p[10] == "1",
            autoContrast = p[11] == "1"
        )
    }

    /** 名称里不允许出现分隔符，避免破坏编码 */
    private fun sanitize(name: String): String =
        name.replace(FIELD, ' ').replace(RECORD, ' ').trim()
}
