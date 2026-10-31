package com.guaner.jsonviewer.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import com.guaner.jsonviewer.editor.JsonEditorTheme
import com.guaner.jsonviewer.editor.JsonSpansLanguage
import io.github.rosemoe.sora.text.Content
import com.guaner.jsonviewer.json.JsonError
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.text.LineStarts
import com.guaner.jsonviewer.theme.ThemeManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import kotlin.math.abs

/**
 * 代码模式编辑器：基于 [Sora Editor](https://github.com/Rosemoe/sora-editor)（LGPL-2.1）。
 *
 * 相比旧的自研 EditText 方案，这里把「排版、行号、滚动、选区、撤销栈」全部交给
 * Sora 的自绘实现——它按行缓存布局、只绘制可视区域，几 MB 文本下输入/删除同样跟手。
 *
 * 对外 API 与旧实现保持一致（[text]、[setTextProgrammatic]、[setError]、
 * [setSearchMatches]、[jumpTo] 等），调用方（MainActivity / TempCodeEditorDialog）无需改动。
 * 配色、字号、字体、换行全部由 [applyThemeFromManager] 从应用主题映射过来。
 */
class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : CodeEditor(context, attrs, defStyleAttr) {

    private val handler = Handler(Looper.getMainLooper())
    private val language = JsonSpansLanguage()

    private var themeColors: ThemeColors = ThemeManager.activeColors
    private var currentScale = ThemeManager.codeScale
    private var currentError: JsonError? = null

    /** 用户设置的换行 */
    private var userWrap = ThemeManager.codeWrap

    /** 因超长单行临时开启的换行（内容变短后自动恢复用户设置） */
    private var autoWrapped = false

    /** 因超长行自动开启换行时回调一次（用于提示用户） */
    var onAutoWrap: (() -> Unit)? = null

    /** 文本变化回调（用户输入与代码设置都会触发） */
    var onTextChanged: (() -> Unit)? = null

    /** 内容超出安全上限时回调（用于提示，不截断） */
    var onOversize: ((Int) -> Unit)? = null
    private var oversizeWarned = false

    /** 程序性设置文本期间不回调 [onTextChanged]，避免「恢复会话」被当成用户编辑 */
    private var suppressCallback = false

    init {
        // 编辑器自己就是 View，去掉背景边框，背景色由主题决定
        setBackgroundColor(themeColors.background)
        setEditorLanguage(language)

        // 行号：显示，但不开「粘性行号面板」（代码页用不到，省一点绘制）
        setLineNumberEnabled(true)
        setPinLineNumber(false)
        setDisplayLnPanel(true)

        // 代码场景：硬键盘 Tab 用空格更符合 JSON 习惯；这里不需要 Tab 缩进
        setTabWidth(4)

        // 关闭自动补全/自动缩进：JSON 里这些「贴心功能」反而容易改坏内容
        props.apply {
            autoIndent = false
            symbolPairAutoCompletion = false
            autoCompletionOnComposing = false
            disallowSuggestions = true
            // 滚动惯性：手感与旧版一致
            scrollFling = true
            overScrollEnabled = true
            // 高亮匹配括号：JSON 结构清晰度的主要来源之一
            highlightMatchingDelimiters = true
        }

        // 不让系统把编辑器内容塞进 View 状态 Bundle：
        // 几百 KB 的文本会在切后台 / 转屏时触发一次巨大的 Parcel 写入（卡顿甚至崩溃）。
        // 内容恢复由应用自己走会话存储。
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        // 滚动条用 Sora 自带的（跟随主题配色），保留系统滚动条会与自绘重叠
        setScrollBarEnabled(true)

        subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
            val text = getText()
            val len = text.length
            if (len > JsonLimits.MAX_INPUT_CHARS && !oversizeWarned) {
                oversizeWarned = true
                onOversize?.invoke(len)
            } else if (len <= JsonLimits.MAX_INPUT_CHARS) {
                oversizeWarned = false
            }
            // 整段替换（导入/格式化/恢复）由调用方自己控制提示，这里只关心用户编辑
            if (suppressCallback) return@subscribeEvent
            if (event.action == ContentChangeEvent.ACTION_SET_NEW_TEXT) return@subscribeEvent
            onTextChanged?.invoke()
        }

