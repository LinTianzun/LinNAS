package com.linnas.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 版本检查页:显示本机 App 版本与服务端版本,并可在线检查更新。
 *
 * 服务端新接口 GET /api/app/version?token=... 返回
 * {versionName, versionCode, url, notes};若 versionCode 大于本机则提供「下载」。
 * 接口不存在(404)、网络失败、已是最新都有友好中文提示。
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
                        addButton("下载更新") { openUrl(ver.url) }
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

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            toast("无法打开下载链接:${e.message}")
        }
    }
}
