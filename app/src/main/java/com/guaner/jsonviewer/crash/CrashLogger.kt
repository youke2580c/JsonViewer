package com.guaner.jsonviewer.crash

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条崩溃日志的摘要（列表展示用，不加载全文） */
class CrashEntry(
    val file: File,
    /** 崩溃时间，如 2026-10-31 05:40:12 */
    val time: String,
    /** 异常类型 + 消息，如 NullPointerException: Attempt to ... */
    val headline: String,
    /** 版本号，如 5.2 (43) */
    val version: String,
    /** 崩溃时最后停留的页面 */
    val screen: String,
    val sizeBytes: Long
)

/**
 * 全局崩溃日志。
 *
 * 装一个 [Thread.UncaughtExceptionHandler]：任何线程（含主线程）未捕获的异常都会先落到
 * `files/crash/crash-*.log`，再交回系统原本的处理器，**不改变系统原有的崩溃/重启行为**。
 *
 * 日志内带版本、机型、系统版本、可用内存与最后停留页面，只凭一份日志就能定位大部分问题。
 * 所有写盘动作都包在 try-catch 里——日志系统自身的失败绝不能引发二次崩溃。
 */
object CrashLogger {

    private const val DIR_NAME = "crash"
    private const val FILE_PREFIX = "crash-"
    private const val FILE_SUFFIX = ".log"
    private const val MAX_FILES = 50
    private const val MAX_BODY_CHARS = 256 * 1024
    private const val HEADER = "#JSONVIEWER-CRASH 1"
    private const val TIME_FILE = "yyyyMMdd-HHmmss-SSS"
    private const val TIME_TEXT = "yyyy-MM-dd HH:mm:ss"

    private var appContext: Context? = null
    private var installed = false

    /** 最后停留的页面，崩溃时写进日志，方便判断是哪个功能出的问题 */
    @Volatile
    private var lastScreen: String = "-"

    /** 处理器自身正在写日志：避免写日志时再抛异常导致无限递归 */
    @Volatile
    private var handling = false

    /**
     * 是否记录日志的开关来源（设置项）。
     *
     * 用回调而不是直接读 [com.guaner.jsonviewer.data.AppSettings]：崩溃发生在任何时刻，
     * 这里必须每次现读，用户关掉开关后立刻生效；回调本身抛异常时按「记录」处理，
     * 宁可多存一份日志，也不能因为读设置失败而丢失现场。
     */
    private var enabledProvider: (() -> Boolean)? = null

    fun bindEnabledProvider(provider: () -> Boolean) {
        enabledProvider = provider
    }

    private fun enabled(): Boolean = try {
        enabledProvider?.invoke() ?: true
    } catch (_: Throwable) {
        true
    }

    /** 供单测锁住「关掉开关就不记 / 读设置异常仍要记」这条契约 */
    internal fun recordingEnabled(): Boolean = enabled()

