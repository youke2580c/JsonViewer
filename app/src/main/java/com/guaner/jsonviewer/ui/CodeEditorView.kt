package com.guaner.jsonviewer.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.AttributeSet
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.Toast
import com.guaner.jsonviewer.editor.JsonEditorTheme
import com.guaner.jsonviewer.editor.JsonSpansLanguage
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.json.JsonError
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.ParseResult
import com.guaner.jsonviewer.text.ImeCommit
import com.guaner.jsonviewer.text.ImeSelection
import com.guaner.jsonviewer.text.SafeFolder
import com.guaner.jsonviewer.theme.ThemeColors
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

    /** 业务侧是否允许编辑（值为 null 时锁住），与设置开关相互独立 */
    private var isEnabledForUser = true

    /** 文本变化回调（用户输入与代码设置都会触发） */
    var onTextChanged: (() -> Unit)? = null

    /**
     * 是否允许「超长行自动折行」。
     *
     * **只有主编辑器应该打开**：折行会往文本里插入真实的 `\n`，主编辑器里这是为了让
     * 一行几十万字符的文本能编辑（JSON 结构内的换行不影响语义）。
     * 但值编辑面板（ValueSheet）是拿编辑器改**某一个节点的值**，那里插入的换行会被
     * 当成值的一部分写回 JSON —— 字符串值里出现裸换行就是非法 JSON。
     * 所以这里默认关闭，由主界面显式打开。
     */
    var autoFoldEnabled: Boolean = false

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

    /**
     * 文本被**程序重排**（超长行自动折行）时回调。
     *
     * 折行只插入换行符、不删任何字符，所以 JSON 语义不变，但所有**基于字符偏移**
     * 的东西（错误位置、搜索命中、跳转高亮）全部失效。上层接到这个回调要把它们
     * 作废并重算 —— 否则就会「明明改对了，错误波浪线还停在别的地方」。
     */
    var onTextRearranged: (() -> Unit)? = null

    /** 程序性设置文本期间不回调 [onTextChanged]，避免「恢复会话」被当成用户编辑 */
    private var suppressCallback = false

    /** 输入法是否正在组词；组词期间不能整篇替换文本（见 [foldLongLinesIfNeeded]） */
    @Volatile
    private var imeComposing = false

    /** 自动折行的提示已经弹过一次了，重复弹会变成骚扰 */
    private var foldNotified = false

    init {
        // 编辑器自己就是 View，去掉背景边框，背景色由主题决定
        setBackgroundColor(themeColors.background)
        setEditorLanguage(language)

        // 代码场景：硬键盘 Tab 用空格更符合 JSON 习惯
        setTabWidth(4)

        // 关闭自动补全：JSON 里自动补全反而容易改坏内容（此开关不开放给用户）
        props.apply {
            autoCompletionOnComposing = false
            // **不要用 disallowSuggestions**：Sora 在 `EditorInputConnection.setSelection()`
            // 和 `updateSelection()` 里拿它当总开关 —— 置 true 后输入法发来的
            // 「移动光标」会被直接 return false 丢掉，`updateSelection` 还会给输入法
            // 回一个随机 index，输入法彻底不知道光标在哪。
            // 禁联想改用 inputType 上的 NO_SUGGESTIONS 标志，效果一样但不影响光标同步。
            disallowSuggestions = false
        }
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

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
            // 这里**绝不能调用 setWordwrap**：它会重建整个 layout，Sora 在重建期间把
            // layoutBusy 置位，点击只记坐标、不落光标（positionNotApplied），
            // 表现就是「正在判断自动换行、光标放不下去、打不了字」。
            // 换行只在「换文档 / 改设置」时定一次，输入路径上一次都不碰。
            //
            // 软换行下删除字符会让断点整体前移，Sora 的断点跟着改，
            // 但光标矩形要等这一帧画完才准；快速连删时看起来就是「光标和文字对不上」。
            // 补一帧重绘让它立刻跟上，代价只有一次绘制。
            if (isWordwrap) postInvalidateOnAnimation()
            onTextChanged?.invoke()
            // 每次编辑都可能让撤销栈从「空」变「非空」，顺手同步按钮可用态
            dispatchUndoState()
            // 只有「一次塞进来一大段」才值得检查折行（粘贴、拖入、输入法整句上屏）。
            // 删除 / 逐字输入绝不触发：折行要整篇替换文本，光标和滚动都会跳，
            // 用户只是删了一个字符却看到「前面的内容都不见了」，就是这么来的。
            //
            // 注意这里是 `post` 而不是防抖延迟：输入法粘贴常常把一大段拆成多批 commitText，
            // 批与批之间只隔几毫秒，300ms 的防抖等到它动手时，某一批早就撞上了
            // 「一行几十万字符的原始形态」，那一批的 O(n²) 断行直接把主线程堵死。
            // 排到本轮消息队列末尾，既能赶在下一批之前收尾，又不会在事件派发的调用栈里
            // 再改一遍 Content。
            if (event.action == ContentChangeEvent.ACTION_INSERT &&
                event.changedText.length >= FOLD_TRIGGER_CHARS
            ) {
                handler.post { foldLongLinesIfNeeded() }
            }
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
        // **先定换行，再换内容**：`setText()` 内部会按当前 `wordwrap` 直接建布局。
        // 顺序反了就是「先建 LineBreakLayout、再重建 WordwrapLayout」，
        // 几十万字符的文档要白白多断一次行，还多一次 layoutBusy 空窗。
        if (isWordwrap != userWrap) setWordwrap(userWrap, false)
        suppressCallback = true
        var folded = false
        try {
            // 内容里若有一行几十万字符，`setText()` 会按软换行整体断一遍 ——
            // 官方 `breakLine` 是 O(n²)，这一步能卡几十秒。先把它折成短行再灌进去。
            val prepared = if (autoFoldEnabled && userWrap) {
                foldSafely(value)?.also { folded = true }?.text ?: value
            } else {
                value
            }
            setText(prepared)
        } finally {
            suppressCallback = false
        }
        // 整篇换成新内容：旧的「按字符偏移」状态（错误位置 / 搜索命中 / 跳转高亮）
        // 全部指向旧文本，一律作废，等重新解析再算。折过的那份尤其要提示一次。
        invalidateOffsetBasedState()
        if (folded && !foldNotified) {
            foldNotified = true
            notifyAutoFolded(hasLineWiderThan(getText(), HUGE_LINE_CHARS))
        }
        oversizeWarned = getText().length > JsonLimits.MAX_INPUT_CHARS
        if (forceTop) {
            setCursorOffset(0)
            post { scrollTo(0, 0) }
        }
        // setText() 会新建 Content 实例，撤销栈也跟着重置，这里按设置恢复一次
        getText().setUndoEnabled(AppSettings.editorUndo)
        // `setText()` 内部已经走了一次 `AnalyzeManager.reset()`，着色会按新内容全量重建；
        // 这里**不要**再调 `language.notifyContentReplaced()`，否则同一篇内容要白白构建两次。
        // 程序性替换（导入 / 格式化 / 清空 / 恢复）不会走用户编辑的回调，
        // 但调用方依赖它去重新解析、刷新表单与大纲，所以这里主动通知一次。
        onTextChanged?.invoke()
        // 换了一整篇内容，撤销栈已被重置，按钮可用态要跟着变
        dispatchUndoState()
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

    /**
     * 更新「当前内容是否通过 JSON 校验」。
     *
     * 唯一的副作用：解析失败 → 关掉语法着色。错的文本里全是无意义的值/键，
     * 逐 token 扫描、下发几万个 `Span` 都是白做，而「粘贴一大段有问题的 JSON、
     * 再一点点改对」恰好是最需要跟手的时刻。
     *
     * 自动折行**不跟着关**：折行有自己的安全闸（见 `foldSafely`），而且越是不合法
     * 的输入往往越需要折行 —— 不折就是一整行长到拖不动。
     */
    fun setError(error: JsonError?) {
        currentError = error
        val valid = error == null
        if (valid != jsonValid) {
            jsonValid = valid
            language.setSyntaxHighlighting(valid)
        }
        applyDiagnostics(error)
    }

    /** 内容是否已通过 JSON 校验；尚未解析过时按「通过」处理，避免一进来就把着色关掉 */
    private var jsonValid = true

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
            singleDirectionDragging = true
            // 长行渲染缓存：不换行的超长行整行绘制结果会被缓存成 RenderNode，
            // 滚动/重绘不再逐字重量。这是超长行「不换行也能滑得动」的关键。
            cacheRenderNodeForLongLines = true
            highlightMatchingDelimiters = AppSettings.editorHighlightBrackets
            // 见 IME_CONTEXT_CHARS：默认值 500000 会让每次输入法回调都复制几十万字符
            maxIPCTextLength = IME_CONTEXT_CHARS
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
        // 字体和字号只在**真的变了**时才写：Sora 的 setTextSizePx() 内部会 createLayout()，
        // 也就是把整篇重新排版一次。原来每次 onResume / 切主题都无条件写一遍，
        // 几十万字符的文档就白白重排一次 —— 这一下正好卡在用户回到界面的瞬间。
        setTypeface(ThemeManager.resolveTypeface())
        setColorScheme(JsonEditorTheme.schemeOf(themeColors))
        setBackgroundColor(themeColors.background)
        applyFontSize((ThemeManager.codeFontSize * currentScale).coerceIn(8f, 40f))
        userWrap = ThemeManager.codeWrap
        // 只在换行模式真的变了时才重建布局：`setWordwrap()` 内部是整篇 `createLayout()`，
        // 每次 onResume / 切主题都无条件调一遍，几十万字符的文档就白等一次重排。
        if (isWordwrap != userWrap) setWordwrap(userWrap, false)
        applyEditorOptions()
        applyDiagnostics(currentError)
    }

    /** 已写入 Sora 的字号（px），用来跳过无变化的重复设置 */
    private var appliedTextSizePx = 0f

    private fun applyFontSize(sp: Float) {
        val px = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics
        )
        // 差 0.05px 视为没变：这是排版层面的差别，肉眼与布局都不受影响
        if (abs(px - appliedTextSizePx) < 0.05f) return
        appliedTextSizePx = px
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

    /** 已写入 Sora 的字体，用来跳过无变化的重复设置（避免多余的 requestLayout） */
    private var appliedTypeface: Typeface? = null

    /** 设置正文字体与行号字体；同一个字体对象重复调用直接忽略 */
    fun setTypeface(tf: Typeface) {
        if (tf === appliedTypeface) return
        appliedTypeface = tf
        setTypefaceText(tf)
        setTypefaceLineNumber(tf)
    }

    fun setFont(tf: Typeface) = setTypeface(tf)

    fun setWrap(wrap: Boolean) {
        userWrap = wrap
        // 先折行再开换行：`setWordwrap(true)` 会立刻按软换行整篇断一次，
        // 遇到几十万字符的单行要 O(n²) 卡死，折成短行之后它才跑得动。
        if (wrap) foldLongLinesIfNeeded()
        if (isWordwrap != wrap) setWordwrap(wrap, false)
        if (wrap) scheduleFoldCheck()
    }

    // ---------- 超长行折行 ----------
    //
    // 背景：Sora 的 `WordwrapLayout.breakLine` 每找一个断点都要从**剩余整行**量一次宽度，
    // 单行断行是 O(n²)。50 万字符的压缩 JSON 打开或敲一个字都能卡几十秒。
    // 官方换行没有写错，是这种输入超出设计；解法只能是别把这种行交给它。
    //
    // 折行只插换行（结构符之后的空白在 JSON 里无意义），字符串内部一字不動，
    // 所以内容语义不变，且能一次撤销。

    /** 折行过程中置 true，避免自己触发的 ContentChangeEvent 再次进入 */
    private var folding = false

    /** 防抖：连续输入/粘贴时只在停手后检查一次 */
    private val foldCheckRunnable = Runnable { foldLongLinesIfNeeded() }

    private fun scheduleFoldCheck() {
        if (!userWrap || !autoFoldEnabled) return
        handler.removeCallbacks(foldCheckRunnable)
        handler.postDelayed(foldCheckRunnable, FOLD_CHECK_DELAY_MS)
    }

    /** 文档里存在超过折行阈值的行时，就地折一次 */
    private fun foldLongLinesIfNeeded() {
        if (!autoFoldEnabled || folding || !userWrap) return
        if (imeComposing) {
            // 输入法正在组词：此刻整篇替换文本，输入法手里的 composition 会失去依托，
            // 常见后果就是上屏的内容串位。等它组完再折。
            scheduleFoldCheck()
            return
        }
        val content = getText()
        val len = content.length
        if (len <= SafeFolder.DEFAULT_THRESHOLD) return
        if (!hasLineWiderThan(content, SafeFolder.DEFAULT_THRESHOLD)) return
        // 折行只插换行、不删字符：把光标位置带过去，否则替换完整篇光标会停在原字符左边，
        // 看起来就是「光标位置不对」。滚动位置同理，直接沿用原来的像素偏移即可。
        val cursorIndex = cursor.left().index.coerceIn(0, len)
        val result = foldSafely(content, cursorIndex) ?: return
        folding = true
        suppressCallback = true
        try {
            content.replace(0, len, result.text)
        } catch (e: Throwable) {
            return
        } finally {
            suppressCallback = false
            folding = false
        }
        // 折行把行数整个改了一遍，语言层的行缓存与下线样式都已过时，推倒重建
        language.notifyContentReplaced()
        invalidateOffsetBasedState()
        // 折行后行号全部变了，光标要按字符偏移重新定位
        runCatching { setCursorOffset(result.anchor.coerceIn(0, content.length)) }
        // 折完还剩超长行，说明那是一整段超长字符串（引号内不能动），只能如实提示。
        // 只提示一次：反复粘贴会反复折行，每次弹 Toast 是骚扰。
        if (!foldNotified) {
            foldNotified = true
            notifyAutoFolded(hasLineWiderThan(content, HUGE_LINE_CHARS))
        }
        onTextChanged?.invoke()
        // 上层基于字符偏移的状态（搜索命中列表、错误跳转）也要跟着作废
        onTextRearranged?.invoke()
        dispatchUndoState()
    }

    /**
     * 作废所有「按字符偏移」存下来的状态。
     *
     * 折行只插入换行符，不删任何字符 —— JSON 语义没变，但**偏移全变了**。
     * 错误行号/列号、搜索命中区间、跳转高亮区间全都是偏移，留着就会画在错的地方，
     * 典型表现是「明明已经改对了，波浪线还挂在别一行」。
     *
     * 这里一律清空，`setTextProgrammatic` 之后会由重新解析重新算出来。
     * 宁可短暂没有标记，也不要给错位置的标记。
     */
    private fun invalidateOffsetBasedState() {
        searchMatches = emptyList()
        searchCurrent = -1
        handler.removeCallbacks(clearFlashRunnable)
        language.setFlashRange(-1, -1)
        setError(null)
    }

    // ---- 注意：这里**不看 jsonValid** ----
    // 早期版本「JSON 有错就不折行」，本意是怕改坏内容；但那恰恰把最需要折行的输入
    // 挡在了门外：粘贴进来的东西经常不是合法 JSON（日志、抽出来的片段、正在改的文本），
    // 而它们同样可能是一行长到卡死的天量文本。真正的安全阀是下面的字符等价校验。

    /**
     * 折行，并且**只在结果与原文本只差「插入的换行符」时才采用**。
     *
     * 这是防止内容被改坏的最后一道闸。[SafeFolder] 靠引号状态判断「哪些符号在
     * 字符串内」，一旦读错作用域（比如文本里有一个没转义的引号），它就会在
     * **字符串里面**插入真实换行 —— JSON 的字符串不允许裸换行，内容直接报废，
     * 表现就是「文本里的 \n 变成了实际换行、无法正确美化」。
     *
     * 折行按定义只做「插入换行」这一件事，所以拿折扣住 pterodactyl 与原文本逐字符
     * 比对一次就足以证明它没有夹带任何别的改动；比对不过就整个放弃 ——
     * 长行慢一点可以忍，内容坏了不能忍。
     */
    private fun foldSafely(text: CharSequence, anchor: Int = -1): SafeFolder.Result? {
        if (!autoFoldEnabled || !userWrap || !isWordwrap) return null
        val result = SafeFolder.foldWithAnchor(text, SafeFolder.DEFAULT_THRESHOLD, anchor) ?: return null
        return if (SafeFolder.onlyInsertedLineBreaks(text, result.text, result.inserted)) result else null
    }

    private fun hasLineWiderThan(content: io.github.rosemoe.sora.text.Content, limit: Int): Boolean {
        val n = content.lineCount
        for (i in 0 until n) {
            if (content.getColumnCount(i) > limit) return true
        }
        return false
    }

    private fun notifyUnwrappable() {
        Toast.makeText(
            context,
            "这段内容是一整行超长字符串，无法折行；已临时关闭自动换行以免卡死",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun notifyAutoFolded(hasUnfoldable: Boolean = false) {
        if (!userWrap) return
        val msg = if (hasUnfoldable) {
            "已自动折行；文中含超长字符串，该行保持原样（换行会较慢）"
        } else {
            "检测到超长行，已自动折行以便编辑（内容不变）"
        }
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }

    // ---------- 输入法连接 ----------
    //
    // 目的只有一个：让**中文输入法左右移动光标**能用。
    //
    // 百度、搜狗这类输入法移动光标的方式很不一致：有的发 `InputConnection.setSelection`
    // （Sora 已经处理），有的直接发 `KeyEvent(KEYCODE_DPAD_LEFT/RIGHT)`。
    // 后者走 `BaseInputConnection.sendKeyEvent` → `ViewRootImpl.dispatchKeyFromIme`，
    // 中间环节一多就不一定落到编辑器上，表现就是「按了没反应」。
    //
    // 这里在 Sora 的连接外面套一层，把方向键的**按下**直接翻译成编辑器自身的
    // 光标移动，并如实告诉输入法「已经处理了」。组词期间不插手：那时方向键属于候选区。

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        return ImeCursorConnection(ic)
    }

    private inner class ImeCursorConnection(target: InputConnection) : InputConnectionWrapper(target, true) {

        /**
         * 是否正在组词：组词时的左右键属于候选/组合串，不能截走。
         *
         * 自己跟踪而不是查 `getComposingText`——那个方法要 Android 13 才有。
         */
        private var composing = false

        private fun beginComposing() {
            imeComposing = true
            composing = true
        }

        private fun endComposing() {
            imeComposing = false
            composing = false
        }

        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (text.isNullOrEmpty()) endComposing() else beginComposing()
            // 少数输入法（语音输入、粘贴预览）会把整段文本先塞进 composing 里，
            // 同样要先折短，否则一行几十万字符就已经成型了
            val prepared = ImeCommit.prepare(text ?: "", autoFoldEnabled && userWrap) ?: text
            return super.setComposingText(prepared, newCursorPosition)
        }

        override fun finishComposingText(): Boolean {
            endComposing()
            return super.finishComposingText()
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            // 少数输入法用 setComposingRegion 开局；只有底层真的承接住了才算在组词
            val accepted = super.setComposingRegion(start, end)
            if (accepted) beginComposing()
            return accepted
        }

        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            // 有些输入法（百度属于这一类）上屏时不调 finishComposingText，只调 commitText。
            // 组成状态一旦卡在 true，之后所有左右键都会被当成「还在组词」放行给候选区，
            // 表现就是「明明按了移动光标，光标纹丝不动」。
            endComposing()
            if (text.isNullOrEmpty()) return super.commitText(text, newCursorPosition)
            // 输入法粘贴：先把超长行折短，再决定怎么插进去（原因见 ImeCommit）
            val prepared = ImeCommit.prepare(text, autoFoldEnabled && userWrap) ?: text
            if (prepared.length >= ImeCommit.BULK_CHARS) return insertBulkText(prepared)
            return super.commitText(prepared, newCursorPosition)
        }

        // ---- 上下文文本：按字符数封顶 ----
        //
        // `getSurroundingText` 内部走的是「不受 IPC 上限约束」的那条路径：
        // 输入法给多大的 length，Sora 就复制多大的文本。百度这类输入法在刷新候选时会
        // 一次要很长的一段（乃至到文档末尾），几十万字符的跨行拼接每次几十毫秒，
        // 一移动光标就来好几趟 —— 这是大文本卡顿里占比最大的一块。
        override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence? =
            super.getTextBeforeCursor(length.coerceAtMost(IME_CONTEXT_CHARS), flags)

        override fun getTextAfterCursor(length: Int, flags: Int): CharSequence? =
            super.getTextAfterCursor(length.coerceAtMost(IME_CONTEXT_CHARS), flags)

        override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): android.view.inputmethod.SurroundingText =
            (super.getSurroundingText(
                beforeLength.coerceAtMost(IME_CONTEXT_CHARS),
                afterLength.coerceAtMost(IME_CONTEXT_CHARS),
                flags
            ) ?: android.view.inputmethod.SurroundingText("", 0, 0, 0))

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            val key = event.keyCode
            if (key != KeyEvent.KEYCODE_DPAD_LEFT && key != KeyEvent.KEYCODE_DPAD_RIGHT) {
                return super.sendKeyEvent(event)
            }
            val step = if (key == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (composing) return true      // 组词中：左右键归候选区，但别再往下转发
                    return moveCursorBy(step)
                }
                // DOWN 已经吃掉并移动过了，UP 必须一并吞掉：
                // 否则它会经 `BaseInputConnection` → `ViewRootImpl.dispatchKeyFromIme`
                // 再走一遍编辑器，一次物理按键就变成了挪两格，或者被别的地方二次处理。
                KeyEvent.ACTION_UP -> return true
            }
            return super.sendKeyEvent(event)
        }

        /** 按字符偏移移动光标（有选区时收敛到选区边界，和常规编辑器一致） */
        private fun moveCursorBy(step: Int): Boolean {
            val content = getText()
            val len = content.length
            if (len == 0) return true
            val cur = cursor ?: return false
            val from = if (step < 0) cur.left().index else cur.right().index
            moveCaretToIndex(from + step)
            return true
        }

        /**
         * 输入法移动光标的另一条常用通道。
         *
         * Sora 的实现只对灰色名单做减法：`setSelection(start, end)` 无条件走
         * `setSelectionRegion(...)`。而百度、搜狗移动光标时传的是**一个字符的选区**
         * （`(pos-1, pos)` 表示「光标挪到 pos」），传进去就成了「选中一个字」。
         * 这里先把这种形态认出来折叠成光标，其余情况照旧交给 Sora。
         *
         * 注意：**不能用 `[com.guaner.jsonviewer.ui.CodeEditorView.setSelectionRegion]`**，
         * 那正是「移动光标变成选择文字」的来源。
         */
        override fun setSelection(start: Int, end: Int): Boolean {
            val len = getText().length
            val caret = ImeSelection.caretIndexFor(start, end, len) ?: return super.setSelection(start, end)
            moveCaretToIndex(caret)
            return true
        }
    }

    /**
     * 把光标挪到 [index]（字符偏移）——**不走 `setSelection(line, column)`**。
     *
     * 官方那条路径每次都会顺带做三件对「挪一个字符」毫无必要的事：
     *
     * 1. `ensurePositionVisible()` → `WordwrapLayout.getCharLayoutOffset()`。
     *    它先二分找到这条 line 的某个 row，再沿 rowTable **线性走到目标列**：
     *    一行被折成多少段就要走多少步；紧接着还要 `renderer.measureText(...)` 再量一次。
     *    一条一千字符的行折出几十个 row，还不明显；几十万字符的行折出上万个 row，
     *    每按一次左右键就从头走一遍 —— 这就是「开了自动换行更卡」的来源。
     * 2. `renderContext.invalidateRenderNodes()`：整份渲染缓存作废，下一帧把屏上每一行
     *    重新排版测量一遍。内容一个字都没变，白做工。
     * 3. 重启光标动画，连带一串 invalidate。
     *
     * 这里只做真正必要的两件事：改光标位置、重绘。
     * 渲染缓存的一致性由 Content 的监听器负责（插入/删除走 `updateForInsertion/Deletion`），
     * 跟 setSelection 里那次 invalidate 无关，跳过是安全的。
     */
    /**
     * 整段插入的分流路径，输入法粘贴与菜单粘贴共用。
     *
     * 绕开 `CodeEditor.commitText()`：它会为了「符号配对」把整段文本 `toCharArray()`
     * 复制一份再逐字符匹配（`symbolPairAutoCompletion` 开着的话，几十万次比较纯属白做），
     * 之后还要给整段做一次异步格式化。这里直接用 Content 的 API 替换，语义完全一致。
     *
     * @return 是否已完成插入；false 表示调用方应当退回原路径
     */
    private fun insertBulkText(text: CharSequence): Boolean {
        // 折也折不动的极端情况：整段就是一个没有结构符的超长字符串。
        // 这样的行会被软换行拆成上万个 row，之后每敲一个字都要重断一次，等于卡死。
        // 这里临时退回「不换行」——横向拖着看总比完全动不了强，并且事后告诉用户。
        if (userWrap && isWordwrap && ImeCommit.longestLineLength(text) > HUGE_LINE_CHARS) {
            setWordwrap(false, false)
            notifyUnwrappable()
        }
        val content = getText()
        val cur = cursor ?: return false
        val lo = minOf(cur.left().index, cur.right().index)
        val hi = maxOf(cur.left().index, cur.right().index)
        content.beginBatchEdit()
        val ok = try {
            content.replace(lo, hi, text)
            true
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { content.endBatchEdit() }
        }
        if (!ok) return false
        // 光标落到刚插入的这串文本后面
        moveCaretToIndex(lo + text.length)
        return true
    }

    /**
     * 编辑器内长按菜单 / 输入法菜单里的「粘贴」。
     *
     * 这条通道**不经过** `onCreateInputConnection` 返回的那层包装：`CodeEditor.pasteText()`
     * 直接调的是它自己持有的 `inputConnection.commitText()`。好在 `pasteText(CharSequence)`
     * 是可覆写的虚方法，无参版本会一路派发到这里，所以在这接住即可 —— 否则大文本照样卡死。
     *
     * 小文本照旧交给 Sora（保留它原来的行为，包括粘贴后格式化）；
     * 只有体量达到 `ImeCommit.BULK_CHARS` 的才先折行再自己插。
     */
    override fun pasteText(text: CharSequence?) {
        if (text.isNullOrEmpty()) {
            super.pasteText(text)
            return
        }
        val prepared = ImeCommit.prepare(text, autoFoldEnabled && userWrap) ?: text
        if (prepared.length >= ImeCommit.BULK_CHARS && insertBulkText(prepared)) return
        super.pasteText(prepared)
    }

    private fun moveCaretToIndex(index: Int) {
        // 输入法回调里崩一下就是软键盘整个失联，宁可吞掉异常也不要打断这条通道
        runCatching { moveCaretToIndexInternal(index) }
    }

    private fun moveCaretToIndexInternal(index: Int) {
        val content = getText()
        val len = content.length
        if (len == 0) return
        val cur = cursor ?: return
        val pos = content.getIndexer().getCharPosition(index.coerceIn(0, len))
        cur.set(pos.line, pos.column)
        selectionAnchor = cur.right()
        // 同步选区给输入法，否则它手里的候选/组合位置会错位
        runCatching { updateSelection() }
        if (!isWholeLineVisible(pos.line)) ensurePositionVisible(pos.line, pos.column)
        invalidate()
    }

    /** 这条 line 是否整条都在屏幕里（判断详见 [ImeSelection.wholeLineVisible]） */
    private fun isWholeLineVisible(line: Int): Boolean =
        ImeSelection.wholeLineVisible(line, getFirstVisibleLine(), getLastVisibleLine(), isWordwrap)

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
        handler.removeCallbacks(foldCheckRunnable)
        super.onDetachedFromWindow()
    }

    private companion object {
        /** 单次最多下发的搜索命中诊断区数量 */
        const val MAX_SEARCH_REGIONS = 200

        /** 定位闪烁高亮的持续时间 */
        const val FLASH_MS = 1_600L

        /** 判定「点在滚动条上」的横向容差（dp）；滚动条本身更窄，留点余量好按 */
        const val SCROLL_BAR_TOUCH_DP = 22f

        /** 粘贴/输入停顿多久后检查一次超长行 */
        const val FOLD_CHECK_DELAY_MS = 300L

        /**
         * 一次插入多少字符才值得检查折行。
         *
         * 与 [SafeFolder.DEFAULT_THRESHOLD] 对齐：一次插入连一行都撑不满时，
         * 不可能产生需要折的超长行，就不必为它整篇替换一次文本。
         */
        const val FOLD_TRIGGER_CHARS = SafeFolder.DEFAULT_THRESHOLD

        /** 折完还剩这么长的行，说明是不可折的超长字符串，提示用户该行换行会慢 */
        const val HUGE_LINE_CHARS = 20_000

        /**
         * 回给输入法的上下文长度上限（字符）。
         *
         * 输入法每次回调 `getTextBeforeCursor` / `getTextAfterCursor` / `getSurroundingText`
         * 都要把对应区间的文本复制成一个 String 送过去。Sora 默认允许 50 万字符一次
         * （`DirectAccessProps.maxIPCTextLength = 500_000`），而 `getSurroundingText`
         * 更是完全不受这个上限约束 —— 在几十万字符的文档上，一次回调就是几十万字符的
         * 跨行拼接，移动一次光标要来回好几趟。这就是「大文本走输入法卡、小文本正常」的主因。
         *
         * 输入法真正需要的只是光标附近这点上下文做组词 / 联想，几百字符绰绰有余。
         */
        const val IME_CONTEXT_CHARS = 512
    }
}
