package com.guaner.jsonviewer.ui

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.data.TextHistoryStore
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 通用「历史列表」弹窗：导入历史与链接历史共用同一套交互与视觉。
 *
 * - 点击某条：加载该条（[onLoad] 回传内容与序号）；
 * - 长按某条：进入多选模式并选中该条，底部按钮变为「删除所选(N)」+「取消」；
 * - 行右侧垃圾桶：单条删除（二次确认）；
 * - 「清空」：清空全部（二次确认）；
 * - 序号用强调色加粗，当前正在使用的那条整行标「（当前）」并加描边。
 *
 * 删除/清空会直接写回 [store]，所以调用方不需要再处理持久化。
 */
object HistoryDialog {

    fun show(
        activity: Activity,
        title: String,
        store: TextHistoryStore,
        current: String? = null,
        emptyHint: String,
        previewLength: Int = 30,
        /** 把存储的原始文本转成列表里显示的一行（链接历史用它显示「POST https://…」） */
        label: (String) -> String = { it },
        /** 判断某条是否「当前正在使用」，默认与 [current] 直接比较 */
        currentMatcher: ((String) -> Boolean)? = null,
        onLoad: (text: String, number: Int) -> Unit
    ) {
        var items = store.entries().toMutableList()
        if (items.isEmpty()) {
            Toast.makeText(activity, emptyHint, Toast.LENGTH_SHORT).show()
            return
        }
        val colors = ThemeManager.activeColors
        val danger = ThemedDialog.dangerColor()
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        var selectMode = false
        val selected = mutableSetOf<Int>()

        val dialog = ThemedDialog.create(activity)
        val box = ThemedDialog.container(activity)
        box.addView(ThemedDialog.title(activity, title))

        val listView = ListView(activity).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, 0)
        }
        box.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(320))
        )

        fun preview(text: String): String =
            text.replace(Regex("\\s+"), " ").let {
                if (it.length > previewLength) it.take(previewLength) + "…" else it
            }

        lateinit var btnClear: TextView
        lateinit var btnClose: TextView

        fun updateButtons() {
            if (selectMode) {
                btnClear.text = if (selected.isEmpty()) "删除所选" else "删除所选(${selected.size})"
                btnClose.text = "取消"
            } else {
                btnClear.text = "清空"
                btnClose.text = "关闭"
            }
        }

        val adapter = object : BaseAdapter() {
            override fun getCount() = items.size
            override fun getItem(position: Int) = items[position]
            override fun getItemId(position: Int) = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val h: Row
                if (convertView != null && convertView.tag is Row) {
                    row = convertView as LinearLayout
                    h = convertView.tag as Row
                } else {
                    row = LinearLayout(activity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(4), dp(8), dp(4), dp(8))
                    }
                    val check = CheckBox(activity).apply {
                        buttonTintList = android.content.res.ColorStateList.valueOf(colors.accent)
                        // 不抢点击：整行点击统一处理，避免勾选状态与数据不同步
                        isClickable = false
                        isFocusable = false
                    }
                    val text = TextView(activity).apply {
                        textSize = 13f
                        setTextColor(colors.text)
                        isClickable = false
                        isFocusable = false
                    }
                    val delete = ImageButton(activity).apply {
                        setBackgroundColor(Color.TRANSPARENT)
                        setImageResource(R.drawable.ic_clear)
                        setColorFilter(colors.gutterText)
                        val pad = dp(8)
                        setPadding(pad, pad, pad, pad)
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        isFocusable = false
                    }
                    row.addView(check)
                    row.addView(
                        text,
                        LinearLayout.LayoutParams(
                            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                        ).apply {
                            marginStart = dp(6)
                            marginEnd = dp(6)
                        }
                    )
                    row.addView(delete, LinearLayout.LayoutParams(dp(28), dp(28)))
                    h = Row(check, delete, text)
                    row.tag = h
                }

                val number = items.size - position
                val item = items[position]
                val isCurrent = currentMatcher?.invoke(item)
                    ?: (current != null && item == current)
                val shown = "#$number  ${preview(label(item))}" +
                    if (isCurrent) "（当前）" else ""
                val sp = SpannableString(shown)
                val numberEnd = 1 + number.toString().length
                // 序号高亮：强调色 + 粗体
                sp.setSpan(
                    ForegroundColorSpan(colors.accent), 0, numberEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sp.setSpan(
                    StyleSpan(android.graphics.Typeface.BOLD), 0, numberEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (isCurrent) {
                    sp.setSpan(
                        ForegroundColorSpan(colors.accent), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                h.text.text = sp
                // 当前使用中的一条加一圈描边，一眼能认出
                row.background = if (isCurrent) {
                    GradientDrawable().apply {
                        cornerRadius = 8 * density
                        setColor(Color.TRANSPARENT)
                        setStroke(dp(1).coerceAtLeast(1), colors.accent)
                    }
                } else null

                h.check.visibility = if (selectMode) View.VISIBLE else View.GONE
                h.check.isChecked = position in selected
                h.delete.visibility = if (selectMode) View.GONE else View.VISIBLE
                h.delete.setOnClickListener {
                    // 用「条目本身」而不是下标：二次确认期间列表若被刷新过，
                    // 旧下标可能越界，直接 removeAt 会抛 IndexOutOfBounds 闪退
                    val target = items.getOrNull(position) ?: return@setOnClickListener
                    ThemedDialog.confirm(
                        activity, "删除历史",
                        "确定删除这一条历史吗？删除后不可恢复。",
                        positive = "删除", danger = true
                    ) {
                        items.remove(target)
                        store.replaceAll(items)
                        selected.clear()
                        if (items.isEmpty()) {
                            dialog.dismiss()
                            Toast.makeText(activity, "历史已清空", Toast.LENGTH_SHORT).show()
                        } else {
                            notifyDataSetChanged()
                            updateButtons()
                        }
                    }
                }
                return row
            }
        }
        listView.adapter = adapter

        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }

        fun exitSelectMode() {
            selectMode = false
            selected.clear()
            adapter.notifyDataSetChanged()
            updateButtons()
        }

        btnClear = ThemedDialog.button(activity, "清空", danger) {
            if (selectMode) {
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "请先勾选要删除的历史", Toast.LENGTH_SHORT).show()
                } else {
                    val n = selected.size
                    ThemedDialog.confirm(
                        activity, "删除所选", "确定删除选中的 $n 条历史吗？",
                        positive = "删除", danger = true
                    ) {
                        items = items.filterIndexed { i, _ -> i !in selected }.toMutableList()
                        store.replaceAll(items)
                        selected.clear()
                        selectMode = false
                        if (items.isEmpty()) {
                            dialog.dismiss()
                            Toast.makeText(activity, "历史已全部删除", Toast.LENGTH_SHORT).show()
                        } else {
                            adapter.notifyDataSetChanged()
                            updateButtons()
                        }
                    }
                }
            } else {
                ThemedDialog.confirm(
                    activity, "清空历史", "确定清空全部 ${items.size} 条历史吗？",
                    positive = "清空", danger = true
                ) {
                    store.clear()
                    dialog.dismiss()
                    Toast.makeText(activity, "已清空历史", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnClose = ThemedDialog.button(activity, "关闭", colors.gutterText) {
            if (selectMode) exitSelectMode() else dialog.dismiss()
        }
        buttonRow.addView(btnClear)
        buttonRow.addView(btnClose)
        box.addView(buttonRow)
        updateButtons()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (selectMode) {
                if (position in selected) selected.remove(position) else selected.add(position)
                adapter.notifyDataSetChanged()
                updateButtons()
            } else {
                dialog.dismiss()
                onLoad(items[position], items.size - position)
            }
        }
        // 长按进入多选，并选中当前这一条
        listView.setOnItemLongClickListener { _, _, position, _ ->
            selectMode = true
            selected.clear()
            selected.add(position)
            adapter.notifyDataSetChanged()
            updateButtons()
            true
        }

        ThemedDialog.show(dialog, box, 320)
    }

    private class Row(val check: CheckBox, val delete: ImageButton, val text: TextView)
}