    /** 安装全局处理器；重复调用无副作用 */
    fun install(context: Context) {
        val ctx = context.applicationContext ?: context
        appContext = ctx
        if (installed) return
        installed = true
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // 设置里关掉「记录崩溃日志」时只跳过落盘，仍然照常交给系统处理
                if (enabled()) save(ctx, thread, throwable)
            } catch (_: Throwable) {
                // 落盘失败也绝不能吞掉原异常：继续交给系统
            }
            if (previous != null) {
                previous.uncaughtException(thread, throwable)
            } else {
                // 理论上不会走到这里（系统一定装了默认处理器）
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun noteScreen(screen: String) {
        lastScreen = screen
    }

    // ---------- 读写 ----------

    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    fun logs(context: Context): List<CrashEntry> = logsIn(dir(context))

    /** 与 [logs] 相同，但直接接收目录（便于纯 JVM 单测覆盖「写盘 → 读列表」整条链路） */
    internal fun logsIn(dir: File): List<CrashEntry> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) }
            ?: return emptyList()
        return files.sortedByDescending { it.name }.mapNotNull { toEntry(it) }
    }

    fun count(context: Context): Int =
        dir(context).listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) }?.size ?: 0

    fun readText(file: File): String = try {
        file.readText().take(MAX_BODY_CHARS)
    } catch (e: Throwable) {
        "读取日志失败：${e.message}"
    }

    fun delete(file: File): Boolean = try {
        file.delete()
    } catch (e: Throwable) {
        false
    }

    /** 清空全部日志，返回删除条数 */
    fun clear(context: Context): Int {
        val files = dir(context).listFiles { f -> f.isFile } ?: return 0
        var n = 0
        for (f in files) {
            if (delete(f)) n++
        }
        return n
    }

    // ---------- 内部实现 ----------

    private fun save(context: Context, thread: Thread, throwable: Throwable) {
        if (handling) return
        handling = true
        try {
            write(context, thread, throwable)
        } finally {
            handling = false
        }
    }

    private fun write(context: Context, thread: Thread, throwable: Throwable) {
        val now = Date()
        val dir = dir(context)
        val body = build(context, thread, throwable, now)
        writeTo(dir, now, body)
    }

    /** 真正落盘：写文件 + 清理超量旧日志。返回写入的文件（失败时为 null） */
    internal fun writeTo(dir: File, now: Date, body: String): File? {
        if (!dir.exists()) dir.mkdirs()
        val name = FILE_PREFIX + SimpleDateFormat(TIME_FILE, Locale.US).format(now) + FILE_SUFFIX
        val file = File(dir, name)
        FileOutputStream(file).use { out ->
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                w.write(body.take(MAX_BODY_CHARS))
                w.flush()
            }
        }
        trim(dir)
        return file
    }

    private fun build(
        context: Context,
        thread: Thread,
        throwable: Throwable,
        now: Date
    ): String = buildLog(
        timeText = SimpleDateFormat(TIME_TEXT, Locale.US).format(now),
        version = versionName(context),
        code = versionCode(context),
        screen = lastScreen,
        threadName = thread.name,
        threadId = thread.id,
        memory = runtimeMemory(),
        device = deviceInfo(),
        os = osInfo(),
        abis = abiInfo(),
        throwable = throwable
    )

    /**
     * 组装日志正文（纯函数，便于单测锁住格式：列表页依赖头部这几行 key=value）。
     * 环境信息全部由调用方传入，避免在 JVM 单测里碰 android.os.Build 的桩字段。
     */
    internal fun buildLog(
        timeText: String,
        version: String,
        code: Long,
        screen: String,
        threadName: String,
        threadId: Long,
        memory: String,
        device: String,
        os: String,
        abis: String,
        throwable: Throwable
    ): String {
        val exception = throwable.javaClass.name
        // message 里的换行必须压平：它是 key=value 单行格式，换行会把头部解析打乱
        val message = (throwable.message ?: "").replace('\n', ' ').replace('\r', ' ').take(300)

        val out = StringBuilder(4096)
        // 头部用 key=value：列表页只解析这几行，不必读完整堆栈
        out.append(HEADER).append('\n')
        out.append("time=").append(timeText).append('\n')
        out.append("version=").append(version).append(" (").append(code).append(")\n")
        out.append("exception=").append(exception).append('\n')
        out.append("message=").append(message).append('\n')
        out.append("screen=").append(screen).append('\n')
        out.append("size=").append(memory).append('\n')
        out.append("----\n")

        out.append("应用：JSON Viewer ").append(version).append(" (").append(code).append(")\n")
        out.append("时间：").append(timeText).append('\n')
        out.append("页面：").append(screen).append('\n')
        out.append("线程：").append(threadName).append("（id=").append(threadId).append("）\n")
        out.append("机型：").append(device).append('\n')
        out.append("系统：").append(os).append('\n')
        out.append("CPU：").append(abis).append('\n')
        out.append("内存：").append(memory).append('\n')
        out.append("异常：").append(exception)
            .append(if (message.isNotEmpty()) ": $message" else "").append('\n')
        out.append('\n').append("---- 堆栈 ----").append('\n')
        out.append(stackOf(throwable))

        // 摘出最内层的 Caused by，多数问题看这一行就够
        out.append('\n').append("---- 原因链 ----").append('\n')
        var cause = throwable.cause
        var depth = 1
        while (cause != null && depth <= 8) {
            out.append("Caused by[").append(depth).append("]: ")
                .append(cause.javaClass.name)
                .append(if (cause.message.isNullOrEmpty()) "" else ": ${cause.message}")
                .append('\n')
            cause = cause.cause
            depth++
        }
        return out.toString()
    }

    private fun deviceInfo(): String = try {
        val d = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
        d.ifEmpty { "未知" }
    } catch (e: Throwable) {
        "未知"
    }

    private fun osInfo(): String = try {
        "Android ${android.os.Build.VERSION.RELEASE}（API ${android.os.Build.VERSION.SDK_INT}）"
    } catch (e: Throwable) {
        "未知"
    }

    private fun abiInfo(): String = try {
        android.os.Build.SUPPORTED_ABIS?.joinToString(",")?.ifEmpty { "未知" } ?: "未知"
    } catch (e: Throwable) {
        "未知"
    }

    private fun stackOf(t: Throwable): String = try {
        val sw = StringWriter()
        PrintWriter(sw).use { t.printStackTrace(it) }
        sw.toString()
    } catch (e: Throwable) {
        "堆栈获取失败：${e.message}\n"
    }

    private fun runtimeMemory(): String {
        val rt = Runtime.getRuntime()
        fun mb(v: Long) = v / 1024 / 1024
        return try {
            "已用 ${mb(rt.totalMemory() - rt.freeMemory())}MB / 已分配 ${mb(rt.totalMemory())}MB / 上限 ${mb(rt.maxMemory())}MB"
        } catch (e: Throwable) {
            "未知"
        }
    }

    private fun versionName(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "-"
    } catch (e: Throwable) {
        "-"
    }

    private fun versionCode(context: Context): Long = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Throwable) {
        -1L
    }

    /** 只保留最近 [MAX_FILES] 份，避免长期使用后占满存储 */
    private fun trim(dir: File) {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(FILE_SUFFIX) } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.name }
            .take(files.size - MAX_FILES)
            .forEach { it.delete() }
    }

    private fun toEntry(file: File): CrashEntry? = try {
        val head = file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            val sb = StringBuilder()
            var line = reader.readLine()
            var n = 0
            while (line != null && n < 12) {
                if (line == "----") break
                sb.append(line).append('\n')
                line = reader.readLine()
                n++
            }
            sb.toString()
        }
        val map = parseMeta(head)
        val exception = map["exception"] ?: "未知异常"
        CrashEntry(
            file = file,
            time = map["time"] ?: file.nameWithoutExtension.removePrefix(FILE_PREFIX),
            headline = headlineOf(exception, map["message"].orEmpty()),
            version = map["version"] ?: "-",
            screen = map["screen"] ?: "-",
            sizeBytes = file.length()
        )
    } catch (e: Throwable) {
        null
    }

    /** 解析日志头部的 key=value（列表页只读这一段，不加载完整堆栈） */
    internal fun parseMeta(head: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (line in head.lineSequence()) {
            val idx = line.indexOf('=')
            if (idx > 0) map[line.substring(0, idx)] = line.substring(idx + 1)
        }
        return map
    }

    /** 列表里显示的一行摘要：``NullPointerException: Attempt to ...`` */
    internal fun headlineOf(exception: String, message: String): String {
        val shortName = exception.substringAfterLast('.').ifEmpty { exception }
        return if (message.isEmpty()) shortName else "$shortName: $message"
    }
}
