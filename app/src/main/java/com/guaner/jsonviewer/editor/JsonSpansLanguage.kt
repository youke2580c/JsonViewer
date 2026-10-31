package com.guaner.jsonviewer.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.SequenceUpdateRange
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.text.TextRange
import io.github.rosemoe.sora.widget.SymbolPairMatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min

/** 语法 token 的样式优先级（同列冲突时低于跳转高亮） */
private const val PRIORITY_TOKEN = 0

/** 跳转高亮在行内的优先级（同列时压过语法 token） */
private const val PRIORITY_FLASH = 1

/**
 * 超过这个长度的行**不再逐 token 着色**（整行只留默认文字色）。
 *
 * 压缩过的 JSON 常常是「一行几十万字符」，逐 token 扫完还要产出上万个 `Span`，
 * 而每多一个 `Span`，Sora 绘制该行时就要多切一段来量宽度。屏幕上本来也只看得见
 * 其中一小段，着色收益为零。
 */
private const val COLORLESS_LINE_CHARS = 4_000

/** 该长度（列数）的行是否还逐 token 着色；0 列和超长行都不着色 */
internal fun shouldColorLine(columns: Int): Boolean = columns in 1..COLORLESS_LINE_CHARS

/** 语法着色种类，映射到编辑器配色方案里的颜色槽位 */
enum class TokenKind { KEY, STRING, NUMBER, LITERAL, PUNCT }

/** 合并两组行号并去重排序：换高亮时旧行要刷回普通样式，新行要上色 */
private fun mergeLines(a: IntArray, b: IntArray): IntArray {
    if (a.isEmpty()) return b
    if (b.isEmpty()) return a
    val all = IntArray(a.size + b.size)
    a.copyInto(all)
    b.copyInto(all, a.size)
    all.sort()
    var n = 0
    for (i in all.indices) {
        if (n == 0 || all[i] != all[n - 1]) all[n++] = all[i]
    }
    return all.copyOf(n)
}

/**
 * 面向整篇 JSON 的**逐行增量**着色语言。
 *
 * 两个性能约束叠在一起，决定了这里的写法：
 *
 * 1. `CodeEditor.setStyles()` 是**全篇**替换 —— 每次按键都重建一遍所有行的 span，
 *    造价跟行数成正比。五万行文档每敲一个字就要分配几十万个 `Span`，
 *    键盘直接卡死。所以这里只在「换文档 / 行数变化」时全量构建一次，
 *    平时编辑走 `updateStyles(styles, SequenceUpdateRange)` 只改受影响的那几行。
 * 2. 分词按行缓存：一次编辑只让受影响的几行失效，重扫成本与文档规模无关。
 */
