package com.linnas.app

// ---------- 数据模型 ----------

data class Library(
    val id: String,
    val name: String,
    val path: String,
    val readOnly: Boolean = false,
    val enabled: Boolean = true,
)

data class Entry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: Long,
    /** 完整虚拟路径(媒体库/搜索结果为绝对 vpath;目录列表为空,由 basePath+name 拼)。 */
    val vpath: String? = null,
)

data class LsResp(
    val lib: Library,
    val path: String,
    val entries: List<Entry>,
    val exists: Boolean,
)

data class UploadInitResp(
    val uploadId: String,
    val received: Long,
    val chunkSize: Long,
)

data class UploadChunkResp(val received: Long)

data class UploadCompleteResp(
    val vpath: String,
    val name: String,
)

/** 已连接设备(/api/devices)。 */
data class Device(
    val ip: String,
    val ua: String,
    val first: Long,
    val last: Long,
    val count: Int,
)

/** 保险箱条目(/api/vault/ls)。 */
data class VaultEntry(
    val name: String,
    val size: Long,
    val modTime: Long,
    val isDir: Boolean,
)

/** 磁盘/存储库剩余空间(/api/disks)。 */
data class DiskInfo(
    val id: String,
    val name: String,
    val path: String,
    val freeMB: Long,
    val totalMB: Long,
)

// ---------- M7 多用户 / 回收站 / 分享 ----------

/** 登录响应(/api/auth/login)。 */
data class LoginResp(
    val token: String,
    val username: String,
    val role: String,
    val idleLockMinutes: Int,
)

/** 当前会话(/api/auth/me)。 */
data class MeResp(
    val username: String,
    val role: String,
    val multiUser: Boolean,
    val idleLockMinutes: Int,
)

/** 服务器端用户(/api/users)。 */
data class ServerUser(
    val id: String,
    val username: String,
    val role: String,
    val createdAt: Long,
    val disabled: Boolean,
)

/** 回收站条目(/api/trash)。 */
data class TrashItem(
    val id: String,
    val name: String,
    val origPath: String,
    val libId: String,
    val size: Long,
    val modTime: Long,
    val isDir: Boolean,
    val trashedAt: Long,
)

/** 分享链接(/api/shares)。 */
data class Share(
    val id: String,
    val vpath: String,
    val name: String,
    val isDir: Boolean,
    val createdBy: String,
    val createdAt: Long,
    val expiresAt: Long, // 0 = 永久
    val hasPassword: Boolean = false,
    val allowDownload: Boolean = false,
)

/** 操作日志条目(/api/audit)。 */
data class AuditEntry(
    val time: Long,
    val user: String,
    val ip: String,
    val action: String,
    val method: String,
    val path: String,
    val target: String,
    val status: Int,
)

// ---------- P1 详情 / 字幕 ----------

/** 文件或目录详情(/api/info):目录给统计,媒体给 ffprobe 信息。 */
data class FileInfo(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modTime: Long,
    val kind: String,
    val ext: String,
    val files: Long,
    val dirs: Long,
    val totalBytes: Long,
    val width: Int,
    val height: Int,
    val duration: Double,
    val bitRate: Long,
    val videoCodec: String,
    val audioCodec: String,
    val fps: Double,
)

/** 一条可用字幕轨(/api/subtitle)。 */
data class SubtitleTrack(
    val label: String,
    val lang: String,
    val name: String,
    val url: String,
)

// ---------- 服务器配置(单服务器,M2 简化) ----------

object ServerConfig {
    var baseUrl: String
        get() = Prefs.get("base_url", "")
        set(v) = Prefs.set("base_url", v)

    var token: String
        get() = Prefs.get("token", "")
        set(v) = Prefs.set("token", v)

    /** M7 多用户会话 token;非空时请求改带 X-Token header(会话优先于口令)。 */
    var sessionToken: String
        get() = Prefs.get("session_token", "")
        set(v) = Prefs.set("session_token", v)

    /** 当前登录用户名(多用户模式)。 */
    var sessionUser: String
        get() = Prefs.get("session_user", "")
        set(v) = Prefs.set("session_user", v)

    /** 当前登录角色:admin / user / viewer。 */
    var sessionRole: String
        get() = Prefs.get("session_role", "")
        set(v) = Prefs.set("session_role", v)

    /** 是否处于多用户模式(由 /api/auth/status 探测)。 */
    var multiUser: Boolean
        get() = Prefs.getBool("multi_user", false)
        set(v) = Prefs.setBool("multi_user", v)

    /** 清除本地会话(退出登录 / 会话失效时)。 */
    fun clearSession() = Prefs.clearSession()
}
