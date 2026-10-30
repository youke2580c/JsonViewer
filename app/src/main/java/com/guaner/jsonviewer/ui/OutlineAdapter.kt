package com.guaner.jsonviewer.ui

import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.databinding.ItemOutlineBinding
import com.guaner.jsonviewer.json.NodeType
import com.guaner.jsonviewer.json.TreeNode
import com.guaner.jsonviewer.theme.ThemeColors
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 结构大纲适配器：渲染全树的大纲行。
 *
 * 行有两种来源（由 Activity 组装）：
 * - 树形浏览：按层级缩进，容器可展开/折叠（只看容器或全部节点）；
 * - 搜索结果：扁平行，标签直接给出路径片段。
 */
class OutlineAdapter(
    private val onPick: (Row) -> Unit,
    private val onToggle: (Row) -> Unit
) : RecyclerView.Adapter<OutlineAdapter.VH>() {

    class Row(
        val node: TreeNode,
        val depth: Int,
        val label: String,
        val hint: String,
        val expandable: Boolean,
        val expanded: Boolean,
        val highlight: Boolean
    )

    private val items = ArrayList<Row>()

    fun submit(rows: List<Row>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun applyTheme() {
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(ItemOutlineBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val row = items[position]
        val b = holder.binding
        val colors = ThemeManager.activeColors
        val density = b.root.resources.displayMetrics.density

        // 层级缩进：只看容器/全部节点时都能看出层级关系。
        // 缩进按层递增但在第 10 层后封顶，避免深嵌套把标签挤成一条线。
        b.root.setPadding(
            (6 + minOf(row.depth, MAX_INDENT_DEPTH) * 12 * density).toInt(),
            b.root.paddingTop,
            (14 * density).toInt(),
            b.root.paddingBottom
        )

        if (row.expandable) {
            b.outlineToggle.visibility = View.VISIBLE
            // 用实心三角代替 ▸/▾：小字号下更清楚
            b.outlineToggle.text = if (row.expanded) "▼" else "▶"
            b.outlineToggle.setTextColor(if (row.expanded) colors.accent else colors.gutterText)
            b.outlineToggle.setOnClickListener { onToggle(row) }
        } else {
            b.outlineToggle.visibility = View.INVISIBLE
            b.outlineToggle.setOnClickListener(null)
        }

        // 三类节点形状不同：对象=圆角方块，数组=空心圆环，最终项=实心小圆点
        b.outlineDot.setBackgroundResource(dotShape(row.node))
        b.outlineDot.backgroundTintList = ColorStateList.valueOf(dotColor(row.node, colors))
        b.outlineLabel.text = row.label
        b.outlineLabel.setTextColor(if (row.highlight) colors.accent else colors.text)
        b.outlineLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, ThemeManager.formFontSize)
        b.outlineHint.text = row.hint
        b.outlineHint.setTextColor(colors.gutterText)
        b.outlineHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, (ThemeManager.formFontSize - 3f).coerceAtLeast(9f))

        b.root.background = if (row.highlight) {
            ColorDrawable(colors.treeSelected)
        } else {
            ColorDrawable(android.graphics.Color.TRANSPARENT)
        }
        // 交互约定：
        // - 可展开项：单击 = 展开/收起，长按 = 跳转；
        // - 最终项：没有可展开内容，单击直接跳转。
        if (row.expandable) {
            b.root.setOnClickListener { onToggle(row) }
            b.root.setOnLongClickListener {
                b.root.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onPick(row)
                true
            }
        } else {
            b.root.setOnClickListener { onPick(row) }
            b.root.setOnLongClickListener { false }
        }
    }

    override fun getItemCount(): Int = items.size

    private fun dotColor(node: TreeNode, colors: ThemeColors): Int = when (node.type) {
        NodeType.OBJECT -> colors.folderObject
        NodeType.ARRAY -> colors.folderArray
        NodeType.STRING -> colors.jsonString
        NodeType.NUMBER -> colors.jsonNumber
        NodeType.BOOLEAN -> colors.jsonBoolean
        NodeType.NULL -> colors.jsonNull
    }

    private fun dotShape(node: TreeNode): Int = when (node.type) {
        NodeType.OBJECT -> R.drawable.dot_object
        NodeType.ARRAY -> R.drawable.dot_array
        else -> R.drawable.dot_circle
    }

    class VH(val binding: ItemOutlineBinding) : RecyclerView.ViewHolder(binding.root)

    companion object {
        /** 缩进最多递增到第几层（更深层不再继续缩进，保证标签还有宽度） */
        const val MAX_INDENT_DEPTH = 10

        /** 大纲行尾提示：容器显示「类型 · 数量」，最终项只显示类型 */
        fun hintOf(node: TreeNode): String =
            if (node.isContainer) "${node.typeLabel} · ${node.children.size}" else node.typeLabel
    }
}
