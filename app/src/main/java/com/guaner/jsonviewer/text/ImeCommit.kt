package com.guaner.jsonviewer.text

/**
 * 输入法提交文本的入口处理。
 *
 * 软件里的「粘贴」按钮走 `setTextProgrammatic`（**先折行，再灌进编辑器**），
 * 所以粘贴 500KB 单行 JSON 毫无压力；但输入法粘贴走的是另一条路：
 *
 * ```
 * InputConnection.commitText(text)
 *   → EditorInputConnection.commitTextInternal()
 *   → CodeEditor.commitText()          // 这里还会为了符号配对把整段复制成 char[]
 *   → Content.replace(...)             // 一行几十万字符就此成型
 *   → ContentListener → WordwrapLayout.afterInsert → breakLine()
 * ```
 *
 * 最后那一步是 O(n²)（每找一个断点都要把剩余整行量一遍宽度），几十万字符的**单行**
 * 要算几十亿次字符度量 —— 主线程就此堵死，表现为「卡住然后崩溃（ANR）」。
 * 事后补救（`scheduleFoldCheck`）根本来不及：延迟任务排在被堵死的主线程上，轮不到它执行。
 *
 * 所以要把折叠提到**文本进入 Content 之前**，同时跳过 `CodeEditor.commitText` 里那套
 * 为「按键输入」设计的符号配对处理 —— 它在整段粘贴这种体量下纯属白做工。
 */
object ImeCommit {

    /**
     * 一次提交的文本超过这个长度就改走自制的插入路径。
     *
     * 常规打字/上屏不可能到这个量级（8K 字符的手打内容没有意义），
     * 达到这个规模的一定是「输入法粘贴」或「语音输入整段落地」。
     */
    const val BULK_CHARS = 8 * 1024

    /**
     * 决定提交给编辑器之前要不要先折行。
     *
     * @param text 输入法提交的原始文本
     * @param foldEnabled 是否允许自动折行（值编辑面板一般是关的）
     * @return 折行后的文本；不需要折（或不敢折）时返回 **null**，调用方据此原样使用 [text]
     */
    fun prepare(
        text: CharSequence,
        foldEnabled: Boolean,
        threshold: Int = SafeFolder.DEFAULT_THRESHOLD
    ): String? {
        if (!foldEnabled || text.isEmpty()) return null
        val result = SafeFolder.foldWithAnchor(text, threshold, -1) ?: return null
        // 和别处一样最后再验一次：折行只允许「多出换行符」，多出或少了别的字符就整个放弃
        return if (SafeFolder.onlyInsertedLineBreaks(text, result.text, result.inserted)) {
            result.text
        } else {
            null
        }
    }

    /** 最长那一行有多长；用来判断折完之后还剩下怎样的行 */
    fun longestLineLength(text: CharSequence): Int {
        var longest = 0
        var cur = 0
        for (i in text.indices) {
            if (text[i] == '\n') {
                if (cur > longest) longest = cur
                cur = 0
            } else {
                cur++
            }
        }
        return maxOf(longest, cur)
    }
}
