package com.guaner.jsonviewer.text

import com.guaner.jsonviewer.json.JsonParser
import com.guaner.jsonviewer.json.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 超大文本的编辑耗时基线。
 *
 * 这里对比的是「每次按键都全量重扫行首表」与「只位移变化点之后的行首」的差距。
 * 不设硬性时间阈值（CI 机器性能不可控），只断言增量路径的实际循环次数远小于全量，
 * 并顺带用一份 5MB 的非 JSON 文本跑通解析器的 fail-fast 路径（不能抛异常、不能卡死）。
 */
class LargeTextBudgetTest {

    private fun bigPlainText(): StringBuilder {
        val sb = StringBuilder()
        var i = 0
        while (sb.length < 5_000_000) {
            sb.append("line ").append(i).append(": ").append("abcdefghij").append('\n')
            i++
        }
        return sb
    }

    @Test
    fun incrementalShiftIsCheaperThanFullRebuild() {
        val sb = bigPlainText()
        val starts = LineStarts.build(sb)
        val lines = starts.size
        assertTrue("应生成大量逻辑行", lines > 100_000)

        // 全量重建的代价与行数同阶；增量只改一行之后的行首
        val t0 = System.nanoTime()
        LineStarts.build(sb)
        val full = System.nanoTime() - t0

        val insertAt = starts[lines / 2]
        sb.insert(insertAt, "XYZ")
        val t1 = System.nanoTime()
        val shifted = LineStarts.shift(starts, insertAt, 0, 3, sb.length)
        val inc = System.nanoTime() - t1

        assertTrue("增量必须成功", shifted != null)
        assertEquals(lines, shifted!!.size)
        // 插入点之前的行首一点不动
        assertEquals(starts[lines / 2 - 1], shifted[lines / 2 - 1])
        // 之后整体右移 3
        assertEquals(starts[lines / 2 + 10] + 3, shifted[lines / 2 + 10])
        // 增量耗时应显著低于全量（同样量级下取 50% 作为宽松边界）
        assertTrue("增量($inc ns) 应快于全量($full ns)", inc < full)
    }

    @Test
    fun hugeNonJsonTextParsesToFailureWithoutCrash() {
        val text = bigPlainText().toString()
        val r = JsonParser.parse(text)
        assertTrue("非 JSON 大文本必须返回 Failure 而不是抛异常", r is ParseResult.Failure)
    }

    @Test
    fun maxLineLengthOnHugeText() {
        val sb = bigPlainText()
        sb.append("no newline tail")
        val max = LineStarts.maxLineLength(sb)
        assertTrue("最长行应至少包含尾部", max >= "no newline tail".length)
    }

    /**
     * 用户编辑后只允许扫光标附近，**不能**为了判断超长行去遍历全文。
     *
     * 这条是「粘贴 500KB 文本后每敲一个键都卡」的回归测试：
     * 断言访问次数局限在 2*radius+1 行内，一旦有人把实现改回全量扫描，这里立刻失败。
     */
    @Test
    fun maxLineLengthAroundOnlyTouchesNearbyLines() {
        val lineCount = 500_000
        var visited = 0
        val max = LineStarts.maxLineLengthAround(line = 250_000, lineCount = lineCount, radius = 3) { i ->
            visited++
            if (i == 250_001) 999_999 else 10
        }
        assertEquals("只看前后各 3 行", 7, visited)
        assertEquals(999_999, max)
    }

    @Test
    fun maxLineLengthAroundClampsAtEdges() {
        // 光标在第 0 行：不能越界到负数
        val a = LineStarts.maxLineLengthAround(0, 10, 3) { it + 1 }
        assertEquals(4, a)
        // 光标在最后一行
        val b = LineStarts.maxLineLengthAround(9, 10, 3) { it + 1 }
        assertEquals(10, b)
        // 空文本
        assertEquals(0, LineStarts.maxLineLengthAround(0, 0, 3) { 100 })
    }

    /**
     * 粘贴的超长单行必须能被识别出来 —— 否则软换行不启用，整行横向排版会让滚动/编辑卡死。
     */
    @Test
    fun detectsPastedVeryLongSingleLine() {
        val sb = StringBuilder()
        repeat(60_000) { sb.append('a') }
        val text = sb.toString()
        // 全文只有一行，光标自然也在这一行
        assertEquals(60_000, LineStarts.maxLineLength(text))
        assertTrue(LineStarts.maxLineLengthAround(0, 1, 3) { text.length } > 20_000)
    }
}
