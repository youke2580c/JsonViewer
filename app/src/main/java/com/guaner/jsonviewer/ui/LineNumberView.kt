package com.guaner.jsonviewer.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 轻量行号栏。
 *
 * 旧实现是一个与全文等高的 TextView（几万行时测量/布局/滚动同步都极重），
 * 这里改为「只绘制当前可视行」的自绘 View：文字来自 EditText 的 [Layout]，
 * 行号取自预计算的逻辑行起始偏移表（一次 O(n) 扫描，支持自动换行时只标逻辑行首）。
 */
class LineNumberView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density

    /** 逻辑行起始偏移（升序）：命中说明该可视行是逻辑行首，需要画行号 */
    var logicalStarts: IntArray = intArrayOf(0)
        set(value) {
            field = value
            invalidate()
        }

    var textSizeSp: Float = 13f
        set(value) {
            field = value
            invalidate()
        }

    var textTypeface: Typeface = Typeface.MONOSPACE
        set(value) {
            field = value
            invalidate()
        }

    var gutterTextColor: Int = ThemeManager.activeColors.gutterText
        set(value) {
            field = value
            invalidate()
        }

    var layoutProvider: (() -> Layout?)? = null
    var scrollProvider: (() -> Int)? = null
    var topInsetProvider: (() -> Int)? = null

    private val padEnd = 6 * density

    override fun onDraw(canvas: Canvas) {
        val layout = layoutProvider?.invoke() ?: return
        if (layout.lineCount <= 0) return
        val scrollY = scrollProvider?.invoke() ?: 0
        val topInset = topInsetProvider?.invoke() ?: 0

        applyPaint()
        val first = layout.getLineForVertical((scrollY - topInset).coerceAtLeast(0))
        val last = layout.getLineForVertical(scrollY - topInset + height)
        val baseX = width - padEnd
        for (v in first..last) {
            val idx = indexOfStart(layout.getLineStart(v))
            if (idx < 0) continue
            val baseline = (topInset + layout.getLineBaseline(v) - scrollY).toFloat()
            if (baseline < -paint.textSize || baseline > height + paint.textSize) continue
            val label = (idx + 1).toString()
            canvas.drawText(label, baseX - paint.measureText(label), baseline, paint)
        }
    }

    /** 数字列宽（用于计算行号栏宽度），digits 为最大行号的位数 */
    fun digitsWidth(digits: Int): Float {
        applyPaint()
        val n = digits.coerceAtLeast(1)
        return paint.measureText("0".repeat(n))
    }

    private fun applyPaint() {
        paint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, textSizeSp, resources.displayMetrics
        )
        paint.typeface = textTypeface
        paint.color = gutterTextColor
    }

    private fun indexOfStart(offset: Int): Int {
        val arr = logicalStarts
        if (arr.isEmpty()) return -1
        var lo = 0
        var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = arr[mid]
            when {
                v == offset -> return mid
                v < offset -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }
}
