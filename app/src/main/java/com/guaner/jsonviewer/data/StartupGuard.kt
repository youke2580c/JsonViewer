package com.guaner.jsonviewer.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 崩溃恢复闸门。
 *
 * 用法：启动时 [shouldSkipRestore]，决定是否跳过「恢复上次内容」；
 * 恢复成功、主界面正常显示出来后调用 [markHealthy] 清除标记。
 *
 * 下次进入时若 [shouldSkipRestore] 仍为 true，说明上次进程在启动恢复阶段就没了
 * （用户看到的正是「一打开就崩、进不去」），此时直接不恢复，保证能进软件。
 */
object StartupGuard {

    private const val PREFS = "startup_guard"
    private const val KEY_ATTEMPT = "restore_attempt"
    private const val KEY_SKIP_SESSION = "skip_session_once"
    private const val KEY_LAST_CRASH = "last_crash_at"
    private const val KEY_RESTORED_ONCE = "restored_once"

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 即将尝试恢复上次内容：先落标记。若这次进程崩了，标记会留到下次启动 */
    fun markRestoreAttempt() {
        prefs?.edit()?.putBoolean(KEY_ATTEMPT, true)?.apply()
    }

    /** 恢复流程顺利完成（主界面显示出来）：清掉标记 */
    fun markHealthy() {
        prefs?.edit()?.putBoolean(KEY_ATTEMPT, false)?.apply()
    }

    /**
     * 本次启动是否可以恢复上次内容。
     *
     * 返回 false 的场景：
     * - 上次是在恢复阶段崩掉的（标记还留着）；
     * - 上次进程异常退出（崩溃日志里有 90 秒内的记录）；
     * - 上一次被动跳过之后又连续崩过一次（见 [recordSkipRestore]）。
     */
    fun shouldRestoreSession(context: Context): Boolean {
        val p = prefs ?: return true
        if (p.getBoolean(KEY_ATTEMPT, false)) {
            // 上次卡在恢复阶段：本次不恢复，并把「跳过」记一次
            recordSkipRestore()
            return false
        }
        if (recentCrash(context)) {
            recordSkipRestore()
            return false
        }
        return true
    }

    /** 是否曾经成功恢复过一次（区分「首次安装」和「崩过之后」） */
    fun hasRestoredBefore(): Boolean = prefs?.getBoolean(KEY_RESTORED_ONCE, false) ?: false

    /** 记录一次成功恢复 */
    fun markRestoredOnce() {
        prefs?.edit()?.putBoolean(KEY_RESTORED_ONCE, true)?.apply()
    }

    /**
     * 记录「因崩溃而跳过恢复」。用户提示里说的跳过只生效一次：
     * 若用户没触发崩溃、只是正常用，说明上次是偶发问题，下次启动照常恢复。
     */
    private fun recordSkipRestore() {
        prefs?.edit()?.putBoolean(KEY_SKIP_SESSION, true)?.apply()
    }

    /** 上一次启动跳过了恢复吗（用于在界面上告知用户，而不是悄悄换成本地空文本） */
    fun consumedSkipFlag(): Boolean {
        val p = prefs ?: return false
        val v = p.getBoolean(KEY_SKIP_SESSION, false)
        if (v) p.edit()?.putBoolean(KEY_SKIP_SESSION, false)?.apply()
        return v
    }

    /** 最近是否有过异常退出（由 CrashLogger 在崩溃瞬间写入时间戳） */
    fun markCrash() {
        prefs?.edit()?.putLong(KEY_LAST_CRASH, System.currentTimeMillis())?.apply()
    }

    private fun recentCrash(context: Context): Boolean {
        val p = prefs ?: return false
        val at = p.getLong(KEY_LAST_CRASH, 0L)
        if (at <= 0L) return false
        // 时间戳被当作「已消费」：只影响紧接着的这一次启动
        p.edit()?.putLong(KEY_LAST_CRASH, 0L)?.apply()
        // 时钟回拨等异常情况按「刚崩过」处理，宁可跳过恢复也别进不去
        return System.currentTimeMillis() - at < MAX_CRASH_AGE_MS || System.currentTimeMillis() < at
    }

    /** 崩溃时间戳的容忍窗口：超过这个时间的老崩溃不再影响恢复 */
    private const val MAX_CRASH_AGE_MS = 5 * 60 * 1000L
}
