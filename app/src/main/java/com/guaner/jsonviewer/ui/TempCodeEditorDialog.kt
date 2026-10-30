package com.guaner.jsonviewer.ui

import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import com.guaner.jsonviewer.databinding.DialogTempEditorBinding
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 临时代码编辑器：值详情弹窗里遇到大文本时用它编辑，而不是把用户甩到代码页去改 JSON 原文。
 *
 * 和代码页用的是同一个 [CodeEditorView]：语法高亮、行号、软换行、双指缩放、
 * 长按选择复制都一致，另外带一个值内搜索栏。
 *
 * 关键差别：这里编辑的是**值的文本本身**，不是 JSON 原文。所以字符串里要换行
 * 直接敲回车就行，保存时由 ValueDraft 自动转义成 `\n`——不用手工敲反斜杠 n。
 * 保存结果回填到值详情弹窗的编辑框，用户确认后再落盘到 JSON。
 */
object TempCodeEditorDialog {

    private const val MAX_MATCHES = 5_000
    private const val SEARCH_DEBOUNCE_MS = 180L

    fun show(activity: Activity, title: String, initial: String, onSave: (String) -> Unit) {
        val colors = ThemeManager.activeColors
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val b = DialogTempEditorBinding.inflate(LayoutInflater.from(activity), null, false)

        // ---------- 主题 ----------
        b.tempRoot.setBackgroundColor(colors.surface)
        b.tempTitle.setTextColor(colors.text)
        b.tempTitle.text = title
        b.tempHint.setTextColor(colors.gutterText)
        b.tempSearchButton.setTextColor(colors.accent)
        b.tempCopyAllButton.setTextColor(colors.accent)
        b.tempSaveButton.setTextColor(colors.accent)
        b.tempSearchRow.setBackgroundColor(colors.gutter)
        b.tempSearchInput.setTextColor(colors.text)
        b.tempSearchInput.setHintTextColor(colors.gutterText)
        b.tempSearchCount.setTextColor(colors.gutterText)
        val tint = ColorStateList.valueOf(colors.accent)
        b.tempSearchPrev.imageTintList = tint
        b.tempSearchNext.imageTintList = tint
        b.tempSearchClose.imageTintList = tint

        b.tempEditor.applyThemeFromManager()
        // 全屏编辑时不需要「边缘滑动打开大纲」，关掉以免吃掉边缘的文本选择手势
        b.tempEditor.edgeSwipeEnabled = false
        b.tempEditor.setTextProgrammatic(initial)

        // ---------- 搜索 ----------
        val handler = Handler(Looper.getMainLooper())
        var matches: List<IntRange> = emptyList()
        var index = -1

        fun updateLabel() {
            b.tempSearchCount.text = when {
                matches.isEmpty() -> "0/0"
                matches.size >= MAX_MATCHES -> "${index + 1}/$MAX_MATCHES+"
                else -> "${index + 1}/${matches.size}"
            }
        }

        fun runSearch() {
            val q = b.tempSearchInput.text?.toString().orEmpty()
            if (q.isEmpty()) {
                matches = emptyList()
                index = -1
                updateLabel()
                b.tempEditor.setSearchMatches(emptyList(), -1)
                return
            }
            // 用 CharSequence 扫描，避免大文本每次按键都 toString 全量拷贝
            val text = b.tempEditor.textSequence
            val found = ArrayList<IntRange>()
            var i = text.indexOf(q, ignoreCase = true)
            while (i >= 0 && found.size < MAX_MATCHES) {
                found.add(i until i + q.length)
                i = text.indexOf(q, i + q.length, ignoreCase = true)
            }
            matches = found
            index = if (found.isEmpty()) -1 else 0
            updateLabel()
            b.tempEditor.setSearchMatches(found, index)
            // takeFocus=false：只滚动定位，不把焦点从搜索框抢走，否则输入一个字就断
            if (index >= 0) b.tempEditor.jumpTo(found[0].first, takeFocus = false)
        }

        val searchRunnable = Runnable { runSearch() }
        b.tempSearchInput.addTextChangedListener(afterTextChanged = {
            handler.removeCallbacks(searchRunnable)
            handler.postDelayed(searchRunnable, SEARCH_DEBOUNCE_MS)
        })
        b.tempSearchInput.setOnEditorActionListener { _, _, _ ->
            if (matches.isNotEmpty()) {
                index = (index + 1) % matches.size
                updateLabel()
                b.tempEditor.setSearchMatches(matches, index)
                b.tempEditor.jumpTo(matches[index].first, takeFocus = false)
            }
            true
        }

        fun closeSearch() {
            handler.removeCallbacks(searchRunnable)
            b.tempSearchInput.setText("")
            b.tempSearchRow.visibility = View.GONE
            b.tempEditor.setSearchMatches(emptyList(), -1)
            matches = emptyList()
            index = -1
            b.tempSearchCount.text = "0/0"
        }

        b.tempSearchButton.setOnClickListener {
            if (b.tempSearchRow.visibility == View.VISIBLE) {
                closeSearch()
            } else {
                b.tempSearchRow.visibility = View.VISIBLE
                b.tempSearchInput.requestFocus()
                (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.showSoftInput(b.tempSearchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        b.tempSearchClose.setOnClickListener { closeSearch() }
        b.tempSearchNext.setOnClickListener {
            if (matches.isEmpty()) return@setOnClickListener
            index = (index + 1) % matches.size
            updateLabel()
            b.tempEditor.setSearchMatches(matches, index)
            b.tempEditor.jumpTo(matches[index].first, takeFocus = false)
        }
        b.tempSearchPrev.setOnClickListener {
            if (matches.isEmpty()) return@setOnClickListener
            index = (index - 1 + matches.size) % matches.size
            updateLabel()
            b.tempEditor.setSearchMatches(matches, index)
            b.tempEditor.jumpTo(matches[index].first, takeFocus = false)
        }

        // ---------- 复制全部 ----------
        b.tempCopyAllButton.setOnClickListener {
            val text = b.tempEditor.text
            if (text.isEmpty()) {
                toast(activity, "没有可复制的内容")
                return@setOnClickListener
            }
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("值", text))
            toast(activity, "已复制 ${text.length} 字符")
        }

        // ---------- 保存 / 退出 ----------
        b.tempSaveButton.setOnClickListener {
            val text = b.tempEditor.text
            onSave(text)
            dialog.dismiss()
        }

        fun hasChanges(): Boolean = b.tempEditor.text != initial

        fun requestClose() {
            handler.removeCallbacks(searchRunnable)
            if (!hasChanges()) {
                dialog.dismiss()
                return
            }
            ThemedDialog.confirm(
                activity,
                "放弃修改？",
                "这里的改动还没有保存到值编辑框。",
                positive = "放弃修改",
                negative = "继续编辑",
                danger = true
            ) { dialog.dismiss() }
        }

        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                requestClose()
                true
            } else {
                false
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(searchRunnable) }

        dialog.setContentView(b.root)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(colors.surface))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        b.tempEditor.requestEditorFocus()
    }

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
    }
}
