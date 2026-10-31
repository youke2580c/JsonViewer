package com.guaner.jsonviewer.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.styling.Spans
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分析器的端到端测试：模拟编辑器「建 Content → reset → 下发 Styles」的完整链路。
 *
 * 这组测试是为了锁死一个真实出过的 bug：分析器缓存了 `Content` 对象本身，
 * 而编辑器 `setText()` 会整个换掉 Content 实例，导致旧引用脱离编辑器、
 * 着色与下游（表单、大纲）全部拿不到内容。
 */
class JsonAnalyzeManagerTest {

    /** 收集分析器下发的样式 */
    private class Collector : StyleReceiver {
        var styles: Styles? = null
        var stylesCount = 0
        var diagnostics: DiagnosticsContainer? = null

        override fun setStyles(source: AnalyzeManager, styles: Styles?) {
            this.styles = styles
            stylesCount++
        }

        override fun setStyles(source: AnalyzeManager, styles: Styles?, commit: Runnable?) {
            this.styles = styles
            stylesCount++
            commit?.run()
        }

        override fun setDiagnostics(source: AnalyzeManager, diagnostics: DiagnosticsContainer?) {
            this.diagnostics = diagnostics
        }

        override fun updateBracketProvider(source: AnalyzeManager, provider: BracketsProvider?) {
            // 本语言不提供括号配对
        }
    }

    private fun contentOf(text: String): Content {
        val c = Content()
        c.insert(0, 0, text)
        return c
    }

    /** 按行统计 span 数量：0 表示这一行没有任何着色 */
    private fun spanCountByLine(styles: Styles?, lineCount: Int): List<Int> {
        val spans = styles?.spans ?: return List(lineCount) { 0 }
        val reader = spans.read()
        val out = ArrayList<Int>(lineCount)
        for (line in 0 until lineCount) {
            reader.moveToLine(line)
            out.add(reader.getSpanCount())
        }
        return out
    }

    private val sample = """
        {
          "name": "demo",
          "n": 12,
          "ok": true,
          "nil": null,
          "arr": [1, 2, 3]
        }
    """.trimIndent()

    @Test
    fun `reset produces spans for every json line`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)

        val content = contentOf(sample)
        manager.reset(ContentReference(content), Bundle())

        assertNotNull("reset 后应下发 Styles", collector.styles)
        val counts = spanCountByLine(collector.styles, content.lineCount)
        assertEquals(content.lineCount, counts.size)
        // 第一行 '{'、最后一行 '}' 至少各有一个标点 span
        assertTrue("首行应有高亮，实际 $counts", counts.first() > 0)
        // 中间的键值行必然有 key / punct / value
        assertTrue("键值行应有高亮，实际 $counts", counts[1] >= 3)
    }

    @Test
    fun `content replaced by editor still yields spans`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)

        // 第一次：短内容
        manager.reset(ContentReference(contentOf("""{"a":1}""")), Bundle())
        val first = collector.stylesCount
        assertTrue(first > 0)

        // 编辑器 setText() 会换成全新的 Content，并再次 reset（这是真实行为）
        val second = contentOf(sample)
        manager.reset(ContentReference(second), Bundle())

        val counts = spanCountByLine(collector.styles, second.lineCount)
        assertEquals("行数必须跟随新内容", second.lineCount, counts.size)
        assertTrue("替换内容后必须仍有高亮，实际 $counts", counts.all { it > 0 })
    }

    @Test
    fun `notifyContentReplaced keeps styles available`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)

        val content = contentOf(sample)
        manager.reset(ContentReference(content), Bundle())
        val before = collector.stylesCount

        lang.notifyContentReplaced()

        assertTrue("整篇替换通知后应重新下发 Styles", collector.stylesCount > before)
        val counts = spanCountByLine(collector.styles, content.lineCount)
        assertTrue("重新下发后仍应有高亮，实际 $counts", counts[1] >= 3)
    }

    @Test
    fun `insert and delete refresh affected lines`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)

        val content = contentOf("{\n  \"a\": 1\n}")
        manager.reset(ContentReference(content), Bundle())

        // 在第二行末尾插入一个逗号（模拟用户输入）
        content.insert(1, content.getLineString(1).length, ",")
        manager.insert(
            CharPosition(1, 0, 0),
            CharPosition(1, content.getLineString(1).length, 0),
            ","
        )

        val counts = spanCountByLine(collector.styles, content.lineCount)
        assertTrue("编辑后该行仍应有高亮，实际 $counts", counts[1] >= 3)
    }

    @Test
    fun `empty content does not crash and yields empty styles`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)

        manager.reset(ContentReference(Content()), Bundle())
        assertNotNull(collector.styles)
    }

    @Test
    fun `destroy detaches receiver safely`() {
        val lang = JsonSpansLanguage()
        val manager = lang.analyzeManager
        val collector = Collector()
        manager.setReceiver(collector)
        manager.reset(ContentReference(contentOf(sample)), Bundle())
        manager.destroy()
        // 销毁后再次触发不应抛异常
        lang.notifyContentReplaced()
    }
}
