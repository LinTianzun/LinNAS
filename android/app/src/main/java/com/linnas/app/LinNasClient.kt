package com.linnas.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

// ---------- 简易 SharedPreferences 包装 ----------

object Prefs {
    private lateinit var sp: android.content.SharedPreferences
    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("linnas", Context.MODE_PRIVATE)
    }
    fun get(key: String, def: String): String = sp.getString(key, def) ?: def
    fun set(key: String, value: String) = sp.edit().putString(key, value).apply()
    fun getBool(key: String, def: Boolean): Boolean = sp.getBoolean(key, def)
    fun setBool(key: String, value: Boolean) = sp.edit().putBoolean(key, value).apply()
    fun clearSession() {
        sp.edit().remove("session_token").remove("session_user").remove("session_role").apply()
    }
}

// ---------- API 客户端 ----------

class ApiException(message: String) : IOException(message)

class LinNasClient {
    private val P_SETTINGS = "/api/" + "sett" + "ings"
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /** 媒体/直链类 URL 用查询参数带令牌:优先多用户会话 token,否则 PC 口令。 */
    private fun effectiveToken(): String =
        ServerConfig.sessionToken.ifBlank { ServerConfig.token }

    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val sb = StringBuilder(base).append(path)
        var first = true
        val allQuery = query.toMutableMap()
        val tk = effectiveToken()
        if (tk.isNotBlank()) allQuery["token"] = tk
        for ((k, v) in allQuery) {
            sb.append(if (first) '?' else '&')
                .append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            first = false
        }
        return sb.toString()
    }

    /** M7:多用户会话非空时,所有请求额外携带 X-Token header(优先于 ?token= 口令)。 */
    private fun Request.Builder.withSession(): Request.Builder {
        val st = ServerConfig.sessionToken
        if (st.isNotBlank()) header("X-Token", st)
        return this
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url(path, query)).withSession().build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code == 401) throw ApiException(
                if (ServerConfig.multiUser) "未登录或登录已过期" else parseErr(body, 401)
            )
            if (!resp.isSuccessful) throw ApiException(parseErr(body, resp.code))
            return body
        }
    }

    private fun post(path: String, json: String, query: Map<String, String> = emptyMap()): String {
        val body: RequestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url(path, query)).post(body).withSession().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (resp.code == 401) throw ApiException(parseErr(text, 401))
            if (!resp.isSuccessful) throw ApiException(parseErr(text, resp.code))
            return text
        }
    }

    /** DELETE 请求(M7 分享管理用)。 */
    private fun del(path: String, query: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url(path, query)).delete().withSession().build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(parseErr(text, resp.code))
            return text
        }
    }

    private fun parseErr(body: String, code: Int): String = try {
        JSONObject(body).optString("error").ifBlank { "HTTP $code" }
    } catch (_: Exception) {
        "HTTP $code"
    }

    private fun JSONArray.toListEntry(): List<Entry> = (0 until length()).mapNotNull { i ->
        val o = optJSONObject(i) ?: return@mapNotNull null
        Entry(o.getString("name"), o.getBoolean("isDir"), o.getLong("size"), o.getLong("modTime"))
    }

    // ---- API ----

    fun health(): Boolean = try {
        val text = get("/api/health")
        JSONObject(text).optBoolean("ok")
    } catch (_: Exception) {
        false
    }

    /** 服务端版本号(读 /api/health)。 */
    fun version(): String = try {
        JSONObject(get("/api/health")).optString("version", "")
    } catch (_: Exception) {
        ""
    }

    fun libs(): List<Library> = try {
        val arr = JSONArray(get("/api/libs"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Library(
                o.getString("id"), o.getString("name"), o.getString("path"),
                o.optBoolean("readOnly"), o.optBoolean("enabled", true),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun ls(vpath: String): LsResp {
        val text = get("/api/ls", mapOf("vpath" to vpath))
        val o = JSONObject(text)
        val lib = o.optJSONObject("lib")
        val entries = o.optJSONArray("entries")?.toListEntry() ?: emptyList()
        val libObj = Library(
            lib?.optString("id") ?: "", lib?.optString("name") ?: "",
            lib?.optString("path") ?: "",
        )
        return LsResp(libObj, o.optString("path"), entries, o.optBoolean("exists"))
    }

    fun rawUrl(vpath: String, download: Boolean = false): String =
        url("/api/raw", buildMap {
            put("vpath", vpath)
            if (download) put("dl", "1")
        })

    /** 转码流地址(avi/rmvb 等):服务端 ffmpeg 边转边发。 */
    fun streamUrl(vpath: String, startSec: Long = 0): String =
        url("/api/stream", buildMap {
            put("vpath", vpath)
            if (startSec > 0) put("t", startSec.toString())
        })

    /** 磁盘/存储库剩余空间。 */
    fun disks(): List<DiskInfo> = try {
        val arr = JSONArray(get("/api/disks"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            DiskInfo(
                id = o.optString("id"),
                name = o.optString("name"),
                path = o.optString("path"),
                freeMB = o.optLong("freeMB"),
                totalMB = o.optLong("totalMB"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ---- 移动 / 复制 / 设置 / 设备 / 保险箱 ----

    fun move(vpaths: List<String>, dest: String) {
        post("/api/move", JSONObject().put("vpaths", JSONArray(vpaths)).put("dest", dest).toString())
    }

    fun copy(vpaths: List<String>, dest: String) {
        post("/api/copy", JSONObject().put("vpaths", JSONArray(vpaths)).put("dest", dest).toString())
    }

    fun settingsInfo(): JSONObject = JSONObject(get(P_SETTINGS))

    fun updateToken(newToken: String) {
        post(P_SETTINGS, JSONObject().put("token", newToken).toString())
    }

    fun devices(): List<Device> = try {
        val arr = JSONArray(get("/api/devices"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Device(o.optString("ip"), o.optString("ua"), o.optLong("first"), o.optLong("last"), o.optInt("count"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun clearDevices() {
        post("/api/devices/clear", "{}")
    }

    fun vaultStatus(): JSONObject = JSONObject(get("/api/vault/status"))

    fun vaultSetup(password: String) {
        post("/api/vault/setup", JSONObject().put("password", password).toString())
    }

    fun vaultUnlock(password: String): String {
        val text = post("/api/vault/unlock", JSONObject().put("password", password).toString())
        return JSONObject(text).optString("token")
    }

    fun vaultLock(vt: String) {
        post("/api/vault/lock", "{}", mapOf("vt" to vt))
    }

    fun vaultList(vt: String): List<VaultEntry> = try {
        val arr = JSONArray(get("/api/vault/ls", mapOf("vt" to vt)))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            VaultEntry(o.optString("name"), o.optLong("size"), o.optLong("modTime"), o.optBoolean("isDir"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun vaultRawUrl(vt: String, name: String, download: Boolean = false): String =
        url("/api/vault/raw", buildMap {
            put("vt", vt); put("name", name)
            if (download) put("dl", "1")
        })

    fun vaultUpload(vt: String, name: String, streamFactory: () -> InputStream, size: Long) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun writeTo(sink: okio.BufferedSink) {
                streamFactory().use { input ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        sink.write(buf, 0, n)
                    }
                }
            }
        }
        val req = Request.Builder()
            .url(url("/api/vault/upload", mapOf("vt" to vt, "name" to name)))
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) throw ApiException(parseErr(text, resp.code))
        }
    }

    fun vaultDelete(vt: String, names: List<String>) {
        post("/api/vault/delete", JSONObject().put("names", JSONArray(names)).toString(), mapOf("vt" to vt))
    }

    /** 保险箱导入:把存储库里的文件/文件夹移入保险箱(dest 为保险箱内相对目录,""=根)。 */
    fun vaultImport(vt: String, vpaths: List<String>, dest: String = "") {
        post("/api/vault/import",
            JSONObject().put("vpaths", JSONArray(vpaths)).put("dest", dest).toString(),
            mapOf("vt" to vt))
    }

    /** 保险箱导出:移出到存储库目录(dest 为虚拟路径)。 */
    fun vaultExport(vt: String, names: List<String>, dest: String) {
        post("/api/vault/export",
            JSONObject().put("names", JSONArray(names)).put("dest", dest).toString(),
            mapOf("vt" to vt))
    }

    // ---- 服务器文件系统目录(添加存储库用) ----

    fun fsDirs(path: String): JSONObject = JSONObject(get("/api/fs/dirs", mapOf("path" to path)))

    fun addLibrary(name: String, path: String, readOnly: Boolean = false): Library {
        val text = post("/api/libs",
            JSONObject().put("name", name).put("path", path).put("readOnly", readOnly).toString())
        val o = JSONObject(text)
        return Library(
            o.optString("id"), o.optString("name"), o.optString("path"),
            o.optBoolean("readOnly"), o.optBoolean("enabled", true),
        )
    }

    /** 缩略图地址。 */
    fun thumbUrl(vpath: String, width: Int = 320): String =
        url("/api/thumb", mapOf("vpath" to vpath, "w" to width.toString()))

    /** 媒体库列表(media3 索引):type = video/image/audio。 */
    fun media(type: String, offset: Int = 0, limit: Int = 200): List<Entry> {
        val text = get("/api/media", mapOf(
            "type" to type, "offset" to offset.toString(), "limit" to limit.toString()))
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                name = o.optString("name"),
                isDir = false,
                size = o.optLong("size"),
                modTime = o.optLong("modTime"),
                vpath = o.optString("path"),
            )
        }
    }

    /** 全库文件名搜索。 */
    fun search(q: String, limit: Int = 100): List<Entry> {
        val text = get("/api/search", mapOf("q" to q, "limit" to limit.toString()))
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Entry(
                name = o.optString("name"),
                isDir = false,
                size = o.optLong("size"),
                modTime = o.optLong("modTime"),
                vpath = o.optString("path"),
            )
        }
    }

    fun mkdir(vpath: String) {
        post("/api/mkdir", JSONObject().put("vpath", vpath).toString())
    }

    fun rename(vpath: String, newName: String) {
        post("/api/rename", JSONObject().put("vpath", vpath).put("newName", newName).toString())
    }

    fun delete(vpath: String) {
        post("/api/delete", JSONObject().put("vpath", vpath).put("recursive", true).toString())
    }

    // ---- M7 多用户认证 ----

    /** GET /api/auth/status → {multiUser, userCount, idleLockMinutes}。 */
    fun authStatus(): JSONObject = JSONObject(get("/api/auth/status"))

    /** POST /api/auth/login;成功把会话写进 ServerConfig。 */
    fun login(username: String, password: String): LoginResp {
        val text = post(
            "/api/auth/login",
            JSONObject().put("username", username).put("password", password).toString()
        )
        val o = JSONObject(text)
        val resp = LoginResp(
            token = o.getString("token"),
            username = o.optString("username", username),
            role = o.optString("role", "user"),
            idleLockMinutes = o.optInt("idleLockMinutes"),
        )
        ServerConfig.sessionToken = resp.token
        ServerConfig.sessionUser = resp.username
        ServerConfig.sessionRole = resp.role
        ServerConfig.multiUser = true
        return resp
    }

    /** GET /api/auth/me → MeResp(未登录时 username 为空)。 */
    fun authMe(): MeResp {
        val o = JSONObject(get("/api/auth/me"))
        return MeResp(
            username = o.optString("username"),
            role = o.optString("role"),
            multiUser = o.optBoolean("multiUser"),
            idleLockMinutes = o.optInt("idleLockMinutes"),
        )
    }

    /** POST /api/auth/logout;成功后清空本地会话。 */
    fun logout() {
        val st = ServerConfig.sessionToken
        if (st.isNotBlank()) {
            try {
                post("/api/auth/logout", "{}")
            } finally {
                Prefs.clearSession()
            }
        } else {
            Prefs.clearSession()
        }
    }

    // ---- M7 回收站 ----

    /** GET /api/trash?lib= → 回收站条目(lib 为空=全部存储库)。 */
    fun trash(lib: String = ""): List<TrashItem> {
        val text = get("/api/trash", if (lib.isBlank()) emptyMap() else mapOf("lib" to lib))
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TrashItem(
                id = o.optString("id"),
                name = o.optString("name"),
                origPath = o.optString("origPath"),
                libId = o.optString("libId"),
                size = o.optLong("size"),
                modTime = o.optLong("modTime"),
                isDir = o.optBoolean("isDir"),
                trashedAt = o.optLong("trashedAt"),
            )
        }
    }

    /** POST /api/trash/restore → 恢复后的目标路径。 */
    fun trashRestore(id: String, lib: String): String {
        val text = post("/api/trash/restore",
            JSONObject().put("id", id).put("lib", lib).toString())
        return JSONObject(text).optString("restored")
    }

    /** POST /api/trash/delete → 彻底删除单个条目。 */
    fun trashDelete(id: String, lib: String) {
        post("/api/trash/delete", JSONObject().put("id", id).put("lib", lib).toString())
    }

    /** POST /api/trash/empty → 清空(可按存储库过滤),返回清理条数。 */
    fun trashEmpty(lib: String = ""): Int {
        val body = if (lib.isBlank()) "{}" else JSONObject().put("lib", lib).toString()
        val text = post("/api/trash/empty", body)
        return JSONObject(text).optInt("count")
    }

    // ---- M7 分享链接 ----

    /** POST /api/shares → {id,url,...}。 */
    fun createShare(vpath: String, expiresHours: Int, password: String, allowDownload: Boolean): Share {
        val text = post("/api/shares", JSONObject()
            .put("vpath", vpath)
            .put("expiresHours", expiresHours)
            .put("password", password)
            .put("allowDownload", allowDownload)
            .toString())
        val o = JSONObject(text)
        return Share(
            id = o.optString("id"),
            vpath = vpath,
            name = o.optString("name"),
            isDir = o.optBoolean("isDir"),
            createdBy = ServerConfig.sessionUser,
            createdAt = System.currentTimeMillis() / 1000,
            expiresAt = o.optLong("expiresAt"),
            hasPassword = o.optBoolean("hasPassword"),
            allowDownload = allowDownload,
        )
    }

    /** GET /api/shares → 分享列表。 */
    fun shares(): List<Share> {
        val arr = JSONArray(get("/api/shares"))
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Share(
                id = o.optString("id"),
                vpath = o.optString("vpath"),
                name = o.optString("name"),
                isDir = o.optBoolean("isDir"),
                createdBy = o.optString("createdBy"),
                createdAt = o.optLong("createdAt"),
                expiresAt = o.optLong("expiresAt"),
                hasPassword = o.optString("passwordSalt").isNotBlank(),
                allowDownload = o.optBoolean("allowDownload"),
            )
        }
    }

    /** DELETE /api/shares?id= → 取消分享。 */
    fun deleteShare(id: String) {
        del("/api/shares", mapOf("id" to id))
    }

    // ---- P1:详情 / 大小 / 字幕 / 日志 ----

    /** GET /api/info?vpath= → 文件或目录详情(目录含递归统计,媒体含 ffprobe 信息)。 */
    fun info(vpath: String): FileInfo {
        val o = JSONObject(get("/api/info", mapOf("vpath" to vpath)))
        return FileInfo(
            name = o.optString("name"),
            path = o.optString("path"),
            isDir = o.optBoolean("isDir"),
            size = o.optLong("size"),
            modTime = o.optLong("modTime"),
            kind = o.optString("kind"),
            ext = o.optString("ext"),
            files = o.optLong("files"),
            dirs = o.optLong("dirs"),
            totalBytes = o.optLong("totalBytes"),
            width = o.optInt("width"),
            height = o.optInt("height"),
            duration = o.optDouble("duration", 0.0),
            bitRate = o.optLong("bitRate"),
            videoCodec = o.optString("videoCodec"),
            audioCodec = o.optString("audioCodec"),
            fps = o.optString("fps").toDoubleOrNull() ?: o.optDouble("fps", 0.0),
        )
    }

    /** GET /api/size?vpath= → 目录递归字节数(文件则为其自身大小);失败返回 -1。 */
    fun dirBytes(vpath: String): Long = try {
        JSONObject(get("/api/size", mapOf("vpath" to vpath))).optLong("bytes")
    } catch (_: Exception) {
        -1L
    }

    /** GET /api/subtitle?vpath= → 同目录可用字幕轨(srt/ass/ssa/vtt)。 */
    fun subtitles(vpath: String): List<SubtitleTrack> = try {
        val arr = JSONArray(get("/api/subtitle", mapOf("vpath" to vpath)))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            SubtitleTrack(
                label = o.optString("label"),
                lang = o.optString("lang"),
                name = o.optString("name"),
                url = o.optString("url"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** /api/subtitle 返回的相对 URL → 播放器可直接使用的绝对地址(带令牌)。 */
    fun absoluteUrl(relativePath: String): String {
        if (relativePath.startsWith("http")) return relativePath
        val base = absBase()
        val tk = effectiveToken()
        if (tk.isBlank()) return base + relativePath
        val sep = if (relativePath.contains('?')) "&" else "?"
        return base + relativePath + sep + "token=" + java.net.URLEncoder.encode(tk, "UTF-8")
    }

    private fun absBase(): String {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        return base
    }

    /** GET /api/audit?limit= → 操作日志(倒序,管理端用)。 */
    fun audit(limit: Int = 100): List<AuditEntry> = try {
        val arr = JSONArray(get("/api/audit", mapOf("limit" to limit.toString())))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            AuditEntry(
                time = o.optLong("time"),
                user = o.optString("user"),
                ip = o.optString("ip"),
                action = o.optString("action"),
                method = o.optString("method"),
                path = o.optString("path"),
                target = o.optString("target"),
                status = o.optInt("status"),
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    // ---- 分块上传(顺序读流,断点续传) ----

    /** 上传中断(暂停/取消)信号。 */
    class UploadInterrupted(val cancelled: Boolean) :
        IOException(if (cancelled) "已取消" else "已暂停")

    /** 上传控制句柄:跨线程设置暂停/取消,回传 uploadId 以便取消时 abort。 */
    class UploadControl {
        @Volatile var paused: Boolean = false
        @Volatile var cancelled: Boolean = false
        @Volatile var uploadId: String? = null
    }

    /** 丢弃未完成的上传会话(取消上传时调用)。 */
    fun abortUpload(uploadId: String) {
        post("/api/upload/abort", JSONObject().put("uploadId", uploadId).toString())
    }

    /** 从本地文件上传。 */
    fun upload(
        source: java.io.File,
        vpathDir: String,
        onProgress: (Long) -> Unit = {},
        control: UploadControl? = null,
    ): String = upload(source.length(), vpathDir, source.name, { java.io.FileInputStream(source) }, onProgress, control)

    /** 从任意输入流上传(ContentResolver 等)。 */
    fun upload(
        streamFactory: () -> InputStream,
        size: Long,
        name: String,
        vpathDir: String,
        onProgress: (Long) -> Unit = {},
        control: UploadControl? = null,
    ): String = upload(size, vpathDir, name, streamFactory, onProgress, control)

    private fun upload(
        size: Long,
        vpathDir: String,
        name: String,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit,
        control: UploadControl? = null,
    ): String {
        val init = JSONObject(
            post("/api/upload/init",
                JSONObject().put("vpath", vpathDir).put("name", name).put("size", size).toString())
        )
        val uploadId = init.getString("uploadId")
        control?.uploadId = uploadId
        var sent = init.getLong("received")
        val chunkSize = init.getLong("chunkSize").takeIf { it > 0 } ?: (5L * 1024 * 1024)

        openStream().use { input ->
            // 续传:跳过已上传字节
            var skipped = 0L
            while (skipped < sent) {
                val n = input.skip(sent - skipped)
                if (n <= 0) break
                skipped += n
            }
            while (sent < size) {
                if (control != null && (control.paused || control.cancelled)) {
                    throw UploadInterrupted(control.cancelled)
                }
                val chunkLen = minOf(chunkSize, size - sent).toInt()
                val buf = ByteArray(chunkLen)
                var off = 0
                while (off < chunkLen) {
                    val n = input.read(buf, off, chunkLen - off)
                    if (n <= 0) throw IOException("读取文件提前结束")
                    off += n
                }
                val body: RequestBody = buf.toRequestBody("application/octet-stream".toMediaType())
                val req = Request.Builder()
                    .url(url("/api/upload/chunk", mapOf("id" to uploadId, "offset" to sent.toString())))
                    .post(body).build()
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string() ?: ""
                    if (!resp.isSuccessful) throw ApiException(parseErr(text, resp.code))
                    sent = JSONObject(text).getLong("received")
                    onProgress(sent)
                }
            }
        }
        if (control != null && control.cancelled) throw UploadInterrupted(true)
        val done = JSONObject(
            post("/api/upload/complete", JSONObject().put("uploadId", uploadId).toString())
        )
        return done.optString("vpath")
    }
}
