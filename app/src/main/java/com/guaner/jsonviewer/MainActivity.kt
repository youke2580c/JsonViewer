package com.guaner.jsonviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.inputmethod.InputMethodManager
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.ListPopupWindow
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import com.guaner.jsonviewer.databinding.ActivityMainBinding
import com.guaner.jsonviewer.crash.CrashLogger
import com.guaner.jsonviewer.json.JsonValueValidator
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.data.JsonHistory
import com.guaner.jsonviewer.data.SessionStore
import com.guaner.jsonviewer.data.UaProfiles
import com.guaner.jsonviewer.data.UrlHistory
import com.guaner.jsonviewer.json.JsonError
import com.guaner.jsonviewer.json.JsonExtractor
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.json.JsonOffsetLocator
import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.JsonSerializer
import com.guaner.jsonviewer.json.ParseResult
import com.guaner.jsonviewer.json.TreeNode
import com.guaner.jsonviewer.json.TreeFlattener
import com.guaner.jsonviewer.theme.ThemeManager
import com.guaner.jsonviewer.ui.BottomActionCatalog
import com.guaner.jsonviewer.ui.HistoryDialog
import com.guaner.jsonviewer.ui.ThemedDialog
import com.guaner.jsonviewer.ui.JsonPathSheet
import com.guaner.jsonviewer.ui.NetworkSheet
import com.guaner.jsonviewer.ui.OutlineAdapter
import com.guaner.jsonviewer.ui.ThemeSettingsActivity
import com.guaner.jsonviewer.ui.TreeAdapter
import com.guaner.jsonviewer.ui.ValueSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * JSON 查看器主界面。
 *
 * 数据流：编辑器文本 --(防抖 350ms 解析)--> JsonParser --(成功: 建树 + 全树索引 / 失败: 行列级错误)
 *
 * 表单（树状）模式采用「文件管理器式」逐层进入：currentPath 指向当前所在容器（如 $.store.book），
 * 列表只渲染该层的直接子项。点击对象/数组即「进入」下一层，顶部面包屑每一段都可点回任意层级。
 * 代码模式与表单模式共享同一份文本；面包屑、搜索、JSONPath 定位全部由解析结果驱动。
 */
class MainActivity : AppCompatActivity() {

    private enum class Mode { CODE, FORM }

    private lateinit var binding: ActivityMainBinding
    private lateinit var treeAdapter: TreeAdapter

    private var mode = Mode.FORM
    private var lastRoot: TreeNode? = null
    private var lastError: JsonError? = null
    private var selectedNode: TreeNode? = null

    /** 当前所在层级（容器节点路径）。"$" 表示根层 */
    private var currentPath: String = "$"
    /** 全树 path -> node 索引，解析成功后建一次，供导航/定位使用 */
    private var treeIndex: Map<String, TreeNode> = emptyMap()

    private var parseJob: Job? = null
    private var treeSearchJob: Job? = null

    // 代码模式搜索状态
    private var codeMatches: List<IntRange> = emptyList()
    private var codeMatchIndex = -1

