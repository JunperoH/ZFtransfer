package io.github.noisetta.nikonpull.data

import io.github.noisetta.nikonpull.ptp.CameraObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadQueueSnapshotTest {
    @Test
    fun createTracksItemCountAndTotalBytes() {
        val queue = DownloadQueueSnapshot.create(
            listOf(media(1L, 1_024L), media(2L, 2_048L)),
        )

        assertEquals(2, queue.totalCount)
        assertEquals(3_072L, queue.totalBytes)
        assertEquals(2, queue.items.size)
        assertEquals(0, queue.processedCount)
    }

    @Test
    fun processedCountIncludesEveryTerminalOutcome() {
        val queue = DownloadQueueSnapshot(
            items = listOf(QueuedDownload(media(4L, 512L))),
            totalCount = 4,
            totalBytes = 4_096L,
            downloadedCount = 1,
            skippedCount = 1,
            failedFilenames = listOf("failed.nef"),
        )

        assertEquals(3, queue.processedCount)
        assertEquals(1, queue.failedCount)
    }

    private fun media(handle: Long, size: Long) = CameraObject(
        handle = handle,
        storageId = 1L,
        format = CameraObject.FORMAT_EXIF_JPEG,
        size = size,
        thumbnailSize = 0L,
        filename = "DSC_$handle.JPG",
        captureDate = "20260803T120000",
    )
}
