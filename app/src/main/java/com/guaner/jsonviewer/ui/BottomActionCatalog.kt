package com.guaner.jsonviewer.ui

import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.data.AppSettings

/**
 * 底部操作栏的动作目录：图标 + 短名 + 长说明 + 对应设置 key。
 *
 * 底部栏与设置页的排序弹窗共用同一份定义，保证「设置里看到的图标」
 * 与「底部栏显示的图标」永远一致；顺序与显隐由 [AppSettings] 持久化。
 *
 * - [label]：两字短名，用于底部栏长按提示与设置弹窗里的行名；
 * - [full]：完整名称，用于「更多」菜单（纯文字列表里两字太短看不懂）；
 * - [desc]：长按提示里的功能说明。
 */
data class BottomAction(
    val key: String,
    val label: String,
    val full: String,
    val desc: String,
    val icon: Int
)

object BottomActionCatalog {

    val CONFIG: List<BottomAction> = listOf(
        BottomAction(
            "outline", "大纲", "结构大纲",
            "树状浏览全部字段；点容器展开、长按跳转", R.drawable.ic_outline_tree
        ),
        BottomAction("search", "搜索", "搜索", "在当前页面里搜索并定位内容", R.drawable.ic_search),
        BottomAction(
            "jsonpath", "查询", "JSONPath 查询",
            "用 JSONPath 表达式精确取出节点", R.drawable.ic_code
        ),
        BottomAction("paste", "粘贴", "粘贴", "从剪贴板粘贴 JSON 文本", R.drawable.ic_paste),
        BottomAction("top", "置顶", "置顶", "回到当前页面最顶部", R.drawable.ic_vertical_top),
        BottomAction(
            "bottom", "置底", "置底",
            "跳到当前页面最底部", R.drawable.ic_vertical_bottom
        ),
        BottomAction(
            "undo", "撤销", "撤销",
            "退回上一步编辑；连续按可一直往前退", R.drawable.ic_undo
        ),
        BottomAction(
            "redo", "重做", "重做",
            "撤销之后又后悔了，用它把改动重新做回来", R.drawable.ic_redo
        ),
        BottomAction("save", "保存", "保存文件", "另存为 JSON 文件，可自选目录", R.drawable.ic_save),
        BottomAction("share", "分享", "分享", "把 JSON 作为文件分享给其他应用", R.drawable.ic_share),
        BottomAction("beautify", "美化", "美化格式", "按缩进重新排版，便于阅读", R.drawable.ic_format),
        BottomAction("minify", "压缩", "压缩", "去掉多余空白，压成一行", R.drawable.ic_compress),
        BottomAction(
            "escape", "转义", "Unicode 转换",
            "中文与 \\uXXXX 互相转换", R.drawable.ic_escape
        ),
        BottomAction(
            "import", "导入", "导入文件",
            "从本地文件读入 JSON 内容", R.drawable.ic_file
        ),
        BottomAction(
            "history", "历史", "导入历史",
            "查看并加载之前导入过的 JSON，默认加载最新一条", R.drawable.ic_history
        ),
        BottomAction(
            "network", "网络", "网络请求",
            "发 GET/POST 请求，自定请求头、UA 与 Content-Type，预览后导入响应体", R.drawable.ic_network
        ),
        BottomAction("clear", "清空", "清空", "清空当前全部内容", R.drawable.ic_clear)
    )

    /** 「更多」入口：固定显示、不可隐藏、不可排序 */
    val MORE = BottomAction("more", "更多", "更多", "其他功能；隐藏的按钮都在这里", R.drawable.ic_dots)

    private val byKey = CONFIG.associateBy { it.key }

    fun action(key: String): BottomAction? = byKey[key]

    fun label(key: String): String = byKey[key]?.label ?: key

    fun full(key: String): String = byKey[key]?.full ?: key

    fun desc(key: String): String = byKey[key]?.desc ?: ""

    fun icon(key: String): Int = byKey[key]?.icon ?: R.drawable.ic_dots

    /** 长按提示：「短名 · 功能说明」 */
    fun longPressHint(action: BottomAction): String = "${action.label} · ${action.desc}"

    /** 校验：目录里的 key 必须与设置里的可配置 key 一一对应 */
    fun keysMatchSettings(): Boolean = CONFIG.map { it.key } == AppSettings.CONFIG_KEYS
}
