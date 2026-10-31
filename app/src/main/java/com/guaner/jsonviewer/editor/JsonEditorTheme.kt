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
 */
object JsonEditorTheme {

    /** 按当前主题生成一份配色方案 */
    fun schemeOf(colors: ThemeColors): EditorColorScheme {
        val scheme = EditorColorScheme()
        with(scheme) {
            // 背景与正文
            setColor(EditorColorScheme.WHOLE_BACKGROUND, colors.background)
            setColor(EditorColorScheme.TEXT_NORMAL, colors.text)

            // 行号
            setColor(EditorColorScheme.LINE_NUMBER_BACKGROUND, colors.gutter)
            setColor(EditorColorScheme.LINE_NUMBER, colors.gutterText)
            setColor(EditorColorScheme.LINE_NUMBER_CURRENT, colors.accent)

            // 当前行 / 选中 / 搜索
            setColor(EditorColorScheme.CURRENT_LINE, mix(colors.background, colors.text, 0.05f))
            setColor(EditorColorScheme.SELECTED_TEXT_BACKGROUND, colors.treeSelected)
            setColor(EditorColorScheme.SELECTION_INSERT, colors.accent)
            setColor(EditorColorScheme.SELECTION_HANDLE, colors.accent)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_BACKGROUND, colors.searchMatch)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_UNDERLINE, colors.accent)
            setColor(EditorColorScheme.HIGHLIGHTED_DELIMITERS_FOREGROUND, colors.text)

            // 找到的文本（编辑器自带搜索模式会用）
            setColor(EditorColorScheme.MATCHED_TEXT_BACKGROUND, colors.searchMatch)
            setColor(EditorColorScheme.TEXT_SELECTED, colors.text)

            // 滚动条
            setColor(EditorColorScheme.SCROLL_BAR_TRACK, colors.gutter)
            setColor(EditorColorScheme.SCROLL_BAR_THUMB, colors.gutterText)
            setColor(EditorColorScheme.SCROLL_BAR_THUMB_PRESSED, colors.accent)

            // 语法高亮：用自定义槽位，由 JsonSpansLanguage 引用
            setColor(JsonSpansLanguage.ColorIds.KEY, colors.jsonKey)
            setColor(JsonSpansLanguage.ColorIds.STRING, colors.jsonString)
            setColor(JsonSpansLanguage.ColorIds.NUMBER, colors.jsonNumber)
            setColor(JsonSpansLanguage.ColorIds.LITERAL, colors.jsonBoolean)
            setColor(JsonSpansLanguage.ColorIds.PUNCT, colors.jsonPunct)

            // 诊断波浪线（错误定位用）
            setColor(EditorColorScheme.PROBLEM_ERROR, colors.jsonBoolean)
            setColor(EditorColorScheme.PROBLEM_WARNING, colors.jsonNumber)
            setColor(EditorColorScheme.PROBLEM_TYPO, colors.gutterText)

            // 行号气泡 / 侧栏
            setColor(EditorColorScheme.LINE_NUMBER_PANEL, colors.surface)
            setColor(EditorColorScheme.LINE_NUMBER_PANEL_TEXT, colors.text)
        }
        return scheme
    }

    /** 按当前生效主题生成 */
    fun currentScheme(): EditorColorScheme = schemeOf(ThemeManager.activeColors)

    private fun mix(a: Int, b: Int, ratio: Float): Int {
        val r = ((Color.red(a) * (1 - ratio)) + Color.red(b) * ratio).toInt()
        val g = ((Color.green(a) * (1 - ratio)) + Color.green(b) * ratio).toInt()
        val bl = ((Color.blue(a) * (1 - ratio)) + Color.blue(b) * ratio).toInt()
        return Color.rgb(r, g, bl)
    }
}
