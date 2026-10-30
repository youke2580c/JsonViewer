package com.guaner.jsonviewer.ui

import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.databinding.ItemTreeNodeBinding
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.TreeNode
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 表单（树状）模式适配器：渲染拍平后的可见节点。
 * 版式参照「键名一行 + 值一行」：键名行点击 = 展开/折叠或选中；
 * 值行点击 = 查看完整值（叶子）或展开/折叠（容器）；长按或点右上角三点 = 操作菜单。
 */
class TreeAdapter(
    private val onClick: (TreeNode) -> Unit,
    private val onLongClick: (TreeNode) -> Unit,
    private val onValueClick: (TreeNode) -> Unit,
    /** 值行长按：直接复制该节点的值（不用先打开完整值弹窗） */
    private val onValueLongClick: (TreeNode) -> Unit,
    /** 三点菜单：由 Activity 填充菜单项并处理点击 */
    private val onMenu: (TreeNode, PopupMenu) -> Unit
) : RecyclerView.Adapter<TreeAdapter.VH>() {

    companion object {
        /** 树行内值的展示上限：超过则截断并提示「点击查看全部」，完整内容进值详情弹窗 */
        const val VALUE_DISPLAY_CHARS = 3_000
    }

    private val items = ArrayList<TreeNode>()
    private var selectedPath: String? = null
    private var matched: Set<String> = emptySet()
    private var flashPath: String? = null
    private val flashHandler = Handler(Looper.getMainLooper())

    fun submit(list: List<TreeNode>, selected: String?) {
        items.clear()
        items.addAll(list)
        selectedPath = selected
        notifyDataSetChanged()
    }

    fun setMatched(paths: Set<String>) {
        matched = paths
        notifyDataSetChanged()
    }

    fun select(path: String?) {
        selectedPath = path
        notifyDataSetChanged()
    }

    /** 当前可见列表中的位置（用于滚动定位），不存在返回 -1 */
    fun positionOf(path: String): Int = items.indexOfFirst { it.path == path }

    fun clear() {
        items.clear()
        selectedPath = null
        matched = emptySet()
        notifyDataSetChanged()
    }

    /** 主题/字号变更后刷新可见节点（重新读取 formFontSize 由 onBindViewHolder 完成） */
    fun applyThemeFromManager() {
        notifyDataSetChanged()
    }

    /** 闪烁高亮某个节点（JSONPath 定位/搜索跳转时调用） */
    fun reveal(path: String) {
        val idx = items.indexOfFirst { it.path == path }
        if (idx < 0) return
        flashPath = path
        notifyItemChanged(idx)
        flashHandler.postDelayed({
            flashPath = null
            val i2 = items.indexOfFirst { it.path == path }
            if (i2 >= 0) notifyItemChanged(i2)
        }, 900)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemTreeNodeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val node = items[position]
        val b = holder.binding
        val density = b.root.resources.displayMetrics.density
        val colors = ThemeManager.activeColors

        b.root.setPadding(
            0,
            (6 * density).toInt(),
            0,
            (6 * density).toInt()
        )

        // 容器（对象/数组）：显示文件夹图标；用颜色区分类型
        // 对象/folder_object，数组/folder_array
        if (node.isContainer) {
            b.folderIcon.visibility = View.VISIBLE
            val folderColor =
                if (node.type == NodeType.ARRAY) colors.folderArray else colors.folderObject
            b.folderIcon.imageTintList = android.content.res.ColorStateList.valueOf(folderColor)
        } else {
            b.folderIcon.visibility = View.GONE
        }

        // 键名 / 数组下标：始终显示，确保右侧三点菜单固定在最右（不会被挤到前面）
        b.key.visibility = View.VISIBLE
        b.key.text = node.indexLabel ?: ""
        // 表单字号跟随主题设置（键名与值同步；元信息保持较小字号）
        b.key.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.formFontSize)
        b.value.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.formFontSize)
        // 键名颜色跟随主题（jsonKey）
        b.key.setTextColor(colors.jsonKey)

        // 值与元信息：叶子完整展示（超长截断 + 提示），容器显示类型与子项数
        when (node.type) {
            NodeType.OBJECT, NodeType.ARRAY -> {
                b.value.text = ""
                b.meta.visibility = View.VISIBLE
                b.meta.text = "${node.typeLabel} · ${node.children.size} 项"
                b.value.maxLines = 1
            }
            else -> {
                val s = node.scalar ?: ""
                b.value.text = if (s.length <= VALUE_DISPLAY_CHARS) {
                    s
                } else {
                    s.take(VALUE_DISPLAY_CHARS) + "\n…（共 ${s.length} 字符，点击查看全部）"
                }
                b.value.maxLines = if (s.length <= VALUE_DISPLAY_CHARS) 12 else 13
                b.value.setTextColor(colorOf(node.type, colors))
                b.meta.visibility = View.VISIBLE
                b.meta.text = when {
                    node.type == NodeType.STRING && s.length > VALUE_DISPLAY_CHARS ->
                        "字符串 · ${s.length} 字符"
                    else -> node.typeLabel
                }
            }
        }
        b.meta.setTextColor(colors.gutterText)
        b.dots.imageTintList = android.content.res.ColorStateList.valueOf(colors.gutterText)

        // 背景：闪烁 > 选中 > 搜索命中 > 默认
        b.root.background = when {
            node.path == flashPath -> ColorDrawable(colors.searchCurrent)
            node.path == selectedPath -> ColorDrawable(colors.treeSelected)
            node.path in matched -> ColorDrawable(colors.searchMatch)
            else -> ColorDrawable(colors.background)
        }

        // 键名行：点击 = 展开/选中，长按 = 操作菜单
        b.keyRow.setOnClickListener { onClick(node) }
        b.keyRow.setOnLongClickListener {
            onLongClick(node)
            true
        }
        // 值行：叶子 = 查看完整值；容器 = 展开/折叠
        b.valueRow.setOnClickListener {
            if (node.isContainer) onClick(node) else onValueClick(node)
        }
        // 值行长按（仅叶子）：直接复制值，省一次「打开弹窗 -> 点复制」
        b.valueRow.setOnLongClickListener {
            if (node.isContainer) {
                false
            } else {
                onValueLongClick(node)
                true
            }
        }
        // 右上角三点菜单
        b.dots.setOnClickListener { v ->
            // 列表还在、但 Activity 已在销毁流程时，show() 会抛 BadTokenException
            try {
                val popup = PopupMenu(v.context, v)
                onMenu(node, popup)
                popup.show()
            } catch (_: Throwable) {
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun colorOf(type: NodeType, colors: com.guaner.jsonviewer.theme.ThemeColors): Int = when (type) {
        NodeType.STRING -> colors.jsonString
        NodeType.NUMBER -> colors.jsonNumber
        NodeType.BOOLEAN -> colors.jsonBoolean
        NodeType.NULL -> colors.jsonNull
        else -> colors.text
    }

    class VH(val binding: ItemTreeNodeBinding) : RecyclerView.ViewHolder(binding.root)
}
