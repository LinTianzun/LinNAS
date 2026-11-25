package com.linnas.app

import java.io.IOException

internal data class ContentRange(val start: Long, val end: Long, val total: Long) {
    val length: Long
        get() = end - start + 1
}

internal data class DownloadResponsePlan(
    val append: Boolean,
    val offset: Long,
    val expectedBytes: Long?,
    val totalBytes: Long?,
    val isFinalRange: Boolean,
)

internal fun parseContentRange(value: String?): ContentRange? {
    if (value == null) return null
    val match = Regex("^bytes (\\d+)-(\\d+)/(\\d+)$").matchEntire(value.trim()) ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    val total = match.groupValues[3].toLongOrNull() ?: return null
    if (start > end || end >= total) return null
    return ContentRange(start, end, total)
}

internal fun planDownloadResponse(
    statusCode: Int,
    contentRangeHeader: String?,
    contentLength: Long,
    existingBytes: Long,
    declaredSize: Long,
): DownloadResponsePlan {
    if (existingBytes < 0 || declaredSize < 0) throw IOException("下载大小无效")
    return when (statusCode) {
        200 -> {
            val expected = contentLength.takeIf { it >= 0 }
                ?: declaredSize.takeIf { it > 0 }
            DownloadResponsePlan(
                append = false,
                offset = 0,
                expectedBytes = expected,
                totalBytes = expected,
                isFinalRange = true,
            )
        }
        206 -> {
            val range = parseContentRange(contentRangeHeader)
                ?: throw IOException("服务器返回了无效的 Content-Range")
            if (range.start != existingBytes) {
                throw IOException("续传位置不匹配: 本地 $existingBytes, 服务器 ${range.start}")
            }
            if (contentLength >= 0 && contentLength != range.length) {
                throw IOException("服务器返回的数据长度与 Content-Range 不一致")
            }
            DownloadResponsePlan(
                append = existingBytes > 0,
                offset = existingBytes,
                expectedBytes = range.length,
                totalBytes = range.total,
                isFinalRange = range.end == range.total - 1,
            )
        }
        else -> throw IOException("下载失败 HTTP $statusCode")
    }
}
