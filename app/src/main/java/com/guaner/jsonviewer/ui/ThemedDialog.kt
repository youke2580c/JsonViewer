package com.guaner.jsonviewer.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 跟随运行时主题的自定义弹窗。
 *
 * 系统 AlertDialog 的背景/文字来自主题资源，应用内换肤（ThemeManager 运行时改色）
 * 不会生效，会出现「深色弹窗配浅色 App」。这里所有元素都用 ThemeManager.activeColors
 * 现场绘制：背景 surface + 描边、标题 text、说明 gutterText、主按钮 accent。
 */
object ThemedDialog {

    /** 危险操作文字色：跟随主题、且在深浅背景上都清晰可读 */
    fun dangerColor(): Int {
        val colors = ThemeManager.activeColors
        return ThemeManager.mix(colors.errorColumn, colors.text, 0.4f)
    }

    fun dp(context: Context, v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    fun create(context: Context): Dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawableResource(android.R.color.transparent)
    }

    fun background(context: Context): GradientDrawable {
        val colors = ThemeManager.activeColors
        return GradientDrawable().apply {
            cornerRadius = 18 * context.resources.displayMetrics.density
            setColor(colors.surface)
            setStroke(dp(context, 1).coerceAtLeast(1), colors.gutter)
        }
    }

    fun container(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 10))
        background = background(context)
    }

    fun title(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 17f
        setTextColor(ThemeManager.activeColors.text)
        setTypeface(typeface, Typeface.BOLD)
    }

    fun body(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 13f
        setTextColor(ThemeManager.activeColors.gutterText)
        setLineSpacing(dp(context, 3).toFloat(), 1f)
    }

    /** 纯文字按钮：主操作 accent、危险操作 errorLine，均无底色 */
    fun button(
        context: Context,
        text: String,
        color: Int,
        weight: Float = 1f,
        onClick: () -> Unit
    ): TextView = TextView(context).apply {
        this.text = text
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(color)
        setPadding(dp(context, 4), dp(context, 12), dp(context, 4), dp(context, 12))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    }

    fun show(dialog: Dialog, content: View, widthDp: Int = 320) {
        // Activity 已在销毁 / 已保存状态时 show 会抛 BadTokenException，直接放弃
        val ctx = dialog.context
        if (ctx is android.app.Activity && (ctx.isFinishing || ctx.isDestroyed)) return
        try {
            dialog.setContentView(content)
            dialog.show()
            val width = dp(dialog.context, widthDp)
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        } catch (e: Throwable) {
            // 窗口令牌失效等极端情况：关掉即可，不能让弹窗把进程带走
            try {
                dialog.dismiss()
            } catch (_: Throwable) {
            }
        }
    }

    /** 通用确认弹窗 */
    fun confirm(
        context: Context,
        title: String,
        message: String,
        positive: String = "确定",
        negative: String = "取消",
        danger: Boolean = false,
        neutral: String? = null,
        onNeutral: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        onConfirm: () -> Unit
    ) {
        val colors = ThemeManager.activeColors
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))
        box.addView(body(context, message), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 10) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 4), 0, 0)
        }
        row.addView(button(context, negative, colors.gutterText) {
            dialog.dismiss()
            onNegative?.invoke()
        })
        if (neutral != null) {
            row.addView(button(context, neutral, colors.gutterText) {
                dialog.dismiss()
                onNeutral?.invoke()
            })
        }
        row.addView(button(context, positive, if (danger) dangerColor() else colors.accent) {
            dialog.dismiss()
            onConfirm()
        })
        box.addView(row)
        show(dialog, box)
    }

    /**
     * 输入弹窗；[onConfirm] 返回 false 时弹窗不关闭（用于范围校验）。
     */    fun input(
        context: Context,
        title: String,
        initial: String,
        positive: String = "保存",
        numeric: Boolean = false,
        onConfirm: (String) -> Boolean
    ) {
        val colors = ThemeManager.activeColors
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))
        val input = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            textSize = 14f
            setTextColor(colors.text)
            backgroundTintList = ColorStateList.valueOf(colors.accent)
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER
        }
        box.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 8) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 4), 0, 0)
        }
        row.addView(button(context, "取消", colors.gutterText) { dialog.dismiss() })
        row.addView(button(context, positive, colors.accent) {
            if (onConfirm(input.text?.toString().orEmpty())) dialog.dismiss()
        })
        box.addView(row)
        show(dialog, box)
        input.requestFocus()
    }

    /**
     * 双输入弹窗（例如「名称 + UA 值」）。
     * [onConfirm] 返回 false 时不关闭弹窗，便于就地提示并让用户修改。
     */
    fun inputTwo(
        context: Context,
        title: String,
        label1: String,
        hint1: String,
        initial1: String = "",
        label2: String,
        hint2: String,
        initial2: String = "",
        positive: String = "保存",
        onConfirm: (String, String) -> Boolean
    ) {
        val colors = ThemeManager.activeColors
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))

        fun field(label: String, hintText: String, initial: String): EditText {
            box.addView(body(context, label).apply { textSize = 12f }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(context, 10) })
            return EditText(context).apply {
                setText(initial)
                setSelection(initial.length)
                hint = hintText
                textSize = 13f
                setTextColor(colors.text)
                setHintTextColor(ThemeManager.mix(colors.gutterText, colors.surface, 0.4f))
                backgroundTintList = ColorStateList.valueOf(colors.accent)
            }.also { edit ->
                box.addView(edit, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ))
            }
        }

        val first = field(label1, hint1, initial1)
        val second = field(label2, hint2, initial2)

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 6), 0, 0)
        }
        row.addView(button(context, "取消", colors.gutterText) { dialog.dismiss() })
        row.addView(button(context, positive, colors.accent) {
            val ok = onConfirm(
                first.text?.toString()?.trim().orEmpty(),
                second.text?.toString()?.trim().orEmpty()
            )
            if (ok) dialog.dismiss()
        })
        box.addView(row)
        show(dialog, box, 330)
        first.requestFocus()
    }

    /**
     * 多行输入弹窗（粘贴 JSON、请求头等）。
     * [onConfirm] 返回 false 时不关闭弹窗，便于在框内直接修正。
     */
    fun multiline(
        context: Context,
        title: String,
        hint: String,
        initial: String = "",
        positive: String = "填入",
        onConfirm: (String) -> Boolean
    ) {
        val colors = ThemeManager.activeColors
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))
        box.addView(body(context, hint), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 6) })

        val input = EditText(context).apply {
            setText(initial)
            setSelection(initial.length)
            this.hint = "{\n  \"Referer\": \"https://example.com\"\n}"
            textSize = 13f
            setTextColor(colors.text)
            setHintTextColor(ThemeManager.mix(colors.gutterText, colors.surface, 0.4f))
            typeface = Typeface.MONOSPACE
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 5
            maxLines = 9
            setPadding(dp(context, 10), dp(context, 8), dp(context, 10), dp(context, 8))
            background = GradientDrawable().apply {
                cornerRadius = 10 * context.resources.displayMetrics.density
                setColor(colors.background)
                setStroke(dp(context, 1).coerceAtLeast(1), colors.gutter)
            }
        }
        box.addView(input, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 8) })

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(context, 4), 0, 0)
        }
        row.addView(button(context, "取消", colors.gutterText) { dialog.dismiss() })
        row.addView(button(context, positive, colors.accent) {
            if (onConfirm(input.text?.toString().orEmpty())) dialog.dismiss()
        })
        box.addView(row)
        show(dialog, box, 340)
        input.requestFocus()
    }

    /** 信息弹窗：只有标题、正文和一个「知道了」按钮 */
    fun message(context: Context, title: String, text: String) {
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))
        box.addView(body(context, text), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(context, 10) })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(button(context, "知道了", ThemeManager.activeColors.accent) { dialog.dismiss() })
        box.addView(row)
        show(dialog, box, 320)
    }

    /** 单选列表弹窗（替代 AlertDialog.setItems），每条一行、文字跟随主题 */
    fun options(context: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val colors = ThemeManager.activeColors
        val dialog = create(context)
        val box = container(context)
        box.addView(title(context, title))
        items.forEachIndexed { index, label ->
            box.addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(colors.text)
                setPadding(0, dp(context, 14), 0, dp(context, 14))
                setOnClickListener {
                    dialog.dismiss()
                    onPick(index)
                }
            })
        }
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(button(context, "取消", colors.gutterText) { dialog.dismiss() })
        box.addView(row)
        show(dialog, box, 320)
    }
}
