package com.linnas.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 版本检查页:显示本机 App 版本与服务端版本,支持**应用内直接下载并安装更新包**。
 *
 * 服务端接口 GET /api/app/version?token=... 返回
 * {versionName, versionCode, url, notes};若 versionCode 大于本机则可下载。
 * 下载在应用内完成(带进度与速度),完成后用系统安装器打开 APK —— 不用跳浏览器。
 */
class VersionCheckActivity : ScreenScaffold() {

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private data class ServerVer(
        val code: Long,
        val name: String,
        val url: String,
        val notes: String,
    )

    override fun title() = "版本检查"

    override fun onCreateScreen() {
        load()
    }

    private fun load() {
        clearContent()
        addText("加载中…")
        lifecycleScope.launch {
            val local = readLocalVersion()
            val serverVersion = try {
                withContext(Dispatchers.IO) { client.version() }
            } catch (_: Exception) {
                ""
            }
            clearContent()
            addRow("本机版本", local.first)
            addRow("服务端版本", serverVersion.ifBlank { "读取失败" })
            addButton("检查更新") { checkUpdate(local.second) }
        }
    }

    /** 读取本机 versionName / versionCode(用 PackageManager,不用 BuildConfig)。 */
    private fun readLocalVersion(): Pair<String, Long> = try {
        val pm = applicationContext.packageManager
        val info = pm.getPackageInfo(applicationContext.packageName, 0)
        val name = info.versionName ?: "未知"
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        "v$name (版本码 $code)" to code
    } catch (_: Exception) {
        "未知" to 0L
    }

    private fun checkUpdate(localCode: Long) {
        clearContent()
        addText("正在检查更新…")
        lifecycleScope.launch {
            try {
                val ver = withContext(Dispatchers.IO) { fetchVersion() }
                clearContent()
                if (ver.code > localCode) {
                    addRow("发现新版本", "v${ver.name} (版本码 ${ver.code})")
                    if (ver.notes.isNotBlank()) addText("更新说明:${ver.notes}")
                    if (ver.url.isNotBlank()) {
                        addButton("下载并安装") { downloadUpdate(ver) }
                    } else {
                        addText("该版本暂未提供下载地址")
                    }
                } else if (ver.code < 0) {
                    addText("未能解析服务端版本信息")
                } else {
                    addText("已是最新版本 v${ver.name} (版本码 ${ver.code})")
                }
                addButton("再次检查") { checkUpdate(localCode) }
            } catch (e: Exception) {
                clearContent()
                addText(e.message ?: "检查更新失败,请稍后重试")
                addButton("重新检查") { checkUpdate(localCode) }
            }
        }
    }

    /**
     * v1.8.1:应用内下载更新包 —— 不跳浏览器。
     * 进度与速度实时显示,下载完直接调起系统安装器。
     */
    private fun downloadUpdate(ver: ServerVer) {
        clearContent()
        addRow("正在下载", "v${ver.name} (版本码 ${ver.code})")
        val status = addLiveText("准备下载…")
        lifecycleScope.launch {
            val speed = SpeedMeter()
            try {
                val file = withContext(Dispatchers.IO) { downloadApk(ver.url, speed) { p, total ->
                    status.text = if (total > 0)
                        "已下载 $p% · ${SpeedMeter.format(speed.latest())}"
                    else "已下载 ${SpeedMeter.format(speed.latest())}"
                } }
                status.text = "下载完成:${file.name}(${file.length() / 1024 / 1024} MB),正在打开安装器…"
                installApk(file)
            } catch (e: Exception) {
                status.text = "下载失败:${e.message ?: "未知错误"}"
                addButton("重试") { downloadUpdate(ver) }
            }
        }
    }

    /** 加一行可实时更新的文本。 */
    private fun addLiveText(initial: String): TextView {
        val tv = TextView(this).apply {
            text = initial
            textSize = 13f
            setTextColor(ThemeColors.textDim(this@VersionCheckActivity))
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(
            tv,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return tv
    }

    private fun downloadApk(url: String, speed: SpeedMeter, onProgress: (Int, Long) -> Unit): File {
        val req = Request.Builder().url(url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("服务器返回空响应")
            val total = body.contentLength()
            val out = File(cacheDir, "linnas-update.apk")
            if (out.exists()) out.delete()
            var received = 0L
            var lastPct = -1
            val buf = ByteArray(128 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(out).use { output ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        received += n
                        val pct = if (total > 0) ((received * 100) / total).toInt() else 0
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(pct, total)
                        }
                    }
                }
            }
            if (total > 0 && received != total) throw IllegalStateException("下载不完整")
            return out
        }
    }

    private fun installApk(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            toast("无法打开安装器:${e.message}")
        }
    }

    /** GET {baseUrl}/api/app/version?token=...,自行拼 URL(不改 LinNasClient)。 */
    private fun fetchVersion(): ServerVer {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (base.isBlank()) throw ApiException("尚未配置服务器地址")
        if (!base.startsWith("http")) base = "http://$base"
        val token = ServerConfig.sessionToken.ifBlank { ServerConfig.token }
        val q = if (token.isBlank()) "" else "?token=" + URLEncoder.encode(token, "UTF-8")
        val req = Request.Builder().url("$base/api/app/version$q").get().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            when {
                resp.code == 404 -> throw ApiException("服务端暂不支持在线更新检查")
                resp.code == 401 -> throw ApiException("登录状态已失效,请重新登录")
                !resp.isSuccessful -> throw ApiException("检查更新失败(HTTP ${resp.code})")
            }
            val o = JSONObject(body)
            return ServerVer(
                code = o.optLong("versionCode", -1L),
                name = o.optString("versionName", ""),
                url = o.optString("url", ""),
                notes = o.optString("notes", ""),
            )
        }
    }
}
