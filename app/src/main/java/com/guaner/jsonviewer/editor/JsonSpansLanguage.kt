package com.guaner.jsonviewer.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.StyleReceiver
import io.github.rosemoe.sora.lang.brackets.BracketsProvider
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.diagnostic.DiagnosticsContainer
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.MappedSpans
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.SpanFactory
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.text.TextRange
import io.github.rosemoe.sora.widget.SymbolPairMatch
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** 语法着色种类，映射到编辑器配色方案里的颜色槽位 */
enum class TokenKind { KEY, STRING, NUMBER, LITERAL, PUNCT }

/**
 * 面向整篇 JSON 的**逐行增量**着色语言。
 *
 * JSON 的 token 边界依赖上下文（字符串是键还是值要看后面有没有冒号），逐字增量很麻烦；
 * 这里退一步按「行」缓存：一次编辑只让受影响的几行失效，分析时只重扫这几行。
 * 于是打字耗时从「全文 O(n)」降到「几行」，几万行文本也跟手。
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

    /** 单行分词结果（行内相对列号） */
    class Token(val start: Int, val end: Int, val kind: TokenKind)

    private inner class LinesAnalyzeManager : AnalyzeManager {

        private var receiver: StyleReceiver? = null
        private var content: Content? = null

        /** 行级 token 缓存，null 表示待重新计算 */
        private var cache: Array<ArrayList<Token>?> = emptyArray()
        private val lock = ReentrantLock()

        override fun setReceiver(receiver: StyleReceiver?) {
            this.receiver = receiver
        }

        override fun reset(content: ContentReference, extraArguments: Bundle) {
            val c = content.reference as? Content
            lock.withLock {
                this.content = c
                cache = arrayOfNulls(c?.lineCount ?: 0)
            }
            dispatch()
        }

        override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
            invalidate(start.line, end.line)
            dispatch()
        }

        override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
            invalidate(start.line, end.line)
            dispatch()
        }

        override fun rerun() {
            lock.withLock { cache = arrayOfNulls(content?.lineCount ?: 0) }
            dispatch()
        }

        override fun destroy() {
            receiver = null
            content = null
            lock.withLock { cache = emptyArray() }
        }

        /** [fromLine]..[toLine] 失效；字符串可能跨行，额外带上前后各一行 */
        private fun invalidate(fromLine: Int, toLine: Int) {
            lock.withLock {
                val c = content ?: return
                val n = c.lineCount
                if (cache.size != n) cache = cache.copyOf(n)
                val lo = (fromLine - 1).coerceAtLeast(0)
                val hi = (toLine + 1).coerceAtMost(n - 1)
                for (i in lo..hi) cache[i] = null
            }
        }

        private fun dispatch() {
            val r = receiver ?: return
            val styles = try {
                lock.withLock { buildStyles() }
            } catch (e: Throwable) {
                return
            } ?: return
            r.setStyles(this, styles)
            r.setDiagnostics(this, DiagnosticsContainer())
            r.updateBracketProvider(this, BracketsProvider { _, _ -> null })
        }

        private fun buildStyles(): Styles? {
            val c = content ?: return null
            val lineCount = c.lineCount
            if (cache.size != lineCount) cache = cache.copyOf(lineCount)
            val builder = MappedSpans.Builder(lineCount)
            for (line in 0 until lineCount) {
                var tokens = cache[line]
                if (tokens == null) {
                    tokens = scanLine(c, line)
                    cache[line] = tokens
                }
                for (t in tokens) builder.add(t.start, spanOf(t))
                builder.determine(line)
            }
            builder.addNormalIfNull()
            return Styles(builder.build())
        }

        private fun scanLine(c: Content, line: Int): ArrayList<Token> {
            val text = try {
                c.getLineString(line)
            } catch (e: Throwable) {
                return ArrayList(0)
            }
            return scan(text)
        }
    }

    private fun spanOf(t: Token): Span {
        val colorId = when (t.kind) {
            TokenKind.KEY -> ColorIds.KEY
            TokenKind.STRING -> ColorIds.STRING
            TokenKind.NUMBER -> ColorIds.NUMBER
            TokenKind.LITERAL -> ColorIds.LITERAL
            TokenKind.PUNCT -> ColorIds.PUNCT
        }
        return SpanFactory.obtain(t.start, colorId.toLong() shl 32)
    }

    private fun scan(line: String): ArrayList<Token> = scanLine(line, maxTokensPerLine)

    companion object {
    /** 单行分词：规则与旧的 JsonHighlighter 一致（键 = 紧跟冒号的字符串） */
    fun scanLine(line: String, maxTokens: Int = 4096): ArrayList<Token> {
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
                line.startsWith("true", i) -> {
                    out.add(Token(i, i + 4, TokenKind.LITERAL))
                    i += 4
                }
                line.startsWith("false", i) -> {
                    out.add(Token(i, i + 5, TokenKind.LITERAL))
                    i += 5
                }
                line.startsWith("null", i) -> {
                    out.add(Token(i, i + 4, TokenKind.LITERAL))
                    i += 4
                }
                else -> i++
            }
        }
        return out
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

    /** 配色方案里的颜色槽位编号（在 [JsonEditorTheme] 里设置具体色值） */
    object ColorIds {
        const val KEY = 9101
        const val STRING = 9102
        const val NUMBER = 9103
        const val LITERAL = 9104
        const val PUNCT = 9105
    }
}
