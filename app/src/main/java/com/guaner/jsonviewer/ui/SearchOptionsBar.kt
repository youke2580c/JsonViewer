package com.guaner.jsonviewer.ui

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 搜索栏里「Aa / .* / 替换」三个开关 + 替换输入框的控制器。
 *
 * 代码页与值编辑弹窗共用同一套交互，避免两处各写一遍、行为逐渐分叉：
 * - 三个开关都是 [TextView] + `isSelected` 表达选中态（不用 CheckBox，省空间也更好对齐）
 * - 显隐完全由「设置 → 编辑器」的 4 个开关决定
 * - 选中态配色：强调色文字 + 15% 强调色淡底
 *
 * 宿主只需：
 * 1. 在布局里 `<include layout="@layout/view_search_options" />`（或直接展开同样的 id）
 * 2. 构造本类并调用 [bind]
 * 3. 在 [onQueryChanged] 里触发自己的搜索
 */
class SearchOptionsBar(
    private val context: Context,
    private val views: Views,
    /** 查询词或选项变化时回调；regex / caseSensitive 为当前生效值 */
    private val onQueryChanged: () -> Unit,
    /** 点「替换当前」 */
    private val onReplaceOne: () -> Unit,
    /** 点「全部替换」 */
    private val onReplaceAll: () -> Unit,
    /** 替换栏展开/收起后回调（宿主可能需要重新算高度） */
    private val onReplaceRowToggled: (Boolean) -> Unit = {}
) {

    /** 通过 id 聚合的控件引用，宿主用 View.findViewById 传入 */
    class Views(
        val optionsRow: View,
        val caseToggle: TextView,
        val regexToggle: TextView,
        val replaceToggle: TextView,
        val replaceRow: View,
        val replaceInput: EditText,
        val replaceOne: TextView,
        val replaceAll: TextView
    )

    /** 当前是否按正则搜索 */
    var isRegex: Boolean = false
        private set

    /** 当前是否区分大小写 */
    var isCaseSensitive: Boolean = false
        private set

    /** 替换栏是否展开 */
    val isReplaceOpen: Boolean
        get() = views.replaceRow.visibility == View.VISIBLE

    private var colors: ThemeColors = ThemeManager.activeColors

    init {
        bind()
    }

    private fun bind() {
        // 恢复上次的选项，让用户不用每次重新点
        isCaseSensitive = AppSettings.editorSearchCaseLast
        isRegex = AppSettings.editorSearchRegexLast
        views.caseToggle.isSelected = isCaseSensitive
        views.regexToggle.isSelected = isRegex

        views.caseToggle.setOnClickListener {
            isCaseSensitive = !isCaseSensitive
            views.caseToggle.isSelected = isCaseSensitive
            AppSettings.editorSearchCaseLast = isCaseSensitive
            applyToggleStyle()
            onQueryChanged()
        }
        views.regexToggle.setOnClickListener {
            isRegex = !isRegex
            views.regexToggle.isSelected = isRegex
            AppSettings.editorSearchRegexLast = isRegex
            applyToggleStyle()
            onQueryChanged()
        }
        views.replaceToggle.setOnClickListener {
            val open = !isReplaceOpen
            views.replaceRow.visibility = if (open) View.VISIBLE else View.GONE
            views.replaceToggle.isSelected = open
            applyToggleStyle()
            onReplaceRowToggled(open)
            if (open) {
                views.replaceInput.requestFocus()
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(views.replaceInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        views.replaceOne.setOnClickListener { onReplaceOne() }
        views.replaceAll.setOnClickListener { onReplaceAll() }
        views.replaceInput.setOnEditorActionListener { _, _, _ ->
            onReplaceOne()
            true
        }
        // 这些控件都不该抢输入框焦点，否则点一下就断开输入
        listOf(views.caseToggle, views.regexToggle, views.replaceToggle, views.replaceOne, views.replaceAll)
            .forEach {
                it.isFocusable = false
                it.isFocusableInTouchMode = false
            }
        applyVisibility()
    }

    /** 按设置决定显示哪些能力（设置页改完回来要重新调一次） */
    fun applyVisibility() {
        val enabled = AppSettings.editorSearch
        val anyOption = AppSettings.editorSearchCase ||
            AppSettings.editorSearchRegex ||
            AppSettings.editorSearchReplace
        views.optionsRow.visibility = if (enabled && anyOption) View.VISIBLE else View.GONE
        views.caseToggle.visibility = if (enabled && AppSettings.editorSearchCase) View.VISIBLE else View.GONE
        views.regexToggle.visibility = if (enabled && AppSettings.editorSearchRegex) View.VISIBLE else View.GONE
        views.replaceToggle.visibility = if (enabled && AppSettings.editorSearchReplace) View.VISIBLE else View.GONE
        if (views.replaceToggle.visibility != View.VISIBLE) {
            views.replaceRow.visibility = View.GONE
            views.replaceToggle.isSelected = false
        }
        applyToggleStyle()
    }

    fun onThemeChanged(newColors: ThemeColors) {
        colors = newColors
        applyToggleStyle()
    }

    fun clearReplaceInput() {
        views.replaceInput.setText("")
    }

    /** 正则编译失败时把按钮标红提醒一下（不弹窗，避免打断输入） */
    fun markRegexInvalid(invalid: Boolean) {
        if (invalid) {
            views.regexToggle.setTextColor(colors.errorColumn.let {
                // errorColumn 太暗，取它和正文的中间色保证可读
                ThemeManager.mix(it, colors.text, 0.35f)
            })
        } else {
            applyToggleStyle()
        }
    }

    private fun applyToggleStyle() {
        val density = context.resources.displayMetrics.density
        val accent = colors.accent
        val idle = colors.gutterText
        listOf(views.caseToggle to isCaseSensitive, views.regexToggle to isRegex, views.replaceToggle to isReplaceOpen)
            .forEach { (tv, on) ->
                tv.setTextColor(if (on) accent else idle)
                val bg = GradientDrawable()
                bg.cornerRadius = 6 * density
                bg.setColor(if (on) ThemeManager.mix(colors.gutter, accent, 0.15f) else Color.TRANSPARENT)
                tv.background = bg
            }
        views.replaceOne.setTextColor(accent)
        views.replaceAll.setTextColor(accent)
        views.replaceInput.setTextColor(colors.text)
        views.replaceInput.setHintTextColor(colors.gutterText)
    }

    companion object {
        /** 给宿主省事的工厂：直接传入搜索栏根布局 */
        fun from(root: View, context: Context, onQueryChanged: () -> Unit,
                 onReplaceOne: () -> Unit, onReplaceAll: () -> Unit,
                 onReplaceRowToggled: (Boolean) -> Unit = {}): SearchOptionsBar {
            return SearchOptionsBar(
                context,
                Views(
                    optionsRow = root.findViewById(com.guaner.jsonviewer.R.id.searchOptionsRow),
                    caseToggle = root.findViewById(com.guaner.jsonviewer.R.id.searchCaseToggle),
                    regexToggle = root.findViewById(com.guaner.jsonviewer.R.id.searchRegexToggle),
                    replaceToggle = root.findViewById(com.guaner.jsonviewer.R.id.searchReplaceToggle),
                    replaceRow = root.findViewById(com.guaner.jsonviewer.R.id.searchReplaceRow),
                    replaceInput = root.findViewById(com.guaner.jsonviewer.R.id.searchReplaceInput),
                    replaceOne = root.findViewById(com.guaner.jsonviewer.R.id.searchReplaceOne),
                    replaceAll = root.findViewById(com.guaner.jsonviewer.R.id.searchReplaceAll)
                ),
                onQueryChanged, onReplaceOne, onReplaceAll, onReplaceRowToggled
            )
        }
    }
}

/** 只关心「有内容变化」的简化 TextWatcher */
inline fun EditText.onTextChanged(crossinline action: (String) -> Unit) {
    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = action(s?.toString().orEmpty())
    })
}
