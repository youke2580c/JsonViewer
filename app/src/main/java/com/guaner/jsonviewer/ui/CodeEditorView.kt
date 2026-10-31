package com.guaner.jsonviewer.ui

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.Layout
import android.text.Spannable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.json.JsonError
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.theme.ThemeManager
import com.guaner.jsonviewer.text.LineStarts
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 代码模式编辑器：自绘行号栏 + 等宽编辑区 + 覆盖式可拖动滚动条。
 * 自带 JSON 语法高亮、错误行/错误列高亮、搜索命中高亮（均由外部数据驱动）。
 *
 * 大文本（200KB+）性能要点：
 * - 行号不再用与全文等高的 TextView，改为只绘制可视行的 [LineNumberView]；
 * - 语法高亮只处理「可视区域 ± 若干行」的切片，滚动时按可视范围增量刷新；
 * - 文本内容不再随每次按键回调全量 toString（由外部在防抖后取一次快照）。
 */
class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val lineNumbers: LineNumberView
    private val editor: EditText
    private val editorContainer: FrameLayout
    private val scrollThumb: View

    private var currentError: JsonError? = null
    private var searchMatches: List<IntRange> = emptyList()
    private var searchCurrent: Int = -1

    private var themeColors: ThemeColors = ThemeManager.activeColors
    private var currentScale = ThemeManager.codeScale

    /** 当前已着色的字符区间（用于滚动时判断是否需要重新着色） */
    private var highlightedWindow: IntRange = IntRange.EMPTY

    /** 用户设置的换行 / 因存在超长行而自动开启的换行 / 实际已应用的状态 */
    private var userWrap = ThemeManager.codeWrap
    private var autoWrap = false
    private var appliedWrap: Boolean? = null

    /** 因超长行自动开启换行时回调一次（用于提示用户） */
    var onAutoWrap: (() -> Unit)? = null
    private var autoWrapNotified = false

    private var applyingSpans = false
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = Runnable {
        // 先统计逻辑行（顺带判断是否存在超长行，必要时自动开启换行），再着色
        rebuildLineStarts()
        applySpans()
        updateScrollThumb()
    }

    // 最近一次文本变化的位置信息（用于判断行结构是否真的变了）
    private var pendingStart = 0
    private var pendingBefore = 0
    private var pendingCount = 0
    private var pendingStructural = true

    /** 用户自己加的 span：自己记账比每次 getSpans 全表扫描便宜，也便于批量清理 */
    private val appliedSpans = ArrayList<Any>(512)

    /** 整段替换（导入 / 格式化）时禁用行首增量：旧行首表对新文本没有意义 */
    private var skipIncrementalOnce = false

    /** 最近一次编辑的时间戳：编辑后的静默期内不让滚动逻辑抢先重建 span */
    private var lastEditAt = 0L

    /** 滚动停止后的增量着色（滚动过程中不着色，避免掉帧） */
    private var scrollRefreshPending = false
    private val scrollRefreshRunnable = Runnable {
        scrollRefreshPending = false
        applySpans()
    }

    /** 文本变化回调（用户输入与代码设置都会触发；内容由调用方自行取快照） */
    var onTextChanged: (() -> Unit)? = null

    /** 内容超出安全上限时回调（用于提示，不截断） */
    var onOversize: ((Int) -> Unit)? = null
    private var oversizeWarned = false

    /** 双指缩放：两指时消费事件做缩放，单指交给 EditText 自身处理光标/选择 */
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (!ThemeManager.codeZoom) return false
                currentScale = (currentScale * detector.scaleFactor).coerceIn(0.5f, 3f)
                // 缩放会触发 EditText 重建 Layout（超长文本下很贵），合并到下一帧只做一次
                applyFontSizeThrottled()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                ThemeManager.codeScale = currentScale
            }
        }
    )

    init {
        orientation = HORIZONTAL
        LayoutInflater.from(context).inflate(R.layout.view_code_editor, this, true)
        lineNumbers = findViewById(R.id.lineNumbers)
        editor = findViewById(R.id.editor)
        editorContainer = findViewById(R.id.editorContainer)
        scrollThumb = findViewById(R.id.scrollThumb)

        lineNumbers.layoutProvider = { editor.layout }
        lineNumbers.scrollProvider = { editor.scrollY }
        lineNumbers.topInsetProvider = { editor.paddingTop }

        // 默认关闭自动换行：每个逻辑行占一个可视行，行号栏才能逐行对齐；超长行横向滚动
        editor.setHorizontallyScrolling(true)
        // 不让系统把文本塞进 View 状态 Bundle：几百 KB 的文本会在切后台 / 转屏时
        // 触发一次巨大的 Parcel 写入（卡顿甚至 TransactionTooLargeException）。
        // 内容恢复由应用自己走会话存储，这里不需要系统帮忙。
        editor.isSaveEnabled = false

        // 断行策略用 SIMPLE：默认的 HIGH_QUALITY 会做段落级最优断行，
        // 超大文本下每次按键重建 Layout 都要花几十毫秒，是输入卡顿的主因之一。
        // 等宽字体 + 代码场景本来也不需要高质量断行与连字符。
        editor.breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        editor.hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        // 自动填充框架会扫描文本内容推断字段，超大文本下是纯粹的浪费
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            editor.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }

        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                pendingStart = start
                pendingBefore = count
                // 大段删除不必逐字检查：反正也不走增量路径
                pendingStructural = count > INCREMENTAL_MAX_CHARS ||
                    (count > 0 && s != null && LineStarts.hasNewline(s, start, count))
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                pendingCount = count
                if (count > INCREMENTAL_MAX_CHARS) {
                    pendingStructural = true
                } else if (count > 0 && s != null && LineStarts.hasNewline(s, start, count)) {
                    pendingStructural = true
                }
            }

            override fun afterTextChanged(s: Editable?) {
                if (!applyingSpans) onContentEdited()
                // 粘贴/分享进来的超大内容：不强行截断用户正在编辑的文本（会打乱光标），
                // 但明确警告一次，让用户知道已超出安全上限、部分功能将降级
                val len = s?.length ?: 0
                if (len > JsonLimits.MAX_INPUT_CHARS && !oversizeWarned) {
                    oversizeWarned = true
                    onOversize?.invoke(len)
                } else if (len <= JsonLimits.MAX_INPUT_CHARS) {
                    oversizeWarned = false
                }
                // 不再回调全文 toString：大文本下每次按键复制 200KB 是主要卡顿来源
                onTextChanged?.invoke()
            }
        })

        // 编辑器滚动：刷新行号栏 / 滚动条 / 可视区语法高亮
        editor.setOnScrollChangeListener { _, _, _, _, _ -> onEditorScrolled() }

        // 双指缩放 + 单指惯性滚动：
        // 两指时消费事件避免光标乱跳；单指时只旁路记录滑动速度并返回 false，
        // 光标定位 / 文本选择仍完全交给 EditText 自己处理。
        editor.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            if (event.pointerCount >= 2) {
                cancelFlingTracking()
                return@setOnTouchListener true
            }
            // 先识别「从左边缘右滑」，命中就把事件全吃掉，避免被 EditText 当成横向滚动
            if (handleEdgeSwipe(event)) return@setOnTouchListener true
            trackFling(event)
            false
        }

        setupScrollThumb()
        applyThemeFromManager()
    }

    var text: String
        get() = editor.text?.toString() ?: ""
        set(value) {
            skipIncrementalOnce = true
            editor.setText(value)
        }

    /** 只读遍历用的文本视图：不复制内容（大文本下避免每次搜索全量拷贝） */
    val textSequence: CharSequence
        get() = editor.text ?: ""

    /**
     * 原位替换一段文本（值编辑保存用）。
     *
     * 不用 setTextProgrammatic：那会把滚动位置和选区都重置到开头，
     * 而编辑某个值之后用户还希望停留在原来的位置。
     */
    fun replaceRange(start: Int, end: Int, newText: String) {
        val editable = editor.text ?: return
        if (start < 0 || end > editable.length || start > end) return
        stopFling()
        editable.replace(start, end, newText)
    }

    /**
     * 代码设置文本（导入/格式化后调用）：回到顶部。
     *
     * forceTop=false 用于「恢复上次内容」：保持光标在 0 但不滚动、不强制格式化，
     * 大文本进来只排一次版。
     */
    fun setTextProgrammatic(value: String, forceTop: Boolean = true) {
        stopFling()
        autoWrapNotified = false
        highlightedWindow = IntRange.EMPTY
        appliedSpans.clear()
        // 先按内容预判换行模式：否则会先按「一行几十万像素」排版一次、再切换重建一次。
        // 切换前先清空旧文本（Layout 立刻变空，代价极低），保证只对新文本排版一次。
        val maxLine = LineStarts.maxLineLength(value)
        autoWrap = if (autoWrap) maxLine > SUPER_LONG_LINE_RELEASE
        else maxLine > SUPER_LONG_LINE_TRIGGER
        val wrap = userWrap || autoWrap
        if (appliedWrap != wrap) {
            skipIncrementalOnce = true
            editor.setText("")
            appliedWrap = wrap
            editor.setHorizontallyScrolling(!wrap)
        }
        skipIncrementalOnce = true
        editor.setText(value)
        if (forceTop) editor.setSelection(0)
        // 大文本：别让系统把光标滚进可视区（会额外触发一次布局），也先不做着色
        val big = value.length > LARGE_TEXT_CHARS
        post {
            if (forceTop) editor.scrollTo(0, 0)
            rebuildLineStarts()
            if (!big) applySpans()
            updateScrollThumb()
        }
        if (autoWrap && !autoWrapNotified) {
            autoWrapNotified = true
            onAutoWrap?.invoke()
        }
    }

    fun setError(error: JsonError?) {
        currentError = error
        scheduleRefresh()
    }

    fun setSearchMatches(matches: List<IntRange>, current: Int) {
        searchMatches = matches
        searchCurrent = current
        scheduleRefresh()
    }

    /**
     * 跳转到指定字符偏移并滚动到可视区中部。
     *
     * takeFocus=false 专供搜索逐字输入使用：只滚动、不动光标与焦点，
     * 否则会把焦点从搜索框抢到编辑器，导致「打一个字就断」。
     */
    fun jumpTo(offset: Int, takeFocus: Boolean = true) {
        stopFling()
        val len = editor.text?.length ?: 0
        val o = offset.coerceIn(0, len)
        // 无论是否抢焦点，都把光标放到目标位置：
        // 否则编辑器之后任何一次重新布局都会 bringPointIntoView 回「旧光标」，
        // 表现为搜索定位后停一会儿视图突然跳走。
        if (takeFocus) editor.requestFocus()
        editor.setSelection(o)
        post {
            val layout = editor.layout ?: return@post
            val line = layout.getLineForOffset(o)
            val y = (layout.getLineTop(line) - editor.height / 2).coerceAtLeast(0)
            editor.scrollTo(0, y)
            updateScrollThumbPosition()
        }
    }

    fun requestEditorFocus() {
        editor.requestFocus()
    }

    // ---------- 定位闪烁（大纲/快捷跳转在代码页里定位某一项） ----------

    private var flashSpan: BackgroundColorSpan? = null
    private val clearFlashRunnable = Runnable { removeFlash() }

    /**
     * 跳转到 [start]..[end] 并短暂高亮，让用户一眼看到落在哪。
     *
     * 用独立的 span 类型（[FlashSpan]），不会被语法高亮的清理逻辑顺手删掉；
     * 1.6 秒后自动移除，不留痕迹。
     */
    fun flashRange(start: Int, end: Int) {
        val editable = editor.text
        if (editable !is Spannable) return
        val len = editable.length
        if (len == 0) return
        val s = start.coerceIn(0, len - 1)
        val e = end.coerceIn(s + 1, len)
        removeFlash()
        val span = FlashSpan(themeColors.searchCurrent)
        editable.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        flashSpan = span
        handler.removeCallbacks(clearFlashRunnable)
        handler.postDelayed(clearFlashRunnable, FLASH_MS)
        // 只滚动不动焦点：从抽屉/弹层点过来后仍保持原输入焦点，避免键盘乱弹
        jumpTo(s, takeFocus = false)
    }

    private fun removeFlash() {
        val span = flashSpan ?: return
        (editor.text as? Spannable)?.removeSpan(span)
        flashSpan = null
    }

    /** 置顶：滚到第一行 */
    fun scrollToTop() {
        stopFling()
        post {
            editor.scrollTo(0, 0)
            updateScrollThumbPosition()
            showScrollThumb()
        }
    }

    /** 置底：滚到最后一行 */
    fun scrollToBottom() {
        stopFling()
        post {
            editor.scrollTo(0, editorMaxScroll())
            updateScrollThumbPosition()
            showScrollThumb()
        }
    }

    // ---------- 惯性滚动（快速滑动后继续滑行） ----------
    //
    // EditText 自身在手指按住时能跟随滚动，但抬手后不会继续滑行。
    // 这里旁路记录滑动速度，抬手时用 OverScroller 继续把内容滑出去，手感与列表一致。

    private val flingScroller = OverScroller(context)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFlingVelocity = ViewConfiguration.get(context).scaledMinimumFlingVelocity
    private val maxFlingVelocity = ViewConfiguration.get(context).scaledMaximumFlingVelocity

    private var velocityTracker: VelocityTracker? = null
    private var downY = 0f
    private var movedFar = false

    /** 惯性滑行中 / 滑行回调是否已排队（避免重复 post 造成双倍速度） */
    private var flinging = false
    private var flingLoopRunning = false

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!flinging) {
                flingLoopRunning = false
                return
            }
            if (flingScroller.computeScrollOffset()) {
                editor.scrollTo(0, flingScroller.currY)
                if (flinging) postOnAnimation(this) else flingLoopRunning = false
            } else {
                flinging = false
                flingLoopRunning = false
            }
        }
    }

    /** 停止惯性滑行（新触摸 / 拖动滚动条 / 跳转 / 换文本时调用） */
    private fun stopFling() {
        flinging = false
        if (!flingScroller.isFinished) flingScroller.forceFinished(true)
    }

    /** 双指缩放开始时放弃单指滑动的速度追踪 */
    private fun cancelFlingTracking() {
        stopFling()
        velocityTracker?.recycle()
        velocityTracker = null
    }

    /** 旁路记录单指滑动速度：ACTION_UP 时按速度启动惯性滑行 */
    private fun trackFling(event: MotionEvent) {
        val t = velocityTracker
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopFling()
                t?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(event) }
                downY = event.y
                movedFar = false
            }
            MotionEvent.ACTION_MOVE -> {
                t?.addMovement(event)
                if (!movedFar && abs(event.y - downY) > touchSlop) movedFar = true
            }
            MotionEvent.ACTION_UP -> {
                if (t != null) {
                    t.addMovement(event)
                    startFling(t)
                    t.recycle()
                }
                velocityTracker = null
            }
            MotionEvent.ACTION_CANCEL -> {
                t?.recycle()
                velocityTracker = null
            }
        }
    }

    private fun startFling(tracker: VelocityTracker) {
        // 没超过触摸阈值 = 点击定位光标，不做惯性；正在拖选文本时也不做惯性
        if (!movedFar) return
        if (editor.selectionStart != editor.selectionEnd) return
        val max = editorMaxScroll()
        if (max <= 0) return
        tracker.computeCurrentVelocity(1000, maxFlingVelocity.toFloat())
        val vy = tracker.yVelocity
        if (abs(vy) < minFlingVelocity) return
        // 手指上滑（vy<0）→ 内容向下走（scrollY 增大），因此取反
        flingScroller.forceFinished(true)
        flingScroller.fling(
            0, editor.scrollY.coerceIn(0, max), 0, (-vy).toInt(),
            0, 0, 0, max, 0, 0
        )
        flinging = true
        if (!flingLoopRunning) {
            flingLoopRunning = true
            postOnAnimation(flingRunnable)
        }
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

    /**
     * 识别从编辑器边缘开始、朝屏幕内侧的滑动（含兜底打开 + 配合抽屉接管）。
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
                // 正在选中文字（含拖动选择手柄）时不识别边缘手势，避免选词误呼出大纲
                val selecting = editor.hasSelection()
                edgeTracking = edgeSwipeEnabled && !selecting && when {
                    edgeSwipeBoth -> leftHit || rightHit
                    edgeSwipeFromRight -> rightHit
                    else -> leftHit
                }
                if (edgeTracking) {
                    // 先让外部把面板摆到起手侧（这一帧布局就能完成）
                    onEdgeHint?.invoke(edgeFromRightSide)
                    // EditText 在 DOWN 时会 requestDisallowInterceptTouchEvent(true)，
                    // DrawerLayout 因此收不到后续 MOVE、没法自己跟手拖动。
                    // DOWN 处理完再放行（此时 disallow 已生效过一次），抽屉就能接管这次拖动；
                    // 万一抽屉没接管，下面的兜底判定仍然会触发。
                    post { parent?.requestDisallowInterceptTouchEvent(false) }
                }
                edgeTriggered = false
                edgeDownX = e.x
                edgeDownY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                // 长按选词发生在 DOWN 之后：一旦出现选区立刻放弃本次边缘判定
                if (edgeTracking && editor.hasSelection()) edgeTracking = false
                if (edgeTracking && !edgeTriggered) {
                    val dx = e.x - edgeDownX
                    val dy = abs(e.y - edgeDownY)
                    val inward = if (edgeFromRightSide) -dx else dx
                    if (inward > touchSlop && inward > dy * 1.5f) {
                        edgeTriggered = true
                        stopFling()
                        onEdgeSwipe?.invoke(edgeFromRightSide)
                        return true
                    }
                    // 明显是纵向滚动或反向滑动：放弃本次边缘判定
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

    private val edgeWidthPx: Float by lazy {
        48 * resources.displayMetrics.density
    }

    // ---------- 主题 / 可读性 ----------

    fun setThemeColors(colors: ThemeColors) {
        themeColors = colors
        scheduleRefresh()
    }

    /** 从 ThemeManager 读取当前设置并应用：配色、字体族、字号(含缩放)、换行 */
    fun applyThemeFromManager() {
        themeColors = ThemeManager.activeColors
        val tf = ThemeManager.resolveTypeface()
        editor.typeface = tf
        lineNumbers.textTypeface = tf
        currentScale = ThemeManager.codeScale
        applyFontSize((ThemeManager.codeFontSize * currentScale).coerceIn(8f, 40f))
        applyWrap(ThemeManager.codeWrap)
        // 代码页背景跟随主题背景色；行号栏跟随 gutter，文字色用 gutterText 保证对比
        editor.setBackgroundColor(themeColors.background)
        editor.setTextColor(themeColors.text)
        lineNumbers.setBackgroundColor(themeColors.gutter)
        lineNumbers.gutterTextColor = themeColors.gutterText
        scheduleRefresh()
    }

    private fun applyFontSize(sp: Float) {
        editor.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        lineNumbers.textSizeSp = sp
        updateGutterWidth(lineNumbers.logicalStarts.size)
        updateScrollThumb()
    }

    /** 双指缩放期间把字号变更合并到下一帧：避免每帧重建 Layout 造成卡顿 */
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
        editor.typeface = tf
        lineNumbers.textTypeface = tf
    }

    fun setWrap(wrap: Boolean) {
        userWrap = wrap
        syncWrapMode()
    }

    private fun applyWrap(wrap: Boolean) {
        userWrap = wrap
        syncWrapMode()
    }

    /**
     * 实际的换行模式 = 用户设置 || 存在超长行时强制换行。
     *
     * 关键：关闭换行时，一个几万字符的值会被排成「一行」，
     * EditText 每次绘制都要从行首累加字符宽度定位可视段（O(字符数)），
     * 滚动时每帧都要花几十毫秒 → 表现为卡死、抖动、置顶置底失效。
     * 因此检测到超长行时自动切换为软换行，把它折成很多短行，绘制成本立刻降下来。
     */
    private fun syncWrapMode() {
        val wrap = userWrap || autoWrap
        if (appliedWrap == wrap) return
        appliedWrap = wrap
        editor.setHorizontallyScrolling(!wrap)
        post {
            lineNumbers.invalidate()
            applySpans()
            updateScrollThumb()
        }
    }

    // ---------- 可拖动滚动条（快速滚动 + 平时隐藏 / 滚动出现 / 停止自动隐藏） ----------

    private var draggingThumb = false
    private var thumbDownY = 0f
    private var thumbDownRatio = 0f
    private val hideThumbRunnable = Runnable { hideThumb() }

    /** 滚动几何：按「行」而不是按像素计算，拇指位置才与看到的文本一致（跟手） */
    private class ScrollGeo(val layout: Layout, val maxFirst: Int, val maxOffset: Int)

    /**
     * 隐藏滚动条：只改 alpha，不改 visibility。
     *
     * visibility 变化会触发 requestLayout → EditText 重新布局，
     * 进而可能把视图「弹」回光标所在行（搜索定位后停一会儿突然上移就是这个原因）。
     */
    private fun hideThumb() {
        // 拖动中绝不隐藏：否则手指还按着，拇指却淡出，松手前都不知道拖到哪了
        if (draggingThumb) return
        if (scrollThumb.alpha != 0f) scrollThumb.alpha = 0f
    }

    private fun showThumb() {
        if (scrollThumb.alpha != 1f) scrollThumb.alpha = 1f
    }

    private fun scrollGeo(): ScrollGeo? {
        val layout = editor.layout ?: return null
        val trackH = editorContainer.height
        if (trackH <= 0 || layout.lineCount <= 0) return null
        val (first, last) = visibleLines(layout)
        val visible = (last - first + 1).coerceAtLeast(1)
        val maxFirst = (layout.lineCount - visible).coerceAtLeast(1)
        val lp = scrollThumb.layoutParams as FrameLayout.LayoutParams
        val thumbH = if (lp.height > 0) lp.height else scrollThumb.height
        val maxOffset = (trackH - thumbH).coerceAtLeast(1)
        return ScrollGeo(layout, maxFirst, maxOffset)
    }

    private fun visibleLines(layout: Layout): Pair<Int, Int> {
        val top = editor.scrollY.coerceAtLeast(0)
        val first = layout.getLineForVertical(top)
        val last = layout.getLineForVertical(top + editor.height)
        return first to last
    }

    /** 当前滚动的「首行进度」0..1（与拇指位置一一对应） */
    private fun scrollRatio(): Float {
        val layout = editor.layout ?: return 0f
        if (layout.lineCount <= 0) return 0f
        val (first, last) = visibleLines(layout)
        val visible = (last - first + 1).coerceAtLeast(1)
        val maxFirst = (layout.lineCount - visible).coerceAtLeast(1)
        return (first.toFloat() / maxFirst).coerceIn(0f, 1f)
    }

    private fun setupScrollThumb() {
        scrollThumb.setOnTouchListener { _, e ->
            // alpha=0（已自动隐藏）时不吃触摸，事件继续交给下面的编辑器
            if (scrollThumb.alpha < 0.5f) return@setOnTouchListener false
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 惯性滑行未结束时按住滚动条：立刻掐断滑行，把控制权交给手指。
                    // 否则 flingRunnable 每帧还在 scrollTo，手指拖动的位移会被立刻覆盖回去，
                    // 表现就是「拉滚动条没反应」。
                    stopFling()
                    draggingThumb = true
                    thumbDownY = e.rawY
                    // 以拇指「当前实际位置」为起点，避免按下瞬间跳一下
                    val geo = scrollGeo()
                    thumbDownRatio = if (geo != null) {
                        (scrollThumb.y / geo.maxOffset).coerceIn(0f, 1f)
                    } else {
                        scrollRatio()
                    }
                    showScrollThumb()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (draggingThumb) {
                        val geo = scrollGeo()
                        if (geo != null) {
                            // 拖动距离 / 拇指可移动距离 = 进度增量；拇指跟手由手指位移决定，
                            // 内容按行反算，两者不会互相拖拽造成漂移
                            val ratio = (thumbDownRatio + (e.rawY - thumbDownY) / geo.maxOffset)
                                .coerceIn(0f, 1f)
                            scrollThumb.y = ratio * geo.maxOffset
                            val targetLine = (ratio * geo.maxFirst).roundToInt()
                                .coerceIn(0, geo.maxFirst)
                            editor.scrollTo(0, geo.layout.getLineTop(targetLine))
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    draggingThumb = false
                    // 松手后按真实滚动位置吸附一次，消除按行取整带来的细微误差
                    updateScrollThumbPosition()
                    showScrollThumb()
                    true
                }
                else -> false
            }
        }
    }

    private fun onEditorScrolled() {
        lineNumbers.invalidate()
        // 滚动路径只改 thumb 的 Y（setY 不触发 requestLayout），并顺延自动隐藏计时
        syncThumbOnScroll()
        maybeRefreshHighlight()
    }

    /** 滚动中每帧：更新拇指位置 + 显示 + 重置自动隐藏计时 */
    private fun syncThumbOnScroll() {
        val geo = scrollGeo()
        if (geo == null || geo.maxFirst <= 0) {
            hideThumb()
            return
        }
        // 拖动中位置由手指决定，这里不要再覆盖，否则会和手指抢位置造成抖动/漂移
        if (!draggingThumb) scrollThumb.y = scrollRatio() * geo.maxOffset
        showThumb()
        scrollThumb.removeCallbacks(hideThumbRunnable)
        scrollThumb.postDelayed(hideThumbRunnable, 1200)
    }

    private fun showScrollThumb() {
        val geo = scrollGeo()
        if (geo == null || geo.maxFirst <= 0) {
            hideThumb()
            return
        }
        showThumb()
        scrollThumb.removeCallbacks(hideThumbRunnable)
        scrollThumb.postDelayed(hideThumbRunnable, 1200)
    }

    /** 编辑区可滚动的最大像素偏移 */
    private fun editorMaxScroll(): Int {
        val layout = editor.layout ?: return 0
        val content = editor.paddingTop + layout.height + editor.paddingBottom
        return (content - editor.height).coerceAtLeast(0)
    }

    /** 全量更新（拇指高度 + 位置）：只在内容/尺寸变化时调用 */
    private fun updateScrollThumb() {
        val layout = editor.layout
        val trackH = editorContainer.height
        if (layout == null || trackH <= 0 || layout.lineCount <= 0) {
            hideThumb()
            return
        }
        val (first, last) = visibleLines(layout)
        val visible = (last - first + 1).coerceAtLeast(1)
        if (layout.lineCount <= visible) {
            // 一屏放得下，不需要滚动条
            hideThumb()
            return
        }
        val density = resources.displayMetrics.density
        val minThumb = (24 * density).toInt()
        // 拇指高度按「可视行数 / 总行数」计算，与滚动进度同一坐标系
        val thumbH = (visible.toFloat() / layout.lineCount * trackH)
            .toInt().coerceIn(minThumb, trackH)
        val lp = scrollThumb.layoutParams as FrameLayout.LayoutParams
        if (lp.height != thumbH) {
            lp.height = thumbH
            scrollThumb.layoutParams = lp
        }
        // 尺寸刚变，下一帧重新定位（避免用旧 height 算位置）
        post { updateScrollThumbPosition() }
    }

    /**
     * 只更新拇指位置（不 requestLayout）。
     *
     * 用 View.setY 设置绝对 Y，而不是改 layoutParams.topMargin：
     * 改 layoutParams 会 requestLayout，让 EditText 跟着重新 measure/layout，
     * 超长文本下每帧重建 Layout 就会表现为「一直抖动、滚不动」。
     */
    private fun updateScrollThumbPosition() {
        val geo = scrollGeo()
        if (geo == null || geo.maxFirst <= 0) {
            hideThumb()
            return
        }
        scrollThumb.y = scrollRatio() * geo.maxOffset
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        post { updateScrollThumb() }
    }

    // ---------- 内部 ----------

    /**
     * 一次文本编辑后的处理。
     *
     * 行号表能增量平移就立刻平移（O(行数)），让打字时行号不错位；
     * 语法高亮一律等输入停顿后再重建——超大文本下每次按键重建几千个 span
     * 是打字卡顿的直接来源，而停顿后着色一次完全够用。
     */
    private fun onContentEdited() {
        lastEditAt = SystemClock.uptimeMillis()
        val skip = skipIncrementalOnce
        skipIncrementalOnce = false
        val small = !skip && !pendingStructural &&
            pendingBefore <= INCREMENTAL_MAX_CHARS && pendingCount <= INCREMENTAL_MAX_CHARS
        if (small) shiftLineStarts()
        scheduleRefresh()
    }

    /** 行结构没变时只把变化点之后的行首整体平移，避免每次按键 O(n) 重扫全文 */
    private fun shiftLineStarts() {
        val text = editor.text ?: return
        val next = LineStarts.shift(
            lineNumbers.logicalStarts, pendingStart, pendingBefore, pendingCount, text.length
        )
        if (next == null) return
        if (next !== lineNumbers.logicalStarts) lineNumbers.logicalStarts = next
    }

    /** 输入停顿后再刷新：连续打字期间不重复做高亮/行号重建，避免大文本卡顿 */
    private fun scheduleRefresh() {
        handler.removeCallbacks(refreshRunnable)
        val len = editor.text?.length ?: 0
        // 超大文本把停顿窗口再拉长：保证两次重建之间能连续输入足够多的字符
        handler.postDelayed(refreshRunnable, if (len > LARGE_TEXT_CHARS) LARGE_TEXT_REFRESH_MS else REFRESH_MS)
    }

    /**
     * 滚动时只「重置计时器」，停止滚动 180ms 后才真正着色。
     * 滚动过程中完全不改 span，避免每帧触发重排/重绘导致抖动。
     */
    private fun maybeRefreshHighlight() {
        // 刚编辑过：着色统一交给 scheduleRefresh 的防抖窗口，滚动逻辑别抢先重建，
        // 否则打字时每 180ms 就被光标滚动触发一次全量着色，等于防抖白做
        if (SystemClock.uptimeMillis() - lastEditAt < EDIT_SPAN_QUIET_MS) return
        handler.removeCallbacks(scrollRefreshRunnable)
        val win = visibleWindow()
        if (win.isEmpty()) return
        val covered = highlightedWindow.first <= win.first && highlightedWindow.last >= win.last
        if (covered) return
        scrollRefreshPending = true
        handler.postDelayed(scrollRefreshRunnable, 180)
    }

    /** 当前可视区的字符区间（按整行对齐） */
    private fun visibleWindow(): IntRange {
        val layout = editor.layout ?: return IntRange.EMPTY
        if (layout.lineCount <= 0) return IntRange.EMPTY
        val top = editor.scrollY
        val first = layout.getLineForVertical(top.coerceAtLeast(0))
        val last = layout.getLineForVertical(top + editor.height)
        val start = layout.getLineStart(first)
        val end = layout.getLineEnd(last)
        return if (end > start) start until end else IntRange.EMPTY
    }

    /** 需要着色的区间：可视区上下各留若干行余量 */
    private fun highlightWindow(): IntRange {
        val len = editor.text?.length ?: 0
        if (len == 0) return IntRange.EMPTY
        val layout = editor.layout
        if (layout == null || layout.lineCount <= 0) {
            return 0 until len.coerceAtMost(FALLBACK_HIGHLIGHT_CHARS)
        }
        val top = editor.scrollY
        val first = (layout.getLineForVertical(top.coerceAtLeast(0)) - MARGIN_LINES).coerceAtLeast(0)
        val last = (layout.getLineForVertical(top + editor.height) + MARGIN_LINES)
            .coerceAtMost(layout.lineCount - 1)
        val start = layout.getLineStart(first)
        var end = layout.getLineEnd(last).coerceAtMost(len)
        // 预算保护：可视区恰好落在一条超长行上时，限制单次着色的字符数
        if (end - start > MAX_HIGHLIGHT_CHARS) end = (start + MAX_HIGHLIGHT_CHARS).coerceAtMost(len)
        return if (end > start) start until end else IntRange.EMPTY
    }

    private fun applySpans() {
        val editable = editor.text
        if (editable !is Spannable) return
        applyingSpans = true
        try {
            // 只清除自己加的 span：自己记账比每次 getSpans 全表扫描便宜，
            // 也不会碰到输入法的组合span 与选择 handle
            for (i in appliedSpans.indices) editable.removeSpan(appliedSpans[i])
            appliedSpans.clear()

            val len = editable.length
            val win = highlightWindow()
            highlightedWindow = win

            /** 单次最多添加的 span 数：按可视行数估算，防止极端文本下 span 爆炸拖慢绘制 */
            var spanBudget = spanBudgetFor(win)

            // 1) 语法高亮：只处理可视区域（含余量）切片。
            // 全文正则 + 全量 span 是大文件滚动卡顿的主要来源；文本极大时连切片着色也放弃，
            // 只保留错误行与搜索命中，把输入流畅度放在第一位。
            if (len <= HIGHLIGHT_OFF_CHARS && !win.isEmpty() && len > 0) {
                val from = win.first.coerceIn(0, len)
                val to = (win.last + 1).coerceIn(from, len)
                if (to > from) {
                    val slice = editable.subSequence(from, to).toString()
                    for (t in JsonHighlighter.findTokens(slice)) {
                        if (spanBudget <= 0) break
                        val s = from + t.start
                        val e = (from + t.end).coerceAtMost(len)
                        // 超长 token（几万字符的字符串值）跳过：一个跨几万字符的 span
                        // 对观感帮助很小，却会显著拖慢 TextView 的绘制
                        if (e > s && e - s <= MAX_TOKEN_CHARS) {
                            val span = FgSpan(colorOf(t.kind))
                            editable.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                            appliedSpans.add(span)
                            spanBudget--
                        }
                    }
                }
            }

            // 2) 错误行 + 错误列（按偏移定位，无需复制全文）
            currentError?.let { err ->
                val (start, end) = lineRange(editable, err.line)
                if (end > start && start < len) {
                    val s = start.coerceAtMost(len)
                    val e = end.coerceAtMost(len)
                    if (e > s) {
                        val lineBg = BgSpan(themeColors.errorLine)
                        editable.setSpan(lineBg, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appliedSpans.add(lineBg)
                        val colStart = err.offset.coerceIn(s, e - 1)
                        val colBg = BgSpan(themeColors.errorColumn)
                        editable.setSpan(colBg, colStart, colStart + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appliedSpans.add(colBg)
                    }
                }
            }

            // 3) 搜索命中（后加的 span 覆盖在前的，当前命中用醒目色）
            // 只画与可视区相交的命中，并限制数量：几千个命中同时上 span 会拖垮绘制
            if (searchMatches.isNotEmpty()) {
                val lo = if (win.isEmpty()) 0 else win.first
                val hi = if (win.isEmpty()) len else win.last + 1
                var budget = MAX_SEARCH_SPANS
                searchMatches.forEachIndexed { i, r ->
                    if (budget <= 0) return@forEachIndexed
                    if (r.last + 1 <= lo || r.first >= hi) return@forEachIndexed
                    val s = r.first.coerceIn(0, len)
                    val e = (r.last + 1).coerceIn(s, len)
                    if (e > s) {
                        val color =
                            if (i == searchCurrent) themeColors.searchCurrent else themeColors.searchMatch
                        val span = BgSpan(color)
                        editable.setSpan(span, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        appliedSpans.add(span)
                        budget--
                    }
                }
            }
        } catch (e: Exception) {
            // 极端文本下 span 异常不能拖垮整个界面
        } finally {
            applyingSpans = false
        }
    }

    private fun colorOf(kind: TokenKind): Int = when (kind) {
        TokenKind.KEY -> themeColors.jsonKey
        TokenKind.STRING -> themeColors.jsonString
        TokenKind.NUMBER -> themeColors.jsonNumber
        TokenKind.LITERAL -> themeColors.jsonBoolean
        TokenKind.PUNCT -> themeColors.jsonPunct
    }

    /** 本次着色允许的 span 数：按可视行数估算，避免一次性给整屏塞几千个 span */
    private fun spanBudgetFor(win: IntRange): Int {
        if (win.isEmpty()) return 0
        val layout = editor.layout ?: return MAX_SPANS
        val (first, last) = visibleLines(layout)
        val rows = (last - first + 1 + MARGIN_LINES * 2).coerceAtLeast(1)
        return (rows * SPANS_PER_LINE).coerceIn(MIN_SPANS, MAX_SPANS)
    }

    /** 一次 O(n) 扫描建立逻辑行起始表（供行号栏按可视行查号），并顺带统计最长行 */
    private fun rebuildLineStarts() {
        val text = editor.text ?: return
        val starts = LineStarts.build(text)
        lineNumbers.logicalStarts = starts
        updateGutterWidth(starts.size)
        val maxLine = LineStarts.maxLineLength(text)

        // 超长行（几万字符的值）在关闭换行时会被排成一行，绘制成本 O(字符数)，
        // 滚动时每帧都要从行首累加定位，表现为卡死/抖动。这里自动降级为软换行。
        // 用「开启阈值 > 关闭阈值」的滞回，避免临界值反复切换导致 Layout 反复重建。
        val needAutoWrap = if (autoWrap) maxLine > SUPER_LONG_LINE_RELEASE
        else maxLine > SUPER_LONG_LINE_TRIGGER
        if (needAutoWrap != autoWrap) {
            autoWrap = needAutoWrap
            syncWrapMode()
            if (needAutoWrap && !autoWrapNotified) {
                autoWrapNotified = true
                onAutoWrap?.invoke()
            }
        }
    }

    private fun updateGutterWidth(lineCount: Int) {
        val digits = lineCount.toString().length.coerceAtLeast(2)
        val w = (lineNumbers.digitsWidth(digits) + 14 * resources.displayMetrics.density).toInt()
        if (lineNumbers.layoutParams.width != w) {
            lineNumbers.layoutParams.width = w
            lineNumbers.requestLayout()
        }
    }

    /** 第 line 行的字符范围（手动扫描，不复制全文；大文件下 O(n) 但零分配） */
    private fun lineRange(text: CharSequence, line: Int): Pair<Int, Int> {
        var idx = 0
        var l = 1
        val n = text.length
        while (l < line && idx < n) {
            if (text[idx] == '\n') l++
            idx++
        }
        if (idx >= n) return n to n
        var end = idx
        while (end < n && text[end] != '\n') end++
        return idx to end
    }

    private class FgSpan(color: Int) : ForegroundColorSpan(color)
    private class BgSpan(color: Int) : BackgroundColorSpan(color)

    /** 定位闪烁用的独立 span：与语法高亮的 BgSpan 区分，避免被清理逻辑一起删掉 */
    private class FlashSpan(color: Int) : BackgroundColorSpan(color)

    private companion object {
        /** 可视区上下额外着色的行数：滚动时不至于露出未着色行 */
        const val MARGIN_LINES = 6

        /** layout 尚未就绪（首帧）时的兜底着色长度 */
        const val FALLBACK_HIGHLIGHT_CHARS = 4000

        /** 单次着色最多处理的字符数 */
        const val MAX_HIGHLIGHT_CHARS = 20_000

        /** 单个 token 的最大着色长度：超过则跳过（几万字符的字符串值） */
        const val MAX_TOKEN_CHARS = 3_000

        /** 单次最多添加的语法高亮 span 数（按可视行估算后的硬上限） */
        const val MAX_SPANS = 1_200

        /** 每行预留的 token 数：把 span 预算限制在「看得见的部分」 */
        const val SPANS_PER_LINE = 24

        /** 可视行极少时的保底预算 */
        const val MIN_SPANS = 200

        /** 单次最多添加的搜索命中 span 数 */
        const val MAX_SEARCH_SPANS = 200

        /** 单次变化超过该长度就不做行首增量平移，直接全量重建 */
        const val INCREMENTAL_MAX_CHARS = 2_048

        /** 输入停顿后重建高亮与行号的延迟 */
        const val REFRESH_MS = 400L

        /** 超大文本的停顿窗口：打字期间完全不动 span，停下再着色 */
        const val LARGE_TEXT_REFRESH_MS = 900L

        /** 超过该长度按「超大文本」处理 */
        const val LARGE_TEXT_CHARS = 200_000

        /** 超过该长度彻底关闭语法高亮（错误行与搜索命中仍保留） */
        const val HIGHLIGHT_OFF_CHARS = 400_000

        /** 编辑后的静默期：期间滚动不触发着色，避免打断输入 */
        const val EDIT_SPAN_QUIET_MS = 700L

        /** 单条逻辑行超过该长度 → 自动开启换行（超长单行绘制极慢） */
        const val SUPER_LONG_LINE_TRIGGER = 3_000

        /** 已开启自动换行后，最长行降到该长度以下才恢复横向滚动（滞回） */
        const val SUPER_LONG_LINE_RELEASE = 1_200

        /** 定位闪烁高亮的持续时间 */
        const val FLASH_MS = 1_600L
    }
}
