package com.guaner.jsonviewer.crash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 崩溃日志的头部解析（列表页只用这几行 key=value，不加载完整堆栈）。
 * 这里锁住格式契约：写入端与读取端必须一致，否则设置页会显示「未知异常」。
 */
class CrashLoggerMetaTest {

    private val head = """
        #JSONVIEWER-CRASH 1
        time=2026-10-31 05:40:12
        version=5.2 (42)
        exception=java.lang.NullPointerException
        message=Attempt to invoke virtual method on a null object reference
        screen=主界面·表单
        size=已用 40MB / 上限 256MB
        ----
    """.trimIndent()

    @Test
    fun `meta is parsed into key value map`() {
        val meta = CrashLogger.parseMeta(head)
        assertEquals("2026-10-31 05:40:12", meta["time"])
        assertEquals("5.2 (42)", meta["version"])
        assertEquals("java.lang.NullPointerException", meta["exception"])
        assertEquals("主界面·表单", meta["screen"])
    }

    @Test
    fun `headline uses simple class name`() {
        assertEquals(
            "NullPointerException: boom",
            CrashLogger.headlineOf("java.lang.NullPointerException", "boom")
        )
    }

    @Test
    fun `headline falls back to class name when message empty`() {
        assertEquals(
            "IllegalStateException",
            CrashLogger.headlineOf("java.lang.IllegalStateException", "")
        )
    }

    @Test
    fun `headline tolerates class name without package`() {
        assertEquals("CustomError: x", CrashLogger.headlineOf("CustomError", "x"))
    }

    @Test
    fun `parse tolerates lines without equals and empty input`() {
        assertTrue(CrashLogger.parseMeta("").isEmpty())
        val meta = CrashLogger.parseMeta("没有等号的一行\nkey=value")
        assertEquals("value", meta["key"])
        assertEquals(1, meta.size)
    }

    /** message 里的等号不能被截断（写入端已把换行压成空格，但仍可能包含 =） */
    @Test
    fun `value may contain equals sign`() {
        val meta = CrashLogger.parseMeta("message=a=b=c")
        assertEquals("a=b=c", meta["message"])
    }

    /**
     * 写入端与读取端的整体契约：buildLog 产出的头部必须能被 parseMeta 还原，
     * 且堆栈/Caused by 必须落在正文里。这是「设置页能看懂日志」的前提。
     */
    @Test
    fun `built log is parseable and keeps stack and cause chain`() {
        val throwable = IllegalStateException(
            "第一行\n第二行",
            RuntimeException("inner boom")
        )
        val body = CrashLogger.buildLog(
            timeText = "2026-10-31 05:40:12",
            version = "5.2",
            code = 42,
            screen = "主界面·表单",
            threadName = "main",
            threadId = 1,
            memory = "已用 40MB / 上限 256MB",
            device = "Xiaomi 23127PN0CC",
            os = "Android 14（API 34）",
            abis = "arm64-v8a",
            throwable = throwable
        )
        val head = body.substringBefore("----\n")
        val meta = CrashLogger.parseMeta(head)
        assertEquals("2026-10-31 05:40:12", meta["time"])
        assertEquals("5.2 (42)", meta["version"])
        assertEquals("java.lang.IllegalStateException", meta["exception"])
        // 换行被压平成空格，否则单行 key=value 会被打乱
        assertEquals("第一行 第二行", meta["message"])
        assertEquals("主界面·表单", meta["screen"])

        assertTrue(body.contains("---- 堆栈 ----"))
        assertTrue(body.contains("Caused by[1]: java.lang.RuntimeException: inner boom"))
    }

    @Test
    fun `message is capped to keep header single line short`() {
        val long = "x".repeat(2000)
        val body = CrashLogger.buildLog(
            timeText = "t", version = "v", code = 1, screen = "s",
            threadName = "main", threadId = 1, memory = "m",
            device = "d", os = "o", abis = "a",
            throwable = RuntimeException(long)
        )
        val meta = CrashLogger.parseMeta(body.substringBefore("----\n"))
        assertEquals(300, meta["message"]?.length)
    }

    /**
     * 「记录崩溃日志」开关的契约：
     * - 没绑定开关时默认记录（老版本升级后行为不变）；
     * - 绑定后按设置值走；
     * - 读设置本身失败时必须记录，不能因为读开关把现场丢了。
     */
    @Test
    fun `recording follows switch and defaults to on when it fails`() {
        assertTrue(CrashLogger.recordingEnabled())

        CrashLogger.bindEnabledProvider { false }
        assertTrue(!CrashLogger.recordingEnabled())

        CrashLogger.bindEnabledProvider { true }
        assertTrue(CrashLogger.recordingEnabled())

        CrashLogger.bindEnabledProvider { error("prefs 未初始化") }
        assertTrue(CrashLogger.recordingEnabled())

        // 恢复默认，避免影响同进程内的其他用例
        CrashLogger.bindEnabledProvider { true }
    }
}
