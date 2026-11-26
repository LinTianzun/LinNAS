package com.linnas.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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

    /** 待安装的 APK:用户去「未知来源」设置页授权后,回到本页自动重试安装一次。 */
    private var pendingInstallFile: File? = null

    /**
     * 为什么用 ActivityResultLauncher 而不是 onResume:
     * 「安装未知来源应用」只能由用户在系统设置页手动开启,代码无法直接授予。
     * 用 launcher 只在「从设置页返回」这一确切时机回调,避免首次显示页面或其他原因回到前台时误触发安装。
     * 回调里先清空 pendingInstallFile,保证无论用户是否开启都只自动重试一次,不会反复弹设置页形成死循环。
     */
    private val installPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallFile
        pendingInstallFile = null
        if (file != null && file.exists()) {
            if (canInstallPackages()) {
                // 授权成功:自动继续安装一次。
                launchInstaller(file)
            } else {
                toast("尚未开启安装权限,可在浏览器中下载后手动安装")
            }
        }
    }

    /**
     * 安装器返回后的回调:RESULT_OK 表示系统已接受安装,顺手清掉缓存里的更新包,
     * 避免 linnas-update*.apk 越堆越多,也避免旧包被安装器/包管理器再次复用。
     */
    private val installResultLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            cleanCachedUpdateApks()
        }
    }

    private data class ServerVer(
        val code: Long,
        val name: String,
        val url: String,
        val notes: String,
    )

    /** 从 APK 包内读到的真实标识与版本(用于安装前核对,防止装到旧版本包)。 */
    private data class ApkInfo(
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
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
                    // 兜底入口:应用内安装若被系统拦截(如未开未知来源、厂商限制),可改用浏览器下载安装。
                    addWebUpdateSection()
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
                val file = withContext(Dispatchers.IO) { downloadApk(ver, speed) { p, total ->
                    status.text = if (total > 0)
                        "已下载 $p% · ${SpeedMeter.format(speed.latest())}"
                    else "已下载 ${SpeedMeter.format(speed.latest())}"
                } }
                status.text = "下载完成:${file.name}(${file.length() / 1024 / 1024} MB),正在校验安装包…"
                installApk(file, ver, status)
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

    /**
     * 清理缓存目录里的历史更新包(linnas-update*.apk),可排除本次目标文件。
     * 只删非空文件;失败静默,避免影响下载/安装主流程。
     */
    private fun cleanCachedUpdateApks(except: File? = null) {
        try {
            cacheDir.listFiles()?.forEach { f ->
                if (f.isFile &&
                    f.name.startsWith("linnas-update") &&
                    (except == null || f.absolutePath != except.absolutePath) &&
                    f.length() > 0
                ) {
                    f.delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    /** 版本名里的非字母数字点替换成 "_",用于安全地拼进文件名。 */
    private fun safeName(name: String): String {
        val s = name.replace(Regex("[^A-Za-z0-9.]"), "_")
        return s.ifBlank { "ver" }
    }

    /** 读取 APK 包内真实的应用标识与版本号;解析失败返回 null。 */
    private fun readApkInfo(file: File): ApkInfo? = try {
        val info = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
        if (info == null) {
            null
        } else {
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
            ApkInfo(
                packageName = info.packageName ?: "",
                versionName = info.versionName ?: "",
                versionCode = code,
            )
        }
    } catch (_: Exception) {
        null
    }

    private fun downloadApk(ver: ServerVer, speed: SpeedMeter, onProgress: (Int, Long) -> Unit): File {
        val req = Request.Builder().url(ver.url).get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
            val body = resp.body ?: throw IllegalStateException("服务器返回空响应")
            val total = body.contentLength()
            // 文件名按版本唯一化:不同版本落到不同文件,避免安装器/PackageInstaller 复用同一 content URI 对应的旧包。
            val out = File(cacheDir, "linnas-update-${ver.code}-${safeName(ver.name)}.apk")
            // 顺势清掉其它历史更新包(排除本次目标),防止旧包残留被安装器再次复用。
            cleanCachedUpdateApks(except = out)
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

    /**
     * 校验下载的 APK 后拉起系统安装器。
     * 为什么要先校验:下载可能拿到的是错误页 / 被截断的文件,直接交给安装器往往毫无反应,
     * 用户会以为「点了没反应」。这里检查三件事 —— 存在、体积合理(>1MB)、文件头是 "PK"(APK 本质是 ZIP);
     * 任一不满足就删掉坏包并提示重新下载。
     */
    private fun installApk(file: File, expected: ServerVer, status: TextView? = null) {
        if (!file.exists() || file.length() <= 1024L * 1024L || !hasZipHeader(file)) {
            file.delete()
            toast("安装包损坏,请重新下载")
            return
        }
        // 核心:安装前核对包内真实版本号,防止装到上一个版本的包(不能只靠界面显示)。
        val info = readApkInfo(file)
        if (info == null) {
            file.delete()
            toast("安装包无法解析,请重新下载")
            return
        }
        if (info.packageName != packageName) {
            file.delete()
            toast("安装包应用标识不符(${info.packageName}),已删除")
            return
        }
        val mismatch = if (expected.code > 0) {
            info.versionCode != expected.code
        } else {
            // 服务端未给版本码的兜底扫描模式:仅在双方版本名都非空且不一致时判定不符。
            expected.name.isNotBlank() && info.versionName.isNotBlank() && expected.name != info.versionName
        }
        if (mismatch) {
            file.delete()
            Sheet(
                this,
                "安装包版本不符",
                "服务器给的是 v${info.versionName}(版本码 ${info.versionCode}),页面显示 v${expected.name}(版本码 ${expected.code})",
            )
                .text("已删除错误的安装包,不会安装旧版本。\n请先「再次检查」;若仍是旧包,说明服务端 release 目录里的 android-version.json / apk 文件不一致。\n也可用下面的网页更新入口手动安装。")
                .primary("再次检查") { checkUpdate(readLocalVersion().second) }
                .tonal("取消") {}
                .show()
            return
        }
        status?.text = "校验通过:v${info.versionName}(版本码 ${info.versionCode}),正在打开安装器…"
        // API 26+ 必须由用户显式允许「安装未知来源应用」,否则系统安装器会静默失败/被拒。
        if (!canInstallPackages()) {
            pendingInstallFile = file
            Sheet(this, "需要安装权限", "请允许本应用安装未知来源应用,返回后会自动继续安装")
                .text("接下来会跳转到系统的「安装未知应用」设置页,请找到本应用并打开允许开关。")
                .primary("去开启") {
                    try {
                        // 走 launcher:从设置页返回时会回调,若已授权就自动继续安装(否则用户还得再点一次「下载并安装」)。
                        installPermissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName"),
                            ),
                        )
                    } catch (e: Exception) {
                        toast("无法打开设置页:${e.message ?: "未知错误"}")
                    }
                }
                .tonal("取消") { pendingInstallFile = null }
                .show()
            return
        }
        launchInstaller(file)
    }

    /** API 26(O)以上才需要查询「是否允许安装未知来源应用」;更低版本默认允许。 */
    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    /** 读取前两个字节判断是否为 ZIP(合法 APK 的魔数是 "PK")。 */
    private fun hasZipHeader(file: File): Boolean = try {
        file.inputStream().use { input ->
            val head = ByteArray(2)
            input.read(head) == 2 &&
                head[0] == 'P'.code.toByte() &&
                head[1] == 'K'.code.toByte()
        }
    } catch (_: Exception) {
        false
    }

    /** 真正拉起系统安装器;失败时给出具体原因并引导改用浏览器更新页。 */
    private fun launchInstaller(file: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            // setDataAndType 必须一次性设置:分两步 setData / setType 会互相清空(Android 7+ 常见坑)。
            // 走 installResultLauncher 以便安装结束(RESULT_OK)后清理缓存更新包。
            installResultLauncher.launch(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                // Activity 上下文不需要 FLAG_ACTIVITY_NEW_TASK,加上反而可能让返回落到桌面;只需临时读授权。
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (e: Exception) {
            toast("无法打开安装器:${e.message ?: "未知错误"},请用浏览器打开更新页下载安装")
        }
    }

    /** 服务端 APK 直链:与 fetchVersion 同样的 baseUrl 归一化规则。 */
    private fun apkDirectUrl(): String {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (base.isBlank()) return ""
        if (!base.startsWith("http")) base = "http://$base"
        return "$base/api/app/apk"
    }

    /**
     * 「网页更新」入口:跳系统浏览器打开更新页,并展示/复制 APK 直链。
     * 为什么要有它:部分机型(尤其 Android 14+/16)对应用内安装限制很严,浏览器下载再从「下载」点开
     * 是最稳妥的兜底路径,因此这里把它作为一等入口而不是隐藏文案。
     */
    private fun addWebUpdateSection() {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (base.isNotBlank() && !base.startsWith("http")) base = "http://$base"
        addTonalButton("在浏览器中打开更新页") {
            if (base.isBlank()) {
                toast("请先连接服务器")
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(base)))
                } catch (e: Exception) {
                    toast("无法打开浏览器:${e.message ?: "未知错误"}")
                }
            }
        }
        val direct = apkDirectUrl()
        if (direct.isNotBlank()) {
            addText("更新包下载直链:\n$direct")
            addTonalButton("复制下载直链") { copyToClipboard(direct) }
        }
        addText("如果手机不允许安装未知来源应用,可以先在浏览器里下载更新包,再从「下载」里点开安装。")
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("更新包直链", text))
        toast("已复制下载直链")
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
