package com.guaner.jsonviewer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 应用级设置（与配色无关的通用开关）。
 *
 * 单独一个 SharedPreferences 文件，避免和主题配置互相干扰：
 * 主题可以整套重置，通用设置不应该被一起清掉。
 */
object AppSettings {

    private const val PREF = "jsonviewer_settings"

    const val SIDE_LEFT = "left"
    const val SIDE_RIGHT = "right"

    /** 边缘滑动呼出：仅面板同侧 / 双侧都可以 / 完全关闭（只能点按钮） */
    const val SWIPE_SAME = "same"
    const val SWIPE_BOTH = "both"
    const val SWIPE_OFF = "off"

    /**
     * 可以放到底部栏的动作 key（顺序可调、可隐藏）。
     *
     * 「更多」不在此列：它是菜单入口，固定显示在底部栏最右端，
     * 否则用户把按钮全隐藏后就再也进不去设置了。
     */
    val CONFIG_KEYS = listOf(
        "outline", "search", "jsonpath", "paste", "top", "bottom", "save", "share",
        "beautify", "minify", "escape", "import", "history", "network", "clear"
    )

    const val DEFAULT_ORDER =
        "outline,search,jsonpath,paste,top,bottom,save,share,beautify,minify,escape,import,history,network,clear"

    /** 默认隐藏的动作：这些先放在「更多」菜单里，用户想用再打开 */
    const val DEFAULT_HIDDEN = "beautify,minify,escape,import,clear"

    /**
     * 「更多」菜单里可开关的固定项。
     *
     * 「设置」刻意不在列表里：它是唯一能回到设置页的入口，
     * 允许关掉的话用户把按钮全藏起来之后就再也进不去了。
     */
    val MENU_KEYS = listOf("about", "sample")

    /** 被关掉的「更多」菜单项（逗号分隔的 key，空串表示全部显示） */
    var hiddenMenu: String
        get() = prefs.getString("hiddenMenu", "") ?: ""
        set(v) = prefs.edit().putString("hiddenMenu", filterValid(v, MENU_KEYS).joinToString(",")).apply()

    val hiddenMenuKeys: Set<String>
        get() = filterValid(hiddenMenu, MENU_KEYS).toSet()

    fun isMenuVisible(key: String): Boolean = key !in hiddenMenuKeys

    fun setMenuVisible(key: String, visible: Boolean) {
        if (key !in MENU_KEYS) return
        val hidden = hiddenMenuKeys.toMutableSet()
        if (visible) hidden.remove(key) else hidden.add(key)
        hiddenMenu = hidden.joinToString(",")
    }

    /** 导入历史条数上限，可在设置里调整 */
    var historyLimit: Int
        get() = prefs.getInt("historyLimit", 5)
        set(v) = prefs.edit().putInt("historyLimit", v.coerceIn(1, 100)).apply()

    /** 链接历史（网络面板访问过的 URL）条数上限 */
    var urlHistoryLimit: Int
        get() = prefs.getInt("urlHistoryLimit", 20)
        set(v) = prefs.edit().putInt("urlHistoryLimit", v.coerceIn(1, 100)).apply()

    /** Content-Type 的「自动」哨兵值：按请求体形态推断 */
    const val CT_AUTO = "auto"

    /**
     * 网络面板的 Content-Type 选择：`auto` 自动推断、空串不发送、其余原样发送。
     * 只影响「请求头里没手动填 Content-Type」的情况。
     */
    var contentType: String
        get() = prefs.getString("contentType", CT_AUTO) ?: CT_AUTO
        set(v) = prefs.edit().putString("contentType", v).apply()

    /**
     * 是否记录崩溃日志。
     *
     * 关闭后全局异常处理器不再写新文件（崩溃本身的行为不变，该崩还是崩，
     * 只是不落盘）；已经存在的日志仍可查看、复制、分享与清空。
     */
    var crashLogEnabled: Boolean
        get() = prefs.getBoolean("crashLogEnabled", true)
        set(v) = prefs.edit().putBoolean("crashLogEnabled", v).apply()

