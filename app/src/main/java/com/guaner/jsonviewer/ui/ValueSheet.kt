package com.guaner.jsonviewer.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.view.KeyEvent
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.NestedScrollView
import androidx.core.widget.addTextChangedListener
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.databinding.SheetValueBinding
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.ValueDraft
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 值详情弹窗：查看某个节点的完整值（树里为了性能只展示前若干字符）。
 * 支持文本选择复制、一键复制值、一键复制 JSONPath，以及在值内搜索高亮并定位。
 */
class ValueSheet : BottomSheetDialogFragment() {

    companion object {
        const val TAG = "ValueSheet"

        /** 弹窗展示的完整值上限：超过则截断（复制同样受此限） */
        const val MAX_SHOW_CHARS = 200_000

        /** 值内搜索命中上限：防止极端情况下 span 过多拖慢界面 */
        private const val MAX_VALUE_MATCHES = 2_000

        /** 搜索输入防抖：大文本下逐字扫描 + 重排是卡顿主因 */
        private const val SEARCH_DEBOUNCE_MS = 180L

        /**
         * 内联编辑的字符上限。
         *
         * EditText 每次输入都会重排受影响行之后的布局，几十万字符时每键都会明显延迟，
         * 所以超过这个量的值不再在弹窗内内联编辑，改为打开临时代码编辑器
         * （那边只有可视区高亮，滚动/输入才不卡，并且可以直接敲真实换行）。
         */
        private const val MAX_EDIT_CHARS = 40_000

        // 同 JsonPathSheet：大内容走静态持有，避免 Bundle 超过 Binder 限制
        private class Args(
            val title: String,
            val path: String,
            val value: String,
            val type: NodeType
        )

        private var pending: Args? = null

        fun newInstance(title: String, path: String, value: String, type: NodeType): ValueSheet {
            pending = Args(title, path, value, type)
            return ValueSheet()
        }
    }

    /** 复制回调：(文本, 标签) */
    var onCopy: ((String, String) -> Unit)? = null

    /** 保存回调：传入可直接写回原文的 JSON 片段，返回 true 表示替换成功 */
    var onSave: ((String) -> Boolean)? = null

    private var title: String = ""
    private var path: String = ""
    private var value: String = ""
    private var valueType: NodeType = NodeType.STRING

    /** 编辑态：编辑框里的初始文本，用于「重置」和判断有没有未保存改动 */
    private var editing = false
    private var originalText: String = ""

    /** 打开时的值类型，用于「重置」和判断「改了类型但没改文本」也算有改动 */
    private var originalType: NodeType = NodeType.STRING

    /** 类型选项（顺序即显示顺序）与对应的 chip 控件 */
    private val typeOptions = ValueDraft.options
    private val typeViews = ArrayList<TextView>()

    /** 只读态的说明文字（编辑结束后恢复，截断提示也能原样回去） */
    private var defaultMeta: String = ""
    /** 实际展示（可能已截断）的文本，搜索高亮在此范围内进行 */
    private var shownValue: String = ""
    private val searchMatches = ArrayList<IntRange>()
    private var searchIndex = -1
    private lateinit var binding: SheetValueBinding

