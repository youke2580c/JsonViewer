package com.guaner.jsonviewer.ui

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.tabs.TabLayout
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.crash.CrashLogger
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.editor.JsonEditorTheme
import com.guaner.jsonviewer.data.JsonHistory
import com.guaner.jsonviewer.data.SessionStore
import com.guaner.jsonviewer.data.UrlHistory
import com.guaner.jsonviewer.databinding.ActivityThemeSettingsBinding
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.theme.SavedTheme
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.theme.ThemeManager
import com.guaner.jsonviewer.theme.ThemePresets
import com.guaner.jsonviewer.ui.BottomActionCatalog

/**
 * 主题管理页：内置主题切换 + 主色调/背景色（hex 输入 + HSV 滑块）+ 字体字号 + 编辑器开关，
 * 所有改动即时写入 [ThemeManager] 并在「实时预览」区反映（语法高亮 + 背景 + 字体）。
 */
class ThemeSettingsActivity : AppCompatActivity() {

    companion object {
        private const val KEY_TAB = "settings_tab"
        private const val TAB_GENERAL = 0
        private const val TAB_THEME = 1
    }

    private lateinit var binding: ActivityThemeSettingsBinding

    /** 实时预览样例 JSON */
    private val SAMPLE = """
        {
          "store": {
            "book": [
              { "category": "fiction", "author": "Tolkien", "price": 22.99 },
              { "category": "reference", "author": "Nigel Rees", "price": 8.95 }
            ],
            "bicycle": { "color": "red", "price": 19.95 }
          },
          "expensive": 10,
          "available": true,
          "note": null
        }
    """.trimIndent()

    /** 防止布局同步时触发输入框/滑块回调形成死循环 */
    private var suppress = false

    /** 顶部栏返回箭头的原始 drawable（用于换肤时反复新建副本着色） */
    private var navIconBase: android.graphics.drawable.Drawable? = null

    /** 当前应用的自定义主题 id（手动改动配色后清空，用于高亮） */
    private var appliedSavedId: String? = null

    private lateinit var savedAdapter: SavedAdapter

    /** 滑块拖动中：不刷新列表（避免每帧 rebind 造成卡顿） */
    private var draggingSlider = false

    /** 底部按钮排序弹窗的临时状态 */
    private var reorderOrder: MutableList<String> = ArrayList()
    private var reorderHidden: MutableSet<String> = HashSet()

    /** 既不在底部栏、也不在「更多」里的动作（三态里的「隐藏」） */
    private var reorderHiddenInMenu: MutableSet<String> = HashSet()

