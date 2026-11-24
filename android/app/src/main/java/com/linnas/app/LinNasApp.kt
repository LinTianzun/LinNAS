package com.linnas.app

import android.app.Application
import android.content.Intent
import java.io.PrintWriter
import java.io.StringWriter

/** 全局崩溃捕获:把堆栈写到 crash.txt 并弹出可复制的错误页,方便定位闪退。 */
class LinNasApp : Application() {
    companion object {
        var instance: LinNasApp? = null
            private set
    }

    override fun onCreate() {
        instance = this
        super.onCreate()
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
