package com.linnas.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OfflineDownloadWorkerTest {
    @Test
    fun staleTaskCannotOverwriteCurrentDownloadState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val vpath = "/lib1/${UUID.randomUUID()}.bin"
        val current = OfflineStore.Item(
            vpath, "payload.bin", 100, 25, OfflineStore.ST_DOWNLOADING, taskId = "current-task",
        )
        try {
            OfflineStore.put(context, current)

            val updated = OfflineStore.updateIfStatus(
                context,
                current.copy(status = OfflineStore.ST_FAILED, msg = "stale worker"),
                OfflineStore.ST_DOWNLOADING,
                "old-task",
            )

            assertFalse(updated)
            assertEquals(current, OfflineStore.get(context, vpath))
        } finally {
            OfflineStore.remove(context, vpath)
        }
    }

    @Test
    fun downloadsFileAndMarksItDoneOnlyAfterCompleteBody() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val payload = ByteArray(256 * 1024) { (it % 239).toByte() }
        val vpath = "/lib1/${UUID.randomUUID()}.bin"
        val previousBase = ServerConfig.baseUrl
        val previousToken = ServerConfig.token
        val previousSession = ServerConfig.sessionToken
        server.start()
        try {
            Prefs.init(context)
            ServerConfig.baseUrl = server.url("/").toString().trimEnd('/')
            ServerConfig.token = ""
            ServerConfig.sessionToken = ""
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

            OfflineDownloader.start(context, vpath, "payload.bin", payload.size.toLong())
            val result = awaitTerminalState(context, vpath)

            assertEquals(OfflineStore.ST_DONE, result.status)
            assertEquals(payload.size.toLong(), result.bytes)
            val localFile = OfflineStore.localFile(context, vpath)
                ?: throw AssertionError("completed download was not available locally")
            assertArrayEquals(payload, localFile.readBytes())
            assertEquals(null, server.takeRequest().getHeader("Range"))
        } finally {
            OfflineDownloader.cancel(context, vpath)
            ServerConfig.baseUrl = previousBase
            ServerConfig.token = previousToken
            ServerConfig.sessionToken = previousSession
            server.shutdown()
        }
    }

    @Test
    fun resumesFromVerifiedRangeOffset() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val payload = ByteArray(128 * 1024) { (it % 197).toByte() }
        val offset = 37_123
        val vpath = "/lib1/${UUID.randomUUID()}.bin"
        val previousBase = ServerConfig.baseUrl
        val previousToken = ServerConfig.token
        val previousSession = ServerConfig.sessionToken
        server.start()
        try {
            Prefs.init(context)
            ServerConfig.baseUrl = server.url("/").toString().trimEnd('/')
            ServerConfig.token = ""
            ServerConfig.sessionToken = ""
            OfflineStore.put(
                context,
                OfflineStore.Item(vpath, "payload.bin", payload.size.toLong(), offset, OfflineStore.ST_PAUSED),
            )
            OfflineStore.partFor(context, vpath).writeBytes(payload.copyOfRange(0, offset))
            server.enqueue(
                MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Range", "bytes $offset-${payload.lastIndex}/${payload.size}")
                    .setBody(Buffer().write(payload, offset, payload.size - offset)),
            )

            OfflineDownloader.resume(context, vpath, "payload.bin", payload.size.toLong())
            val result = awaitTerminalState(context, vpath)

            assertEquals(OfflineStore.ST_DONE, result.status)
            val localFile = OfflineStore.localFile(context, vpath)
                ?: throw AssertionError("resumed download was not available locally")
            assertArrayEquals(payload, localFile.readBytes())
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        } finally {
            OfflineDownloader.cancel(context, vpath)
            ServerConfig.baseUrl = previousBase
            ServerConfig.token = previousToken
            ServerConfig.sessionToken = previousSession
            server.shutdown()
        }
    }

    @Test
    fun serverIgnoringRangeRestartsInsteadOfAppending() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val server = MockWebServer()
        val payload = ByteArray(96 * 1024) { (it % 181).toByte() }
        val offset = 12_345
        val vpath = "/lib1/${UUID.randomUUID()}.bin"
        val previousBase = ServerConfig.baseUrl
        val previousToken = ServerConfig.token
        val previousSession = ServerConfig.sessionToken
        server.start()
        try {
            Prefs.init(context)
            ServerConfig.baseUrl = server.url("/").toString().trimEnd('/')
            ServerConfig.token = ""
            ServerConfig.sessionToken = ""
            OfflineStore.put(
                context,
                OfflineStore.Item(vpath, "payload.bin", payload.size.toLong(), offset, OfflineStore.ST_PAUSED),
            )
            OfflineStore.partFor(context, vpath).writeBytes(payload.copyOfRange(0, offset))
            server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

            OfflineDownloader.resume(context, vpath, "payload.bin", payload.size.toLong())
            val result = awaitTerminalState(context, vpath)

            assertEquals(OfflineStore.ST_DONE, result.status)
            val localFile = OfflineStore.localFile(context, vpath)
                ?: throw AssertionError("restarted download was not available locally")
            assertArrayEquals(payload, localFile.readBytes())
            assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        } finally {
            OfflineDownloader.cancel(context, vpath)
            ServerConfig.baseUrl = previousBase
            ServerConfig.token = previousToken
            ServerConfig.sessionToken = previousSession
            server.shutdown()
        }
    }

    private suspend fun awaitTerminalState(context: Context, vpath: String): OfflineStore.Item =
        withTimeout(30_000) {
            while (true) {
                val item = OfflineStore.get(context, vpath)
                if (item?.status == OfflineStore.ST_DONE || item?.status == OfflineStore.ST_FAILED) {
                    return@withTimeout item ?: throw AssertionError("download task disappeared")
                }
                delay(100)
            }
            throw AssertionError("unreachable")
        }
}