    /** 搜索防抖：避免每次按键都对超长文本做全量扫描 + 重排 */
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchRunnable = Runnable { runValueSearch() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending?.let {
            title = it.title
            path = it.path
            value = it.value
            valueType = it.type
            originalType = it.type
        }
        pending = null
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = SheetValueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 文本/背景跟随当前主题
        val colors = ThemeManager.activeColors
        binding.valueTitle.setTextColor(colors.text)
        binding.valuePath.setTextColor(colors.gutterText)
        binding.valueMeta.setTextColor(colors.gutterText)
        // 查看区域不再单独设底色：与弹窗同色（root 背景），文字用正文色
        binding.valueRoot.setBackgroundColor(colors.background)
        binding.valueFull.setBackgroundColor(Color.TRANSPARENT)
        binding.valueFull.setTextColor(colors.text)

        // 搜索行背景 / 图标 tint / 按钮跟随主题
        binding.searchRowValue.setBackgroundColor(colors.gutter)
        val accentTint = ColorStateList.valueOf(colors.accent)
        binding.searchPrevValue.imageTintList = accentTint
        binding.searchNextValue.imageTintList = accentTint
        binding.searchCloseValue.imageTintList = accentTint
        binding.searchCountValue.setTextColor(colors.gutterText)
        binding.copyValueButton.backgroundTintList = ColorStateList.valueOf(colors.accent)
        binding.copyValueButton.setTextColor(ThemeManager.contrastText(colors.accent))
        binding.copyPathButton.setTextColor(colors.accent)
        binding.editValueButton.setTextColor(colors.accent)
        binding.valueEditor.setTextColor(colors.text)
        binding.valueEditor.setHintTextColor(colors.gutterText)
        binding.resetValueButton.setTextColor(colors.gutterText)
        binding.cancelEditButton.setTextColor(colors.text)
        binding.saveValueButton.backgroundTintList = ColorStateList.valueOf(colors.accent)
        binding.saveValueButton.setTextColor(ThemeManager.contrastText(colors.accent))

        // 触摸内部内容时阻止 BottomSheet 把竖向滚动误判为下拉关闭
        binding.valueScroll.setOnTouchListener { v, _ ->
            v.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }
        setupScrollThumb()

        binding.valueTitle.text = title
        binding.valuePath.text = path

        val truncated = value.length > MAX_SHOW_CHARS
        shownValue = if (truncated) value.take(MAX_SHOW_CHARS) else value
        // 用可变 Spannable 承载：搜索时只增删 span，不重建（也不重排）整段超长文本
        binding.valueFull.setText(
            SpannableStringBuilder(shownValue),
            TextView.BufferType.SPANNABLE
        )
        binding.valueScroll.post { updateScrollThumb() }
        binding.valueMeta.text = if (truncated) {
            "共 ${JsonLimits.formatChars(value.length)}，已展示前 ${JsonLimits.formatChars(shownValue.length)}"
        } else {
            "共 ${JsonLimits.formatChars(value.length)} · 长按文本可选中复制"
        }
        defaultMeta = binding.valueMeta.text.toString()

        binding.copyValueButton.setOnClickListener {
            if (value.isEmpty()) {
                toast("没有可复制的内容")
            } else {
                onCopy?.invoke(shownValue, "值")
            }
        }
        binding.copyPathButton.setOnClickListener {
            onCopy?.invoke(path, "JSONPath")
        }

        // 值内搜索：高亮 + 上下定位（输入防抖，避免大文本逐字全量扫描卡顿）
        binding.searchInputValue.addTextChangedListener(afterTextChanged = {
            searchHandler.removeCallbacks(searchRunnable)
            searchHandler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
        })
        binding.searchInputValue.setOnEditorActionListener { _, _, _ ->
            valueSearchNext()
            true
        }
        binding.searchPrevValue.setOnClickListener { valueSearchPrev() }
        binding.searchNextValue.setOnClickListener { valueSearchNext() }
        binding.searchCloseValue.setOnClickListener { closeValueSearch() }

        setupEditing()
    }

    // ---------- 编辑 ----------

    private fun setupEditing() {
        buildTypeRow()
        binding.editValueButton.setOnClickListener { enterEditMode() }
        binding.resetValueButton.setOnClickListener {
            valueType = originalType
            binding.valueEditor.setText(originalText)
            binding.valueEditor.setSelection(0)
            // 原来的类型是 null 时输入框仍要锁住
            binding.valueEditor.isEnabled = valueType != NodeType.NULL
            styleTypeRow()
            binding.valueMeta.text = editHint()
            toast("已恢复为打开时的内容与类型")
        }
        binding.cancelEditButton.setOnClickListener { requestExitEditMode() }
        binding.saveValueButton.setOnClickListener { saveEdit() }
        // 编辑态禁止返回键直接关闭：先问是否保存
        dialog?.setOnKeyListener { _, keyCode, event ->
            if (editing && keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestExitEditMode()
                true
            } else {
                false
            }
        }
    }

