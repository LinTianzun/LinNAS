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

// ---------- 服务器配置(单服务器,M2 简化) ----------

object ServerConfig {
    var baseUrl: String
        get() = Prefs.get("base_url", "")
        set(v) = Prefs.set("base_url", v)

    var token: String
        get() = Prefs.get("token", "")
        set(v) = Prefs.set("token", v)
}
