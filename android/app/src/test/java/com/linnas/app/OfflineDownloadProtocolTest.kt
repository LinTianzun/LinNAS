package com.linnas.app

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDownloadProtocolTest {
    @Test
    fun parsesContentRangeAndComputesRangeLength() {
        val range = parseContentRange("bytes 100-199/1000")

        assertEquals(ContentRange(100, 199, 1000), range)
        assertEquals(100L, range?.length)
    }

    @Test
    fun rejectsMalformedOrImpossibleContentRanges() {
        listOf(null, "bytes */1000", "bytes 9-8/1000", "bytes 0-1000/1000", "items 0-9/10")
            .forEach { assertEquals(null, parseContentRange(it)) }
    }

    @Test
    fun validResumeAppendsAtExactOffsetAndUsesServerTotal() {
        val plan = planDownloadResponse(
            statusCode = 206,
            contentRangeHeader = "bytes 100-999/1000",
            contentLength = 900,
            existingBytes = 100,
            declaredSize = 1000,
        )

        assertTrue(plan.append)
        assertEquals(100L, plan.offset)
        assertEquals(900L, plan.expectedBytes)
        assertEquals(1000L, plan.totalBytes)
        assertTrue(plan.isFinalRange)
    }

    @Test
    fun ignoredRangeRestartsFromZeroInsteadOfAppending() {
        val plan = planDownloadResponse(
            statusCode = 200,
            contentRangeHeader = null,
            contentLength = 1000,
            existingBytes = 100,
            declaredSize = 1000,
        )

        assertFalse(plan.append)
        assertEquals(0L, plan.offset)
        assertEquals(1000L, plan.expectedBytes)
        assertEquals(1000L, plan.totalBytes)
    }

    @Test
    fun rejectsMismatchedRangeOffsetAndLength() {
        assertThrows(IOException::class.java) {
            planDownloadResponse(206, "bytes 99-999/1000", 901, 100, 1000)
        }
        assertThrows(IOException::class.java) {
            planDownloadResponse(206, "bytes 100-999/1000", 899, 100, 1000)
        }
    }

    @Test
    fun rejectsUnexpectedStatusesAndNegativeSizes() {
        assertThrows(IOException::class.java) {
            planDownloadResponse(416, "bytes */1000", 0, 1000, 1000)
        }
        assertThrows(IOException::class.java) {
            planDownloadResponse(200, null, 10, -1, 10)
        }
    }
}
