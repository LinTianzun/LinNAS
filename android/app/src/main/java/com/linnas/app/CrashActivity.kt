package com.linnas.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** 崩溃详情页:显示堆栈,可一键复制发给开发者。 */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeStore.styleRes(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_crash)
        val trace = intent.getStringExtra("trace") ?: "(无堆栈)"
        findViewById<TextView>(R.id.crashText).text = trace
        findViewById<Button>(R.id.btnCrashCopy).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("linNas crash", trace))
            (it as Button).text = "已复制"
        }
        findViewById<Button>(R.id.btnCrashClose).setOnClickListener { finish() }
    }
}
