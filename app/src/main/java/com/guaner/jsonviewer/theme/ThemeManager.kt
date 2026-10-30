package com.guaner.jsonviewer.theme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface

/**
 * 主题管理器（单例）。
 *
 * 采用「运行时动态着色」而非全量 ?attr 重构：所有可调色集中在 [activeColors]，
 * 由 MainActivity / CodeEditorView / ValueSheet 等少数入口读取并应用到具体 View。
 *
 * 持久化字段：
 * - presetId        ：内置主题 id（classic_blue / dark_night / mint_green / warm_orange / sakura_purple）
 * - customBrand     ：自定义主色调 hex（null 表示用预设）
 * - customAccent    ：自定义强调色 hex（按钮/滑块/Tab 指示器等，null 表示跟随主色 brand）
 * - customBackground：自定义背景色 hex（null 表示用预设）
 * - codeFontSize / formFontSize：字号 sp
 * - fontFamily      ：mono / sans / serif / default
 * - codeWrap        ：代码编辑器是否换行
 * - codeZoom        ：代码编辑器是否允许双指缩放
 * - codeScale       ：双指缩放当前系数
 */
object ThemeManager {

    private const val PREF = "jsonviewer_theme"
    private const val KEY_SAVED_THEMES = "savedThemes"

    const val FONT_MONO = "mono"
    const val FONT_SANS = "sans"
    const val FONT_SERIF = "serif"
    const val FONT_DEFAULT = "default"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    // ---------- 持久化读写 ----------

    var presetId: String
        get() = prefs.getString("presetId", ThemePresets.DEFAULT_ID) ?: ThemePresets.DEFAULT_ID
        set(v) = prefs.edit().putString("presetId", v).apply()

    var customBrand: String?
        get() = prefs.getString("customBrand", null)
        set(v) = prefs.edit().putString("customBrand", v).apply()

    var customBackground: String?
        get() = prefs.getString("customBackground", null)
        set(v) = prefs.edit().putString("customBackground", v).apply()

    /** 自定义强调色 hex（按钮/滑块/Tab 指示器等；null 表示跟随主色 brand） */
    var customAccent: String?
        get() = prefs.getString("customAccent", null)
        set(v) = prefs.edit().putString("customAccent", v).apply()

    var codeFontSize: Float
        get() = prefs.getFloat("codeFontSize", 13f)
        set(v) = prefs.edit().putFloat("codeFontSize", v.coerceIn(8f, 40f)).apply()

    var formFontSize: Float
        get() = prefs.getFloat("formFontSize", 14f)
        set(v) = prefs.edit().putFloat("formFontSize", v.coerceIn(10f, 40f)).apply()

    var fontFamily: String
        get() = prefs.getString("fontFamily", FONT_MONO) ?: FONT_MONO
        set(v) = prefs.edit().putString("fontFamily", v).apply()

    var codeWrap: Boolean
        get() = prefs.getBoolean("codeWrap", false)
        set(v) = prefs.edit().putBoolean("codeWrap", v).apply()

    var codeZoom: Boolean
        get() = prefs.getBoolean("codeZoom", true)
        set(v) = prefs.edit().putBoolean("codeZoom", v).apply()

    var codeScale: Float
        get() = prefs.getFloat("codeScale", 1f)
        set(v) = prefs.edit().putFloat("codeScale", v.coerceIn(0.5f, 3f)).apply()

    /** 自定义背景时是否自动保证文字/图标对比（默认开） */
    var autoContrast: Boolean
        get() = prefs.getBoolean("autoContrast", true)
        set(v) = prefs.edit().putBoolean("autoContrast", v).apply()

    // ---------- 命名保存的自定义主题 ----------

    /** 已保存的自定义主题（按保存顺序） */
    val savedThemes: List<SavedTheme>
        get() = SavedThemeCodec.decode(prefs.getString(KEY_SAVED_THEMES, null))

    /** 把当前生效的配色与排版保存成一个命名主题 */
    fun saveCurrentTheme(name: String): SavedTheme {
        val label = name.trim().ifBlank { "我的主题" }.take(24)
        val c = activeColors
        val theme = SavedTheme(
            id = "t" + System.currentTimeMillis().toString(36) +
                (0..999).random().toString().padStart(3, '0'),
            name = label,
            presetId = presetId,
            brand = toHex(c.brand),
            background = toHex(c.background),
            accent = customAccent,
            fontFamily = fontFamily,
            codeFontSize = codeFontSize,
            formFontSize = formFontSize,
            codeWrap = codeWrap,
            codeZoom = codeZoom,
            autoContrast = autoContrast
        )
        writeSavedThemes(savedThemes + theme)
        return theme
    }

    fun deleteSavedTheme(id: String) {
        writeSavedThemes(savedThemes.filterNot { it.id == id })
    }

    /** 应用一个已保存主题：恢复配色 + 字体字号 + 编辑器开关 */
    fun applySavedTheme(id: String): Boolean {
        val t = savedThemes.firstOrNull { it.id == id } ?: return false
        presetId = if (ThemePresets.presets.containsKey(t.presetId)) t.presetId else ThemePresets.DEFAULT_ID
        customBrand = t.brand
        customBackground = t.background
        customAccent = t.accent
        fontFamily = t.fontFamily
        codeFontSize = t.codeFontSize
        formFontSize = t.formFontSize
        codeWrap = t.codeWrap
        codeZoom = t.codeZoom
        autoContrast = t.autoContrast
        return true
    }