    // 表单模式搜索状态（全树匹配路径，定位时跳到对应父层）
    private var treeMatches: List<String> = emptyList()
    private var treeMatchIndex = -1

    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result: ActivityResult ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                readTextFromUri(uri) { text -> setJsonText(text, "已导入文件") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        AppSettings.init(this)
        JsonHistory.init(this)
        UrlHistory.init(this)
        UaProfiles.init(this)
        CrashLogger.noteScreen("主界面")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        setupEditor()
        setupTree()
        setupOutline()
        setupSearch()
        setupErrorBanner()
        setupBottomBar()

        binding.loadSampleButton.setOnClickListener { loadSample() }

        // 默认进入表单（逐层进入树状）模式
        switchMode(Mode.FORM)
        applyTheme()
        restoreSession(savedInstanceState)

        // 系统返回（导航栏返回键 / 手势返回）：优先关搜索 → 表单子层级回退一层 → 否则默认退出
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // 大纲抽屉打开时先关抽屉
                if (binding.drawerLayout.isDrawerOpen(binding.outlinePanel)) {
                    binding.drawerLayout.closeDrawer(binding.outlinePanel)
                    return
                }
                if (binding.searchRowCode.isVisible) {
                    closeCodeSearch()
                    return
                }
                if (binding.searchRowForm.isVisible) {
                    closeTreeSearch()
                    return
                }
                if (mode == Mode.FORM && currentPath != "$") {
                    goUp()
                    return
                }
                // 无特殊层级时交给系统默认（结束 Activity）
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        })

        handleIntent(intent)
        maybeNotifyRecentCrash()
    }

    /**
     * 上次异常退出后，本次启动给一句轻提示，告诉用户去哪里看日志。
     *
     * 只提示「24 小时内写入、且没提示过」的那一条：既不会每次开都弹，
     * 也不会把几个月前的老日志翻出来吓人。目录列举放在 IO 线程，不拖慢启动。
     */
    private fun maybeNotifyRecentCrash() {
        lifecycleScope.launch {
            val latest = withContext(Dispatchers.IO) {
                CrashLogger.logs(this@MainActivity).firstOrNull()
            } ?: return@launch
            val age = System.currentTimeMillis() - latest.file.lastModified()
            if (age > 24 * 60 * 60 * 1000L) return@launch
            val prefs = getSharedPreferences("crash_notice", Context.MODE_PRIVATE)
            if (prefs.getString("last", null) == latest.file.name) return@launch
            prefs.edit().putString("last", latest.file.name).apply()
            if (isFinishing || isDestroyed) return@launch
            toast("上次异常退出，崩溃日志已保存（设置 → 崩溃日志）")
        }
    }

    /** 把设置里的开关同步到系统：启用/停用 PROCESS_TEXT 别名（长按选词菜单立即生效） */
    private fun applyProcessTextSetting() {
        val state = if (AppSettings.showProcessText) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(this, "com.guaner.jsonviewer.ProcessTextAlias"),
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // 个别 ROM 限制组件状态切换，失败就保持默认（显示入口）
        }
    }

    override fun onResume() {
        super.onResume()
        applyProcessTextSetting()
        binding.editorView.applyThemeFromManager()
        treeAdapter.applyThemeFromManager()
        applyTheme()
        // 设置页可能改过按钮顺序 / 快捷跳转开关 / 大纲滑出方向，回来时同步一次
        applyBottomOrder()
        applyOutlineSide()
        if (mode == Mode.FORM) {
            binding.quickJumpScroll.isVisible =
                AppSettings.showQuickJump && binding.quickJumpRow.childCount > 0
        }
    }

    override fun onStop() {
        // 退到后台就落盘：进程被系统回收后再次打开能恢复上次内容
        persistNow(sync = true)
        super.onStop()
    }

    // ---------- 表单页边缘内滑：呼出结构大纲 ----------

    private var edgePending = false
    private var edgeClaimed = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f

    /** 本次手势从哪一侧边缘起手（双侧模式下两侧都认） */
    private var edgeFromRight = false
    private val edgeSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop }
    /** 与编辑器保持一致：48dp（和放宽后的 DrawerLayout 原生边缘相同，避免出现「40~48dp 没反应」的死区） */
    private val formEdgeWidth by lazy { 48 * resources.displayMetrics.density }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (handleFormEdgeSwipe(ev)) return true
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 只在表单页做兜底：代码页有编辑器自己的识别（更跟手），表单页的 RecyclerView
     * 开始滚动后会 requestDisallowInterceptTouchEvent，DrawerLayout 拿不到后续事件。
     *
     * 命中条件：起手点在面板所在侧边缘 40dp 内 + 朝屏幕内侧滑动 + 纵向位移不明显。
     * 命中后吞掉整个手势，避免同时触发列表滚动。
     */
    private fun handleFormEdgeSwipe(ev: MotionEvent): Boolean {
        if (mode != Mode.FORM) return false
        if (AppSettings.swipeOff) return false
        if (binding.drawerLayout.isDrawerOpen(binding.outlinePanel)) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val leftHit = ev.x <= formEdgeWidth
                val rightHit = ev.x >= resources.displayMetrics.widthPixels - formEdgeWidth
                val both = AppSettings.swipeBoth
                edgeFromRight = if (both) rightHit else AppSettings.isOutlineRight
                edgePending = when {
                    both -> leftHit || rightHit
                    AppSettings.isOutlineRight -> rightHit
                    else -> leftHit
                }
                if (edgePending) prepareOutlineSide(edgeFromRight)
                edgeClaimed = false
                edgeDownX = ev.x
                edgeDownY = ev.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeClaimed) return true
                if (!edgePending) return false
                val dx = ev.x - edgeDownX
                val dy = kotlin.math.abs(ev.y - edgeDownY)
                val inward = if (edgeFromRight) -dx else dx
                if (inward > edgeSlop && inward > dy * 1.5f) {
                    edgePending = false
                    // 没有可展开的内容时只给提示，不吞掉手势
                    if (lastRoot == null) {
                        openOutline(edgeFromRight)
                        return false
                    }
                    edgeClaimed = true
                    openOutline(edgeFromRight)
                    return true
                }
                if (dy > edgeSlop * 2 || inward < -edgeSlop) edgePending = false
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val claimed = edgeClaimed
                edgePending = false
                edgeClaimed = false
                if (claimed) return true
            }
        }
        return false
    }

    // ---------- 会话记忆（上次内容） ----------

    /** 上次已落盘的内容，用于跳过无变化的重复写入 */
    private var lastSavedSession: String? = null
    private var sessionJob: Job? = null

    /**
     * 恢复上次内容。
     * - 外部「打开文件 / 分享」进来（intent 带数据）时不恢复，避免覆盖用户要看的文件；
     * - 横竖屏等重建时 EditText 自己会恢复文本，也不重复恢复。
     */
    private fun restoreSession(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        if (!AppSettings.autoRestoreSession) return
        val intent = intent
        val fromOutside = intent != null && (
            intent.action == Intent.ACTION_VIEW ||
                intent.action == Intent.ACTION_SEND ||
                intent.action == Intent.ACTION_PROCESS_TEXT
            )
        if (fromOutside) return
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) { SessionStore.load(this@MainActivity) }
            if (text.isNullOrBlank()) return@launch
            // 恢复期间用户已经开始输入/粘贴，则不覆盖
            if (binding.editorView.text.isNotBlank()) return@launch
            lastSavedSession = text
            binding.editorView.setTextProgrammatic(text)
            toast("已恢复上次的内容")
        }
    }

    /** 内容变化后延迟落盘：连续输入期间不反复写文件 */
    private fun schedulePersist() {
        sessionJob?.cancel()
        sessionJob = lifecycleScope.launch {
            delay(1200)
            persistNow(sync = false)
        }
    }

    /**
     * 立即落盘。sync=true 用于 onStop：此时 lifecycleScope 可能已被取消，
     * 因此用独立线程写，保证真正写完。
     */
    private fun persistNow(sync: Boolean) {
        // 关闭「自动恢复上次内容」后不再落盘：本机不保留任何文本副本
        if (!AppSettings.autoRestoreSession) {
            lastSavedSession = null
            SessionStore.clear(applicationContext)
            return
        }
        val text = binding.editorView.text
        if (text == lastSavedSession) return
        lastSavedSession = text
        val appContext = applicationContext
        if (sync) {
            Thread { SessionStore.save(appContext, text) }.start()
        } else {
            lifecycleScope.launch(Dispatchers.IO) { SessionStore.save(appContext, text) }
        }
    }

    /**
     * 主题着色：把所有可着色元素统一套用当前生效主题。
     * - 顶部系统状态栏「贴近哪个容器就跟随哪个容器的背景色」：
     *   代码页贴近编辑器背景(background)，表单页贴近面包屑背景(gutter)。
     * - 底部栏/面包屑容器背景、Tab 指示器与文字、按钮/图标 tint、搜索行、
     *   表单页容器与列表背景，全部由 ThemeColors 驱动；滑块/按钮/指示器统一用强调色 accent，
     *   其余无法单独设置的颜色（如卡片底色）按主色/背景自动派生。
     */
    private fun applyTheme() {
        val colors = ThemeManager.activeColors
        val accent = colors.accent

        // 状态栏：跟随当前页面最邻近容器的背景色
        val statusColor = if (mode == Mode.CODE) colors.background else colors.gutter
        window.statusBarColor = statusColor
        val light = ThemeManager.contrastText(statusColor) == Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = light

        // 底部操作栏（含 Tab 切换条）与面包屑容器背景
        binding.bottomBar.setBackgroundColor(colors.surface)
        binding.breadcrumbRow.setBackgroundColor(colors.gutter)

        // Tab 切换条：指示器与选中文字用强调色，未选中用次要文字色
        binding.tabs.setSelectedTabIndicatorColor(accent)
        binding.tabs.setTabTextColors(colors.gutterText, accent)
        binding.tabs.setBackgroundColor(colors.surface)

        // 图标 tint（按钮 / 返回 / 复制）统一强调色
        val iconTint = ColorStateList.valueOf(accent)
        bottomButtons.values.forEach { it.imageTintList = iconTint }
        binding.btnMore.imageTintList = iconTint
        binding.copyPathButton.imageTintList = iconTint
        binding.breadcrumbBack.imageTintList = iconTint
        binding.elementCountText.setTextColor(colors.gutterText)

        // 语法错误横幅：底色取主题的错误高亮色，文字用其对比色
        val onError = ThemeManager.contrastText(colors.errorLine)
        binding.errorBanner.setCardBackgroundColor(colors.errorLine)
        binding.errorTitle.setTextColor(onError)
        binding.errorDetail.setTextColor(ThemeManager.mix(onError, colors.errorLine, 0.3f))
        binding.tvErrorHint.setTextColor(ThemeManager.mix(onError, colors.errorLine, 0.3f))

        // 搜索行（代码/表单）：背景跟随面包屑(gutter)，图标 tint 用强调色
        themeSearchRow(binding.searchRowCode, iconTint, colors)
        themeSearchRow(binding.searchRowForm, iconTint, colors)

        // 表单页：容器与列表背景跟随主题背景色
        binding.formPanel.setBackgroundColor(colors.background)
        binding.treeView.setBackgroundColor(colors.background)
        binding.codePanel.setBackgroundColor(colors.background)
        binding.emptyHint.setTextColor(colors.gutterText)
        binding.loadSampleButton.setTextColor(accent)

        // 当前层快捷跳转条 + 左侧结构大纲
        binding.quickJumpScroll.setBackgroundColor(colors.gutter)
        updateQuickJumpSelection()
        binding.drawerLayout.setScrimColor(0x66000000)
        styleOutlineChrome(colors)
        outlineAdapter.applyTheme()
    }

    /** 给搜索行套用主题：行背景 + 按钮/计数文字 tint */
    private fun themeSearchRow(row: View, iconTint: ColorStateList, colors: com.guaner.jsonviewer.theme.ThemeColors) {
        row.setBackgroundColor(colors.gutter)
        row.findViewById<View>(R.id.searchPrevCode)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchNextCode)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchCloseCode)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchPrevForm)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchNextForm)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchCloseForm)?.let { (it as? android.widget.ImageButton)?.imageTintList = iconTint }
        row.findViewById<View>(R.id.searchCountCode)?.let { (it as? TextView)?.setTextColor(colors.gutterText) }
        row.findViewById<View>(R.id.searchCountForm)?.let { (it as? TextView)?.setTextColor(colors.gutterText) }
        listOf(R.id.searchInputCode, R.id.searchInputForm).forEach { id ->
            row.findViewById<View>(id)?.let { v ->
                (v as? android.widget.EditText)?.apply {
                    setTextColor(colors.text)
                    setHintTextColor(colors.gutterText)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("mode", if (mode == Mode.FORM) 1 else 0)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val saved = if (savedInstanceState.getInt("mode", 0) == 1) Mode.FORM else Mode.CODE
        binding.root.post { switchMode(saved) }
    }

    // ---------- 初始化 ----------

    private fun setupTabs() {
        binding.tabs.addTab(binding.tabs.newTab().setText("代码"))
        binding.tabs.addTab(binding.tabs.newTab().setText("表单"))
        binding.tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                switchMode(if (tab?.position == 1) Mode.FORM else Mode.CODE)
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupEditor() {
        // 回调不带文本：大文本下每次按键全量 toString 是主要卡顿来源，
        // 改为防抖后在 scheduleParse 里取一次快照。
        binding.editorView.onTextChanged = {
            scheduleParse()
            schedulePersist()
        }
        binding.editorView.onOversize = { len ->
            toast("内容已达 ${JsonLimits.formatChars(len)}，超出安全上限，语法高亮已自动关闭")
        }
        // 存在超长行（几万字符的值）时自动换成软换行，否则单行绘制会让滚动卡死
        binding.editorView.onAutoWrap = {
            toast("检测到超长行，已自动换行以保证滚动流畅")
        }
    }

    private fun setupTree() {
        treeAdapter = TreeAdapter(
            onClick = { node ->
                // 容器节点 -> 进入该层；叶子 -> 选中高亮（点值行才看完整值）
                if (node.isContainer) enterNode(node) else selectNode(node)
            },
            onLongClick = { node -> copyToClipboard(node.path, "JSONPath") },
            onValueClick = { node -> openValueSheet(node) },
            onValueLongClick = { node ->
                val value = node.scalar.orEmpty()
                if (value.isEmpty()) toast("该值为空，没有可复制的内容")
                else copyToClipboard(value, "值")
            },
            onMenu = { node, popup -> buildNodeMenu(node, popup) }
        )
        binding.treeView.layoutManager = LinearLayoutManager(this)
        binding.treeView.adapter = treeAdapter
        binding.copyPathButton.setOnClickListener {
            // 选中子节点则复制该节点路径；否则复制当前面包屑层级路径（如 $.store / $.store.book）
            val path = selectedNode?.path ?: currentPath
            copyToClipboard(path, "JSONPath")
        }
        binding.breadcrumbBack.setOnClickListener { goUp() }
    }

    /** 面包屑左侧返回：回到当前层的父层级 */
    private fun goUp() {
        if (currentPath == "$") {
            toast("已在根目录")
            return
        }
        currentPath = TreeNode.ancestorsOf(currentPath).lastOrNull() ?: "$"
        selectedNode = null
        renderLevel()
    }

    // ---------- 结构大纲（左侧抽屉：全树一览 + 快速定位） ----------

    private lateinit var outlineAdapter: OutlineAdapter

    /** 已展开的容器路径（大纲树） */
    private val outlineExpanded = HashSet<String>()

    /** 默认显示全部节点（含最终项）；开启后只看容器，便于看整体结构 */
    private var outlineContainersOnly = false

    private fun setupOutline() {
        outlineAdapter = OutlineAdapter(
            onPick = { row -> pickOutline(row.node) },
            onToggle = { row ->
                if (!outlineExpanded.remove(row.node.path)) outlineExpanded.add(row.node.path)
                rebuildOutline()
            }
        )
        binding.outlineList.layoutManager = LinearLayoutManager(this)
        binding.outlineList.adapter = outlineAdapter

        binding.outlineClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(binding.outlinePanel)
        }
        binding.outlineSearch.addTextChangedListener(afterTextChanged = { rebuildOutline() })
        binding.outlineOnlyContainers.setOnClickListener {
            outlineContainersOnly = !outlineContainersOnly
            rebuildOutline()
        }
        binding.outlineExpandAll.setOnClickListener {
            expandOutlineAll()
            rebuildOutline()
        }
        binding.outlineCollapseAll.setOnClickListener {
            outlineExpanded.clear()
            rebuildOutline()
        }
        binding.drawerLayout.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                // 手势直接拖开（不经 openOutline）时也要展开到当前层级
                outlineExpanded.addAll(TreeNode.ancestorsOf(currentPath))
                if (currentPath != "$") outlineExpanded.add(currentPath)
                rebuildOutline()
            }
        })
        setupOutlineSwipe()
    }

    /**
     * 边缘内滑呼出大纲。
     *
     * 三层保障：
     * 1. DrawerLayout 默认只在边缘 20dp 内响应，手指很难正好压线 → 反射把左右两个
     *    dragger 的边距都放宽到 48dp；
     * 2. 代码页的 EditText 会在拖动时抢占事件（requestDisallowInterceptTouchEvent），
     *    所以编辑器自己再识别一次边缘内滑；
     * 3. 表单页的 RecyclerView 同样会 disallowIntercept，这里在 Activity 层再兜一次。
     */
    private fun setupOutlineSwipe() {
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED, binding.outlinePanel)
        binding.editorView.onEdgeSwipe = { fromRight -> openOutline(fromRight) }
        binding.editorView.onEdgeHint = { fromRight -> prepareOutlineSide(fromRight) }
        applyOutlineSide()
    }

    /**
     * 把大纲面板放到指定一侧（DrawerLayout 靠子 View 的 gravity 决定位置）。
     * 返回是否真的换了边——调用方据此决定要不要等一帧布局再开抽屉。
     */
    private fun positionOutline(fromRight: Boolean): Boolean {
        val panel = binding.outlinePanel
        val lp = panel.layoutParams as? DrawerLayout.LayoutParams ?: return false
        val target = if (fromRight) Gravity.END else Gravity.START
        val changed = lp.gravity != target
        if (changed) {
            if (binding.drawerLayout.isDrawerOpen(panel)) {
                binding.drawerLayout.closeDrawer(panel, false)
            }
            lp.gravity = target
            panel.layoutParams = lp
            // 立刻请求重新布局，让面板这帧就站到新的一侧（否则 openDrawer 会从旧位置滑出）
            panel.requestLayout()
        }
        // 关闭箭头始终指向面板内侧：面板在右边时把默认的左箭头转 180°
        binding.outlineClose.rotation = if (fromRight) 180f else 0f
        return changed
    }

    /**
     * 边缘手势 DOWN 时先把面板摆到起手那侧。
     *
     * 关闭中的面板在屏幕外，换边看不出来；等手指真的滑到阈值再摆就来不及——
     * DrawerLayout 的拖动已经从旧位置开始了，看起来就是「从对面飞过来」。
     */
    private fun prepareOutlineSide(fromRight: Boolean) {
        if (binding.drawerLayout.isDrawerOpen(binding.outlinePanel)) return
        positionOutline(fromRight)
    }

    /** 读取设置：初始方位 + 边缘滑动范围（同侧 / 双侧 / 关闭） */
    private fun applyOutlineSide() {
        positionOutline(AppSettings.isOutlineRight)
        val swipeOff = AppSettings.swipeOff
        binding.editorView.edgeSwipeEnabled = !swipeOff
        binding.editorView.edgeSwipeFromRight = AppSettings.isOutlineRight
        binding.editorView.edgeSwipeBoth = AppSettings.swipeBoth
        widenDrawerEdge(!swipeOff)
    }

    /** [enabled] 为 false 时把边缘触发宽度设为 0，等于关掉原生边缘手势（按钮仍能打开抽屉） */
    private fun widenDrawerEdge(enabled: Boolean = true) {
        val edge = if (enabled) (48 * resources.displayMetrics.density).toInt() else 0
        // 左右两个 dragger 都要放宽：面板换到右侧后走的是 mRightDragger
        listOf("mLeftDragger", "mRightDragger").forEach { name ->
            try {
                val f = DrawerLayout::class.java.getDeclaredField(name)
                f.isAccessible = true
                val helper = f.get(binding.drawerLayout) as? androidx.customview.widget.ViewDragHelper
                    ?: return@forEach
                val edgeField = androidx.customview.widget.ViewDragHelper::class.java
                    .getDeclaredField("mEdgeSize")
                edgeField.isAccessible = true
                edgeField.setInt(helper, edge)
            } catch (e: Throwable) {
                // 个别 ROM 字段名不同，失败就退回系统默认边距，不影响其他功能
            }
        }
    }

    /**
     * 打开结构大纲。
     *
     * [fromRight] 为空表示「点大纲按钮」：按设置里的滑出方向出；
     * 非空表示来自边缘滑动：从哪条边起手，面板就从哪边出（双侧模式靠这个各归各位，
     * 同侧模式下起手边本来就和设置一致）。
     */
    private fun openOutline(fromRight: Boolean? = null) {
        if (lastRoot == null) {
            toast(if (lastError != null) "当前 JSON 有语法错误，请先修正" else "请先输入 JSON 内容")
            return
        }
        val changed = positionOutline(fromRight ?: AppSettings.isOutlineRight)
        // 展开到当前所在层级，打开大纲就能看到自己处于树的哪个位置
        outlineExpanded.addAll(TreeNode.ancestorsOf(currentPath))
        if (currentPath != "$") outlineExpanded.add(currentPath)
        rebuildOutline()
        if (changed) {
            // 换边后必须等这一帧布局完成再开：DrawerLayout 以子 View 的 left 作为滑动起点，
            // 不等的话面板还站在旧侧，看起来就是从屏幕对面飞过来
            openDrawerWhenLaidOut()
        } else {
            binding.drawerLayout.openDrawer(binding.outlinePanel, true)
        }
    }

    /** 换边后等布局完成再打开；布局监听万一没触发，80ms 后兜底打开 */
    private fun openDrawerWhenLaidOut() {
        val panel = binding.outlinePanel
        var opened = false
        fun openOnce() {
            if (opened) return
            opened = true
            panel.removeOnLayoutChangeListener(layoutOpenListener)
            binding.drawerLayout.openDrawer(panel, true)
        }
        layoutOpenListener = object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View, l: Int, t: Int, r: Int, b: Int,
                ol: Int, ot: Int, or_: Int, ob: Int
            ) {
                // 只关心「左边距变了」的那次布局（说明面板已经站到新的一侧）
                if (l != ol) openOnce()
            }
        }
        panel.addOnLayoutChangeListener(layoutOpenListener)
        panel.requestLayout()
        panel.postDelayed({ openOnce() }, 80)
    }

    private var layoutOpenListener: View.OnLayoutChangeListener? = null

    /**
     * 展开全部层级（不止两层）。
     *
     * 用显式栈做深度优先，避免深嵌套 JSON 递归爆栈；
     * 展开的容器数量与最终行数都受 [MAX_OUTLINE_ROWS] 约束，防止几十万节点全铺开卡死列表。
     */
    private fun expandOutlineAll() {
        val root = lastRoot ?: return
        outlineExpanded.clear()
        val stack = ArrayDeque<TreeNode>()
        for (i in root.children.indices.reversed()) stack.addLast(root.children[i])
        var budget = MAX_OUTLINE_ROWS
        while (stack.isNotEmpty() && budget > 0) {
            val node = stack.removeLast()
            if (!node.isContainer || node.children.isEmpty()) continue
            outlineExpanded.add(node.path)
            budget--
            for (i in node.children.indices.reversed()) stack.addLast(node.children[i])
        }
    }

    /** 抽屉打开时才重建大纲（关闭状态下重建纯属浪费） */
    private fun refreshOutlineIfOpen() {
        if (binding.drawerLayout.isDrawerOpen(binding.outlinePanel)) rebuildOutline()
    }

    /** 大纲搜索防抖任务：大 JSON 的全树扫描必须放后台，否则每敲一个字都卡输入 */
    private var outlineSearchJob: Job? = null

    /** 重新组装大纲行：有搜索词走扁平命中列表，否则走可折叠树 */
    private fun rebuildOutline() {
        val root = lastRoot
        val query = binding.outlineSearch.text?.toString()?.trim().orEmpty()
        val colors = ThemeManager.activeColors
        styleOutlineChrome(colors)

        if (root == null) {
            outlineSearchJob?.cancel()
            outlineAdapter.submit(emptyList())
            binding.outlineCount.text = ""
            return
        }
        outlineSearchJob?.cancel()

        if (query.isEmpty()) {
            val rows = ArrayList<OutlineAdapter.Row>()
            for (c in root.children) collectOutlineRow(c, 0, rows)
            // 根层没有容器（例如 [1,2,3]）时「只看容器」会得到空表，此时仍展示根层叶子
            if (rows.isEmpty()) {
                for (c in root.children) rows.add(rowOf(c, 0, false, false))
            }
            outlineAdapter.submit(rows)
            binding.outlineCount.text =
                if (rows.size >= MAX_OUTLINE_ROWS) "${rows.size}+ 项" else "${rows.size} 项"
            return
        }

        outlineSearchJob = lifecycleScope.launch {
            delay(250)
            val index = treeIndex
            val curPath = currentPath
            val selPath = selectedNode?.path
            val rows = withContext(Dispatchers.Default) {
                buildOutlineSearchRows(index, query, curPath, selPath)
            }
            outlineAdapter.submit(rows)
            binding.outlineCount.text =
                if (rows.size >= MAX_OUTLINE_ROWS) "命中 ${rows.size}+" else "命中 ${rows.size}"
        }
    }

    private fun buildOutlineSearchRows(
        index: Map<String, TreeNode>,
        query: String,
        curPath: String,
        selPath: String?
    ): List<OutlineAdapter.Row> {
        val rows = ArrayList<OutlineAdapter.Row>()
        for (node in index.values) {
            if (rows.size >= MAX_OUTLINE_ROWS) break
            val hitKey = node.key?.contains(query, ignoreCase = true) == true
            val hitValue = node.scalar?.contains(query, ignoreCase = true) == true
            if (!hitKey && !hitValue) continue
            rows.add(
                OutlineAdapter.Row(
                    node = node,
                    depth = 0,
                    label = shortPathOf(node.path),
                    hint = OutlineAdapter.hintOf(node),
                    expandable = false,
                    expanded = false,
                    highlight = node.path == curPath || node.path == selPath
                )
            )
        }
        return rows
    }

    private fun collectOutlineRow(
        node: TreeNode,
        depth: Int,
        rows: MutableList<OutlineAdapter.Row>
    ) {
        if (rows.size >= MAX_OUTLINE_ROWS) return
        if (outlineContainersOnly && !node.isContainer) return
        val expandable = node.isContainer && node.children.isNotEmpty()
        val expanded = expandable && outlineExpanded.contains(node.path)
        rows.add(rowOf(node, depth, expandable, expanded))
        if (expanded) for (c in node.children) collectOutlineRow(c, depth + 1, rows)
    }

    private fun rowOf(
        node: TreeNode,
        depth: Int,
        expandable: Boolean,
        expanded: Boolean
    ) = OutlineAdapter.Row(
        node = node,
        depth = depth,
        label = node.indexLabel ?: node.typeLabel,
        hint = OutlineAdapter.hintOf(node),
        expandable = expandable,
        expanded = expanded,
        highlight = node.path == currentPath || node.path == selectedNode?.path
    )

    /** 搜索命中的标签：取路径尾部几段，够区分又不至于太长 */
    private fun shortPathOf(path: String): String {
        val crumbs = TreeNode.breadcrumbOf(path)
        return if (crumbs.size <= 3) crumbs.joinToString(".") else crumbs.takeLast(3).joinToString(".")
    }

    /**
     * 从大纲跳转到某一项。
     *
     * - 代码页打开大纲：留在代码页，滚动到该字段在原文中的位置并闪烁高亮；
     * - 表单页打开大纲：容器跳到该层，最终项跳到所在层并选中。
     */
    private fun pickOutline(node: TreeNode) {
        binding.drawerLayout.closeDrawer(binding.outlinePanel)
        if (mode == Mode.CODE) {
            selectedNode = node
            val range = JsonOffsetLocator.find(binding.editorView.textSequence, node.path)
            if (range != null) {
                binding.editorView.flashRange(range.first, range.last + 1)
            } else {
                toast("未在当前代码中定位到该项")
            }
            return
        }
        switchMode(Mode.FORM)
        if (node.isContainer) {
            currentPath = node.path
            selectedNode = null
            renderLevel()
        } else {
            currentPath = TreeNode.ancestorsOf(node.path).lastOrNull() ?: "$"
            selectedNode = node
            renderLevel()
            val pos = treeAdapter.positionOf(node.path)
            if (pos >= 0) {
                (binding.treeView.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(pos, binding.treeView.height / 3)
                treeAdapter.reveal(node.path)
            }
        }
        outlineExpanded.addAll(TreeNode.ancestorsOf(currentPath))
    }

    /** 大纲面板配色：标题 / 搜索框 / 三个开关 */
    private fun styleOutlineChrome(colors: com.guaner.jsonviewer.theme.ThemeColors) {
        val density = resources.displayMetrics.density
        binding.outlinePanel.setBackgroundColor(colors.surface)
        binding.outlineTitle.setTextColor(colors.text)
        binding.outlineCount.setTextColor(colors.gutterText)
        binding.outlineHintText.setTextColor(colors.gutterText)
        binding.outlineClose.imageTintList = ColorStateList.valueOf(colors.accent)
        binding.outlineSearch.setTextColor(colors.text)
        binding.outlineSearch.setHintTextColor(colors.gutterText)
        binding.outlineSearch.background = GradientDrawable().apply {
            cornerRadius = 19 * density
            setColor(colors.background)
            setStroke((1 * density).toInt().coerceAtLeast(1), colors.gutter)
        }
        styleOutlineAction(binding.outlineOnlyContainers, outlineContainersOnly, colors)
        styleOutlineAction(binding.outlineExpandAll, false, colors)
        styleOutlineAction(binding.outlineCollapseAll, false, colors)
    }

    private fun styleOutlineAction(
        tv: TextView,
        active: Boolean,
        colors: com.guaner.jsonviewer.theme.ThemeColors
    ) {
        val density = resources.displayMetrics.density
        tv.setTextColor(if (active) colors.accent else colors.gutterText)
        tv.background = if (active) GradientDrawable().apply {
            cornerRadius = 13 * density
            setColor(colors.treeSelected)
            setStroke((1 * density).toInt().coerceAtLeast(1), colors.accent)
        } else {
            null
        }
    }

    private fun setupSearch() {
        // 输入防抖：连续打字时不做 O(n) 扫描与跳转，避免每敲一个字就卡一下/视图乱跳
        binding.searchInputCode.addTextChangedListener(afterTextChanged = { scheduleCodeSearch() })
        binding.searchInputCode.setOnEditorActionListener { _, _, _ ->
            codeSearchNext()
            true
        }
        binding.searchNextCode.setOnClickListener { codeSearchNext() }
        binding.searchPrevCode.setOnClickListener { codeSearchPrev() }
        binding.searchCloseCode.setOnClickListener { closeCodeSearch() }

        binding.searchInputForm.addTextChangedListener(afterTextChanged = { runTreeSearch() })
        binding.searchInputForm.setOnEditorActionListener { _, _, _ ->
            treeSearchNext()
            true
        }
        binding.searchNextForm.setOnClickListener { treeSearchNext() }
        binding.searchPrevForm.setOnClickListener { treeSearchPrev() }
        binding.searchCloseForm.setOnClickListener { closeTreeSearch() }

        // 搜索行里的按钮不可获取焦点：点「上一个/下一个/关闭」时不会把焦点从输入框抢走，
        // 输入法保持弹出，可以连续输入
        listOf(
            binding.searchPrevCode, binding.searchNextCode, binding.searchCloseCode,
            binding.searchPrevForm, binding.searchNextForm, binding.searchCloseForm
        ).forEach {
            it.isFocusable = false
            it.isFocusableInTouchMode = false
        }
    }

    private fun setupErrorBanner() {
        binding.errorBanner.setOnClickListener {
            lastError?.let { jumpToError(it) }
        }
    }

    // ---------- 模式切换 ----------

    private fun switchMode(m: Mode) {
        mode = m
        CrashLogger.noteScreen(if (m == Mode.CODE) "主界面·代码" else "主界面·表单")
        binding.codePanel.isVisible = m == Mode.CODE
        binding.formPanel.isVisible = m == Mode.FORM
        val tabIndex = if (m == Mode.CODE) 0 else 1
        if (binding.tabs.selectedTabPosition != tabIndex) {
            binding.tabs.getTabAt(tabIndex)?.select()
        }
        if (m == Mode.FORM) {
            if (lastRoot == null) {
                binding.emptyHint.text = if (lastError != null) {
                    "当前 JSON 有语法错误，请切回「代码」模式查看并修正"
                } else {
                    "粘贴或输入 JSON，或点击底部栏导入"
                }
            }
            renderLevel()
        }
        // 切换页面后状态栏颜色需跟随新页面（代码页→编辑器背景，表单页→面包屑背景）
        applyTheme()
    }

    // ---------- 解析 ----------

    private fun scheduleParse() {
        parseJob?.cancel()
        parseJob = lifecycleScope.launch {
            delay(350)
            // 在主线程取一次文本快照（防抖后只取一次），解析与建索引放后台
            val snapshot = binding.editorView.text
            val parsed = try {
                withContext(Dispatchers.Default) {
                    val r = JsonParser.parse(snapshot)
                    val idx = if (r is ParseResult.Success) {
                        TreeFlattener.buildIndex(r.root)
                    } else {
                        emptyMap()
                    }
                    r to idx
                }
            } catch (e: Throwable) {
                // 解析是纯计算，理论上只会返回 Failure；这里兜住 StackOverflowError /
                // OutOfMemoryError 这类 Error，避免后台线程把整个进程带走
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("解析失败：${e.javaClass.simpleName}")
                return@launch
            }
            applyParseResult(parsed.first, parsed.second)
        }
    }

    /**
     * 安全显示弹窗/面板：Activity 已在销毁流程、或已保存过状态（后台收到回调）时直接放弃。
     * 否则 `show()` 会抛 IllegalStateException / BadTokenException 直接闪退。
     */
    private fun safeShow(fragment: androidx.fragment.app.DialogFragment, tag: String): Boolean {
        if (isFinishing || isDestroyed) return false
        if (supportFragmentManager.isStateSaved) return false
        return try {
            fragment.show(supportFragmentManager, tag)
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun applyParseResult(result: ParseResult, index: Map<String, TreeNode>) {
        when (result) {
            is ParseResult.Success -> {
                // 尽量留在原来的层级：编辑某个值后重新解析，不应该把用户弹回根层
                val keepPath = currentPath
                val keepSelected = selectedNode?.path
                lastRoot = result.root
                treeIndex = index
                lastError = null
                currentPath = if (keepPath == "$" || index.containsKey(keepPath)) keepPath else "$"
                selectedNode = keepSelected?.let { index[it] }
                binding.errorBanner.isVisible = false
                binding.editorView.setError(null)
                renderLevel()
                refreshOutlineIfOpen()
            }
            is ParseResult.Failure -> {
                lastError = result.error
                showErrorBanner(result.error)
                binding.editorView.setError(result.error)
            }
            ParseResult.Empty -> {
                lastRoot = null
                treeIndex = emptyMap()
                lastError = null
                currentPath = "$"
                selectedNode = null
                outlineExpanded.clear()
                binding.errorBanner.isVisible = false
                renderLevel()
                refreshOutlineIfOpen()
            }
        }
    }

    private fun showErrorBanner(err: JsonError) {
        binding.errorBanner.isVisible = true
        binding.errorTitle.text = "第 ${err.line} 行第 ${err.column} 列 · ${err.title}"
        binding.errorDetail.text = buildString {
            append(err.detail)
            err.lineText?.let { append('\n').append(it) }
        }
    }

    private fun jumpToError(err: JsonError) {
        if (mode != Mode.CODE) switchMode(Mode.CODE)
        binding.editorView.jumpTo(err.offset)
    }

    // ---------- 表单树（逐层进入导航） ----------

    /** 渲染 currentPath 这一层的直接子项 */
    private fun renderLevel() {
        val root = lastRoot
        if (root == null) {
            treeAdapter.clear()
            binding.elementCountText.text = ""
            buildBreadcrumb()
            binding.quickJumpRow.removeAllViews()
            binding.quickJumpScroll.isVisible = false
            binding.emptyState.isVisible = true
            return
        }
        binding.emptyState.isVisible = false
        val container = if (currentPath == "$") root else treeIndex[currentPath]
        if (container == null) {
            // 路径失效（如 JSON 被编辑导致节点消失），回退到根层
            currentPath = "$"
            renderLevel()
            return
        }
        val items = container.children
        treeAdapter.submit(items, selectedNode?.path)
        binding.elementCountText.text = "本层 ${items.size} 项"
        buildBreadcrumb()
        buildQuickJump(items)
        binding.treeView.scrollToPosition(0)
    }

    // ---------- 当前层快捷跳转条 ----------

    /**
     * 列出当前层的所有直接子项，作为可点击的 chip：
     * 容器 chip → 进入该层；叶子 chip → 滚动定位并选中。
     */
    private fun buildQuickJump(items: List<TreeNode>) {
        val row = binding.quickJumpRow
        row.removeAllViews()
        if (items.isEmpty() || !AppSettings.showQuickJump) {
            binding.quickJumpScroll.isVisible = false
            return
        }
        binding.quickJumpScroll.isVisible = true
        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        val padH = (10 * density).toInt()
        val padV = (5 * density).toInt()
        val gap = (6 * density).toInt()

        items.take(MAX_QUICK_CHIPS).forEach { node ->
            val tv = TextView(this).apply {
                text = chipLabel(node)
                textSize = 12f
                setPadding(padH, padV, padH, padV)
                isSingleLine = true
                tag = node.path
                setOnClickListener { onQuickJumpChip(node) }
                if (node.isContainer) {
                    // 容器 chip：单击只定位（高亮这一项），长按才进入
                    isLongClickable = true
                    setOnLongClickListener {
                        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        enterNode(node)
                        true
                    }
                }
            }
            applyChipStyle(tv, node, colors)
            row.addView(
                tv,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            )
        }
        if (items.size > MAX_QUICK_CHIPS) {
            val more = TextView(this).apply {
                text = "… 共 ${items.size} 项"
                textSize = 12f
                setPadding(padH, padV, padH, padV)
                isSingleLine = true
                setTextColor(colors.gutterText)
            }
            row.addView(
                more,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = gap }
            )
        }
        binding.quickJumpScroll.post { binding.quickJumpScroll.scrollTo(0, 0) }
    }

    private fun chipLabel(node: TreeNode): String {
        val label = node.indexLabel ?: node.typeLabel
        return if (node.isContainer) "$label ▸" else label
    }

    /** chip 样式：容器/选中用强调色描边突出，其余为普通描边 */
    private fun applyChipStyle(tv: TextView, node: TreeNode, colors: com.guaner.jsonviewer.theme.ThemeColors) {
        val density = resources.displayMetrics.density
        val selected = node.path == selectedNode?.path
        val accent = colors.accent
        val strokeColor = if (node.isContainer || selected) accent else colors.gutterText
        tv.background = GradientDrawable().apply {
            cornerRadius = 16 * density
            setColor(if (selected) colors.treeSelected else colors.surface)
            setStroke((1 * density).toInt().coerceAtLeast(1), strokeColor)
        }
        tv.setTextColor(
            when {
                selected -> accent
                node.isContainer -> accent
                else -> colors.text
            }
        )
    }

    /** 选中状态变化 / 换主题后，刷新 chip 高亮 */
    private fun updateQuickJumpSelection() {
        val colors = ThemeManager.activeColors
        val row = binding.quickJumpRow
        for (i in 0 until row.childCount) {
            val tv = row.getChildAt(i) as? TextView ?: continue
            val path = tv.tag as? String ?: continue
            val node = treeIndex[path] ?: continue
            applyChipStyle(tv, node, colors)
        }
    }

    /**
     * 单击 chip：定位到该项（滚动到列表中该行并高亮），不论容器还是最终项都只做定位；
     * 容器 chip 的「进入」交给长按（见 buildQuickJump）。
     */
    private fun onQuickJumpChip(node: TreeNode) {
        selectedNode = node
        treeAdapter.select(node.path)
        val pos = treeAdapter.positionOf(node.path)
        if (pos >= 0) {
            (binding.treeView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, binding.treeView.height / 3)
            treeAdapter.reveal(node.path)
        }
        updateQuickJumpSelection()
    }

    /** 进入一个容器节点（对象/数组），只显示它的直接子项 */
    private fun enterNode(node: TreeNode) {
        if (!node.isContainer) return
        currentPath = node.path
        selectedNode = null
        renderLevel()
    }

    /** 顶部面包屑：按 currentPath 切成可点击的层级，最后一段为当前层（高亮） */
    private fun buildBreadcrumb() {
        val chips = binding.breadcrumbChips
        chips.removeAllViews()
        val crumbs = TreeNode.breadcrumbOf(currentPath)
        val paths = listOf("$") + TreeNode.ancestorsOf(currentPath)
        // 面包屑颜色跟随运行时主题（原来取的是固定资源色，换肤后不生效）
        val themeColors = ThemeManager.activeColors
        val sepColor = themeColors.gutterText
        val brand = themeColors.accent
        val density = resources.displayMetrics.density
        val padH = (6 * density).toInt()
        crumbs.forEachIndexed { i, label ->
            val isLast = i == crumbs.size - 1
            val tv = TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(padH, padH / 2, padH, padH / 2)
                if (isLast) {
                    setTextColor(brand)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                } else {
                    setTextColor(sepColor)
                    setOnClickListener {
                        currentPath = paths.getOrNull(i) ?: "$"
                        selectedNode = null
                        renderLevel()
                    }
                }
            }
            chips.addView(tv)
            if (!isLast) {
                chips.addView(TextView(this).apply {
                    text = " › "
                    textSize = 12f
                    setTextColor(sepColor)
                })
            }
        }
    }

    private fun selectNode(node: TreeNode) {
        selectedNode = node
        treeAdapter.select(node.path)
        updateQuickJumpSelection()
    }

    private fun showNodeMenu(node: TreeNode) {
        selectNode(node)
        try {
            val popup = PopupMenu(this, binding.breadcrumbRow)
            buildNodeMenu(node, popup)
            popup.show()
        } catch (_: Throwable) {
            // 窗口令牌失效（Activity 正在销毁）时忽略，不能让菜单把进程带走
        }
    }
    /** 填充节点操作菜单（右上角三点共用） */
    private fun buildNodeMenu(node: TreeNode, popup: PopupMenu) {
        popup.menu.add(0, MENU_VIEW_VALUE, 0, if (node.isContainer) "查看子树 JSON" else "查看完整值")
        popup.menu.add(0, MENU_COPY_PATH, 1, "复制 JSONPath")
        popup.menu.add(0, MENU_COPY_VALUE, 2, if (node.isContainer) "复制子树 JSON" else "复制值")
        if (node.isContainer) popup.menu.add(0, MENU_ENTER, 3, "进入此层")
        popup.menu.add(0, MENU_GO_UP, 4, "返回上一层")
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_VIEW_VALUE -> openValueSheet(node)
                MENU_COPY_PATH -> copyToClipboard(node.path, "JSONPath")
                MENU_COPY_VALUE -> copyToClipboard(
                    if (node.isContainer) JsonSerializer.compactOf(node) else node.scalar.orEmpty(),
                    "值"
                )
                MENU_ENTER -> enterNode(node)
                MENU_GO_UP -> goUp()
            }
            true
        }
    }

    /**
     * 打开值详情弹窗：叶子展示完整值，容器展示子树紧凑 JSON。
     * 子树序列化可能很重（大 JSON），放后台线程做，防止 ANR。
     */
    private fun openValueSheet(node: TreeNode) {
        selectNode(node)
        if (!node.isContainer) {
            showValueSheet(node, node.key ?: node.typeLabel, node.scalar.orEmpty())
            return
        }
        toast("正在生成子树 JSON…")
        lifecycleScope.launch {
            val text = try {
                withContext(Dispatchers.Default) { JsonSerializer.compactOf(node) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 深嵌套/超大子树可能抛 StackOverflowError 或 OOM，属于 Error 而非 Exception，
                // 必须一起兜住，否则这一下就是闪退
                toast("生成子树 JSON 失败：${e.javaClass.simpleName}")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            showValueSheet(node, node.key ?: node.typeLabel, text)
        }
    }

    private fun showValueSheet(node: TreeNode, title: String, value: String) {
        val sheet = ValueSheet.newInstance(title, node.path, value, node.type)
        sheet.onCopy = { text, label -> copyToClipboard(text, label) }
        sheet.onSave = { raw -> applyValueEdit(node.path, raw) }
        safeShow(sheet, ValueSheet.TAG)
    }

    /**
     * 把值弹窗里编辑好的 JSON 片段替换回原文的对应位置。
     *
     * 用 JsonOffsetLocator 定位「值」的字符区间（对象成员是值而不是键名），
     * 只替换这一段，周围缩进与格式原样保留；替换前对整份文本做一次解析校验，
     * 不通过就完全不改（宁可让用户重改，也不要把 JSON 弄坏）。
     */
    private fun applyValueEdit(path: String, raw: String): Boolean {
        val text = binding.editorView.text
        val range = JsonOffsetLocator.findValueRange(text, path)
        if (range == null) {
            toast("定位不到该字段，文本可能已变化，请重新打开再试")
            return false
        }
        val start = range.first
        val end = range.last + 1
        if (start < 0 || end > text.length || start >= end) return false
        val sb = StringBuilder(text.length - (end - start) + raw.length)
        sb.append(text, 0, start).append(raw).append(text, end, text.length)
        val newText = sb.toString()
        if (JsonParser.parse(newText) !is ParseResult.Success) {
            toast("修改后整体 JSON 不合法，已取消（原内容未变）")
            return false
        }
        binding.editorView.replaceRange(start, end, raw)
        // 文本变了：立即重新解析，表单/代码两个页面看到的是同一份内容
        scheduleParse()
        return true
    }

    /** JSONPath 命中后在表单中定位：切到命中节点的父层 -> 选中并闪烁 */
    private fun locateInTree(rawPath: String) {
        val normalized = TreeNode.normalizePath(rawPath)
        val node = treeIndex[normalized]
        if (node == null) {
            toast("未在表单中找到对应节点：$normalized")
            return
        }
        val parentPath = TreeNode.ancestorsOf(normalized).lastOrNull() ?: "$"
        currentPath = parentPath
        selectedNode = node
        treeAdapter.setMatched(emptySet())
        renderLevel()
        val pos = treeAdapter.positionOf(normalized)
        if (pos >= 0) {
            (binding.treeView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, binding.treeView.height / 3)
            treeAdapter.reveal(normalized)
        }
    }

    // ---------- 代码模式搜索 ----------

    private var codeSearchJob: Job? = null

    /** 搜索输入防抖：连续打字只跑最后一次，避免每敲一个字就全量扫描 + 视图跳动 */
    private fun scheduleCodeSearch() {
        codeSearchJob?.cancel()
        codeSearchJob = lifecycleScope.launch {
            delay(180)
            runCodeSearch()
        }
    }

    private fun runCodeSearch() {
        val q = binding.searchInputCode.text?.toString().orEmpty()
        if (q.isEmpty()) {
            codeMatches = emptyList()
            codeMatchIndex = -1
            binding.searchCountCode.text = "0/0"
            binding.editorView.setSearchMatches(emptyList(), -1)
            return
        }
        // 用 CharSequence 直接扫描，避免每次按键把 200KB 文本 toString 全量拷贝
        val text = binding.editorView.textSequence
        val matches = ArrayList<IntRange>()
        var idx = text.indexOf(q, ignoreCase = true)
        while (idx >= 0 && matches.size < MAX_CODE_MATCHES) {
            matches.add(idx until (idx + q.length))
            idx = text.indexOf(q, idx + q.length, ignoreCase = true)
        }
        codeMatches = matches
        codeMatchIndex = if (matches.isEmpty()) -1 else 0
        updateCodeSearchLabel()
        binding.editorView.setSearchMatches(matches, codeMatchIndex)
        // takeFocus=false：只滚动定位，不把焦点从搜索框抢走，否则输入一个字就断
        if (codeMatchIndex >= 0) binding.editorView.jumpTo(matches[0].first, takeFocus = false)
    }

    private fun updateCodeSearchLabel() {
        binding.searchCountCode.text = when {
            codeMatches.isEmpty() -> "0/0"
            codeMatches.size >= MAX_CODE_MATCHES -> "${codeMatchIndex + 1}/5000+"
            else -> "${codeMatchIndex + 1}/${codeMatches.size}"
        }
    }

    private fun codeSearchNext() {
        if (codeMatches.isEmpty()) return
        codeMatchIndex = (codeMatchIndex + 1) % codeMatches.size
        updateCodeSearchLabel()
        binding.editorView.setSearchMatches(codeMatches, codeMatchIndex)
        binding.editorView.jumpTo(codeMatches[codeMatchIndex].first, takeFocus = false)
    }

    private fun codeSearchPrev() {
        if (codeMatches.isEmpty()) return
        codeMatchIndex = (codeMatchIndex - 1 + codeMatches.size) % codeMatches.size
        updateCodeSearchLabel()
        binding.editorView.setSearchMatches(codeMatches, codeMatchIndex)
        binding.editorView.jumpTo(codeMatches[codeMatchIndex].first, takeFocus = false)
    }

    private fun closeCodeSearch() {
        binding.searchInputCode.setText("")
        binding.searchRowCode.isVisible = false
        binding.editorView.setSearchMatches(emptyList(), -1)
    }

    // ---------- 表单模式搜索（定位到命中节点所在层） ----------

    private fun runTreeSearch() {
        val q = binding.searchInputForm.text?.toString().orEmpty()
        if (q.isEmpty()) {
            treeMatches = emptyList()
            treeMatchIndex = -1
            treeAdapter.setMatched(emptySet())
            binding.searchCountForm.text = "0/0"
            renderLevel()
            return
        }
        val root = lastRoot ?: run {
            treeMatches = emptyList()
            treeMatchIndex = -1
            binding.searchCountForm.text = "0/0"
            return
        }
        // 大 JSON 的全树扫描放后台 + 防抖：主线程逐节点 contains 必 ANR
        treeSearchJob?.cancel()
        treeSearchJob = lifecycleScope.launch {
            delay(300)
            val matchResult = withContext(Dispatchers.Default) {
                TreeFlattener.collectMatches(root, q)
            }
            treeMatches = matchResult.matchedPaths
            treeMatchIndex = if (treeMatches.isEmpty()) -1 else 0
            if (treeMatches.isEmpty()) {
                withContext(Dispatchers.Main) {
                    treeAdapter.setMatched(emptySet())
                    binding.searchCountForm.text = "0/0"
                }
            } else {
                withContext(Dispatchers.Main) { jumpToTreeMatch() }
            }
        }
    }

    /** 跳到当前匹配项：进入其所在父层，并选中、高亮该节点 */
    private fun jumpToTreeMatch() {
        val path = treeMatches.getOrNull(treeMatchIndex) ?: return
        val parentPath = TreeNode.ancestorsOf(path).lastOrNull() ?: "$"
        val node = treeIndex[path]
        currentPath = parentPath
        selectedNode = node
        treeAdapter.setMatched(treeMatches.toSet())
        binding.searchCountForm.text = "${treeMatchIndex + 1}/${treeMatches.size}"
        renderLevel()
        val pos = treeAdapter.positionOf(path)
        if (pos >= 0) {
            (binding.treeView.layoutManager as? LinearLayoutManager)
                ?.scrollToPositionWithOffset(pos, binding.treeView.height / 3)
            treeAdapter.reveal(path)
        }
    }

    private fun treeSearchNext() {
        if (treeMatches.isEmpty()) return
        treeMatchIndex = (treeMatchIndex + 1) % treeMatches.size
        jumpToTreeMatch()
    }

    private fun treeSearchPrev() {
        if (treeMatches.isEmpty()) return
        treeMatchIndex = (treeMatchIndex - 1 + treeMatches.size) % treeMatches.size
        jumpToTreeMatch()
    }

    private fun closeTreeSearch() {
        binding.searchInputForm.setText("")
        binding.searchRowForm.isVisible = false
        treeMatches = emptyList()
        treeMatchIndex = -1
        treeAdapter.setMatched(emptySet())
        renderLevel()
    }

    // ---------- JSONPath ----------

    private fun openJsonPathSheet() {
        if (lastRoot == null) {
            toast(if (lastError != null) "当前 JSON 有语法错误，请先修正" else "请先输入 JSON 内容")
            lastError?.let { jumpToError(it) }
            return
        }
        lifecycleScope.launch {
            // 文本快照在主线程取（Editable 不是线程安全的），后续处理放后台
            val json = binding.editorView.text
            val sheet = JsonPathSheet.newInstance(json)
            sheet.onLocate = { normalized ->
                switchMode(Mode.FORM)
                locateInTree(normalized)
            }
            sheet.onCopyAll = { text -> copyToClipboard(text, "JSONPath 结果") }
            safeShow(sheet, JsonPathSheet.TAG)
        }
    }

    // ---------- 网络请求 ----------

    private fun openNetworkSheet() {
        val sheet = NetworkSheet()
        sheet.onImport = { body -> setJsonText(body, "已导入响应体") }
        safeShow(sheet, NetworkSheet.TAG)
    }

    // ---------- 底部操作栏 ----------

    /** key -> 已建好的按钮；「more」是固定项，其余来自 [BottomActionCatalog] */
    private val bottomButtons = LinkedHashMap<String, ImageButton>()

    /**
     * 底部栏：按设置里的顺序/显隐渲染。
     *
     * 按钮只创建一次（保证 tint、点击回调不变），之后靠 addView 重排；
     * 数量超出屏幕宽度时由外层 HorizontalScrollView 横向滚动。
     */
    private fun setupBottomBar() {
        buildBottomButtons()
        // 「更多」固定在布局里、始终在最右端，不参与排序/隐藏
        binding.btnMore.setOnClickListener { showMoreMenu(it) }
        binding.btnMore.setOnLongClickListener {
            toast(BottomActionCatalog.longPressHint(BottomActionCatalog.MORE))
            true
        }
        applyBottomOrder()
    }

    private fun buildBottomButtons() {
        val density = resources.displayMetrics.density
        val out = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        val ripple = if (out.resourceId != 0) ContextCompat.getDrawable(this, out.resourceId) else null

        BottomActionCatalog.CONFIG.forEach { action ->
            val btn = ImageButton(this).apply {
                setImageResource(action.icon)
                contentDescription = action.label
                scaleType = android.widget.ImageView.ScaleType.CENTER
                // 每个按钮独立一份 ripple，共享同一个 drawable 会让水波纹状态互相串
                background = ripple?.constantState?.newDrawable()?.mutate()
                imageTintList = ColorStateList.valueOf(ThemeManager.activeColors.accent)
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setOnClickListener { runBottomAction(action.key) }
                // 只有图标难免认不出，长按显示「短名 · 功能说明」
                setOnLongClickListener {
                    toast(BottomActionCatalog.longPressHint(action))
                    true
                }
            }
            bottomButtons[action.key] = btn
        }
    }

    /** 按设置重排 / 重显底部按钮：先清空容器，再按 visibleOrder 追加（「更多」在布局里固定不动） */
    private fun applyBottomOrder() {
        val container = binding.bottomActions
        val density = resources.displayMetrics.density
        val w = (46 * density).toInt()
        val h = (44 * density).toInt()
        container.removeAllViews()
        AppSettings.visibleOrder.forEach { key ->
            bottomButtons[key]?.let {
                it.tag = key
                container.addView(it, LinearLayout.LayoutParams(w, h))
            }
        }
        // 之前若横向滚动过，重排后停留位置会很怪；统一回到最左端
        binding.bottomActionsScroll.post { binding.bottomActionsScroll.scrollTo(0, 0) }
    }

    /** 执行一个底部动作（底部栏点击与「更多」菜单共用） */
    private fun runBottomAction(key: String) {
        when (key) {
            "outline" -> openOutline()
            "search" -> toggleSearchRow()
            "jsonpath" -> openJsonPathSheet()
            "paste" -> pasteFromClipboard()
            "top" -> scrollToEdge(top = true)
            "bottom" -> scrollToEdge(top = false)
            "save" -> saveToFile()
            "share" -> shareFile()
            "beautify" -> transformJson("美化") { JsonSerializer.pretty(it) }
            "minify" -> transformJson("压缩") { JsonSerializer.minify(it) }
            "escape" -> showEscapeDialog()
            "import" -> pickFile()
            "history" -> showHistoryDialog()
            "network" -> openNetworkSheet()
            "clear" -> confirmClear()
        }
    }

    // ---------- 导入历史 ----------

    /** 「历史」按钮：加载、长按多选、单条删除、清空（与链接历史共用同一个弹窗） */
    private fun showHistoryDialog() {
        HistoryDialog.show(
            this,
            title = "导入历史",
            store = JsonHistory.store,
            current = binding.editorView.text?.toString(),
            emptyHint = "还没有导入历史",
            previewLength = 30
        ) { text, number ->
            setJsonText(text, "已加载历史 #$number")
        }
    }

    /** 置顶 / 置底：代码页滚编辑器，表单页滚当前层列表 */
    private fun scrollToEdge(top: Boolean) {
        if (mode == Mode.CODE) {
            if (top) binding.editorView.scrollToTop() else binding.editorView.scrollToBottom()
            return
        }
        val count = treeAdapter.itemCount
        if (count <= 0) return
        val target = if (top) 0 else count - 1
        (binding.treeView.layoutManager as? LinearLayoutManager)
            ?.smoothScrollToPosition(binding.treeView, null, target)
            ?: binding.treeView.scrollToPosition(target)
    }

    // ---------- 保存到文件 / 分享 ----------

    /** SAF 新建文档：用户自己选目录与文件名，不需要任何存储权限 */
    private val createJsonDocument = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeTextToUri(uri)
    }

    private fun saveToFile() {
        if (binding.editorView.text.isBlank()) {
            toast("没有可保存的内容")
            return
        }
        val name = "json_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
            .format(java.util.Date()) + ".json"
        try {
            createJsonDocument.launch(name)
        } catch (e: Exception) {
            toast("无法打开文件选择器：${e.message}")
        }
    }

    private fun writeTextToUri(uri: Uri) {
        val text = binding.editorView.text
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openOutputStream(uri, "w")?.use { out ->
                        val data = text.toByteArray(Charsets.UTF_8)
                        out.write(data)
                        data.size
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (bytes == null) {
                toast("保存失败，请换一个位置重试")
            } else {
                toast("已保存 ${JsonLimits.formatBytes(bytes.toLong())}")
            }
        }
    }

    /** 分享：写一份到应用缓存，再用 FileProvider 授权给目标 App（不需要存储权限） */
    private fun shareFile() {
        val text = binding.editorView.text
        if (text.isBlank()) {
            toast("没有可分享的内容")
            return
        }
        lifecycleScope.launch {
            val uri = withContext(Dispatchers.IO) {
                try {
                    val dir = java.io.File(cacheDir, "share").apply { mkdirs() }
                    // 只保留最新一份，避免缓存目录越积越多
                    dir.listFiles()?.forEach { it.delete() }
                    val file = java.io.File(dir, "json_${System.currentTimeMillis()}.json")
                    file.writeText(text)
                    FileProvider.getUriForFile(this@MainActivity, "$packageName.fileprovider", file)
                } catch (e: Exception) {
                    null
                }
            }
            if (uri == null) {
                toast("生成分享文件失败")
                return@launch
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "JSON 文件")
                // 小文本额外带一份纯文本，个别不支持收文件的 App 也能分享；
                // 大文本只走文件，避免 EXTRA_TEXT 超过 Binder 限制导致崩溃
                if (text.length < 100_000) putExtra(Intent.EXTRA_TEXT, text)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, "分享 JSON"))
            } catch (e: Exception) {
                toast("没有可用的分享应用")
            }
        }
    }

    /**
     * 底部「更多」菜单：
     * - 只列出**没在底部栏显示**的动作，按文字长度从短到长排列（短标签向上，扫读更快）；
     * - 「设置 / 关于 / 加载示例」固定排在最后三项，不参与排序。
     */
    private class MenuItem(val key: String?, val fixedId: Int, val text: String)

    /**
     * 「更多」菜单。
     *
     * 不用 PopupMenu：它的背景来自主题资源（运行时换肤不生效，深色主题下还是白底黑字）。
     * 改用 ListPopupWindow，底色、描边、文字颜色全部按当前主题现场画。
     */
    private fun showMoreMenu(anchor: View) {
        val visible = AppSettings.visibleOrder.toSet()
        // 隐藏的动作：按目录里的固定顺序（美化 → 压缩 → 转义 → 导入 → 清空），两字名。
        // 设置里标成「隐藏」的项两边都不显示，这里要一起排除
        val gone = AppSettings.menuHiddenKeys
        val items = ArrayList<MenuItem>()
        BottomActionCatalog.CONFIG.filter { it.key !in visible && it.key !in gone }
            .forEach { items.add(MenuItem(it.key, 0, it.label)) }
        // 固定三项，永远在菜单底部；「关于」「示例」可以在设置里关掉，「设置」不可关
        // （它是回到设置页的唯一入口，藏起来就再也改不回来了）
        items.add(MenuItem(null, MORE_THEME, "设置"))
        if (AppSettings.isMenuVisible("about")) items.add(MenuItem(null, MORE_ABOUT, "关于"))
        if (AppSettings.isMenuVisible("sample")) items.add(MenuItem(null, MORE_SAMPLE, "示例"))

        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        val adapter = object : ArrayAdapter<MenuItem>(this, android.R.layout.simple_list_item_1, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = convertView as? TextView ?: TextView(context).apply {
                    textSize = 15f
                    setPadding(
                        (16 * density).toInt(), (11 * density).toInt(),
                        (16 * density).toInt(), (11 * density).toInt()
                    )
                }
                tv.text = items[position].text
                tv.setTextColor(colors.text)
                return tv
            }
        }

        val popup = ListPopupWindow(this).apply {
            anchorView = anchor
            setAdapter(adapter)
            // 底色跟主题：圆角卡片 + 细描边，和主界面观感一致
            setBackgroundDrawable(
                GradientDrawable().apply {
                    cornerRadius = 12 * density
                    setColor(colors.surface)
                    setStroke(
                        (1 * density).toInt().coerceAtLeast(1),
                        colors.gutter
                    )
                }
            )
            width = (140 * density).toInt()
            isModal = true
            setOnItemClickListener { _, _, position, _ ->
                val item = items[position]
                dismiss()
                when {
                    item.key != null -> runBottomAction(item.key)
                    item.fixedId == MORE_THEME ->
                        startActivity(Intent(this@MainActivity, ThemeSettingsActivity::class.java))
                    item.fixedId == MORE_ABOUT -> showAbout()
                    item.fixedId == MORE_SAMPLE -> loadSample()
                }
            }
        }
        try {
            popup.show()
        } catch (_: Throwable) {
        }
    }

    private fun toggleSearchRow() {
        if (mode == Mode.CODE) {
            val show = !binding.searchRowCode.isVisible
            binding.searchRowCode.isVisible = show
            if (show) {
                binding.searchInputCode.requestFocus()
                showKeyboard(binding.searchInputCode)
            }
        } else {
            val show = !binding.searchRowForm.isVisible
            binding.searchRowForm.isVisible = show
            if (show) {
                binding.searchInputForm.requestFocus()
                showKeyboard(binding.searchInputForm)
            }
        }
    }

    /** 转义互转入口：中文 ↔ \uXXXX 两个方向，让用户自己选 */
    private fun showEscapeDialog() {
        if (binding.editorView.text.isBlank()) {
            toast("没有可处理的内容")
            return
        }
        ThemedDialog.options(
            this, "Unicode 转换",
            listOf("中文 → \\uXXXX（转义）", "\\uXXXX → 中文（还原）")
        ) { which ->
            if (which == 0) {
                transformText("转义") { JsonSerializer.escapeUnicodeText(it) }
            } else {
                transformText("还原") { JsonSerializer.unescapeUnicode(it) }
            }
        }
    }

    /**
     * 对原文做纯文本变换（转义互转用）。
     *
     * 不走 transformJson：那条路会先解析再重新序列化，格式会被重排；
     * 转义应该只动字符本身，缩进和换行保持原样。
     */
    private fun transformText(label: String, transform: (String) -> String) {
        val text = binding.editorView.text
        if (text.isBlank()) {
            toast("没有可处理的内容")
            return
        }
        lifecycleScope.launch {
            // 几十万字符的逐字扫描放后台，避免卡主线程
            val result = withContext(Dispatchers.Default) { transform(text) }
            if (result == text) {
                toast("内容没有变化")
                return@launch
            }
            binding.editorView.setTextProgrammatic(result)
            toast("已$label")
        }
    }

    private fun transformJson(label: String, transform: (TreeNode) -> String): Boolean {
        val root = lastRoot
        if (root == null) {
            toast("当前 JSON 有错误，无法$label")
            lastError?.let { jumpToError(it) }
            return true
        }
        // 大 JSON 的全树序列化必须放后台：主线程跑几十万节点必然 ANR
        lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.Default) { transform(root) }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                // 深嵌套可能抛 StackOverflowError，属于 Error，catch Exception 兜不住
                toast("$label 失败：${e.javaClass.simpleName}")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            binding.editorView.setTextProgrammatic(result)
            toast("已$label")
        }
        return true
    }

    private fun confirmClear() {
        ThemedDialog.confirm(
            this, "清空输入", "确定要清空当前编辑器内容吗？", positive = "清空", danger = true
        ) {
            binding.editorView.setTextProgrammatic("")
            lastRoot = null
            lastError = null
            selectedNode = null
            currentPath = "$"
            treeIndex = emptyMap()
            treeMatches = emptyList()
            treeMatchIndex = -1
            binding.errorBanner.isVisible = false
            renderLevel()
        }
    }

    private fun showAbout() {
        val version = try {
            packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
        } catch (e: Exception) {
            ""
        }
        ThemedDialog.message(
            this, "关于 JSON 查看器",
                "版本 $version · com.guaner.jsonviewer\n\n" +
                    "本地优先的 JSON 查看与编辑工具，除网络请求外完全离线运行，仅申请网络权限。\n\n" +
                    "· 代码 / 表单双模式，表单逐层进入，面包屑可逐段回退\n" +
                    "· JSONPath 查询、双模式搜索、精确到行列的错误定位\n" +
                    "· 值详情与就地编辑，可切换值类型，大文本用临时编辑器\n" +
                    "· 美化 / 压缩 / Unicode 转义\n" +
                    "· 网络请求：GET/POST、自定义请求头、UA / Referer / Content-Type 策略、粘贴抓包格式自动拆分、编码与 URL 转义\n" +
                    "· 主题配色、字号、底部按钮三态（底栏 / 更多 / 隐藏）\n" +
                    "· 崩溃日志可查看、复制、分享，也可在设置里关闭\n\n" +
                    "开源地址：github.com/zmn001125/JsonViewer\n" +
                    "许可证：GNU GPL v3.0（修改或分发须同样开源）\n" +
                    "解析 Jackson Core · 查询 Jayway JsonPath"
        )
    }

    // ---------- 文件导入 / 分享接收 ----------

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/json",
                    "text/plain",
                    "text/x-json",
                    "application/octet-stream"
                )
            )
        }
        try {
            openDocumentLauncher.launch(intent)
        } catch (e: Exception) {
            toast("无法打开文件选择器：${e.message}")
        }
    }

    private fun readTextFromUri(uri: Uri, onDone: (String) -> Unit) {
        lifecycleScope.launch {
            val (text, warn) = withContext(Dispatchers.IO) { readUriText(uri) }
            if (text.isNullOrBlank()) {
                toast(warn ?: "文件为空或读取失败")
            } else {
                onDone(text)
                warn?.let { toast(it) }
            }
        }
    }

    /**
     * 读取 URI 文本，带三重防护：
     * 1) 先用文件描述符查实际大小，超过上限直接拒绝，不读入内存；
     * 2) 流式读取，最多读 MAX_READ_BYTES，超出即截断（statSize 为 -1 时的兜底）；
     * 3) 按字符上限截断，保证编辑器与后续解析的内存可控。
     * 返回 (文本, 警告信息)；文本为 null 表示失败。
     */
    private fun readUriText(uri: Uri): Pair<String?, String?> {
        return try {
            // 1) 大小预检
            val size = try {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
            } catch (e: Exception) {
                -1L
            }
            if (size > JsonLimits.MAX_FILE_BYTES) {
                return null to "文件过大（${JsonLimits.formatBytes(size)}），超过 ${JsonLimits.formatBytes(JsonLimits.MAX_FILE_BYTES)} 上限"
            }

            // 2) 流式读取 + 字节截断
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    buf.write(chunk, 0, n)
                    if (total >= JsonLimits.MAX_READ_BYTES) break
                }
                buf.toByteArray()
            } ?: return null to "无法打开文件"

            val decoded = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")

            // 字节读满上限说明 statSize 不可信（-1）且内容超限，截断并提示
            if (bytes.size >= JsonLimits.MAX_READ_BYTES) {
                return decoded.take(JsonLimits.MAX_INPUT_CHARS) to
                    "文件内容超出加载上限，已截断到前 ${JsonLimits.formatChars(JsonLimits.MAX_INPUT_CHARS)}"
            }

            // 3) 字符数截断
            if (decoded.length > JsonLimits.MAX_INPUT_CHARS) {
                val cut = decoded.take(JsonLimits.MAX_INPUT_CHARS)
                cut to "内容过大，已截断到前 ${JsonLimits.formatChars(JsonLimits.MAX_INPUT_CHARS)}"
            } else {
                decoded to null
            }
        } catch (e: Exception) {
            null to "读取失败：${e.message}"
        }
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> {
                // 任何应用里选中文本 → 系统菜单「查看 JSON」→ 导入到本应用
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
                if (!text.isNullOrBlank()) {
                    setJsonText(text, "已导入所选文本")
                } else {
                    toast("所选文本为空")
                }
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data
                if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
                    readTextFromUri(uri) { setJsonText(it, "已打开文件") }
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_STREAM, Uri::class.java
                )
                if (streamUri != null) {
                    readTextFromUri(streamUri) { setJsonText(it, "已接收分享的 JSON") }
                } else {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    if (!text.isNullOrBlank()) setJsonText(text, "已接收分享的 JSON")
                }
                // 关键：读完后立刻移除大 extras。
                // 否则 Activity 退到后台时，系统会把 intent extras 序列化进系统进程，
                // 超过 Binder ~1MB 限制会抛 TransactionTooLargeException 直接杀进程（表现为闪退）。
                intent.removeExtra(Intent.EXTRA_TEXT)
                intent.removeExtra(Intent.EXTRA_STREAM)
                setIntent(intent)
            }
        }
    }

    private fun setJsonText(text: String, message: String) {
        if (text.isBlank()) {
            toast("内容为空")
            return
        }
        // 换了一整份内容：回到根层、清掉旧的展开状态
        currentPath = "$"
        selectedNode = null
        outlineExpanded.clear()
        lifecycleScope.launch {
            // 提取可能涉及多次校验，放后台做，避免粘贴大文本时卡住主线程
            val prepared = try {
                withContext(Dispatchers.Default) {
                    val text2 = prepareInput(text, message)
                    text2 to JsonValueValidator.isValid(text2.first)
                }
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                toast("导入失败：${e.javaClass.simpleName}")
                return@launch
            }
            if (isFinishing || isDestroyed) return@launch
            binding.editorView.setTextProgrammatic(prepared.first.first)
            // 有效 JSON 才入历史；重复内容自动置顶不重复存
            if (prepared.second) JsonHistory.add(prepared.first.first)
            toast(prepared.first.second)
        }
    }

    /**
     * 纯计算：从混杂文本中提取「第一个可完整解析的 JSON」，并做超限截断。
     * 例如 `[00:11.566] {"code":"200",...}` 会丢掉时间戳前缀，只保留 JSON 本体。
     * 返回 (最终文本, 提示语)。
     */
    private fun prepareInput(text: String, message: String): Pair<String, String> {
        // 设置里关闭过滤：原样导入，不做任何提取
        if (!AppSettings.filterImportedText) {
            return if (text.length > JsonLimits.MAX_INPUT_CHARS) {
                text.take(JsonLimits.MAX_INPUT_CHARS) to
                    "内容过大，已截断到前 ${JsonLimits.formatChars(JsonLimits.MAX_INPUT_CHARS)}"
            } else {
                text to message
            }
        }
        val found = JsonExtractor.findFirstJson(text)
        val base: String
        var note = message
        if (found == null) {
            base = text
            note = "未找到可解析的 JSON 数据，已保留原文本"
        } else {
            base = found.text
            if (found.start > 0 || found.end < text.length) {
                note = "$message（已自动提取第一个有效 JSON，忽略其余内容）"
            }
        }
        return if (base.length > JsonLimits.MAX_INPUT_CHARS) {
            base.take(JsonLimits.MAX_INPUT_CHARS) to
                "内容过大，已截断到前 ${JsonLimits.formatChars(JsonLimits.MAX_INPUT_CHARS)}"
        } else {
            base to note
        }
    }

    private fun loadSample() {
        setJsonText(SAMPLE_JSON, "已加载示例")
    }

    /** FAB「粘贴代码」：读取系统剪贴板文本填入编辑器，粘贴后切到代码模式方便确认/编辑 */
    private fun pasteFromClipboard() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = cm?.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast("剪贴板为空")
            return
        }
        val text = clip.getItemAt(0).text?.toString()
        if (text.isNullOrBlank()) {
            toast("剪贴板内容不是文本")
            return
        }
        setJsonText(text, "已从剪贴板粘贴")
        switchMode(Mode.CODE)
    }

    // ---------- 工具 ----------

    private fun copyToClipboard(text: String, label: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        val preview = if (text.length > 50) text.take(50) + "…（${text.length} 字符）" else text
        toast("已复制：$preview")
    }

    private fun showKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toast(msg: String) {
        // Activity 已销毁时 Toast 在某些 ROM 上会抛 BadTokenException，这里静默兜住
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val MENU_VIEW_VALUE = 10
        private const val MENU_COPY_PATH = 1
        private const val MENU_COPY_VALUE = 2
        private const val MENU_ENTER = 3
        private const val MENU_GO_UP = 4

        /** 「更多」菜单里动态项（未显示在底部栏的动作）的 id 基址 */
        private const val MENU_HIDDEN_BASE = 100

        private const val MORE_THEME = 37
        private const val MORE_SAMPLE = 33
        private const val MORE_ABOUT = 35

        /** 代码模式搜索命中上限：压缩 JSON 搜逗号类关键词可能命中几十万次，全量 span 会拖垮界面 */
        private const val MAX_CODE_MATCHES = 5_000

        /** 结构大纲最多渲染的行数：极端 JSON 下防止一次性生成几十万行 */
        private const val MAX_OUTLINE_ROWS = 3_000

        /** 当前层快捷跳转条最多渲染的 chip 数 */
        private const val MAX_QUICK_CHIPS = 60

        private val SAMPLE_JSON = """
            {
              "store": {
                "book": [
                  { "category": "reference", "author": "Nigel Rees", "title": "Sayings of the Century", "price": 8.95 },
                  { "category": "fiction", "author": "Evelyn Waugh", "title": "Sword of Honour", "price": 12.99 },
                  { "category": "fiction", "author": "Herman Melville", "title": "Moby Dick", "isbn": "0-553-21311-3", "price": 8.99 },
                  { "category": "fiction", "author": "J. R. R. Tolkien", "title": "The Lord of the Rings", "isbn": "0-395-19395-8", "price": 22.99 }
                ],
                "bicycle": { "color": "red", "price": 19.95 }
              },
              "expensive": 10
            }
        """.trimIndent()
    }
}
