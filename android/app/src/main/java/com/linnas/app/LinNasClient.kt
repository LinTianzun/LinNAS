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

    private fun url(path: String, query: Map<String, String> = emptyMap()): String {
        var base = ServerConfig.baseUrl.trimEnd('/')
        if (!base.startsWith("http")) base = "http://$base"
        val sb = StringBuilder(base).append(path)
        var first = true
        val allQuery = query.toMutableMap()
        if (ServerConfig.token.isNotBlank()) allQuery["token"] = ServerConfig.token
        for ((k, v) in allQuery) {
            sb.append(if (first) '?' else '&')
                .append(k).append('=').append(java.net.URLEncoder.encode(v, "UTF-8"))
            first = false
        }
        return sb.toString()
    }

    private fun get(path: String, query: Map<String, String> = emptyMap()): String {
        val req = Request.Builder().url(url(path, query)).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (resp.code == 401) throw ApiException("口令无效")
            if (!resp.isSuccessful) throw ApiException(parseErr(body, resp.code))
            return body
        }
    }

    private fun post(path: String, json: String, query: Map<String, String> = emptyMap()): String {
        val body: RequestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        val req = Request.Builder().url(url(path, query)).post(body).build()
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

    // ---- 分块上传(顺序读流,断点续传) ----

    /** 从本地文件上传。 */
    fun upload(source: java.io.File, vpathDir: String, onProgress: (Long) -> Unit = {}): String =
        upload(source.length(), vpathDir, source.name, { java.io.FileInputStream(source) }, onProgress)

    /** 从任意输入流上传(ContentResolver 等)。 */
    fun upload(
        streamFactory: () -> InputStream,
        size: Long,
        name: String,
        vpathDir: String,
        onProgress: (Long) -> Unit = {},
    ): String = upload(size, vpathDir, name, streamFactory, onProgress)

    private fun upload(
        size: Long,
        vpathDir: String,
        name: String,
        openStream: () -> InputStream,
        onProgress: (Long) -> Unit,
    ): String {
        val init = JSONObject(
            post("/api/upload/init",
                JSONObject().put("vpath", vpathDir).put("name", name).put("size", size).toString())
        )
        val uploadId = init.getString("uploadId")
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
        val done = JSONObject(
            post("/api/upload/complete", JSONObject().put("uploadId", uploadId).toString())
        )
        return done.optString("vpath")
    }
}