        applyThemeFromManager()
    }

    // ---------- 文本读写 ----------

    var text: String
        get() = getText().toString()
        set(value) = setTextProgrammatic(value)

    /** 只读遍历用的文本视图：不复制内容（大文本下避免每次搜索全量拷贝） */
    val textSequence: CharSequence
        get() = getText()

    /**
     * 原位替换一段文本（值编辑保存用）。
     *
     * 不重置滚动位置与选区：编辑某个值之后用户还希望停留在原来的位置。
     */
    fun replaceRange(start: Int, end: Int, newText: String) {
        val content = getText()
        val len = content.length
        if (start < 0 || end > len || start > end) return
        suppressCallback = true
        try {
            content.replace(start, end, newText)
        } finally {
            suppressCallback = false
        }
    }

    /**
     * 代码设置文本（导入/格式化/恢复会话后调用）。
     *
     * [forceTop] = true 时回到顶部；false 用于「恢复上次内容」，保持光标在 0 但不强制滚动。
     */
    fun setTextProgrammatic(value: String, forceTop: Boolean = true) {
        suppressCallback = true
        try {
            setText(value)
        } finally {
            suppressCallback = false
        }
        oversizeWarned = getText().length > JsonLimits.MAX_INPUT_CHARS
        // 单行几十万字符时横向排版极贵（每帧都要从行首累加像素宽度），先临时关掉换行退避。
        // 这里只为自保：正常 JSON 不会出现这种行，超长行通常来自用户粘贴的其它文本。
        if (value.isNotEmpty() && LineStarts.maxLineLength(value) > SUPER_LONG_LINE_CHARS && !userWrap && !autoWrapped) {
            setWordwrap(true, false)
            autoWrapped = true
            onAutoWrap?.invoke()
        } else if (autoWrapped && LineStarts.maxLineLength(value) <= SUPER_LONG_LINE_RELEASE) {
            autoWrapped = false
            setWordwrap(userWrap, false)
        }
        if (forceTop) {
            setCursorOffset(0)
            post { scrollTo(0, 0) }
        }
        currentError?.let { applyDiagnostics(it) }
    }

    /** 置顶：滚到第一行 */
    fun scrollToTop() = post { scrollTo(0, 0) }

    /** 置底：滚到最后一行 */
    fun scrollToBottom() = post { scrollTo(0, scrollMaxY) }

    /** 指定字符偏移并滚动到可视区中部 */
    fun jumpTo(offset: Int, takeFocus: Boolean = true) {
        val content = getText()
        val len = content.length
        if (len == 0) return
        val o = offset.coerceIn(0, len)
        val pos = content.getIndexer().getCharPosition(o)
        if (takeFocus) requestFocus()
        // takeFocus=false（搜索逐字输入）时也不抢焦点，否则会把焦点从搜索框抢走导致「打一个字就断」
        setSelection(pos.line, pos.column)
        post { ensurePositionVisible(pos.line, pos.column) }
    }

    fun requestEditorFocus() {
        requestFocus()
    }

    /** 短暂高亮一段区间（大纲/快捷跳转定位） */
    fun flashRange(start: Int, end: Int) {
        val content = getText()
        val len = content.length
        if (len == 0) return
        val s = start.coerceIn(0, len - 1)
        val e = end.coerceIn(s + 1, len)
        flashStart = s
        flashEnd = e
        applyDiagnostics(currentError)
        jumpTo(s, takeFocus = false)
        handler.removeCallbacks(clearFlashRunnable)
        handler.postDelayed(clearFlashRunnable, FLASH_MS)
    }

    private var flashStart = -1
    private var flashEnd = -1
    private val clearFlashRunnable = Runnable {
        flashStart = -1
        flashEnd = -1
        applyDiagnostics(currentError)
    }

    // ---------- 错误与搜索高亮 ----------

    fun setError(error: JsonError?) {
        currentError = error
        applyDiagnostics(error)
    }

    /**
     * 搜索命中：高亮由应用自己用诊断区渲染（Sora 自带的搜索模式会接管交互，
     * 而这里需要「外部搜索框驱动、编辑器只负责显示」的模式）。
     */
    fun setSearchMatches(matches: List<IntRange>, current: Int) {
        searchMatches = matches
        searchCurrent = current
        applyDiagnostics(currentError)
    }

    private var searchMatches: List<IntRange> = emptyList()
    private var searchCurrent: Int = -1

    /** 把错误行 / 错误列 / 搜索命中 / 定位闪烁统一转成诊断区下发给编辑器 */
    private fun applyDiagnostics(error: JsonError?) {
        val container = DiagnosticsContainer()
        val content = getText()
        val len = content.length
        if (len > 0) {
            error?.let { err ->
                val indexer = content.getIndexer()
                val line = err.line.coerceIn(1, content.lineCount)
                val lineStart = indexer.getCharIndex(line - 1, 0)
                val lineEnd = if (line < content.lineCount) indexer.getCharIndex(line, 0) - 1 else len
                if (lineEnd > lineStart) {
                    container.addDiagnostic(
                        DiagnosticRegion(lineStart, lineEnd, DiagnosticRegion.SEVERITY_ERROR)
                    )
                }
                val colOffset = err.offset.coerceIn(0, len - 1)
                container.addDiagnostic(
                    DiagnosticRegion(colOffset, (colOffset + 1).coerceAtMost(len), DiagnosticRegion.SEVERITY_WARNING)
                )
            }
            if (searchMatches.isNotEmpty()) {
                var budget = MAX_SEARCH_REGIONS
                searchMatches.forEachIndexed { i, r ->
                    if (budget <= 0) return@forEachIndexed
                    val s = r.first.coerceIn(0, len)
                    val e = (r.last + 1).coerceIn(s, len)
                    if (e > s) {
                        container.addDiagnostic(
                            DiagnosticRegion(s, e, if (i == searchCurrent)
                                DiagnosticRegion.SEVERITY_WARNING else DiagnosticRegion.SEVERITY_TYPO)
                        )
                        budget--
                    }
                }
            }
            if (flashStart >= 0 && flashEnd > flashStart) {
                container.addDiagnostic(
                    DiagnosticRegion(
                        flashStart.coerceIn(0, len),
                        flashEnd.coerceIn(0, len),
                        DiagnosticRegion.SEVERITY_TYPO
                    )
                )
            }
        }
        // 直接复用编辑器自己的诊断通道：语言分析器只负责着色，诊断由本视图按需覆盖
        setDiagnostics(container)
    }

    // ---------- 主题 / 字体 / 换行 ----------

    fun setThemeColors(colors: ThemeColors) {
        themeColors = colors
        setBackgroundColor(colors.background)
    }

    /** 从 ThemeManager 读取当前设置并应用：配色、字体族、字号(含缩放)、换行 */
    fun applyThemeFromManager() {
        themeColors = ThemeManager.activeColors
        currentScale = ThemeManager.codeScale
        val tf = ThemeManager.resolveTypeface()
        setTypefaceText(tf)
        setTypefaceLineNumber(tf)
        setColorScheme(JsonEditorTheme.schemeOf(themeColors))
        setBackgroundColor(themeColors.background)
        applyFontSize((ThemeManager.codeFontSize * currentScale).coerceIn(8f, 40f))
        userWrap = ThemeManager.codeWrap
        setWordwrap(userWrap, false)
        applyDiagnostics(currentError)
    }

    private fun applyFontSize(sp: Float) {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
        setTextSizePx(px)
    }

    /** 双指缩放期间把字号变更合并到下一帧：避免每帧重建布局造成卡顿 */
    private var fontApplyPending = false
    private fun applyFontSizeThrottled() {
        if (fontApplyPending) return
        fontApplyPending = true
        post {
            fontApplyPending = false
            applyFontSize((ThemeManager.codeFontSize * currentScale).coerceIn(8f, 40f))
        }
    }

    fun setFont(tf: Typeface) {
        setTypefaceText(tf)
        setTypefaceLineNumber(tf)
    }

    fun setWrap(wrap: Boolean) {
        userWrap = wrap
        if (!autoWrapped) setWordwrap(wrap, false)
    }

    // ---------- 双指缩放（Sora 默认不支持，这里补上） ----------

    private var scaleDetector: android.view.ScaleGestureDetector? = null

    private fun ensureScaleDetector() {
        if (scaleDetector != null) return
        scaleDetector = android.view.ScaleGestureDetector(
            context,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    if (!ThemeManager.codeZoom) return false
                    currentScale = (currentScale * detector.scaleFactor).coerceIn(0.5f, 3f)
                    applyFontSizeThrottled()
                    return true
                }

                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    ThemeManager.codeScale = currentScale
                }
            }
        )
    }

    // ---------- 边缘内滑：呼出结构大纲 ----------

    /** 由 MainActivity 注入：命中「边缘内滑」时打开大纲抽屉，参数为是否从右边缘起手 */
    var onEdgeSwipe: ((fromRight: Boolean) -> Unit)? = null

    /**
     * 由 MainActivity 注入：DOWN 命中边缘时先把面板摆到起手那侧。
     *
     * 必须在 DOWN 就做——等滑到阈值再摆，面板已经从旧位置开始动了，
     * 双侧模式下看起来就是「从屏幕对面飞过来」。
     */
    var onEdgeHint: ((fromRight: Boolean) -> Unit)? = null

    /** 大纲从右侧滑出时置 true：改为识别「右边缘向左滑」 */
    var edgeSwipeFromRight = false

    /** 关闭后完全不识别边缘手势（设置里选「滑动不呼出」时） */
    var edgeSwipeEnabled = true

    /** 双侧模式：左右边缘向内滑都能呼出，面板从起手的那一侧出 */
    var edgeSwipeBoth = false

    private var edgeTracking = false
    private var edgeTriggered = false
    private var edgeFromRightSide = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    private val touchSlop by lazy { ViewConfiguration.get(context).scaledTouchSlop.toFloat() }
    private val edgeWidthPx by lazy { 48 * resources.displayMetrics.density }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        ensureScaleDetector()
        scaleDetector?.onTouchEvent(event)

        // 两指：收缩/张开用于缩放，不再把事件交给编辑器（否则光标会乱跳）
        if (event.pointerCount >= 2) {
            return true
        }

        // 先识别「边缘内滑」，命中直接消费，避免被编辑器当成横向滚动/文本选择
        if (handleEdgeSwipe(event)) return true

        return super.onTouchEvent(event)
    }

    /**
     * 识别从编辑器边缘开始、朝屏幕内侧的滑动。
     *
     * 理想路径：DOWN 命中边缘 → onEdgeHint 先摆好面板 → 放行父级拦截 →
     * DrawerLayout 自己接管拖动（完全跟手）。若抽屉因任何原因没接管，
     * 下面的显式判定会兜底：内滑位移明显大于纵向时消费手势并回调 onEdgeSwipe。
     */
    private fun handleEdgeSwipe(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val leftHit = e.x <= edgeWidthPx
                val rightHit = e.x >= width - edgeWidthPx
                edgeFromRightSide = if (edgeSwipeBoth) rightHit else edgeSwipeFromRight
                // 正在选中文字时不识别边缘手势，避免选词误呼出大纲
                val selecting = cursor.isSelected
                edgeTracking = edgeSwipeEnabled && !selecting && when {
                    edgeSwipeBoth -> leftHit || rightHit
                    edgeSwipeFromRight -> rightHit
                    else -> leftHit
                }
                if (edgeTracking) {
                    onEdgeHint?.invoke(edgeFromRightSide)
                    // 编辑器在 DOWN 时会 requestDisallowInterceptTouchEvent(true)，
                    // DrawerLayout 因此收不到后续 MOVE、没法自己跟手拖动。
                    // DOWN 处理完再放行（此时 disallow 已生效过一次），抽屉就能接管这次拖动。
                    post { parent?.requestDisallowInterceptTouchEvent(false) }
                }
                edgeTriggered = false
                edgeDownX = e.x
                edgeDownY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeTracking && cursor.isSelected) edgeTracking = false
                if (edgeTracking && !edgeTriggered) {
                    val dx = e.x - edgeDownX
                    val dy = abs(e.y - edgeDownY)
                    val inward = if (edgeFromRightSide) -dx else dx
                    if (inward > touchSlop && inward > dy * 1.5f) {
                        edgeTriggered = true
                        onEdgeSwipe?.invoke(edgeFromRightSide)
                        return true
                    }
                    if (dy > touchSlop * 2 || inward < -touchSlop) edgeTracking = false
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val consumed = edgeTriggered
                edgeTracking = false
                edgeTriggered = false
                if (consumed) return true
            }
        }
        return edgeTriggered
    }

    // ---------- EditText 兼容层 ----------
    //
    // 值编辑面板（ValueSheet）原先用 EditText，迁移过来时保留这几个同名方法，
    // 语义按 EditText 对齐，调用方几乎不用改。

    /** 与 EditText 一致：整段替换并把光标收到开头 */
    fun setEditableText(text: CharSequence) {
        setTextProgrammatic(text.toString(), forceTop = false)
        setCursorOffset(0)
    }

    fun getEditableText(): String = getText().toString()

    /**
     * 可编辑开关。
     *
     * Sora 自己不看 View 的 enabled 状态（设 false 依然能输入），必须走 [setEditable]；
     * 同时把 View 置灰，让用户一眼看出「这一格现在不能改」（值为 null 时）。
     */
    fun setEditorEditable(editable: Boolean) {
        setEditable(editable)
        alpha = if (editable) 1f else 0.55f
    }

    /** EditText 语义的选区：按字符偏移设置（起止同一位置即放置光标） */
    fun setSelectionOffsets(start: Int, end: Int) {
        val content = getText()
        val len = content.length
        val s = start.coerceIn(0, len)
        val e = end.coerceIn(s, len)
        val sp = content.getIndexer().getCharPosition(s)
        val ep = content.getIndexer().getCharPosition(e)
        if (s == e) {
            setSelection(sp.line, sp.column)
        } else {
            setSelectionRegion(sp.line, sp.column, ep.line, ep.column)
        }
    }

    /** 把光标放到 [index] */
    fun setCursorOffset(index: Int) = setSelectionOffsets(index, index)

    fun setHintTextColor(color: Int) {
        // Sora 没有 hint 概念，保留空实现让调用方无需分支
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clearFlashRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 单次最多下发的搜索命中诊断区数量 */
        const val MAX_SEARCH_REGIONS = 200

        /** 定位闪烁高亮的持续时间 */
        const val FLASH_MS = 1_600L

        /** 单行超过该长度时临时开启软换行（避免一屏排成几十万像素的一行） */
        const val SUPER_LONG_LINE_CHARS = 20_000

        /** 最长行降到该长度以下才恢复用户的换行设置（滞回，避免临界值反复切换） */
        const val SUPER_LONG_LINE_RELEASE = 10_000
    }
}