    /** 「更多」菜单里被关掉的固定项（关于 / 示例），同样先改副本、点保存才落盘 */
    private var reorderMenuHidden: MutableSet<String> = HashSet()
    private var reorderContainer: android.widget.LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ThemeManager.init(this)
        AppSettings.init(this)
        CrashLogger.noteScreen("设置页")
        binding = ActivityThemeSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.toolbar.inflateMenu(R.menu.menu_theme_settings)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_reset) confirmReset()
            true
        }

        setupPresetList()
        setupSavedThemes()
        setupTabs(savedInstanceState?.getInt(KEY_TAB) ?: TAB_GENERAL)
        setupGeneral()
        setupBrand()
        setupBackground()
        setupAccent()
        setupFont()
        setupSizes()
        setupSwitches()
        syncFromActive()
    }

    // ---------- 分页（常规 / 主题） ----------

    /**
     * 两个 Tab：常规（应用级开关、历史、底部按钮、大纲）与主题（配色、字体、预览）。
     *
     * 用「显示/隐藏两个容器」而不是 ViewPager：两页内容都在同一个 ScrollView 里，
     * 切 Tab 时只需换可见容器并回到顶部，没有额外 Fragment/适配器状态要维护，
     * 也不会出现「切过去还没测量完就闪一下」的问题。
     */
    private fun setupTabs(initial: Int) {
        val tabs = binding.settingsTabs
        tabs.removeAllTabs()
        tabs.addTab(tabs.newTab().setText("常规"))
        tabs.addTab(tabs.newTab().setText("主题"))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) = showPage(tab.position, true)
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })
        val target = initial.coerceIn(TAB_GENERAL, TAB_THEME)
        tabs.getTabAt(target)?.select()
        showPage(target, false)
    }

    private fun showPage(index: Int, resetScroll: Boolean) {
        val general = index == TAB_GENERAL
        binding.pageGeneral.isVisible = general
        binding.pageTheme.isVisible = !general
        if (resetScroll) binding.settingsScroll.scrollTo(0, 0)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 旋转屏幕后停在原来那一页，不要跳回「常规」
        outState.putInt(KEY_TAB, binding.settingsTabs.selectedTabPosition)
    }

    // ---------- 预设列表 ----------

    private fun setupPresetList() {
        val adapter = PresetAdapter(
            ThemePresets.presetNames,
            ThemePresets.presets,
            ThemeManager.presetId
        ) { id ->
            ThemeManager.presetId = id
            ThemeManager.customBrand = null
            ThemeManager.customBackground = null
            appliedSavedId = null
            (binding.presetList.adapter as PresetAdapter).setSelected(id)
            syncFromActive()
        }
        binding.presetList.adapter = adapter
    }

    // ---------- 我的主题（命名保存 / 应用 / 删除） ----------

    private fun setupSavedThemes() {
        savedAdapter = SavedAdapter(
            onApply = { theme ->
                if (ThemeManager.applySavedTheme(theme.id)) {
                    appliedSavedId = theme.id
                    (binding.presetList.adapter as? PresetAdapter)?.setSelected(ThemeManager.presetId)
                    syncFromActive()
                    toast("已应用主题「${theme.name}」")
                } else {
                    toast("该主题已不存在")
                }
            },
            onDelete = { theme -> confirmDeleteTheme(theme) }
        )
        binding.savedThemeList.adapter = savedAdapter
        binding.saveThemeButton.setOnClickListener { promptSaveTheme() }
        refreshSavedList()
    }

    private fun refreshSavedList() {
        savedAdapter.submit(ThemeManager.savedThemes, appliedSavedId)
        binding.savedEmptyHint.isVisible = ThemeManager.savedThemes.isEmpty()
    }

    private fun promptSaveTheme() {
        ThemedDialog.input(
            this, "保存当前主题", "我的主题 ${ThemeManager.savedThemes.size + 1}"
        ) { name ->
            val theme = ThemeManager.saveCurrentTheme(name)
            appliedSavedId = theme.id
            refreshSavedList()
            toast("已保存主题「${theme.name}」")
            true
        }
    }

    /** 导入历史条数：输入 1-100，缩小后立即裁掉最旧的记录 */
    private fun showHistoryLimitDialog() {
        ThemedDialog.input(
            this, "导入历史条数（1-100）", AppSettings.historyLimit.toString(), numeric = true
        ) { raw ->
            val value = raw.trim().toIntOrNull()
            if (value == null || value < 1 || value > 100) {
                toast("请输入 1 到 100 之间的整数")
                false
            } else {
                AppSettings.historyLimit = value
                JsonHistory.trimToLimit()
                binding.tvHistoryValue.text = "$value"
                true
            }
        }
    }

    /** 链接历史条数：输入 1-100，缩小后立即裁掉最旧的记录 */
    private fun showUrlHistoryLimitDialog() {
        ThemedDialog.input(
            this, "链接历史条数（1-100）", AppSettings.urlHistoryLimit.toString(), numeric = true
        ) { raw ->
            val value = raw.trim().toIntOrNull()
            if (value == null || value < 1 || value > 100) {
                toast("请输入 1 到 100 之间的整数")
                false
            } else {
                AppSettings.urlHistoryLimit = value
                UrlHistory.trimToLimit()
                binding.tvUrlHistoryValue.text = "$value"
                true
            }
        }
    }

    private fun confirmDeleteTheme(theme: com.guaner.jsonviewer.theme.SavedTheme) {
        ThemedDialog.confirm(
            this, "删除主题", "确定删除「${theme.name}」吗？", positive = "删除", danger = true
        ) {
            ThemeManager.deleteSavedTheme(theme.id)
            if (appliedSavedId == theme.id) appliedSavedId = null
            refreshSavedList()
        }
    }

    /** 编辑器开关是「设置里改、主界面生效」，这里只记录版本号，返回主界面时统一应用 */
    private fun notifyEditorOptionsChanged() {
        // 主界面在 onResume 会重新 applyThemeFromManager()，内部已包含 applyEditorOptions()；
        // 这里不需要额外广播，保持简单。
    }

    // ---------- 常规设置（与配色无关） ----------

    private fun setupGeneral() {
        binding.autoRestoreSwitch.isChecked = AppSettings.autoRestoreSession
        binding.quickJumpSwitch.isChecked = AppSettings.showQuickJump

        binding.autoRestoreSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.autoRestoreSession = checked
            // 关闭即清掉已经存在的内容，彻底不在本机留副本
            if (!checked) SessionStore.clear(this)
            refreshSavedSessionHint()
        }
        binding.quickJumpSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.showQuickJump = checked
        }

        // 代码编辑器（Sora）行为开关：写入设置后下一帧统一刷新一次编辑器
        with(binding) {
            editorLineNumbersSwitch.isChecked = AppSettings.editorLineNumbers
            editorCurrentLineSwitch.isChecked = AppSettings.editorHighlightCurrentLine
            editorBracketsSwitch.isChecked = AppSettings.editorHighlightBrackets
            editorScrollBarSwitch.isChecked = AppSettings.editorScrollBar
            editorScrollFlingSwitch.isChecked = AppSettings.editorScrollFling
            editorOverScrollSwitch.isChecked = AppSettings.editorOverScroll
            editorUndoSwitch.isChecked = AppSettings.editorUndo
            editorAutoIndentSwitch.isChecked = AppSettings.editorAutoIndent
            editorSymbolPairSwitch.isChecked = AppSettings.editorSymbolPair
            editorSearchSwitch.isChecked = AppSettings.editorSearch
            editorSearchCaseSwitch.isChecked = AppSettings.editorSearchCase
            editorSearchRegexSwitch.isChecked = AppSettings.editorSearchRegex
            editorSearchReplaceSwitch.isChecked = AppSettings.editorSearchReplace

            editorLineNumbersSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorLineNumbers = v
                notifyEditorOptionsChanged()
            }
            editorCurrentLineSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorHighlightCurrentLine = v
                notifyEditorOptionsChanged()
            }
            editorBracketsSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorHighlightBrackets = v
                notifyEditorOptionsChanged()
            }
            editorScrollBarSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorScrollBar = v
                notifyEditorOptionsChanged()
            }
            editorScrollFlingSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorScrollFling = v
                notifyEditorOptionsChanged()
            }
            editorOverScrollSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorOverScroll = v
                notifyEditorOptionsChanged()
            }
            editorUndoSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorUndo = v
                notifyEditorOptionsChanged()
            }
            editorAutoIndentSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorAutoIndent = v
                notifyEditorOptionsChanged()
            }
            editorSymbolPairSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorSymbolPair = v
                notifyEditorOptionsChanged()
            }
            editorSearchSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorSearch = v
                syncSearchSubSwitches()
            }
            editorSearchCaseSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorSearchCase = v
            }
            editorSearchRegexSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorSearchRegex = v
            }
            editorSearchReplaceSwitch.setOnCheckedChangeListener { _, v ->
                AppSettings.editorSearchReplace = v
            }
            syncSearchSubSwitches()
        }
        // 系统文本菜单「查看 JSON」入口：启/停 activity-alias，长按选词立即生效
        binding.processTextSwitch.isChecked = AppSettings.showProcessText
        binding.processTextSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.showProcessText = checked
            applyProcessTextSetting(checked)
        }
        // 粘贴/导入过滤：控制是否自动提取第一个有效 JSON
        binding.filterImportSwitch.isChecked = AppSettings.filterImportedText
        binding.filterImportSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.filterImportedText = checked
        }
        // 导入历史条数：点击弹单选，缩小后立即裁掉最旧的记录
        binding.tvHistoryValue.text = "${AppSettings.historyLimit}"
        binding.rowHistoryLimit.setOnClickListener { showHistoryLimitDialog() }
        // 链接历史条数：交互与导入历史一致
        binding.tvUrlHistoryValue.text = "${AppSettings.urlHistoryLimit}"
        binding.rowUrlHistoryLimit.setOnClickListener { showUrlHistoryLimitDialog() }
        // 崩溃日志：先用开关决定要不要记，再提供入口查看已有日志
        binding.crashLogSwitch.isChecked = AppSettings.crashLogEnabled
        binding.crashLogSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.crashLogEnabled = checked
            toast(
                if (checked) "已开启：异常退出时自动保存现场"
                else "已关闭：不再写入新日志，已有日志仍可查看"
            )
        }
        // 崩溃日志：显示条数，点开查看/复制/分享/删除
        refreshCrashCount()
        binding.rowCrashLog.setOnClickListener {
            CrashLogDialog.show(this) { count -> binding.tvCrashLogValue.text = "$count" }
        }
        binding.reorderButton.setOnClickListener { showReorderDialog() }

        // 大纲滑出方向：左侧（右滑呼出）/ 右侧（左滑呼出）
        binding.outlineSideGroup.check(
            if (AppSettings.isOutlineRight) R.id.sideRight else R.id.sideLeft
        )
        binding.outlineSideGroup.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.outlineSide =
                if (checkedId == R.id.sideRight) AppSettings.SIDE_RIGHT else AppSettings.SIDE_LEFT
        }

        // 边缘滑动模式：同侧 / 双侧 / 关闭
        binding.outlineSwipeGroup.check(
            when (AppSettings.outlineSwipe) {
                AppSettings.SWIPE_BOTH -> R.id.swipeBoth
                AppSettings.SWIPE_OFF -> R.id.swipeOff
                else -> R.id.swipeSame
            }
        )
        binding.outlineSwipeGroup.setOnCheckedChangeListener { _, checkedId ->
            AppSettings.outlineSwipe = when (checkedId) {
                R.id.swipeBoth -> AppSettings.SWIPE_BOTH
                R.id.swipeOff -> AppSettings.SWIPE_OFF
                else -> AppSettings.SWIPE_SAME
            }
        }

        binding.clearSessionButton.setOnClickListener {
            SessionStore.clear(this)
            refreshSavedSessionHint()
            toast("已清除本机保存的文本")
        }
        refreshSavedSessionHint()
    }

    /** 显示「本机保存的内容」：大小 + 真实路径，让用户清楚到底存了什么、存在哪 */
    /** 崩溃日志条数：0 条时也显示 0，让用户知道「没崩过」而不是功能没做 */
    private fun refreshCrashCount() {
        val count = CrashLogger.count(this)
        binding.tvCrashLogValue.text = "$count"
        binding.tvCrashLogValue.alpha = if (count > 0) 1f else 0.55f
    }

    private fun refreshSavedSessionHint() {
        val size = SessionStore.savedBytes(this)
        binding.savedSessionHint.text = when {
            !AppSettings.autoRestoreSession -> "自动恢复已关闭 · 本机不保存任何内容"
            size <= 0L -> "当前没有保存内容"
            else -> "已保存 ${JsonLimits.formatBytes(size)} · 只保留最新一份\n${SessionStore.path(this)}"
        }
    }

    /**
     * 底部按钮设置：每行显示「图标 + 文字 + 显示开关」，右侧 ↑/↓ 调顺序。
     *
     * 隐藏的按钮不会消失，只是从底部栏挪到「更多」菜单里，随时可以再打开；
     * 「更多」入口固定显示、不参与排序，避免把所有按钮隐藏后进不去设置。
     */
    private var reorderDialog: androidx.appcompat.app.AlertDialog? = null
    private var reorderSnapshot: String = ""

    /** 当前弹窗里「顺序 + 隐藏集合」的签名，用于判断有没有未保存的改动 */
    private fun reorderSignature(): String =
        reorderOrder.joinToString(",") + "#" + reorderHidden.sorted().joinToString(",") +
            "#" + reorderHiddenInMenu.sorted().joinToString(",") +
            "#" + reorderMenuHidden.sorted().joinToString(",")

    /** 启用/停用 PROCESS_TEXT 别名（与 MainActivity.onResume 的逻辑保持一致） */
    private fun applyProcessTextSetting(show: Boolean) {
        val state = if (show) {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                android.content.ComponentName(this, "com.guaner.jsonviewer.ProcessTextAlias"),
                state,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            // 失败就保持默认（显示入口），不影响其他设置
        }
    }

    private fun showReorderDialog() {
        reorderOrder = AppSettings.orderList.toMutableList()
        reorderHidden = AppSettings.hiddenKeys.toMutableSet()
        reorderHiddenInMenu = AppSettings.menuHiddenKeys.toMutableSet()
        reorderMenuHidden = AppSettings.hiddenMenuKeys.toMutableSet()
        reorderSnapshot = reorderSignature()

        val density = resources.displayMetrics.density
        val colors = ThemeManager.activeColors

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((18 * density).toInt(), (4 * density).toInt(), (18 * density).toInt(), 0)
        }
        reorderContainer = container
        rebuildReorderRows()

        val listHeight = minOf(
            (330 * density).toInt(),
            (resources.displayMetrics.heightPixels * 0.5f).toInt()
        )
        val scroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            addView(container)
        }

        val root = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(scroll, android.widget.LinearLayout.LayoutParams(-1, listHeight))
            addView(buildReorderActions())
        }

        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("底部按钮与更多菜单")
            .setView(root)
            .create()
        // 系统 AlertDialog 背景来自主题资源，运行时换肤不生效，这里换成主题色描边背景
        dialog.window?.setBackgroundDrawable(ThemedDialog.background(this))
        reorderDialog = dialog
        // 不响应「点外部关闭」：这里改动较多，误触一下就把设置丢了
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK &&
                event.action == android.view.KeyEvent.ACTION_UP
            ) {
                requestCloseReorder(dialog)
                true
            } else {
                false
            }
        }
        dialog.show()
    }

    /** 底部三个按钮：重置 / 取消 / 保存 */
    private fun buildReorderActions(): android.view.View {
        val density = resources.displayMetrics.density
        val colors = ThemeManager.activeColors
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
        }
        fun action(label: String, color: Int, bold: Boolean, action: () -> Unit): TextView =
            TextView(this).apply {
                text = label
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(color)
                if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = android.widget.LinearLayout.LayoutParams(0, (46 * density).toInt(), 1f)
                setOnClickListener { action() }
            }

        row.addView(action("重置", colors.gutterText, false) { resetReorder() })
        row.addView(action("取消", colors.text, false) {
            reorderDialog?.let { requestCloseReorder(it) }
        })
        row.addView(action("保存", colors.accent, true) {
            saveReorder()
            reorderDialog?.dismiss()
        })
        return row
    }

    /** 保存底部按钮设置：写盘后主界面 onResume 会按新配置重建底部栏 */
    private fun saveReorder() {
        AppSettings.bottomOrder = reorderOrder.joinToString(",")
        AppSettings.hiddenBottom = reorderHidden.joinToString(",")
        // 只在「已经不在底部栏」的项里保留「也不在菜单里」，两边永远一致
        AppSettings.menuHidden = reorderHiddenInMenu.intersect(reorderHidden).joinToString(",")
        AppSettings.hiddenMenu = reorderMenuHidden.joinToString(",")
        reorderSnapshot = reorderSignature()
        toast("已保存底部按钮设置")
    }

    /** 恢复默认顺序与默认隐藏项（只改弹窗里的副本，仍需点保存才生效） */
    private fun resetReorder() {
        reorderOrder = AppSettings.normalize(AppSettings.DEFAULT_ORDER, AppSettings.CONFIG_KEYS).toMutableList()
        reorderHidden = AppSettings.filterValid(AppSettings.DEFAULT_HIDDEN, AppSettings.CONFIG_KEYS).toMutableSet()
        reorderHiddenInMenu = HashSet()
        reorderMenuHidden = HashSet()
        rebuildReorderRows()
        toast("已恢复默认，点「保存」后生效")
    }

    /** 关闭前检查：有未保存改动就问一次，避免辛苦调好的顺序被误关丢掉 */
    private fun requestCloseReorder(dialog: android.app.Dialog) {
        if (reorderSignature() == reorderSnapshot) {
            dialog.dismiss()
            return
        }
        ThemedDialog.confirm(
            this, "保存修改？", "底部按钮的改动还没有保存。",
            positive = "保存",
            negative = "放弃修改",
            neutral = "继续编辑",
            onNegative = { dialog.dismiss() }   // 放弃修改：连底部按钮弹窗一起关掉
        ) {
            saveReorder()
            dialog.dismiss()
        }
    }

    /** 按当前顺序重建弹窗里的行；上/下移或切换开关后整块重建，逻辑最简单也最不容易错位 */
    private fun rebuildReorderRows() {
        val container = reorderContainer ?: return
        // 重建会清空子 View 导致滚动位置回到顶部，这里记住并恢复，免得调下面的项时跳来跳去
        val scroll = container.parent as? android.widget.ScrollView
        val keepY = scroll?.scrollY ?: 0
        val colors = ThemeManager.activeColors
        val density = resources.displayMetrics.density
        container.removeAllViews()
        container.addView(
            sectionHeader("点右侧按钮循环切换：底栏 → 更多 → 隐藏（隐藏后底部栏和「更多」里都不显示）")
        )
        reorderOrder.forEachIndexed { index, key ->
            val place = AppSettings.placeOf(key, reorderHidden, reorderHiddenInMenu)
            val row = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
                // 彻底隐藏的整行压暗，一眼能看出「这个现在哪儿都不显示」
                alpha = when (place) {
                    AppSettings.PLACE_BOTTOM -> 1f
                    AppSettings.PLACE_MORE -> 0.85f
                    else -> 0.45f
                }
            }
            // 图标：和底部栏完全一致，用户不必靠文字猜是哪个按钮
            row.addView(android.widget.ImageView(this).apply {
                setImageResource(BottomActionCatalog.icon(key))
                imageTintList = ColorStateList.valueOf(
                    if (place == AppSettings.PLACE_BOTTOM) colors.accent else colors.gutterText
                )
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    (22 * density).toInt(), (22 * density).toInt()
                )
                contentDescription = BottomActionCatalog.full(key)
            })
            row.addView(TextView(this).apply {
                // 名称统一两字（与底部按钮、长按提示完全一致）
                text = BottomActionCatalog.label(key)
                textSize = 14f
                setPadding((8 * density).toInt(), 0, 0, 0)
                setTextColor(colors.text)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            // 三态按钮：底栏 → 更多 → 隐藏 → 底栏（点一下走一步，当前状态直接写在按钮上）
            row.addView(placeButton(place) {
                val next = AppSettings.nextPlace(place)
                AppSettings.applyPlace(key, next, reorderHidden, reorderHiddenInMenu)
                rebuildReorderRows()
            })
            row.addView(moveButton("↑", index > 0) { swapReorder(index, index - 1) })
            row.addView(moveButton("↓", index < reorderOrder.size - 1) { swapReorder(index, index + 1) })
            container.addView(row)
        }
        // 「更多」菜单里的固定项：设置不可关，关于/示例可以关掉，关掉后菜单里就不再出现
        container.addView(sectionHeader("更多菜单（关掉的项不再出现在菜单里）"))
        container.addView(
            menuRow("设置", "固定显示：它是回到设置页的唯一入口", checked = true, locked = true) {}
        )
        container.addView(
            menuRow("关于", checked = "about" !in reorderMenuHidden) { checked ->
                if (checked) reorderMenuHidden.remove("about") else reorderMenuHidden.add("about")
                rebuildReorderRows()
            }
        )
        container.addView(
            menuRow("示例", "加载内置示例 JSON", checked = "sample" !in reorderMenuHidden) { checked ->
                if (checked) reorderMenuHidden.remove("sample") else reorderMenuHidden.add("sample")
                rebuildReorderRows()
            }
        )
        scroll?.post { scroll.scrollTo(0, keepY) }
    }

    /** 弹窗里的小节标题：底部栏 / 更多菜单 */
    private fun sectionHeader(text: String): View {
        val density = resources.displayMetrics.density
        val colors = ThemeManager.activeColors
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(colors.gutterText)
            setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
        }
    }

    /**
     * 「更多」菜单里的一项：只有文字和开关（菜单本身也不显示图标）。
     *
     * 锁定的项（设置）不给开关，右侧改成「固定」小字——关掉它用户就再也进不来设置了。
     */
    private fun menuRow(
        label: String,
        hint: String = "",
        checked: Boolean,
        locked: Boolean = false,
        onToggle: (Boolean) -> Unit
    ): View {
        val density = resources.displayMetrics.density
        val colors = ThemeManager.activeColors
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, (3 * density).toInt(), 0, (3 * density).toInt())
            alpha = if (checked) 1f else 0.55f
        }
        // 左侧留出与上面「图标 + 间距」等宽的空位，让两段的文字左对齐
        row.addView(
            View(this),
            android.widget.LinearLayout.LayoutParams((30 * density).toInt(), 1)
        )
        val texts = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        texts.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(colors.text)
        })
        if (hint.isNotEmpty()) {
            texts.addView(TextView(this).apply {
                text = hint
                textSize = 11f
                setTextColor(colors.gutterText)
                setPadding(0, (2 * density).toInt(), 0, 0)
            })
        }
        row.addView(
            texts,
            android.widget.LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
        )
        if (locked) {
            row.addView(TextView(this).apply {
                text = "固定"
                textSize = 12f
                setTextColor(colors.gutterText)
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
            })
        } else {
            row.addView(com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
                isChecked = checked
                thumbTintList = ColorStateList.valueOf(colors.accent)
                trackTintList = ColorStateList.valueOf(
                    ThemeManager.mix(colors.accent, colors.surface, 0.4f)
                )
                setOnCheckedChangeListener { _, c -> onToggle(c) }
            })
        }
        return row
    }

    private fun swapReorder(from: Int, to: Int) {
        if (from !in reorderOrder.indices || to !in reorderOrder.indices) return
        val key = reorderOrder.removeAt(from)
        reorderOrder.add(to, key)
        rebuildReorderRows()
    }

    /** 三态按钮：底栏（强调色）/ 更多（正文色）/ 隐藏（次要色），点一下切下一步 */
    private fun placeButton(place: Int, action: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        val colors = ThemeManager.activeColors
        val (label, color) = when (place) {
            AppSettings.PLACE_BOTTOM -> "底栏" to colors.accent
            AppSettings.PLACE_MORE -> "更多" to colors.text
            else -> "隐藏" to colors.gutterText
        }
        return TextView(this).apply {
            text = label
            textSize = 12f
            gravity = android.view.Gravity.CENTER
            setTextColor(color)
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * density
                setColor(android.graphics.Color.TRANSPARENT)
                setStroke((1 * density).toInt().coerceAtLeast(1), color)
            }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (52 * density).toInt(), (30 * density).toInt()
            )
            setOnClickListener { action() }
        }
    }

    private fun moveButton(label: String, enabled: Boolean, action: () -> Unit): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 17f
            gravity = android.view.Gravity.CENTER
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.25f
            setTextColor(ThemeManager.activeColors.accent)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                (34 * density).toInt(), (36 * density).toInt()
            )
            setOnClickListener { action() }
        }
    }

    // ---------- 主色调 ----------

    private fun setupBrand() {
        val l = simpleListener { updateBrandFromSliders() }
        binding.brandHue.setOnSeekBarChangeListener(l)
        binding.brandSat.setOnSeekBarChangeListener(l)
        binding.brandVal.setOnSeekBarChangeListener(l)
        binding.brandHex.doAfterTextChanged { txt ->
            if (suppress) return@doAfterTextChanged
            val c = ThemeManager.parseColorOrNull(txt.toString())
            if (c != null) {
                val hsv = rgbToHsv(c)
                suppress = true
                binding.brandHue.progress = hsv[0].toInt()
                binding.brandSat.progress = (hsv[1] * 100).toInt()
                binding.brandVal.progress = (hsv[2] * 100).toInt()
                suppress = false
                applyBrand(c)
            }
        }
    }

    private fun updateBrandFromSliders() {
        val c = hsvToColor(binding.brandHue.progress, binding.brandSat.progress, binding.brandVal.progress)
        suppress = true
        binding.brandHex.setText(ThemeManager.toHex(c))
        suppress = false
        applyBrand(c)
    }

    private fun applyBrand(c: Int) {
        ThemeManager.customBrand = ThemeManager.toHex(c)
        binding.brandSwatch.setBackgroundColor(c)
        markCustomEdited()
        refreshPreview()
    }

    /** 手动改了配色：不再算「已应用某个保存的主题」，取消其高亮 */
    private fun markCustomEdited() {
        if (appliedSavedId != null) {
            appliedSavedId = null
            refreshSavedList()
        }
    }

    // ---------- 背景色 ----------

    private fun setupBackground() {
        val l = simpleListener { updateBgFromSliders() }
        binding.bgHue.setOnSeekBarChangeListener(l)
        binding.bgSat.setOnSeekBarChangeListener(l)
        binding.bgVal.setOnSeekBarChangeListener(l)
        binding.bgHex.doAfterTextChanged { txt ->
            if (suppress) return@doAfterTextChanged
            val c = ThemeManager.parseColorOrNull(txt.toString())
            if (c != null) {
                val hsv = rgbToHsv(c)
                suppress = true
                binding.bgHue.progress = hsv[0].toInt()
                binding.bgSat.progress = (hsv[1] * 100).toInt()
                binding.bgVal.progress = (hsv[2] * 100).toInt()
                suppress = false
                applyBackground(c)
            }
        }
        binding.bgAutoContrast.setOnCheckedChangeListener { _, checked ->
            ThemeManager.autoContrast = checked
            syncFromActive()
            refreshPreview()
        }
    }

    private fun updateBgFromSliders() {
        val c = hsvToColor(binding.bgHue.progress, binding.bgSat.progress, binding.bgVal.progress)
        suppress = true
        binding.bgHex.setText(ThemeManager.toHex(c))
        suppress = false
        applyBackground(c)
    }

    private fun applyBackground(c: Int) {
        ThemeManager.customBackground = ThemeManager.toHex(c)
        binding.bgSwatch.setBackgroundColor(c)
        markCustomEdited()
        refreshPreview()
    }

    // ---------- 强调色（按钮 / 滑块 / Tab） ----------

    private fun setupAccent() {
        val l = simpleListener { updateAccentFromSliders() }
        binding.accentHue.setOnSeekBarChangeListener(l)
        binding.accentSat.setOnSeekBarChangeListener(l)
        binding.accentVal.setOnSeekBarChangeListener(l)
        binding.accentHex.doAfterTextChanged { txt ->
            if (suppress) return@doAfterTextChanged
            val c = ThemeManager.parseColorOrNull(txt.toString()) ?: return@doAfterTextChanged
            val hsv = rgbToHsv(c)
            suppress = true
            binding.accentHue.progress = hsv[0].toInt()
            binding.accentSat.progress = (hsv[1] * 100).toInt()
            binding.accentVal.progress = (hsv[2] * 100).toInt()
            suppress = false
            setAccentEditable(true)
            if (binding.accentFollow.isChecked) binding.accentFollow.isChecked = false
            applyAccent(c)
        }
        binding.accentFollow.setOnCheckedChangeListener { _, checked ->
            if (suppress) return@setOnCheckedChangeListener
            setAccentEditable(!checked)
            if (checked) {
                // 跟随主色调：清除自定义强调色（accent 自动回到 brand）
                ThemeManager.customAccent = null
                syncAccentControls(ThemeManager.activeColors)
            } else {
                // 取消跟随：以当前生效强调色作为自定义起点
                val cur = ThemeManager.activeColors.accent
                ThemeManager.customAccent = ThemeManager.toHex(cur)
            }
            refreshPreview()
        }
    }

    private fun updateAccentFromSliders() {
        val c = hsvToColor(binding.accentHue.progress, binding.accentSat.progress, binding.accentVal.progress)
        suppress = true
        binding.accentHex.setText(ThemeManager.toHex(c))
        suppress = false
        if (binding.accentFollow.isChecked) binding.accentFollow.isChecked = false
        setAccentEditable(true)
        applyAccent(c)
    }

    /** 写入自定义强调色并刷新（follow=true 时清除自定义，回到跟随主色） */
    private fun applyAccent(c: Int, follow: Boolean = false) {
        ThemeManager.customAccent = if (follow) null else ThemeManager.toHex(c)
        binding.accentSwatch.setBackgroundColor(if (follow) ThemeManager.activeColors.brand else c)
        markCustomEdited()
        refreshPreview()
    }

    private fun setAccentEditable(editable: Boolean) {
        binding.accentHex.isEnabled = editable
        binding.accentHue.isEnabled = editable
        binding.accentSat.isEnabled = editable
        binding.accentVal.isEnabled = editable
    }

    /** 把强调色控件的值同步到给定配色（不写回 ThemeManager） */
    private fun syncAccentControls(colors: ThemeColors) {
        val follow = ThemeManager.customAccent == null
        suppress = true
        binding.accentFollow.isChecked = follow
        setAccentEditable(!follow)
        val src = if (follow) colors.brand else colors.accent
        binding.accentHex.setText(ThemeManager.toHex(src))
        rgbToHsv(src).let {
            binding.accentHue.progress = it[0].toInt()
            binding.accentSat.progress = (it[1] * 100).toInt()
            binding.accentVal.progress = (it[2] * 100).toInt()
        }
        binding.accentSwatch.setBackgroundColor(src)
        suppress = false
    }

    // ---------- 字体 ----------

    private fun setupFont() {
        val names = arrayOf("等宽 Mono", "无衬线 Sans", "衬线 Serif", "系统默认")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, names)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.fontSpinner.adapter = adapter
        binding.fontSpinner.setSelection(fontPosition(ThemeManager.fontFamily))
        binding.fontSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                ThemeManager.fontFamily = fontFamilyFromPosition(pos)
                refreshPreview()
            }

            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    // ---------- 字号 ----------

    private fun setupSizes() {
        binding.codeSize.setOnSeekBarChangeListener(simpleListener {
            val sp = (binding.codeSize.progress + 8).toFloat()
            ThemeManager.codeFontSize = sp
            binding.codeSizeLabel.text = "${sp.toInt()}sp"
            refreshPreview()
        })
        binding.formSize.setOnSeekBarChangeListener(simpleListener {
            val sp = (binding.formSize.progress + 10).toFloat()
            ThemeManager.formFontSize = sp
            binding.formSizeLabel.text = "${sp.toInt()}sp"
        })
    }

    // ---------- 开关 ----------

    private fun setupSwitches() {
        binding.wrapSwitch.setOnCheckedChangeListener { _, checked -> ThemeManager.codeWrap = checked }
        binding.zoomSwitch.setOnCheckedChangeListener { _, checked -> ThemeManager.codeZoom = checked }
    }

    // ---------- 同步 / 预览 ----------

    /** 把当前生效配色与所有设置项同步到本页控件（不写回 ThemeManager） */
    private fun syncFromActive() {
        val c = ThemeManager.activeColors
        suppress = true
        binding.brandHex.setText(ThemeManager.toHex(c.brand))
        rgbToHsv(c.brand).let {
            binding.brandHue.progress = it[0].toInt()
            binding.brandSat.progress = (it[1] * 100).toInt()
            binding.brandVal.progress = (it[2] * 100).toInt()
        }
        binding.brandSwatch.setBackgroundColor(c.brand)

        binding.bgHex.setText(ThemeManager.toHex(c.background))
        rgbToHsv(c.background).let {
            binding.bgHue.progress = it[0].toInt()
            binding.bgSat.progress = (it[1] * 100).toInt()
            binding.bgVal.progress = (it[2] * 100).toInt()
        }
        binding.bgSwatch.setBackgroundColor(c.background)

        binding.codeSize.progress = (ThemeManager.codeFontSize - 8).toInt()
        binding.codeSizeLabel.text = "${ThemeManager.codeFontSize.toInt()}sp"
        binding.formSize.progress = (ThemeManager.formFontSize - 10).toInt()
        binding.formSizeLabel.text = "${ThemeManager.formFontSize.toInt()}sp"
        binding.wrapSwitch.isChecked = ThemeManager.codeWrap
        binding.zoomSwitch.isChecked = ThemeManager.codeZoom
        binding.bgAutoContrast.isChecked = ThemeManager.autoContrast
        binding.fontSpinner.setSelection(fontPosition(ThemeManager.fontFamily))
        suppress = false
        syncAccentControls(c)
        refreshPreview()
    }

    /** 根据当前生效主题刷新预览区（语法高亮 + 背景 + 字体 + 字号） */
    private fun refreshPreview() {
        val colors = ThemeManager.activeColors
        binding.preview.setBackgroundColor(colors.background)
        binding.preview.typeface = ThemeManager.resolveTypeface()
        binding.preview.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.codeFontSize)
        val span = SpannableString(SAMPLE)
        for (t in JsonHighlighter.findTokens(SAMPLE)) {
            span.setSpan(
                ForegroundColorSpan(colorOf(t.kind, colors)),
                t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        binding.preview.text = span
        // 主题管理页自身的底色/顶部栏/状态栏/控件 tint 也随主题实时变化
        applySelfTheme()
    }

    /**
     * 让主题管理页自身跟随当前生效主题：
     * - 顶部栏用主色，状态栏跟随顶部栏颜色；
     * - 各卡片底色用 surface（无法单独设置的按主色/背景自动派生）；
     * - 所有文字（除语法预览）用正文色，保证与背景对比；
     * - 滑块、开关用强调色 accent。
     */
    private fun applySelfTheme() {
        val colors = ThemeManager.activeColors
        binding.root.setBackgroundColor(colors.background)
        binding.toolbar.setBackgroundColor(colors.brand)
        val onBrand = ThemeManager.contrastText(colors.brand)
        binding.toolbar.setTitleTextColor(onBrand)
        // 返回箭头 tint 与顶部栏前景色一致，避免浅色主色下看不见。
        // 缓存原始图标，每次从 constantState 新建副本，避免拖动滑块反复换肤时叠加包装层。
        if (navIconBase == null) navIconBase = binding.toolbar.navigationIcon
        navIconBase?.let { base ->
            val d = base.constantState?.newDrawable()?.mutate() ?: base.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(d, onBrand)
            binding.toolbar.navigationIcon = d
        }

        // 顶部栏右侧的菜单（「重置」）与溢出图标同样用对比色，
        // 否则浅色主色下会是深灰字/黑图标，几乎看不见；顺带避免它落进未换肤的溢出弹窗。
        // 顶栏菜单项不是 toolbar 的直接子 View，而是包在 ActionMenuView 里，
        // 所以这里递归整棵子树找「重置」这个 TextView（ActionMenuItemView 是 TextView 子类）。
        val resetItem = binding.toolbar.menu.findItem(R.id.action_reset)
        val resetTitle = resetItem?.title?.toString()
        // 直接给标题套一个前景色 span：即使菜单项 View 被 AppCompat 重建，
        // 颜色也由文本自身带出来，不会退回主题默认的黑色。
        if (resetItem != null && resetTitle != null) {
            resetItem.title = android.text.SpannableString(resetTitle).apply {
                setSpan(
                    android.text.style.ForegroundColorSpan(onBrand),
                    0, resetTitle.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
        }
        tintToolbarChildren(binding.toolbar, resetTitle, onBrand)
        binding.toolbar.overflowIcon?.let { icon ->
            val d = icon.constantState?.newDrawable()?.mutate() ?: icon.mutate()
            androidx.core.graphics.drawable.DrawableCompat.setTint(d, onBrand)
            binding.toolbar.overflowIcon = d
        }

        // 状态栏跟随顶部栏颜色
        window.statusBarColor = colors.brand
        val light = onBrand == Color.BLACK
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = light

        // 各卡片底色跟随 surface
        listOf(
            binding.cardGeneral,
            binding.cardSaved,
            binding.cardBrand, binding.cardBg, binding.cardAccent,
            binding.cardFont, binding.cardEditor, binding.cardPreview
        ).forEach { it.setCardBackgroundColor(colors.surface) }

        // 卡片标题与所有文字（除实时预览的语法高亮）跟随正文色，保证任意背景可读。
        // toolbar 必须排除：它的标题要用 onBrand 对比色，不能被这里的正文色覆盖
        // （之前标题被刷成黑色、返回箭头仍是白色，两者不一致）。
        // settingsTabs 也要排除：Tab 的文字是 TabLayout 内部创建的 TextView，
        // 会被这里的正文色刷黑（首次进入「常规」「主题」是黑字），
        // 等用户点一下 Tab、TabLayout 自己重新应用 tabTextColors 才变回对比色。
        colorAllText(binding.root, colors.text, setOf(R.id.preview, R.id.toolbar, R.id.settingsTabs))

        // 分页 Tab 与顶部栏连成一体：底色同顶部栏，未选中项压暗、选中项与指示条用对比色。
        // 必须放在 colorAllText 之后，否则刚设好的颜色又被正文色覆盖。
        applyTabColors(colors.brand, onBrand)

        // 保存按钮用强调色、空态提示用次要文字色（放在 colorAllText 之后覆盖）
        binding.saveThemeButton.setTextColor(colors.accent)
        binding.savedEmptyHint.setTextColor(colors.gutterText)

        // 常规卡片：说明文字/分隔线用次要色，操作按钮用强调色
        binding.tvAutoRestoreHint.setTextColor(colors.gutterText)
        binding.tvQuickJumpHint.setTextColor(colors.gutterText)
        binding.tvProcessTextHint.setTextColor(colors.gutterText)
        binding.tvFilterImportHint.setTextColor(colors.gutterText)
        binding.tvHistoryLimitHint.setTextColor(colors.gutterText)
        binding.tvUrlHistoryLimitHint.setTextColor(colors.gutterText)
        binding.tvCrashLogHint.setTextColor(colors.gutterText)
        binding.tvCrashLogRecordHint.setTextColor(colors.gutterText)
        binding.tvReorderHint.setTextColor(colors.gutterText)
        binding.tvOutlineSideHint.setTextColor(colors.gutterText)
        binding.tvOutlineSwipeHint.setTextColor(colors.gutterText)
        binding.savedSessionHint.setTextColor(colors.gutterText)
        listOf(
                binding.generalDivider1, binding.generalDividerProcess,
            binding.generalDividerFilter, binding.generalDividerHistory,
            binding.generalDividerUrlHistory,
            binding.generalDividerCrash,
            binding.generalDividerCrashList,
            binding.generalDivider2, binding.generalDivider3, binding.generalDivider4
        ).forEach { it.setBackgroundColor(colors.gutter) }

        // 大纲方向单选：按钮圈用强调色（文字由 colorAllText 统一上色）
        val radioTint = ColorStateList.valueOf(colors.accent)
        listOf(binding.sideLeft, binding.sideRight, binding.swipeSame, binding.swipeBoth, binding.swipeOff)
            .forEach { it.buttonTintList = radioTint }
        listOf(
            binding.reorderButton, binding.clearSessionButton,
            binding.tvHistoryValue, binding.tvUrlHistoryValue, binding.tvCrashLogValue
        ).forEach { tv ->
            tv.setTextColor(colors.accent)
            tv.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16 * resources.displayMetrics.density
                setColor(colors.background)
                setStroke((1 * resources.displayMetrics.density).toInt().coerceAtLeast(1), colors.accent)
            }
        }

        // 滑块 tint 用强调色
        val accent = ColorStateList.valueOf(colors.accent)
        listOf(
            binding.brandHue, binding.brandSat, binding.brandVal,
            binding.bgHue, binding.bgSat, binding.bgVal,
            binding.accentHue, binding.accentSat, binding.accentVal,
            binding.codeSize, binding.formSize
        ).forEach {
            it.progressTintList = accent
            it.thumbTintList = accent
        }

        // 开关 tint：**四种状态分别给色**，关闭态必须明显比开启态暗。
        // 之前只给了一个固定色，Material 会用它去派生出「关闭」态，结果关闭的轨道仍是
        // 高亮灰（明度接近开启的强调色），一眼分不出开还是关。
        val dark = JsonEditorTheme.isDark(colors.surface)
        // 开启：强调色轨道 + 白/黑 thumb（保证任何主题下都醒目）
        val trackOn = colors.accent
        val thumbOn = if (JsonEditorTheme.isDark(colors.accent)) 0xFFFFFFFF.toInt() else 0xFF101010.toInt()
        // 关闭：轨道只比卡片底色亮一点点，thumb 用中灰——整体明度明显低于开启
        val trackOff = if (dark) {
            ThemeManager.mix(colors.surface, 0xFFFFFFFF.toInt(), 0.14f)
        } else {
            ThemeManager.mix(colors.surface, 0xFFFFFFFF.toInt(), 0.0f).let {
                ThemeManager.mix(it, 0xFF000000.toInt(), 0.20f)
            }
        }
        val thumbOff = ThemeManager.mix(trackOff, colors.text, 0.45f)
        // 禁用态再压一档，避免「关着的开关」和「不可用的开关」混淆
        val trackDisabled = ThemeManager.mix(colors.surface, colors.gutterText, 0.16f)
        val thumbDisabled = ThemeManager.mix(trackDisabled, colors.text, 0.28f)

        val thumbStates = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(thumbOn, thumbOff, colors.accent, thumbDisabled)
        )
        val trackStates = ColorStateList(
            arrayOf(
                intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_enabled),
                intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
                intArrayOf()
            ),
            intArrayOf(trackOn, trackOff, ThemeManager.mix(trackOn, trackOff, 0.55f), trackDisabled)
        )
        listOf(
            binding.bgAutoContrast, binding.accentFollow,
            binding.wrapSwitch, binding.zoomSwitch,
            binding.autoRestoreSwitch, binding.quickJumpSwitch,
            binding.processTextSwitch, binding.filterImportSwitch,
            binding.crashLogSwitch,
            // 编辑器行为卡片新增的 9 个开关，必须一起 tint，否则深色主题下轨道是系统默认色
            binding.editorLineNumbersSwitch, binding.editorCurrentLineSwitch,
            binding.editorBracketsSwitch, binding.editorScrollBarSwitch,
            binding.editorScrollFlingSwitch, binding.editorOverScrollSwitch,
            binding.editorUndoSwitch, binding.editorAutoIndentSwitch,
            binding.editorSymbolPairSwitch,
            binding.editorSearchSwitch, binding.editorSearchCaseSwitch,
            binding.editorSearchRegexSwitch, binding.editorSearchReplaceSwitch,
        ).forEach {
            it.thumbTintList = thumbStates
            it.trackTintList = trackStates
        }

        // 编辑器行为卡片的副标题统一压暗（无 id，按 textSize=11sp 识别）
        tintEditorHints(binding.cardEditor, colors.gutterText)

        // 十六进制输入框文字色
        binding.brandHex.setTextColor(colors.text)
        binding.bgHex.setTextColor(colors.text)
        binding.accentHex.setTextColor(colors.text)

        // 预设/已保存主题卡片：文字色与描边色跟随主题（拖动滑块时跳过，松手后统一刷新）
        if (!draggingSlider) refreshAdapterTheming()
    }

    /** 搜索总开关关闭时，三个子开关置灰（保留勾选状态，重新打开时原样恢复） */
    private fun syncSearchSubSwitches() {
        val on = AppSettings.editorSearch
        listOf(
            binding.editorSearchCaseSwitch,
            binding.editorSearchRegexSwitch,
            binding.editorSearchReplaceSwitch
        ).forEach {
            // 只用 isEnabled：颜色由 trackStates / thumbStates 的 disabled 分支决定，
            // 再叠 alpha 会让「不可用」比「关闭」还淡，容易和关闭态混淆
            it.isEnabled = on
        }
    }

    /** 把编辑器卡片里 11sp 的说明文字刷成次要色（正文色由 colorAllText 统一给，这里再压暗） */
    private fun tintEditorHints(v: View, color: Int) {
        if (v is TextView && v.textSize <= 11.5f) {
            v.setTextColor(color)
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) tintEditorHints(v.getChildAt(i), color)
    }

    /** 让主题卡片列表重新绑定（文字色 / 描边色随主题变化） */
    private fun refreshAdapterTheming() {
        binding.presetList.adapter?.notifyDataSetChanged()
        if (ThemeManager.savedThemes.isNotEmpty()) savedAdapter.refreshTheme()
    }

    /** 递归给所有 TextView 着色（排除指定 id，如实时预览保留语法高亮） */
    /** 递归给顶栏里的菜单文字/图标上对比色（菜单项被 ActionMenuView 包了一层） */
    private fun tintToolbarChildren(v: View, menuTitle: String?, color: Int) {
        when (v) {
            is TextView -> if (menuTitle != null && v.text?.toString() == menuTitle) v.setTextColor(color)
            is android.widget.ImageButton -> v.imageTintList =
                android.content.res.ColorStateList.valueOf(color)
            is ViewGroup -> for (i in 0 until v.childCount) tintToolbarChildren(v.getChildAt(i), menuTitle, color)
            else -> Unit
        }
    }

    /**
     * 分页 Tab 的配色：底色与顶部栏一致，未选中项压暗、选中项与指示条用对比色。
     *
     * 系统 TabLayout 的默认文字/指示条颜色取自主题资源，运行时换肤不会变；
     * 而它的文字 View 又会被 [colorAllText] 当成普通文本刷成正文色，
     * 所以这里在全局着色之后再显式覆盖一次（首次进入也必须是正确颜色）。
     */
    private fun applyTabColors(brand: Int, onBrand: Int) {
        val tabs = binding.settingsTabs
        tabs.setBackgroundColor(brand)
        tabs.setTabTextColors(ThemeManager.mix(onBrand, brand, 0.4f), onBrand)
        tabs.setSelectedTabIndicatorColor(onBrand)
        // Tab 内部的 TextView 是懒创建的，直接刷一遍更稳（setTabTextColors 只影响已存在的 TabView）
        for (i in 0 until tabs.tabCount) {
            val tab = tabs.getTabAt(i) ?: continue
            val view = tab.view
            if (view is ViewGroup) {
                for (j in 0 until view.childCount) {
                    (view.getChildAt(j) as? TextView)?.setTextColor(
                        if (tab.isSelected) onBrand else ThemeManager.mix(onBrand, brand, 0.4f)
                    )
                }
            }
        }
    }

    private fun colorAllText(v: View, color: Int, excludeIds: Set<Int>) {
        // 命中的整棵子树都跳过（toolbar 要保留自己的对比色）
        if (v.id in excludeIds) return
        if (v is TextView) v.setTextColor(color)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) colorAllText(v.getChildAt(i), color, excludeIds)
        }
    }

    /** 重置前确认：配色/字号会被恢复默认，这里用跟随主题的弹窗问一次 */
    private fun confirmReset() {
        ThemedDialog.confirm(
            this, "重置主题",
            "将恢复默认配色、字号与排版设置，当前的自定义会丢失。",
            positive = "重置"
        ) { resetAll() }
    }

    private fun resetAll() {
        ThemeManager.reset()
        (binding.presetList.adapter as? PresetAdapter)?.setSelected(ThemeManager.presetId)
        syncFromActive()
    }

    // ---------- 工具 ----------

    private fun colorOf(kind: TokenKind, colors: ThemeColors): Int = when (kind) {
        TokenKind.KEY -> colors.jsonKey
        TokenKind.STRING -> colors.jsonString
        TokenKind.NUMBER -> colors.jsonNumber
        TokenKind.LITERAL -> colors.jsonBoolean
        TokenKind.PUNCT -> colors.jsonPunct
    }

    private fun rgbToHsv(color: Int): FloatArray {
        val hsv = FloatArray(3)
        Color.RGBToHSV(Color.red(color), Color.green(color), Color.blue(color), hsv)
        return hsv
    }

    private fun hsvToColor(h: Int, s: Int, v: Int): Int =
        Color.HSVToColor(floatArrayOf(h.toFloat(), s / 100f, v / 100f))

    private fun fontPosition(font: String): Int = when (font) {
        ThemeManager.FONT_MONO -> 0
        ThemeManager.FONT_SANS -> 1
        ThemeManager.FONT_SERIF -> 2
        else -> 3
    }

    private fun fontFamilyFromPosition(pos: Int): String = when (pos) {
        0 -> ThemeManager.FONT_MONO
        1 -> ThemeManager.FONT_SANS
        2 -> ThemeManager.FONT_SERIF
        else -> ThemeManager.FONT_DEFAULT
    }

    private fun simpleListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange()
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
            draggingSlider = true
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
            draggingSlider = false
            refreshAdapterTheming()
        }
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** 主题预设选择列表 */
    class PresetAdapter(
        private val names: List<Pair<String, String>>,
        private val presets: Map<String, ThemeColors>,
        private var selectedId: String,
        private val onClick: (String) -> Unit
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {

        fun setSelected(id: String) {
            selectedId = id
            notifyDataSetChanged()
        }

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val swatchRow: View = itemView.findViewById(R.id.swatchRow)
            val swBrand: View = itemView.findViewById(R.id.swBrand)
            val swBg: View = itemView.findViewById(R.id.swBg)
            val swKey: View = itemView.findViewById(R.id.swKey)
            val swString: View = itemView.findViewById(R.id.swString)
            val swNumber: View = itemView.findViewById(R.id.swNumber)
            val name: TextView = itemView.findViewById(R.id.name)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_theme_preset, parent, false)
            return VH(v)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (id, label) = names[position]
            val colors = presets[id] ?: return
            val prev = colors.previewColors()
            holder.swBrand.setBackgroundColor(prev[0])
            holder.swBg.setBackgroundColor(prev[1])
            holder.swKey.setBackgroundColor(prev[2])
            holder.swString.setBackgroundColor(prev[3])
            holder.swNumber.setBackgroundColor(prev[4])
            holder.name.text = label
            // 预设名称跟随正文色，保证自定义背景下依然可读
            val active = ThemeManager.activeColors
            holder.name.setTextColor(active.text)
            // 每张卡片都画描边：相近配色也能分辨；选中时用强调色加粗
            val selected = id == selectedId
            holder.itemView.background = cardBorder(
                holder.itemView.context,
                if (selected) active.accent
                else ThemeManager.mix(active.text, active.background, 0.35f),
                if (selected) 2f else 1f
            )
            holder.itemView.setOnClickListener { onClick(id) }
        }

        override fun getItemCount() = names.size
    }

    /** 已保存的自定义主题列表 */
    class SavedAdapter(
        private val onApply: (SavedTheme) -> Unit,
        private val onDelete: (SavedTheme) -> Unit
    ) : RecyclerView.Adapter<SavedAdapter.VH>() {

        private var items: List<SavedTheme> = emptyList()
        private var selectedId: String? = null

        fun submit(list: List<SavedTheme>, selected: String?) {
            items = list
            selectedId = selected
            notifyDataSetChanged()
        }

        fun refreshTheme() = notifyDataSetChanged()

        class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
            val swBrand: View = itemView.findViewById(R.id.swBrand)
            val swBg: View = itemView.findViewById(R.id.swBg)
            val swAccent: View = itemView.findViewById(R.id.swAccent)
            val swText: View = itemView.findViewById(R.id.swText)
            val name: TextView = itemView.findViewById(R.id.name)
            val delete: android.widget.ImageButton = itemView.findViewById(R.id.deleteTheme)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_saved_theme, parent, false))

        override fun onBindViewHolder(holder: VH, position: Int) {
            val theme = items[position]
            val prev = theme.previewColors()
            holder.swBrand.setBackgroundColor(prev[0])
            holder.swBg.setBackgroundColor(prev[1])
            holder.swAccent.setBackgroundColor(prev[2])
            holder.swText.setBackgroundColor(prev[3])
            holder.name.text = theme.name
            val colors = ThemeManager.activeColors
            holder.name.setTextColor(colors.text)
            holder.delete.imageTintList = ColorStateList.valueOf(colors.gutterText)
            val selected = theme.id == selectedId
            holder.itemView.background = cardBorder(
                holder.itemView.context,
                if (selected) colors.accent else ThemeManager.mix(colors.text, colors.background, 0.35f),
                if (selected) 2f else 1f
            )
            holder.itemView.setOnClickListener { onApply(theme) }
            holder.delete.setOnClickListener { onDelete(theme) }
        }

        override fun getItemCount() = items.size
    }
}

/** 圆角描边背景（主题卡片通用）：透明填充 + 指定颜色描边 */
private fun cardBorder(
    context: android.content.Context,
    color: Int,
    widthDp: Float
): android.graphics.drawable.GradientDrawable {
    val d = context.resources.displayMetrics.density
    return android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = 10 * d
        setStroke((widthDp * d).toInt().coerceAtLeast(1), color)
        setColor(android.graphics.Color.TRANSPARENT)
    }
}