    /** 类型 chip：手动着色（不走 ?attr，才能跟运行时主题一致） */
    private fun buildTypeRow() {
        val density = resources.displayMetrics.density
        typeViews.clear()
        binding.typeRow.removeAllViews()
        typeOptions.forEach { (type, label) ->
            val tv = TextView(requireContext()).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(
                    (12 * density).toInt(), (5 * density).toInt(),
                    (12 * density).toInt(), (5 * density).toInt()
                )
                setOnClickListener { selectType(type) }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = (8 * density).toInt() }
            }
            typeViews.add(tv)
            binding.typeRow.addView(tv)
        }
        styleTypeRow()
    }

    private fun styleTypeRow() {
        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        typeOptions.forEachIndexed { i, (type, _) ->
            val tv = typeViews.getOrNull(i) ?: return@forEachIndexed
            val selected = type == valueType
            tv.background = GradientDrawable().apply {
                cornerRadius = 13 * density
                setColor(if (selected) colors.accent else Color.TRANSPARENT)
                setStroke(
                    (1 * density).toInt().coerceAtLeast(1),
                    if (selected) colors.accent else colors.gutter
                )
            }
            tv.setTextColor(
                if (selected) ThemeManager.contrastText(colors.accent) else colors.text
            )
        }
    }

    /**
     * 切换值类型。只做「让编辑框内容能与新类型对上」的最小调整：
     * 数字/布尔/对象/数组在内容为空时给个占位，null 直接填 null 并锁住输入框，
     * 其余情况保留用户已经敲进去的文本，校验交给保存时统一做。
     */
    private fun selectType(type: NodeType) {
        if (type == valueType) return
        val prev = valueType
        valueType = type
        val current = binding.valueEditor.text?.toString().orEmpty()
        binding.valueEditor.isEnabled = type != NodeType.NULL
        val filled = ValueDraft.coerceText(current, prev, type)
        if (filled != current) {
            binding.valueEditor.setText(filled)
            binding.valueEditor.setSelection(0)
        }
        styleTypeRow()
        binding.valueMeta.text = editHint()
        if (type == NodeType.NULL) {
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.hideSoftInputFromWindow(binding.valueEditor.windowToken, 0)
        }
    }

    private fun enterEditMode() {
        // 大文本内联编辑每敲一个键都要重排几十万字符，改在临时代码编辑器里编辑：
        // 那里和代码页共用 CodeEditorView（高亮/行号/搜索），但编辑的是「值的文本」，
        // 换行直接敲回车即可，保存时由 ValueDraft 自动转义成 \n。
        if (value.length > MAX_EDIT_CHARS) {
            openTempEditor(value)
            return
        }
        beginEditing(value)
    }

    /** 打开临时代码编辑器；保存后把文本回填到值编辑框，由用户确认再写回 JSON */
    private fun openTempEditor(initial: String) {
        val host = activity ?: return
        TempCodeEditorDialog.show(
            host,
            "编辑值 · ${JsonLimits.formatChars(initial.length)}",
            initial
        ) { text ->
            if (!isAdded) return@show
            beginEditing(text)
            toast("已填入值编辑框，确认后点「保存」")
        }
    }

    private fun beginEditing(initialText: String) {
        editing = true
        originalText = value
        originalType = valueType
        binding.valueEditor.isEnabled = valueType != NodeType.NULL
        binding.valueEditor.setText(initialText)
        binding.valueEditor.setSelection(0)
        binding.valueEditor.visibility = View.VISIBLE
        binding.valueFull.visibility = View.GONE
        binding.valueScroll.visibility = View.GONE
        binding.scrollThumb.visibility = View.GONE
        binding.searchRowValue.visibility = View.GONE
        binding.actionRowReadonly.visibility = View.GONE
        binding.actionRowEdit.visibility = View.VISIBLE
        binding.typeRowScroll.visibility = View.VISIBLE
        styleTypeRow()
        binding.valueMeta.setTextColor(ThemeManager.activeColors.accent)
        binding.valueMeta.text = editHint()
        // 编辑中不允许下滑/点外部关闭，避免误触丢改动；退出走「取消」或返回键（会询问）
        isCancelable = false
        binding.valueEditor.requestFocus()
        binding.valueEditor.post {
            binding.valueEditor.scrollTo(0, 0)
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.showSoftInput(binding.valueEditor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun exitEditMode() {
        editing = false
        originalText = ""
        binding.valueEditor.visibility = View.GONE
        binding.valueFull.visibility = View.VISIBLE
        binding.valueScroll.visibility = View.VISIBLE
        binding.searchRowValue.visibility = View.VISIBLE
        binding.actionRowReadonly.visibility = View.VISIBLE
        binding.actionRowEdit.visibility = View.GONE
        binding.typeRowScroll.visibility = View.GONE
        binding.valueEditor.isEnabled = true
        binding.valueMeta.setTextColor(ThemeManager.activeColors.gutterText)
        binding.valueMeta.text = defaultMeta
        isCancelable = true
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.valueEditor.windowToken, 0)
        binding.valueScroll.post { updateScrollThumb() }
    }

    private fun isDirty(): Boolean =
        editing && (binding.valueEditor.text?.toString().orEmpty() != originalText ||
            valueType != originalType)

    private fun requestExitEditMode() {
        if (!isDirty()) {
            exitEditMode()
            return
        }
        ThemedDialog.confirm(
            requireContext(), "保存修改？", "该值已修改但还没有保存。",
            positive = "保存",
            negative = "放弃修改",
            neutral = "继续编辑",
            onNegative = { exitEditMode() }     // 放弃修改：退出编辑态、不写回
        ) { saveEdit() }
    }

    private fun saveEdit() {
        val text = binding.valueEditor.text?.toString().orEmpty()
        // 文本和类型都没变：直接退出编辑态，不必写回
        if (text == originalText && valueType == originalType) {
            exitEditMode()
            return
        }
        val raw = buildRawForSave(text)
        if (raw == null) {
            toast(invalidHint())
            return
        }
        // onSave 返回 false 表示主界面的整体校验没通过（文本已变/定位失败等），此时留在编辑态
        if (onSave?.invoke(raw) == true) {
            exitEditMode()
            toast("已保存修改")
            dismissAllowingStateLoss()
        }
    }

    /** 把编辑框里的文本按当前类型转成可写回原文的 JSON 片段；非法返回 null */
    private fun buildRawForSave(text: String): String? = ValueDraft.toRawJson(text, valueType)

    private fun editHint(): String = ValueDraft.hint(valueType)

    private fun invalidHint(): String = ValueDraft.invalidHint(valueType)

    override fun onStart() {
        super.onStart()
        // 弹窗固定在展开高度：内容在内部独立滚动，避免「滚到底被弹窗拉下」
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            isHideable = false
            skipCollapsed = true
            state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    // ---------- 可拖动滚动条（快速滚动 + 平时隐藏 / 滚动出现 / 停止自动隐藏） ----------

    private var draggingThumb = false
    private var thumbDownY = 0f
    private var thumbDownScroll = 0
    private val hideThumbRunnable = Runnable { binding.scrollThumb.visibility = View.GONE }

    private fun setupScrollThumb() {
        // 内容滚动时：更新 thumb 位置并显示（随后自动隐藏）
        binding.valueScroll.setOnScrollChangeListener(
            NestedScrollView.OnScrollChangeListener { _, _, _, _, _ ->
                updateScrollThumb()
                showScrollThumb()
            }
        )
        // 拖动滚动条快速滚动
        binding.scrollThumb.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    draggingThumb = true
                    thumbDownY = e.rawY
                    thumbDownScroll = binding.valueScroll.scrollY
                    showScrollThumb()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingThumb) {
                        val containerH = binding.valueScrollContainer.height
                        val thumbH = binding.scrollThumb.height
                        val maxOffset = (containerH - thumbH).coerceAtLeast(1)
                        val child = binding.valueScroll.getChildAt(0)
                        val range = (child?.height ?: 0) - binding.valueScroll.height
                        if (range > 0 && maxOffset > 0) {
                            val dy = e.rawY - thumbDownY
                            val newScroll = (thumbDownScroll + dy / maxOffset * range)
                                .toInt().coerceIn(0, range)
                            binding.valueScroll.scrollTo(0, newScroll)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingThumb = false
                    showScrollThumb() // 松手后重新计时自动隐藏
                    true
                }
                else -> false
            }
        }
        binding.valueScroll.post { updateScrollThumb() }
    }

    private fun showScrollThumb() {
        binding.scrollThumb.removeCallbacks(hideThumbRunnable)
        binding.scrollThumb.visibility = View.VISIBLE
        binding.scrollThumb.postDelayed(hideThumbRunnable, 1200)
    }

    /** 按滚动比例/可视比例更新滚动条 thumb 的位置与长度；内容不足一屏时隐藏 */
    private fun updateScrollThumb() {
        val sv = binding.valueScroll
        val container = binding.valueScrollContainer
        val child = sv.getChildAt(0) ?: return
        val contentH = child.height
        val viewH = sv.height
        if (viewH <= 0 || contentH <= viewH) {
            binding.scrollThumb.visibility = View.GONE
            return
        }
        val trackH = container.height
        if (trackH <= 0) return
        val density = resources.displayMetrics.density
        val minThumb = (32 * density).toInt()
        val thumbH = (viewH.toFloat() / contentH * trackH).toInt().coerceIn(minThumb, trackH)
        val maxOffset = (trackH - thumbH).coerceAtLeast(1)
        val maxScroll = (contentH - viewH).coerceAtLeast(1)
        val ratio = (sv.scrollY.toFloat() / maxScroll).coerceIn(0f, 1f)
        val top = (ratio * maxOffset).toInt()
        val lp = binding.scrollThumb.layoutParams as FrameLayout.LayoutParams
        if (lp.height != thumbH || lp.topMargin != top) {
            lp.height = thumbH
            lp.topMargin = top
            binding.scrollThumb.layoutParams = lp
        }
    }

    // ---------- 值内搜索 ----------

    private fun runValueSearch() {
        val q = binding.searchInputValue.text?.toString().orEmpty()
        searchMatches.clear()
        if (q.isNotEmpty()) {
            var idx = shownValue.indexOf(q, 0, ignoreCase = true)
            while (idx >= 0 && searchMatches.size < MAX_VALUE_MATCHES) {
                searchMatches.add(idx until idx + q.length)
                idx = shownValue.indexOf(q, idx + q.length, ignoreCase = true)
            }
        }
        searchIndex = if (searchMatches.isEmpty()) -1 else 0
        updateValueSearchLabel()
        highlightValue()
        if (searchIndex >= 0) scrollToMatch()
    }

    private fun updateValueSearchLabel() {
        binding.searchCountValue.text = when {
            searchMatches.isEmpty() -> "0/0"
            searchMatches.size >= MAX_VALUE_MATCHES -> "${searchIndex + 1}/$MAX_VALUE_MATCHES+"
            else -> "${searchIndex + 1}/${searchMatches.size}"
        }
    }

    /**
     * 把命中点成高亮 span：当前命中用 search_current，其余用 search_match。
     * 关键优化：直接在 TextView 已持有的 Spannable 上增删 span，
     * 不再每次输入都新建一份 20 万字符的 SpannableString 并 setText 触发全量重排。
     * 这样每次搜索只会让「命中所在的行」失效重排，而不是整段文本。
     */
    private fun highlightValue() {
        val sp = binding.valueFull.text as? Spannable ?: return
        sp.getSpans(0, sp.length, BackgroundColorSpan::class.java).forEach { sp.removeSpan(it) }
        if (searchMatches.isEmpty()) return
        val colors = ThemeManager.activeColors
        searchMatches.forEachIndexed { i, r ->
            val s = r.first.coerceIn(0, sp.length)
            val e = (r.last + 1).coerceIn(s, sp.length)
            if (e > s) {
                val color = if (i == searchIndex) colors.searchCurrent else colors.searchMatch
                sp.setSpan(
                    BackgroundColorSpan(color),
                    s,
                    e,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
    }

    /** 滚动到当前命中行（让命中行显示在视口偏上位置） */
    private fun scrollToMatch() {
        val range = searchMatches.getOrNull(searchIndex) ?: return
        binding.valueFull.post {
            val tv = binding.valueFull
            val layout = tv.layout ?: return@post
            val line = layout.getLineForOffset(range.first)
            val y = layout.getLineTop(line)
            binding.valueScroll.smoothScrollTo(0, (y - tv.paddingTop).coerceAtLeast(0))
        }
    }

    private fun valueSearchNext() {
        if (searchMatches.isEmpty()) return
        searchIndex = (searchIndex + 1) % searchMatches.size
        updateValueSearchLabel()
        highlightValue()
        scrollToMatch()
    }

    private fun valueSearchPrev() {
        if (searchMatches.isEmpty()) return
        searchIndex = (searchIndex - 1 + searchMatches.size) % searchMatches.size
        updateValueSearchLabel()
        highlightValue()
        scrollToMatch()
    }

    private fun closeValueSearch() {
        binding.searchInputValue.setText("")
        searchMatches.clear()
        searchIndex = -1
        binding.searchCountValue.text = "0/0"
        // 只清 span，不重设文本
        highlightValue()
    }

    override fun onDestroyView() {
        searchHandler.removeCallbacks(searchRunnable)
        super.onDestroyView()
    }

    private fun toast(msg: String) {
        // 用可空 context：面板已 detach（如旋转屏幕、被系统回收）时直接放弃，别让 toast 把进程带走
        val ctx = context ?: return
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }
}