    private fun writeSavedThemes(list: List<SavedTheme>) {
        prefs.edit().putString(KEY_SAVED_THEMES, SavedThemeCodec.encode(list)).apply()
    }

    // ---------- 当前生效配色 ----------

    /**
     * 解析当前生效配色：先取预设，再用自定义主色/背景/强调色覆盖。
     * 强调色（按钮/滑块/Tab 指示器等）默认跟随主色 brand；自定义背景且开启自动对比时，
     * 按背景亮度重算辅助前景色（text / gutterText / jsonNull / 卡片 surface / gutter），
     * 保证「字体颜色和图标等颜色与背景色有对比」。
     */
    val activeColors: ThemeColors
        get() {
            val base = ThemePresets.presets[presetId] ?: ThemePresets.classicBlue
            val brand = customBrand?.let { parseColorOrNull(it) } ?: base.brand
            val background = customBackground?.let { parseColorOrNull(it) } ?: base.background
            // 强调色默认跟随主色；若单独设置了强调色则用强调色
            val accent = customAccent?.let { parseColorOrNull(it) } ?: brand
            return deriveColors(base, brand, background, accent, customBackground != null, autoContrast)
        }

    /**
     * 纯函数：根据是否自定义背景与是否自动对比，推导最终配色。
     * 自定义背景且开启自动对比时，按背景亮度重算辅助前景色（text/gutterText/jsonNull）
     * 与卡片/行号底色（surface/gutter），保证「字体颜色和图标等颜色与背景色有对比」；
     * 其余地方由 brand / accent 自动配色。
     */
    internal fun deriveColors(
        base: ThemeColors,
        brand: Int,
        background: Int,
        accent: Int,
        customBackground: Boolean,
        autoContrast: Boolean
    ): ThemeColors {
        return if (customBackground && autoContrast) {
            val fg = contrastText(background)
            // surface/gutter 由背景与前景按固定比例混合，自动保证与背景的对比度
            val surface = mix(background, fg, 0.06f)
            val gutter = mix(background, fg, 0.12f)
            base.copy(
                brand = brand,
                background = background,
                accent = accent,
                text = fg,
                gutterText = fg,
                jsonNull = fg,
                surface = surface,
                gutter = gutter
            )
        } else {
            base.copy(brand = brand, background = background, accent = accent)
        }
    }

    /**
     * 纯函数：按 ratio 在 a、b 两色间线性混合（0=a，1=b），用于派生卡片/行号底色。
     */
    fun mix(a: Int, b: Int, ratio: Float): Int {
        val r = ((a shr 16 and 0xFF) * (1 - ratio) + (b shr 16 and 0xFF) * ratio).toInt()
        val g = ((a shr 8 and 0xFF) * (1 - ratio) + (b shr 8 and 0xFF) * ratio).toInt()
        val bl = ((a and 0xFF) * (1 - ratio) + (b and 0xFF) * ratio).toInt()
        return (0xFF shl 24) or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (bl and 0xFF)
    }

    fun resolveTypeface(): Typeface = when (fontFamily) {
        FONT_MONO -> Typeface.MONOSPACE
        FONT_SANS -> Typeface.SANS_SERIF
        FONT_SERIF -> Typeface.SERIF
        else -> Typeface.DEFAULT
    }

    /** 根据背景亮度返回可读前景色：YIQ 公式，亮底用黑、暗底用白（纯实现，无 Android 依赖） */
    fun contrastText(bg: Int): Int {
        val r = (bg shr 16) and 0xFF
        val g = (bg shr 8) and 0xFF
        val b = bg and 0xFF
        val yiq = (r * 299 + g * 587 + b * 114) / 1000
        return if (yiq >= 128) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    /** 解析 hex 颜色（支持 #rgb / #rrggbb / #aarrggbb），失败返回 null（纯实现） */
    fun parseColorOrNull(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        val h = hex.trim().removePrefix("#")
        val rgb = when (h.length) {
            3 -> h.map { c -> "$c$c" }.joinToString("")
            6 -> h
            8 -> h.takeLast(6)
            else -> return null
        }
        if (rgb.length != 6) return null
        if (rgb.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        val r = rgb.substring(0, 2).toIntOrNull(16) ?: return null
        val g = rgb.substring(2, 4).toIntOrNull(16) ?: return null
        val b = rgb.substring(4, 6).toIntOrNull(16) ?: return null
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun toHex(color: Int): String {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return String.format("#%02X%02X%02X", r, g, b)
    }

    /**
     * 导入/粘贴文本自动裁剪：保留第一个 `{` 或 `[` 及其之后的内容。
     * 若没有容器符号（如纯说明文本），原样返回，避免误删。
     * 若文本本身就是以容器开头（索引 0），原样返回。
     */
    fun stripBeforeFirstContainer(text: String): String {
        val firstBrace = text.indexOf('{')
        val firstBracket = text.indexOf('[')
        val cut = when {
            firstBrace < 0 && firstBracket < 0 -> -1
            firstBrace < 0 -> firstBracket
            firstBracket < 0 -> firstBrace
            else -> minOf(firstBrace, firstBracket)
        }
        if (cut <= 0) return text
        return text.substring(cut)
    }

    fun reset() {
        presetId = ThemePresets.DEFAULT_ID
        customBrand = null
        customAccent = null
        customBackground = null
        codeFontSize = 13f
        formFontSize = 14f
        fontFamily = FONT_MONO
        codeWrap = false
        codeZoom = true
        codeScale = 1f
        autoContrast = true
    }
}
