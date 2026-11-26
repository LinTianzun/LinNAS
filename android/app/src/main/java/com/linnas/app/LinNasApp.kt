package com.linnas.app

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import java.io.PrintWriter
import java.io.StringWriter

/** 全局崩溃捕获:把堆栈写到 crash.txt 并弹出可复制的错误页,方便定位闪退。 */
class LinNasApp : Application() {
    companion object {
        var instance: LinNasApp? = null
            private set
    }

    /** 当前处于「已启动」状态的 Activity 数量;归零表示整个 App 都不可见了。 */
    private var startedActivities = 0

    override fun onCreate() {
        instance = this
        super.onCreate()
        Prefs.init(applicationContext)

        // 保险箱免密的「App 是否退出」判定:只有所有 Activity 都停止(开始归零)时
        // 才算退到后台,这样从保险箱跳到播放器/文件选择器再返回不会误判成退出 App。
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                if (startedActivities == 1) VaultSession.onAppForeground()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0) VaultSession.onAppBackground()
            }

            // 其余回调不需要处理,但必须全部实现:低版本系统在运行时校验接口完整性。
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val trace = sw.toString()
                val dir = getExternalFilesDir(null) ?: filesDir
                java.io.File(dir, "crash.txt").writeText("${System.currentTimeMillis()}\n$trace")
                startActivity(Intent(this, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("trace", trace.take(12000))
                })
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }
}
