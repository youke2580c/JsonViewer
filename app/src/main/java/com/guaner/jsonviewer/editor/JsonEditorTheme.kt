package com.guaner.jsonviewer.editor

import android.graphics.Color
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.theme.ThemeManager
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * 把应用自己的主题（[ThemeColors]）翻译成编辑器配色。
 *
 * 全部色值都从当前主题取，因此换主题、改自定义背景/主色、自动对比度都会跟着变，
 * 不需要为编辑器单独维护一套配色。
 *
 * **每个槽位都必须显式写入**：`EditorColorScheme.getColor(id)` 对没设置过的 ID 返回
 * 默认色（内置方案接近白底黑字），在深色主题下就会变成「文字和背景一个色」。
 */
object JsonEditorTheme {

    /**
     * 跳转「整行色带」的自定义配色槽位。
     *
     * 必须大于 `EditorColorScheme.END_COLOR_ID`（= 66），否则会覆盖内置槽位。
     * 与 `CodeEditorView.FLASH_LINE_COLOR_ID` 保持一致。
     */
    const val FLASH_LINE_COLOR_ID = 70

    /** 按当前主题生成一份配色方案 */
    fun schemeOf(colors: ThemeColors): EditorColorScheme {
        val scheme = EditorColorScheme()
        // 选中/当前行等「叠加层」需要和背景拉开差距：深色底提亮，浅色底压暗
        val overlay = translucent(colors.text, if (isDark(colors.background)) 0.12f else 0.07f)
        val overlayStrong = translucent(colors.accent, 0.22f)

        // 跳转高亮的两层色块（都是「实色」而不是透明叠加，才看得出是块而不是线）
        // - 整行带：压得足够可见，但不抢文字；深色底用强调色 16%，浅色底 11%
        // - 字段名块：明显更重，且必须和整行带拉开差距
        val jumpLine = translucent(colors.accent, if (isDark(colors.background)) 0.16f else 0.11f)
        val jumpWord = translucent(colors.accent, if (isDark(colors.background)) 0.62f else 0.46f)

        with(scheme) {
            // ---------- 基础 ----------
            setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.background)
            setColor(EditorColorScheme.TEXT_NORMAL, colors.text)

            // ---------- 行号 ----------
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.gutter)
            setColor(EditorColorScheme.LINE_NUMBER, colors.gutterText)
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.accent)
            setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors.surface)
            setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, colors.text)
            setColor(EditorColorScheme.LINE_DIVIDER, colors.gutter)

            // ---------- 当前行 / 选中 ----------
            setColor(EditorColorScheme.CURRENT_LINE, overlay)
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.treeSelected)
            setColor(EditorColorScheme.SELECTION_INSERT, colors.accent)
            setColor(EditorColorScheme.SELECTION_HANDLE, colors.accent)
            setColor(EditorColorScheme.TEXT_SELECTED, colors.text)
            setColor(EditorColorScheme.BLOCK_LINE, overlay)
            setColor(EditorColorScheme.BLOCK_LINE_CURRENT, overlay)

            // ---------- 搜索 ----------
            setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.searchMatch)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, colors.searchMatch)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, colors.accent)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, colors.text)

            // ---------- 滚动条 ----------
            setColor(EditorColorScheme.SCROLL_BAR_TRACK, colors.gutter)
            setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.gutterText)
            setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.accent)

            // ---------- 语法高亮（自定义槽位，必须全部写入） ----------
            setColor(JsonSpansLanguage.ColorIds.KEY, colors.jsonKey)
            setColor(JsonSpansLanguage.ColorIds.STRING, colors.jsonString)
            setColor(JsonSpansLanguage.ColorIds.NUMBER, colors.jsonNumber)
            setColor(JsonSpansLanguage.ColorIds.LITERAL, colors.jsonBoolean)
            setColor(JsonSpansLanguage.ColorIds.PUNCT, colors.jsonPunct)

            // ---------- 诊断（错误 / 警告 / 拼写） ----------
            setColor(EditorColorScheme.PROBLEM_ERROR, colors.jsonBoolean)
            setColor(EditorColorScheme.PROBLEM_WARNING, colors.jsonNumber)
            // PROBLEM_TYPO 现在只用于「搜索非当前命中」，用淡强调色
            setColor(EditorColorScheme.PROBLEM_TYPO, jumpLine)

            // ---------- 自动补全与文本操作弹窗（默认白底，深色主题下会刺眼） ----------
            setColor(EditorColorScheme.COMPLETION_WND_BACKGROUND, colors.surface)
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_PRIMARY, colors.text)
            setColor(EditorColorScheme.COMPLETION_WND_TEXT_SECONDARY, colors.gutterText)
            setColor(EditorColorScheme.COMPLETION_WND_ITEM_CURRENT, overlayStrong)

            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_BACKGROUND, colors.surface)
            setColor(EditorColorScheme.TEXT_ACTION_WINDOW_ICON_COLOR, colors.accent)

            // ---------- 静态 span（大纲跳转的字段名色块）/ 粘性分隔线 ----------
            // CodeEditorView.onDraw 自绘字段名圆角块时读的就是 STATIC_SPAN_BACKGROUND
            setColor(EditorColorScheme.STATIC_SPAN_BACKGROUND, jumpWord)
            setColor(EditorColorScheme.STATIC_SPAN_FOREGROUND, colors.text)
            setColor(EditorColorScheme.STICKY_SCROLL_DIVIDER, colors.gutter)

            // ---------- 跳转「整行色带」（自定义槽位） ----------
            // 与 CodeEditorView.FLASH_LINE_COLOR_ID 必须保持一致
            setColor(FLASH_LINE_COLOR_ID, jumpLine)

        }
        return scheme
    }

    /** 按当前生效主题生成 */
    fun currentScheme(): EditorColorScheme = schemeOf(ThemeManager.activeColors)

    /** 判断某个颜色属于深色还是浅色（决定叠加层提亮还是压暗） */
    fun isDark(color: Int): Boolean {
        val luma = (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0
        return luma < 0.5
    }

    /** 带透明度的叠加色 */
    private fun translucent(base: Int, alpha: Float): Int {
        return Color.argb(
            (alpha * 255).toInt().coerceIn(0, 255),
            Color.red(base),
            Color.green(base),
            Color.blue(base)
        )
    }

    /** 不透明混合，[ratio] 是 [b] 的占比 */
    fun mix(a: Int, b: Int, ratio: Float): Int {
        val r = ((Color.red(a) * (1 - ratio)) + Color.red(b) * ratio).toInt()
        val g = ((Color.green(a) * (1 - ratio)) + Color.green(b) * ratio).toInt()
        val bl = ((Color.blue(a) * (1 - ratio)) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }
}
