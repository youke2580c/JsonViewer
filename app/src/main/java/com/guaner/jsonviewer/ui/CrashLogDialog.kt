package com.guaner.jsonviewer.ui

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
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
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.guaner.jsonviewer.R
import com.guaner.jsonviewer.crash.CrashEntry
import com.guaner.jsonviewer.crash.CrashLogger
import com.guaner.jsonviewer.json.JsonLimits
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 崩溃日志查看器（设置页入口）。
 *
 * - 列表按时间倒序，显示时间 + 异常摘要 + 大小；
 * - 点击某条 → 全屏等宽的日志详情，可一键复制；
 * - 长按 → 多选，底部变「删除所选(N)」；
 * - 「清空」二次确认后删掉全部。
 *
 * 视觉全部走 [ThemedDialog] / [ThemeManager.activeColors]，跟随运行时换肤。
 */
object CrashLogDialog {

    fun show(activity: Activity, onChanged: (Int) -> Unit = {}) {
        if (activity.isFinishing || activity.isDestroyed) return
        var items = CrashLogger.logs(activity).toMutableList()
        if (items.isEmpty()) {
            Toast.makeText(activity, "还没有崩溃日志，一切正常", Toast.LENGTH_SHORT).show()
            onChanged(0)
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
        box.addView(ThemedDialog.title(activity, "崩溃日志"))

        val hint = ThemedDialog.body(
            activity,
            "应用异常退出时自动保存现场（版本、机型、堆栈、最后页面）。共 ${items.size} 条，最多保留 50 条。"
        ).apply {
            textSize = 11f
            setPadding(0, dp(6), 0, 0)
        }
        box.addView(hint)

        val listView = ListView(activity).apply {
            divider = null
            selector = ColorDrawable(Color.TRANSPARENT)
            setPadding(0, dp(8), 0, 0)
        }
        val listHeight = minOf(
            dp(320),
            (activity.resources.displayMetrics.heightPixels * 0.5f).toInt()
        )
        box.addView(
            listView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, listHeight)
        )

        lateinit var btnDanger: TextView
        lateinit var btnClose: TextView

        fun updateButtons() {
            if (selectMode) {
                btnDanger.text = if (selected.isEmpty()) "删除所选" else "删除所选(${selected.size})"
                btnClose.text = "取消"
            } else {
                btnDanger.text = "清空"
                btnClose.text = "关闭"
            }
        }

        fun refreshCount() = onChanged(items.size)

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
                        isClickable = false
                        isFocusable = false
                    }
                    val text = TextView(activity).apply {
                        textSize = 12f
                        setTextColor(colors.text)
                        setLineSpacing(dp(2).toFloat(), 1f)
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

                val item = items.getOrNull(position)
                val number = items.size - position
                if (item == null) {
                    h.text.text = ""
                    return row
                }
                val head = "#$number  ${item.time}"
                val sp = android.text.SpannableStringBuilder(head)
                sp.setSpan(
                    ForegroundColorSpan(colors.accent), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sp.setSpan(
                    StyleSpan(Typeface.BOLD), 0, head.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                sp.append("\n")
                sp.append(item.headline.take(120))
                sp.append("\n")
                sp.append("${item.screen} · ${JsonLimits.formatBytes(item.sizeBytes)}")
                // 摘要部分用次要色，时间保持强调色
                sp.setSpan(
                    ForegroundColorSpan(colors.gutterText),
                    head.length + 1,
                    sp.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                h.text.text = sp

                h.check.visibility = if (selectMode) View.VISIBLE else View.GONE
                h.check.isChecked = position in selected
                h.delete.visibility = if (selectMode) View.GONE else View.VISIBLE
                h.delete.setOnClickListener {
                    val target = items.getOrNull(position) ?: return@setOnClickListener
                    ThemedDialog.confirm(
                        activity, "删除日志",
                        "确定删除这条崩溃日志吗？删除后不可恢复。",
                        positive = "删除", danger = true
                    ) {
                        items.remove(target)
                        CrashLogger.delete(target.file)
                        selected.clear()
                        refreshCount()
                        if (items.isEmpty()) {
                            dialog.dismiss()
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

        btnDanger = ThemedDialog.button(activity, "清空", danger) {
            if (selectMode) {
                if (selected.isEmpty()) {
                    Toast.makeText(activity, "请先勾选要删除的日志", Toast.LENGTH_SHORT).show()
                } else {
                    val n = selected.size
                    ThemedDialog.confirm(
                        activity, "删除所选", "确定删除选中的 $n 条崩溃日志吗？",
                        positive = "删除", danger = true
                    ) {
                        val targets = items.filterIndexed { i, _ -> i in selected }
                        targets.forEach { CrashLogger.delete(it.file) }
                        items = items.filterIndexed { i, _ -> i !in selected }.toMutableList()
                        selected.clear()
                        selectMode = false
                        refreshCount()
                        if (items.isEmpty()) {
                            dialog.dismiss()
                        } else {
                            adapter.notifyDataSetChanged()
                            updateButtons()
                        }
                    }
                }
            } else {
                ThemedDialog.confirm(
                    activity, "清空崩溃日志", "确定清空全部 ${items.size} 条日志吗？",
                    positive = "清空", danger = true
                ) {
                    CrashLogger.clear(activity)
                    items = mutableListOf()
                    refreshCount()
                    dialog.dismiss()
                    Toast.makeText(activity, "已清空崩溃日志", Toast.LENGTH_SHORT).show()
                }
            }
        }
        btnClose = ThemedDialog.button(activity, "关闭", colors.gutterText) {
            if (selectMode) exitSelectMode() else dialog.dismiss()
        }
        buttonRow.addView(btnDanger)
        buttonRow.addView(btnClose)
        box.addView(buttonRow)
        updateButtons()

        listView.setOnItemClickListener { _, _, position, _ ->
            if (selectMode) {
                if (position in selected) selected.remove(position) else selected.add(position)
                adapter.notifyDataSetChanged()
                updateButtons()
            } else {
                val item = items.getOrNull(position) ?: return@setOnItemClickListener
                showDetail(activity, item)
            }
        }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            selectMode = true
            selected.clear()
            selected.add(position)
            adapter.notifyDataSetChanged()
            updateButtons()
            true
        }

        ThemedDialog.show(dialog, box, 340)
    }

    /** 日志详情：等宽字体 + 可滚动 + 一键复制 */
    private fun showDetail(activity: Activity, entry: CrashEntry) {
        if (activity.isFinishing || activity.isDestroyed) return
        val colors = ThemeManager.activeColors
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val text = CrashLogger.readText(entry.file)
        val dialog = ThemedDialog.create(activity)
        val box = ThemedDialog.container(activity)
        box.addView(ThemedDialog.title(activity, "崩溃详情"))
        box.addView(
            ThemedDialog.body(activity, entry.time).apply {
                textSize = 11f
                setPadding(0, dp(4), 0, 0)
            }
        )

        val logView = TextView(activity).apply {
            this.text = text
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(colors.text)
            setTextIsSelectable(true)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = 10 * density
                setColor(ThemeManager.mix(colors.background, colors.surface, 0.5f))
                setStroke(dp(1).coerceAtLeast(1), colors.gutter)
            }
        }
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(logView, ViewGroup.LayoutParams(-1, -2))
        }
        val logHeight = minOf(
            dp(360),
            (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        )
        box.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                logHeight
            ).apply { topMargin = dp(8) }
        )

        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(6), 0, 0)
        }
        row.addView(ThemedDialog.button(activity, "复制全部", colors.accent) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("崩溃日志", text))
            Toast.makeText(activity, "日志已复制", Toast.LENGTH_SHORT).show()
        })
        row.addView(ThemedDialog.button(activity, "分享文件", colors.accent) {
            share(activity, entry)
        })
        row.addView(ThemedDialog.button(activity, "关闭", colors.gutterText) { dialog.dismiss() })
        box.addView(row)

        ThemedDialog.show(dialog, box, 380)
    }

    /**
     * 分享日志文件。
     *
     * 优先走 FileProvider 发原文件（堆栈很长时也不会撞 Binder 的 ~1MB 限制）；
     * 万一 URI 授权失败，退回「复制到 cache/share + 文本分享」，再不行只提示。
     */
    private fun share(activity: Activity, entry: CrashEntry) {
        try {
            val dir = java.io.File(activity.cacheDir, "share").apply { mkdirs() }
            // 只保留最新一份，避免缓存无限增长
            dir.listFiles()?.forEach { it.delete() }
            val target = java.io.File(dir, entry.file.name)
            entry.file.copyTo(target, overwrite = true)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", target
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                putExtra(android.content.Intent.EXTRA_SUBJECT, "JSON Viewer 崩溃日志")
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            activity.startActivity(android.content.Intent.createChooser(intent, "分享崩溃日志"))
            return
        } catch (_: Throwable) {
            // 继续走文本兜底
        }
        try {
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(android.content.Intent.EXTRA_SUBJECT, "JSON Viewer 崩溃日志")
                putExtra(
                    android.content.Intent.EXTRA_TEXT,
                    CrashLogger.readText(entry.file).take(64 * 1024)
                )
            }
            activity.startActivity(android.content.Intent.createChooser(intent, "分享崩溃日志"))
        } catch (e: Throwable) {
            Toast.makeText(activity, "无法分享：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private class Row(val check: CheckBox, val delete: ImageButton, val text: TextView)
}