class JsonSpansLanguage(
    private val maxTokensPerLine: Int = 4096
) : Language {

    private val manager = LinesAnalyzeManager()

    override fun getAnalyzeManager(): AnalyzeManager = manager

    // 允许轻微中断：输入法组词期间也能保证输入框跟手
    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_SLIGHT

    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {
        // JSON 不需要补全
    }

    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int = 0

    override fun useTab(): Boolean = false

    override fun getFormatter(): Formatter = NoOpFormatter

    override fun getSymbolPairs(): SymbolPairMatch? = null

    override fun getNewlineHandlers(): Array<NewlineHandler>? = null

    override fun destroy() {
        manager.destroy()
    }

    /**
     * 通知「整篇内容被替换」。
     *
     * `CodeEditor.setText()` 会新建 Content 实例，此前的行缓存与样式表必然全部失效；
     * 主动清空可避免拿旧行号去读新内容（行数变少时会越界，行数变多时会漏着色）。
     */
    fun notifyContentReplaced() {
        manager.invalidateAll()
    }

    /**
     * 设置「跳转高亮」区间（全局字符下标，闭区间语义 `[start, end)`）。
     *
     * 传 `-1, -1` 表示清除。设置后只重算涉及的行：
     * - 起始行到结束行整行铺浅色背景（`ColorIds.FLASH_LINE`）
     * - 目标区间本身再叠一层更重的背景（`ColorIds.FLASH_WORD`）
     *
     * 之所以走样式通道而不是诊断或自绘：`Span` 的背景由 Sora 排版层直接绘制，
     * 和搜索命中、语法着色完全同一条路，软换行 / Tab / 任何字体都不会算歪。
     */
    fun setFlashRange(start: Int, end: Int) {
        manager.applyFlashRange(start, end)
    }

    /** 单行分词结果（行内相对列号） */
    class Token(val start: Int, val end: Int, val kind: TokenKind)

    /**
     * 行内待下发的一段样式。
     *
     * [priority] 只在**同列冲突**时用于决定谁留下：高亮 > 语法 token。
     * 否则字段名的起点恰好压在某个 token 上时，高亮会被去重丢掉、整块不显示。
     */
    private class LineSpan(val column: Int, val style: Long, val priority: Int)

    /** 高亮落在某一行内的列区间（行内相对列号，`[start, end)`） */
    private class LineFlash(
        val wordStart: Int,
        val wordEnd: Int,
        val lineEnd: Int
    )

    private inner class LinesAnalyzeManager : AnalyzeManager {

        private var receiver: StyleReceiver? = null

        /**
         * 编辑器传进来的内容引用。
         *
         * **不能缓存 `Content` 对象本身**：`CodeEditor.setText()` 会整个换掉内部的 Content 实例，
         * 旧引用随即脱离编辑器，之后所有行读取都拿不到新内容 —— 表现就是「着色、表单、大纲全空」。
         * 这里只保存 `ContentReference`，每次派发时重新取 `reference`。
         */
        private var reference: ContentReference? = null

        /** 行级 token 缓存，null 表示待重新计算 */
        private var cache: Array<ArrayList<Token>?> = emptyArray()
        private val lock = ReentrantLock()

        /** 当前下发给编辑器的样式表；增量更新时原地修改它内部的 span 表 */
        private var styles: Styles? = null
        private var mapped: MappedSpans? = null

        /** 跳转高亮区间（全局字符下标，`[flashStart, flashEnd)`）；-1 表示无 */
        private var flashStart = -1
        private var flashEnd = -1

        /** 上一次高亮涉及的行，换高亮时要先把它们刷回普通样式 */
        private var flashLines: IntArray = IntArray(0)

        fun applyFlashRange(start: Int, end: Int) {
            val c = contentOrNull()
            lock.withLock {
                flashStart = start
                flashEnd = end
            }
            if (c == null) {
                invalidateAll()
                return
            }
            val next = resolveFlash(c)?.keys?.toIntArray() ?: IntArray(0)
            val old = flashLines
            flashLines = next
            updateLines(mergeLines(old, next))
        }

        override fun setReceiver(receiver: StyleReceiver?) {
            this.receiver = receiver
        }

        override fun reset(content: ContentReference, extraArguments: Bundle) {
            lock.withLock {
                reference = content
                cache = arrayOfNulls(lineCountOf())
                flashLines = IntArray(0)
            }
            fullDispatch()
            dispatchBrackets()
        }

        override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
            onEdit(start.line, end.line)
        }

        override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
            onEdit(start.line, end.line)
        }

        override fun rerun() {
            lock.withLock { cache = arrayOfNulls(lineCountOf()) }
            fullDispatch()
        }

        /** 整篇替换：丢弃全部行缓存并按新内容重排，然后重新下发着色 */
        fun invalidateAll() {
            lock.withLock {
                // reference 由编辑器在 reset() 时更新；若编辑器换了 Content 而没走 reset，
                // 这里也拿不到新对象，直接清空缓存等下一次派发重建即可。
                cache = arrayOfNulls(lineCountOf())
                flashLines = IntArray(0)
            }
            fullDispatch()
        }

        override fun destroy() {
            receiver = null
            reference = null
            styles = null
            mapped = null
            lock.withLock { cache = emptyArray() }
        }

        /**
         * 内容变化后的统一切入点。
         *
         * - 同一行内编辑（最常见）：只重算这一行，`updateStyles` 只让这一行的渲染缓存失效。
         * - 跨行编辑（回车 / 粘贴多行 / 删除选区）：行号整体位移，span 表与行缓存的对应关系
         *   需要重建，走一次全量。这类操作本来就低频。
         */
        private fun onEdit(startLine: Int, endLine: Int) {
            val c = contentOrNull() ?: return fullDispatch()
            val spanTable = mapped
            if (spanTable == null || styles == null || spanTable.lineCount != c.lineCount) {
                return fullDispatch()
            }
            if (startLine != endLine) {
                lock.withLock {
                    if (cache.size != c.lineCount) cache = arrayOfNulls(c.lineCount)
                    for (i in (startLine - 1).coerceAtLeast(0)..(endLine + 1).coerceAtMost(c.lineCount - 1)) {
                        cache[i] = null
                    }
                }
                return fullDispatch()
            }
            // 字符串可能跨行，前后各带一行一起重扫
            val lo = (startLine - 1).coerceAtLeast(0)
            val hi = (startLine + 1).coerceAtMost(c.lineCount - 1)
            lock.withLock {
                for (i in lo..hi) {
                    if (i < cache.size) cache[i] = null
                }
            }
            updateLines(IntArray(hi - lo + 1) { lo + it })
        }

        /** 当前内容（每次实时取，避免指向被替换掉的旧 Content） */
        private fun contentOrNull(): Content? = try {
            reference?.reference as? Content
        } catch (e: Throwable) {
            null
        }

        private fun lineCountOf(): Int = contentOrNull()?.lineCount ?: 0

        /**
         * 重新下发整篇样式。
         *
         * 只在换文档、行数变化、清空缓存时走这里；日常编辑走 [updateLines]。
         */
        private fun fullDispatch() {
            val r = receiver ?: return
            val c = contentOrNull() ?: return
            val lineCount = c.lineCount
            if (cache.size != lineCount) cache = arrayOfNulls(lineCount)

            val flash = resolveFlash(c)
            flashLines = flash?.keys?.toIntArray() ?: IntArray(0)

            val builder = MappedSpans.Builder(lineCount)
            for (line in 0 until lineCount) {
                for (span in lineSpans(c, line, flash?.get(line))) {
                    builder.add(line, span)
                }
                // 这一行没有内容时补一个空白 span，保证行在样式表里存在
                builder.determine(line)
            }
            builder.addNormalIfNull()
            val spanTable = builder.build()
            val newStyles = Styles(spanTable)
            mapped = spanTable
            styles = newStyles
            r.setStyles(this, newStyles)
        }

        /**
         * 只重算并下发指定的几行。
         *
         * 关键点：`MappedSpans.modify().setSpansOnLine()` 是**原地**改 span 表，
         * 配合 `updateStyles(..., SequenceUpdateRange)` 只让这几行的渲染缓存失效 ——
         * 整个过程的成本与文档总行数无关。
         */
        private fun updateLines(lines: IntArray) {
            val r = receiver ?: return
            val c = contentOrNull() ?: return
            val spanTable = mapped ?: return fullDispatch()
            val currentStyles = styles ?: return fullDispatch()
            if (spanTable.lineCount != c.lineCount) return fullDispatch()
            if (lines.isEmpty()) return

            val flash = resolveFlash(c)
            val modifier = spanTable.modify()
            var lo = Int.MAX_VALUE
            var hi = -1
            for (line in lines) {
                if (line < 0 || line >= c.lineCount) continue
                lock.withLock { if (line < cache.size) cache[line] = null }
                val spans = lineSpans(c, line, flash?.get(line))
                if (spans.isEmpty()) continue
                modifier.setSpansOnLine(line, spans)
                lo = min(lo, line)
                hi = max(hi, line)
            }
            if (hi < 0) return
            r.updateStyles(this, currentStyles, SequenceUpdateRange(lo, hi))
        }

        /**
         * 只在换文档/重建时下发一次「空括号配对」。
         *
         * 这里**不碰诊断**：语法错误标记由 `CodeEditorView.applyDiagnostics()` 负责。
         */
        private fun dispatchBrackets() {
            val r = receiver ?: return
            r.updateBracketProvider(this, BracketsProvider { _, _ -> null })
        }

        /** 生成某一行要下发的 span 列表（列号严格递增，至少一条） */
        private fun lineSpans(c: Content, line: Int, hl: LineFlash?): List<Span> {
            val cached = lock.withLock { cache.getOrNull(line) }
            val tokens: ArrayList<Token> = if (cached != null) {
                cached
            } else {
                val computed = scanLine(c, line)
                lock.withLock { if (line < cache.size) cache[line] = computed }
                computed
            }

            // 收集本行要下发的 span：先放语法 token，再插入高亮片段，最后按列排序。
            // 同一行 span 的列号必须**严格递增**，否则 Builder 抛 IllegalStateException，
            // 整篇样式下发失败（表现为完全不着色、表单大纲全空）。
            // priority 用来处理「同列冲突」：高亮必须压过语法 token，否则高亮起点
            // 恰好落在 token 上时会被去重丢掉，整块高亮就不见了。
            val spans = ArrayList<LineSpan>(tokens.size + 5)
            for (t in tokens) {
                if (t.start < 0 || t.start >= t.end) continue
                spans.add(LineSpan(t.start, styleOf(t), PRIORITY_TOKEN))
            }
            if (hl != null) {
                spans.addAll(flashSpans(hl, c.getColumnCount(line)))
            }

            spans.sortWith(compareBy({ it.column }, { -it.priority }))
            val out = ArrayList<Span>(spans.size + 1)
            var lastColumn = -1
            for (s in spans) {
                if (s.column <= lastColumn) continue
                lastColumn = s.column
                out.add(SpanFactory.obtain(s.column, s.style))
            }
            // 空行 / 未着色行也要有一条，否则 span 表里这一行会被上一行的样式填满
            if (out.isEmpty()) out.add(SpanFactory.obtain(0, NORMAL_STYLE))
            return out
        }

        /**
         * 把一行的高亮切成若干段样式（纯计算，便于单测）。
         *
         * 规则：整行铺浅色带，字段名区间叠更重的块，区间外恢复浅色带，行尾恢复无背景。
         * 当字段名块正好顶到行尾时**不再单独插一条 bandEnd 的恢复段** ——
         * 那条和块尾同列，会被去重丢掉，反而让背景蔓延到下一行。
         */
        private fun flashSpans(hl: LineFlash, lineLen: Int): List<LineSpan> {
            val bandEnd = hl.lineEnd.coerceIn(0, lineLen)
            if (bandEnd <= 0) return emptyList()
            val wordFrom = hl.wordStart.coerceIn(0, bandEnd)
            val wordTo = hl.wordEnd.coerceIn(wordFrom, bandEnd)
            val out = ArrayList<LineSpan>(4)
            out.add(LineSpan(0, FLASH_LINE_STYLE, PRIORITY_FLASH))
            if (wordTo > wordFrom) {
                out.add(LineSpan(wordFrom, FLASH_WORD_STYLE, PRIORITY_FLASH))
                if (wordTo < bandEnd) {
                    out.add(LineSpan(wordTo, FLASH_LINE_STYLE, PRIORITY_FLASH))
                }
            }
            out.add(LineSpan(bandEnd, RESTORE_STYLE, PRIORITY_FLASH))
            return out
        }

        /**
         * 把全局 [flashStart]–[flashEnd] 换算成按行索引的列区间。
         *
         * 只有高亮涉及的行才会进 map，行数可能很大时不浪费内存。
         */
        private fun resolveFlash(c: Content): Map<Int, LineFlash>? {
            val (fs, fe) = lock.withLock { flashStart to flashEnd }
            if (fs < 0 || fe <= fs) return null
            val total = c.length
            if (total == 0) return null
            val s = fs.coerceIn(0, total - 1)
            val e = fe.coerceIn(s + 1, total)
            val indexer = c.getIndexer()
            val startPos = indexer.getCharPosition(s)
            val endPos = indexer.getCharPosition(e)
            if (startPos.line > endPos.line) return null

            val out = HashMap<Int, LineFlash>(endPos.line - startPos.line + 1)
            for (line in startPos.line..endPos.line) {
                if (line < 0 || line >= c.lineCount) continue
                val lineLen = c.getColumnCount(line)
                val from = if (line == startPos.line) startPos.column else 0
                val to = if (line == endPos.line) endPos.column else lineLen
                out[line] = LineFlash(
                    wordStart = from.coerceIn(0, lineLen),
                    wordEnd = to.coerceIn(0, lineLen),
                    lineEnd = lineLen
                )
            }
            return out
        }

        /**
         * 取一行的 token。
         *
         * 两点性能约束：
         * - 超长行直接返回空（见 [COLORLESS_LINE_CHARS]）：压缩 JSON 动辄一行几百 KB，
         *   逐 token 会产生上万个 span，把 Sora 的绘制拖垮；
         * - 用 `ContentLine`（它本身是 `CharSequence`）而不是 `getLineString()`：
         *   后者每次都把整行复制成一个新 `String`，一行 500KB 就是每次输入多一次 500KB 分配。
         */
        private fun scanLine(c: Content, line: Int): ArrayList<Token> {
            val cols = try {
                c.getColumnCount(line)
            } catch (e: Throwable) {
                return ArrayList(0)
            }
            if (!shouldColorLine(cols)) return ArrayList(0)
            val seq = try {
                c.getLine(line)
            } catch (e: Throwable) {
                return ArrayList(0)
            }
            return scan(seq)
        }
    }

    /**
     * token 对应的样式值。
     *
     * 必须用 [TextStyle.makeStyle] 生成 —— 它会把颜色 ID 放进低 19 位并做范围校验。
     * 早期版本手工写 `colorId shl 32`，既越过了 FOREGROUND_BITS(低19位) 的定义范围，
     * 又把高位误置成 STRIKETHROUGH 之类的样式位，最终表现为「文字跟背景同色 + 多删除线」。
     */
    internal fun styleOf(t: Token): Long {
        val colorId = when (t.kind) {
            TokenKind.KEY -> ColorIds.KEY
            TokenKind.STRING -> ColorIds.STRING
            TokenKind.NUMBER -> ColorIds.NUMBER
            TokenKind.LITERAL -> ColorIds.LITERAL
            TokenKind.PUNCT -> ColorIds.PUNCT
        }
        return TextStyle.makeStyle(colorId)
    }

    private fun scan(line: CharSequence): ArrayList<Token> = scanLine(line, maxTokensPerLine)

    companion object {
    /**
     * 跳转「整行浅色带」的样式：文字用正常前景色 + 浅背景色。
     *
     * `makeStyle(foregroundColorId, backgroundColorId, bold, italic, strike)`——
     * 这是 Sora 官方支持的带背景写法，和搜索命中/语法着色走同一条渲染管线。
     */
    val FLASH_LINE_STYLE: Long =
        TextStyle.makeStyle(ColorIds.FLASH_TEXT, ColorIds.FLASH_LINE, false, false, false)

    /** 跳转「字段名深色块」的样式：同样带背景，但背景更重 */
    val FLASH_WORD_STYLE: Long =
        TextStyle.makeStyle(ColorIds.FLASH_TEXT, ColorIds.FLASH_WORD, false, false, false)

    /** 用于在高亮结束处把背景「关掉」（只设前景色，背景 0 = 透明） */
    val RESTORE_STYLE: Long = TextStyle.makeStyle(ColorIds.FLASH_TEXT)

    /** 无着色行用的默认样式（前景 0 = 跟随配色方案的普通文字色） */
    val NORMAL_STYLE: Long = TextStyle.makeStyle(0)

    /**
     * 单行分词：规则与旧的 JsonHighlighter 一致（键 = 紧跟冒号的字符串）。
     *
     * 参数用 `CharSequence` 是为了能直接扫 `ContentLine`，省掉一次整行复制。
     */
    fun scanLine(line: CharSequence, maxTokens: Int = 4096): ArrayList<Token> {
        val out = ArrayList<Token>(16)
        val n = line.length
        var i = 0
        while (i < n && out.size < maxTokens) {
            val ch = line[i]
            when {
                ch == '"' -> {
                    val start = i
                    i++
                    var escaped = false
                    while (i < n) {
                        val c = line[i]
                        if (escaped) {
                            escaped = false
                        } else if (c == '\\') {
                            escaped = true
                        } else if (c == '"') {
                            break
                        }
                        i++
                    }
                    if (i < n) i++
                    var j = i
                    while (j < n && (line[j] == ' ' || line[j] == '\t')) j++
                    val kind = if (j < n && line[j] == ':') TokenKind.KEY else TokenKind.STRING
                    out.add(Token(start, i, kind))
                }
                ch == '-' || ch in '0'..'9' -> {
                    val start = i
                    i++
                    while (i < n) {
                        val c = line[i]
                        val expSign = (c == '+' || c == '-') &&
                            (line[i - 1] == 'e' || line[i - 1] == 'E')
                        if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || expSign) i++ else break
                    }
                    out.add(Token(start, i, TokenKind.NUMBER))
                }
                ch == '{' || ch == '}' || ch == '[' || ch == ']' || ch == ',' || ch == ':' -> {
                    out.add(Token(i, i + 1, TokenKind.PUNCT))
                    i++
                }
                startsWithAt(line, i, "true") -> {
                    out.add(Token(i, i + 4, TokenKind.LITERAL))
                    i += 4
                }
                startsWithAt(line, i, "false") -> {
                    out.add(Token(i, i + 5, TokenKind.LITERAL))
                    i += 5
                }
                startsWithAt(line, i, "null") -> {
                    out.add(Token(i, i + 4, TokenKind.LITERAL))
                    i += 4
                }
                else -> i++
            }
        }
        return out
    }

    /** `CharSequence` 没有 `startsWith(prefix, offset)`，这里补一个等价的 */
    private fun startsWithAt(seq: CharSequence, offset: Int, prefix: String): Boolean {
        if (offset + prefix.length > seq.length) return false
        for (i in prefix.indices) {
            if (seq[offset + i] != prefix[i]) return false
        }
        return true
    }
    }

    /** 什么都不做的格式化器：只为了让语言接口完整，本应用不提供格式化 */
    private object NoOpFormatter : Formatter {
        override fun format(text: Content, cursorRange: TextRange) = Unit
        override fun formatRegion(text: Content, cursorRange: TextRange, rangeToFormat: TextRange) = Unit
        override fun setReceiver(receiver: Formatter.FormatResultReceiver?) = Unit
        override fun isRunning(): Boolean = false
        override fun destroy() = Unit
    }

    /**
     * 配色方案里的颜色槽位编号。
     *
     * 约束（来自 Sora）：
     * - 颜色 ID 必须是 **0..524287**（19 位）；
     * - 内置配色只用到 `EditorColorScheme.END_COLOR_ID`(=66)，自定义槽位要排在它之后，
     *   否则会和内置槽位撞号；
     * - `getColor(id)` 对没设置过的 ID 返回默认色，表现出来就是「文字和背景一个色」。
     *   因此这几个 ID 必须由 [JsonEditorTheme] 全部显式设置。
     */
    object ColorIds {
        /** 起始槽位：紧跟内置槽位之后 */
        const val BASE = 67
        const val KEY = BASE
        const val STRING = BASE + 1
        const val NUMBER = BASE + 2
        const val LITERAL = BASE + 3
        const val PUNCT = BASE + 4

        /**
         * 跳转高亮用的两个背景槽位。
         *
         * 背景色也必须走颜色 id（`Span` 只能存 id，不能存 ARGB），
         * 因此同样由 [JsonEditorTheme] 显式写入。
         */
        const val FLASH_LINE = BASE + 5
        const val FLASH_WORD = BASE + 6

        /** 高亮区内的文字色；背景恢复时也用它，避免文字继承成上一种 token 的颜色 */
        const val FLASH_TEXT = BASE + 7

        /** 全部自定义槽位，供配色层统一写入 */
        val ALL = intArrayOf(KEY, STRING, NUMBER, LITERAL, PUNCT, FLASH_LINE, FLASH_WORD, FLASH_TEXT)
    }
}
