package com.guaner.jsonviewer

import android.app.Application
import com.guaner.jsonviewer.crash.CrashLogger
import com.guaner.jsonviewer.data.AppSettings
import com.guaner.jsonviewer.data.JsonHistory
import com.guaner.jsonviewer.data.UaProfiles
import com.guaner.jsonviewer.data.UrlHistory
import com.guaner.jsonviewer.theme.ThemeManager

/**
 * 进程入口。
 *
 * 1) 尽早装上崩溃日志处理器——必须在 Application 里装（而不是 MainActivity）：
 *    否则启动阶段、后台线程、设置页等在 Activity 之前/之外发生的崩溃都抓不到。
 * 2) 统一初始化各单例，避免任何入口（Activity 重建、系统直启某个页面）忘记初始化
 *    而抛 UninitializedPropertyAccessException。
 */
class JsonViewerApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // AppSettings 必须先初始化：崩溃处理器要读「是否记录崩溃日志」开关
        ThemeManager.init(this)
        AppSettings.init(this)
        JsonHistory.init(this)
        UrlHistory.init(this)
        UaProfiles.init(this)
        CrashLogger.install(this)
        CrashLogger.bindEnabledProvider { AppSettings.crashLogEnabled }
    }
}
