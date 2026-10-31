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
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.json.JsonError
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.text.LineStarts
import com.guaner.jsonviewer.theme.ThemeManager
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticRegion
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
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

    /** 业务侧是否允许编辑（值为 null 时锁住），与设置开关相互独立 */
    private var isEnabledForUser = true

    /** 因超长行自动开启换行时回调一次（用于提示用户） */
    var onAutoWrap: (() -> Unit)? = null

    /** 文本变化回调（用户输入与代码设置都会触发） */
    var onTextChanged: (() -> Unit)? = null

    /** 内容超出安全上限时回调（用于提示，不截断） */
    var onOversize: ((Int) -> Unit)? = null

    /**
     * 撤销栈状态变化（可撤销 / 可重做）时回调，用来刷新底栏按钮的可用态。
     *
     * 参数依次为 `canUndo`、`canRedo`。用户编辑、程序化换文本、撤销重做本身都会触发，
     * 但只在值真的变化时才回调，避免每次按键都刷一遍底栏。
     */
    var onUndoStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)? = null
    private var lastCanUndo = false
    private var lastCanRedo = false
    private var oversizeWarned = false

    /** 程序性设置文本期间不回调 [onTextChanged]，避免「恢复会话」被当成用户编辑 */
    private var suppressCallback = false

    init {
        // 编辑器自己就是 View，去掉背景边框，背景色由主题决定
        setBackgroundColor(themeColors.background)
        setEditorLanguage(language)

        // 代码场景：硬键盘 Tab 用空格更符合 JSON 习惯
        setTabWidth(4)

        // 关闭自动补全：JSON 里自动补全反而容易改坏内容（此开关不开放给用户）
        props.apply {
            autoCompletionOnComposing = false
            disallowSuggestions = true
        }

        // 不让系统把编辑器内容塞进 View 状态 Bundle：
        // 几百 KB 的文本会在切后台 / 转屏时触发一次巨大的 Parcel 写入（卡顿甚至崩溃）。
        // 内容恢复由应用自己走会话存储。
        isSaveEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS

        // 各项行为开关统一从设置读取（见 AppSettings 的 editor* 字段）
        applyEditorOptions()

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
            // 每次编辑都检测一次「是否出现超长行」：粘贴一整段无换行的文本时，
            // 之前只有 setText() 才检测，用户手动粘贴根本不会触发，于是换行永远不生效
            maybeAutoWrapForLongLine(edited = true)
            onTextChanged?.invoke()
            // 每次编辑都可能让撤销栈从「空」变「非空」，顺手同步按钮可用态
            dispatchUndoState()
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
     * 判断触摸点是否落在滚动条上（含一点容差）。
     *
     * 滚动条本身就贴着控件右/下边缘，而应用又用「从右边缘向内滑」打开大纲，
     * 两者位置完全重叠：用户想拖滚动条，实际却被当成划开大纲。
     * 外部在决定是否接管边缘手势前，先用这个判断让路。
     *
     * 只有开启了滚动条且内容真的超出一屏时才认，否则普通边缘区域仍可正常划出抽屉。
     */
    fun isTouchOnScrollBar(x: Float, y: Float): Boolean {
        val touchSlop = resources.displayMetrics.density * SCROLL_BAR_TOUCH_DP
        if (isVerticalScrollBarEnabled() && scrollMaxY > 0 && x >= width - touchSlop) return true
        if (isHorizontalScrollBarEnabled() && scrollMaxX > 0 && y >= height - touchSlop) return true
        return false
    }

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
        // 导入 / 恢复是一次性动作，这里可以放心全量扫一遍最长行
        maybeAutoWrapForLongLine(edited = false)
        if (forceTop) {
            setCursorOffset(0)
            post { scrollTo(0, 0) }
        }
        currentError?.let { applyDiagnostics(it) }
        // setText() 会新建 Content 实例，撤销栈也跟着重置，这里按设置恢复一次
        getText().setUndoEnabled(AppSettings.editorUndo)
        // 编辑器换了一整篇新内容，旧的行缓存全部作废，重新下发一次着色
        language.notifyContentReplaced()
        // 程序性替换（导入 / 格式化 / 清空 / 恢复）不会走用户编辑的回调，
        // 但调用方依赖它去重新解析、刷新表单与大纲，所以这里主动通知一次。
        onTextChanged?.invoke()
        // 换了一整篇内容，撤销栈已被重置，按钮可用态要跟着变
        dispatchUndoState()
    }

    /**
     * 检测是否需要为「超长单行」临时开启软换行。
     *
     * 参数 [edited]：
     * - `false`（整篇替换）：直接全量扫最长行，一次 O(n) 可以接受
     * - `true`（用户编辑/粘贴）：**只检查本次编辑影响的行**，绝不全量扫描。
     *   否则粘贴 500KB 文本后，每敲一个键都扫一遍全文，主线程直接卡死。
     *
     * 之前这里只在 `setText()` 里检测，导致用户手动粘贴的长文本永远触发不到换行，
     * 于是一整行几十万字符挤在一行里显示、横向排版每帧都要从头累加像素宽度 → 左右滑动卡死。
     */
    private fun maybeAutoWrapForLongLine(edited: Boolean) {
        val content = getText()
        val len = content.length
        if (len == 0) {
            if (autoWrapped) {
                autoWrapped = false
                setWordwrap(userWrap, true)
            }
            return
        }
        val longest = if (edited) {
            maxLineLengthAroundCursor(content)
        } else {
            LineStarts.maxLineLength(content)
        }
        if (longest > SUPER_LONG_LINE_CHARS) {
            if (!autoWrapped) {
                autoWrapped = true
                setWordwrap(true, true)
                onAutoWrap?.invoke()
            }
        } else if (autoWrapped && longest <= SUPER_LONG_LINE_RELEASE) {
            autoWrapped = false
            setWordwrap(userWrap, true)
        }
    }

    /**
     * 只看光标附近某一行附近的最长行，O(行数) 而不是 O(全文)。
     *
     * 粘贴/输入只可能影响光标所在的那一行（或粘贴内容跨的几行），
     * 检查它以当前行附近为主即可；跨多行的巨块粘贴由 [setTextProgrammatic] 全量兜底。
     */
    private fun maxLineLengthAroundCursor(content: Content): Int {
        val cursor = runCatching { getCursor() }.getOrNull() ?: return 0
        return LineStarts.maxLineLengthAround(
            line = cursor.leftLine,
            lineCount = content.lineCount,
            radius = SCAN_AROUND_LINES
        ) { i -> runCatching { content.getColumnCount(i) }.getOrDefault(0) }
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

    /**
     * 跳转并高亮一段区间（大纲 / 快捷跳转）。
     *
     * **走的是和语法着色 / 搜索命中同一套样式通道**：把高亮区间交给 [JsonSpansLanguage]，
     * 由它在对应的行里插一个带背景色的 `Span`（`TextStyle.makeStyle(fg, bg, ...)`），
     * 剩下的交给 Sora 自己排版绘制——它天然知道每个字符在哪、软换行怎么折。
     *
     * 早前试过两条歪路，都不行：
     * 1. 诊断通道 `setDiagnostics`：`drawDiagnosticIndicators` 只画贴基线的波浪线/下划线，
     *    而且 `SEVERITY_NONE` 映射到颜色 id 0 会被整段跳过，根本画不出背景块。
     * 2. 自己重写 `onDraw` 按坐标画矩形：`getCharLayoutOffset` 只认逻辑行号，
     *    软换行、Tab、比例字体全部会算歪，维护成本极高。
     *
     * 现在两层高亮都在语言层完成：
     * - **整行背景**：整行下发一个背景色 Span（浅色带）
     * - **字段名背景**：目标区间单独下发更重的背景色 Span，天然覆盖在整行带之上
     */
    fun flashRange(start: Int, end: Int) {
        val content = getText()
        val len = content.length
        if (len == 0) return
        val s = start.coerceIn(0, len - 1)
        val e = end.coerceIn(s + 1, len)

        language.setFlashRange(s, e)
        jumpTo(s, takeFocus = false)
        handler.removeCallbacks(clearFlashRunnable)
        handler.postDelayed(clearFlashRunnable, FLASH_MS)
    }

    private val clearFlashRunnable = Runnable {
        language.setFlashRange(-1, -1)
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

    // ---------- 撤销 / 重做 ----------

    /**
     * 撤销一步。
     *
     * 基类 [CodeEditor.undo] 已经做了文本恢复 + 选区恢复 + 重绘，这里只补两件事：
     * 1. 尊重设置里的「允许撤销」（关掉时按钮不该生效）
     * 2. 通知外部重新解析表单/大纲，并刷新按钮可用态
     *
     * @return false 表示没有可撤销的内容（或撤销被关闭）
     */
    fun undoStep(): Boolean {
        if (!AppSettings.editorUndo) return false
        if (!runCatching { canUndo() }.getOrDefault(false)) return false
        val ok = runCatching { undo(); true }.getOrDefault(false)
        if (ok) notifyAfterUndoRedo()
        return ok
    }

    /** 重做一步；[undoStep] 的反向操作 */
    fun redoStep(): Boolean {
        if (!AppSettings.editorUndo) return false
        if (!runCatching { canRedo() }.getOrDefault(false)) return false
        val ok = runCatching { redo(); true }.getOrDefault(false)
        if (ok) notifyAfterUndoRedo()
        return ok
    }

    /** 当前是否还能撤销（设置关掉时恒为 false，按钮跟着置灰） */
    fun canUndoStep(): Boolean =
        AppSettings.editorUndo && runCatching { canUndo() }.getOrDefault(false)

    /** 当前是否还能重做 */
    fun canRedoStep(): Boolean =
        AppSettings.editorUndo && runCatching { canRedo() }.getOrDefault(false)

    /** 撤销/重做后：把光标滚到可视区，并补齐业务回调 */
    private fun notifyAfterUndoRedo() {
        runCatching {
            val cursor = getCursor()
            jumpTo(cursor.left, takeFocus = false)
        }
        onTextChanged?.invoke()
        dispatchUndoState()
    }

    /** 把当前撤销栈状态同步给外部（值没变就不回调，避免每次按键刷底栏） */
    fun dispatchUndoState() {
        val u = canUndoStep()
        val r = canRedoStep()
        if (u == lastCanUndo && r == lastCanRedo) return
        lastCanUndo = u
        lastCanRedo = r
        onUndoStateChanged?.invoke(u, r)
    }

    // ---------- Sora 原生搜索（正则 / 替换） ----------

    /**
     * 用 Sora 的 [io.github.rosemoe.sora.widget.EditorSearcher] 做搜索。
     *
     * 相比自己扫 `CharSequence.indexOf`，这里多了**正则**能力，且搜索在 Sora 内部线程池
     * 跑、超大文本不卡主线程。命中高亮与「当前命中」由 Sora 自己维护，不用再走诊断通道。
     *
     * @param pattern 关键词或正则
     * @param regex true 时按正则解析
     * @param caseSensitive true 时区分大小写
     * @param callback 搜索结束（结果已就绪）后回主线程回调，参数为命中总数
     * @return false 表示正则非法，已自动回退到普通搜索
     */
    fun searchWithSora(
        pattern: String,
        regex: Boolean,
        caseSensitive: Boolean,
        callback: ((count: Int) -> Unit)? = null
    ): Boolean {
        val searcher = getSearcher()
        if (pattern.isEmpty()) {
            searcher.stopSearch()
            searchMatches = emptyList()
            searchCurrent = -1
            applyDiagnostics(currentError)
            callback?.invoke(0)
            return true
        }
        var fallback = false
        // 正则先自己编译一次：非法表达式直接本地拦下，避免 Sora 内部抛 PatternSyntaxException 崩掉
        if (regex && runCatching { java.util.regex.Pattern.compile(pattern) }.isFailure) {
            fallback = true
        }
        val type = if (regex && !fallback) {
            EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
        } else {
            EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        searcher.setCyclicJumping(true)
        searcher.search(pattern, EditorSearcher.SearchOptions(type, !caseSensitive))
        // 清空自定义诊断（错误除外），把高亮交给 Sora
        searchMatches = emptyList()
        searchCurrent = -1
        applyDiagnostics(currentError)
        if (callback != null) {
            // searcher.search 是异步的，下一帧再取结果
            postDelayed({ callback(searcher.matchedPositionCount) }, 60)
        }
        return !fallback
    }

    /** 跳到下一个命中；没有命中时返回 false */
    fun searchNext(): Boolean = runCatching { getSearcher().gotoNext() }.getOrDefault(false)

    /** 跳到上一个命中 */
    fun searchPrevious(): Boolean = runCatching { getSearcher().gotoPrevious() }.getOrDefault(false)

    /** 当前命中序号（从 0 开始，无命中返回 -1） */
    fun searchMatchIndex(): Int =
        runCatching { getSearcher().currentMatchedPositionIndex }.getOrDefault(-1)

    /** 命中总数 */
    fun searchMatchCount(): Int =
        runCatching { getSearcher().matchedPositionCount }.getOrDefault(0)

    /** 停止搜索，清掉高亮 */
    fun stopSearch() {
        runCatching { getSearcher().stopSearch() }
        searchMatches = emptyList()
        searchCurrent = -1
        applyDiagnostics(currentError)
    }

    /** 替换当前命中（未选中命中时等价于点一次「下一个」再替换） */
    fun replaceCurrent(replacement: String) {
        runCatching { getSearcher().replaceCurrentMatch(replacement) }
        notifyTextReplacedBySearch()
    }

    /** 替换全部命中 */
    fun replaceAllMatches(replacement: String, onDone: ((count: Int) -> Unit)? = null) {
        val total = searchMatchCount()
        runCatching {
            getSearcher().replaceAll(replacement) {
                // Sora 在替换完成后回调（主线程），这里再通知一次内容变更
                notifyTextReplacedBySearch()
                onDone?.invoke(total)
            }
        }.onFailure { onDone?.invoke(0) }
    }

    /** 替换会改内容但走的是 Sora 内部通道，这里补一次着色刷新与业务回调 */
    private fun notifyTextReplacedBySearch() {
        getText().setUndoEnabled(AppSettings.editorUndo)
        language.notifyContentReplaced()
        onTextChanged?.invoke()
    }

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
            // 跳转高亮（整行色带 + 字段名色块）已全部改由 onDraw 自绘，
            // 这里不再下发诊断区——诊断区只会画波浪线/下划线，画不出色块。
        }
        // 直接复用编辑器自己的诊断通道：语言分析器只负责着色，诊断由本视图按需覆盖
        setDiagnostics(container)
    }

    // ---------- 主题 / 字体 / 换行 ----------

    fun setThemeColors(colors: ThemeColors) {
        themeColors = colors
        setBackgroundColor(colors.background)
    }

    /**
     * 应用「设置 → 代码编辑器」里的行为开关。
     *
     * 全部走 Sora 自己的开关，改完立即生效，不需要重启或重建编辑器。
     */
    fun applyEditorOptions() {
        setLineNumberEnabled(AppSettings.editorLineNumbers)
        setLineNumbersPinned(AppSettings.editorPinLineNumber)
        setHighlightCurrentLine(AppSettings.editorHighlightCurrentLine)
        setHighlightBracketPair(AppSettings.editorHighlightBrackets)
        setScrollBarEnabled(AppSettings.editorScrollBar)
        setEditable(isEnabledForUser)
        // 撤销栈是 Content 层的开关（关掉后不再记录历史，省一点内存）
        getText().setUndoEnabled(AppSettings.editorUndo)
        props.apply {
            autoIndent = AppSettings.editorAutoIndent
            symbolPairAutoCompletion = AppSettings.editorSymbolPair
            scrollFling = AppSettings.editorScrollFling
            overScrollEnabled = AppSettings.editorOverScroll
            // 允许「只朝一个方向」的惯性滑动：横向内容长时，斜着滑也不会被纵向拖动卡住
            singleDirectionFling = true
            highlightMatchingDelimiters = AppSettings.editorHighlightBrackets
        }
    }

    /** Sora 的行号气泡（滚动时常驻显示当前行号） */
    private fun setLineNumbersPinned(pinned: Boolean) {
        setPinLineNumber(pinned)
        setDisplayLnPanel(pinned)
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
        setWordwrap(userWrap, true)
        applyEditorOptions()
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
        if (!autoWrapped) setWordwrap(wrap, true)
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
                // 按在滚动条上：这是「拖滚动条」，不是「从边缘划开大纲」。
                // 滚动条贴着右边缘，和边缘手势区完全重叠，不排除就会边拖边弹大纲。
                if (isTouchOnScrollBar(e.x, e.y)) {
                    edgeTracking = false
                    edgeTriggered = false
                    return false
                }
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
        isEnabledForUser = editable
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

        /** 用户编辑后检查超长行时，只看光标前后各几行（避免每次按键扫全文） */
        const val SCAN_AROUND_LINES = 3

        /** 判定「点在滚动条上」的横向容差（dp）；滚动条本身更窄，留点余量好按 */
        const val SCROLL_BAR_TOUCH_DP = 22f
    }
}