    /** 没填 Referer 时是否自动补「链接的站点根」 */
    var sendReferer: Boolean
        get() = prefs.getBoolean("sendReferer", true)
        set(v) = prefs.edit().putBoolean("sendReferer", v).apply()

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    }

    /** 启动时是否自动恢复上次的文本内容（关闭后也不再落盘，避免在本地留下内容） */
    var autoRestoreSession: Boolean
        get() = prefs.getBoolean("autoRestoreSession", true)
        set(v) = prefs.edit().putBoolean("autoRestoreSession", v).apply()

    /** 表单页底部「当前层快捷跳转」chip 条是否显示 */
    var showQuickJump: Boolean
        get() = prefs.getBoolean("showQuickJump", true)
        set(v) = prefs.edit().putBoolean("showQuickJump", v).apply()

    /**
     * 系统级文本选择菜单是否显示「查看 JSON」入口。
     *
     * 通过 activity-alias 的 enabled 状态实现：开关变化时调用
     * PackageManager.setComponentEnabledSetting，下次长按选词立即生效，无需重启。
     */
    var showProcessText: Boolean
        get() = prefs.getBoolean("showProcessText", true)
        set(v) = prefs.edit().putBoolean("showProcessText", v).apply()

    /**
     * 粘贴 / 导入 / 外部打开时是否过滤文本：自动提取第一个可完整解析的 JSON
     * （例如 `[00:11.566] {...}` 会去掉时间戳前缀）。关闭后原样导入，一字不动。
     */
    var filterImportedText: Boolean
        get() = prefs.getBoolean("filterImportedText", true)
        set(v) = prefs.edit().putBoolean("filterImportedText", v).apply()

    /**
     * 结构大纲从哪一侧滑出。
     *
     * [SIDE_LEFT]：面板贴左边，从左边缘向右滑呼出；
     * [SIDE_RIGHT]：面板贴右边，从右边缘向左滑呼出。
     */
    var outlineSide: String
        get() = prefs.getString("outlineSide", SIDE_LEFT) ?: SIDE_LEFT
        set(v) = prefs.edit().putString("outlineSide", normalizeSide(v)).apply()

    /** 纯函数：任何历史值 / 脏值都收敛为 left 或 right */
    fun normalizeSide(raw: String?): String = if (raw == SIDE_RIGHT) SIDE_RIGHT else SIDE_LEFT

    val isOutlineRight: Boolean
        get() = outlineSide == SIDE_RIGHT

    /** 边缘滑动呼出的模式，见 SWIPE_* */
    var outlineSwipe: String
        get() = prefs.getString("outlineSwipe", SWIPE_SAME) ?: SWIPE_SAME
        set(v) = prefs.edit().putString("outlineSwipe", normalizeSwipe(v)).apply()

    /** 纯函数：脏值一律退回「同侧」，避免左右都失灵 */
    fun normalizeSwipe(raw: String?): String = when (raw) {
        SWIPE_BOTH, SWIPE_OFF -> raw
        else -> SWIPE_SAME
    }

    val swipeBoth: Boolean get() = outlineSwipe == SWIPE_BOTH
    val swipeOff: Boolean get() = outlineSwipe == SWIPE_OFF

    /** 底部按钮顺序（逗号分隔的 key，含被隐藏的项，保证隐藏项也有稳定位置） */
    var bottomOrder: String
        get() = prefs.getString("bottomOrder", DEFAULT_ORDER) ?: DEFAULT_ORDER
        set(v) = prefs.edit().putString("bottomOrder", normalize(v, CONFIG_KEYS).joinToString(",")).apply()

    /** 被隐藏的底部按钮（不会再显示在底部栏，会出现在「更多」菜单里） */
    var hiddenBottom: String
        get() = prefs.getString("hiddenBottom", DEFAULT_HIDDEN) ?: DEFAULT_HIDDEN
        set(v) = prefs.edit().putString("hiddenBottom", filterValid(v, CONFIG_KEYS).joinToString(",")).apply()

    /** 完整顺序（含隐藏项） */
    val orderList: List<String>
        get() = normalize(bottomOrder, CONFIG_KEYS)

    val hiddenKeys: Set<String>
        get() = filterValid(hiddenBottom, CONFIG_KEYS).toSet()

    /** 实际显示在底部栏上的顺序 */
    val visibleOrder: List<String>
        get() {
            val hidden = hiddenKeys
            return orderList.filter { it !in hidden }
        }

    /**
     * 既不在底部栏、也不在「更多」菜单里的动作（三态里的「隐藏」）。
     *
     * 与 [hiddenBottom] 的区别：hiddenBottom 只是「从底部栏挪进更多」，
     * 这里是真的哪里都不显示，只保留在设置列表里，随时可以再调出来。
     */
    var menuHidden: String
        get() = prefs.getString("menuHidden", "") ?: ""
        set(v) = prefs.edit().putString("menuHidden", filterValid(v, CONFIG_KEYS).joinToString(",")).apply()

    val menuHiddenKeys: Set<String>
        get() = filterValid(menuHidden, CONFIG_KEYS).toSet()

    /** 一个按钮的三个去处：底部栏 / 「更多」/ 彻底隐藏 */
    const val PLACE_BOTTOM = 0
    const val PLACE_MORE = 1
    const val PLACE_HIDDEN = 2

    /** 纯函数：某个按钮现在在哪 */
    fun placeOf(key: String, bottomHidden: Set<String>, menuHidden: Set<String>): Int = when {
        key !in bottomHidden -> PLACE_BOTTOM
        key in menuHidden -> PLACE_HIDDEN
        else -> PLACE_MORE
    }

    /**
     * 纯函数：把按钮放到指定位置（就地改两个集合，保证两者永远一致）。
     *
     * 不变量：menuHidden ⊆ bottomHidden——在底部栏上的按钮不可能同时「不在菜单里」。
     */
    fun applyPlace(
        key: String,
        place: Int,
        bottomHidden: MutableSet<String>,
        menuHidden: MutableSet<String>
    ) {
        when (place) {
            PLACE_BOTTOM -> {
                bottomHidden.remove(key)
                menuHidden.remove(key)
            }
            PLACE_MORE -> {
                bottomHidden.add(key)
                menuHidden.remove(key)
            }
            else -> {
                bottomHidden.add(key)
                menuHidden.add(key)
            }
        }
    }

    /** 纯函数：三态循环 底栏 → 更多 → 隐藏 → 底栏 */
    fun nextPlace(current: Int): Int = when (current) {
        PLACE_BOTTOM -> PLACE_MORE
        PLACE_MORE -> PLACE_HIDDEN
        else -> PLACE_BOTTOM
    }

    fun isVisible(key: String): Boolean = key !in hiddenKeys

    fun setVisible(key: String, visible: Boolean) {
        val hidden = hiddenKeys.toMutableSet()
        if (visible) hidden.remove(key) else hidden.add(key)
        hiddenBottom = hidden.joinToString(",")
    }

    /**
     * 纯函数：只保留合法 key（去重，不补全）。
     *
     * 用于「隐藏集合」这类允许缺项的配置：不能像顺序那样把缺失项补回来，
     * 否则隐藏列表会变成「全部隐藏」。
     */
    fun filterValid(raw: String?, valid: List<String>): List<String> {
        val result = ArrayList<String>()
        if (raw.isNullOrBlank()) return result
        for (part in raw.split(',')) {
            val key = part.trim()
            if (key.isNotEmpty() && key in valid && key !in result) result.add(key)
        }
        return result
    }

    /**
     * 纯函数：把任意字符串顺序规范成合法列表。
     *
     * - 丢弃未知 key 与重复项（旧版本升级、手工改坏都能自愈）；
     * - 缺失的 key 按 [valid] 顺序补在末尾，保证每一项都有位置。
     */
    fun normalize(raw: String?, valid: List<String>): List<String> {
        val result = ArrayList<String>(valid.size)
        if (!raw.isNullOrBlank()) {
            for (part in raw.split(',')) {
                val key = part.trim()
                if (key.isNotEmpty() && key in valid && key !in result) result.add(key)
            }
        }
        for (key in valid) if (key !in result) result.add(key)
        return result
    }
}
